package tvrc.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tvrc.core.config.AppConfig
import tvrc.core.config.ConfigValidator
import tvrc.core.config.FsConfigStore
import tvrc.core.config.JsonConfigRepository
import java.io.File
import java.util.UUID

/** 内容库条目的三种类型。 */
enum class ItemKind { VIDEO, SERIES, LIVE }

/**
 * 内容库条目（R-06）。
 *
 * 设计裁决：
 * - 一条 LibItem 就是「用户能点播的一个东西」：单集视频、整组合集、单个直播间；
 * - 合集把 bvids 全存下来（导入时一次性展开完），播放时不再联网，列表离线也能看；
 * - id 用稳定键生成（kind+目标），重复导入同一个链接会复用旧条目，不重复建项。
 */
data class LibItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val subtitle: String = "",
    val bvid: String? = null,
    val bvids: List<String> = emptyList(),
    val roomId: Long? = null,
    val mid: Long? = null,
    val listId: Long? = null,
    val sourceUrl: String = "",
    val addedAt: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val favorite: Boolean = false
) {
    val kindLabel: String
        get() = when (kind) {
            ItemKind.VIDEO -> "视频"
            ItemKind.SERIES -> "合集"
            ItemKind.LIVE -> "直播"
        }

    val episodeCount: Int
        get() = when (kind) {
            ItemKind.SERIES -> bvids.size
            else -> 1
        }

    fun searchKey(): String =
        (title + " " + subtitle + " " + (bvid ?: "") + " " + (roomId?.toString() ?: "")).lowercase()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("title", title)
        put("subtitle", subtitle)
        put("bvid", bvid ?: "")
        put("bvids", JSONArray(bvids))
        put("roomId", roomId ?: 0L)
        put("mid", mid ?: 0L)
        put("listId", listId ?: 0L)
        put("sourceUrl", sourceUrl)
        put("addedAt", addedAt)
        put("lastPlayedAt", lastPlayedAt)
        put("favorite", favorite)
    }

    companion object {
        fun fromJson(o: JSONObject): LibItem = LibItem(
            id = o.optString("id"),
            kind = runCatching { ItemKind.valueOf(o.optString("kind", "VIDEO")) }.getOrDefault(ItemKind.VIDEO),
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            bvid = o.optString("bvid").ifBlank { null },
            bvids = o.optJSONArray("bvids")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            roomId = o.optLong("roomId").takeIf { it > 0 },
            mid = o.optLong("mid").takeIf { it > 0 },
            listId = o.optLong("listId").takeIf { it > 0 },
            sourceUrl = o.optString("sourceUrl"),
            addedAt = o.optLong("addedAt"),
            lastPlayedAt = o.optLong("lastPlayedAt"),
            favorite = o.optBoolean("favorite")
        )
    }
}

/**
 * 节目单条目（R-07）：到点由服务拉起指定内容，可选先唤醒电视。
 * [itemIds] 是按顺序播放的内容库条目；旧数据没有这个字段时退回单个 [itemId]。
 */
data class ScheduleItem(
    val id: String,
    val itemId: String,
    val title: String,
    val hour: Int,
    val minute: Int,
    val days: Set<Int>,          // 1=周一 .. 7=周日；空集 = 每天
    val wake: Boolean = true,
    val enabled: Boolean = true,
    val lastRunKey: String = "", // "2026-11-20 20:30"，防同分钟重复执行
    val itemIds: List<String> = emptyList()
) {
    /** 兼容旧数据：没有 itemIds 时就是单个 itemId。 */
    val allItemIds: List<String>
        get() = itemIds.ifEmpty { listOf(itemId).filter { it.isNotBlank() } }

    fun timeText(): String = "%02d:%02d".format(hour, minute)

    fun daysText(): String = if (days.isEmpty()) "每天" else
        days.sorted().joinToString(" ") { d -> listOf("一", "二", "三", "四", "五", "六", "日")[d - 1] }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("itemId", itemId)
        put("title", title)
        put("hour", hour)
        put("minute", minute)
        put("days", JSONArray(days.sorted()))
        put("wake", wake)
        put("enabled", enabled)
        put("lastRunKey", lastRunKey)
        put("itemIds", JSONArray(itemIds))
    }

    companion object {
        fun fromJson(o: JSONObject): ScheduleItem = ScheduleItem(
            id = o.optString("id"),
            itemId = o.optString("itemId"),
            title = o.optString("title"),
            hour = o.optInt("hour"),
            minute = o.optInt("minute"),
            days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: emptySet(),
            wake = o.optBoolean("wake", true),
            enabled = o.optBoolean("enabled", true),
            lastRunKey = o.optString("lastRunKey"),
            itemIds = o.optJSONArray("itemIds")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { s -> s.isNotBlank() } }
            } ?: emptyList()
        )
    }
}

