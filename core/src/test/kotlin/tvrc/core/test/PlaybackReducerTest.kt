package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.Effect
import tvrc.core.ErrorKind
import tvrc.core.Event
import tvrc.core.IdleReason
import tvrc.core.MAX_AUTO_RETRIES
import tvrc.core.Mode
import tvrc.core.PlaybackReducer
import tvrc.core.PlaybackState
import tvrc.core.Session
import tvrc.core.SwitchReason
import tvrc.core.TvStatus

/**
 * L1 单元测试：完全纯 JVM，不需要电视、不需要 Android、不需要网络。
 * 覆盖 H 方案四条 P0 修正与 K §4 的关键坑。
 */
class PlaybackReducerTest {

    private fun liveSession(
        rooms: List<String> = listOf("5017134", "5064126", "4374913"),
        switchSeconds: Long = 1800L
    ) = Session(rooms = rooms, switchSeconds = switchSeconds, mode = Mode.LIVE)

    /** 把 effects 里的设备命令收集起来，方便断言顺序。 */
    private class Harness(initial: PlaybackState = PlaybackState.Idle(0L)) {
        var state: PlaybackState = initial
            private set
        var effects: List<Effect> = emptyList()
            private set
        val commands = mutableListOf<String>()

        fun send(event: Event): PlaybackState {
            val t = PlaybackReducer.reduce(state, event)
            state = t.state
            effects = t.effects
            for (e in t.effects) when (e) {
                is Effect.WakeTv -> commands += "wake"
                is Effect.Launch -> commands += "launch:${e.target}"
                is Effect.StopTv -> commands += "stop"
                else -> Unit
            }
            return state
        }
    }

    private fun scheduleSwitchOf(effects: List<Effect>): Long? =
        effects.filterIsInstance<Effect.ScheduleSwitch>().firstOrNull()?.afterMs

    private fun scheduleRetryOf(effects: List<Effect>): Long? =
        effects.filterIsInstance<Effect.ScheduleRetry>().firstOrNull()?.afterMs

    // 1
    @Test
    fun startFromIdle_entersStarting_andSendsWakeThenLaunch() {
        val h = Harness()
        val s = h.send(Event.UserStart(1_000L, liveSession()))

        assertTrue(s is PlaybackState.Starting, "应进入 Starting")
        s as PlaybackState.Starting
        assertEquals(0, s.index)
        assertEquals(1, s.attempt)
        assertEquals(listOf("wake", "launch:5017134"), h.commands)
        assertTrue(h.effects.any { it is Effect.Notify })
    }

    // 2
    @Test
    fun startWithEmptySession_goesToNoContentError() {
        val h = Harness()
        val s = h.send(Event.UserStart(1_000L, Session()))

        assertTrue(s is PlaybackState.Error)
        s as PlaybackState.Error
        assertEquals(ErrorKind.NO_CONTENT, s.kind)
        assertEquals(null, s.session)
        assertTrue(h.effects.any { it is Effect.UserMessage && it.code == "NO_CONTENT" })
        assertTrue(h.commands.isEmpty(), "没有内容时不应下发任何设备命令")
    }

    // 3
    @Test
    fun ackOk_entersPlaying_withSwitchDelay() {
        val h = Harness()
        h.send(Event.UserStart(1_000L, liveSession(switchSeconds = 1800L)))
        val s = h.send(Event.TvAck(2_000L, ok = true))

        assertTrue(s is PlaybackState.Playing)
        s as PlaybackState.Playing
        assertEquals(0, s.index)
        assertEquals(0, s.missCount)
        assertEquals(2_000L + 1_800_000L, s.nextSwitchAtMs)
        assertEquals(1_800_000L, scheduleSwitchOf(h.effects))
    }

