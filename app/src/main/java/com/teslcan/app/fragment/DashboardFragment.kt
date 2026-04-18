package com.teslcan.app.fragment

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.teslcan.app.AlertInfo
import com.teslcan.app.MainActivity
import com.teslcan.app.R
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * TLA SpeedAlert: Leaflet + KNSafetyCode 스타일 camConfig + 주변 마커.
 */
class DashboardFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvGps: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvSpeedUnit: TextView
    private lateinit var tvLimit: TextView
    private lateinit var tvAlertTitle: TextView
    private lateinit var tvAlertDistance: TextView
    private lateinit var tvCamType: TextView
    private lateinit var progressDistance: ProgressBar
    private lateinit var btnMute: Button
    private lateinit var mapView: WebView
    private lateinit var alertCard: View
    private lateinit var limitSign: View
    private var mapReady = false

    private var lastUiPhase: Int = 0

    private var lastCamUpdateLat = 0.0
    private var lastCamUpdateLon = 0.0
    private val CAM_UPDATE_THRESHOLD = 100.0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_dashboard, container, false)
        tvStatus = v.findViewById(R.id.tvStatus)
        tvGps = v.findViewById(R.id.tvGps)
        tvSpeed = v.findViewById(R.id.tvSpeed)
        tvSpeedUnit = v.findViewById(R.id.tvSpeedUnit)
        tvLimit = v.findViewById(R.id.tvLimit)
        tvAlertTitle = v.findViewById(R.id.tvAlertTitle)
        tvAlertDistance = v.findViewById(R.id.tvAlertDistance)
        tvCamType = v.findViewById(R.id.tvCamType)
        progressDistance = v.findViewById(R.id.progressDistance)
        btnMute = v.findViewById(R.id.btnMute)
        mapView = v.findViewById(R.id.mapView)
        alertCard = v.findViewById(R.id.alertCard)
        limitSign = v.findViewById(R.id.limitSign)

        setupMap()
        btnMute.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            act.bleService?.alertPlayer?.muteFor(60_000)
            act.setAdsMuted(true)
            btnMute.text = "1분 음소거 중"
            btnMute.postDelayed({
                btnMute.text = "음소거 (1분)"
                val stillMuted = act.bleService?.alertPlayer?.isMuted() == true
                act.setAdsMuted(stillMuted)
            }, 60_000)
        }
        return v
    }

    override fun onResume() {
        super.onResume()
        if (view != null) bindBleCallbacks()
    }

    private fun bindBleCallbacks() {
        val act = activity as? MainActivity ?: return
        act.whenServiceReady { svc ->
            svc.onConnectionChanged = { connected ->
                activity?.runOnUiThread {
                    tvStatus.text = if (connected) "● 연결됨" else "○ 검색 중..."
                    tvStatus.setTextColor(
                        if (connected) Color.parseColor("#39D353")
                        else Color.parseColor("#FF6600")
                    )
                }
            }
            svc.onSpeedUpdate = { sp ->
                activity?.runOnUiThread {
                    tvSpeed.text = "$sp"
                    val limit = tvLimit.text.toString().toIntOrNull() ?: 0
                    val over = limit > 0 && sp > limit + svc.settings.overSpeedThreshold
                    tvSpeed.setTextColor(
                        if (over) Color.parseColor("#FF4444") else Color.WHITE
                    )
                }
            }
            svc.onGpsUpdate = { sats, fix ->
                activity?.runOnUiThread {
                    tvGps.text = if (fix) "GPS $sats" else "GPS 검색"
                    tvGps.setTextColor(
                        if (fix) Color.parseColor("#39D353")
                        else Color.parseColor("#FF6600")
                    )
                }
            }
            svc.onLocationUpdate = { lat, lon ->
                if (mapReady) {
                    activity?.runOnUiThread {
                        mapView.evaluateJavascript("updateLocation($lat,$lon);", null)
                        val moved = distanceFast(lat, lon, lastCamUpdateLat, lastCamUpdateLon)
                        if (moved > CAM_UPDATE_THRESHOLD || lastCamUpdateLat == 0.0) {
                            lastCamUpdateLat = lat
                            lastCamUpdateLon = lon
                            updateNearbyCameras(lat, lon)
                        }
                    }
                }
            }
            svc.onAlertUpdate = { info ->
                activity?.runOnUiThread { updateAlertUI(info) }
            }
        }
    }

    private fun updateNearbyCameras(lat: Double, lon: Double) {
        val act = activity as? MainActivity ?: return
        val svc = act.bleService ?: return
        val db = svc.getCameraDb() ?: return
        val cameras = db.findNearbyForMap(lat, lon, 1500)
        mapView.evaluateJavascript("clearNearby();", null)
        for (cam in cameras) {
            val h = cam.direction?.toInt() ?: -1
            val js =
                "addNearbyCamera(${cam.lat},${cam.lon},${cam.speedLimit},${cam.safetyCode.code},$h);"
            mapView.evaluateJavascript(js, null)
        }
        Log.d("MapJS", "updateNearbyCameras: ${cameras.size}개 (${lat.format(5)},${lon.format(5)})")
    }

    private fun Double.format(d: Int) = "%.${d}f".format(this)

    private fun distanceFast(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 111320.0
        val dLon = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    private fun roundedAlertMeters(d: Int): Int = ((d + 50) / 100) * 100

    private fun updateAlertUI(info: AlertInfo) {
        if (info.phase == 0) {
            lastUiPhase = 0
            if (mapReady) mapView.evaluateJavascript("hideCamera();", null)
            alertCard.setBackgroundColor(Color.parseColor("#161B22"))
            tvAlertTitle.text = "주행 중"
            tvAlertTitle.setTextColor(Color.parseColor("#8B949E"))
            tvAlertDistance.text = ""
            tvCamType.text = ""
            progressDistance.progress = 0
            tvLimit.text = ""
            limitSign.visibility = View.INVISIBLE
            return
        }

        val displayMeters = roundedAlertMeters(info.distance)

        if (info.phase == -1) {
            tvAlertDistance.text = "${displayMeters}m"
            val d1 = 1100
            val pct = ((1f - displayMeters.toFloat() / d1) * 100).toInt().coerceIn(0, 100)
            progressDistance.progress = pct
            if (info.speedLimit > 0) {
                tvLimit.text = "${info.speedLimit}"
                limitSign.visibility = View.VISIBLE
            }
            val displayPhase = if (lastUiPhase in 1..4) lastUiPhase else 2
            applyPhaseColors(displayPhase, info)
            return
        }

        if (info.phase in 1..4) lastUiPhase = info.phase

        if (mapReady && info.phase in 1..4 && info.camLat != 0.0 && info.camLon != 0.0) {
            val os = if (info.overspeed) 1 else 0
            mapView.evaluateJavascript(
                "showCamera(${info.camLat},${info.camLon},${info.safetyCode.code},${info.speedLimit},${info.distance},${info.phase},$os);",
                null
            )
        }

        val bg = when (info.phase) {
            1 -> "#1A2B1A"
            2 -> "#2D2000"
            3 -> "#2D1500"
            4 -> if (info.overspeed) "#3D0000" else "#2D0A00"
            else -> "#161B22"
        }
        alertCard.setBackgroundColor(Color.parseColor(bg))

        val titleColor = when (info.phase) {
            1 -> "#39D353"
            2 -> "#FFFF00"
            3 -> "#FF8800"
            4 -> "#FF4444"
            else -> "#8B949E"
        }
        tvAlertTitle.setTextColor(Color.parseColor(titleColor))
        tvAlertTitle.text = when {
            info.isSection && info.sectionAvgSpeed > 0 ->
                "구간 평균 ${info.sectionAvgSpeed} km/h"
            info.overspeed && info.phase >= 3 -> "감속하세요"
            else -> "전방 단속"
        }

        tvCamType.text = "「${info.safetyCode.label}」"
        tvAlertDistance.text = "${displayMeters}m"

        val d1 = 1100
        val pct = ((1f - displayMeters.toFloat() / d1) * 100).toInt().coerceIn(0, 100)
        progressDistance.progress = pct

        if (info.speedLimit > 0) {
            tvLimit.text = "${info.speedLimit}"
            limitSign.visibility = View.VISIBLE
        } else {
            tvLimit.text = ""
            limitSign.visibility = View.INVISIBLE
        }
    }

    private fun applyPhaseColors(displayPhase: Int, info: AlertInfo) {
        val bg = when (displayPhase) {
            1 -> "#1A2B1A"
            2 -> "#2D2000"
            3 -> "#2D1500"
            4 -> if (info.overspeed) "#3D0000" else "#2D0A00"
            else -> "#161B22"
        }
        alertCard.setBackgroundColor(Color.parseColor(bg))
        val titleColor = when (displayPhase) {
            1 -> "#39D353"
            2 -> "#FFFF00"
            3 -> "#FF8800"
            4 -> "#FF4444"
            else -> "#8B949E"
        }
        tvAlertTitle.setTextColor(Color.parseColor(titleColor))
        tvAlertTitle.text = if (info.overspeed && displayPhase >= 3) "감속하세요" else "전방 단속"
        tvCamType.text = "「${info.safetyCode.label}」"
    }

    private fun setupMap() {
        mapView.settings.javaScriptEnabled = true
        mapView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg?.let { Log.d("MapJS", "${it.sourceId()}:${it.lineNumber()} ${it.message()}") }
                return true
            }
        }
        mapView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapReady = true
            }
        }

        val html = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
