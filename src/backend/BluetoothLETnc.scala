package org.aprsdroid.app

import _root_.android.bluetooth._
import _root_.android.util.Log

import _root_.java.util.UUID
import _root_.android.bluetooth.BluetoothGattCharacteristic
import _root_.android.bluetooth.BluetoothGattCallback
import _root_.android.bluetooth.BluetoothGatt
import _root_.android.bluetooth.BluetoothDevice
import _root_.net.ab0oo.aprs.parser._
import android.os.Build

import java.io._

class BluetoothLETnc(service : AprsService, prefs : PrefsWrapper) extends AprsBackend(prefs) {
	private val TAG = "APRSdroid.BluetoothLE"

	private val SERVICE_UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb")
	private val CHARACTERISTIC_UUID_RX = UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb")
	private val CHARACTERISTIC_UUID_TX = UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb")

	private val tncmac = prefs.getString("ble.mac", null)
	private var gatt: BluetoothGatt = null
	private var tncDevice: BluetoothDevice = null
	private var txCharacteristic: BluetoothGattCharacteristic = null
	private var rxCharacteristic: BluetoothGattCharacteristic = null

	private var proto: TncProto = _

	private val bleInputStream = new BLEInputStream()
	private val bleOutputStream = new BLEOutputStream()

	private var conn : BLEReceiveThread = null

	override def start(): Boolean = {
		if (gatt == null)
			createConnection()
		false
	}