    // 4
    @Test
    fun threeStartFailures_giveUpToUnreachableError() {
        val h = Harness()
        h.send(Event.UserStart(0L, liveSession()))

        h.send(Event.TvAck(100L, ok = false, detail = "connect timeout"))
        var s = h.state as PlaybackState.Starting
        assertEquals(2, s.attempt)
        assertEquals(2_000L, scheduleRetryOf(h.effects))

        h.send(Event.TvAck(200L, ok = false))
        s = h.state as PlaybackState.Starting
        assertEquals(3, s.attempt)
        assertEquals(4_000L, scheduleRetryOf(h.effects))

        h.send(Event.TvAck(300L, ok = false))
        val err = h.state as PlaybackState.Error
        assertEquals(ErrorKind.TV_UNREACHABLE, err.kind)
        assertEquals(null, scheduleRetryOf(h.effects), "第三次失败后应放弃而不是继续排重试")
        assertTrue(h.effects.any { it is Effect.UserMessage && it.code == "TV_UNREACHABLE" })
        assertTrue(h.commands.contains("stop"))
    }

    // 5
    @Test
    fun tickBeforeDeadline_doesNotSwitch() {
        val h = Harness(PlaybackState.Playing(liveSession(), 0, 0L, 1_800_000L))
        val s = h.send(Event.Tick(1_799_999L))

        assertTrue(s is PlaybackState.Playing)
        assertTrue(h.effects.isEmpty(), "未到点不应产生任何副作用")
    }

    // 6
    @Test
    fun tickAtDeadline_switchesToNext_andLaunchesIt() {
        val h = Harness(PlaybackState.Playing(liveSession(), 0, 0L, 1_800_000L))
        val s = h.send(Event.Tick(1_800_000L))

        assertTrue(s is PlaybackState.Switching)
        s as PlaybackState.Switching
        assertEquals(0, s.fromIndex)
        assertEquals(1, s.toIndex)
        assertEquals(SwitchReason.SCHEDULED, s.reason)
        assertEquals(listOf("launch:5064126"), h.commands)
    }

    // 7
    @Test
    fun threeConsecutiveMisses_triggerSwitchAsTvLost() {
        val h = Harness(PlaybackState.Playing(liveSession(), 0, 0L, 1_800_000L))
        h.send(Event.TvReport(100L, alive = false, playing = false))
        assertEquals(1, (h.state as PlaybackState.Playing).missCount)
        h.send(Event.TvReport(200L, alive = false, playing = false))
        assertEquals(2, (h.state as PlaybackState.Playing).missCount)

        val s = h.send(Event.TvReport(300L, alive = false, playing = false))
        assertTrue(s is PlaybackState.Switching)
        s as PlaybackState.Switching
        assertEquals(SwitchReason.TV_LOST, s.reason)
        assertEquals(1, s.toIndex)
        assertEquals(listOf("launch:5064126"), h.commands)
    }

    // 8
    @Test
    fun aliveReport_resetsMissCount() {
        val h = Harness(PlaybackState.Playing(liveSession(), 0, 0L, 1_800_000L, missCount = 2))
        val s = h.send(Event.TvReport(100L, alive = true, playing = true))

        assertEquals(0, (s as PlaybackState.Playing).missCount)
        assertTrue(h.effects.any { it is Effect.ProbeTv }, "清零后应继续探测")
    }

    // 9
    @Test
    fun switchingAckOk_entersPlayingAtTarget_resetsMisses() {
        val h = Harness(PlaybackState.Switching(liveSession(), 0, 1, SwitchReason.SCHEDULED, 0L))
        val s = h.send(Event.TvAck(500L, ok = true))

        assertTrue(s is PlaybackState.Playing)
        s as PlaybackState.Playing
        assertEquals(1, s.index)
        assertEquals(0, s.missCount)
        assertEquals(500L + 1_800_000L, s.nextSwitchAtMs)
    }

