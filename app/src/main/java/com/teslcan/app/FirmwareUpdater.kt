package com.teslcan.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Nordic-style BLE OTA (URL 기반). GATT 쓰기는 메인 [Handler]에서 순차 실행합니다.
 */
class FirmwareUpdater(private val context: Context) {

    companion object {
        private const val TAG = "FwUpdate"
        private const val FW_JSON_URL =
            "https://raw.githubusercontent.com/kangbumhee/TLA_MAP/main/firmware.json"
        private val OTA_SERVICE = UUID.fromString("fb1e4002-54ae-4a28-9f74-dfccb248601d")
        private val OTA_DATA = UUID.fromString("fb1e4003-54ae-4a28-9f74-dfccb248601d")
        private val OTA_CTRL = UUID.fromString("fb1e4004-54ae-4a28-9f74-dfccb248601d")
        private const val CHUNK_SIZE = 512
        private const val BLE_PREFS = "ble_service"
        private const val KEY_DEVICE_FW = "device_fw_version"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    data class FwInfo(
        val version: String,
        val url: String,
        val size: Int,
        val changelog: String
    )

    @SuppressLint("MissingPermission")
    fun checkAndUpdate(
        gatt: BluetoothGatt?,
        currentVersion: String,
        onStatus: (String) -> Unit
    ) {
        if (gatt == null) {
            post(onStatus, "BLE 연결이 없습니다")
            return
        }

        Thread {
            try {
                post(onStatus, "서버 확인 중...")
                val conn = (URL(FW_JSON_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    requestMethod = "GET"
                }
                if (conn.responseCode != 200) {
                    post(onStatus, "서버 오류: HTTP ${conn.responseCode}")
                    return@Thread
                }
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val obj = JSONObject(json)
                val fw = FwInfo(
                    version = obj.getString("version"),
                    url = obj.getString("url"),
                    size = obj.getInt("size"),
                    changelog = obj.optString("changelog", "")
                )

                if (!isRemoteNewer(fw.version, currentVersion)) {
                    post(onStatus, "최신 버전입니다 (v$currentVersion)")
                    return@Thread
                }

                post(onStatus, "v${fw.version} 발견!\n${fw.changelog}\n\n다운로드 중...")

                val binConn = (URL(fw.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 60000
                    readTimeout = 120000
                    requestMethod = "GET"
                }
                if (binConn.responseCode != 200) {
                    post(onStatus, "펌웨어 파일 HTTP ${binConn.responseCode}")
                    return@Thread
                }
                val binData = binConn.inputStream.readBytes()
                binConn.disconnect()

                if (binData.size != fw.size) {
                    post(onStatus, "크기 불일치: ${binData.size} != ${fw.size}")
                    return@Thread
                }

                post(onStatus, "다운로드 완료 (${binData.size} bytes)\nBLE 전송 시작...")

                mainHandler.post {
                    runOtaOnMain(gatt, binData, fw, onStatus)
                }
            } catch (e: Exception) {
                Log.e(TAG, "OTA 실패", e)
                post(onStatus, "오류: ${e.message ?: "알 수 없음"}")
            }
        }.start()
    }

    private fun saveDeviceFirmwareVersion(version: String) {
        context.getSharedPreferences(BLE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_FW, version)
            .apply()
    }

    private fun isRemoteNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun runOtaOnMain(
        gatt: BluetoothGatt,
        binData: ByteArray,
        fw: FwInfo,
        onStatus: (String) -> Unit
    ) {
        val service = gatt.getService(OTA_SERVICE)
        if (service == null) {
            post(onStatus, "기기가 OTA 서비스를 지원하지 않습니다")
            return
        }
        val ctrlChar = service.getCharacteristic(OTA_CTRL)
        val dataChar = service.getCharacteristic(OTA_DATA)
        if (ctrlChar == null || dataChar == null) {
            post(onStatus, "OTA 특성을 찾을 수 없습니다")
            return
        }

        OtaChunkSender(
            gatt = gatt,
            ctrlChar = ctrlChar,
            dataChar = dataChar,
            bin = binData,
            main = mainHandler,
            onStatus = onStatus,
            fwVersion = fw.version,
            onFirmwareSaved = { saveDeviceFirmwareVersion(it) }
        ).start()
    }

    private fun post(onStatus: (String) -> Unit, msg: String) {
        mainHandler.post { onStatus(msg) }
    }

    @SuppressLint("MissingPermission")
    private class OtaChunkSender(
        private val gatt: BluetoothGatt,
        private val ctrlChar: BluetoothGattCharacteristic,
        private val dataChar: BluetoothGattCharacteristic,
        private val bin: ByteArray,
        private val main: Handler,
        private val onStatus: (String) -> Unit,
        private val fwVersion: String,
        private val onFirmwareSaved: (String) -> Unit
    ) {
        private var offset = 0
        private var started = false

        private val step = object : Runnable {
            override fun run() {
                if (!started) {
                    started = true
                    ctrlChar.value = "START:${bin.size}".toByteArray()
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(ctrlChar)
                    main.postDelayed(this, 800)
                    return
                }
                if (offset >= bin.size) {
                    ctrlChar.value = "END".toByteArray()
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(ctrlChar)
                    onFirmwareSaved(fwVersion)
                    onStatus("업데이트 전송 완료\n기기가 재부팅될 수 있습니다.")
                    return
                }
                val end = minOf(offset + CHUNK_SIZE, bin.size)
                val chunk = bin.copyOfRange(offset, end)
                dataChar.value = chunk
                dataChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(dataChar)
                offset = end
                val pct = if (bin.isNotEmpty()) offset * 100 / bin.size else 100
                if (pct % 10 == 0 || offset >= bin.size) {
                    onStatus("전송 중... $pct%")
                }
                main.postDelayed(this, 30)
            }
        }

        fun start() {
            main.post(step)
        }
    }
}