/** 开播监控条目（R-08）：priority>0 时可打断普通播放，allowOverride 才允许覆盖手动停止。 */
data class MonitorItem(
    val roomId: Long,
    val title: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val allowOverride: Boolean = false,
    val autoWake: Boolean = true,
    val lastLive: Boolean = false,
    val lastChangeAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("roomId", roomId)
        put("title", title)
        put("enabled", enabled)
        put("priority", priority)
        put("allowOverride", allowOverride)
        put("autoWake", autoWake)
        put("lastLive", lastLive)
        put("lastChangeAt", lastChangeAt)
    }

    companion object {
        fun fromJson(o: JSONObject): MonitorItem = MonitorItem(
            roomId = o.optLong("roomId"),
            title = o.optString("title"),
            enabled = o.optBoolean("enabled", true),
            priority = o.optInt("priority"),
            allowOverride = o.optBoolean("allowOverride"),
            autoWake = o.optBoolean("autoWake", true),
            lastLive = o.optBoolean("lastLive"),
            lastChangeAt = o.optLong("lastChangeAt")
        )
    }
}

/** 执行/事件记录（节目单执行、开播提醒、错误），UI 直接展示最近 N 条。 */
data class RunRecord(val atMs: Long, val kind: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("at", atMs)
        put("kind", kind)
        put("text", text)
    }

    companion object {
        fun fromJson(o: JSONObject) = RunRecord(o.optLong("at"), o.optString("kind"), o.optString("text"))
    }
}

enum class TraceStatus(val label: String) {
    INFO("进行中"),
    SUCCESS("成功"),
    WARNING("未确认"),
    ERROR("失败")
}

/**
 * 面向排障的结构化事件。与日志尾部并存，保留操作、目标、结果和耗时，
 * 让用户在 App 内直接判断导入与播放停在哪一步。
 */
data class TraceEvent(
    val atMs: Long,
    val area: String,
    val action: String,
    val status: TraceStatus,
    val summary: String,
    val details: String = "",
    val elapsedMs: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("at", atMs)
        put("area", area)
        put("action", action)
        put("status", status.name)
        put("summary", summary)
        put("details", details)
        if (elapsedMs != null) put("elapsedMs", elapsedMs)
    }

    companion object {
        fun fromJson(o: JSONObject): TraceEvent = TraceEvent(
            atMs = o.optLong("at"),
            area = o.optString("area"),
            action = o.optString("action"),
            status = runCatching { TraceStatus.valueOf(o.optString("status", "INFO")) }
                .getOrDefault(TraceStatus.INFO),
            summary = o.optString("summary"),
            details = o.optString("details"),
            elapsedMs = if (o.has("elapsedMs")) o.optLong("elapsedMs") else null
        )
    }
}

/**
 * 本地持久化（R-06「重启后内容仍在」）。
 *
 * 全部落 filesDir：config.json 沿用 :core 的 JsonConfigRepository（与二代同 schema，可直接迁移），
 * library.json 是本代新增（内容库 / 节目单 / 监控 / 执行记录）。
 * 所有读写 synchronized —— 服务在后台线程改、UI 在主线程读，别指望 SharedPreferences 的隐式同步。
 */
class Store(context: Context) {

    private val dir: File = context.filesDir
    private val libFile = File(dir, "library.json")
    val keyDir: File = File(dir, "adbkey").apply { mkdirs() }
    val logFile = File(dir, "tvrc.log")
    private val traceFile = File(dir, "diagnostics.json")

    val configPath: String = File(dir, "config.json").path
    private val repo = JsonConfigRepository(FsConfigStore(), configPath, ConfigValidator::checkDeviceSettings)

    private var cfg: AppConfig = AppConfig()
    private var configErrors: List<String> = emptyList()
    private var items: MutableList<LibItem> = mutableListOf()
    private var schedules: MutableList<ScheduleItem> = mutableListOf()
    private var monitors: MutableList<MonitorItem> = mutableListOf()
    private var runs: MutableList<RunRecord> = mutableListOf()
    private var traces: MutableList<TraceEvent> = mutableListOf()

    /** 监控总开关与轮询间隔（R-08 自适应轮询的基础参数）。 */
    var monitorEnabled: Boolean = true
        private set
    var pollSeconds: Int = 60
        private set

    init {
        reload()
    }

