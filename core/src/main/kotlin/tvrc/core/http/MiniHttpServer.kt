package tvrc.core.http

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 手写最小 HTTP 代理（三代专用，替代 Ktor）。
 *
 * **路由口径按二代已跑通的方案**（相关文件/termux-port/station.py:341-380）：
 *
 * | 路由 | 用途 | 校验 |
 * |---|---|---|
 * | GET /status、/api/status | 健康/能力协商 | 无 |
 * | GET /live?room=<房间号>（别名 /live.flv） | 直播流 | room 必须在 [liveRooms] 里，否则 400 |
 * | GET /video?bvid=<BV号> | 点播流 | bvid 必须形如 BVxxxxxxxx，且在 [videoIds] 里（列表为空=只校验格式，兼容二代） |
 * | GET /live?url=、/video?url= | **仅自测**（[allowDirectUrl]=true 时才开，默认关） | 不开时一律 400 |
 *
 * 行为约定（与二代一致）：
 * - 只支持 GET/HEAD，一律 Connection: close，不落盘；
 * - 直播**绝不带 Range**、**不走镜像**（F-07/F-08；带 Range 会让直播 206/乱序，换域名会 404）；
 * - 点播透传客户端 Range；上游无视 Range 返 200 全量时**本地裁剪**成 206；
 * - 上游解析失败 → 502；参数不合法/不在白名单 → 400（二代口径）；
 * - 64KB 逐块边收边发，内存占用与文件大小无关。
 *
 * 端口模型（K §2 裁决 2）：本进程唯一监听是媒体代理 0.0.0.0:8100，供电视拉流；
 * 控制指令走进程内方法调用（PlaybackEngine.start/stop/retry），不存在 HTTP 控制端口。
 * [bindAddress] 仅用于测试绑回环，生产恒为 0.0.0.0。
 */
