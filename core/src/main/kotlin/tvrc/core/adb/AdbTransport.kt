package tvrc.core.adb

import java.io.Closeable
import java.io.IOException
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * adb 能力层：把"串行 / 超时 / 断线重连 / 密钥 / 命令封装"这些最容易踩的坑集中在一处。
 *
 * 设计要点（对应 K §4 事实）：
 * - F-01：绝不发 start-server / adb connect，直连方案由 [AdbClient] 实现负责；
 * - F-11：所有命令经同一把公平锁串行，且每条带超时；
 * - F-02：RSA 私钥由 [AdbKeyStore] 持久化（600），重启不再弹授权；
 * - F-05：am start 的 URL 必须转义 & 和 ?；
 * - F-04：存活判定 pidof → ps -A → ps | grep 三级回退。
 */
class AdbTransport(
    private val clientFactory: () -> AdbClient,
    private val keyStore: AdbKeyStore,
    private val keyName: String = "unknown@tvrc",
    private val defaultTimeoutMs: Long = 25_000,
    private val connectAttempts: Int = 3,
    private val retryOnLoss: Boolean = true,
    private val lockTimeoutMs: Long = 30_000
) : Closeable {

    private val lock = ReentrantLock(true)

    @Volatile
    private var client: AdbClient? = null

    @Volatile
    var connectCount: Int = 0
        private set

    @Volatile
    var commandCount: Long = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    val keyPair: KeyPair by lazy { keyStore.loadOrCreate() }

    val publicKeyBase64: String by lazy {
        AdbKeyStore.adbPublicKeyBase64(keyPair.public as RSAPublicKey)
    }

    fun isConnected(): Boolean = client != null

    fun ensureConnected(): Boolean {
        if (client != null) return true
        var attempt = 0
        while (attempt < connectAttempts) {
            attempt++
            val candidate = clientFactory()
            val ok = runCatching { candidate.connect() }.getOrElse {
                lastError = it.message ?: it.javaClass.simpleName
                false
            }
            if (ok) {
                client = candidate
                connectCount++
                lastError = null
                return true
            }
            runCatching { candidate.close() }
        }
        lastError = "connect failed after $connectAttempts attempts: " + (lastError ?: "ADB authentication rejected")
        return false
    }

    fun shell(command: String, timeoutMs: Long = defaultTimeoutMs): ShellResult {
        require(!command.contains("start-server")) { "forbidden: adb start-server (F-01)" }
        require(!LEGACY_CONNECT.containsMatchIn(command)) { "forbidden: adb connect (F-01)" }

        if (!lock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS)) {
            return ShellResult(-1, "", "busy: another adb command is still running")
        }
        try {
            if (!ensureConnected()) return ShellResult(-1, "", lastError ?: "not connected")
            val current = client ?: return ShellResult(-1, "", "not connected")
            return try {
                commandCount++
                current.shell(command, timeoutMs)
            } catch (e: AdbTimeoutException) {
                lastError = e.message
                ShellResult(-1, "", "timeout: ${e.message}")
            } catch (e: IOException) {
                lastError = e.message
                runCatching { current.close() }
                client = null
                if (!retryOnLoss) {
                    ShellResult(-1, "", "io: ${e.message}")
                } else {
                    if (!ensureConnected()) {
                        ShellResult(-1, "", "reconnect failed: $lastError")
                    } else {
                        val retried = client!!
                        try {
                            commandCount++
                            retried.shell(command, timeoutMs)
                        } catch (e2: Exception) {
                            lastError = e2.message
                            ShellResult(-1, "", "io-after-reconnect: ${e2.message}")
                        }
                    }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    fun getProp(name: String): String = shell("getprop $name").stdout.trim()

    fun keyEvent(code: Int): ShellResult = shell("input keyevent $code")

    /** keyevent 224 = KEYCODE_WAKEUP，点亮的是电视自己的屏。 */
    fun wake(): ShellResult = keyEvent(224)

    fun stopApp(pkg: String): ShellResult = shell("am force-stop $pkg")

    fun startVideo(url: String, component: String): ShellResult =
        shell("am start -a android.intent.action.VIEW -d ${escapeForShell(url)} -n $component")

    /** 存活判定：pidof → ps -A → ps | grep（F-04，Android 8+ ps 只列 shell 用户）。 */
    fun isAlive(pkg: String): Boolean {
        val pidof = shell("pidof $pkg")
        if (pidof.ok && pidof.stdout.trim().isNotEmpty()) return true

        val ps = shell("ps -A")
        if (ps.ok && ps.stdout.lineSequence().any { it.contains(pkg) }) return true

        val grep = shell("ps | grep $pkg")
        return grep.ok && grep.stdout.lineSequence().any { it.contains(pkg) && !it.contains("grep") }
    }

    /**
     * 换台后 mpv 可能停在暂停态（F-03）：读状态→补 keyevent 85，最多 [attempts] 次。
     * 返回实际补发次数。sleeper 可注入，测试里不真睡 1.5 秒。
     */
    fun replenishPlayState(
        pkg: String,
        attempts: Int = 3,
        sleeper: (Long) -> Unit = { Thread.sleep(it) }
    ): Int {
        var sent = 0
        var i = 0
        while (i < attempts) {
            if (!isAlive(pkg)) return sent
            keyEvent(85)
            sent++
            i++
            if (i < attempts) sleeper(1_500L)
        }
        return sent
    }

    override fun close() {
        runCatching { client?.close() }
        client = null
    }

    companion object {
        private val LEGACY_CONNECT = Regex("""(^|\s)adb\s+connect\s""")
        private val SHELL_SPECIALS = charArrayOf(
            '&', '?', ' ', '\'', '"', '(', ')', '|', ';', '<', '>', '$', '`', '\\', '!', '*', '#', '~', '\n'
        )

        /** dadb shell 会把整串交给远端 shell，所以 & ? 空格等必须转义（F-05）。 */
        fun escapeForShell(raw: String): String {
            val sb = StringBuilder(raw.length + 8)
            for (ch in raw) {
                if (SHELL_SPECIALS.contains(ch)) sb.append('\\')
                sb.append(ch)
            }
            return sb.toString()
        }
    }
}