	private def connect(): Unit = {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			gatt = tncDevice.connectGatt(service, false, callback, BluetoothDevice.TRANSPORT_LE)
		} else {
			// Dual-mode devices are not supported
			gatt = tncDevice.connectGatt(service, false, callback)
		}
	}

	private val callback = new BluetoothGattCallback {
		override def onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int): Unit = {
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				Log.d(TAG, "Connected to GATT server")
				gatt.discoverServices()
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				Log.d(TAG, "Disconnected from GATT server")
				service.postAbort(service.getString(R.string.bt_error_connect))
			}
		}

		override def onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int): Unit = {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				Log.d(TAG, "Descriptor written successfully")
				gatt.requestMtu(128)
			} else {
				Log.e(TAG, "Failed to write descriptor")
			}
		}

		override def onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int): Unit = {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				Log.d(TAG, s"MTU changed to $mtu bytes")
			} else {
				Log.e(TAG, "Failed to change MTU")
			}
		}

		override def onServicesDiscovered(gatt: BluetoothGatt, status: Int): Unit = {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				val gservice = gatt.getService(SERVICE_UUID)
				if (gservice != null) {
					txCharacteristic = gservice.getCharacteristic(CHARACTERISTIC_UUID_TX)
					rxCharacteristic = gservice.getCharacteristic(CHARACTERISTIC_UUID_RX)

					gatt.setCharacteristicNotification(rxCharacteristic, true)
					val descriptor = rxCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
					if (descriptor != null) {
						descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
						gatt.writeDescriptor(descriptor)
					}

					proto = AprsBackend.instanciateProto(service, bleInputStream, bleOutputStream)

					Log.d(TAG, "Services discovered and characteristics set")
				} else {
					Log.d(TAG, "Service not found!")
				}
			} else {
				Log.d(TAG, "onServicesDiscovered received: " + status)
			}
		}

		override def onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int): Unit = {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				Log.d(TAG, "Characteristic write successful")
			} else {
				Log.d(TAG, "Characteristic write failed with status: " + status)
			}

			bleOutputStream.isWaitingForAck = false
			bleOutputStream.sendNextChunk()
		}

		override def onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
			Log.d(TAG, "Characteristics changed")

			val data = characteristic.getValue

			Log.d(TAG, "Received: " + data.length + " bytes from BLE");

			bleInputStream.active = false

			bleInputStream.appendData(data);

			if (data.length != 64) {
				bleInputStream.active = true
			}
		}
	}

	private def createConnection(): Unit = {
		Log.d(TAG, "BluetoothTncBle.createConnection: " + tncmac)
		val adapter = BluetoothAdapter.getDefaultAdapter

		if (adapter == null) {
			service.postAbort(service.getString(R.string.bt_error_unsupported))
			return
		}

		if (!adapter.isEnabled) {
			service.postAbort(service.getString(R.string.bt_error_disabled))
			return
		}

		if (tncmac == null) {
			service.postAbort(service.getString(R.string.bt_error_no_tnc))
			return
		}

		tncDevice = BluetoothAdapter.getDefaultAdapter.getRemoteDevice(tncmac)
		if (tncDevice == null) {
			service.postAbort(service.getString(R.string.bt_error_no_tnc))
			return
		}

		connect()

		conn = new BLEReceiveThread()
		conn.start()
	}

	override def update(packet: APRSPacket): String = {
		try {
			proto.writePacket(packet)
			"BLE OK"
		} catch {
			case e: Exception =>
				e.printStackTrace()
				gatt.disconnect()
				"BLE disconnected"
		}
	}

	private def sendToBle(data: Array[Byte]): Unit = {
		if (txCharacteristic != null && gatt != null) {
			txCharacteristic.setValue(data)
			gatt.writeCharacteristic(txCharacteristic)
		}
	}

	override def stop(): Unit = {
		if (gatt == null)
			return
			
		gatt.disconnect()
		gatt.close()
		gatt = null
		
		conn.synchronized {
			conn.running = false
		}
		conn.shutdown()
		conn.interrupt()
		conn.join(50)
	}

	private class BLEReceiveThread extends Thread("APRSdroid Bluetooth connection") {
		private val TAG = "APRSdroid.BLEReceiveThread"
		var running = true

		override def run(): Unit = {
			running = true
			Log.d(TAG, "BLEReceiveThread.run()")

			while (running) {
				try {
					// Log.d(TAG, "waiting for data...")
					while (running) {
						val line = proto.readPacket()
						Log.d(TAG, "recv: " + line)
						service.postSubmit(line)
					}
				} catch {
					case e : Exception => 
						Log.d("ProtoTNC", "proto.readPacket exception")
				}
			}
			Log.d(TAG, "BLEReceiveThread.terminate()")
		}

		def shutdown(): Unit = {
			Log.d(TAG, "shutdown()")
		}
	}

	private class BLEInputStream extends InputStream {
		private var buffer: Array[Byte] = Array()
		var active = false

		def appendData(data: Array[Byte]): Unit = {
			buffer ++= data
		}

		override def read(): Int = {
			if (buffer.isEmpty || !active) -1
			else {
				val byte = buffer.head
				buffer = buffer.tail
				byte & 0xFF
			}
		}

		override def read(b: Array[Byte], off: Int, len: Int): Int = {
			if (!active) -1
			else {
				val available = math.min(len, buffer.length)
				System.arraycopy(buffer, 0, b, off, available)
				buffer = buffer.drop(available)
				available
			}
		}
	}

	private class BLEOutputStream extends OutputStream {
		private var buffer: Array[Byte] = Array()
		private val mtuSize = 64
		var isWaitingForAck = false
		
		override def write(b: Int): Unit = {
			Log.d(TAG, "Got data (byte): " + b.toByte)

			buffer ++= Array(b.toByte)
		}

		override def write(b: Array[Byte], off: Int, len: Int): Unit = {
			val data = b.slice(off, off + len)
			Log.d(TAG, "Got data: " + data)

			buffer ++= data
		}

		override def flush(): Unit = {
			Log.d(TAG, "Flushed. Send to BLE")

			if (!isWaitingForAck) {
				sendNextChunk()
			}
		}

		def sendNextChunk(): Unit = {
			if (buffer.nonEmpty) {
				val chunk = buffer.take(mtuSize)
				buffer = buffer.drop(mtuSize)

				Log.d(TAG, s"Sending ${chunk.length} bytes to BLE")

				sendToBle(chunk)

				isWaitingForAck = true
			} else {
				Log.d(TAG, "No more data to send.")
			}
		}
	}
}
