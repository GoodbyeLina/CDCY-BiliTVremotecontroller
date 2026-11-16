package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.http.MiniHttpServer
import java.net.Socket
import java.net.URLEncoder

/**
 * L1 真 socket 测试：客户端是真的 TCP 连接 + 真的 HTTP 报文，上游用 FakeUpstream 隔离外网。
 *
 * 路由口径按二代已跑通方案（station.py:341-380）：
 * - /live?room=<房间号>（别名 /live.flv），白名单外 400
 * - /video?bvid=<BV号>，格式/白名单不符 400
 * - /status、/api/status
 * - ?url= 仅 allowDirectUrl=true 的自测模式可用，生产默认 400
 */
class MiniHttpServerTest {

    private class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun rawRequest(port: Int, requestLine: String, headers: Map<String, String> = emptyMap()): Resp {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            val sb = StringBuilder()
            sb.append(requestLine).append("\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("\r\n")
            s.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()

            val all = s.getInputStream().readBytes()
            val sep = headEnd(all)
            val head = String(all, 0, sep, Charsets.ISO_8859_1)
            val body = all.copyOfRange(sep + 4, all.size)
            val lines = head.split("\r\n")
            val status = lines[0].split(" ")[1].toInt()
            val hs = LinkedHashMap<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) hs[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            return Resp(status, hs, body)
        }
    }

