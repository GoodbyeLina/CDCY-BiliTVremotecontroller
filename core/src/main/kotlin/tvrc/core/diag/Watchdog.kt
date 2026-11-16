package tvrc.core.diag

/**
 * 看门狗：只在「本该在播但长时间没有进展」时动手。
 *
 * 关键约束（对齐 H 方案 5.6 与 K 文档裁决）：
 * - Idle 不拉（用户主动停的不许自愈，否则违反「不抢控制权」）
 * - Error 是终态，不自动重试（由用户点重试）
 * - 连续重启上限 3 次，两次之间至少冷却 60 秒；超限就停下并报错，不做无限重启
 */
data class WatchdogInput(
    val nowMs: Long,
    val lastProgressMs: Long,
    val playing: Boolean,
    val restartCount: Int,
    val lastRestartMs: Long,
    val stallThresholdMs: Long = DEFAULT_STALL_MS,
    val maxRestarts: Int = DEFAULT_MAX_RESTARTS,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    companion object {
        const val DEFAULT_STALL_MS = 30_000L
        const val DEFAULT_MAX_RESTARTS = 3
        const val DEFAULT_COOLDOWN_MS = 60_000L
    }
}

sealed interface WatchdogDecision {
    data object Nothing : WatchdogDecision
    data class Restart(val stalledForMs: Long, val attempt: Int) : WatchdogDecision
    data class GiveUp(val stalledForMs: Long, val reason: String) : WatchdogDecision
}

object Watchdog {

    fun decide(input: WatchdogInput): WatchdogDecision {
        if (!input.playing) return WatchdogDecision.Nothing
        val stalled = input.nowMs - input.lastProgressMs
        if (stalled < input.stallThresholdMs) return WatchdogDecision.Nothing
        if (input.restartCount >= input.maxRestarts) {
            return WatchdogDecision.GiveUp(stalled, "已连续重启 " + input.restartCount + " 次仍无进展，停止自动恢复")
        }
        val sinceRestart = input.nowMs - input.lastRestartMs
        if (input.restartCount > 0 && sinceRestart < input.cooldownMs) {
            return WatchdogDecision.Nothing
        }
        return WatchdogDecision.Restart(stalled, input.restartCount + 1)
    }
}
