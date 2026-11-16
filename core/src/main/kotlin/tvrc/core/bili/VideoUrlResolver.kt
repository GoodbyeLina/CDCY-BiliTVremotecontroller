package tvrc.core.bili

import tvrc.core.json.Json
import tvrc.core.json.arrOrNull
import tvrc.core.json.field
import tvrc.core.json.longOrNull
import tvrc.core.json.strOrNull
import java.io.IOException

/**
 * BV 号 → 可播放的 MP4 直链（FR-09 点播链路的第一段）。
 *
 * 两步：/x/web-interface/view 取 cid，再 /x/player/playurl?qn=64&fnval=1 取 durl[0].url。
 * 只处理 durl（MP4）形态；DASH（fnval 位掩码）留给后续，避免一次做两套。
 * 解析不出来一律抛 IOException，让上层转成 502 与 E-BILI-xx 文案，不静默返回空串。
 */
interface VideoUrlResolver {
    @Throws(IOException::class)
    fun resolve(bvid: String): String
}

class RealVideoUrlResolver(
    private val http: HttpText,
    private val timeoutMs: Int = 15_000
) : VideoUrlResolver {

    override fun resolve(bvid: String): String {
        val viewUrl = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid
        val view = Json.parse(http.get(viewUrl, HEADERS, timeoutMs))
        val cid = view.field("data")?.field("cid")?.longOrNull()
            ?: throw IOException("view 接口没有 cid: " + bvid)
        val playUrl = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid +
            "&cid=" + cid + "&qn=64&fnval=1"
        val play = Json.parse(http.get(playUrl, HEADERS, timeoutMs))
        val durl = play.field("data")?.field("durl")?.arrOrNull()
        val url = durl?.firstOrNull()?.field("url")?.strOrNull()
        if (url.isNullOrBlank()) throw IOException("playurl 没有 durl 直链: " + bvid)
        return url
    }

    private companion object {
        val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Referer" to "https://www.bilibili.com/"
        )
    }
}
