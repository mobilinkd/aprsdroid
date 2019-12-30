package org.aprsdroid.app

import android.Manifest
import _root_.net.ab0oo.aprs.parser.APRSPacket
import _root_.java.io.{InputStream, OutputStream}

object AprsBackend {
	val DEFAULT_CONNTYPE = "tcp"

	val PASSCODE_NONE	= 0
	val PASSCODE_OPTIONAL	= 1
	val PASSCODE_REQUIRED	= 2

	val CAN_RECEIVE		= 1
	val CAN_XMIT		= 2
	val CAN_DUPLEX		= 3

	// "struct" for APRS backend information
	class BackendInfo(
		val create : (AprsService, PrefsWrapper) => AprsBackend,
		val prefxml : Int,
		val permissions : Set[String],
		val duplex : Int,
		val need_passcode : Int
	) {}

	// map from old "backend" to new proto-link-aprsis (defaults are bluetooth and tcp)
	val backend_upgrade = Map(
		"tcp" -> "aprsis-bluetooth-tcp",
		"udp" -> "aprsis-bluetooth-udp",
		"http" -> "aprsis-bluetooth-http",
		"afsk" -> "afsk-bluetooth-tcp",
		"bluetooth" -> "kiss-bluetooth-tcp",
		"kenwood" -> "kenwood-bluetooth-tcp",
		"tcptnc" -> "kiss-tcpip-tcp",
		"usb" -> "kiss-usb-tcp"
		)

	// add your own BackendInfo here
	val backend_collection = Map(
		"udp" -> new BackendInfo(
			(s, p) => new UdpUploader(p),
			R.xml.backend_udp,
			Set(),
			CAN_XMIT,
			PASSCODE_REQUIRED),
		"http" -> new BackendInfo(
			(s, p) => new HttpPostUploader(p),
			R.xml.backend_http,
			Set(),
			CAN_XMIT,
			PASSCODE_REQUIRED),
		"afsk" -> new BackendInfo(
			(s, p) => new AfskUploader(s, p),
			0,
      Set(Manifest.permission.RECORD_AUDIO),
			CAN_DUPLEX,
			PASSCODE_NONE),
		"tcp" -> new BackendInfo(
			(s, p) => new TcpUploader(s, p),
			R.xml.backend_tcp,
			Set(),
			CAN_DUPLEX,
			PASSCODE_OPTIONAL),
		"bluetooth" -> new BackendInfo(
			(s, p) => new BluetoothTnc(s, p),
			R.xml.backend_bluetooth,
			Set(Manifest.permission.BLUETOOTH_ADMIN),
			CAN_DUPLEX,
			PASSCODE_NONE),
		"tcpip" -> new BackendInfo(
			(s, p) => new TcpUploader(s, p),
			R.xml.backend_tcptnc,
			Set(),
			CAN_DUPLEX,
			PASSCODE_NONE),
		"usb" -> new BackendInfo(
			(s, p) => new UsbTnc(s, p),
			R.xml.backend_usb,
			Set(),
			CAN_DUPLEX,
			PASSCODE_NONE)
		)

	class ProtoInfo(
		val create : (AprsService, InputStream, OutputStream) => TncProto,
		val prefxml : Int,
		val link : String
	) {}

	val proto_collection = Map(
		"aprsis" -> new ProtoInfo(
			(s, is, os) => new AprsIsProto(s, is, os),
			R.xml.proto_aprsis, "aprsis"),
		"afsk" -> new ProtoInfo(
			null,
			R.xml.proto_afsk, null),
		"kiss" -> new ProtoInfo(
			(s, is, os) => new KissProto(s, is, os),
			R.xml.proto_kiss, "link"),
		"tnc2" -> new ProtoInfo(
			(s, is, os) => new Tnc2Proto(is, os),
			R.xml.proto_tnc2, "link"),
		"kenwood" -> new ProtoInfo(
			(s, is, os) => new KenwoodProto(s, is, os),
			R.xml.proto_kenwood, "link")
	);
	def defaultProtoInfo(p : String) : ProtoInfo = {
		proto_collection.get(p) match {
		case Some(pi) => pi
		case None => proto_collection("aprsis")
		}
	}
	def defaultProtoInfo(prefs : PrefsWrapper) : ProtoInfo = defaultProtoInfo(prefs.getProto())

	def defaultBackendInfo(prefs : PrefsWrapper) : BackendInfo = {
		val pi = defaultProtoInfo(prefs)
		val link = if (pi.link != null) { prefs.getString(pi.link, "bluetooth") } else { prefs.getProto() }
		backend_collection.get(link) match {
		case Some(bi) => bi
		case None => backend_collection(DEFAULT_CONNTYPE)
		}
	}


	def instanciateUploader(service : AprsService, prefs : PrefsWrapper) : AprsBackend = {
		defaultBackendInfo(prefs).create(service, prefs)
	}
	def instanciateProto(service : AprsService, is : InputStream, os : OutputStream) : TncProto = {
		defaultProtoInfo(service.prefs).create(service, is, os)
	}
	def prefxml_proto(prefs : PrefsWrapper) = {
		defaultProtoInfo(prefs).prefxml
	}
	def prefxml_backend(prefs : PrefsWrapper) = {
		defaultBackendInfo(prefs).prefxml
	}

}

abstract class AprsBackend(prefs : PrefsWrapper) {
	val login = prefs.getLoginString()

	// returns true if successfully started.
	// when returning false, AprsService.postPosterStarted() must be called
	def start() : Boolean

	def update(packet : APRSPacket) : String

	def stop()
}
