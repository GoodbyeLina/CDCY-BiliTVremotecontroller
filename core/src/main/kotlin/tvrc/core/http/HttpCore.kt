package tvrc.core.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

/** 解析出的 HTTP 请求（只支持我们需要的子集：GET/HEAD + 一层 query）。 */
data class ParsedRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>
)

object HttpParsing {

    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val CRLFCRLF = 0x0D0A0D0A

    /** 从 socket 读一个请求头。读不到/超限返回 null。 */
    fun readRequest(input: InputStream): ParsedRequest? {
        val buf = ByteArrayOutputStream(512)
        var tail = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            tail = (tail shl 8) or b
            if (buf.size() >= 4 && tail == CRLFCRLF) break
            if (buf.size() > MAX_HEADER_BYTES) throw IOException("request header too large")
        }
        val text = buf.toString("ISO-8859-1")
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val method = first[0].uppercase()
        val target = first[1]
        val qIndex = target.indexOf('?')
        val path = if (qIndex >= 0) target.substring(0, qIndex) else target
        val query = if (qIndex >= 0) parseQuery(target.substring(qIndex + 1)) else emptyMap()
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return ParsedRequest(method, path, query, headers)
    }

    fun parseQuery(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            if (i < 0) out[URLDecoder.decode(pair, "UTF-8")] = ""
            else out[URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }
        return out
    }
}

/**
 * Range 头解析与裁剪（F-07：直播绝不能带 Range，点播透传；上游返 200 全量时本地裁剪）。
 */
data class ByteRange(val start: Long, val endInclusive: Long?) {

    fun endFor(total: Long): Long = endInclusive?.coerceAtMost(total - 1) ?: (total - 1)

    companion object {
        /** 支持 "bytes=0-1023"、"bytes=1024-"、"bytes=-500"。不可解析返回 null。 */
        fun parse(header: String?): ByteRange? {
            if (header == null) return null
            val h = header.trim()
            if (!h.startsWith("bytes=")) return null
            val spec = h.removePrefix("bytes=").substringBefore(',')
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val left = spec.substring(0, dash).trim()
            val right = spec.substring(dash + 1).trim()
            return when {
                left.isEmpty() && right.isEmpty() -> null
                left.isEmpty() -> null // 后缀形式 bytes=-N 需要总长才能算，调用方按全文处理
                right.isEmpty() -> ByteRange(left.toLongOrNull() ?: return null, null)
                else -> {
                    val s = left.toLongOrNull() ?: return null
                    val e = right.toLongOrNull() ?: return null
                    if (e < s) null else ByteRange(s, e)
                }
            }
        }
    }
}
