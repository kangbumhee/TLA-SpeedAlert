package com.teslcan.app.fragment

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.teslcan.app.EventLog
import com.teslcan.app.LogEvent
import com.teslcan.app.R

class LogFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_log, container, false)
        val list = v.findViewById<ListView>(R.id.listLog)
        val tvEmpty = v.findViewById<TextView>(R.id.tvEmpty)
        val btnClear = v.findViewById<Button>(R.id.btnClear)

        val log = EventLog(requireContext())
        val events = log.getAll()

        if (events.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            list.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            list.visibility = View.VISIBLE
            list.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                events.map { formatEvent(it) }
            )
        }

        btnClear.setOnClickListener {
            log.clear()
            list.adapter = null
            tvEmpty.visibility = View.VISIBLE
            list.visibility = View.GONE
        }
        return v
    }

    private fun formatEvent(e: LogEvent): String {
        val time = DateFormat.format("MM/dd HH:mm", e.time)
        val camTypes = mapOf(
            0 to "단속",
            1 to "고정식",
            2 to "이동식",
            3 to "구간",
            4 to "신호",
            5 to "버스",
            6 to "보호구역"
        )
        val kind = when (e.type) {
            "ALERT" -> "📍 ${camTypes[e.camType] ?: "단속"} ${e.distance}m"
            "PASS" -> "✓ 통과 (${camTypes[e.camType] ?: ""})"
            "OVER" -> "⚠ 과속 ${e.speedKmh}/${e.limitKmh}km"
            "GPS_LOST" -> "🛰 GPS 끊김"
            else -> e.type
        }
        return "$time   $kind"
    }
}
