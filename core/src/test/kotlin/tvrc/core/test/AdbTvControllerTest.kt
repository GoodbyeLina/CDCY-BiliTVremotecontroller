package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.adb.AdbKeyStore
import tvrc.core.adb.AdbTransport
import tvrc.core.adb.AdbTvController
import tvrc.core.adb.ShellResult
import java.nio.file.Files

/** P1-1：逐字断言下发命令、命令顺序、keyevent 85 补发次数与失败回执。 */
class AdbTvControllerTest {

    private val fake = FakeAdbClient()
    private val transport = AdbTransport(
        clientFactory = { fake },
        keyStore = AdbKeyStore(Files.createTempDirectory("adbkey-p1").toFile()),
        defaultTimeoutMs = 1_000
    )
    private val controller = AdbTvController(
        transport = transport
    )

    private fun ok(stdout: String = "") = ShellResult(0, stdout, "")
    private fun fail(stderr: String) = ShellResult(-1, "", stderr)

    @Test
    fun wakeSendsKeyevent224() {
        val ack = controller.wake()
        assertTrue(ack.ok)
        assertEquals(listOf("input keyevent 224"), fake.commands)
    }

    @Test
    fun wakeFailurePropagatesDetail() {
        fake.respond({ it == "input keyevent 224" }) { fail("timeout after 25s") }
        val ack = controller.wake()
        assertFalse(ack.ok)
        assertEquals("timeout after 25s", ack.detail)
    }

    @Test
    fun launchDoesNotToggleGlobalPlaybackEvenWhenMpvIsAlive() {
        fake.respond({ it.startsWith("pidof ") }) { ok("4321\n") }
        val ack = controller.launch("http://192.168.10.241:8100/live/5017134")

        assertTrue(ack.ok)
        assertEquals(
            "am start -a android.intent.action.VIEW -d http://192.168.10.241:8100/live/5017134 -n is.xyz.mpv/.MPVActivity",
            fake.commands.first()
        )
        assertEquals(1, fake.commands.size, "起播只发目标 Intent，不触碰全局媒体会话")
        assertTrue(ack.detail.contains("no global media keys"))
    }

    @Test
    fun launchEscapeSpecialCharsInUrl() {
        fake.respond({ it.startsWith("pidof ") }) { ok("") }
        controller.launch("http://192.168.10.241:8100/video/BV1xx?part=1&t=2")

        val amStart = fake.commands.first()
        assertTrue(amStart.contains("BV1xx\\?part=1\\&t=2"), "URL 里的 ? 和 & 必须转义：$amStart")
    }

    @Test
    fun launchFailureShortCircuitsNoReplenish() {
        fake.respond({ it.startsWith("am start") }) { fail("Activity class does not exist") }
        val ack = controller.launch("http://h:8100/live/1")

        assertFalse(ack.ok)
        assertEquals("Activity class does not exist", ack.detail)
        assertEquals(1, fake.commands.size, "起播失败不应再发补发命令")
        assertEquals(0, fake.commands.count { it == "input keyevent 85" })
    }

    @Test
    fun launchDoesNotSendMediaKeysWhenMpvIsNotAlive() {
        fake.respond({ it.startsWith("pidof ") }) { ok("") }
        fake.respond({ it == "ps -A" }) { ok("PID USER NAME\n") }
        fake.respond({ it.startsWith("ps | grep") }) { ok("") }
        val ack = controller.launch("http://h:8100/live/1")

        assertTrue(ack.ok)
        assertEquals(0, fake.commands.count { it == "input keyevent 85" }, "进程已死不补发")
        assertTrue(ack.detail.contains("no global media keys"))
    }

    @Test
    fun stopSendsForceStop() {
        val ack = controller.stop()
        assertTrue(ack.ok)
        assertEquals(listOf("am force-stop is.xyz.mpv"), fake.commands)
    }

