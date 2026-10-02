package id.ac.usu.sosrelay

import org.junit.Assert.*
import org.junit.Test

class PacketCodecTest {
    // Exact independent vector from ESP32 Protocol.cpp: sender=12345678, epoch+42,
    // lat=-62001 => FF0DCF; lon=1068167 => 104C87; ACTIVE, hop=63.
    private val firmwareVector = byteArrayOf(0x52, 0x4D, 0x12, 0x34, 0x56, 0x78,
        0, 0, 42, -1, 0x0D, -49, 0x10, 0x4C, -121, 1, 63)

    @Test fun sosMatchesFirmwareBytes() {
        val packet = RelayPacket(0x12345678, PacketCodec.EPOCH_SECONDS + 42,
            -6.2001, 106.8167, hop = 63)
        assertArrayEquals(firmwareVector, PacketCodec.encode(packet))
        assertEquals(packet, PacketCodec.decode(firmwareVector))
    }

    @Test fun ackFlagsAndCoordinatesFollowFirmware() {
        val packet = RelayPacket(0xFFFFFFFF, PacketCodec.EPOCH_SECONDS,
            status = PacketStatus.RESOLVED, kind = PacketKind.ACK, fromServer = true, hop = 7)
        val encoded = PacketCodec.encode(packet)
        assertEquals(0xC7, encoded[16].toInt() and 0xFF)
        assertArrayEquals(ByteArray(6), encoded.copyOfRange(9, 15))
        assertEquals(packet, PacketCodec.decode(encoded))
        encoded[15] = 1
        assertNull(PacketCodec.decode(encoded))
    }

    @Test fun rejectsMalformedAndOutOfRangePackets() {
        assertNull(PacketCodec.decode(ByteArray(17)))
        assertNull(PacketCodec.decode(firmwareVector + byteArrayOf(0)))
        assertNull(PacketCodec.decode(byteArrayOf(-1, -1) + firmwareVector))
        assertNull(PacketCodec.decode(firmwareVector.copyOf().apply { this[15] = 3 }))
        assertNull(PacketCodec.decode(firmwareVector.copyOf().apply {
            this[9] = 0x7F; this[10] = -1; this[11] = -1
        }))
        val base = RelayPacket(1, PacketCodec.EPOCH_SECONDS)
        listOf(base.copy(timestampSeconds = PacketCodec.EPOCH_SECONDS - 1),
            base.copy(timestampSeconds = PacketCodec.EPOCH_SECONDS + PacketCodec.TIMESTAMP_MODULO),
            base.copy(latitude = Double.NaN), base.copy(longitude = 181.0),
            base.copy(senderCrc = -1), base.copy(hop = 64), base.copy(kind = PacketKind.ACK)
        ).forEach { assertThrows(IllegalArgumentException::class.java) { PacketCodec.encode(it) } }
    }

    @Test fun timestampBoundariesAndCppRounding() {
        val last = RelayPacket(1, PacketCodec.EPOCH_SECONDS + PacketCodec.TIMESTAMP_MODULO - 1,
            -0.00005, 0.00005)
        val decoded = PacketCodec.decode(PacketCodec.encode(last))!!
        assertEquals(last.timestampSeconds, decoded.timestampSeconds)
        assertEquals(-0.0001, decoded.latitude, 0.0)
        assertEquals(0.0001, decoded.longitude, 0.0)
        assertEquals(PacketCodec.EPOCH_SECONDS,
            PacketCodec.decode(PacketCodec.encode(last.copy(timestampSeconds = PacketCodec.EPOCH_SECONDS)))!!.timestampSeconds)
    }
}
