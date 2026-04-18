package com.teslcan.app

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AdManager(private val context: Context) {

    companion object {
        private const val TAG = "AdManager"
        private const val ADS_URL =
            "https://raw.githubusercontent.com/kangbumhee/TLA_MAP/main/ads.json"
        private const val PREFS = "ad_prefs"
        private const val STATS_URL_BASE = "https://script.google.com/macros/s/AKfycbxyH5u3oUY4uaaRgCPyHeaZNB7577gEVdQU19xwSAaakMcu0NjpCrD0s6wi7EDuwOsy6w/exec"
    }

    data class PeriodicAd(
        val id: String,
        val message: String,
        val intervalMin: Int,
        val startHour: Int,
        val endHour: Int,
        val enabled: Boolean,
        val priority: Int
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var isMuted = false

    private var startupMessage = ""
    private var startupEnabled = false
    private val periodicAds = mutableListOf<PeriodicAd>()
    private var refreshIntervalSec = 300L
    private var adsVersion = 0

    private val lastPlayedMs = mutableMapOf<String, Long>()

    var onAdPlayed: ((String, String) -> Unit)? = null

    private var periodicCheckPosted = false
    private var refreshPosted = false

    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            checkAndPlayPeriodic()
            handler.postDelayed(this, 30_000L)
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchAdsAsync()
            handler.postDelayed(this, refreshIntervalSec * 1000L)
        }
    }

    fun init(ttsEngine: TextToSpeech) {
        tts = ttsEngine
        loadCached()
        fetchAdsAsync()
        if (!periodicCheckPosted) {
            periodicCheckPosted = true
            handler.postDelayed(periodicCheckRunnable, 60_000L)
        }
        if (!refreshPosted) {
            refreshPosted = true
            handler.postDelayed(refreshRunnable, refreshIntervalSec * 1000L)
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun playStartup(onDone: () -> Unit) {
        if (!startupEnabled || startupMessage.isBlank() || isMuted) {
            handler.post(onDone)
            return
        }
        speakQueued(startupMessage, "startup_ad")
        reportStat("startup", startupMessage)
        onAdPlayed?.invoke("startup", startupMessage)
        val delay = 2000L + startupMessage.length * 80L
        handler.postDelayed(onDone, delay)
    }

    private fun speakQueued(text: String, utteranceId: String) {
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.9f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    private fun checkAndPlayPeriodic() {
        if (isMuted) return

        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        val eligible = periodicAds.filter { ad ->
            ad.enabled && isInTimeRange(hour, ad.startHour, ad.endHour)
        }

        val ready = eligible.filter { ad ->
            val last = lastPlayedMs[ad.id] ?: 0L
            (now - last) >= ad.intervalMin * 60_000L
        }.sortedBy { it.priority }

        val ad = ready.firstOrNull() ?: return

        speakQueued(ad.message, "periodic_${ad.id}")
        lastPlayedMs[ad.id] = now
        reportStat(ad.id, ad.message)
        onAdPlayed?.invoke(ad.id, ad.message)
        Log.d(TAG, "[AD] ${ad.id}: ${ad.message}")
    }

    private fun isInTimeRange(hour: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            if (end >= 24) hour >= start && hour <= 23
            else hour >= start && hour < end
        } else {
            hour >= start || hour < end
        }
    }

    private fun fetchAdsAsync() {
        Thread {
            try {
                val conn = URL(ADS_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                if (conn.responseCode != 200) {
                    Log.w(TAG, "[AD] HTTP ${conn.responseCode}")
                    return@Thread
                }
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                parseAds(text)
                prefs.edit().putString("cached_ads", text).apply()
                Log.d(TAG, "[AD] 업데이트 완료 v$adsVersion, ${periodicAds.size}개")
            } catch (e: Exception) {
                Log.w(TAG, "[AD] 다운로드 실패: ${e.message}")
            }
        }.start()
    }

    private fun loadCached() {
        val cached = prefs.getString("cached_ads", null) ?: return
        try {
            parseAds(cached)
        } catch (_: Exception) {
        }
    }

    private fun parseAds(json: String) {
        val obj = JSONObject(json)
        adsVersion = obj.optInt("version", 0)
        refreshIntervalSec = obj.optLong("refreshIntervalSec", 300L).coerceAtLeast(60L)

        val su = obj.optJSONObject("startup")
        if (su != null) {
            startupEnabled = su.optBoolean("enabled", false)
            startupMessage = su.optString("message", "")
        }

        periodicAds.clear()
        val arr = obj.optJSONArray("periodic") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            periodicAds.add(
                PeriodicAd(
                    id = a.optString("id", "ad_$i"),
                    message = a.optString("message", ""),
                    intervalMin = a.optInt("intervalMin", 60),
                    startHour = a.optInt("startHour", 0),
                    endHour = a.optInt("endHour", 24),
                    enabled = a.optBoolean("enabled", true),
                    priority = a.optInt("priority", 5)
                )
            )
        }
    }

    private fun reportStat(adId: String, message: String) {
        if (STATS_URL_BASE.isBlank()) return
        Thread {
            try {
                val devHash = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ).hashCode().toString(16)
                val url = "$STATS_URL_BASE?ad=$adId&dev=$devHash" +
                    "&app=${context.packageName}&t=${System.currentTimeMillis()}"
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    inputStream.bufferedReader().close()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    fun destroy() {
        handler.removeCallbacks(periodicCheckRunnable)
        handler.removeCallbacks(refreshRunnable)
        periodicCheckPosted = false
        refreshPosted = false
    }
}
