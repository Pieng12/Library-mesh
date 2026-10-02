package id.ac.usu.sosrelay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper

data class RelayCapabilities(
    val bleSupported: Boolean,
    val bluetoothEnabled: Boolean?,
    val extendedAdvertisingSupported: Boolean?,
    val codedPhySupported: Boolean?,
    val advertiserAvailable: Boolean?,
    val locationEnabled: Boolean,
    val missingPermissions: List<String>,
    // Android API used here selects Coded PHY but cannot select/verify on-air S=8.
    val s8Verified: Boolean = false,
)

sealed class RelayEvent {
    data class Capabilities(val value: RelayCapabilities) : RelayEvent()
    data class Status(val message: String) : RelayEvent()
    data class Received(val packet: RelayPacket, val rssi: Int, val primaryPhy: Int,
        val secondaryPhy: Int, val payload: ByteArray) : RelayEvent()
    data class Error(val code: String, val message: String) : RelayEvent()
}

/**
 * Foreground BLE radio, independent of any Activity. All public calls must use the main thread.
 * Listener runs on the main thread. One scan and one advertising set per instance.
 * Call stopAll when the host leaves the foreground and close when disposing this instance.
 */
@SuppressLint("MissingPermission")
class SosRelay(context: Context, listener: (RelayEvent) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((RelayEvent) -> Unit)? = listener
    private val adapter: BluetoothAdapter? = this.context.getSystemService(BluetoothManager::class.java)?.adapter
    private var closed = false
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingCallback: AdvertisingSetCallback? = null
    private var advertisingTimeout: Runnable? = null
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (closed) return
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            // Exported is necessary for broadcasts from the privileged Bluetooth process.
            // Trust the adapter state, never the extras of a potentially forged broadcast.
            if (checkCapabilities().bluetoothEnabled != true) {
                stopAll()
                emit(RelayEvent.Status("Bluetooth tidak aktif; operasi dihentikan"))
            }
        }
    }

    init {
        mainThread()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        else this.context.registerReceiver(bluetoothReceiver, filter)
    }

    fun checkCapabilities(): RelayCapabilities {
        mainThread()
        val missing = RelayPermissions.missing(context)
        val ble = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val location = context.getSystemService(LocationManager::class.java)?.let {
            it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } ?: false
        val result = try {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                RelayCapabilities(ble, null, null, null, null, location, missing)
            } else {
                val enabled = adapter?.isEnabled == true
                RelayCapabilities(ble, enabled,
                    if (enabled) adapter?.isLeExtendedAdvertisingSupported else null,
                    if (enabled) adapter?.isLeCodedPhySupported else null,
                    if (enabled) adapter?.bluetoothLeAdvertiser != null else null,
                    location, missing)
            }
        } catch (e: SecurityException) {
            error("MISSING_PERMISSION", e.message ?: "Izin Bluetooth dicabut")
            RelayCapabilities(ble, null, null, null, null, location, RelayPermissions.missing(context))
        }
        if (!closed) emit(RelayEvent.Capabilities(result))
        return result
    }

    fun startScanning() {
        mainThread()
        if (!ready(scan = true)) return
        if (scanCallback != null) { emit(RelayEvent.Status("Scanning sudah berjalan")); return }
        val owner = adapter?.bluetoothLeScanner ?: run { error("SCANNER_UNAVAILABLE", "Scanner tidak tersedia"); return }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = receive(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::receive) }
            private fun receive(result: ScanResult) {
                handler.post {
                    if (closed || scanCallback !== this) return@post
                    val bytes = result.scanRecord?.getManufacturerSpecificData(PacketCodec.MANUFACTURER_ID) ?: return@post
                    val packet = PacketCodec.decode(bytes)
                    if (packet == null) error("INVALID_PACKET", "Payload RM tidak valid: ${bytes.size} byte")
                    else emit(RelayEvent.Received(packet, result.rssi, result.primaryPhy, result.secondaryPhy, bytes.copyOf()))
                }
            }
            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (scanCallback !== this) return@post
                    scanCallback = null; scanner = null
                    error("SCAN_FAILED", "Android scan error=$errorCode")
                }
            }
        }
        try {
            val filter = ScanFilter.Builder().setManufacturerData(PacketCodec.MANUFACTURER_ID,
                byteArrayOf(0x52, 0x4D), byteArrayOf(-1, -1)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false).setPhy(BluetoothDevice.PHY_LE_CODED).setReportDelay(0).build()
            scanner = owner; scanCallback = callback
            owner.startScan(listOf(filter), settings, callback)
            emit(RelayEvent.Status("Scanning diminta: PHY Coded, filter FFFF/RM; tunggu paket atau error"))
        } catch (e: RuntimeException) {
            scanCallback = null; scanner = null
            try { owner.stopScan(callback) } catch (_: RuntimeException) { }
            operationError("SCAN_START_FAILED", e)
        }
    }

    fun stopScanning() {
        mainThread()
        val callback = scanCallback ?: return
        val owner = scanner
        scanCallback = null; scanner = null
        try { owner?.stopScan(callback); emit(RelayEvent.Status("Scanning dihentikan")) }
        catch (e: RuntimeException) { operationError("SCAN_STOP_FAILED", e) }
    }

    /** Advertises until stopAdvertising/stopAll/close. This does not confirm reception by ESP32. */
    fun sendTestPacket(packet: RelayPacket = PacketCodec.testPacket()) {
        mainThread()
        if (!ready(scan = false)) return
        if (advertisingCallback != null) { error("ADVERTISING_BUSY", "Hentikan advertising sebelum mengirim paket berikutnya"); return }
        val payload = try { PacketCodec.encode(packet) } catch (e: IllegalArgumentException) {
            error("INVALID_PACKET", e.message ?: "Paket tidak valid"); return
        }
        val owner = adapter?.bluetoothLeAdvertiser ?: run { error("ADVERTISER_UNAVAILABLE", "Advertiser tidak tersedia"); return }
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (closed || advertisingCallback !== this) {
                    // Stop may race with the asynchronous controller start acknowledgment.
                    if (status == ADVERTISE_SUCCESS) try { owner.stopAdvertisingSet(this) }
                    catch (e: RuntimeException) { operationError("ADVERTISE_STOP_FAILED", e) }
                    return
                }
                clearAdvertisingTimeout()
                if (status != ADVERTISE_SUCCESS || set == null) {
                    advertisingCallback = null; advertiser = null
                    error("ADVERTISE_FAILED", "Android advertising error=$status")
                } else emit(RelayEvent.Status("Advertising aktif: Coded/Coded, 250 ms, daya aktual $txPower dBm; S=8 belum terverifikasi"))
            }
            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                if (advertisingCallback !== this) return
                clearAdvertisingTimeout()
                advertisingCallback = null; advertiser = null
                emit(RelayEvent.Status("Advertising dihentikan oleh controller"))
            }
        }
        try {
            val parameters = AdvertisingSetParameters.Builder().setLegacyMode(false)
                .setConnectable(false).setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED).setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setInterval(400).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM).build()
            // Android adds company ID once; do not prefix FF FF to these 17 bytes.
            val data = AdvertiseData.Builder().addManufacturerData(PacketCodec.MANUFACTURER_ID, payload)
                .setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
            advertiser = owner; advertisingCallback = callback
            owner.startAdvertisingSet(parameters, data, null, null, null, callback, handler)
            advertisingTimeout = Runnable {
                if (advertisingCallback === callback) {
                    stopAdvertising()
                    error("ADVERTISE_TIMEOUT", "Controller tidak mengonfirmasi start dalam 10 detik")
                }
            }.also { handler.postDelayed(it, 10_000) }
            emit(RelayEvent.Status("Start advertising diminta; sender=${packet.senderCrc}, waktu=${packet.timestampSeconds}"))
        } catch (e: RuntimeException) {
            clearAdvertisingTimeout()
            advertisingCallback = null; advertiser = null
            try { owner.stopAdvertisingSet(callback) } catch (_: RuntimeException) { }
            operationError("ADVERTISE_START_FAILED", e)
        }
    }

    fun stopAdvertising() {
        mainThread()
        clearAdvertisingTimeout()
        val callback = advertisingCallback ?: return
        val owner = advertiser
        advertisingCallback = null; advertiser = null
        try { owner?.stopAdvertisingSet(callback); emit(RelayEvent.Status("Stop advertising diminta")) }
        catch (e: RuntimeException) { operationError("ADVERTISE_STOP_FAILED", e) }
    }

    fun stopAll() { mainThread(); stopScanning(); stopAdvertising() }

    override fun close() {
        mainThread()
        if (closed) return
        stopAll()
        context.unregisterReceiver(bluetoothReceiver)
        closed = true
        listener = null
    }

    private fun ready(scan: Boolean): Boolean {
        if (closed) { error("CLOSED", "Buat instance baru setelah close"); return false }
        val caps = checkCapabilities()
        val rejection = when {
            caps.missingPermissions.isNotEmpty() -> "MISSING_PERMISSION" to "Izin belum lengkap: ${caps.missingPermissions.joinToString()}"
            !caps.bleSupported -> "BLE_UNSUPPORTED" to "BLE tidak didukung"
            caps.bluetoothEnabled != true -> "BLUETOOTH_OFF" to "Aktifkan Bluetooth di pengaturan HP"
            scan && !caps.locationEnabled -> "LOCATION_OFF" to "Aktifkan layanan Lokasi untuk scanning"
            caps.codedPhySupported != true -> "CODED_PHY_UNSUPPORTED" to "PHY Coded tidak didukung; tidak ada fallback otomatis"
            !scan && caps.extendedAdvertisingSupported != true -> "EXTENDED_ADVERTISING_UNSUPPORTED" to "Extended advertising tidak didukung"
            !scan && caps.advertiserAvailable != true -> "ADVERTISER_UNAVAILABLE" to "Advertiser tidak tersedia"
            else -> null
        }
        if (rejection != null) { error(rejection.first, rejection.second); return false }
        return true
    }
    private fun clearAdvertisingTimeout() { advertisingTimeout?.let(handler::removeCallbacks); advertisingTimeout = null }
    private fun operationError(code: String, e: RuntimeException) = error(
        if (e is SecurityException) "MISSING_PERMISSION" else code, e.message ?: e.javaClass.simpleName)
    private fun error(code: String, message: String) = emit(RelayEvent.Error(code, message))
    private fun emit(event: RelayEvent) { handler.post { if (!closed) listener?.invoke(event) } }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "Panggil SosRelay pada main thread" } }
}