    private fun headEnd(bytes: ByteArray): Int {
        var i = 0
        while (i <= bytes.size - 4) {
            if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
                bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()
            ) return i
            i++
        }
        return bytes.size
    }

    private fun enc(url: String): String = URLEncoder.encode(url, "UTF-8")

    private fun <T> withServer(
        fetcher: FakeUpstream,
        mirrors: List<String> = emptyList(),
        rooms: Set<Long> = emptySet(),
        live: (Long) -> String? = { null },
        videos: Set<String> = emptySet(),
        video: (String) -> String? = { null },
        allowDirectUrl: Boolean = false,
        block: (Int) -> T
    ): T {
        MiniHttpServer(
            port = 0,
            fetcher = fetcher,
            mirrors = mirrors,
            liveRooms = { rooms },
            resolveLive = live,
            videoIds = { videos },
            resolveVideo = video,
            allowDirectUrl = allowDirectUrl
        ).use { srv -> return block(srv.start()) }
    }

    /** 1. /status 带 schemaVersion、capabilities、rooms（健康契约协商）。 */
    @Test
    fun statusReportsSchemaVersionAndCapabilities() {
        withServer(FakeUpstream(), rooms = setOf(5017134)) { port ->
            val r = rawRequest(port, "GET /status HTTP/1.1")
            assertEquals(200, r.status)
            val text = String(r.body, Charsets.UTF_8)
            assertTrue(text.contains("\"schemaVersion\":1"), text)
            assertTrue(text.contains("capabilities"), text)
            assertTrue(text.contains("live"), text)
            assertTrue(text.contains("5017134"), text)
            assertEquals("close", r.headers["connection"])
        }
    }

    /** 2. 二代老名字 /api/status 也必须是 200（电视自检脚本用的是它）。 */
    @Test
    fun apiStatusAliasWorks() {
        withServer(FakeUpstream()) { port ->
            val r = rawRequest(port, "GET /api/status HTTP/1.1")
            assertEquals(200, r.status)
            assertTrue(String(r.body, Charsets.UTF_8).contains("\"ok\":true"))
        }
    }

    /** 3. /live?room= 命中白名单 → 解析到上游；直播绝不带 Range、不走镜像（F-07 / F-08）。 */
    @Test
    fun liveByRoomForwardsWithoutRangeAndSkipsMirrors() {
        val up = FakeUpstream().route(
            "http://up/a.flv", 200, mapOf("content-type" to "video/x-flv"), "AAAA".toByteArray()
        )
        withServer(up, mirrors = listOf("127.0.0.1:9"), rooms = setOf(5017134), live = { "http://up/a.flv" }) { port ->
            val r = rawRequest(port, "GET /live?room=5017134 HTTP/1.1", mapOf("Range" to "bytes=0-1"))
            assertEquals(200, r.status)
            assertArrayEquals("AAAA".toByteArray(), r.body)
            assertEquals(1, up.seen.size, "直播不得走镜像")
            val sent = up.seen[0].second
            assertNull(sent.entries.firstOrNull { it.key.equals("Range", true) }, "直播不得带 Range")
            assertTrue(sent.keys.any { it.equals("Referer", true) })
            assertTrue(sent.keys.any { it.equals("User-Agent", true) })
        }
    }

    /** 4. 二代别名 /live.flv 同样可用。 */
    @Test
    fun liveFlvAliasWorks() {
        val up = FakeUpstream().route("http://up/a.flv", 200, mapOf("content-type" to "video/x-flv"), "F".toByteArray())
        withServer(up, rooms = setOf(42), live = { "http://up/a.flv" }) { port ->
            val r = rawRequest(port, "GET /live.flv?room=42 HTTP/1.1")
            assertEquals(200, r.status)
            assertArrayEquals("F".toByteArray(), r.body)
        }
    }

    /** 5. 房间不在白名单 → 400，且不产生任何上游请求（二代 station.py:355）。 */
    @Test
    fun liveRoomNotAllowedRejected() {
        val up = FakeUpstream()
        withServer(up, rooms = setOf(5017134), live = { "http://up/a.flv" }) { port ->
            val r = rawRequest(port, "GET /live?room=999999 HTTP/1.1")
            assertEquals(400, r.status)
            assertEquals(0, up.seen.size)
        }
    }

    /** 6. 缺 room / room 非数字 → 400。 */
    @Test
    fun liveMissingRoomRejected() {
        withServer(FakeUpstream(), rooms = setOf(1)) { port ->
            assertEquals(400, rawRequest(port, "GET /live HTTP/1.1").status)
            assertEquals(400, rawRequest(port, "GET /live?room=abc HTTP/1.1").status)
            assertEquals(400, rawRequest(port, "GET /live?room= HTTP/1.1").status)
        }
    }

    /** 7. /video?bvid= 透传 Range，206 原样返回。 */
    @Test
    fun videoByBvidPassesRangeThrough() {
        val up = FakeUpstream().route(
            "http://up/v.mp4", 206,
            mapOf("content-type" to "video/mp4", "content-range" to "bytes 0-3/100"),
            "ABCD".toByteArray()
        )
        withServer(up, videos = setOf("BV1xx411c7mD"), video = { "http://up/v.mp4" }) { port ->
            val r = rawRequest(port, "GET /video?bvid=BV1xx411c7mD HTTP/1.1", mapOf("Range" to "bytes=0-3"))
            assertEquals(206, r.status)
            assertEquals("bytes 0-3/100", r.headers["content-range"])
            assertArrayEquals("ABCD".toByteArray(), r.body)
            val sentRange = up.seen[0].second.entries.first { it.key.equals("Range", true) }.value
            assertEquals("bytes=0-3", sentRange)
        }
    }

    /** 8. bvid 不在当前列表 → 400（三代收紧点，二代只校验 BV 前缀）。 */
    @Test
    fun videoBvidNotInListRejected() {
        val up = FakeUpstream()
        withServer(up, videos = setOf("BV1xx411c7mD"), video = { "http://up/v.mp4" }) { port ->
            val r = rawRequest(port, "GET /video?bvid=BV1aaaaaaaaa HTTP/1.1")
            assertEquals(400, r.status)
            assertEquals(0, up.seen.size)
        }
    }

    /** 9. bvid 格式不对 → 400。 */
    @Test
    fun videoBadBvidRejected() {
        withServer(FakeUpstream(), video = { "http://up/v.mp4" }) { port ->
            assertEquals(400, rawRequest(port, "GET /video?bvid=abc HTTP/1.1").status)
            assertEquals(400, rawRequest(port, "GET /video HTTP/1.1").status)
            assertEquals(400, rawRequest(port, "GET /video?bvid=BV12 HTTP/1.1").status)
        }
    }

    /** 10. 上游无视 Range 返 200 全量 → 本地裁剪成 206（F-07 / S3）。 */
    @Test
    fun videoTrimsFullBodyToRequestedRange() {
        val full = ByteArray(100) { it.toByte() }
        val up = FakeUpstream().route("http://up/v.mp4", 200, mapOf("content-type" to "video/mp4"), full)
        withServer(up, video = { "http://up/v.mp4" }) { port ->
            val r = rawRequest(port, "GET /video?bvid=BV1xx411c7mD HTTP/1.1", mapOf("Range" to "bytes=10-19"))
            assertEquals(206, r.status)
            assertEquals("bytes 10-19/100", r.headers["content-range"])
            assertArrayEquals(full.copyOfRange(10, 20), r.body)
        }
    }

    /** 11. 256KB 大 body 逐块转发的完整性。 */
    @Test
    fun largeBodyStreamsIntact() {
        val big = ByteArray(256 * 1024) { ((it * 31) % 251).toByte() }
        val up = FakeUpstream().route("http://up/big.bin", 200, mapOf("content-type" to "application/octet-stream"), big)
        withServer(up, video = { "http://up/big.bin" }) { port ->
            val r = rawRequest(port, "GET /video?bvid=BV1xx411c7mD HTTP/1.1")
            assertEquals(200, r.status)
            assertArrayEquals(big, r.body)
        }
    }

    /** 12. /video 主源失败 → 镜像回退成功。 */
    @Test
    fun videoFallsBackToMirror() {
        val up = FakeUpstream().route(
            "http://127.0.0.1:2/v.mp4", 200, mapOf("content-type" to "video/mp4"), "MIRROR".toByteArray()
        )
        withServer(up, mirrors = listOf("127.0.0.1:2"), video = { "http://127.0.0.1:1/v.mp4" }) { port ->
            val r = rawRequest(port, "GET /video?bvid=BV1xx411c7mD HTTP/1.1")
            assertEquals(200, r.status)
            assertArrayEquals("MIRROR".toByteArray(), r.body)
            assertEquals(2, up.seen.size)
        }
    }

    /** 13. 生产模式（allowDirectUrl=false）：?url= 一律 400，不泄露成任意 URL 代理。 */
    @Test
    fun directUrlDisabledByDefault() {
        val up = FakeUpstream().route("http://up/a.flv", 200, emptyMap(), "X".toByteArray())
        withServer(up, rooms = setOf(1)) { port ->
            assertEquals(400, rawRequest(port, "GET /live?url=" + enc("http://up/a.flv") + " HTTP/1.1").status)
            assertEquals(400, rawRequest(port, "GET /video?url=" + enc("http://up/a.flv") + " HTTP/1.1").status)
            assertEquals(0, up.seen.size)
        }
    }

    /** 14. 自测模式（allowDirectUrl=true）：?url= 可用，供 verify-proxy.sh 使用。 */
    @Test
    fun directUrlWorksWhenEnabled() {
        val up = FakeUpstream().route("http://up/a.flv", 200, mapOf("content-type" to "video/x-flv"), "OK".toByteArray())
        withServer(up, allowDirectUrl = true) { port ->
            val r = rawRequest(port, "GET /live?url=" + enc("http://up/a.flv") + " HTTP/1.1")
            assertEquals(200, r.status)
            assertArrayEquals("OK".toByteArray(), r.body)
        }
    }

    /** 15. 未知路径 404，非 GET/HEAD 405。 */
    @Test
    fun notFoundAndMethodNotAllowed() {
        withServer(FakeUpstream()) { port ->
            assertEquals(404, rawRequest(port, "GET /nope HTTP/1.1").status)
            assertEquals(404, rawRequest(port, "GET /live/5017134 HTTP/1.1").status)
            assertEquals(405, rawRequest(port, "POST /status HTTP/1.1").status)
        }
    }
}
