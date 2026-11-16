package tvrc.core.test

import tvrc.core.http.UpstreamFetcher
import tvrc.core.http.UpstreamResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Collections

/** 上游替身：按 URL 返回预设响应，并记录收到的请求头。 */
class FakeUpstream : UpstreamFetcher {

    data class Reply(val status: Int, val headers: Map<String, String>, val bytes: ByteArray)

    private val routes = LinkedHashMap<String, Reply>()
    val seen: MutableList<Pair<String, Map<String, String>>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var failWith: IOException? = null

    fun route(
        url: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        bytes: ByteArray = ByteArray(0)
    ): FakeUpstream {
        routes[url] = Reply(status, headers, bytes)
        return this
    }

    override fun open(url: String, requestHeaders: Map<String, String>, timeoutMs: Int): UpstreamResponse {
        seen.add(url to requestHeaders)
        failWith?.let { e -> failWith = null; throw e }
        val r = routes[url] ?: return UpstreamResponse(404, emptyMap(), ByteArrayInputStream(ByteArray(0)), 0)
        return UpstreamResponse(r.status, r.headers, ByteArrayInputStream(r.bytes), r.bytes.size.toLong())
    }
}
