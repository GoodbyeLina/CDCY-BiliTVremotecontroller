package tvrc.core

import tvrc.core.PlaybackState.Error
import tvrc.core.PlaybackState.Idle
import tvrc.core.PlaybackState.Playing
import tvrc.core.PlaybackState.Starting
import tvrc.core.PlaybackState.Switching

/**
 * 纯函数状态机：state + event -> (state, effects)。
 *
 * 三条硬规则（来自 H 方案 P0 修正与 K §4 事实清单）：
 * 1. [Error] 是终态：看门狗、自愈、空闲超时都不许把它拉起来，只能等冷却或用户重试。
 * 2. 用户主动停止后进入 [Idle]，自愈不生效，避免「关了又自己开」。
 * 3. 播放中不响应空闲超时（FR-10：无播放任务才停）。
 */
object PlaybackReducer {

    private fun stay(state: PlaybackState): Transition = Transition(state)

    private fun switchDelayMs(session: Session): Long = session.effectiveSwitchSeconds * 1000L

    private fun backoffMs(attempt: Int): Long = 2_000L * attempt.coerceAtLeast(1)

    private fun launchOf(session: Session, index: Int) = Effect.Launch(index, session.target(index))

    private fun restart(nowMs: Long, session: Session): Transition {
        val starting = Starting(session, 0, 1, nowMs, 0)
        return Transition(
            starting,
            listOf(
                Effect.CancelTimers,
                Effect.WakeTv,
                Effect.Notify(starting),
                launchOf(session, 0),
                Effect.Log("info", "开始播放：mode=${session.mode} 共 ${session.items.size} 项，间隔 ${session.effectiveSwitchSeconds}s")
            )
        )
    }

    private fun stopTo(nowMs: Long): Transition {
        val idle = Idle(nowMs, IdleReason.USER_STOP)
        return Transition(
            idle,
            listOf(Effect.CancelTimers, Effect.StopTv, Effect.Notify(idle), Effect.Log("info", "用户停止播放"))
        )
    }

    fun reduce(state: PlaybackState, event: Event): Transition = when (state) {
        is Idle -> onIdle(state, event)
        is Starting -> onStarting(state, event)
        is Playing -> onPlaying(state, event)
        is Switching -> onSwitching(state, event)
        is Error -> onError(state, event)
    }

    private fun onIdle(state: Idle, event: Event): Transition = when (event) {
        is Event.UserStart -> {
            if (!event.session.isValid()) {
                val err = Error(
                    ErrorKind.NO_CONTENT, null, event.nowMs, MAX_AUTO_RETRIES, Long.MAX_VALUE, "session 为空"
                )
                Transition(err, listOf(Effect.UserMessage("NO_CONTENT", ErrorKind.NO_CONTENT.userText), Effect.Notify(err)))
            } else {
                restart(event.nowMs, event.session)
            }
        }
        is Event.UserStop -> Transition(Idle(event.nowMs, IdleReason.USER_STOP), listOf(Effect.CancelTimers))
        else -> stay(state)
    }

    private fun onStarting(state: Starting, event: Event): Transition = when (event) {
        is Event.TvAck -> {
            if (event.ok) {
                val delay = switchDelayMs(state.session)
                val playing = Playing(
                    state.session, state.index, event.nowMs, event.nowMs + delay, 0, state.recoveryCount
                )
                Transition(
                    playing,
                    listOf(
                        Effect.ScheduleSwitch(delay),
                        Effect.ProbeTv(PROBE_INTERVAL_MS),
                        Effect.Notify(playing),
                        Effect.Log("info", "起播成功：第 ${state.index} 项")
                    )
                )
            } else if (state.attempt < MAX_START_ATTEMPTS) {
                val next = state.copy(attempt = state.attempt + 1, sinceMs = event.nowMs)
                val wait = backoffMs(state.attempt)
                Transition(
                    next,
                    listOf(
                        Effect.ScheduleRetry(wait),
                        Effect.Log("warn", "起播失败(第 ${state.attempt} 次)：${event.detail}，${wait}ms 后重试")
                    )
                )
            } else {
                val err = Error(
                    ErrorKind.TV_UNREACHABLE, state.session, event.nowMs, state.recoveryCount,
                    event.nowMs + RETRY_COOLDOWN_MS, event.detail
                )
                Transition(
                    err,
                    listOf(
                        Effect.StopTv,
                        Effect.UserMessage("TV_UNREACHABLE", ErrorKind.TV_UNREACHABLE.userText),
                        Effect.Notify(err),
                        Effect.Log("error", "起播失败 ${MAX_START_ATTEMPTS} 次，放弃：${event.detail}")
                    )
                )
            }
        }
        is Event.RetryDue -> {
            // attempt 是「刚刚做过第几次」：attempt=MAX 时仍要再发一次（第 MAX 次）起播，
            // 真正放弃由 TvAck 失败分支判定，故这里用 > 而不是 >=。
            if (state.attempt > MAX_START_ATTEMPTS) {
                val err = Error(
                    ErrorKind.TV_UNREACHABLE, state.session, event.nowMs, state.recoveryCount,
                    event.nowMs + RETRY_COOLDOWN_MS, "start retries exhausted"
                )
                Transition(
                    err,
                    listOf(
                        Effect.StopTv,
                        Effect.UserMessage("TV_UNREACHABLE", ErrorKind.TV_UNREACHABLE.userText),
                        Effect.Notify(err),
                        Effect.Log("error", "起播重试用尽")
                    )
                )
            } else {
                Transition(
                    state,
                    listOf(
                        Effect.WakeTv,
                        Effect.Notify(state),
                        launchOf(state.session, state.index),
                        Effect.Log("info", "重试起播第 ${state.attempt} 次")
                    )
                )
            }
        }
        is Event.UserStop -> stopTo(event.nowMs)
        is Event.UserStart -> restart(event.nowMs, event.session)
        else -> stay(state)
    }

