package tvrc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import tvrc.core.AppGraph
import tvrc.core.IdleReason
import tvrc.core.Mode
import tvrc.core.PlaybackState
import tvrc.core.Session
import tvrc.core.TvController
import tvrc.core.adb.AdbKeyStore
import tvrc.core.adb.AdbTvController
import tvrc.core.adb.AdbTransport
import tvrc.core.adb.DadbClient
import tvrc.core.bili.BiliLiveApi
import tvrc.core.bili.RealBiliLiveApi
import tvrc.core.bili.RealVideoUrlResolver
import tvrc.core.bili.VideoTargets
import tvrc.core.bili.UrlConnectionHttpText
import tvrc.core.config.AppConfig
import tvrc.core.http.HttpUrlConnectionFetcher
import tvrc.core.http.MediaTransferEvent
import tvrc.core.http.MediaPhase
import tvrc.core.http.MediaFailure
import tvrc.core.json.Json
import tvrc.core.json.field
import tvrc.core.json.longOrNull
import tvrc.core.json.strOrNull
import tvrc.core.media.AdbMediaSessionSource
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 本机在局域网里的 IPv4（电视要能访问到手机，绝不能给 127.0.0.1）。 */
object LanAddress {
    fun localIpv4(): String {
        return runCatching {
            val all = Collections.list(NetworkInterface.getNetworkInterfaces())
            all.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
    }
}

/**
 * 进程内单点状态（UI 与服务之间的唯一桥）。
 *
 * 为什么不用 Binder/LiveData：首版只有「一个 Activity + 一个前台服务」，
 * 用单例 + 主线程回调最省事，也不会因为 Service 被系统重启而丢监听。
 */
object Ctl {

    @Volatile
    var store: Store? = null

    @Volatile
    var service: PlaybackService? = null

    @Volatile
    var state: PlaybackState = PlaybackState.Idle(0L, IdleReason.NEVER_STARTED)
        private set

    @Volatile
    var proxyPort: Int = 0
        private set

    @Volatile
    var playingTitle: String = ""
        private set

    @Volatile
    var tvAlive: Boolean = false
        private set

    @Volatile
    var tvPlaying: Boolean = false
        private set

    @Volatile
    var toast: String = ""
        private set

    @Volatile
    var mediaText: String = ""
        private set

    fun onMediaText(text: String) {
        mediaText = text
        refresh()
    }

    /** 用户手动按过停止：监控抢播默认必须尊重它（R-08 规则）。 */
    @Volatile
    var userStopped: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)

    fun onChange(cb: () -> Unit) {
        listeners += cb
    }

    fun offChange(cb: () -> Unit) {
        listeners -= cb
    }

    fun refresh() {
        main.post { listeners.toList().forEach { runCatching { it() } } }
    }

    fun bg(task: () -> Unit) {
        io.execute { runCatching(task) }
    }

    fun onState(s: PlaybackState) {
        state = s
        if (s is PlaybackState.Error) tvPlaying = false
        refresh()
    }

    fun onStatusChanged(port: Int, alive: Boolean, playing: Boolean) {
        if (port > 0) proxyPort = port
        tvAlive = alive
        tvPlaying = playing
        refresh()
    }

    fun onPlaying(title: String) {
        playingTitle = title
        mediaText = "等待电视请求媒体（画面状态未知）"
        refresh()
    }

    fun onExplicitPlay() {
        userStopped = false
    }

    fun onUserStopped() {
        userStopped = true
        playingTitle = ""
        mediaText = ""
        refresh()
    }

    fun say(message: String) {
        toast = message
        refresh()
    }

    fun stateText(): String = when (val s = state) {
        is PlaybackState.Idle -> "空闲"
        is PlaybackState.Starting -> "启动中（第 " + s.attempt + " 次）"
        is PlaybackState.Playing -> "播放中"
        is PlaybackState.Switching -> "切台中"
        is PlaybackState.Error -> "错误：" + s.kind.userText
    }
}

/** 导入结果：成功给条目和说明，失败给能直接显示给用户的原因。 */
sealed interface ImportOutcome {
    data class Ok(val items: List<LibItem>, val note: String) : ImportOutcome
    data class Fail(val reason: String, val hops: List<String> = emptyList()) : ImportOutcome
}

/** 单集视频信息（core 的 ArchiveItem 只覆盖合集接口，这里补单集标题）。 */
class ViewInfoApi(private val http: tvrc.core.bili.HttpText) {

    data class Info(val title: String, val owner: String, val pages: Int, val durationSec: Long)

