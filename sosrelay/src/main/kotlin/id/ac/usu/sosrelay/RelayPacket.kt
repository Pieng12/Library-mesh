package id.ac.usu.sosrelay

import kotlin.math.floor
import java.util.zip.CRC32

enum class PacketKind { SOS, ACK }
enum class PacketStatus { CANCELLED, ACTIVE, RESOLVED }

data class RelayPacket(
    val senderCrc: Long,
    val timestampSeconds: Long,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val status: PacketStatus = PacketStatus.ACTIVE,
    val kind: PacketKind = PacketKind.SOS,
    val fromServer: Boolean = false,
    val hop: Int = 0,
)

/** Wire contract of firmware/esp32c3/{include/Protocol.h,src/Protocol.cpp}. */
object PacketCodec {
    const val MANUFACTURER_ID = 0xFFFF
    const val PAYLOAD_LENGTH = 17
    const val VERSION = "resqmesh-ble17-v1"
    const val EPOCH_SECONDS = 1780272000L
    const val EPOCH_ID = "resqmesh-2026-06-01"
    const val TIMESTAMP_MODULO = 1L shl 24

    /** Throws IllegalArgumentException for values outside the firmware wire contract. */
    fun encode(packet: RelayPacket): ByteArray {
        require(packet.senderCrc in 0..0xFFFFFFFFL) { "senderCrc harus uint32" }
        require(packet.timestampSeconds in EPOCH_SECONDS until EPOCH_SECONDS + TIMESTAMP_MODULO) {
            "Waktu di luar epoch protokol; jangan melakukan wrap timestamp"
        }
        require(packet.hop in 0..63) { "hop harus 0..63" }
        require(packet.kind != PacketKind.ACK || packet.status != PacketStatus.ACTIVE) {
            "ACK tidak boleh ACTIVE"
        }
        val bytes = ByteArray(PAYLOAD_LENGTH)
        bytes[0] = 0x52; bytes[1] = 0x4D
        for (i in 0..3) bytes[2 + i] = (packet.senderCrc shr (24 - i * 8)).toByte()
        write24(bytes, 6, (packet.timestampSeconds - EPOCH_SECONDS).toInt())
        if (packet.kind == PacketKind.SOS) {
            write24(bytes, 9, coordinate(packet.latitude, 90.0))
            write24(bytes, 12, coordinate(packet.longitude, 180.0))
        }
        bytes[15] = packet.status.ordinal.toByte()
        bytes[16] = (packet.hop or (if (packet.kind == PacketKind.ACK) 0x80 else 0) or
            (if (packet.fromServer) 0x40 else 0)).toByte()
        return bytes
    }

    /** Android getManufacturerSpecificData already removes the two-byte company ID. */
    fun decode(payload: ByteArray): RelayPacket? {
        if (payload.size != PAYLOAD_LENGTH || payload[0] != 0x52.toByte() ||
            payload[1] != 0x4D.toByte()) return null
        val status = PacketStatus.entries.getOrNull(payload[15].toInt() and 0xFF) ?: return null
        val flags = payload[16].toInt() and 0xFF
        val kind = if (flags and 0x80 != 0) PacketKind.ACK else PacketKind.SOS
        if (kind == PacketKind.ACK && status == PacketStatus.ACTIVE) return null
        val lat = if (kind == PacketKind.SOS) signed24(payload, 9) / 10000.0 else 0.0
        val lon = if (kind == PacketKind.SOS) signed24(payload, 12) / 10000.0 else 0.0
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        var sender = 0L
        for (i in 2..5) sender = (sender shl 8) or (payload[i].toLong() and 0xFF)
        return RelayPacket(sender, EPOCH_SECONDS + read24(payload, 6), lat, lon, status,
            kind, flags and 0x40 != 0, flags and 0x3F)
    }

    /** Stable demo sender ID; no MAC address or real GPS position is read. */
    fun testPacket(senderName: String = "sosrelay-demo", nowSeconds: Long = System.currentTimeMillis() / 1000): RelayPacket {
        val crc = CRC32().apply { update(senderName.toByteArray(Charsets.UTF_8)) }.value
        return RelayPacket(crc, nowSeconds, -6.2001, 106.8167)
    }

    private fun coordinate(value: Double, limit: Double): Int {
        require(value.isFinite() && value in -limit..limit) { "Koordinat di luar batas" }
        // C++ std::lround: halves round away from zero, including negative values.
        val scaled = value * 10000.0
        return if (scaled < 0) -floor(-scaled + 0.5).toInt() else floor(scaled + 0.5).toInt()
    }
    private fun write24(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..2) bytes[offset + i] = (value shr (16 - i * 8)).toByte()
    }
    private fun read24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset + 2].toInt() and 0xFF)
    private fun signed24(bytes: ByteArray, offset: Int): Int {
        val raw = read24(bytes, offset)
        return if (raw and 0x800000 != 0) raw or -0x1000000 else raw
    }
}
