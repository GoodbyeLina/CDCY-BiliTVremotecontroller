package tvrc.core.config

import tvrc.core.json.Json
import tvrc.core.json.JsonValue
import tvrc.core.json.arrOrNull
import tvrc.core.json.field
import tvrc.core.json.longOrNull
import tvrc.core.json.strOrNull
import tvrc.core.json.write

/**
 * 三代 App 的完整配置。字段口径对齐 H 方案 5.x / K 文档 §11（二代 config.example.json 15 键）。
 *
 * 关键默认值裁决：
 * - switchSeconds 默认 1800（FR-05：60–86400，默认 30 分钟）。二代现场用 30 秒，
 *   由 P3-2 迁移照抄文件里的值，不改默认值本身。
 * - port 默认 8100，避让二代 8099。
 * - idleTimeoutMinutes = 0 表示不自动停止（FR-10 需显式开启）。
 */
data class AppConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val source: String = "live",
    val rooms: List<Long> = emptyList(),
    val listMid: Long? = null,
    val listId: Long? = null,
    val pickCount: Int = 5,
    val switchSeconds: Int = 1800,
    val statusSeconds: Int = 10,
    val port: Int = 8100,
    val tvHost: String = "",
    val tvPort: Int = 5555,
    val publicHost: String = "",
    val mpvPkg: String = "is.xyz.mpv",
    val mpvActivity: String = "is.xyz.mpv/.MPVActivity",
    val wizardCompleted: Boolean = false,
    val idleTimeoutMinutes: Int = 0,
    /** 直播画质 qn：80 流畅 / 150 高清 / 250 超清 / 400 蓝光 / 10000 原画。
     *  默认 250：手机中转要「收一遍再发一遍」，原画在热门房间容易把 Wi-Fi 打满。 */
    val liveQn: Int = DEFAULT_LIVE_QN,
    /** 未知键原样保留（二代 config.json 里可能有 control_port / setup_seen 等）。 */
    val extra: Map<String, JsonValue> = emptyMap()
) {
    val tvAddress: String get() = if (tvPort == 5555) tvHost else "${tvHost}:${tvPort}"

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        /** 下限取 30 而非常文档的 60：二代现场（config.example.json）就是 switch_seconds=30
         *  且已长时间验证稳定；三代要「配置直接用」，就必须能承载这个值。
         *  默认仍是 1800（FR-05），60–86400 作为推荐区间写进向导提示。 */
        const val MIN_SWITCH_SECONDS = 30
        const val MAX_SWITCH_SECONDS = 86_400
        const val MIN_PICK_COUNT = 1
        const val MAX_PICK_COUNT = 50
        const val MIN_STATUS_SECONDS = 3
        const val MAX_STATUS_SECONDS = 600
        const val MAX_IDLE_MINUTES = 24 * 60
        const val DEFAULT_LIVE_QN = 250
        /** B站直播可选清晰度；不在这个集合里的值会让配置校验失败。 */
        val LIVE_QN_CHOICES = setOf(80, 150, 250, 400, 10_000)
    }
}