    fun info(bvid: String): Info? {
        val url = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid
        val body = runCatching { http.get(url, RealBiliLiveApi.HEADERS, 12_000) }.getOrNull() ?: return null
        val root = runCatching { Json.parse(body) }.getOrNull() ?: return null
        val d = root.field("data") ?: return null
        return Info(
            title = d.field("title").strOrNull() ?: bvid,
            owner = d.field("owner").field("name").strOrNull() ?: "",
            pages = d.field("videos").longOrNull()?.toInt() ?: 1,
            durationSec = d.field("duration").longOrNull() ?: 0L
        )
    }
}

/**
 * 链接导入（R-01/R-02/R-05）：分享文案或网址 → 内容库条目。
 *
 * 分工：
 * - 短链展开与识别交给 core 的 [tvrc.core.bili.SourceExpander]（零 Android 依赖、有单测）；
 * - 「合集/系列到底是不是合集」必须问接口，不靠正则猜（K 裁决 F-16）；
 * - 任何一步失败都返回可读原因，绝不静默吞掉。
 */
class LinkImporter(
    private val expander: tvrc.core.bili.SourceExpander,
    private val liveApi: BiliLiveApi,
    private val archive: tvrc.core.bili.BiliArchiveApi,
    private val viewApi: ViewInfoApi
) {

    fun import(raw: String): ImportOutcome {
        val exp = expander.expand(raw)
        val src = exp.source
        return when (src) {
            is tvrc.core.bili.Source.LiveRoom -> {
                val info = runCatching { liveApi.roomInfo(src.roomId) }.getOrNull()
                val title = info?.title?.takeIf { it.isNotBlank() } ?: ("直播间 " + src.roomId)
                val sub = when {
                    info == null -> "已导入，开播状态未知"
                    info.live -> "直播中 · 人气 " + info.online
                    else -> "未开播"
                }
                ImportOutcome.Ok(
                    listOf(
                        LibItem(
                            id = "", kind = ItemKind.LIVE, title = title, subtitle = sub,
                            roomId = src.roomId, sourceUrl = raw.trim()
                        )
                    ),
                    exp.note + " · " + sub
                )
            }

            is tvrc.core.bili.Source.Video -> {
                val info = runCatching { viewApi.info(src.bvid) }.getOrNull()
                ImportOutcome.Ok(
                    listOf(
                        LibItem(
                            id = "", kind = ItemKind.VIDEO,
                            title = info?.title ?: src.bvid,
                            subtitle = listOfNotNull(
                                info?.owner?.takeIf { it.isNotBlank() },
                                if ((info?.pages ?: 1) > 1) "共 " + info?.pages + " P" else null,
                                src.bvid
                            ).joinToString(" · "),
                            bvid = src.bvid, sourceUrl = raw.trim()
                        )
                    ),
                    exp.note
                )
            }

            is tvrc.core.bili.Source.Series -> {
                val page = runCatching { archive.seriesAll(src.mid, src.listId) }
                val items = page.getOrNull()
                if (items.isNullOrEmpty()) {
                    ImportOutcome.Fail("合集 " + src.listId + " 没有拿到任何稿件（" + (page.exceptionOrNull()?.message ?: "接口返回空") + "）", exp.hops)
                } else {
                    ImportOutcome.Ok(
                        listOf(
                            LibItem(
                                id = "", kind = ItemKind.SERIES,
                                title = "合集 " + src.listId + "（" + items.size + " 集）",
                                subtitle = items.first().title + " 等",
                                bvids = items.map { it.bvid },
                                mid = src.mid, listId = src.listId, sourceUrl = raw.trim()
                            )
                        ),
                        "已展开 " + items.size + " 集"
                    )
                }
            }

            is tvrc.core.bili.Source.Season -> {
                val page = runCatching { archive.seasonAll(src.mid, src.seasonId) }
                val items = page.getOrNull()
                if (items.isNullOrEmpty()) {
                    ImportOutcome.Fail("系列 " + src.seasonId + " 没有拿到任何稿件（" + (page.exceptionOrNull()?.message ?: "接口返回空") + "）", exp.hops)
                } else {
                    ImportOutcome.Ok(
                        listOf(
                            LibItem(
                                id = "", kind = ItemKind.SERIES,
                                title = "系列 " + src.seasonId + "（" + items.size + " 集）",
                                subtitle = items.first().title + " 等",
                                bvids = items.map { it.bvid },
                                mid = src.mid, sourceUrl = raw.trim()
                            )
                        ),
                        "已展开 " + items.size + " 集"
                    )
                }
            }

            is tvrc.core.bili.Source.Up ->
                ImportOutcome.Fail("UP 主主页需要先选一个合集（首版暂不整站展开），请粘贴合集链接", exp.hops)

            is tvrc.core.bili.Source.ShortLink ->
                ImportOutcome.Fail(exp.note + "；可以把手机分享里的完整网址复制过来", exp.hops)

            tvrc.core.bili.Source.Unknown ->
                ImportOutcome.Fail("没认出这个输入：支持房间号 / live.bilibili.com 网址 / BV 号 / 合集链接 / b23.tv 短链")
        }
    }
}

