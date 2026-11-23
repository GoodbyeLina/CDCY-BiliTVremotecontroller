package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.ErrorKind
import tvrc.core.diag.ErrorCode
import tvrc.core.diag.Watchdog
import tvrc.core.diag.WatchdogDecision
import tvrc.core.diag.WatchdogInput

class DiagnosticsTest {

    @Test
    fun everyErrorCodeHasFullUserText() {
        assertTrue(ErrorCode.entries.size >= 12, "至少 12 个错误码，实际 " + ErrorCode.entries.size)
        for (c in ErrorCode.entries) {
            assertTrue(c.code.matches(Regex("E-[A-Z]+-\\d{2}")), "错误码格式不对：" + c.code)
            assertTrue(c.title.length in 2..20, c.code + " title 长度异常：" + c.title)
            assertTrue(c.cause.length > 6, c.code + " 缺原因")
            assertTrue(c.action.length > 6, c.code + " 缺做法")
            assertTrue(!c.action.contains("联系客服"), c.code + " 不许写「联系客服」这种废话")
        }
    }

    @Test
    fun playbackErrorsMapToCatalog() {
        for (k in ErrorKind.entries) {
            val c = ErrorCode.of(k)
            assertNotNull(ErrorCode.byCode(c.code))
            val text = ErrorCode.describe(k)
            assertTrue(text.contains("怎么办"), "UI 文案必须带怎么做：" + text)
            assertTrue(text.length < 200, "一行放不下：" + text.length)
        }
    }

    @Test
    fun unknownCodeLookupIsNull() {
        assertEquals(null, ErrorCode.byCode("E-NOPE-99"))
    }

    @Test
    fun watchdogIgnoresIdle() {
        val d = Watchdog.decide(WatchdogInput(nowMs = 100_000, lastProgressMs = 0, playing = false, restartCount = 0, lastRestartMs = 0))
        assertEquals(WatchdogDecision.Nothing, d)
    }

    @Test
    fun watchdogWithoutReliableMediaProgressNeverRestarts() {
        val input = WatchdogInput(nowMs = 10_000_000, lastProgressMs = 0,
            playing = true, restartCount = 0, lastRestartMs = 0)
        assertEquals(WatchdogDecision.Nothing, Watchdog.decide(input))
    }

    @Test
    fun watchdogRestartsOnStall() {
        val d = Watchdog.decide(WatchdogInput(nowMs = 61_000, lastProgressMs = 0, playing = true, progressReliable = true, restartCount = 0, lastRestartMs = 0))
        assertTrue(d is WatchdogDecision.Restart, d.toString())
        assertEquals(1, (d as WatchdogDecision.Restart).attempt)
        assertEquals(61_000, d.stalledForMs)
    }

    @Test
    fun watchdogRespectsCooldown() {
        val d = Watchdog.decide(
            WatchdogInput(nowMs = 100_000, lastProgressMs = 10_000, playing = true, progressReliable = true, restartCount = 1, lastRestartMs = 70_000)
        )
        assertEquals(WatchdogDecision.Nothing, d, "距上次重启 30s < 60s 冷却，不许再重启")
    }

    @Test
    fun watchdogGivesUpAfterThreeRestarts() {
        val d = Watchdog.decide(
            WatchdogInput(nowMs = 500_000, lastProgressMs = 400_000, playing = true, progressReliable = true, restartCount = 3, lastRestartMs = 0)
        )
        assertTrue(d is WatchdogDecision.GiveUp, d.toString())
        assertTrue((d as WatchdogDecision.GiveUp).reason.contains("3"))
    }
}
