package tvrc.core.bili

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 跳转跟随层（H 方案 R-01「短链展开」）：b23.tv 短链必须发一次请求读 Location 才知道真实地址。
 * 抽成接口是为了单测零网络，也便于以后换 DNS/代理。
 */
fun interface RedirectFollower {
    @Throws(IOException::class)
    fun follow(url: String, timeoutMs: Int): String
}

/**
 * 真实实现：逐跳手动跟随，最多 [maxHops] 跳。
 * 不用 HttpURLConnection 的自动跟随 —— 我们要记录每一跳（诊断要能看到短链到底跳到哪），
 * 也要在异常跳转（非 b 站域）时及时停手，避免循环。
 */
class UrlConnectionRedirectFollower(
    private val maxHops: Int = 5,
    private val userAgent: String = RealBiliLiveApi.UA
) : RedirectFollower {

    override fun follow(url: String, timeoutMs: Int): String {
        var current = url
        var hop = 0
        while (hop < maxHops) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            try {
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = absolute(current, location)
                    hop++
                    continue
                }
                return current
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    private fun absolute(base: String, location: String): String =
        try {
            URL(URL(base), location).toString()
        } catch (e: IOException) {
            location
        }
}

/**
 * 分享文案提取（R-01「处理带参数网址和整段分享文案」）。
 * B站手机端分享出来的是一段话：「【标题】 https://b23.tv/xxxx」或带 emoji/换行，
 * 所以先抠 URL，再交给 [SourceParser]。
 */
object ShareText {

    private val URL_PATTERN = Regex("""https?://[^\s'"<>\u4e00-\u9fa5，。；、）】」』]+""")

    fun extractUrls(raw: String?): List<String> {
        val text = (raw ?: "").replace("\u200b", "")
        if (text.isBlank()) return emptyList()
        return URL_PATTERN.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '>') }.toList()
    }

    /** 分享文案里挑最像 B站入口的那个链接；没有就返回 null。 */
    fun pickBiliUrl(raw: String?): String? {
        val urls = extractUrls(raw)
        if (urls.isEmpty()) return null
        return urls.firstOrNull {
            it.contains("bilibili.com") || it.contains("b23.tv") || it.contains("bili2233")
        } ?: urls.first()
    }
}

/** 一次展开的完整证据：识别结果 + 实际跳转链 + 给人看的一句话。 */
data class ExpandResult(
    val source: Source,
    val hops: List<String>,
    val note: String
) {
    val isShortLinkUnresolved: Boolean get() = source is Source.ShortLink
}

/**
 * 入口统一层（R-01/R-02）：分享文案 → 抠链接 → 若是 b23.tv 短链则展开 → 重新识别。
 *
 * 设计裁决：
 * - 只在**识别结果为 ShortLink** 时才发网络请求，普通网址/CV号/房间号零网络；
 * - 展开失败不抛异常，返回原 ShortLink + 失败原因，UI 才能提示「换成网页地址再试」；
 * - 跳转链原样返回，写进诊断日志（现场排查「分享链接导入失败」第一眼看这个）。
 */
class SourceExpander(
    private val follower: RedirectFollower,
    private val timeoutMs: Int = 8_000,
    private val parser: (String) -> Source = { SourceParser.parse(it) }
) {

    fun expand(raw: String?): ExpandResult {
        val text = (raw ?: "").replace("\u200b", "").trim()
        if (text.isEmpty()) return ExpandResult(Source.Unknown, emptyList(), "输入为空")

        val candidate = ShareText.pickBiliUrl(text) ?: text
        var source = parser(candidate)
        val hops = ArrayList<String>()

        if (source is Source.ShortLink) {
            hops += source.url
            val finalUrl = try {
                follower.follow(source.url, timeoutMs)
            } catch (e: Exception) {
                return ExpandResult(
                    source,
                    hops,
                    "短链展开失败（" + (e.message ?: e.javaClass.simpleName) + "），请改用网页地址"
                )
            }
            if (finalUrl != source.url) {
                hops += finalUrl
                source = parser(finalUrl)
            }
            if (source is Source.ShortLink) {
                return ExpandResult(source, hops, "短链跳转后仍不是可识别地址：" + finalUrl)
            }
        }
        return ExpandResult(source, hops, describe(source))
    }

    private fun describe(source: Source): String = when (source) {
        is Source.LiveRoom -> "直播间 " + source.roomId
        is Source.Video -> "视频 " + source.bvid
        is Source.Series -> "合集 mid=" + source.mid + " id=" + source.listId
        is Source.Season -> "系列 mid=" + source.mid + " sid=" + source.seasonId
        is Source.Up -> "UP 主 " + source.mid
        is Source.ShortLink -> "短链 " + source.url
        Source.Unknown -> "无法识别"
    }
}
