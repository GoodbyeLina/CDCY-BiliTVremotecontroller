package tvrc.core.bili

import tvrc.core.Mode
import tvrc.core.MAX_SWITCH_SECONDS
import tvrc.core.Session
import tvrc.core.json.Json
import tvrc.core.json.JsonValue
import tvrc.core.json.arrOrNull
import tvrc.core.json.field
import tvrc.core.json.longOrNull
import tvrc.core.json.strOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 只做 GET 拿文本的可替换层，测试用假实现。 */
interface HttpText {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>, timeoutMs: Int): String
}

class UrlConnectionHttpText : HttpText {
    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IOException("HTTP " + code)
        } finally {
            conn.disconnect()
        }
    }
}

class BiliApiException(message: String) : IOException(message)

data class RoomInfo(val roomId: Long, val live: Boolean, val title: String, val online: Long)

data class FlvStream(val roomId: Long, val url: String, val host: String)

/**
 * 直播间信息与 FLV 直链。端点口径对齐二代 station.py：
 * - room/v1/Room/get_info?room_id=  → data.live_status(1=直播中)/title/online
 * - xlive/web-room/v2/index/getRoomPlayInfo?room_id=&protocol=0,1&format=0,1,2&codec=0,1&qn=10000&platform=web&ptype=8
 *   → data.playurl_info.playurl.stream[].format[].codec[]，取 http_stream/flv/avc 的 host+base_url+extra
 */
interface BiliLiveApi {
    @Throws(IOException::class)
    fun roomInfo(roomId: Long): RoomInfo

    @Throws(IOException::class)
    fun flvStream(roomId: Long): FlvStream
}

class RealBiliLiveApi(
    private val http: HttpText,
    private val timeoutMs: Int = 15_000
) : BiliLiveApi {

    override fun roomInfo(roomId: Long): RoomInfo {
        val url = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId
        val root = parseBody(url, http.get(url, HEADERS, timeoutMs))
        val code = root.field("code")?.longOrNull()
        if (code != 0L) {
            throw BiliApiException("get_info code=" + code)
        }
        val d = root.field("data") as? JsonValue.Obj
            ?: throw BiliApiException("get_info missing data")
        return RoomInfo(
            roomId = roomId,
            live = d.field("live_status")?.longOrNull() == 1L,
            title = d.field("title")?.strOrNull() ?: "",
            online = d.field("online")?.longOrNull() ?: 0L
        )
    }

    override fun flvStream(roomId: Long): FlvStream {
        val url = ("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo" +
            "?room_id=" + roomId +
            "&protocol=0,1&format=0,1,2&codec=0,1&qn=10000&platform=web&ptype=8")
        val root = parseBody(url, http.get(url, HEADERS, timeoutMs))
        val streams = root.field("data")?.field("playurl_info")?.field("playurl")
            ?.field("stream")?.arrOrNull()
            ?: throw BiliApiException("no stream for room " + roomId)
        for (s in streams) {
            val proto = s.field("protocol_name")?.strOrNull() ?: continue
            val formats = s.field("format")?.arrOrNull() ?: continue
            for (f in formats) {
                val fmtName = f.field("format_name")?.strOrNull() ?: continue
                val codecs = f.field("codec")?.arrOrNull() ?: continue
                for (c in codecs) {
                    val codecName = c.field("codec_name")?.strOrNull() ?: continue
                    if (proto != "http_stream" || fmtName != "flv" || codecName != "avc") continue
                    val ui = c.field("url_info")?.arrOrNull()?.firstOrNull() ?: continue
                    val host = ui.field("host")?.strOrNull() ?: continue
                    val base = c.field("base_url")?.strOrNull() ?: continue
                    val extra = ui.field("extra")?.strOrNull() ?: ""
                    return FlvStream(roomId, host + base + extra, host)
                }
            }
        }
        throw BiliApiException("no flv stream for room " + roomId)
    }

    private fun parseBody(url: String, body: String): JsonValue.Obj {
        val value = try {
            Json.parse(body)
        } catch (e: RuntimeException) {
            throw BiliApiException("bad json from " + url + ": " + (e.message ?: ""))
        }
        return value as? JsonValue.Obj ?: throw BiliApiException("expected object from " + url)
    }

    companion object {
        const val UA: String =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        val HEADERS: Map<String, String> = mapOf(
            "User-Agent" to UA,
            "Referer" to "https://live.bilibili.com/"
        )
    }
}

/** 直播源 → 状态机 Session。目标地址统一指向本机代理 8100。 */
object LiveSessions {

    fun liveSession(roomIds: List<Long>, publicHost: String, port: Int, switchSeconds: Long): Session =
        Session(
            rooms = roomIds.map { target(it, publicHost, port) },
            switchSeconds = switchSeconds,
            mode = Mode.LIVE
        )

    fun singleSession(roomId: Long, publicHost: String, port: Int): Session =
        Session(
            rooms = listOf(target(roomId, publicHost, port)),
            switchSeconds = MAX_SWITCH_SECONDS,
            mode = Mode.SINGLE
        )

    /** 电视要拉的地址：与二代一致用 query 形式 /live?room=<房间号>（见 MiniHttpServer 路由表）。 */
    private fun target(roomId: Long, host: String, port: Int): String =
        "http://" + host + ":" + port + "/live?room=" + roomId
}
