package tvrc.core.bili

/**
 * 用户输入 → 结构化内容源。口径对齐二代 control.py 的 parse_input（F-09）：
 * 支持：直播间号 / live.bilibili.com 链接 / BV 号 / space 合集与列表 / UP 主页 / b23.tv 短链（只识别）。
 *
 * TODO(P2-3)：本层只做「识别入口」；series vs season vs UP 的**判型**必须调 B 站接口验证
 * （series/archives 与 seasons_archives_list，K 裁决 F-16：不许正则猜），见 L-编程清单-纯软件范围.md P2-3。
 */
sealed interface Source {

    /** 直播间（房间号或 live 链接）。 */
    data class LiveRoom(val roomId: Long) : Source

    /** 单个视频（BV 号）。识别后由上层引导（H 方案：第二版展开）。 */
    data class Video(val bvid: String) : Source

    /** space.bilibili.com/{mid}/lists/{listId}。 */
    data class Series(val mid: Long, val listId: Long) : Source

    /** space.bilibili.com/{mid}/channel/collectiondetail?sid=...。 */
    data class Season(val mid: Long, val seasonId: Long) : Source

    /** space.bilibili.com/{mid}（UP 主页）。 */
    data class Up(val mid: Long) : Source

    /** b23.tv 短链：只识别不展开，展开需要网络请求（S2 再做）。 */
    data class ShortLink(val url: String) : Source

    object Unknown : Source
}

object SourceParser {

    private val LIVE_URL = Regex("live\\.bilibili\\.com/(?:blanc/)?(\\d+)")
    private val SERIES_URL = Regex("space\\.bilibili\\.com/(\\d+)/lists/(\\d+)")
    private val SEASON_URL = Regex("space\\.bilibili\\.com/(\\d+)/channel/collectiondetail\\?[^#]*\\bsid=(\\d+)")
    private val VIDEO_REF = Regex("(?:bilibili\\.com/video/|^)(BV[0-9A-Za-z]{8,})")
    private val SPACE_URL = Regex("space\\.bilibili\\.com/(\\d+)")
    private val PURE_DIGITS = Regex("\\d{1,12}")
    private val SHORT_URL = Regex("https?://b23\\.tv/\\S+")

    fun parse(raw: String?): Source {
        val text = (raw ?: "").trim().replace("\u200b", "").trim()
        if (text.isEmpty()) return Source.Unknown

        if (text.contains("b23.tv")) {
            SHORT_URL.find(text)?.let { return Source.ShortLink(it.value) }
        }
        LIVE_URL.find(text)?.let { return Source.LiveRoom(it.groupValues[1].toLong()) }
        SERIES_URL.find(text)?.let {
            return Source.Series(it.groupValues[1].toLong(), it.groupValues[2].toLong())
        }
        SEASON_URL.find(text)?.let {
            return Source.Season(it.groupValues[1].toLong(), it.groupValues[2].toLong())
        }
        VIDEO_REF.find(text)?.let { return Source.Video(it.groupValues[1]) }
        SPACE_URL.find(text)?.let { return Source.Up(it.groupValues[1].toLong()) }
        if (PURE_DIGITS.matches(text)) return Source.LiveRoom(text.toLong())
        return Source.Unknown
    }
}
