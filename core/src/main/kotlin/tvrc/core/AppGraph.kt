package tvrc.core

import tvrc.core.bili.BiliLiveApi
import tvrc.core.bili.LiveSessions
import tvrc.core.bili.VideoUrlResolver
import tvrc.core.config.AppConfig
import tvrc.core.diag.LogLevel
import tvrc.core.diag.RingLog
import tvrc.core.diag.Watchdog
import tvrc.core.diag.WatchdogDecision
import tvrc.core.diag.WatchdogInput
import tvrc.core.engine.Cancellable
import tvrc.core.engine.Clock
import tvrc.core.engine.PlaybackEngine
import tvrc.core.engine.RealScheduler
import tvrc.core.engine.Scheduler
import tvrc.core.engine.SystemClock
import tvrc.core.http.HttpUrlConnectionFetcher
import tvrc.core.http.MiniHttpServer
import tvrc.core.http.UpstreamFetcher
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * 装配根（L 清单之后的「收口」层）：把配置、代理、adb、状态机、看门狗、环形日志装成一条完整链路。
 *
 * 设计约束：
 * 1) 构造注入，全部依赖都是接口，测试可零网络、零设备跑通；
 * 2) 只做装配与生命周期，不含业务判断（判断都在 reducer / Watchdog 纯函数里）；
 * 3) 不引用任何 Android 类型，:app 层只负责 new 出真实依赖再调 [start]/[close]。
 */
