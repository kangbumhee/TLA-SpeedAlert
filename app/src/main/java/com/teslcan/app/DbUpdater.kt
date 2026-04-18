package com.teslcan.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DbUpdater(
    private val context: Context,
    private val db: CameraDatabase
) {
    companion object {
        private const val TAG = "DbUpdater"
        private const val VERSION_URL =
            "https://raw.githubusercontent.com/kangbumhee/TLA_MAP/main/version.json"
        private const val CSV_URL =
            "https://raw.githubusercontent.com/kangbumhee/TLA_MAP/main/cameras.csv"
    }

    interface UpdateListener {
        fun onCheckStart()
        fun onUpdateAvailable(ver: Int)
        fun onDownloading()
        fun onComplete(cnt: Int, ver: Int)
        fun onNoUpdate()
        fun onError(msg: String)
    }

    private val prefs = context.getSharedPreferences("camalert_db", Context.MODE_PRIVATE)

    private var currentVersion: Int
        get() = prefs.getInt("db_version", 0)
        set(v) {
            prefs.edit().putInt("db_version", v).apply()
        }

    fun checkAndUpdate(listener: UpdateListener) {
        Thread {
            try {
                listener.onCheckStart()

                val versionJson = httpGet(VERSION_URL)
                if (versionJson == null) {
                    listener.onError("서버 연결 실패")
                    return@Thread
                }

                val json = JSONObject(versionJson)
                val remoteVersion = json.optInt("version", 0)
                val remoteCount = json.optInt("count", 0)

                Log.d(TAG, "현재 v$currentVersion → 서버 v$remoteVersion (${remoteCount}개)")

                if (remoteVersion <= currentVersion) {
                    listener.onNoUpdate()
                    return@Thread
                }

                listener.onUpdateAvailable(remoteVersion)
                listener.onDownloading()

                val csvData = httpGet(CSV_URL)
                if (csvData == null) {
                    listener.onError("CSV 다운로드 실패")
                    return@Thread
                }

                val count = db.loadFromCsvString(csvData)

                if (count > 0) {
                    currentVersion = remoteVersion
                    listener.onComplete(count, remoteVersion)
                    Log.d(TAG, "업데이트 완료: v$remoteVersion, ${count}개")
                } else {
                    listener.onError("CSV 파싱 실패 (0개)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "업데이트 오류", e)
                listener.onError(e.message ?: "알 수 없는 오류")
            }
        }.start()
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode}: $urlStr")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 오류: $urlStr", e)
            null
        }
    }
}