class MiniHttpServer(
    private val port: Int = 8100,
    private val bindAddress: String = "0.0.0.0",
    private val fetcher: UpstreamFetcher,
    private val mirrors: List<String> = emptyList(),
    /** 允许代理的直播房间号（来自当前会话配置）。每次请求实时取，便于轮播列表变化。 */
    private val liveRooms: () -> Set<Long> = { emptySet() },
    /** 房间号 → 真实 FLV 上游地址；解析不出来返回 null（→502）。 */
    private val resolveLive: (Long) -> String? = { null },
    /** 允许代理的点播 BV 号。空集合 = 只校验 BV 格式（二代行为）；非空 = 严格白名单。 */
    private val videoIds: () -> Set<String> = { emptySet() },
    /** BV 号 → 真实 MP4 上游地址；解析不出来返回 null（→502）。 */
    private val resolveVideo: (String) -> String? = { null },
    /** 是否允许 ?url= 直连形式（只给 verify-proxy 这类回环自测开；生产必须 false）。 */
    private val allowDirectUrl: Boolean = false,
    private val schemaVersion: Int = 1,
    private val capabilities: List<String> = listOf("live", "video", "status"),
    private val liveHeaders: Map<String, String> = UpstreamHeaders.LIVE,
    private val videoHeaders: Map<String, String> = UpstreamHeaders.VIDEO,
    private val upstreamTimeoutMs: Int = 15_000,
    private val chunkSize: Int = 64 * 1024
) : Closeable {

    private companion object {
        /** 二代的 bvid 判定是 startswith("BV")，这里收紧到 BV + 至少 8 位字母数字（真实 BV 号是 BV + 10 位）。 */
        val BVID_RE = Regex("^BV[0-9A-Za-z]{8,}$")
    }

    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val requestCount = AtomicLong(0)

    val boundPort: Int
        get() = server?.localPort ?: -1

    fun start(): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, port))
        server = s
        val p = Executors.newCachedThreadPool { r -> Thread(r, "mini-http").apply { isDaemon = true } }
        pool = p
        Thread({ acceptLoop(s) }, "mini-http-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    override fun close() {
        runCatching { server?.close() }
        pool?.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val c = s.accept()
                pool?.execute { handle(c) }
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 20_000
                val req = HttpParsing.readRequest(s.getInputStream()) ?: return
                requestCount.incrementAndGet()
                val out = s.getOutputStream()
                route(req, out)
                out.flush()
            }
        } catch (_: IOException) {
            // 客户端断开/超时：忽略
        }
    }

    private fun route(req: ParsedRequest, out: OutputStream) {
        when (req.method) {
            "GET", "HEAD" -> Unit
            else -> {
                respond(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "only GET/HEAD\n".toByteArray())
                return
            }
        }
        val headOnly = req.method == "HEAD"
        when (req.path) {
            "/status", "/api/status" -> {
                val caps = capabilities.joinToString(",") { "\"" + it + "\"" }
                val rooms = liveRooms().sorted().joinToString(",")
                val json = "{\"ok\":true,\"schemaVersion\":$schemaVersion,\"capabilities\":[$caps]," +
                    "\"rooms\":[$rooms],\"videoCount\":" + videoIds().size + "}"
                respond(out, 200, "OK", "application/json; charset=utf-8", json.toByteArray(), headOnly)
            }
            "/live", "/live.flv" -> liveRoute(req, out, headOnly)
            "/video" -> videoRoute(req, out, headOnly)
            else -> respond(out, 404, "Not Found", "text/plain; charset=utf-8", "not found\n".toByteArray(), headOnly)
        }
    }

    /**
     * 直播：/live?room=<房间号>（别名 /live.flv）。
     * 白名单外的房间一律 400（二代口径），解析不出上游 502。
     */
    private fun liveRoute(req: ParsedRequest, out: OutputStream, headOnly: Boolean) {
        val direct = req.query["url"]
        if (!direct.isNullOrBlank()) {
            if (!allowDirectUrl) {
                respond(out, 400, "Bad Request", "text/plain; charset=utf-8", "url form disabled\n".toByteArray(), headOnly)
                return
            }
            forwardTo(direct, req, out, allowRange = false, allowMirror = false, headers = liveHeaders, headOnly = headOnly)
            return
        }
        val room = req.query["room"]?.trim()?.toLongOrNull()
        if (room == null) {
            respond(out, 400, "Bad Request", "text/plain; charset=utf-8", "missing or bad room\n".toByteArray(), headOnly)
            return
        }
        if (room !in liveRooms()) {
            respond(out, 400, "Bad Request", "text/plain; charset=utf-8", "room not allowed\n".toByteArray(), headOnly)
            return
        }
        val upstream = resolveLive(room)
        if (upstream.isNullOrBlank()) {
            respond(out, 502, "Bad Gateway", "text/plain; charset=utf-8", "cannot resolve room\n".toByteArray(), headOnly)
            return
        }
        forwardTo(upstream, req, out, allowRange = false, allowMirror = false, headers = liveHeaders, headOnly = headOnly)
    }

    /**
     * 点播：/video?bvid=<BV号>。
     * 格式不对 → 400；[videoIds] 非空时不在列表 → 400（为空则只校验格式，兼容二代）；解析不出上游 502。
     */
    private fun videoRoute(req: ParsedRequest, out: OutputStream, headOnly: Boolean) {
        val direct = req.query["url"]
        if (!direct.isNullOrBlank()) {
            if (!allowDirectUrl) {
                respond(out, 400, "Bad Request", "text/plain; charset=utf-8", "url form disabled\n".toByteArray(), headOnly)
                return
            }
            forwardTo(direct, req, out, allowRange = true, allowMirror = true, headers = videoHeaders, headOnly = headOnly)
            return
        }
        val bvid = req.query["bvid"]?.trim().orEmpty()
        if (!BVID_RE.matches(bvid)) {
            respond(out, 400, "Bad Request", "text/plain; charset=utf-8", "missing or bad bvid\n".toByteArray(), headOnly)
            return
        }
        val allowed = videoIds()
        if (allowed.isNotEmpty() && bvid !in allowed) {
            respond(out, 400, "Bad Request", "text/plain; charset=utf-8", "bvid not allowed\n".toByteArray(), headOnly)
            return
        }
        val upstream = resolveVideo(bvid)
        if (upstream.isNullOrBlank()) {
            respond(out, 502, "Bad Gateway", "text/plain; charset=utf-8", "cannot resolve bvid\n".toByteArray(), headOnly)
            return
        }
        forwardTo(upstream, req, out, allowRange = true, allowMirror = true, headers = videoHeaders, headOnly = headOnly)
    }

    private fun forwardTo(
        target: String,
        req: ParsedRequest,
        out: OutputStream,
        allowRange: Boolean,
        allowMirror: Boolean,
        headers: Map<String, String>,
        headOnly: Boolean
    ) {
        val clientRangeHeader = if (allowRange) req.headers["range"] else null
        val upstreamHeaders = LinkedHashMap(headers)
        if (clientRangeHeader != null) upstreamHeaders["Range"] = clientRangeHeader

        var resp = fetch(target, upstreamHeaders)
        if (allowMirror && resp.status >= 400 && mirrors.isNotEmpty()) {
            runCatching { resp.body.close() }
            for (m in mirrors) {
                val alt = withMirror(target, m) ?: continue
                val r = fetch(alt, upstreamHeaders)
                if (r.status < 400) {
                    resp = r
                    break
                }
                runCatching { r.body.close() }
            }
        }

        if (resp.status >= 400) {
            runCatching { resp.body.close() }
            respond(out, 502, "Bad Gateway", "text/plain; charset=utf-8", "upstream ${resp.status}\n".toByteArray(), headOnly)
            return
        }

        val range = ByteRange.parse(clientRangeHeader)
        if (clientRangeHeader != null && resp.status == 200 && range != null && resp.contentLength >= 0) {
            // 上游无视 Range 返 200 全量 → 本地裁剪成 206（F-07 / S3）
            val total = resp.contentLength
            if (range.start >= total) {
                runCatching { resp.body.close() }
                respond(out, 416, "Range Not Satisfiable", "text/plain; charset=utf-8", "range not satisfiable\n".toByteArray(), headOnly)
                return
            }
            val end = range.endFor(total)
            val length = end - range.start + 1
            skipFully(resp.body, range.start)
            val respHeaders = LinkedHashMap<String, String>()
            putIfPresent(respHeaders, "Content-Type", resp.headers["content-type"])
            respHeaders["Content-Range"] = "bytes ${range.start}-$end/$total"
            respHeaders["Content-Length"] = length.toString()
            respHeaders["Accept-Ranges"] = "bytes"
            val status = "206 Partial Content"
            writeHead(out, status, respHeaders)
            if (!headOnly) copyLimited(resp.body, out, length)
            resp.body.close()
            return
        }

        val respHeaders = LinkedHashMap<String, String>()
        putIfPresent(respHeaders, "Content-Type", resp.headers["content-type"] ?: "application/octet-stream")
        putIfPresent(respHeaders, "Content-Range", resp.headers["content-range"])
        respHeaders["Accept-Ranges"] = "bytes"
        if (resp.contentLength >= 0) respHeaders["Content-Length"] = resp.contentLength.toString()
        writeHead(out, statusLine(resp.status), respHeaders)
        if (!headOnly) copyAll(resp.body, out)
        resp.body.close()
    }

    private fun fetch(url: String, headers: Map<String, String>): UpstreamResponse =
        fetcher.open(url, headers, upstreamTimeoutMs)

    /** 只对 /video 的镜像替换：保留原 path/query，只换 host:port。 */
    internal fun withMirror(url: String, mirror: String): String? = runCatching {
        val u = URL(url)
        val mu = if (mirror.contains("://")) URL(mirror) else URL("${u.protocol}://$mirror")
        val p = if (mu.port > 0) mu.port else u.port
        URL(u.protocol, mu.host, p, u.file).toString()
    }.getOrNull()

    private fun respond(
        out: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean = false
    ) {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = contentType
        h["Content-Length"] = body.size.toString()
        writeHead(out, "$code $reason", h)
        if (!headOnly) out.write(body)
    }

    private fun writeHead(out: OutputStream, statusLine: String, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(statusLine).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun statusLine(code: Int): String = when (code) {
        200 -> "200 OK"
        206 -> "206 Partial Content"
        400 -> "400 Bad Request"
        404 -> "404 Not Found"
        416 -> "416 Range Not Satisfiable"
        502 -> "502 Bad Gateway"
        else -> "$code OK"
    }

    private fun putIfPresent(map: MutableMap<String, String>, key: String, value: String?) {
        if (!value.isNullOrBlank()) map[key] = value
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(chunkSize)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) return
            remaining -= n
        }
    }

    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long) {
        var remaining = limit
        val buf = ByteArray(chunkSize)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun copyAll(input: InputStream, out: OutputStream) {
        val buf = ByteArray(chunkSize)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }
}