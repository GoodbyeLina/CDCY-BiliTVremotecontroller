package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.ErrorKind
import tvrc.core.Mode
import tvrc.core.PlaybackState
import tvrc.core.Session
import tvrc.core.TvStatus
import tvrc.core.engine.PlaybackEngine

/** PlaybackEngine 的虚拟时间测试：0 真等待、0 真机。 */
class PlaybackEngineTest {

    private fun session(switchSeconds: Long = 60L) =
        Session(rooms = listOf("a", "b", "c"), switchSeconds = switchSeconds, mode = Mode.LIVE)

    private class Fixture(idleTimeoutMs: Long = 0L) {
        val clock = VirtualClock(1_000L)
        val scheduler = VirtualScheduler(clock)
        val tv = FakeTv()
        val states = mutableListOf<PlaybackState>()
        val messages = mutableListOf<Pair<String, String>>()
        var idleShutdowns = 0
        val engine = PlaybackEngine(
            tv = tv,
            scheduler = scheduler,
            clock = clock,
            idleTimeoutMs = idleTimeoutMs,
            onState = { states.add(it) },
            onMessage = { code, text -> messages.add(code to text) },
            onIdleShutdown = { idleShutdowns++ }
        )
    }

    /** 1. 起播：命令先 wake 再 launch，状态通知 Starting → Playing。 */
    @Test
    fun startReachesPlayingWithExpectedCommands() {
        val f = Fixture()
        f.engine.start(session())

        val s = f.engine.currentState
        assertTrue(s is PlaybackState.Playing, "应为 Playing，实际 $s")
        assertEquals(0, (s as PlaybackState.Playing).index)
        assertEquals(listOf("wake", "launch:a"), f.tv.commands)
        assertTrue(f.states.first() is PlaybackState.Starting)
        assertTrue(f.states.last() is PlaybackState.Playing)
    }

    /** 2. 到点自动换台到下一项。 */
    @Test
    fun switchTimerMovesToNextItem() {
        val f = Fixture()
        f.engine.start(session())
        f.scheduler.advanceBy(60_000L)

        val s = f.engine.currentState
        assertTrue(s is PlaybackState.Playing, "应为 Playing，实际 $s")
        assertEquals(1, (s as PlaybackState.Playing).index)
        assertTrue(f.tv.commands.contains("launch:b"), "应已下发第二项：${f.tv.commands}")
    }

    /** 3. 连续 3 次探测掉线 → TV_LOST 换台。 */
    @Test
    fun threeMissesTriggerTvLostSwitch() {
        val f = Fixture()
        f.engine.start(session())
        f.tv.status = TvStatus(alive = false, playing = false)

        f.scheduler.advanceBy(5_000L)
        assertEquals(1, (f.engine.currentState as PlaybackState.Playing).missCount)
        f.scheduler.advanceBy(5_000L)
        assertEquals(2, (f.engine.currentState as PlaybackState.Playing).missCount)
        f.scheduler.advanceBy(5_000L)

        val s = f.engine.currentState
        assertTrue(s is PlaybackState.Playing, "换到下一项后应回到 Playing，实际 $s")
        assertEquals(1, (s as PlaybackState.Playing).index)
        assertTrue(f.tv.commands.contains("launch:b"))
    }

    /** 4. 在线时探测链不断，状态保持 Playing。 */
    @Test
    fun healthyProbeKeepsPlayingAndReschedules() {
        val f = Fixture()
        f.engine.start(session())
        val before = f.tv.commands.count { it == "probe" }

        f.scheduler.advanceBy(15_000L)

        assertTrue(f.engine.currentState is PlaybackState.Playing)
        assertEquals(0, (f.engine.currentState as PlaybackState.Playing).missCount)
        assertTrue(f.tv.commands.count { it == "probe" } >= before + 3, "15 秒内应探测 ≥3 次")
        assertTrue(f.scheduler.pendingCount() > 0)
    }

    /** 5. 用户停止：进 Idle、下发 stop、定时器全清，之后不再有任何命令。 */
    @Test
    fun userStopCancelsAllTimers() {
        val f = Fixture()
        f.engine.start(session())
        f.engine.stop()

        assertTrue(f.engine.currentState is PlaybackState.Idle)
        assertTrue(f.tv.commands.contains("stop"))
        val n = f.tv.commands.size

        f.scheduler.advanceBy(200_000L)
        assertEquals(n, f.tv.commands.size, "停止后不应再有任何设备命令")
        assertEquals(0, f.scheduler.pendingCount())
    }

    /** 6. 空闲超时回调只触发一次。 */
    @Test
    fun idleTimeoutFiresOnce() {
        val f = Fixture(idleTimeoutMs = 1_000L)
        f.engine.start(session())
        f.engine.stop()

        f.scheduler.advanceBy(1_000L)
        assertEquals(1, f.idleShutdowns)
        f.scheduler.advanceBy(10_000L)
        assertEquals(1, f.idleShutdowns, "空闲超时是一次性回调")
    }

    /** 7. 冷却期内手动重试被拦；冷却过后重试成功。 */
    @Test
    fun retryRespectsCooldown() {
        val f = Fixture()
        f.tv.enqueueLaunch(false, false, false)
        f.engine.start(session())

        f.scheduler.advanceBy(2_000L)
        f.scheduler.advanceBy(4_000L)

        val err = f.engine.currentState
        assertTrue(err is PlaybackState.Error, "三次起播失败应进 Error，实际 $err")
        assertEquals(ErrorKind.TV_UNREACHABLE, (err as PlaybackState.Error).kind)

        f.engine.retry()
        assertTrue(f.messages.any { it.first == "COOLDOWN" }, "冷却期内应提示冷却")

        f.scheduler.advanceBy(60_000L)
        f.engine.retry()
        val after = f.engine.currentState
        assertTrue(after is PlaybackState.Playing, "冷却后重试应恢复播放，实际 $after；cmd=${f.tv.commands}；msg=${f.messages}")
    }

    /** 8. 换台时起播失败 → Error(PLAY_FAILED) 并给出用户提示。 */
    @Test
    fun switchingFailureEntersPlayFailedError() {
        val f = Fixture()
        f.engine.start(session())
        f.tv.enqueueLaunch(false)

        f.scheduler.advanceBy(60_000L)

        val s = f.engine.currentState
        assertTrue(s is PlaybackState.Error, "换台失败应进 Error，实际 $s")
        assertEquals(ErrorKind.PLAY_FAILED, (s as PlaybackState.Error).kind)
        assertTrue(f.messages.any { it.first == "PLAY_FAILED" })
    }
}
