package id.ac.usu.sosrelay.demo

import android.app.Activity
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import id.ac.usu.sosrelay.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private var relay: SosRelay? = null
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private var pendingAction: (() -> Unit)? = null
    private val lines = ArrayDeque<String>()
    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    @Suppress("DEPRECATION") // API 26–29 inset fallback.
    @SuppressLint("SetTextI18n") // Indonesian demo UI, no localization resources yet.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            // Edge-to-edge on target SDK 36: keep controls outside system bars.
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom)
                } else {
                    view.setPadding(dp(16) + insets.systemWindowInsetLeft,
                        dp(12) + insets.systemWindowInsetTop,
                        dp(16) + insets.systemWindowInsetRight,
                        dp(8) + insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        root.addView(TextView(this).apply { text = "SOS Relay • BLE Coded"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Hanya berjalan saat aplikasi di depan. Aktifkan Bluetooth dan Lokasi. Paket uji memakai koordinat contoh, bukan GPS HP."
            textSize = 14f
        })
        button(root, "Periksa kemampuan perangkat") { withPermissions { relay?.checkCapabilities() } }
        button(root, "Mulai scanning") { withPermissions { relay?.startScanning() } }
        button(root, "Hentikan scanning") { relay?.stopScanning() }
        button(root, "Kirim paket uji") { withPermissions { relay?.sendTestPacket() } }
        button(root, "Hentikan advertising") { relay?.stopAdvertising() }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        append("Siap. Izinkan akses Lokasi/Nearby devices ketika diminta. Jika izin ditolak permanen, buka Pengaturan > Aplikasi > SOS Relay Demo > Izin.")
    }

    override fun onStart() {
        super.onStart()
        relay = SosRelay(applicationContext, ::onRelayEvent)
        relay?.checkCapabilities()
    }

    override fun onStop() {
        pendingAction = null
        relay?.close()
        relay = null
        append("Aplikasi meninggalkan foreground: scanning dan advertising dihentikan, resource dilepas.")
        super.onStop()
    }

    private fun withPermissions(action: () -> Unit) {
        if (relay == null) return
        val missing = RelayPermissions.missing(this)
        if (missing.isEmpty()) action()
        else if (pendingAction == null) {
            pendingAction = action
            append("Meminta izin; komunikasi belum dimulai.")
            // Request coarse + fine together on Android 12+, so precise permission can be selected.
            requestPermissions(RelayPermissions.required(), 100)
        } else append("Permintaan izin masih berlangsung.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 100) return
        val action = pendingAction
        pendingAction = null
        if (grantResults.isNotEmpty() && RelayPermissions.missing(this).isEmpty()) {
            append("Izin lengkap.")
            if (relay != null) action?.invoke()
        } else {
            append("Izin ditolak/belum lengkap. Operasi dibatalkan. Berikan Lokasi tepat dan izin Bluetooth melalui Pengaturan aplikasi.")
            relay?.checkCapabilities()
        }
    }

    private fun onRelayEvent(event: RelayEvent) {
        when (event) {
            is RelayEvent.Status -> append(event.message)
            is RelayEvent.Error -> append("ERROR ${event.code}: ${event.message}")
            is RelayEvent.Capabilities -> with(event.value) {
                append("BLE=$bleSupported | Bluetooth=${show(bluetoothEnabled)} | extended=${show(extendedAdvertisingSupported)} | Coded=${show(codedPhySupported)} | advertiser=${show(advertiserAvailable)} | Lokasi=$locationEnabled\nS=8/125 kbps: belum terverifikasi. Izin kurang: ${missingPermissions.joinToString().ifEmpty { "tidak ada" }}")
            }
            is RelayEvent.Received -> {
                val p = event.packet
                val hex = event.payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                append("RX RSSI=${event.rssi} dBm PHY=${event.primaryPhy}/${event.secondaryPhy}\n${p.kind} sender=${p.senderCrc} t=${p.timestampSeconds} lat=${p.latitude} lon=${p.longitude} status=${p.status} hop=${p.hop} server=${p.fromServer}\n$hex")
            }
        }
    }

    private fun append(message: String) {
        Log.i("SosRelayDemo", message)
        lines.addLast("${timestamp.format(Date())} $message")
        while (lines.size > 200) lines.removeFirst()
        logView.text = lines.joinToString("\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun show(value: Boolean?): String = value?.toString() ?: "belum diketahui (Bluetooth mati/izin kurang)"
    private fun button(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
