package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.Mode
import tvrc.core.bili.BiliApiException
import tvrc.core.bili.BiliLiveApi
import tvrc.core.bili.HttpText
import tvrc.core.bili.LiveSessions
import tvrc.core.bili.RealBiliLiveApi
import java.io.IOException

/** 假 HTTP：按 URL 回放固定响应，记录带过的 headers。 */
class FakeHttpText : HttpText {
    val responses = mutableMapOf<String, String>()
    val requestedUrls = mutableListOf<String>()
    val seenUserAgents = mutableSetOf<String>()

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): String {
        val key = url.substringBefore("?")
        requestedUrls.add(url)
        headers["User-Agent"]?.let { seenUserAgents.add(it) }
        return responses[key] ?: throw IOException("no canned response for " + url)
    }

    fun stub(urlBase: String, body: String) {
        responses[urlBase.substringBefore("?")] = body
    }
}

class BiliLiveApiTest {

    private fun api(): Pair<RealBiliLiveApi, FakeHttpText> {
        val http = FakeHttpText()
        return RealBiliLiveApi(http) to http
    }

    @Test
    fun roomInfoLive() {
        val (api, http) = api()
        http.stub(
            "https://api.live.bilibili.com/room/v1/Room/get_info",
            "{\"code\":0,\"data\":{\"live_status\":1,\"title\":\"晚间电台\",\"online\":1234}}"
        )
        val info = api.roomInfo(5017134L)
        assertTrue(info.live)
        assertEquals("晚间电台", info.title)
        assertEquals(1234L, info.online)
        assertEquals(5017134L, info.roomId)
    }

    @Test
    fun roomInfoNotLive() {
        val (api, http) = api()
        http.stub(
            "https://api.live.bilibili.com/room/v1/Room/get_info",
            "{\"code\":0,\"data\":{\"live_status\":0,\"title\":\"\",\"online\":0}}"
        )
        assertEquals(false, api.roomInfo(1L).live)
    }

    @Test
    fun roomInfoApiErrorThrows() {
        val (api, http) = api()
        http.stub(
            "https://api.live.bilibili.com/room/v1/Room/get_info",
            "{\"code\":6002001,\"message\":\"房间不存在\"}"
        )
        assertThrows(BiliApiException::class.java) { api.roomInfo(9L) }
    }

    @Test
    fun flvStreamPicksHttpFlvAvcAndSkipsOthers() {
        val (api, http) = api()
        http.stub(
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo",
            """
            {"code":0,"data":{"playurl_info":{"playurl":{"stream":[
              {"protocol_name":"http_hls","format":[
                {"format_name":"fmp4","codec":[
                  {"codec_name":"avc","base_url":"/hls/index.m3u8","url_info":[{"host":"https://h.example.cn","extra":""}]}
                ]}
              ]},
              {"protocol_name":"http_stream","format":[
                {"format_name":"flv","codec":[
                  {"codec_name":"hev","base_url":"/dir/hev.flv","url_info":[{"host":"https://xy.example.cn","extra":"?x=1"}]},
                  {"codec_name":"avc","base_url":"/dir/stream.flv","url_info":[{"host":"https://xy.example.cn","extra":"?expires=1"}]}
                ]}
              ]}
            ]}}}}
            """.trimIndent()
        )
        val s = api.flvStream(5017134L)
        assertEquals("https://xy.example.cn/dir/stream.flv?expires=1", s.url)
        assertEquals("https://xy.example.cn", s.host)
    }

    @Test
    fun flvStreamNoMatchThrows() {
        val (api, http) = api()
        http.stub(
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo",
            "{\"code\":0,\"data\":{\"playurl_info\":{\"playurl\":{\"stream\":[]}}}}"
        )
        val ex = assertThrows(BiliApiException::class.java) { api.flvStream(5L) }
        assertTrue(ex.message?.contains("no flv stream") == true)
    }

    @Test
    fun badJsonThrows() {
        val (api, http) = api()
        http.stub("https://api.live.bilibili.com/room/v1/Room/get_info", "<html>blocked</html>")
        assertThrows(BiliApiException::class.java) { api.roomInfo(3L) }
    }

    @Test
    fun liveSessionTargetsProxyUrls() {
        val session = LiveSessions.liveSession(
            listOf(5017134L, 5064126L), "192.168.10.241", 8100, 1800L
        )
        assertEquals(
            listOf(
                "http://192.168.10.241:8100/live?room=5017134",
                "http://192.168.10.241:8100/live?room=5064126"
            ),
            session.rooms
        )
        assertEquals(Mode.LIVE, session.mode)
        assertTrue(session.isValid())
    }

    @Test
    fun singleSessionIsSingleModeOneRoom() {
        val session = LiveSessions.singleSession(5017134L, "192.168.10.241", 8100)
        assertEquals(1, session.rooms.size)
        assertEquals(Mode.SINGLE, session.mode)
        assertEquals(5017134L.toString(), session.target(0).substringAfterLast("room="))
        assertEquals(0, session.nextIndex(0), "单房间模式换台回到自己")
    }
}