/**
 * 前台服务（R-03/R-07/R-08 的承载者）：
 * - 持有 [AppGraph]（媒体代理 + 播放状态机 + 看门狗）；
 * - 跑节目单定时器与开播监控轮询；
 * - 通知栏常驻，手机息屏也不被系统掐掉。
 */
class PlaybackService : Service() {

    companion object {
        const val CH_PLAY = "tvrc-play"
        const val CH_LIVE = "tvrc-live"
        const val NOTIF_ID = 1001
        const val LIVE_NOTIF_BASE = 2000

        const val ACTION_PLAY_ITEM = "tvrc.action.PLAY_ITEM"
        const val ACTION_PLAY_ROOM = "tvrc.action.PLAY_ROOM"
        const val ACTION_STOP = "tvrc.action.STOP"
        const val ACTION_POLL = "tvrc.action.POLL"
        const val EXTRA_ITEM = "itemId"
        const val EXTRA_ROOM = "roomId"
        const val EXTRA_REASON = "reason"

        fun playItem(ctx: Context, itemId: String, reason: String = "manual") {
            val i = Intent(ctx, PlaybackService::class.java).apply {
                action = ACTION_PLAY_ITEM
                putExtra(EXTRA_ITEM, itemId)
                putExtra(EXTRA_REASON, reason)
            }
            start(ctx, i)
        }

        fun playRoom(ctx: Context, roomId: Long, reason: String = "manual") {
            val i = Intent(ctx, PlaybackService::class.java).apply {
                action = ACTION_PLAY_ROOM
                putExtra(EXTRA_ROOM, roomId)
                putExtra(EXTRA_REASON, reason)
            }
            start(ctx, i)
        }

        fun stopPlayback(ctx: Context) {
            start(ctx, Intent(ctx, PlaybackService::class.java).apply { action = ACTION_STOP })
        }

        fun ensureRunning(ctx: Context) {
            start(ctx, Intent(ctx, PlaybackService::class.java).apply { action = ACTION_POLL })
        }

        private fun start(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }
    }

    private lateinit var store: Store
    private var graph: AppGraph? = null
    @Volatile
    private var transport: AdbTransport? = null
    @Volatile
    private var transportTarget: Pair<String, Int>? = null

    /** 借用同一目标的串行传输层；不得因自检再建一个竞争 ADB 连接。 */
    internal fun activeAdbShell(cfg: AppConfig): ((String, Long) -> tvrc.core.adb.ShellResult)? {
        val current = transport ?: return null
        if (transportTarget != (cfg.tvHost to cfg.tvPort)) return null
        return { command, timeoutMs -> current.shell(command, timeoutMs) }
    }
    private val playbackRequests = PlaybackRequestGate()
    private val playbackCommit = Any()
    private var lastNetworkSignature: String? = null
    @Volatile
    private var probeRunning = false
    private var tvProbeFailures = 0
    private var tvProbeSamples = 0

    private var tv: TvController? = null
    private var handler: Handler? = null
    private val io = Executors.newFixedThreadPool(3)

    @Volatile
    private var startedGraph = false

