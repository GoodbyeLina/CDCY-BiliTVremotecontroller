package tvrc.core

/**
 * 播放模式。
 * - LIVE  : 多个直播间轮播（二代 source=live）
 * - SINGLE: 单个直播间反复播放
 * - VIDEO : 视频列表轮播
 */
enum class Mode { LIVE, SINGLE, VIDEO }

/** 换台间隔合法区间（秒），与 H 方案 FR-05 一致：默认 1800，范围 60..86400。 */
const val MIN_SWITCH_SECONDS: Long = 60L
const val MAX_SWITCH_SECONDS: Long = 86_400L

/** 启动阶段最多尝试次数（含首次）。 */
const val MAX_START_ATTEMPTS: Int = 3

/** 异常自动恢复最多次数，超过后进入终态等待用户手动重试。 */
const val MAX_AUTO_RETRIES: Int = 3

/** 自动恢复冷却时间，久于它才允许下一次自动/手动恢复。 */
const val RETRY_COOLDOWN_MS: Long = 60_000L

/** 连续探测失败达到此值时判定电视侧真的掉了，触发换台/自愈。 */
const val MAX_MISSES: Int = 3

/** 播放中探测电视状态的间隔。 */
const val PROBE_INTERVAL_MS: Long = 5_000L

/**
 * 一次播放会话的输入。所有内容由内容源解析得到，状态机不关心 BV 号/合集如何展开。
 */
data class Session(
    val rooms: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    val switchSeconds: Long = 1800L,
    val mode: Mode = Mode.LIVE
) {
    val items: List<String>
        get() = if (mode == Mode.VIDEO) videos else rooms

    /** 越界值按 60..86400 收敛，而不是抛异常——用户填错不该让 App 崩。 */
    val effectiveSwitchSeconds: Long
        get() = switchSeconds.coerceIn(MIN_SWITCH_SECONDS, MAX_SWITCH_SECONDS)

    fun isValid(): Boolean = items.isNotEmpty()

    fun target(index: Int): String = if (items.isEmpty()) "" else items[((index % items.size) + items.size) % items.size]

    fun nextIndex(current: Int): Int = if (items.isEmpty()) 0 else (current + 1) % items.size
}

/** 进入空闲的原因。 */
enum class IdleReason { NEVER_STARTED, USER_STOP, IDLE_TIMEOUT }

/** 从播放中进入切换的原因。 */
enum class SwitchReason { SCHEDULED, TV_LOST, MANUAL }

/** 错误分类，决定能否自动恢复、以及给用户看什么话。 */
enum class ErrorKind(val autoRecoverable: Boolean, val userText: String) {
    TV_UNREACHABLE(true, "连不上电视，请确认电视已开机且和手机在同一 WiFi"),
    PLAY_FAILED(true, "电视端播放失败，可能链接失效或播放器被关掉"),
    NO_CONTENT(false, "没有可播放的内容，请先在设置里添加直播间或视频"),
    INTERNAL(false, "App 内部错误，请重试或查看日志")
}

/**
 * 五种状态。全部不可变，状态迁移只发生在 [PlaybackReducer.reduce]。
 */
sealed interface PlaybackState {

    /** 空闲：没有播放任务。播放中不会因为空闲超时被拉进这里（FR-10）。 */
    data class Idle(val sinceMs: Long, val reason: IdleReason = IdleReason.NEVER_STARTED) : PlaybackState

    /** 启动中：已下发唤醒 + 起播命令，等电视回执。 */
    data class Starting(
        val session: Session,
        val index: Int,
        val attempt: Int,
        val sinceMs: Long,
        val recoveryCount: Int = 0
    ) : PlaybackState

    /** 播放中：电视确认在播。nextSwitchAtMs 到点即换台。 */
    data class Playing(
        val session: Session,
        val index: Int,
        val startedAtMs: Long,
        val nextSwitchAtMs: Long,
        val missCount: Int = 0,
        val recoveryCount: Int = 0
    ) : PlaybackState

    /** 切换中：已下发换台命令，等回执。 */
    data class Switching(
        val session: Session,
        val fromIndex: Int,
        val toIndex: Int,
        val reason: SwitchReason,
        val sinceMs: Long,
        val recoveryCount: Int = 0
    ) : PlaybackState

    /** 错误：终态，不会被看门狗自动拉起；只能等冷却后自动/手动恢复。 */
    data class Error(
        val kind: ErrorKind,
        val session: Session?,
        val atMs: Long,
        val recoveryCount: Int,
        val canRetryAtMs: Long,
        val detail: String = ""
    ) : PlaybackState
}

/** 外部世界喂给状态机的事件。 */
sealed interface Event {
    data class UserStart(val nowMs: Long, val session: Session) : Event
    data class UserStop(val nowMs: Long) : Event
    data class UserRetry(val nowMs: Long) : Event
    data class Tick(val nowMs: Long) : Event
    data class TvAck(val nowMs: Long, val ok: Boolean, val detail: String = "") : Event
    data class TvReport(val nowMs: Long, val alive: Boolean, val playing: Boolean) : Event
    data class RetryDue(val nowMs: Long) : Event
    data class IdleTimeout(val nowMs: Long) : Event
}

/** 状态机要求外部世界执行的副作用。状态机自己不碰 adb、不碰网络、不碰线程。 */
sealed interface Effect {
    data object WakeTv : Effect
    data class Launch(val index: Int, val target: String) : Effect
    data object StopTv : Effect
    data class ProbeTv(val afterMs: Long) : Effect
    data class ScheduleSwitch(val afterMs: Long) : Effect
    data class ScheduleRetry(val afterMs: Long) : Effect
    data object CancelTimers : Effect
    data class Notify(val state: PlaybackState) : Effect
    data class UserMessage(val code: String, val text: String) : Effect
    data class Log(val level: String, val message: String) : Effect
}

/** 一次 reduce 的结果：新状态 + 要做的副作用。 */
data class Transition(val state: PlaybackState, val effects: List<Effect> = emptyList())