    @Synchronized
    fun reload() {
        val loaded = repo.loadOrDefaults()
        cfg = loaded.first
        configErrors = loaded.second
        val json = runCatching { if (libFile.isFile) JSONObject(libFile.readText()) else null }.getOrNull()
        items = json?.optJSONArray("items")?.toList { LibItem.fromJson(it) }?.toMutableList() ?: mutableListOf()
        schedules = json?.optJSONArray("schedule")?.toList { ScheduleItem.fromJson(it) }?.toMutableList()
            ?: mutableListOf()
        monitors = json?.optJSONArray("monitor")?.toList { MonitorItem.fromJson(it) }?.toMutableList()
            ?: mutableListOf()
        runs = json?.optJSONArray("runs")?.toList { RunRecord.fromJson(it) }?.toMutableList() ?: mutableListOf()
        monitorEnabled = json?.optBoolean("monitorEnabled", true) ?: true
        pollSeconds = json?.optInt("pollSeconds", 60) ?: 60
        val traceJson = runCatching {
            if (traceFile.isFile) JSONObject(traceFile.readText()) else null
        }.getOrNull()
        traces = traceJson?.optJSONArray("events")?.toList { TraceEvent.fromJson(it) }?.toMutableList()
            ?: mutableListOf()
    }

    @Synchronized
    private fun persist() {
        val root = JSONObject().apply {
            put("version", 1)
            put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })
            put("schedule", JSONArray().also { a -> schedules.forEach { a.put(it.toJson()) } })
            put("monitor", JSONArray().also { a -> monitors.forEach { a.put(it.toJson()) } })
            put("runs", JSONArray().also { a -> runs.takeLast(120).forEach { a.put(it.toJson()) } })
            put("monitorEnabled", monitorEnabled)
            put("pollSeconds", pollSeconds)
        }
        runCatching {
            val tmp = File(libFile.absolutePath + ".tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(libFile)) {
                libFile.writeText(root.toString(2))
                tmp.delete()
            }
        }
    }

    @Synchronized
    private fun persistTraces() {
        runCatching {
            val root = JSONObject().apply {
                put("version", 1)
                put("events", JSONArray().also { a -> traces.forEach { a.put(it.toJson()) } })
            }
            val tmp = File(traceFile.absolutePath + ".tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(traceFile)) {
                traceFile.writeText(root.toString(2))
                tmp.delete()
            }
        }
    }

    // ---- 配置 ----

    @Synchronized
    fun config(): AppConfig = cfg

    @Synchronized
    fun configProblems(): List<String> = configErrors

    @Synchronized
    fun saveConfig(c: AppConfig): List<String> {
        val errors = runCatching { repo.save(c) }.getOrElse {
            listOf("保存配置失败：" + (it.message ?: "请稍后重试"))
        }
        if (errors.isEmpty()) {
            cfg = c
            configErrors = emptyList()
        }
        return errors
    }

    // ---- 内容库 ----

    @Synchronized
    fun items(): List<LibItem> = items.sortedByDescending { it.lastPlayedAt * 1_000L + it.addedAt }

    @Synchronized
    fun item(id: String): LibItem? = items.firstOrNull { it.id == id }

    /** 去重导入：同 kind + 同目标复用一个 id，重复导入只更新标题、不新增条目。 */
    @Synchronized
    fun upsert(item: LibItem): LibItem {
        val stableId = item.id.ifBlank { stableId(item) }
        val existing = items.firstOrNull { it.id == stableId }
        return if (existing == null) {
            val fresh = item.copy(id = stableId, addedAt = if (item.addedAt > 0) item.addedAt else System.currentTimeMillis())
            items += fresh
            persist()
            fresh
        } else {
            val merged = existing.copy(
                title = item.title.ifBlank { existing.title },
                subtitle = item.subtitle.ifBlank { existing.subtitle },
                bvids = if (item.bvids.isNotEmpty()) item.bvids else existing.bvids,
                favorite = existing.favorite
            )
            val idx = items.indexOfFirst { it.id == stableId }
            items[idx] = merged
            persist()
            merged
        }
    }

    @Synchronized
    fun removeItem(id: String) {
        items.removeAll { it.id == id }
        schedules.removeAll { it.itemId == id }
        persist()
    }

    @Synchronized
    fun setFavorite(id: String, favorite: Boolean) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(favorite = favorite)
            persist()
        }
    }

    @Synchronized
    fun touchPlayed(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(lastPlayedAt = System.currentTimeMillis())
            persist()
        }
    }

    // ---- 节目单 ----

    @Synchronized
    fun schedules(): List<ScheduleItem> = schedules.sortedWith(compareBy({ it.hour }, { it.minute }))

    @Synchronized
    fun addSchedule(s: ScheduleItem) {
        schedules += s.copy(id = s.id.ifBlank { UUID.randomUUID().toString().take(8) })
        persist()
    }

    @Synchronized
    fun removeSchedule(id: String) {
        schedules.removeAll { it.id == id }
        persist()
    }

    @Synchronized
    fun setScheduleEnabled(id: String, enabled: Boolean) {
        val idx = schedules.indexOfFirst { it.id == id }
        if (idx >= 0) {
            schedules[idx] = schedules[idx].copy(enabled = enabled)
            persist()
        }
    }

    /** 调整节目单内内容的播放顺序（itemId 同步为首项，兼容旧字段）。 */
    @Synchronized
    fun setScheduleItems(id: String, itemIds: List<String>) {
        val idx = schedules.indexOfFirst { it.id == id }
        if (idx < 0 || itemIds.isEmpty()) return
        schedules[idx] = schedules[idx].copy(itemId = itemIds.first(), itemIds = itemIds)
        persist()
    }

    @Synchronized
    fun markScheduleRun(id: String, runKey: String) {
        val idx = schedules.indexOfFirst { it.id == id }
        if (idx >= 0) {
            schedules[idx] = schedules[idx].copy(lastRunKey = runKey)
            persist()
        }
    }

    // ---- 监控 ----

    @Synchronized
    fun monitors(): List<MonitorItem> = monitors.sortedByDescending { it.priority }

    @Synchronized
    fun addMonitor(m: MonitorItem) {
        val idx = monitors.indexOfFirst { it.roomId == m.roomId }
        if (idx >= 0) monitors[idx] = m else monitors += m
        persist()
    }

    @Synchronized
    fun updateMonitor(m: MonitorItem) {
        val idx = monitors.indexOfFirst { it.roomId == m.roomId }
        if (idx >= 0) {
            monitors[idx] = m
            persist()
        }
    }

    @Synchronized
    fun removeMonitor(roomId: Long) {
        monitors.removeAll { it.roomId == roomId }
        persist()
    }

    @Synchronized
    fun setMonitorSwitch(enabled: Boolean) {
        monitorEnabled = enabled
        persist()
    }

    @Synchronized
    fun setPollSeconds(seconds: Int) {
        pollSeconds = seconds.coerceIn(20, 600)
        persist()
    }

    // ---- 执行记录 ----

    @Synchronized
    fun runs(): List<RunRecord> = runs.takeLast(60).reversed()

    @Synchronized
    fun log(kind: String, text: String) {
        runs += RunRecord(System.currentTimeMillis(), kind, text)
        if (runs.size > 200) runs = runs.takeLast(200).toMutableList()
        persist()
        runCatching { logFile.appendText("[" + System.currentTimeMillis() + "] " + kind + ": " + text + "\n") }
    }

    // ---- 诊断事件 ----

    @Synchronized
    fun trace(
        area: String,
        action: String,
        status: TraceStatus,
        summary: String,
        details: String = "",
        elapsedMs: Long? = null
    ) {
        val event = TraceEvent(
            atMs = System.currentTimeMillis(),
            area = area,
            action = action,
            status = status,
            summary = summary,
            details = details.take(4_000),
            elapsedMs = elapsedMs
        )
        traces += event
        if (traces.size > 300) traces = traces.takeLast(300).toMutableList()
        persistTraces()
        val elapsed = elapsedMs?.let { "，耗时 ${it}ms" }.orEmpty()
        runCatching {
            logFile.appendText(
                "[" + event.atMs + "] " + area + " / " + action + " [" + status.label + "] " +
                    summary + elapsed + if (details.isBlank()) "\n" else "\n  " + details.replace("\n", "\n  ") + "\n"
            )
        }
    }

    @Synchronized
    fun traces(limit: Int = 200): List<TraceEvent> = traces.takeLast(limit.coerceAtLeast(1)).reversed()

    @Synchronized
    fun clearDiagnostics() {
        traces.clear()
        runs.clear()
        persistTraces()
        persist()
        runCatching { logFile.writeText("") }
    }

    private fun stableId(item: LibItem): String = when (item.kind) {
        ItemKind.LIVE -> "live-" + (item.roomId ?: 0)
        ItemKind.SERIES -> "series-" + (item.listId ?: 0) + "-" + (item.mid ?: 0)
        ItemKind.VIDEO -> "video-" + (item.bvid ?: item.sourceUrl.hashCode().toString())
    }

    private fun <R> JSONArray.toList(map: (JSONObject) -> R): List<R> =
        (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(map) }
}
