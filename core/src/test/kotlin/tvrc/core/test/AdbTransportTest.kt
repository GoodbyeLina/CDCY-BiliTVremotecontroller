package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tvrc.core.adb.AdbKeyStore
import tvrc.core.adb.AdbTimeoutException
import tvrc.core.adb.AdbTransport
import tvrc.core.adb.ShellResult
import java.io.File
import java.io.IOException
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AdbTransportTest {

    @Test
    fun connectionFailureKeepsTheOriginalCause(@TempDir dir: File) {
        val t = AdbTransport({ object : tvrc.core.adb.AdbClient {
            override fun connect(): Boolean = throw IOException("connection refused at tv:5555")
            override fun shell(command: String, timeoutMs: Long) = ShellResult(0)
            override fun close() = Unit
        } }, AdbKeyStore(dir), connectAttempts = 1)
        val result = t.shell("echo test")
        assertFalse(result.ok)
        assertTrue(result.stderr.contains("connection refused at tv:5555"))
    }

    private fun transport(fake: FakeAdbClient, dir: File, retry: Boolean = true): AdbTransport =
        AdbTransport({ fake }, AdbKeyStore(dir), defaultTimeoutMs = 5_000, retryOnLoss = retry)

    /** 1. 并发 8 条命令必须串行（F-11）。 */
    @Test
    fun concurrentCommandsAreSerialized(@TempDir dir: File) {
        val fake = FakeAdbClient(latencyMs = 20)
        val t = transport(fake, dir)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..8).map { i ->
            pool.submit(Callable { start.await(); t.shell("getprop p$i") })
        }
        start.countDown()
        for (f in futures) assertTrue(f.get(20, TimeUnit.SECONDS).ok)
        pool.shutdown()

        assertEquals(1, fake.maxConcurrent, "同一时刻只允许一条 adb 命令")
        assertEquals(8, fake.commands.size)
    }

    /** 2. 超时不重试，避免慢命令被放大成雪崩。 */
    @Test
    fun timeoutIsNotRetried(@TempDir dir: File) {
        val fake = FakeAdbClient()
        fake.failNextWith = AdbTimeoutException("timeout after 100ms")
        val t = transport(fake, dir)

        val first = t.shell("getprop ro.x", 100)
        assertFalse(first.ok)
        assertTrue(first.stderr.contains("timeout"))
        assertEquals(1, fake.connectCalls, "超时不得触发重连")

        assertTrue(t.shell("getprop ro.ok").ok, "超时之后应能继续工作")
        assertEquals(1, fake.connectCalls)
    }

    /** 3. IO 断线 → 重连一次 → 同一条命令重试成功。 */
    @Test
    fun ioLossReconnectsOnceAndRetries(@TempDir dir: File) {
        val fake = FakeAdbClient(connectResults = listOf(true, true))
        fake.failNextWith = IOException("broken pipe")
        val t = transport(fake, dir)

        val r = t.shell("getprop a")
        assertTrue(r.ok)
        assertEquals(2, fake.connectCalls)
        assertEquals(listOf("getprop a", "getprop a"), fake.commands.toList())
    }

    /** 4. 拒绝 adb server 语义（F-01）。 */
    @Test
    fun refusesLegacyAdbServerCommands(@TempDir dir: File) {
        val fake = FakeAdbClient()
        val t = transport(fake, dir)

        assertThrows<IllegalArgumentException> { t.shell("adb start-server") }
        assertThrows<IllegalArgumentException> { t.shell("adb connect 192.168.1.10:5555") }
        assertTrue(fake.commands.isEmpty())
    }

    /** 5. am start 的 URL 必须转义 & 和 ?（F-05）。 */
    @Test
    fun startVideoEscapesUrlAndBindsComponent(@TempDir dir: File) {
        val fake = FakeAdbClient()
        val t = transport(fake, dir)
        val url = "https://live.bilibili.com/5017134?x=1&y=2"

        t.startVideo(url, "is.xyz.mpv/.MPVActivity")

        val last = fake.commands.last()
        assertTrue(last.startsWith("am start -a android.intent.action.VIEW -d "))
        assertTrue(last.contains(AdbTransport.escapeForShell(url)))
        assertTrue(last.contains("\\?x=1\\&y=2"), "query 分隔符必须被反斜杠转义：$last")
        assertTrue(last.endsWith("-n is.xyz.mpv/.MPVActivity"))
    }

    /** 6. 存活判定三级回退（F-04）。 */
    @Test
    fun isAliveFallsBackPidofToPsToGrep(@TempDir dir: File) {
        val fake = FakeAdbClient()
        val t = transport(fake, dir)
        fake.respond({ it.startsWith("pidof ") }) { ShellResult(0, "", "") }
        fake.respond({ it == "ps -A" }) { ShellResult(1, "", "err") }
        fake.respond({ it.startsWith("ps | grep ") }) { ShellResult(0, "u0_a123 4321 1 is.xyz.mpv\n", "") }

        assertTrue(t.isAlive("is.xyz.mpv"))
        assertEquals(3, fake.commands.size, "应该依次尝试 pidof / ps -A / ps|grep")
    }

    /** 7. 全空 → 判定不在播。 */
    @Test
    fun isAliveFalseWhenAllChecksEmpty(@TempDir dir: File) {
        val fake = FakeAdbClient()
        val t = transport(fake, dir)
        assertFalse(t.isAlive("is.xyz.mpv"))
    }

    /** 8. 密钥持久化 + ADB 公钥 wire 格式正确（F-02）。 */
    @Test
    fun keyStorePersistsSameKeyAndBlobIsWellFormed(@TempDir dir: File) {
        val store = AdbKeyStore(dir)
        val kp1 = store.loadOrCreate()
        val kp2 = store.loadOrCreate()

        val m1 = (kp1.public as RSAPublicKey).modulus
        val m2 = (kp2.public as RSAPublicKey).modulus
        assertEquals(m1, m2, "两次加载必须是同一把密钥，否则每次重启都弹授权")
        assertTrue(File(store.privateKeyPath).isFile)

        val name = "test@tvrc"
        val blob = AdbKeyStore.adbPublicKeyBlob(kp1.public as RSAPublicKey)
        assertEquals(524, blob.size)
        val len = (blob[0].toInt() and 0xFF) or ((blob[1].toInt() and 0xFF) shl 8) or
            ((blob[2].toInt() and 0xFF) shl 16) or ((blob[3].toInt() and 0xFF) shl 24)
        assertEquals(64, len)
        val modulus = java.math.BigInteger(1, blob.copyOfRange(8, 264).reversedArray())
        assertEquals(m1, modulus)
        val rr = java.math.BigInteger(1, blob.copyOfRange(264, 520).reversedArray())
        assertEquals(java.math.BigInteger.ONE.shiftLeft(4096).mod(m1), rr)
        assertArrayEquals(byteArrayOf(1, 0, 1, 0), blob.copyOfRange(520, 524))

        val b64 = AdbKeyStore.adbPublicKeyBase64(kp1.public as RSAPublicKey)
        assertArrayEquals(blob, Base64.getDecoder().decode(b64))
        assertEquals("$b64 $name\u0000", String(AdbKeyStore.adbPublicKeyWire(kp1.public as RSAPublicKey, name)))
    }
}
