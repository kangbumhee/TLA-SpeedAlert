package com.teslcan.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LogEvent(
    val time: Long,
    val type: String,
    val camType: Int,
    val speedKmh: Int,
    val limitKmh: Int,
    val distance: Int,
    val lat: Double,
    val lon: Double
)

class EventLog(private val context: Context) {

    private val prefs = context.getSharedPreferences("camalert_log", Context.MODE_PRIVATE)
    private val maxEvents = 200

    fun add(e: LogEvent) {
        val arr = loadArray()
        val obj = JSONObject().apply {
            put("t", e.time)
            put("type", e.type)
            put("ct", e.camType)
            put("sp", e.speedKmh)
            put("lim", e.limitKmh)
            put("d", e.distance)
            put("la", e.lat)
            put("lo", e.lon)
        }
        arr.put(obj)
        while (arr.length() > maxEvents) arr.remove(0)
        prefs.edit().putString("log", arr.toString()).apply()
    }

    fun getAll(): List<LogEvent> {
        val arr = loadArray()
        val list = mutableListOf<LogEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                LogEvent(
                    time = o.getLong("t"),
                    type = o.getString("type"),
                    camType = o.getInt("ct"),
                    speedKmh = o.getInt("sp"),
                    limitKmh = o.getInt("lim"),
                    distance = o.getInt("d"),
                    lat = o.getDouble("la"),
                    lon = o.getDouble("lo")
                )
            )
        }
        return list.reversed()
    }

    fun clear() {
        prefs.edit().remove("log").apply()
    }

    private fun loadArray(): JSONArray {
        val s = prefs.getString("log", null) ?: return JSONArray()
        return try {
            JSONArray(s)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
