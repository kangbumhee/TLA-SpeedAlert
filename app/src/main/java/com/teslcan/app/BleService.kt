package com.teslcan.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID

class BleService : Service() {

    companion object {
        private const val TAG = "BleService"
        private const val CHANNEL_ID = "camalert_channel"
        private const val NOTIFICATION_ID = 1
        private const val TARGET_DEVICE_NAME = "TeslaCAN"
        val SERVICE_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val CHAR_SPEED_UUID: UUID = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")
        val CHAR_GPS_UUID: UUID = UUID.fromString("0000ff04-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    private val binder = LocalBinder()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var isConnected = false
    private var isScanning = false

    lateinit var alertPlayer: AlertPlayer
    lateinit var settings: SettingsStore
    lateinit var eventLog: EventLog
    private lateinit var cameraDb: CameraDatabase
    private lateinit var cameraEngine: CameraEngine

    fun getCameraDb(): CameraDatabase? = if (::cameraDb.isInitialized) cameraDb else null

    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onSpeedUpdate: ((Int) -> Unit)? = null
    var onAlertUpdate: ((AlertInfo) -> Unit)? = null
    var onGpsUpdate: ((Int, Boolean) -> Unit)? = null
    var onCameraCount: ((Int) -> Unit)? = null
    var onLocationUpdate: ((Double, Double) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var currentSpeed = 0
    private var currentLat = 0.0
    private var currentLon = 0.0
    /** 시뮬: 세그먼트 bearing(도). 미사용 시 -1 */
    private var simulationBearingDeg: Double = -1.0
    private var lastGpsValid = false
    private var overspeedLogActive = false
    /** 실차 GPS: 직전 fix 좌표(진행 방위 추정용). 시뮬 중에는 사용하지 않음 */
    private var lastCourseLat: Double? = null
    private var lastCourseLon: Double? = null

    private val servicePrefs by lazy {
        getSharedPreferences("ble_service", Context.MODE_PRIVATE)
    }

    var adManager: AdManager? = null
        private set
    var routeSimulator: RouteSimulator? = null
        private set
    var isSimulationMode = false
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, makeNotification("초기화 중..."))

        settings = SettingsStore(this)
        eventLog = EventLog(this)
        alertPlayer = AlertPlayer(this)
        alertPlayer.onTtsReady = { tts ->
            if (adManager == null) {
                adManager = AdManager(this@BleService).apply {
                    init(tts)
                    onAdPlayed = { id, msg -> Log.d(TAG, "광고 재생: $id - $msg") }
                }
                adManager?.playStartup {
                    handler.postDelayed({
                        alertPlayer.speakEvent("CamAlert 시작합니다")
                    }, 400)
                }
            }
        }
        alertPlayer.init()
        alertPlayer.applySettings(settings)

        handler.postDelayed({
            if (adManager == null) {
                alertPlayer.speakEvent("CamAlert 시작합니다")
            }
        }, 4500)

        cameraDb = CameraDatabase(this)
        if (cameraDb.getCameraCount() == 0) {
            cameraDb.importFromAssetsAuto("cameras.csv")
        }
        cameraEngine = CameraEngine(cameraDb, settings)

        handler.post { onCameraCount?.invoke(cameraDb.getCameraCount()) }

        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = mgr.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner
        updateNoti("장치 검색 중... (DB: ${cameraDb.getCameraCount()}개)")

        routeSimulator = RouteSimulator().apply {
            onLocationUpdate = { lat, lon, speedKmh, sats, fix, bearingDeg ->
                currentLat = lat
                currentLon = lon
                currentSpeed = speedKmh
                simulationBearingDeg = bearingDeg
                handler.post {
                    onSpeedUpdate?.invoke(speedKmh)
                    onGpsUpdate?.invoke(sats, fix)
                    this@BleService.onLocationUpdate?.invoke(lat, lon)
                }
                if (fix) checkCamera()
            }
            onSimulationEnd = {
                handler.post {
                    isSimulationMode = false
                    simulationBearingDeg = -1.0
                    cameraEngine.bearingOverrideDeg = null
                    updateNoti("시뮬레이션 종료")
                    alertPlayer.speakEvent("시뮬레이션이 종료되었습니다")
                }
            }
        }

        startScan()
    }

    fun setAdMuted(muted: Boolean) {
        adManager?.setMuted(muted)
    }

    fun getGatt(): BluetoothGatt? = gatt

    fun readFirmwareVersion(): String =
        servicePrefs.getString("device_fw_version", "0.0.0") ?: "0.0.0"

    fun setDeviceFirmwareVersion(version: String) {
        servicePrefs.edit().putString("device_fw_version", version).apply()
    }

    fun applyAllSettings() {
        alertPlayer.applySettings(settings)
    }

    fun startSimulation(presetName: String, speedKmh: Int = 60) {
        isSimulationMode = true
        lastCourseLat = null
        lastCourseLon = null
        stopScan()
        try {
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        isConnected = false
        cameraEngine.reset()
        alertPlayer.stop()
        handler.post {
            onConnectionChanged?.invoke(false)
            onAlertUpdate?.invoke(CameraEngine.idle())
            updateNoti("시뮬: $presetName ($speedKmh km/h)")
            alertPlayer.speakEvent("시뮬레이션을 시작합니다")
        }
        routeSimulator?.speedMultiplier = 1.0
        routeSimulator?.startPreset(presetName, speedKmh)
    }

    fun startSimulationRoute(
        sLat: Double,
        sLon: Double,
        eLat: Double,
        eLon: Double,
        speedKmh: Int = 60
    ) {
        isSimulationMode = true
        lastCourseLat = null
        lastCourseLon = null
        stopScan()
        try {
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        isConnected = false
        cameraEngine.reset()
        alertPlayer.stop()
        handler.post {
            onConnectionChanged?.invoke(false)
            onAlertUpdate?.invoke(CameraEngine.idle())
            updateNoti("시뮬: 커스텀 경로")
            alertPlayer.speakEvent("시뮬레이션을 시작합니다")
        }
        routeSimulator?.speedMultiplier = 1.0
        routeSimulator?.startRoute(sLat, sLon, eLat, eLon, speedKmh)
    }

    fun stopSimulation() {
        routeSimulator?.stop()
        isSimulationMode = false
        simulationBearingDeg = -1.0
        lastCourseLat = null
        lastCourseLon = null
        cameraEngine.reset()
        alertPlayer.stop()
        handler.post {
            onAlertUpdate?.invoke(CameraEngine.idle())
            updateNoti("시뮬 종료. 장치 검색 중...")
        }
        startScan()
    }

    /** 시뮬 재생 속도(틱 간격). 시뮬 중에도 즉시 반영. */
    fun setSimulationSpeedMultiplier(mult: Double) {
        routeSimulator?.let { rs ->
            rs.speedMultiplier = mult
            if (isSimulationMode) rs.notifyTickIntervalChanged()
        }
    }

    /** 설정 화면에서 DB 갱신 후 카메라 개수 UI 동기화용 */
    fun refreshCameraCount() {
        handler.post {
            try {
                onCameraCount?.invoke(cameraDb.getCameraCount())
            } catch (e: Exception) {
                Log.w(TAG, "refreshCameraCount", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adManager?.destroy()
        adManager = null
        routeSimulator?.stop()
        routeSimulator = null
        stopScan()
        try {
            gatt?.close()
        } catch (_: SecurityException) {
        }
        alertPlayer.shutdown()
        cameraDb.close()
    }

    private fun startScan() {
        if (isScanning) return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val stt = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner?.startScan(filters, stt, scanCb)
            isScanning = true
        } catch (_: SecurityException) {
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCb)
            isScanning = false
        } catch (_: SecurityException) {
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { dev ->
                try {
                    if (dev.name == TARGET_DEVICE_NAME) {
                        stopScan()
                        dev.connectGatt(this@BleService, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                    }
                } catch (_: SecurityException) {
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.postDelayed({ startScan() }, 3000)
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                isConnected = true
                handler.post {
                    onConnectionChanged?.invoke(true)
                    updateNoti("장치 연결됨")
                    alertPlayer.speakEvent("장치가 연결되었습니다")
                }
                try {
                    g.discoverServices()
                } catch (_: SecurityException) {
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                isConnected = false
                gatt = null
                overspeedLogActive = false
                if (isSimulationMode) return
                handler.post {
                    onConnectionChanged?.invoke(false)
                    alertPlayer.stop()
                    cameraEngine.reset()
                    updateNoti("연결 끊김. 재검색 중...")
                    alertPlayer.speakEvent("연검이 끊어졌습니다")
                }
                handler.postDelayed({ startScan() }, 2000)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            gatt = g
            val svc = g.getService(SERVICE_UUID) ?: return
            enableNotify(g, svc.getCharacteristic(CHAR_SPEED_UUID))
            handler.postDelayed({ enableNotify(g, svc.getCharacteristic(CHAR_GPS_UUID)) }, 500)
        }

        @Deprecated("", level = DeprecationLevel.HIDDEN)
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = ch.value ?: return
            when (ch.uuid) {
                CHAR_SPEED_UUID -> onSpeed(v)
                CHAR_GPS_UUID -> onGps(v)
            }
        }
    }

    private fun onSpeed(data: ByteArray) {
        if (data.size < 2) return
        currentSpeed = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        handler.post { onSpeedUpdate?.invoke(currentSpeed) }
    }

    /** 직전 GPS fix (엔진 내부 베어링용으로는 미사용, 시뮬/디버그 참고용으로 유지) */
    private fun feedCourseBearingFromBleGps() {
        lastCourseLat = currentLat
        lastCourseLon = currentLon
    }

    private fun onGps(data: ByteArray) {
        if (data.size < 10) return
        val latInt = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
            ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val lonInt = ((data[4].toInt() and 0xFF) shl 24) or ((data[5].toInt() and 0xFF) shl 16) or
            ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val sats = data[8].toInt() and 0xFF
        val fix = data[9].toInt() != 0
        currentLat = latInt / 1000000.0
        currentLon = lonInt / 1000000.0

        handler.post {
            onGpsUpdate?.invoke(sats, fix)
            if (fix) onLocationUpdate?.invoke(currentLat, currentLon)
        }

        if (fix) {
            if (!lastGpsValid) alertPlayer.speakEvent("GPS 연결됨. 위성 ${sats}개")
            lastGpsValid = true
            if (!isSimulationMode && ::cameraEngine.isInitialized) {
                feedCourseBearingFromBleGps()
            }
            checkCamera()
        } else if (lastGpsValid) {
            lastGpsValid = false
            lastCourseLat = null
            lastCourseLon = null
            overspeedLogActive = false
            cameraEngine.reset()
            handler.post {
                alertPlayer.stop()
                onAlertUpdate?.invoke(CameraEngine.idle())
            }
            alertPlayer.speakEvent("GPS 신호가 약합니다")
            eventLog.add(
                LogEvent(
                    System.currentTimeMillis(),
                    "GPS_LOST",
                    0,
                    currentSpeed,
                    0,
                    0,
                    currentLat,
                    currentLon
                )
            )
        }
    }

    private fun checkCamera() {
        val bearingOverride =
            if (isSimulationMode && simulationBearingDeg >= 0.0) simulationBearingDeg else null
        val r = cameraEngine.check(
            currentLat,
            currentLon,
            currentSpeed,
            settings.overSpeedThreshold,
            bearingOverride
        ) ?: return

        handler.post {
            onAlertUpdate?.invoke(r)

            if (r.phase == 0) {
                overspeedLogActive = false
                if (r.speedLimit > 0) {
                    eventLog.add(
                        LogEvent(
                            System.currentTimeMillis(),
                            "PASS",
                            r.camType,
                            currentSpeed,
                            r.speedLimit,
                            0,
                            currentLat,
                            currentLon
                        )
                    )
                    alertPlayer.handleAlert(0, 0, 0, currentSpeed, r.speedLimit, r.camType, 0, 0, 0)
                } else {
                    alertPlayer.stop()
                }
                return@post
            }

            if (r.overspeed) {
                if (!overspeedLogActive) {
                    overspeedLogActive = true
                    eventLog.add(
                        LogEvent(
                            System.currentTimeMillis(),
                            "OVER",
                            r.camType,
                            currentSpeed,
                            r.speedLimit,
                            r.distance,
                            currentLat,
                            currentLon
                        )
                    )
                }
            } else {
                overspeedLogActive = false
            }

            // 1000~1100m: 대시보드·지도만, 구간 음성(handleSafetyAlert) 생략
            if (r.phase == -1) {
                return@post
            }

            if (r.phase >= 1) {
                alertPlayer.handleSafetyAlert(r, currentSpeed)
                if (r.zoneTriggered > 0) {
                    eventLog.add(
                        LogEvent(
                            System.currentTimeMillis(),
                            "ALERT",
                            r.camType,
                            currentSpeed,
                            r.speedLimit,
                            r.distance,
                            currentLat,
                            currentLon
                        )
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic?) {
        if (ch == null) return
        try {
            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD_UUID)?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(it)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "CamAlert", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun makeNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CamAlert")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNoti(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, makeNotification(text))
    }
}