    private var monitorNextAt = 0L
    private var scheduleNextAt = 0L
    private var backoffMs = 0L
    private var lastTvConnected = false
    private var lastTvPlaying = false
    private var lastTracedPlayback = ""
    private var currentPlaybackStartedAt = 0L
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreate() {
        super.onCreate()
        store = Ctl.store ?: Store(this).also { Ctl.store = it }
        Ctl.service = this
        createChannels()
        startForegroundCompat(buildNotification("服务已启动"))
        handler = Handler(Looper.getMainLooper())
        handler?.postDelayed({ tick() }, 1_000L)
        Ctl.bg { probeTv() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_ITEM -> intent.getStringExtra(EXTRA_ITEM)?.let { id ->
                val reason = intent.getStringExtra(EXTRA_REASON) ?: "manual"
                playItem(id, reason)
            }

            ACTION_PLAY_ROOM -> {
                val room = intent.getLongExtra(EXTRA_ROOM, 0L)
                val reason = intent.getStringExtra(EXTRA_REASON) ?: "manual"
                if (room > 0) playRoom(room, reason)
            }

            ACTION_STOP -> stopPlayback()
            else -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        runCatching { graph?.close() }
        graph = null
        startedGraph = false
        runCatching { transport?.close() }
        transport = null
        io.shutdownNow()
        if (Ctl.service === this) Ctl.service = null
        super.onDestroy()
    }

    // ---- 播放 ----

    private fun playItem(itemId: String, reason: String) {
        val item = store.item(itemId)
        if (item == null) {
            Ctl.say("内容库里找不到这条（可能已删除）")
            store.log("play", "找不到条目 " + itemId)
            store.trace(
                "播放", "准备", TraceStatus.ERROR, "内容库里找不到目标",
                "条目 ID：" + itemId + "，触发来源：" + reasonLabel(reason)
            )
            return
        }
        val ticket = playbackRequests.next()
        if (reason == "manual") Ctl.onExplicitPlay()
        Ctl.bg { startItem(item, reason, ticket) }
    }

    private fun playRoom(roomId: Long, reason: String) {
        val item = store.items().firstOrNull { it.kind == ItemKind.LIVE && it.roomId == roomId }
            ?: LibItem(id = "", kind = ItemKind.LIVE, title = "直播间 " + roomId, roomId = roomId)
        val ticket = playbackRequests.next()
        if (reason == "manual") Ctl.onExplicitPlay()
        Ctl.bg { startItem(item, reason, ticket) }
    }

    private fun startItem(item: LibItem, reason: String, ticket: Long) {
        if (!playbackRequests.isCurrent(ticket)) return
        val startedAt = System.currentTimeMillis()
        store.trace(
            "播放", "请求", TraceStatus.INFO, reasonLabel(reason) + "播放请求已收到",
            diagnosticItem(item)
        )
        currentPlaybackStartedAt = startedAt
        val cfg = store.config()
        if (cfg.tvHost.isBlank()) {
            Ctl.say("还没填电视地址：先去「设备」页做完向导")
            store.log("error", "电视地址为空，无法播放")
            store.trace(
                "播放", "准备", TraceStatus.ERROR, "电视地址为空，无法播放",
                diagnosticItem(item), System.currentTimeMillis() - startedAt
            )
            return
        }
        val session = try {
            buildSession(item, cfg)
        } catch (e: Exception) {
            if (!playbackRequests.isCurrent(ticket)) return
            Ctl.say(e.message ?: "内容不可播放")
            store.log("error", "构建播放会话失败：" + (e.message ?: ""))
            store.trace(
                "播放", "构建会话", TraceStatus.ERROR, e.message ?: "内容不可播放",
                diagnosticItem(item) + "\n异常类型：" + e.javaClass.name,
                System.currentTimeMillis() - startedAt
            )
            return
        }
        if (session == null) return
        val note = when (reason) {
            "schedule" -> "节目单"
            "monitor" -> "开播抢播"
            else -> "手动"
        }
        store.log("play", note + "播放：" + item.title)
        store.trace(
            "播放", "会话创建", TraceStatus.SUCCESS, "播放会话已创建",
            diagnosticItem(item) + "\n模式：" + session.mode +
                "\n目标：" + session.items.joinToString("\n") +
                "\n切换间隔：" + session.effectiveSwitchSeconds + " 秒" +
                "\n电视：" + cfg.tvAddress + "，播放器：" + cfg.mpvActivity,
            System.currentTimeMillis() - startedAt
        )
        synchronized(playbackCommit) {
        if (!playbackRequests.isCurrent(ticket)) {
            store.trace("播放", "取消过期请求", TraceStatus.INFO, "较早的准备结果已取消，不再拉起电视")
            return
        }
        Ctl.onPlaying(item.title)
        store.touchPlayed(item.id)
        store.trace(
            "播放", "提交", TraceStatus.INFO, "正在启动代理并等待电视回执",
            "代理端口：" + (if (cfg.port > 0) cfg.port else 8100) +
                "，本机地址：" + cfg.publicHost.ifBlank { LanAddress.localIpv4() }
        )
        playSession(session)
        updateNotification(Ctl.stateText() + " · " + item.title)
        }
    }

    /** 直播先查开播状态（R-02：未开播要给明确提示，不能拉起一个死流）。 */
    private fun buildSession(item: LibItem, cfg: AppConfig): Session? {
        val host = cfg.publicHost.ifBlank { LanAddress.localIpv4() }
        val port = if (cfg.port > 0) cfg.port else 8100
        return when (item.kind) {
            ItemKind.LIVE -> {
                val roomId = item.roomId ?: run {
                    Ctl.say("这条直播没有房间号")
                    store.trace("播放", "直播检查", TraceStatus.ERROR, "直播条目没有房间号", diagnosticItem(item))
                    return null
                }
                val infoResult = runCatching { liveApi().roomInfo(roomId) }
                val info = infoResult.getOrNull()
                if (infoResult.isFailure) {
                    val error = infoResult.exceptionOrNull()
                    store.trace(
                        "播放", "直播检查", TraceStatus.WARNING, "无法查询开播状态，继续尝试播放",
                        "房间：" + roomId + "\n异常：" + (error?.message ?: error?.javaClass?.name ?: "未知错误")
                    )
                } else if (info != null && !info.live) {
                    Ctl.say("「" + item.title + "」未开播，等开播后再试")
                    store.log("monitor", "房间 " + roomId + " 未开播，已跳过")
                    store.trace(
                        "播放", "直播检查", TraceStatus.WARNING, "直播间当前未开播，已跳过",
                        diagnosticItem(item)
                    )
                    return null
                } else if (info != null) {
                    store.trace(
                        "播放", "直播检查", TraceStatus.SUCCESS, "直播间正在开播",
                        "房间：" + roomId + "\n标题：" + info.title +
                            (if (info.online > 0) "\n人气：" + info.online else "")
                    )
                }
                Session(
                    rooms = listOf("http://" + host + ":" + port + "/live?room=" + roomId),
                    switchSeconds = tvrc.core.MAX_SWITCH_SECONDS,
                    mode = Mode.SINGLE
                )
            }

            ItemKind.VIDEO -> {
                val bvid = item.bvid ?: run {
                    Ctl.say("这条视频没有 BV 号")
                    store.trace("播放", "视频检查", TraceStatus.ERROR, "视频条目没有 BV 号", diagnosticItem(item))
                    return null
                }
                store.trace(
                    "播放", "视频检查", TraceStatus.SUCCESS, "视频目标有效",
                    "BV：" + bvid + "\n标题：" + item.title
                )
                Session(
                    videos = listOf(VideoTargets.target(bvid, host, port)),
                    switchSeconds = tvrc.core.MAX_SWITCH_SECONDS,
                    mode = Mode.VIDEO
                )
            }

            ItemKind.SERIES -> {
                if (item.bvids.isEmpty()) {
                    Ctl.say("合集是空的，请重新导入")
                    store.trace("播放", "合集检查", TraceStatus.ERROR, "合集没有可播放的剧集", diagnosticItem(item))
                    return null
                }
                Session(
                    videos = item.bvids.map { VideoTargets.target(it, host, port) },
                    switchSeconds = cfg.switchSeconds.toLong(),
                    mode = Mode.VIDEO
                )
            }
        }
    }

    private fun playSession(session: Session) {
        val cfg = store.config()
        if (!startedGraph || graph == null) {
            val g = buildGraph(cfg, session)
            graph = g
            startedGraph = true
            val bound = runCatching { g.start() }.getOrElse { e ->
                Ctl.say("代理启动失败：" + (e.message ?: "端口被占用？"))
                store.log("error", "代理启动失败：" + (e.message ?: ""))
                store.trace(
                    "播放", "代理启动", TraceStatus.ERROR, "代理或播放引擎启动失败",
                    "端口：" + cfg.port + "\n异常：" + e.javaClass.name + ": " + (e.message ?: "未知错误")
                )
                startedGraph = false
                return
            }
            Ctl.onStatusChanged(bound, Ctl.tvAlive, Ctl.tvPlaying)
            store.log("app", "代理已启动 :" + bound)
            store.trace(
                "播放", "代理启动", TraceStatus.SUCCESS, "媒体代理已启动",
                "监听端口：" + bound + "\n本机地址：" +
                    cfg.publicHost.ifBlank { LanAddress.localIpv4() }
            )
        } else {
            graph?.engine?.start(session)
        }
    }

    private fun buildGraph(cfg: AppConfig, initial: Session): AppGraph {
        val keyStore = AdbKeyStore(store.keyDir, "adbkey")
        val t = AdbTransport(
            clientFactory = { DadbClient(cfg.tvHost, cfg.tvPort, keyStore) },
            keyStore = keyStore,
            defaultTimeoutMs = 25_000
        )
        transportTarget = cfg.tvHost to cfg.tvPort
        transport = t
        val controller = AdbTvController(
            transport = t,
            mpvPackage = cfg.mpvPkg,
            mpvComponent = cfg.mpvActivity,
            mediaSession = AdbMediaSessionSource(t)
        )
        tv = controller
        // 代理白名单要覆盖「内容库里的所有直播间」，否则播库里的房间会被 400 挡住
        val allowedRooms = (cfg.rooms + store.items().mapNotNull { if (it.kind == ItemKind.LIVE) it.roomId else null })
            .distinct()
        val effective = cfg.copy(
            rooms = allowedRooms,
            publicHost = cfg.publicHost.ifBlank { LanAddress.localIpv4() }
        )
        val http = UrlConnectionHttpText()
        return AppGraph(
            config = effective,
            tv = controller,
            liveApi = RealBiliLiveApi(http),
            videoResolver = RealVideoUrlResolver(http),
            fetcher = HttpUrlConnectionFetcher(baseHeaders = emptyMap()),
            sessionProvider = { initial },
            onState = { s ->
                Ctl.onState(s)
                tracePlaybackState(s)
            },
            onMediaEvent = { event -> traceMedia(event) },
            onMessage = { code, text ->
                store.trace("恢复", code, TraceStatus.WARNING, text)
                Ctl.say(text)
            }
        )
    }

    private fun tracePlaybackState(state: PlaybackState) {
        val signature = when (state) {
            is PlaybackState.Idle -> "idle-" + state.reason
            is PlaybackState.Starting -> "starting-" + state.index + "-" + state.attempt
            is PlaybackState.Playing -> "playing-" + state.index + "-" + state.startedAtMs
            is PlaybackState.Switching -> "switching-" + state.fromIndex + "-" + state.toIndex
            is PlaybackState.Error -> "error-" + state.kind + "-" + state.atMs
        }
        if (signature == lastTracedPlayback) return
        lastTracedPlayback = signature
        when (state) {
            is PlaybackState.Idle -> store.trace(
                "播放", "状态", TraceStatus.INFO, "播放已停止",
                when (state.reason) {
                    IdleReason.USER_STOP -> "原因：用户停止"
                    IdleReason.IDLE_TIMEOUT -> "原因：空闲超时"
                    IdleReason.NEVER_STARTED -> "原因：尚未开始"
                }
            )
            is PlaybackState.Starting -> store.trace(
                "播放", "等待电视", TraceStatus.INFO,
                "已发送唤醒和起播命令，等待回执",
                "项目：" + state.session.target(state.index) +
                    "\n尝试：" + state.attempt + "/3" +
                    "\n模式：" + state.session.mode
            )
            is PlaybackState.Playing -> store.trace(
                "播放", "电视回执", TraceStatus.SUCCESS, "电视已接受起播命令",
                "项目：" + state.session.target(state.index) +
                    "\n下一次切换：" + state.nextSwitchAtMs + "（毫秒时间戳）" +
                    "\n说明：这表示电视播放器接受了命令；媒体是否持续可播由后续探测确认。",
                playbackElapsed()
            )
            is PlaybackState.Switching -> store.trace(
                "播放", "换台", TraceStatus.INFO,
                "正在从第 " + (state.fromIndex + 1) + " 项切到第 " + (state.toIndex + 1) + " 项",
                "目标：" + state.session.target(state.toIndex) +
                    "\n原因：" + state.reason
            )
            is PlaybackState.Error -> store.trace(
                "播放", "电视回执", TraceStatus.ERROR, state.kind.userText,
                "错误分类：" + state.kind +
                    "\n重试策略：由错误类型、次数上限与用户停止状态共同决定" +
                    "\n详情：" + state.detail.ifBlank { "电视或播放引擎没有返回更多信息" },
                playbackElapsed()
            )
        }
    }

    private fun playbackElapsed(): Long? =
        if (currentPlaybackStartedAt > 0) System.currentTimeMillis() - currentPlaybackStartedAt else null

    private fun liveApi(): BiliLiveApi = RealBiliLiveApi(UrlConnectionHttpText())

    private fun stopPlayback() {
        val stopTicket = playbackRequests.next() // no main-thread wait on a slow ADB launch
        Ctl.onUserStopped()
        Ctl.bg {
            synchronized(playbackCommit) {
                // A subsequent explicit play supersedes this stop job.
                if (!playbackRequests.isCurrent(stopTicket)) return@bg
                runCatching { graph?.stop() }
                store.log("play", "用户停止")
                store.trace("播放", "停止", TraceStatus.INFO, "用户主动停止播放，不自动恢复")
                updateNotification("已停止")
            }
        }
    }

    private fun traceMedia(event: MediaTransferEvent) {
        val d = MediaDiagnostics.describe(event)
        store.trace("媒体链路", d.action, d.status, d.summary, d.details, event.elapsedMs)
        // Late EOF from an old socket is diagnostic evidence only, not a reason to relaunch.
        if (event.headOnly || Ctl.userStopped || Ctl.state is PlaybackState.Idle) return
        val state = Ctl.state
        val target = when (state) {
            is PlaybackState.Playing -> state.session.target(state.index)
            is PlaybackState.Starting -> state.session.target(state.index)
            is PlaybackState.Switching -> state.session.target(state.toIndex)
            else -> ""
        }
        val identity = runCatching {
            val uri = java.net.URI(target)
            val query = tvrc.core.http.HttpParsing.parseQuery(uri.rawQuery.orEmpty())
            if (uri.path == "/video") query["bvid"] else query["room"]?.let { "room:$it" }
        }.getOrNull()
        if (identity != event.mediaIdentity) return
        if (event.phase in listOf(MediaPhase.FAILURE, MediaPhase.RETRY, MediaPhase.FIRST_BYTE, MediaPhase.PROGRESS)) {
            if (event.failure !in listOf(MediaFailure.CLIENT_WRITE, MediaFailure.SERVER_CLOSED)) {
                Ctl.onMediaText(d.summary)
            }
        }
    }

    /** Passive connectivity observation; no speed-test traffic and no automatic player restart. */
    private fun sampleNetwork() {
        val signature = runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "无可用默认网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络（电视可能无法访问手机代理）"
                else -> "其他网络"
            } + "; 网络标识=" + (network?.toString() ?: "无")
        }.getOrElse { "网络状态未知：" + it.javaClass.simpleName }
        if (signature == lastNetworkSignature) return
        lastNetworkSignature = signature
        store.trace("网络", "连接变化", if (signature.startsWith("无")) TraceStatus.WARNING else TraceStatus.INFO,
            signature, "被动采样，不是延迟或丢包检测；切换网络后若电视无法播放，请确认同一局域网并检查手机代理地址。不会自动重开视频。")
    }

    // ---- 周期任务：探针 + 节目单 + 开播监控 ----

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now >= monitorNextAt) {
            monitorNextAt = now + 5_000L
            if (!probeRunning) {
                probeRunning = true
                Ctl.bg { try { sampleNetwork(); probeTv() } finally { probeRunning = false } }
            }
        }
        if (now >= scheduleNextAt) {
            scheduleNextAt = now + 20_000L
            Ctl.bg { runDueSchedules() }
        }
        if (store.monitorEnabled && now >= monitorPollAt) {
            monitorPollAt = now + pollIntervalMs()
            Ctl.bg { pollMonitors() }
        }
        updateNotification(Ctl.stateText() + (if (Ctl.playingTitle.isNotBlank()) " · " + Ctl.playingTitle else ""))
        handler?.postDelayed({ tick() }, 1_000L)
    }

    @Volatile
    private var monitorPollAt = 0L

    /** 自适应间隔（R-08）：有房间在播就慢一点，连续失败退避到 5 分钟，基础值取用户设置。 */
    private fun pollIntervalMs(): Long {
        val base = store.pollSeconds * 1_000L
        val boosted = if (store.monitors().any { it.lastLive }) base * 3 else base
        return (boosted + backoffMs).coerceIn(20_000L, 600_000L)
    }

    private fun probeTv(): Boolean {
        val controller = tv ?: return false
        val status = runCatching { controller.probe() }.getOrNull() ?: return false
        val ping = transport?.shell("echo TVRC_ADB_OK", 5_000)
        val connected = ping?.ok == true && ping.stdout.trim() == "TVRC_ADB_OK"
        tvProbeSamples++
        if (connected) tvProbeFailures = 0 else tvProbeFailures++
        if (tvProbeSamples % 6 == 0 || !connected) {
            store.trace("电视", "连接采样", if (connected) TraceStatus.INFO else TraceStatus.WARNING,
                if (connected) "ADB 可达，媒体画面仍需电视确认" else "本次 ADB 探测失败（不代表视频已中断）",
                "连续失败：$tvProbeFailures；进程存活：${status.alive}；系统播放状态：${status.playing}\n" +
                    "单次超时不重启播放器；持续失败请检查电视电源、同一局域网、IP、调试授权。")
        }
        Ctl.onStatusChanged(Ctl.proxyPort, connected, status.playing)
        if (connected != lastTvConnected || status.playing != lastTvPlaying) {
            store.trace(
                "电视", "状态变化",
                if (connected) TraceStatus.SUCCESS else TraceStatus.ERROR,
                "ADB " + (if (connected) "在线" else "离线") +
                    "，播放状态" + (if (status.playing) "已确认" else "未确认"),
                "进程存活：" + status.alive +
                    (if (ping?.ok != true) "\nADB 详情：" + (ping?.stderr ?: "未返回") else "")
            )
            lastTvConnected = connected
            lastTvPlaying = status.playing
        }
        return connected
    }

    private fun reasonLabel(reason: String): String = when (reason) {
        "schedule" -> "节目单"
        "monitor" -> "开播抢播"
        else -> "手动"
    }

    private fun diagnosticItem(item: LibItem): String = when (item.kind) {
        ItemKind.LIVE -> "类型：直播\n房间：" + (item.roomId ?: 0) + "\n标题：" + item.title
        ItemKind.VIDEO -> "类型：视频\nBV：" + (item.bvid ?: "无") + "\n标题：" + item.title
        ItemKind.SERIES -> "类型：合集\n标题：" + item.title + "\n集数：" + item.bvids.size
    }

    private fun runDueSchedules() {
        val cal = java.util.Calendar.getInstance()
        val dayIndex = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> 1
            java.util.Calendar.TUESDAY -> 2
            java.util.Calendar.WEDNESDAY -> 3
            java.util.Calendar.THURSDAY -> 4
            java.util.Calendar.FRIDAY -> 5
            java.util.Calendar.SATURDAY -> 6
            else -> 7
        }
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val hh = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val mm = cal.get(java.util.Calendar.MINUTE)
        for (s in store.schedules()) {
            if (!s.enabled) continue
            if (s.days.isNotEmpty() && dayIndex !in s.days) continue
            val due = s.hour == hh && s.minute == mm
            val runKey = dateKey + " " + s.timeText()
            if (!due || s.lastRunKey == runKey) continue
            store.markScheduleRun(s.id, runKey)
            store.log("schedule", "到点执行：" + s.title + "（" + s.timeText() + "）")
            if (s.wake) {
                val wake = runCatching { tv?.wake() }
                if (wake.isFailure || wake.getOrNull()?.ok == false) {
                    store.trace(
                        "节目单", "唤醒电视", TraceStatus.WARNING, "唤醒电视没有成功，继续尝试播放",
                        "节目：" + s.title +
                            "\n详情：" + (wake.exceptionOrNull()?.message ?: wake.getOrNull()?.detail ?: "播放器未连接")
                    )
                }
                Thread.sleep(1_200L)
                runCatching { tv?.wake() }
            }
            playItem(s.itemId, "schedule")
        }
    }

    private fun pollMonitors() {
        val observedGeneration = playbackRequests.snapshot()
        if (!store.monitorEnabled) return
        val list = store.monitors().filter { it.enabled }
        if (list.isEmpty()) {
            backoffMs = 0L
            return
        }
        var failures = 0
        for (m in list) {
            val result = runCatching { liveApi().roomInfo(m.roomId) }
            val info = result.getOrNull()
            if (info == null) {
                failures++
                store.trace(
                    "直播监控", "开播检查", TraceStatus.WARNING, "无法查询直播间状态",
                    "房间：" + m.roomId + "\n标题：" + m.title +
                        "\n异常：" + (result.exceptionOrNull()?.message ?: "接口返回空")
                )
                continue
            }
            val changed = info.live != m.lastLive
            val updated = m.copy(
                title = info.title.ifBlank { m.title },
                lastLive = info.live,
                lastChangeAt = if (changed) System.currentTimeMillis() else m.lastChangeAt
            )
            store.updateMonitor(updated)
            if (!changed) continue
            if (info.live) {
                store.log("monitor", "开播：" + updated.title + "（房间 " + m.roomId + "）")
                notifyLive(updated)
                maybeGrab(updated, observedGeneration)
            } else {
                store.log("monitor", "下播：" + updated.title)
                cancelLiveNotification(m.roomId)
            }
        }
        backoffMs = if (failures > 0) (backoffMs + 30_000L).coerceAtMost(300_000L) else 0L
        Ctl.refresh()
    }

    /** 抢播规则（R-08）：priority>0 才打断；用户手动停止时必须显式允许覆盖。 */
    private fun maybeGrab(m: MonitorItem, observedGeneration: Long) {
        if (m.priority <= 0) return
        if (Ctl.userStopped && !m.allowOverride) {
            store.log("monitor", "跳过抢播：" + m.title + "（用户手动停止且未允许覆盖）")
            return
        }
        store.log("monitor", "抢播：" + m.title)
        // A lookup begun before a newer play/stop cannot supersede that command.
        val ticket = playbackRequests.nextIfCurrent(observedGeneration) ?: return
        val item = store.items().firstOrNull { it.kind == ItemKind.LIVE && it.roomId == m.roomId }
            ?: LibItem(id = "", kind = ItemKind.LIVE, title = m.title, roomId = m.roomId)
        Ctl.bg { startItem(item, "monitor", ticket) }
    }

    // ---- 通知 ----

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PLAY, "播放服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "代理与播放状态常驻通知"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, "开播提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "关注的直播间开播时提醒"
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_PLAY)
            .setContentTitle("B站直播遥控器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun notifyLive(m: MonitorItem) {
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 100,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CH_LIVE)
            .setContentTitle("开播提醒 · " + m.title)
            .setContentText("房间 " + m.roomId + " 刚开播" + (if (m.priority > 0) "，按规则抢播" else ""))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(LIVE_NOTIF_BASE + (m.roomId % 1000).toInt(), notif) }
    }

    private fun cancelLiveNotification(roomId: Long) {
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(LIVE_NOTIF_BASE + (roomId % 1000).toInt())
        }
    }
}