    // 10
    @Test
    fun switchingAckFail_entersPlayFailedError_withCooldown() {
        val h = Harness(PlaybackState.Switching(liveSession(), 0, 1, SwitchReason.SCHEDULED, 0L))
        val s = h.send(Event.TvAck(500L, ok = false, detail = "mpv exited"))

        assertTrue(s is PlaybackState.Error)
        s as PlaybackState.Error
        assertEquals(ErrorKind.PLAY_FAILED, s.kind)
        assertEquals(500L + 60_000L, s.canRetryAtMs)
        assertTrue(h.effects.any { it is Effect.UserMessage && it.code == "PLAY_FAILED" })
        assertTrue(h.commands.contains("stop"))
    }

    // 11
    @Test
    fun userStop_goesIdle_andWatchdogCannotRevive() {
        val h = Harness(PlaybackState.Playing(liveSession(), 0, 0L, 1_800_000L))
        val s = h.send(Event.UserStop(9_000L))

        assertTrue(s is PlaybackState.Idle)
        s as PlaybackState.Idle
        assertEquals(IdleReason.USER_STOP, s.reason)
        assertTrue(h.commands.contains("stop"))

        // 看门狗/空闲超时在 Idle 上不得产生任何副作用，更不能重新起播
        val h2 = Harness(s)
        val after = h2.send(Event.IdleTimeout(9_000L + 10_000_000L))
        assertEquals(s, after)
        assertTrue(h2.effects.isEmpty())
        assertTrue(h2.commands.isEmpty())
    }

    // 12
    @Test
    fun userRetryDuringCooldown_isBlockedWithMessage() {
        val err = PlaybackState.Error(
            ErrorKind.TV_UNREACHABLE, liveSession(), atMs = 0L, recoveryCount = 0,
            canRetryAtMs = 60_000L
        )
        val h = Harness(err)
        val s = h.send(Event.UserRetry(10_000L))

        assertEquals(err, s)
        val msg = h.effects.filterIsInstance<Effect.UserMessage>().first()
        assertEquals("COOLDOWN", msg.code)
        assertTrue(h.commands.isEmpty())
    }

    // 13
    @Test
    fun autoRetry_incrementsRecoveryCount() {
        val err = PlaybackState.Error(
            ErrorKind.TV_UNREACHABLE, liveSession(), atMs = 0L, recoveryCount = 0,
            canRetryAtMs = 60_000L
        )
        val h = Harness(err)
        val s = h.send(Event.RetryDue(60_000L))

        assertTrue(s is PlaybackState.Starting)
        s as PlaybackState.Starting
        assertEquals(1, s.recoveryCount)
        assertEquals(listOf("wake", "launch:5017134"), h.commands)
    }

    // 14
    @Test
    fun autoRetryExhausted_isTerminal() {
        val err = PlaybackState.Error(
            ErrorKind.TV_UNREACHABLE, liveSession(), atMs = 0L,
            recoveryCount = MAX_AUTO_RETRIES, canRetryAtMs = 0L
        )
        val h = Harness(err)
        val s = h.send(Event.RetryDue(5_000L))

        assertEquals(err, s)
        assertTrue(h.commands.isEmpty(), "恢复次数用尽后不得再自动拉起")
    }

    // 15
    @Test
    fun noContentError_isNotAutoRecoverable() {
        val err = PlaybackState.Error(
            ErrorKind.NO_CONTENT, null, atMs = 0L, recoveryCount = 0, canRetryAtMs = 0L
        )
        val h = Harness(err)
        val s = h.send(Event.RetryDue(5_000L))

        assertEquals(err, s)
        assertTrue(h.commands.isEmpty())
    }

    // 16
    @Test
    fun switchSecondsOutOfRange_isClampedTo60() {
        val h = Harness()
        h.send(Event.UserStart(0L, liveSession(rooms = listOf("5017134"), switchSeconds = 30L)))
        val s = h.send(Event.TvAck(1_000L, ok = true)) as PlaybackState.Playing

        assertEquals(1_000L + 60_000L, s.nextSwitchAtMs, "30 秒应被收敛到 60 秒下限")
    }

