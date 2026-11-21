package tvrc.core.adb

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

class AdbProtocolException(message: String) : IOException(message)

/**
 * ADB 二进制协议的一帧。
 *
 * 帧结构（全部小端）：
 *   command(4) | arg0(4) | arg1(4) | data_length(4) | data_check(4) | magic(4) | payload(data_length)
 * data_check 是无符号字节累加和（旧文档将字段命名为 data_crc32，但并非 CRC32）。
 *   magic = command 按位取反（用来挡住错位/串包）
 */
data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray) {

    fun encode(): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        putInt(out, 0, command)
        putInt(out, 4, arg0)
        putInt(out, 8, arg1)
        putInt(out, 12, payload.size)
        putInt(out, 16, checksum(payload, 0, payload.size))
        putInt(out, 20, command.inv())
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    fun commandName(): String = when (command) {
        A_CNXN -> "CNXN"
        A_AUTH -> "AUTH"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_WRTE -> "WRTE"
        A_CLSE -> "CLSE"
        else -> "0x" + Integer.toHexString(command)
    }

    companion object {
        const val HEADER_SIZE = 24

        /** 单帧上限，防被坏数据撑爆内存。 */
        const val MAX_PAYLOAD = 1024 * 1024

        const val A_CNXN = 0x4e584e43   // CNXN
        const val A_AUTH = 0x48545541   // AUTH
        const val A_OPEN = 0x4e45504f   // OPEN
        const val A_OKAY = 0x59414b4f   // OKAY
        const val A_WRTE = 0x45545257   // WRTE
        const val A_CLSE = 0x45534c43   // CLSE

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        const val A_VERSION = 0x01000000
        const val MAX_DATA = 256 * 1024

        fun checksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0
            for (i in offset until offset + length) sum += data[i].toInt() and 0xff
            return sum
        }

        fun read(input: DataInputStream): AdbPacket {
            val header = ByteArray(HEADER_SIZE)
            try {
                input.readFully(header)
            } catch (e: EOFException) {
                throw AdbProtocolException("connection closed while reading header")
            }
            val command = getInt(header, 0)
            val arg0 = getInt(header, 4)
            val arg1 = getInt(header, 8)
            val length = getInt(header, 12)
            val expectedChecksum = getInt(header, 16)
            val magic = getInt(header, 20)
            if (magic != command.inv()) {
                throw AdbProtocolException("bad magic for command 0x" + Integer.toHexString(command))
            }
            if (length < 0 || length > MAX_PAYLOAD) {
                throw AdbProtocolException("bad payload length " + length)
            }
            val payload = ByteArray(length)
            try {
                input.readFully(payload)
            } catch (e: EOFException) {
                throw AdbProtocolException("connection closed while reading payload")
            }
            if (checksum(payload, 0, length) != expectedChecksum) {
                throw AdbProtocolException("bad checksum for command 0x" + Integer.toHexString(command))
            }
            return AdbPacket(command, arg0, arg1, payload)
        }

        private fun putInt(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value shr 8) and 0xFF).toByte()
            target[offset + 2] = ((value shr 16) and 0xFF).toByte()
            target[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        private fun getInt(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xFF) or
                ((source[offset + 1].toInt() and 0xFF) shl 8) or
                ((source[offset + 2].toInt() and 0xFF) shl 16) or
                ((source[offset + 3].toInt() and 0xFF) shl 24)
    }
}
