package tvrc.core.adb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * 真实 adb 传输层：直连设备的 5555 端口，自己走 ADB 二进制协议（F-01：不要 adb server、不要 start-server、不要 adb connect）。
 *
 * 握手：CNXN → (AUTH token → AUTH signature → 若仍未授权则 AUTH RSAPUBLICKEY) → CNXN。
 * 命令：OPEN("shell:xxx\0") → OKAY → 若干 WRTE（数据）→ CLSE。
 *
 * 注意：legacy shell 服务不带退出码，[shell] 的 exitCode 固定为 0；要退出码用 [shellWithExitCode]。
 */
class DadbClient(
    private val host: String,
    private val port: Int = 5555,
    private val keyStore: AdbKeyStore,
    private val connectTimeoutMs: Int = 5_000,
    private val banner: String = "host::tvrc",
    private val authorizationTimeoutMs: Int = 30_000
) : AdbClient {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var keyPair: KeyPair? = null
    private var nextLocalId: Int = 1

    override fun connect(): Boolean {
        closeQuietly()
        return try {
            connectAndAuthenticate().also { if (!it) closeQuietly() }
        } catch (e: Exception) {
            closeQuietly()
            throw e
        }
    }

    private fun connectAndAuthenticate(): Boolean {
        val s = Socket()
        socket = s
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = connectTimeoutMs
        s.tcpNoDelay = true
        input = DataInputStream(BufferedInputStream(s.getInputStream()))
        output = BufferedOutputStream(s.getOutputStream())

        val kp = keyStore.loadOrCreate()
        keyPair = kp

        send(
            AdbPacket(
                AdbPacket.A_CNXN,
                AdbPacket.A_VERSION,
                AdbPacket.MAX_DATA,
                (banner + "\u0000").toByteArray(Charsets.UTF_8)
            )
        )

        var tokenRounds = 0
        while (true) {
            val packet = try {
                AdbPacket.read(input!!)
            } catch (e: SocketTimeoutException) {
                val stage = when (tokenRounds) {
                    0 -> "等待设备首包（未收到 CNXN/AUTH）"
                    1 -> "已发送 AUTH 签名，等待设备响应"
                    else -> "已发送公钥，等待电视授权确认；请在电视弹窗中选择允许后重试"
                }
                val timeout = if (tokenRounds >= 2) authorizationTimeoutMs else connectTimeoutMs
                throw AdbTimeoutException("ADB 握手超时：$host:$port；$stage（${timeout}ms）").apply {
                    initCause(e)
                }
            }
            when (packet.command) {
                AdbPacket.A_CNXN -> return true
                AdbPacket.A_AUTH -> {
                    if (packet.arg0 != AdbPacket.AUTH_TOKEN) return false
                    tokenRounds++
                    if (tokenRounds == 1) {
                        val signature = AdbAuth.signToken(kp.private as PrivateKey, packet.payload)
                        send(AdbPacket(AdbPacket.A_AUTH, AdbPacket.AUTH_SIGNATURE, 0, signature))
                    } else if (tokenRounds == 2) {
                        val payload = AdbKeyStore.adbPublicKeyWire(kp.public as RSAPublicKey)
                        send(AdbPacket(AdbPacket.A_AUTH, AdbPacket.AUTH_RSAPUBLICKEY, 0, payload))
                        s.soTimeout = authorizationTimeoutMs
                    } else {
                        return false
                    }
                }
                else -> throw AdbProtocolException(
                    "unexpected " + packet.commandName() + " during handshake"
                )
            }
        }
    }

    override fun shell(command: String, timeoutMs: Long): ShellResult =
        shellRaw(command, timeoutMs)

    /**
     * legacy shell 没有退出码：包一层 echo 取 \$?，再把标记行从输出里摘掉。
     * 这样上层能区分「命令执行了但失败」（exitCode != 0）与「连不上/超时」（异常）。
     */
    fun shellWithExitCode(command: String, timeoutMs: Long): ShellResult {
        val marker = "__TVRC_EXIT__"
        val wrapped = command + "; echo " + marker + "\$?"
        val raw = shellRaw(wrapped, timeoutMs)
        var exitCode = 0
        val kept = ArrayList<String>()
        for (line in raw.stdout.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.startsWith(marker)) {
                exitCode = trimmed.substring(marker.length).trim().toIntOrNull() ?: 0
            } else {
                kept.add(line)
            }
        }
        var text = kept.joinToString("\n")
        if (text.endsWith("\n")) text = text.substring(0, text.length - 1)
        return ShellResult(exitCode, text, "")
    }

    private fun shellRaw(command: String, timeoutMs: Long): ShellResult {
        val inp = input ?: throw IOException("not connected: call connect() first")
        val localId = nextLocalId++
        send(
            AdbPacket(
                AdbPacket.A_OPEN,
                localId,
                0,
                ("shell:" + command + "\u0000").toByteArray(Charsets.UTF_8)
            )
        )
        socket?.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val out = ByteArrayOutputStream()
        try {
            while (true) {
                val packet = AdbPacket.read(inp)
                when (packet.command) {
                    AdbPacket.A_OKAY -> Unit
                    AdbPacket.A_WRTE -> {
                        out.write(packet.payload)
                        send(AdbPacket(AdbPacket.A_OKAY, localId, packet.arg0, EMPTY))
                    }
                    AdbPacket.A_CLSE -> return ShellResult(0, out.toString("UTF-8"), "")
                    else -> Unit
                }
            }
        } catch (e: SocketTimeoutException) {
            throw AdbTimeoutException("shell timeout after " + timeoutMs + "ms: " + command)
        }
    }

    private fun send(packet: AdbPacket) {
        val os = output ?: throw IOException("not connected")
        os.write(packet.encode())
        os.flush()
    }

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}
