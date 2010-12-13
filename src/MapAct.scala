package de.duenndns.aprsdroid

import _root_.android.content.{BroadcastReceiver, Context, Intent, IntentFilter}
import _root_.android.graphics.drawable.{Drawable, BitmapDrawable}
import _root_.android.graphics.{Canvas, Paint, Point, Rect, Typeface}
import _root_.android.os.Bundle
import _root_.android.util.Log
import _root_.com.google.android.maps._

// to make scala-style iterating over arraylist possible
import scala.collection.JavaConversions._

class MapAct extends MapActivity {
	val TAG = "MapAct"

	lazy val mapview = findViewById(R.id.mapview).asInstanceOf[MapView]
	lazy val allicons = this.getResources().getDrawable(R.drawable.allicons)
	lazy val staoverlay = new StationOverlay(allicons)
	lazy val db = StorageDatabase.open(this)

	lazy val locReceiver = new BroadcastReceiver() {
		override def onReceive(ctx : Context, i : Intent) {
			Benchmark("loadDb") {
				staoverlay.loadDb(db)
			}
			mapview.invalidate()
			//postlist.setSelection(0)
		}
	}
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.mapview)
		mapview.setBuiltInZoomControls(true)

		staoverlay.loadDb(db)
		mapview.getOverlays().add(staoverlay)

		// listen for new positions
		registerReceiver(locReceiver, new IntentFilter(AprsService.UPDATE))

	}

	override def onDestroy() {
		super.onDestroy()
		unregisterReceiver(locReceiver)
	}
	override def isRouteDisplayed() = false
}

class Station(val point : GeoPoint, val call : String, val message : String, val symbol : String)
	extends OverlayItem(point, call, message) {


}

class StationOverlay(icons : Drawable) extends ItemizedOverlay[Station](icons) {

	//lazy val calls = new scala.collection.mutable.HashMap[String, Boolean]()
	lazy val stations = new java.util.ArrayList[Station]()

	override def size() = stations.size()
	override def createItem(idx : Int) : Station = stations.get(idx)

	def symbol2rect(symbol : String) : Rect = {
		val alt_offset = if (symbol(0) == '/') 0 else 96
		val index = symbol(1) - 32
		val x = (index / 16) * 16 + alt_offset
		val y = (index % 16) * 16
		new Rect(x, y, x+16, y+16)
	}

	def symbolIsOverlayed(symbol : String) = {
		(symbol(0) != '/' && symbol(0) != '\\')
	}

	override def draw(c : Canvas, m : MapView, shadow : Boolean) : Unit = {
		if (shadow) return;

		val textPaint = new Paint()
		textPaint.setARGB(255, 200, 255, 200)
		textPaint.setTextAlign(Paint.Align.CENTER)
		textPaint.setTextSize(12)
		textPaint.setTypeface(Typeface.MONOSPACE)
		textPaint.setAntiAlias(true)

		val symbPaint = new Paint(textPaint)
		symbPaint.setARGB(255, 255, 255, 255)
		symbPaint.setTextSize(11)

		val strokePaint = new Paint(textPaint)
		strokePaint.setARGB(255, 0, 0, 0)
		strokePaint.setStyle(Paint.Style.STROKE)
		strokePaint.setStrokeWidth(2)

		val symbStrPaint = new Paint(strokePaint)
		symbStrPaint.setTextSize(11)

		val p = new Point()
		for (s <- stations) {
			m.getProjection().toPixels(s.point, p)
			val srcRect = symbol2rect(s.symbol)
			val destRect = new Rect(p.x-8, p.y-8, p.x+8, p.y+8)
			c.drawBitmap(icons.asInstanceOf[BitmapDrawable].getBitmap, srcRect, destRect, null)
			if (symbolIsOverlayed(s.symbol)) {
				c.drawText(s.symbol(0).toString(), p.x, p.y+4, symbStrPaint)
				c.drawText(s.symbol(0).toString(), p.x, p.y+4, symbPaint)
			}
			if (m.getZoomLevel() >= 10) {
				c.drawText(s.call, p.x, p.y+20, strokePaint)
				c.drawText(s.call, p.x, p.y+20, textPaint)
			}
		}
	}

	def loadDb(db : StorageDatabase) {
		stations.clear()
		val c = db.getPositions(null, null, null)
		c.moveToFirst()
		while (!c.isAfterLast()) {
			val call = c.getString(c.getColumnIndexOrThrow(StorageDatabase.Position.CALL))
			val symbol = c.getString(c.getColumnIndexOrThrow(StorageDatabase.Position.SYMBOL))
			val comment = c.getString(c.getColumnIndexOrThrow(StorageDatabase.Position.COMMENT))
			val lat = c.getInt(c.getColumnIndexOrThrow(StorageDatabase.Position.LAT))
			val lon = c.getInt(c.getColumnIndexOrThrow(StorageDatabase.Position.LON))
			addStation(new Station(new GeoPoint(lat, lon), call, comment, symbol))
			c.moveToNext()
		}
		c.close()
		setLastFocusedIndex(-1)
		populate()
		Log.d("StationOverlay", "total %d items".format(size()))
	}

	def addStation(sta : Station) {
		//if (calls.contains(sta.getTitle()))
		//	return
		//calls.add(sta.getTitle(), true)
		stations.add(sta)
	}

	def addStation(post : String) {
		try {
			val (call, lat, lon, sym, comment) = AprsPacket.parseReport(post)
			Log.d("StationOverlay", "got %s(%d, %d)%s -> %s".format(call, lat, lon, sym, comment))
			addStation(new Station(new GeoPoint(lat, lon), call, comment, sym))
		} catch {
		case e : Exception =>
			Log.d("StationOverlay", "bad " + post)
		}
	}
}
