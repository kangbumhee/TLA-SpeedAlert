package com.teslcan.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.teslcan.app.fragment.DashboardFragment
import com.teslcan.app.fragment.LogFragment
import com.teslcan.app.fragment.SettingsFragment

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_DASH = "dashboard"
        private const val TAG_SETTINGS = "settings"
        private const val TAG_LOG = "log"
    }

    var bleService: BleService? = null
    private var bound = false

    private lateinit var dashboardFragment: DashboardFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var logFragment: LogFragment
    private lateinit var activeFragment: Fragment

    private val permissions = mutableListOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val listeners = mutableListOf<(BleService) -> Unit>()

    fun whenServiceReady(cb: (BleService) -> Unit) {
        bleService?.let {
            cb(it)
            return
        }
        listeners.add(cb)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as BleService.LocalBinder
            bleService = b.getService()
            bound = true
            listeners.forEach { it(bleService!!) }
            listeners.clear()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val store = SettingsStore(this)
        if (store.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            dashboardFragment = DashboardFragment()
            settingsFragment = SettingsFragment()
            logFragment = LogFragment()
            fm.beginTransaction()
                .add(R.id.container, logFragment, TAG_LOG)
                .hide(logFragment)
                .add(R.id.container, settingsFragment, TAG_SETTINGS)
                .hide(settingsFragment)
                .add(R.id.container, dashboardFragment, TAG_DASH)
                .commit()
            activeFragment = dashboardFragment
        } else {
            dashboardFragment = fm.findFragmentByTag(TAG_DASH) as DashboardFragment
            settingsFragment = fm.findFragmentByTag(TAG_SETTINGS) as SettingsFragment
            logFragment = fm.findFragmentByTag(TAG_LOG) as LogFragment
            activeFragment = fm.fragments.firstOrNull { !it.isHidden } ?: dashboardFragment
        }

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.tab_dashboard -> dashboardFragment
                R.id.tab_settings -> settingsFragment
                R.id.tab_log -> logFragment
                else -> dashboardFragment
            }
            if (target !== activeFragment) {
                fm.beginTransaction()
                    .hide(activeFragment)
                    .show(target)
                    .commit()
                activeFragment = target
            }
            true
        }

        when (activeFragment) {
            is DashboardFragment -> nav.selectedItemId = R.id.tab_dashboard
            is SettingsFragment -> nav.selectedItemId = R.id.tab_settings
            is LogFragment -> nav.selectedItemId = R.id.tab_log
        }

        checkPermissionsAndStart()
    }

    /** 대시보드 음소거 시 주기 광고 TTS도 같이 억제 */
    fun setAdsMuted(muted: Boolean) {
        bleService?.setAdMuted(muted)
    }

    private fun checkPermissionsAndStart() {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        } else {
            startBleService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 100) return
        val allGranted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            startBleService()
        } else {
            Toast.makeText(this, "BLE·위치·알림 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startBleService() {
        val i = Intent(this, BleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            @Suppress("DEPRECATION")
            startService(i)
        }
        bindService(i, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }
}
