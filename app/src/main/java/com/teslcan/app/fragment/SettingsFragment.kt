package com.teslcan.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.teslcan.app.CameraDatabase
import com.teslcan.app.DbUpdater
import com.teslcan.app.FirmwareUpdater
import com.teslcan.app.MainActivity
import com.teslcan.app.R
import com.teslcan.app.SettingsStore

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        val store = SettingsStore(requireContext())

        val rgProfile = v.findViewById<RadioGroup>(R.id.rgProfile)
        when (store.profile) {
            "BEGINNER" -> rgProfile.check(R.id.rbBeginner)
            "VETERAN" -> rgProfile.check(R.id.rbVeteran)
            "NIGHT" -> rgProfile.check(R.id.rbNight)
            else -> rgProfile.check(R.id.rbStandard)
        }
        rgProfile.setOnCheckedChangeListener { _, id ->
            store.profile = when (id) {
                R.id.rbBeginner -> "BEGINNER"
                R.id.rbVeteran -> "VETERAN"
                R.id.rbNight -> "NIGHT"
                else -> "STANDARD"
            }
            notifyService()
        }

        bindSwitch(v, R.id.swFixed, store.enableFixed) { store.enableFixed = it }
        bindSwitch(v, R.id.swMobile, store.enableMobile) { store.enableMobile = it }
        bindSwitch(v, R.id.swSection, store.enableSection) { store.enableSection = it }
        bindSwitch(v, R.id.swRedLight, store.enableRedLight) { store.enableRedLight = it }
        bindSwitch(v, R.id.swChildZone, store.enableChildZone) { store.enableChildZone = it }
        bindSwitch(v, R.id.swBusLane, store.enableBusLane) { store.enableBusLane = it }

        val seekOver = v.findViewById<SeekBar>(R.id.seekOverSpeed)
        val tvOver = v.findViewById<TextView>(R.id.tvOverSpeed)
        seekOver.max = 30
        seekOver.progress = store.overSpeedThreshold
        tvOver.text = "+${store.overSpeedThreshold} km/h"
        seekOver.setOnSeekBarChangeListener(
            seekListener { p ->
                store.overSpeedThreshold = p
                tvOver.text = "+$p km/h"
                notifyService()
            }
        )

        val seekVol = v.findViewById<SeekBar>(R.id.seekVolume)
        val tvVol = v.findViewById<TextView>(R.id.tvVolume)
        seekVol.max = 100
        seekVol.progress = store.volume
        tvVol.text = "${store.volume}%"
        seekVol.setOnSeekBarChangeListener(
            seekListener { p ->
                store.volume = p
                tvVol.text = "$p%"
                notifyService()
            }
        )

        val rgStyle = v.findViewById<RadioGroup>(R.id.rgStyle)
        when (store.voiceStyle) {
            "SHORT" -> rgStyle.check(R.id.rbShort)
            "DETAILED" -> rgStyle.check(R.id.rbDetailed)
            else -> rgStyle.check(R.id.rbStandardStyle)
        }
        rgStyle.setOnCheckedChangeListener { _, id ->
            store.voiceStyle = when (id) {
                R.id.rbShort -> "SHORT"
                R.id.rbDetailed -> "DETAILED"
                else -> "STANDARD"
            }
            notifyService()
        }

        bindSwitch(v, R.id.swNightAuto, store.nightAutoVolume) {
            store.nightAutoVolume = it
            notifyService()
        }
        bindSwitch(v, R.id.swBeep, store.useBeep) {
            store.useBeep = it
            notifyService()
        }
        bindSwitch(v, R.id.swChime, store.passChime) {
            store.passChime = it
            notifyService()
        }
        bindSwitch(v, R.id.swKeepScreen, store.keepScreenOn) {
            store.keepScreenOn = it
        }
        bindSwitch(v, R.id.sw1000m, store.alertAt1000m) {
            store.alertAt1000m = it
            notifyService()
        }
        bindSwitch(v, R.id.sw500m, store.alertAt500m) {
            store.alertAt500m = it
            notifyService()
        }
        bindSwitch(v, R.id.sw300m, store.alertAt300m) {
            store.alertAt300m = it
            notifyService()
        }
        bindSwitch(v, R.id.sw100m, store.alertAt100m) {
            store.alertAt100m = it
            notifyService()
        }

        val btnUpdate = v.findViewById<Button>(R.id.btnUpdate)
        val tvDbInfo = v.findViewById<TextView>(R.id.tvDbInfo)
        btnUpdate.setOnClickListener {
            val db = CameraDatabase(requireContext())
            val up = DbUpdater(requireContext(), db)
            btnUpdate.isEnabled = false
            up.checkAndUpdate(
                object : DbUpdater.UpdateListener {
                    override fun onCheckStart() = runUi { tvDbInfo.text = "확인 중..." }

                    override fun onUpdateAvailable(ver: Int) = runUi { tvDbInfo.text = "v$ver 발견" }

                    override fun onDownloading() = runUi { tvDbInfo.text = "다운로드 중..." }

                    override fun onComplete(cnt: Int, ver: Int) = runUi {
                        tvDbInfo.text = "DB v$ver (${cnt}개)"
                        btnUpdate.isEnabled = true
                        (activity as? MainActivity)?.bleService?.refreshCameraCount()
                    }

                    override fun onNoUpdate() = runUi {
                        tvDbInfo.text = "최신 버전"
                        btnUpdate.isEnabled = true
                    }

                    override fun onError(msg: String) = runUi {
                        tvDbInfo.text = "실패: $msg"
                        btnUpdate.isEnabled = true
                    }
                }
            )
        }

        v.findViewById<Button>(R.id.btnTestVoice).setOnClickListener {
            (activity as? MainActivity)?.bleService?.let { svc ->
                svc.applyAllSettings()
                svc.alertPlayer.testAlert(
                    phase = 1,
                    distance = 500,
                    speedKmh = 50,
                    limitKmh = 60,
                    camType = 0
                )
            }
        }

        val btnFirmware = v.findViewById<Button>(R.id.btnFirmwareUpdate)
        val tvFirmware = v.findViewById<TextView>(R.id.tvFirmwareStatus)
        btnFirmware.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            act.whenServiceReady { svc ->
                val gatt = svc.getGatt()
                val currentVer = svc.readFirmwareVersion()
                val updater = FirmwareUpdater(requireContext())
                btnFirmware.isEnabled = false
                updater.checkAndUpdate(gatt, currentVer) { status ->
                    runUi {
                        tvFirmware.text = status
                        btnFirmware.isEnabled = true
                    }
                }
            }
        }

        val btnSimGangnam = v.findViewById<Button>(R.id.btnSimGangnam)
        val btnSimSeocho = v.findViewById<Button>(R.id.btnSimSeocho)
        val btnSimCamera = v.findViewById<Button>(R.id.btnSimCamera)
        val btnSimStop = v.findViewById<Button>(R.id.btnSimStop)
        val spinnerSimSpeed = v.findViewById<Spinner>(R.id.spinnerSimSpeed)
        val tvSimStatus = v.findViewById<TextView>(R.id.tvSimStatus)

        val speeds = intArrayOf(40, 60, 80, 100)

        fun getSimSpeed(): Int {
            val idx = spinnerSimSpeed.selectedItemPosition
            return if (idx in speeds.indices) speeds[idx] else 60
        }

        fun launchSim(preset: String) {
            val act = activity as? MainActivity ?: return
            act.whenServiceReady { svc ->
                val speed = getSimSpeed()
                svc.startSimulation(preset, speed)
                btnSimStop.isEnabled = true
                tvSimStatus.text = "시뮬 진행: $preset (${speed} km/h)"
                svc.routeSimulator?.onSimulationEnd = {
                    activity?.runOnUiThread {
                        btnSimStop.isEnabled = false
                        tvSimStatus.text = "시뮬 종료"
                    }
                }
            }
        }

        btnSimGangnam.setOnClickListener { launchSim("gangnam_jamsil") }
        btnSimSeocho.setOnClickListener { launchSim("seocho_yangjae") }
        btnSimCamera.setOnClickListener { launchSim("camera_dense") }
        btnSimStop.setOnClickListener {
            (activity as? MainActivity)?.whenServiceReady { svc ->
                svc.stopSimulation()
                btnSimStop.isEnabled = false
                tvSimStatus.text = "시뮬 중지됨"
            }
        }

        return v
    }

    private fun bindSwitch(v: View, id: Int, init: Boolean, onChange: (Boolean) -> Unit) {
        v.findViewById<SwitchMaterial>(id).apply {
            isChecked = init
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
    }

    private fun seekListener(cb: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
            cb(p)
        }

        override fun onStartTrackingTouch(s: SeekBar?) {}

        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun notifyService() {
        (activity as MainActivity).bleService?.applyAllSettings()
    }

    private inline fun runUi(crossinline block: () -> Unit) {
        activity?.runOnUiThread { block() }
    }
}