body{margin:0;background:#0D1117}
#map{width:100%;height:100vh}
.cam-div-icon{background:transparent!important;border:none!important;}
.cam-nearby{background:transparent!important;border:none!important;}
</style>
</head><body><div id="map"></div>
<script>
var map=L.map('map',{zoomControl:false,attributionControl:false}).setView([37.5,127.0],15);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(map);
var marker=null;
var camMarker=null;
var alertLine=null;
var lastCamKey=null;

var kakaoCfg={
  82:{color:'#FF4444',icon:'\uD83D\uDCF7',label:'\uACE0\uC815'},
  81:{color:'#FF8800',icon:'\uD83D\uDCF7',label:'\uC774\uB3D9'},
  86:{color:'#FF4444',icon:'\uD83D\uDEA6',label:'\uC2E0\uD638+\uACFC\uC18D'},
  90:{color:'#FFAA00',icon:'\uD83D\uDEA6',label:'\uC2E0\uD638'},
  92:{color:'#AA44FF',icon:'\u23F1',label:'\uAD6C\uAC04\uC2DC'},
  93:{color:'#AA44FF',icon:'\u23F1',label:'\uAD6C\uAC04\uC885'},
  96:{color:'#9933CC',icon:'\u23F1',label:'\uAD6C\uAC04'},
  87:{color:'#3388ff',icon:'\uD83C\uDD7F',label:'\uC8FC\uC815\uCC28'},
  84:{color:'#3388ff',icon:'\uD83D\uDE8C',label:'\uBC84\uC2A4'},
  100:{color:'#FF4444',icon:'\uD83D\uDCE6',label:'\uBC15\uC2A4'},
  11:{color:'#00CC00',icon:'\uD83C\uDFEB',label:'\uC2A4\uCFE8'}
};
var legacyCfg={
  0:{color:'#FF4444',icon:'\u26A1',label:'\uACFC\uC18D'},
  1:{color:'#FF4444',icon:'\uD83D\uDCF7',label:'\uACE0\uC815'},
  2:{color:'#4488FF',icon:'\uD83D\uDCE6',label:'\uC774\uB3D9'},
  3:{color:'#FF8800',icon:'\u23F1',label:'\uAD6C\uAC04'},
  4:{color:'#FFDD00',icon:'\uD83D\uDEA6',label:'\uC2E0\uD638'},
  5:{color:'#4488FF',icon:'\uD83D\uDE8C',label:'\uBC84\uC2A4'},
  6:{color:'#39D353',icon:'\uD83C\uDFEB',label:'\uC5B4\uB9B0\uC774'}
};
function pickCfg(code){
  var k=parseInt(code,10);
  if(kakaoCfg[k]) return kakaoCfg[k];
  if(legacyCfg[k]) return legacyCfg[k];
  return legacyCfg[0];
}

function showCamera(lat,lon,code,limit,dist,phase,overspeed){
  if(camMarker) map.removeLayer(camMarker);
  if(alertLine) map.removeLayer(alertLine);
  var cfg=pickCfg(code);
  var borderColor=overspeed?'#FF0000':cfg.color;
  var size=phase>=3?40:32;
  var html='<div style="width:'+size+'px;height:'+size+'px;background:'+cfg.color+';border-radius:50%;border:3px solid '+borderColor+';display:flex;align-items:center;justify-content:center;color:#fff;font-weight:bold;font-size:'+(phase>=3?16:13)+'px">'+(limit>0?String(limit):'\u2013')+'</div>';
  var icon=L.divIcon({html:html,iconSize:[size,size],iconAnchor:[size/2,size/2],className:'cam-div-icon'});
  camMarker=L.marker([lat,lon],{icon:icon}).addTo(map);
  lastCamKey=lat+','+lon;
  if(marker){
    alertLine=L.polyline([marker.getLatLng(),[lat,lon]],{color:overspeed?'#FF0000':'#FFAA00',weight:3,dashArray:'8,6'}).addTo(map);
    map.fitBounds(L.latLngBounds([marker.getLatLng(),[lat,lon]]),{padding:[50,50],maxZoom:16});
  }
}
function hideCamera(){
  lastCamKey=null;
  if(camMarker){map.removeLayer(camMarker);camMarker=null;}
  if(alertLine){map.removeLayer(alertLine);alertLine=null;}
}
function updateLine(){
  if(alertLine&&marker&&camMarker) alertLine.setLatLngs([marker.getLatLng(),camMarker.getLatLng()]);
}
function updateLocation(lat,lon){
  if(!marker) marker=L.circleMarker([lat,lon],{radius:9,color:'#39D353',fillColor:'#39D353',fillOpacity:0.9}).addTo(map);
  else marker.setLatLng([lat,lon]);
  if(!camMarker) map.setView([lat,lon],16);
  updateLine();
}
var nearbyMarkers=[];
function clearNearby(){
  for(var i=0;i<nearbyMarkers.length;i++) map.removeLayer(nearbyMarkers[i]);
  nearbyMarkers=[];
}
function addNearbyCamera(lat,lon,speedLimit,safetyCode,heading){
  var cfg=pickCfg(safetyCode);
  var sl=parseInt(speedLimit,10);
  console.log('addNearbyCamera code='+safetyCode+' limit='+sl+' h='+heading);
  if(sl>0){
    var icon=L.divIcon({
      className:'cam-nearby',
      html:'<div style="text-align:center;">'
        +'<div style="background:'+cfg.color+';color:#fff;border-radius:50%;width:28px;height:28px;display:flex;align-items:center;justify-content:center;font-weight:bold;font-size:11px;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,0.5);">'+sl+'</div>'
        +'<div style="font-size:8px;color:'+cfg.color+';text-shadow:0 1px 2px #000;margin-top:1px;white-space:nowrap;">'+cfg.icon+' '+cfg.label+'</div></div>',
      iconSize:[32,38],iconAnchor:[16,19]
    });
    nearbyMarkers.push(L.marker([lat,lon],{icon:icon,interactive:false}).addTo(map));
  } else {
    nearbyMarkers.push(L.circleMarker([lat,lon],{radius:5,color:'#3388ff',fillColor:'#3388ff',fillOpacity:0.7,weight:1,interactive:false}).addTo(map));
  }
}
</script></body></html>
        """.trimIndent()

        mapView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
    }
}