class AppGraph(
    val config: AppConfig,
    private val tv: TvController,
    private val liveApi: BiliLiveApi,
    private val videoResolver: VideoUrlResolver,
    private val fetcher: UpstreamFetcher,
    private val scheduler: Scheduler = RealScheduler(),
    private val clock: Clock = SystemClock,
    val ringLog: RingLog = RingLog(),
    private val sessionProvider: (AppConfig) -> Session = { liveOnlySession(it) },
    private val bindAddress: String = "0.0.0.0",
    private val allowDirectUrl: Boolean = false,
    private val watchdogIntervalMs: Long = 10_000L,
    /** 状态外流口（:app 的前台服务靠它刷通知与 UI；默认空实现，老测试零影响）。 */
    private val onState: (PlaybackState) -> Unit = {}
) : Closeable {

    private val liveCache = ConcurrentHashMap<Long, String>()
    private val videoCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var allowedRooms: Set<Long> = config.rooms.toSet()

    @Volatile
    private var allowedVideos: Set<String> = emptySet()

    @Volatile
    private var currentSession: Session = sessionProvider(config)

    @Volatile
    private var lastState: PlaybackState = PlaybackState.Idle(0L, IdleReason.NEVER_STARTED)

    @Volatile
    private var lastProgressMs: Long = clock.nowMs()

    @Volatile
    private var restartCount: Int = 0

    @Volatile
    private var lastRestartMs: Long = 0L

    @Volatile
    private var started: Boolean = false

    private var watchdogTimer: Cancellable? = null

    val server: MiniHttpServer = MiniHttpServer(
        port = config.port,
        bindAddress = bindAddress,
        fetcher = fetcher,
        liveRooms = { allowedRooms },
        resolveLive = { room -> resolveLiveUrl(room) },
        videoIds = { allowedVideos },
        resolveVideo = { bvid -> resolveVideoUrl(bvid) },
        allowDirectUrl = allowDirectUrl
    )

    val engine: PlaybackEngine = PlaybackEngine(
        tv = tv,
        scheduler = scheduler,
        clock = clock,
        idleTimeoutMs = config.idleTimeoutMinutes.toLong() * 60_000L,
        logger = { msg -> ringLog.add(LogLevel.DEBUG, "engine", msg, clock.nowMs()) },
        onState = { state -> onEngineState(state) },
        onMessage = { code, text -> ringLog.add(LogLevel.WARN, code, text, clock.nowMs()) },
        onIdleShutdown = {
            ringLog.add(LogLevel.INFO, "idle", "空闲超时，停止播放并关闭代理", clock.nowMs())
            shutdownForIdle()
        }
    )

    /** 起代理 + 起轮播 + 起看门狗巡检。返回代理实际绑定的端口（测试可传 0）。 */
    fun start(): Int {
        val boundPort = server.start()
        started = true
        // 端口 0 = 由系统分配；Session 里的电视 URL 必须写真实端口，否则电视拿到 :0
        currentSession = sessionProvider(withBoundPort(boundPort))
        ringLog.add(LogLevel.INFO, "app", "代理已启动 :" + boundPort + "，来源=" + config.source, clock.nowMs())
        scheduleWatchdog()
        engine.start(currentSession)
        return boundPort
    }

    fun stop() {
        engine.stop()
        ringLog.add(LogLevel.INFO, "app", "用户停止", clock.nowMs())
    }

    fun retry() = engine.retry()

    /** 是否在运行（代理已起、未关闭）。 */
    val isRunning: Boolean
        get() = started

    /** 当前播放状态快照（:app 首屏用它渲染状态卡）。 */
    val state: PlaybackState
        get() = lastState

    /** 当前看门狗已自动重启次数（运维/测试观察用）。 */
    val watchdogRestartCount: Int
        get() = restartCount

    fun watchdogInput(): WatchdogInput = WatchdogInput(
        nowMs = clock.nowMs(),
        lastProgressMs = lastProgressMs,
        playing = lastState is PlaybackState.Playing,
        restartCount = restartCount,
        lastRestartMs = lastRestartMs
    )

    /** 供测试直接驱动一次看门狗判定，生产由 [scheduleWatchdog] 周期调用。 */
    fun checkWatchdog() {
        when (val decision = Watchdog.decide(watchdogInput())) {
            is WatchdogDecision.Nothing -> Unit
            is WatchdogDecision.Restart -> {
                restartCount = decision.attempt
                lastRestartMs = clock.nowMs()
                lastProgressMs = clock.nowMs()
                ringLog.add(
                    LogLevel.WARN, "watchdog",
                    "停滞 " + decision.stalledForMs + " ms，第 " + decision.attempt + " 次自动重启",
                    clock.nowMs()
                )
                engine.retry()
            }
            is WatchdogDecision.GiveUp -> {
                ringLog.add(LogLevel.ERROR, "watchdog", decision.reason, clock.nowMs())
                shutdownForIdle()
            }
        }
    }

    override fun close() {
        watchdogTimer?.cancel()
        watchdogTimer = null
        runCatching { engine.stop() }
        runCatching { server.close() }
        (scheduler as? Closeable)?.let { runCatching { it.close() } }
        started = false
        ringLog.add(LogLevel.INFO, "app", "已关闭", clock.nowMs())
    }

    private fun onEngineState(state: PlaybackState) {
        lastState = state
        runCatching { onState(state) }
        if (state is PlaybackState.Playing) {
            lastProgressMs = clock.nowMs()
            restartCount = 0
        }
        ringLog.add(LogLevel.INFO, "state", describe(state), clock.nowMs())
    }

    private fun shutdownForIdle() {
        runCatching { engine.stop() }
        runCatching { server.close() }
        started = false
    }

    private fun scheduleWatchdog() {
        if (watchdogIntervalMs <= 0L) return
        watchdogTimer = scheduler.postDelayed(watchdogIntervalMs) {
            checkWatchdog()
            if (started) scheduleWatchdog()
        }
    }

    private fun resolveLiveUrl(roomId: Long): String? {
        return try {
            val url = liveApi.flvStream(roomId).url
            liveCache[roomId] = url
            url
        } catch (e: Exception) {
            ringLog.add(LogLevel.WARN, "proxy", "解析直播直链失败 room=" + roomId + ": " + e.message, clock.nowMs())
            liveCache[roomId]
        }
    }

    private fun resolveVideoUrl(bvid: String): String? {
        return try {
            val url = videoResolver.resolve(bvid)
            videoCache[bvid] = url
            url
        } catch (e: Exception) {
            ringLog.add(LogLevel.WARN, "proxy", "解析点播直链失败 bvid=" + bvid + ": " + e.message, clock.nowMs())
            videoCache[bvid]
        }
    }

    private fun withBoundPort(boundPort: Int): AppConfig =
        config.copy(port = boundPort, publicHost = config.publicHost.ifBlank { "127.0.0.1" })

    private fun describe(state: PlaybackState): String = when (state) {
        is PlaybackState.Idle -> "Idle/" + state.reason
        is PlaybackState.Starting -> "Starting idx=" + state.index
        is PlaybackState.Playing -> "Playing idx=" + state.index
        is PlaybackState.Switching -> "Switching " + state.fromIndex + "->" + state.toIndex + " (" + state.reason + ")"
        is PlaybackState.Error -> "Error/" + state.kind
    }

    companion object {
        fun liveOnlySession(config: AppConfig): Session =
            LiveSessions.liveSession(config.rooms, config.publicHost, config.port, config.switchSeconds.toLong())

        /** 生产入口：全部用真实实现，Android 层只需要拿到 tv 与路径。 */
        fun production(config: AppConfig, tv: TvController, allowDirectUrl: Boolean = false): AppGraph {
            val http = tvrc.core.bili.UrlConnectionHttpText()
            return AppGraph(
                config = config,
                tv = tv,
                liveApi = tvrc.core.bili.RealBiliLiveApi(http),
                videoResolver = tvrc.core.bili.RealVideoUrlResolver(http),
                fetcher = HttpUrlConnectionFetcher(baseHeaders = emptyMap()),
                allowDirectUrl = allowDirectUrl
            )
        }
    }
}
