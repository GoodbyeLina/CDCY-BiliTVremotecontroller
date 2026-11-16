package tvrc.core.http

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 上游响应。body 必须由调用方关闭。 */
data class UpstreamResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    val contentLength: Long
)

/** 上游抓取端口。真实实现走 HttpURLConnection；测试用 FakeUpstream。 */
interface UpstreamFetcher {
    fun open(url: String, requestHeaders: Map<String, String>, timeoutMs: Int = 15_000): UpstreamResponse
}

/**
 * TLS 兜底策略（F-09）：B站 CDN 证书常与域名不匹配，只能对 CDN 域放开校验，
 * 绝不能全局放开。
 */
enum class TlsPolicy {
    NONE,
    CDN_ONLY;

    fun allows(url: String): Boolean = when (this) {
        NONE -> false
        CDN_ONLY -> {
            val host = runCatching { URL(url).host.lowercase() }.getOrDefault("")
            CDN_HOSTS.any { host == it || host.endsWith(".$it") }
        }
    }

    companion object {
        private val CDN_HOSTS = listOf("bilivideo.com", "bilivideo.cn", "hdslb.com", "bilibili.com")
    }
}

/** 默认请求头：伪装成浏览器 Referer，否则 B站 会 403。 */
object UpstreamHeaders {
    /**
     * 实测（2026-11-13，P2-4 验收）：Android 移动版 UA 请求直播 CDN 直接 403（552 字节错误页），
     * 换桌面版 Chrome UA 后 200。故统一用桌面 UA——电视端 mpv 拉的是本机代理，UA 由这里决定。
     */
    const val DESKTOP_UA: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    val LIVE: Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Referer" to "https://live.bilibili.com/",
        "Accept" to "*/*"
    )
    val VIDEO: Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Referer" to "https://www.bilibili.com/",
        "Accept" to "*/*"
    )
}

class HttpUrlConnectionFetcher(
    private val baseHeaders: Map<String, String>,
    private val tlsPolicy: TlsPolicy = TlsPolicy.CDN_ONLY
) : UpstreamFetcher {

    override fun open(url: String, requestHeaders: Map<String, String>, timeoutMs: Int): UpstreamResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        for ((k, v) in baseHeaders) conn.setRequestProperty(k, v)
        for ((k, v) in requestHeaders) conn.setRequestProperty(k, v)

        if (conn is HttpsURLConnection && tlsPolicy.allows(url)) {
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            conn.sslSocketFactory = trustAllContext().socketFactory
        }

        val status = conn.responseCode
        val stream: InputStream = if (status >= 400) (conn.errorStream ?: ByteArrayInputStream(ByteArray(0)))
        else conn.inputStream
        val headers = LinkedHashMap<String, String>()
        for ((name, values) in conn.headerFields) {
            if (name == null || values.isEmpty()) continue
            headers[name.lowercase()] = values.first()
        }
        return UpstreamResponse(status, headers, stream, conn.contentLengthLong)
    }

    private fun trustAllContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
    }
}