/** 校验结果：errors 为空才合法。 */
data class ConfigCheck(val config: AppConfig, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

object ConfigValidator {

    fun check(c: AppConfig): ConfigCheck = check(c, requireContent = true)

    /** 设备设置可先于内容库保存；实际播放的内容由 Android 端另行选择。 */
    fun checkDeviceSettings(c: AppConfig): ConfigCheck = check(c, requireContent = false)

    private fun check(c: AppConfig, requireContent: Boolean): ConfigCheck {
        val e = mutableListOf<String>()
        if (c.schemaVersion != AppConfig.CURRENT_SCHEMA_VERSION) {
            e += "schemaVersion=@${c.schemaVersion} 不受支持（当前 @${AppConfig.CURRENT_SCHEMA_VERSION}）"
        }
        if (c.source !in setOf("live", "video", "series", "season", "up")) {
            e += "source=@${c.source} 不认识（可选 live/video/series/season/up）"
        }
        if (requireContent && c.source == "live" && c.rooms.isEmpty()) e += "source=live 时 rooms 不能为空"
        if (requireContent && c.source == "series" && (c.listMid == null || c.listId == null)) {
            e += "source=series 需要 list_mid 与 list_id"
        }
        if (c.switchSeconds !in AppConfig.MIN_SWITCH_SECONDS..AppConfig.MAX_SWITCH_SECONDS) {
            e += "switch_seconds=@${c.switchSeconds} 超范围（@${AppConfig.MIN_SWITCH_SECONDS}–@${AppConfig.MAX_SWITCH_SECONDS}）"
        }
        if (c.pickCount !in AppConfig.MIN_PICK_COUNT..AppConfig.MAX_PICK_COUNT) {
            e += "pick_count=@${c.pickCount} 超范围（@${AppConfig.MIN_PICK_COUNT}–@${AppConfig.MAX_PICK_COUNT}）"
        }
        if (c.statusSeconds !in AppConfig.MIN_STATUS_SECONDS..AppConfig.MAX_STATUS_SECONDS) {
            e += "status_seconds=@${c.statusSeconds} 超范围（@${AppConfig.MIN_STATUS_SECONDS}–@${AppConfig.MAX_STATUS_SECONDS}）"
        }
        if (c.port !in 1024..65535) e += "port=@${c.port} 不合法（1024–65535）"
        if (c.port == 8099) e += "port=8099 是二代端口，三代请用 8100 起"
        if (c.tvPort !in 1..65535) e += "tv 端口 @${c.tvPort} 不合法"
        if (c.tvHost.isBlank()) e += "tv 地址为空（首次配置由向导写入）"
        if (c.idleTimeoutMinutes !in 0..AppConfig.MAX_IDLE_MINUTES) {
            e += "idle_timeout_minutes=@${c.idleTimeoutMinutes} 超范围（0–@${AppConfig.MAX_IDLE_MINUTES}，0=不自动停）"
        }
        if (c.liveQn !in AppConfig.LIVE_QN_CHOICES) {
            e += "live_qn=@${c.liveQn} 不合法（可选 80/150/250/400/10000）"
        }
        return ConfigCheck(c, e)
    }
}

/** 文件读写抽象，测试用内存实现，真机用 FsConfigStore。 */
interface ConfigStore {
    fun readText(path: String): String?
    fun writeText(path: String, text: String)
}

class FsConfigStore : ConfigStore {
    override fun readText(path: String): String? {
        val f = java.io.File(path)
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    override fun writeText(path: String, text: String) {
        val f = java.io.File(path)
        f.parentFile?.mkdirs()
        val tmp = java.io.File(f.absolutePath + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}

sealed interface LoadResult {
    data class Ok(val config: AppConfig, val check: ConfigCheck) : LoadResult
    data class Missing(val path: String) : LoadResult
    data class Invalid(val path: String, val reason: String) : LoadResult
}

/**
 * JSON 配置仓库。文件格式 = 二代 config.json 的扁平 snake_case 结构；
 * 未知键原样重写，保证二代→三代迁移不丢字段。
 */
class JsonConfigRepository(
    private val store: ConfigStore,
    private val path: String,
    private val validate: (AppConfig) -> ConfigCheck = ConfigValidator::check
) {

    fun load(): LoadResult {
        val text = store.readText(path) ?: return LoadResult.Missing(path)
        val parsed = try {
            Json.parse(text)
        } catch (e: Exception) {
            return LoadResult.Invalid(path, "不是合法 JSON：" + e.message)
        }
        val obj = parsed as? JsonValue.Obj ?: return LoadResult.Invalid(path, "顶层不是对象")
        val known = setOf(
            "schemaVersion", "schema_version", "source", "rooms", "list_mid", "list_id",
            "pick_count", "switch_seconds", "status_seconds", "port", "tv", "public_host",
            "mpv_pkg", "mpv_activity", "wizard_completed", "idle_timeout_minutes", "live_qn"
        )
        val extra = obj.fields.filterKeys { it !in known }
        val tv = obj.field("tv").strOrNull() ?: ""
        val hp = parseHostPort(tv, 5555)
        val seen = obj.field("setup_seen")
        val cfg = AppConfig(
            schemaVersion = (obj.field("schemaVersion").longOrNull()
                ?: obj.field("schema_version").longOrNull() ?: 1L).toInt(),
            source = obj.field("source").strOrNull() ?: "live",
            rooms = obj.field("rooms").arrOrNull()?.mapNotNull { it.longOrNull() } ?: emptyList(),
            listMid = obj.field("list_mid").longOrNull(),
            listId = obj.field("list_id").longOrNull(),
            pickCount = (obj.field("pick_count").longOrNull() ?: 5L).toInt(),
            switchSeconds = (obj.field("switch_seconds").longOrNull() ?: 1800L).toInt(),
            statusSeconds = (obj.field("status_seconds").longOrNull() ?: 10L).toInt(),
            port = (obj.field("port").longOrNull() ?: 8100L).toInt(),
            tvHost = hp.first,
            tvPort = hp.second,
            publicHost = obj.field("public_host").strOrNull() ?: "",
            mpvPkg = obj.field("mpv_pkg").strOrNull() ?: "is.xyz.mpv",
            mpvActivity = obj.field("mpv_activity").strOrNull() ?: "is.xyz.mpv/.MPVActivity",
            wizardCompleted = when {
                obj.field("wizard_completed") is JsonValue.Bool ->
                    (obj.field("wizard_completed") as JsonValue.Bool).value
                seen is JsonValue.Bool -> seen.value
                else -> false
            },
            idleTimeoutMinutes = (obj.field("idle_timeout_minutes").longOrNull() ?: 0L).toInt(),
            liveQn = (obj.field("live_qn").longOrNull() ?: AppConfig.DEFAULT_LIVE_QN.toLong()).toInt(),
            extra = extra
        )
        return LoadResult.Ok(cfg, validate(cfg))
    }

    /**
     * 降级读：文件缺失/损坏/非法都返回默认配置 + 原因，App 用它在 UI 上显示「配置有问题，进入向导」。
     * 绝不因配置坏而崩溃（NFR：装完点开始就能看，配置错误要能看到原因）。
     */
    fun loadOrDefaults(): Pair<AppConfig, List<String>> {
        return when (val r = load()) {
            is LoadResult.Ok -> r.config to r.check.errors
            is LoadResult.Missing -> AppConfig() to listOf("配置不存在（" + r.path + "），已用默认值")
            is LoadResult.Invalid -> AppConfig() to listOf("配置无法解析（" + r.path + "）：" + r.reason)
        }
    }

    /** 保存；非法配置不落盘，返回错误列表。 */
    fun save(c: AppConfig): List<String> {
        val chk = validate(c)
        if (!chk.ok) return chk.errors
        store.writeText(path, toJson(c))
        return emptyList()
    }

    companion object {

        fun parseHostPort(tv: String, defaultPort: Int): Pair<String, Int> {
            val t = tv.trim()
            if (t.isEmpty()) return "" to defaultPort
            val i = t.lastIndexOf(':')
            if (i <= 0 || i == t.length - 1) return t to defaultPort
            val host = t.substring(0, i)
            val port = t.substring(i + 1).toIntOrNull() ?: return host to defaultPort
            return host to port
        }

        fun toJson(c: AppConfig): String {
            val sb = StringBuilder()
            sb.append("{\n")
            fun kv(name: String, raw: String) {
                sb.append("  \"").append(name).append("\": ").append(raw).append(",\n")
            }
            kv("schemaVersion", c.schemaVersion.toString())
            kv("source", "\"" + c.source + "\"")
            kv("rooms", c.rooms.joinToString(prefix = "[", postfix = "]"))
            c.listMid?.let { kv("list_mid", it.toString()) }
            c.listId?.let { kv("list_id", it.toString()) }
            kv("pick_count", c.pickCount.toString())
            kv("switch_seconds", c.switchSeconds.toString())
            kv("status_seconds", c.statusSeconds.toString())
            kv("port", c.port.toString())
            kv("tv", "\"" + c.tvAddress + "\"")
            kv("public_host", "\"" + c.publicHost + "\"")
            kv("mpv_pkg", "\"" + c.mpvPkg + "\"")
            kv("mpv_activity", "\"" + c.mpvActivity + "\"")
            kv("wizard_completed", c.wizardCompleted.toString())
            kv("idle_timeout_minutes", c.idleTimeoutMinutes.toString())
            kv("live_qn", c.liveQn.toString())
            for (entry in c.extra) kv(entry.key, entry.value.write())
            if (sb.endsWith(",\n")) sb.setLength(sb.length - 2)
            sb.append("\n}\n")
            return sb.toString()
        }
    }
}
