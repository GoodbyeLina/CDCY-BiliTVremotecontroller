package tvrc.core.engine

import tvrc.core.Effect
import tvrc.core.Event
import tvrc.core.IdleReason
import tvrc.core.PROBE_INTERVAL_MS
import tvrc.core.PlaybackReducer
import tvrc.core.PlaybackState
import tvrc.core.Session
import tvrc.core.TvController

/**
 * 把纯函数状态机接到真实世界：Effect → 设备/定时器，设备回执/定时到点 → Event。
 *
 * 所有状态改动都在同一把锁内完成（synchronized 可重入），因此定时器线程与调用方线程
 * 不会把状态机改乱。状态机本身仍然纯净、可单测。
 */
class PlaybackEngine(
    private val tv: TvController,
    private val scheduler: Scheduler,
    private val clock: Clock = SystemClock,
    private val probeIntervalMs: Long = PROBE_INTERVAL_MS,
    private val idleTimeoutMs: Long = 0L,
    private val logger: (String) -> Unit = {},
    private val onState: (PlaybackState) -> Unit = {},
    private val onMessage: (String, String) -> Unit = { _, _ -> },
    private val onIdleShutdown: () -> Unit = {}
) {

    private val lock = Any()
    private var state: PlaybackState = PlaybackState.Idle(clock.nowMs(), IdleReason.NEVER_STARTED)
    private var idleTimer: Cancellable? = null
    private var revision = 0L
    private var timerGeneration = 0L

    val currentState: PlaybackState
        get() = synchronized(lock) { state }

    fun start(session: Session) = send(Event.UserStart(clock.nowMs(), session))

    fun stop() = send(Event.UserStop(clock.nowMs()))

    fun retry() = send(Event.UserRetry(clock.nowMs()))

    fun tick() = send(Event.Tick(clock.nowMs()))

    fun send(event: Event) {
        synchronized(lock) {
            val transition = PlaybackReducer.reduce(state, event)
            state = transition.state
            revision++
            dispatch(transition.effects, revision)
        }
    }

    private fun dispatch(effects: List<Effect>, expectedRevision: Long) {
        for (effect in effects) {
            // 回调可能重入 stop/start；旧迁移剩余副作用不得覆盖用户的新决定。
            if (revision != expectedRevision) return
            when (effect) {
                Effect.WakeTv -> {
                    val ack = tv.wake()
                    // 唤醒失败直接当作启动失败；成功则等待 Launch 的回执，避免重复 ack
                    if (revision == expectedRevision && !ack.ok) send(Event.TvAck(clock.nowMs(), false, ack.detail))
                }
                is Effect.Launch -> {
                    val ack = tv.launch(effect.target)
                    if (revision == expectedRevision) send(Event.TvAck(clock.nowMs(), ack.ok, ack.detail))
                }
                Effect.StopTv -> tv.stop()
                is Effect.ProbeTv -> postGuarded(effect.afterMs) {
                    val beforeProbe = revision
                    try {
                        val status = tv.probe()
                        if (revision == beforeProbe) send(Event.TvReport(clock.nowMs(), status.alive, status.playing))
                    } catch (e: Exception) {
                        // 探针异常只有“未知”含义，不能据此换台或丢失后续探测链。
                        if (revision == beforeProbe) {
                            logger("[warn] 电视状态暂时未知：${e.message}")
                            dispatch(listOf(Effect.ProbeTv(probeIntervalMs)), beforeProbe)
                        }
                    }
                }
                is Effect.ScheduleSwitch -> postGuarded(effect.afterMs) {
                    send(Event.Tick(clock.nowMs()))
                }
                is Effect.ScheduleRetry -> postGuarded(effect.afterMs) {
                    send(Event.RetryDue(clock.nowMs()))
                }
                Effect.CancelTimers -> {
                    timerGeneration++
                    scheduler.cancelAll()
                    cancelIdleTimer()
                }
                is Effect.Notify -> {
                    onState(effect.state)
                    if (revision == expectedRevision) updateIdleTimer(effect.state)
                }
                is Effect.UserMessage -> onMessage(effect.code, effect.text)
                is Effect.Log -> logger("[${effect.level}] ${effect.message}")
            }
        }
    }

    private fun postGuarded(delayMs: Long, task: () -> Unit): Cancellable {
        val generation = timerGeneration
        return scheduler.postDelayed(delayMs) {
            synchronized(lock) {
                if (generation == timerGeneration) task()
            }
        }
    }

    private fun updateIdleTimer(newState: PlaybackState) {
        cancelIdleTimer()
        if (idleTimeoutMs > 0 && newState is PlaybackState.Idle) {
            idleTimer = postGuarded(idleTimeoutMs) {
                if (state is PlaybackState.Idle) onIdleShutdown()
            }
        }
    }

    private fun cancelIdleTimer() {
        idleTimer?.cancel()
        idleTimer = null
    }
}
