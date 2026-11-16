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
            dispatch(transition.effects)
        }
    }

    private fun dispatch(effects: List<Effect>) {
        for (effect in effects) {
            when (effect) {
                Effect.WakeTv -> {
                    val ack = tv.wake()
                    // 唤醒失败直接当作启动失败；成功则等待 Launch 的回执，避免重复 ack
                    if (!ack.ok) send(Event.TvAck(clock.nowMs(), false, ack.detail))
                }
                is Effect.Launch -> {
                    val ack = tv.launch(effect.target)
                    send(Event.TvAck(clock.nowMs(), ack.ok, ack.detail))
                }
                Effect.StopTv -> tv.stop()
                is Effect.ProbeTv -> scheduler.postDelayed(effect.afterMs) {
                    val status = tv.probe()
                    send(Event.TvReport(clock.nowMs(), status.alive, status.playing))
                }
                is Effect.ScheduleSwitch -> scheduler.postDelayed(effect.afterMs) {
                    send(Event.Tick(clock.nowMs()))
                }
                is Effect.ScheduleRetry -> scheduler.postDelayed(effect.afterMs) {
                    send(Event.RetryDue(clock.nowMs()))
                }
                Effect.CancelTimers -> {
                    scheduler.cancelAll()
                    cancelIdleTimer()
                }
                is Effect.Notify -> {
                    onState(effect.state)
                    updateIdleTimer(effect.state)
                }
                is Effect.UserMessage -> onMessage(effect.code, effect.text)
                is Effect.Log -> logger("[${effect.level}] ${effect.message}")
            }
        }
    }

    private fun updateIdleTimer(newState: PlaybackState) {
        cancelIdleTimer()
        if (idleTimeoutMs > 0 && newState is PlaybackState.Idle) {
            idleTimer = scheduler.postDelayed(idleTimeoutMs) { onIdleShutdown() }
        }
    }

    private fun cancelIdleTimer() {
        idleTimer?.cancel()
        idleTimer = null
    }
}
