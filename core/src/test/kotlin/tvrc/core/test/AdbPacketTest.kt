package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.adb.AdbPacket
import tvrc.core.adb.AdbProtocolException
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class AdbPacketTest {

    private fun read(bytes: ByteArray) = AdbPacket.read(DataInputStream(ByteArrayInputStream(bytes)))

    @Test
    fun roundTrip() {
        val payload = "shell:echo hi\u0000".toByteArray(Charsets.UTF_8)
        val original = AdbPacket(AdbPacket.A_OPEN, 3, 9, payload)
        val decoded = read(original.encode())
        assertEquals(original.command, decoded.command)
        assertEquals(original.arg0, decoded.arg0)
        assertEquals(original.arg1, decoded.arg1)
        assertEquals(String(payload, Charsets.UTF_8), String(decoded.payload, Charsets.UTF_8))
    }

    @Test
    fun emptyPayloadRoundTrip() {
        val decoded = read(AdbPacket(AdbPacket.A_OKAY, 1, 2, ByteArray(0)).encode())
        assertEquals(AdbPacket.A_OKAY, decoded.command)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun badMagicRejected() {
        val bytes = AdbPacket(AdbPacket.A_CNXN, 0, 0, ByteArray(0)).encode()
        bytes[20] = (bytes[20].toInt() xor 0xFF).toByte()
        val ex = assertThrows(AdbProtocolException::class.java) { read(bytes) }
        assertTrue(ex.message!!.contains("bad magic"))
    }

    @Test
    fun badChecksumRejected() {
        val bytes = AdbPacket(AdbPacket.A_WRTE, 1, 1, "hello".toByteArray()).encode()
        bytes[24] = (bytes[24].toInt() xor 0xFF).toByte()
        val ex = assertThrows(AdbProtocolException::class.java) { read(bytes) }
        assertTrue(ex.message!!.contains("bad checksum"))
    }

    @Test
    fun oversizedLengthRejected() {
        val bytes = ByteArray(24)
        // command = CNXN, magic 正确，但长度写 2MB
        val command = AdbPacket.A_CNXN
        bytes[0] = (command and 0xFF).toByte()
        bytes[1] = ((command shr 8) and 0xFF).toByte()
        bytes[2] = ((command shr 16) and 0xFF).toByte()
        bytes[3] = ((command shr 24) and 0xFF).toByte()
        val length = AdbPacket.MAX_PAYLOAD + 1
        bytes[12] = (length and 0xFF).toByte()
        bytes[13] = ((length shr 8) and 0xFF).toByte()
        bytes[14] = ((length shr 16) and 0xFF).toByte()
        bytes[15] = ((length shr 24) and 0xFF).toByte()
        val magic = command.inv()
        bytes[20] = (magic and 0xFF).toByte()
        bytes[21] = ((magic shr 8) and 0xFF).toByte()
        bytes[22] = ((magic shr 16) and 0xFF).toByte()
        bytes[23] = ((magic shr 24) and 0xFF).toByte()
        val ex = assertThrows(AdbProtocolException::class.java) { read(bytes) }
        assertTrue(ex.message!!.contains("bad payload length"))
    }

    @Test
    fun checksumMatchesAdbUnsignedByteSum() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(477, AdbPacket.checksum(data, 0, data.size))
        assertEquals(383, AdbPacket.checksum(byteArrayOf(0xff.toByte(), 0x80.toByte()), 0, 2))
    }

    @Test
    fun cnxnMatchesIndependentWireFixture() {
        // Legacy ADB CNXN: version 0x01000000, maxdata 256 KiB, host:: NUL, sum 562.
        val fixture = "43 4e 58 4e 00 00 00 01 00 00 04 00 07 00 00 00 32 02 00 00 bc b1 a7 b1 68 6f 73 74 3a 3a 00"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val packet = AdbPacket(AdbPacket.A_CNXN, AdbPacket.A_VERSION, AdbPacket.MAX_DATA, "host::\u0000".toByteArray())
        assertArrayEquals(fixture, packet.encode())
        assertArrayEquals(packet.payload, read(fixture).payload)
    }
}