    @Test
    fun probeAliveHitsPidofOnly() {
        fake.respond({ it == "pidof is.xyz.mpv" }) { ok("4321\n") }
        val status = controller.probe()
        assertTrue(status.alive)
        assertEquals(listOf("pidof is.xyz.mpv"), fake.commands, "pidof 命中即止，不回退")
    }

    @Test
    fun probeFallsBackToPsDashA() {
        fake.respond({ it == "pidof is.xyz.mpv" }) { ok("") }
        fake.respond({ it == "ps -A" }) { ok("PID USER NAME\n1024 u0_a123 is.xyz.mpv\n") }
        val status = controller.probe()
        assertTrue(status.alive)
        assertEquals(listOf("pidof is.xyz.mpv", "ps -A"), fake.commands)
    }

    @Test
    fun probeFallsBackToLegacyPsGrep() {
        fake.respond({ it == "pidof is.xyz.mpv" }) { ok("") }
        fake.respond({ it == "ps -A" }) { ok("PID USER NAME\n") }
        fake.respond({ it.startsWith("ps | grep") }) { ok("u0_a123 1024 4 5 is.xyz.mpv\n") }
        val status = controller.probe()
        assertTrue(status.alive)
        assertEquals(
            listOf("pidof is.xyz.mpv", "ps -A", "ps | grep is.xyz.mpv"),
            fake.commands,
            "三级回退顺序必须固定"
        )
    }

    @Test
    fun probeAllFailMeansNotAlive() {
        val status = controller.probe()
        assertFalse(status.alive)
        assertFalse(status.playing, "进程都不在，一定不在播")
    }

    // ---- P1-2：playing 由 dumpsys media_session 判定 ----

    private fun controllerWith(dump: String) = AdbTvController(
        transport = transport,
        mediaSession = object : tvrc.core.media.MediaSessionTextSource {
            override fun dumpMediaSessions(): String = dump
        }
    )

    @Test
    fun probePlayingWhenMediaSessionStateIs3() {
        fake.respond({ it == "pidof is.xyz.mpv" }) { ok("4321\n") }
        val dump = "Media sessions:\n" +
            "  Session 2uhash: is.xyz.mpv/.MPVActivity (flag 0)\n" +
            "      active true\n" +
            "      PlaybackState: state=3, position=12000\n"
        val status = controllerWith(dump).probe()
        assertTrue(status.alive)
        assertTrue(status.playing, "state=3 必须判成在播，否则看门狗会把正常播放误判卡死")
        // dumpsys 走注入的 MediaSessionTextSource，不经过 adb，所以命令表里只有 pidof
        assertEquals(listOf("pidof is.xyz.mpv"), fake.commands)
    }

    @Test
    fun probeNotPlayingWhenMediaSessionStateIs2() {
        fake.respond({ it == "pidof is.xyz.mpv" }) { ok("4321\n") }
        val dump = "Media sessions:\n  Session 1: is.xyz.mpv/.MPVActivity\n      PlaybackState: state=2, position=0\n"
        val status = controllerWith(dump).probe()
        assertTrue(status.alive)
        assertFalse(status.playing, "state=2 是暂停，不算在播")
    }

    @Test
    fun probeSkipsDumpWhenProcessGone() {
        val status = controllerWith("state=3").probe()
        assertFalse(status.alive)
        assertFalse(fake.commands.any { it.startsWith("dumpsys") }, "进程已死不该再去读 media_session")
    }

    @Test
    fun probeSwallowsDumpFailure() {
        fake.respond({ it == "pidof is.xyz.mpv" }) { ok("4321\n") }
        val status = AdbTvController(
            transport = transport,
                mediaSession = object : tvrc.core.media.MediaSessionTextSource {
                override fun dumpMediaSessions(): String = throw IllegalStateException("dumpsys permission denied")
            }
        ).probe()
        assertTrue(status.alive)
        assertFalse(status.playing, "dumpsys 报错只能算「未知」，不能抛异常打断引擎")
    }
}