    private fun onPlaying(state: Playing, event: Event): Transition = when (event) {
        is Event.Tick -> {
            if (event.nowMs >= state.nextSwitchAtMs) {
                val to = state.session.nextIndex(state.index)
                val switching = Switching(
                    state.session, state.index, to, SwitchReason.SCHEDULED, event.nowMs, state.recoveryCount
                )
                Transition(
                    switching,
                    listOf(launchOf(state.session, to), Effect.Log("info", "到点换台 ${state.index} -> $to"))
                )
            } else {
                stay(state)
            }
        }

        is Event.TvReport -> {
            if (event.alive) {
                Transition(state.copy(missCount = 0), listOf(Effect.ProbeTv(PROBE_INTERVAL_MS)))
            } else {
                val miss = state.missCount + 1
                if (miss >= MAX_MISSES) {
                    val to = state.session.nextIndex(state.index)
                    val switching = Switching(
                        state.session, state.index, to, SwitchReason.TV_LOST, event.nowMs, state.recoveryCount
                    )
                    Transition(
                        switching,
                        listOf(
                            launchOf(state.session, to),
                            Effect.Log("warn", "电视连续 ${MAX_MISSES} 次探测失败，换到第 $to 项")
                        )
                    )
                } else {
                    Transition(state.copy(missCount = miss), listOf(Effect.ProbeTv(PROBE_INTERVAL_MS)))
                }
            }
        }

        is Event.UserStop -> stopTo(event.nowMs)
        is Event.UserStart -> restart(event.nowMs, event.session)
        is Event.IdleTimeout -> stay(state)
        else -> stay(state)
    }

    private fun onSwitching(state: Switching, event: Event): Transition = when (event) {
        is Event.TvAck -> {
            if (event.ok) {
                val delay = switchDelayMs(state.session)
                val playing = Playing(
                    state.session, state.toIndex, event.nowMs, event.nowMs + delay, 0, state.recoveryCount
                )
                Transition(
                    playing,
                    listOf(
                        Effect.ScheduleSwitch(delay),
                        Effect.ProbeTv(PROBE_INTERVAL_MS),
                        Effect.Notify(playing),
                        Effect.Log("info", "换台成功：第 ${state.toIndex} 项")
                    )
                )
            } else {
                val err = Error(
                    ErrorKind.PLAY_FAILED, state.session, event.nowMs, state.recoveryCount,
                    event.nowMs + RETRY_COOLDOWN_MS, event.detail
                )
                Transition(
                    err,
                    listOf(
                        Effect.StopTv,
                        Effect.UserMessage("PLAY_FAILED", ErrorKind.PLAY_FAILED.userText),
                        Effect.Notify(err),
                        Effect.Log("error", "换台失败：${event.detail}")
                    )
                )
            }
        }
        is Event.UserStop -> stopTo(event.nowMs)
        is Event.UserStart -> restart(event.nowMs, event.session)
        else -> stay(state)
    }

    private fun onError(state: Error, event: Event): Transition = when (event) {
        is Event.UserRetry -> {
            val session = state.session
            when {
                session == null -> stay(state)
                event.nowMs < state.canRetryAtMs -> {
                    val waitSec = (state.canRetryAtMs - event.nowMs + 999L) / 1000L
                    Transition(
                        state,
                        listOf(Effect.UserMessage("COOLDOWN", "冷却中，还需 $waitSec 秒"))
                    )
                }
                else -> restart(event.nowMs, session)
            }
        }

        is Event.RetryDue -> {
            val session = state.session
            val canAutoRecover = state.kind.autoRecoverable &&
                state.recoveryCount < MAX_AUTO_RETRIES &&
                event.nowMs >= state.canRetryAtMs
            if (canAutoRecover && session != null) {
                val boosted = state.copy(recoveryCount = state.recoveryCount + 1)
                val starting = Starting(session, 0, 1, event.nowMs, boosted.recoveryCount)
                Transition(
                    starting,
                    listOf(
                        Effect.WakeTv,
                        Effect.Notify(starting),
                        launchOf(session, 0),
                        Effect.Log("warn", "自动恢复第 ${boosted.recoveryCount}/${MAX_AUTO_RETRIES} 次")
                    )
                )
            } else {
                Transition(
                    state,
                    if (state.kind.autoRecoverable && event.nowMs >= state.canRetryAtMs)
                        listOf(Effect.Log("error", "自动恢复次数已用尽，等待用户重试"))
                    else emptyList()
                )
            }
        }

        is Event.UserStop -> Transition(Idle(event.nowMs, IdleReason.USER_STOP), listOf(Effect.CancelTimers))
        is Event.UserStart -> restart(event.nowMs, event.session)
        else -> stay(state)
    }
}
