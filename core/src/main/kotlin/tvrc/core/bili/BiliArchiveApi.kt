package tvrc.core.bili

import tvrc.core.Mode
import tvrc.core.Session
import tvrc.core.json.Json
import tvrc.core.json.JsonValue
import tvrc.core.json.arrOrNull
import tvrc.core.json.field
import tvrc.core.json.longOrNull
import tvrc.core.json.strOrNull
import java.io.IOException

/** 合集/系列里的单个稿件。 */
data class ArchiveItem(
    val bvid: String,
    val title: String,
    val cover: String,
    val durationSec: Long,
    val pubdate: Long
)

data class ArchivePage(val items: List<ArchiveItem>, val total: Int)

/**
 * 系列 / 合集稿件列表（L P2-2）。两个端点口径不同但返回结构接近：
 * - 系列（用户自建 series）：/x/series/archives?mid=&series_id=&only_normal=true&sort=desc&pn=&ps=
 *   → data.archives[] + data.page.total
 * - 合集（新版 season）：/x/polymer/web-space/seasons_archives_list?mid=&season_id=&sort_reverse=false&page_num=&page_size=
 *   → data.archives[] + data.page.total
 */
interface BiliArchiveApi {
    @Throws(IOException::class)
    fun seriesPage(mid: Long, seriesId: Long, pageNum: Int, pageSize: Int): ArchivePage

    @Throws(IOException::class)
    fun seasonPage(mid: Long, seasonId: Long, pageNum: Int, pageSize: Int): ArchivePage

    /** 自动翻页拼装，直到取满 total 或遇到空页（maxPages 兜底防死循环）。 */
    @Throws(IOException::class)
    fun seriesAll(mid: Long, seriesId: Long, pageSize: Int = 50, maxPages: Int = 50): List<ArchiveItem>

    @Throws(IOException::class)
    fun seasonAll(mid: Long, seasonId: Long, pageSize: Int = 50, maxPages: Int = 50): List<ArchiveItem>
}

class RealBiliArchiveApi(
    private val http: HttpText,
    private val timeoutMs: Int = 15_000
) : BiliArchiveApi {

    override fun seriesPage(mid: Long, seriesId: Long, pageNum: Int, pageSize: Int): ArchivePage {
        val url = "https://api.bilibili.com/x/series/archives?mid=" + mid +
            "&series_id=" + seriesId +
            "&only_normal=true&sort=desc&pn=" + pageNum + "&ps=" + pageSize
        return parsePage(http.get(url, HEADERS, timeoutMs), url)
    }

    override fun seasonPage(mid: Long, seasonId: Long, pageNum: Int, pageSize: Int): ArchivePage {
        val url = "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list?mid=" + mid +
            "&season_id=" + seasonId +
            "&sort_reverse=false&page_num=" + pageNum + "&page_size=" + pageSize
        return parsePage(http.get(url, HEADERS, timeoutMs), url)
    }

    override fun seriesAll(mid: Long, seriesId: Long, pageSize: Int, maxPages: Int): List<ArchiveItem> =
        collect(pageSize, maxPages) { pn, ps -> seriesPage(mid, seriesId, pn, ps) }

    override fun seasonAll(mid: Long, seasonId: Long, pageSize: Int, maxPages: Int): List<ArchiveItem> =
        collect(pageSize, maxPages) { pn, ps -> seasonPage(mid, seasonId, pn, ps) }

    private fun collect(
        pageSize: Int,
        maxPages: Int,
        fetch: (Int, Int) -> ArchivePage
    ): List<ArchiveItem> {
        val all = ArrayList<ArchiveItem>()
        var page = 1
        while (page <= maxPages) {
            val result = fetch(page, pageSize)
            if (result.items.isEmpty()) break
            all.addAll(result.items)
            if (all.size >= result.total) break
            page++
        }
        return all
    }

    private fun parsePage(body: String, url: String): ArchivePage {
        val root = try {
            Json.parse(body) as? JsonValue.Obj
        } catch (e: RuntimeException) {
            throw BiliApiException("bad json from " + url + ": " + (e.message ?: ""))
        } ?: throw BiliApiException("expected object from " + url)
        val code = root.field("code")?.longOrNull()
        if (code != 0L) throw BiliApiException("archives code=" + code)

        val data = root.field("data") as? JsonValue.Obj
            ?: throw BiliApiException("archives missing data")
        val archives = data.field("archives")?.arrOrNull().orEmpty()
        val items = archives.mapNotNull { it.toItem() }
        val total = data.field("page")?.field("total")?.longOrNull()?.toInt()
            ?: data.field("page")?.field("count")?.longOrNull()?.toInt()
            ?: items.size
        return ArchivePage(items, total)
    }

    private fun JsonValue.toItem(): ArchiveItem? {
        val bvid = field("bvid")?.strOrNull() ?: return null
        return ArchiveItem(
            bvid = bvid,
            title = field("title")?.strOrNull() ?: "",
            cover = field("cover")?.strOrNull() ?: field("pic")?.strOrNull() ?: "",
            durationSec = field("duration")?.longOrNull() ?: 0L,
            pubdate = field("pubdate")?.longOrNull() ?: field("ctime")?.longOrNull() ?: 0L
        )
    }

    companion object {
        val HEADERS: Map<String, String> = mapOf(
            "User-Agent" to RealBiliLiveApi.UA,
            "Referer" to "https://space.bilibili.com/"
        )
    }
}

/** 单集直链组装（L P2-2 的「单集直链组装」）：统一指向本机代理 8100 的 /video/<bvid>。 */
object VideoTargets {
    fun target(bvid: String, publicHost: String, port: Int): String =
        "http://" + publicHost + ":" + port + "/video?bvid=" + bvid
}

/** 合集/系列 → 轮播 Session（按列表顺序播）。 */
fun LiveSessions.videoSession(
    items: List<ArchiveItem>,
    publicHost: String,
    port: Int,
    switchSeconds: Long
): Session = Session(
    videos = items.map { VideoTargets.target(it.bvid, publicHost, port) },
    switchSeconds = switchSeconds,
    mode = Mode.VIDEO
)