    // 17
    @Test
    fun lastIndex_wrapsToFirst() {
        val h = Harness(PlaybackState.Playing(liveSession(), 2, 0L, 1_800_000L))
        val s = h.send(Event.Tick(1_800_000L)) as PlaybackState.Switching

        assertEquals(2, s.fromIndex)
        assertEquals(0, s.toIndex)
        assertEquals(listOf("launch:5017134"), h.commands)
    }

    // 18b —— 起播重试定时器必须有出口
    @Test
    fun startingStateHandlesRetryDue() {
        val retrying = PlaybackState.Starting(liveSession(), 0, 2, 0L)
        val h = Harness(retrying)
        val s = h.send(Event.RetryDue(2_000L))

        assertTrue(s is PlaybackState.Starting)
        assertEquals(listOf("wake", "launch:5017134"), h.commands)

        // attempt 语义是「刚做完第几次」：attempt=3 时仍应再发一次，只有 attempt>MAX 才放弃
        val exhausted = PlaybackState.Starting(liveSession(), 0, 4, 0L)
        val h2 = Harness(exhausted)
        val e = h2.send(Event.RetryDue(4_000L))
        assertTrue(e is PlaybackState.Error)
        assertEquals(ErrorKind.TV_UNREACHABLE, (e as PlaybackState.Error).kind)
        assertTrue(h2.commands.contains("stop"))
    }

    @Test
    fun unknownMediaStateAndSingleMissDoNotSwitch() {
        val h = Harness(PlaybackState.Playing(liveSession(), 0, 0, 1_800_000))
        h.send(Event.TvReport(100, alive = false, playing = false))
        assertTrue(h.effects.none { it is Effect.Launch })
        repeat(20) { h.send(Event.TvReport(200L + it, alive = true, playing = false)) }
        assertTrue(h.state is PlaybackState.Playing)
        assertEquals(0, (h.state as PlaybackState.Playing).missCount)
        assertTrue(h.commands.isEmpty())
    }

    @Test
    fun videoLostNeverSilentlyRestartsOrAutoRetries() {
        val video = Session(videos = listOf("v1", "v2"), mode = Mode.VIDEO)
        val h = Harness(PlaybackState.Playing(video, 1, 0, 1_800_000))
        repeat(3) { h.send(Event.TvReport(it * 5_000L, alive = false, playing = false)) }
        assertTrue(h.state is PlaybackState.Error)
        assertTrue(h.effects.any { it is Effect.UserMessage && it.text.contains("进度") })
        h.send(Event.RetryDue(1_000_000))
        assertTrue(h.state is PlaybackState.Error)
        assertTrue(h.commands.isEmpty())
    }

    @Test
    fun liveRecoveryHasCooldownAndCumulativeLimit() {
        val h = Harness(PlaybackState.Playing(liveSession(), 0, 0, 1_800_000))
        repeat(MAX_AUTO_RETRIES) { attempt ->
            val now = attempt * 100_000L
            repeat(3) { h.send(Event.TvReport(now + it, alive = false, playing = false)) }
            val switching = h.state as PlaybackState.Switching
            assertEquals(attempt + 1, switching.recoveryCount)
            h.send(Event.TvAck(now + 3, true))
            repeat(3) { h.send(Event.TvReport(now + 4 + it, alive = false, playing = false)) }
            if (attempt < MAX_AUTO_RETRIES - 1) assertTrue(h.state is PlaybackState.Playing)
        }
        assertTrue(h.state is PlaybackState.Error)
        val count = h.commands.size
        h.send(Event.RetryDue(10_000_000))
        assertEquals(count, h.commands.size)
    }

    // 18 —— 验证替身本身可靠
    @Test
    fun fakeTv_recordsCommands_andHonoursScriptedFailures() {
        val tv = FakeTv()
        tv.enqueueLaunch(false, true)
        assertTrue(tv.wake().ok)
        assertFalse(tv.launch("a").ok)
        assertTrue(tv.launch("b").ok)
        tv.status = TvStatus(alive = false, playing = false)
        assertFalse(tv.probe().alive)
        assertEquals(listOf("wake", "launch:a", "launch:b", "probe"), tv.commands)
    }
}
