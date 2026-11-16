package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import tvrc.core.bili.RealVideoUrlResolver
import java.io.IOException

class VideoUrlResolverTest {

    @Test
    fun resolvesViaViewThenPlayurl() {
        val http = FakeHttpText()
        http.stub("https://api.bilibili.com/x/web-interface/view", "{\"code\":0,\"data\":{\"cid\":12345}}")
        http.stub(
            "https://api.bilibili.com/x/player/playurl",
            "{\"code\":0,\"data\":{\"durl\":[{\"url\":\"https://cdn/x.mp4?e=1\"}]}}"
        )
        val url = RealVideoUrlResolver(http).resolve("BV1xx411c7mD")
        assertEquals("https://cdn/x.mp4?e=1", url)
        assertTrue(http.requestedUrls[0].contains("bvid=BV1xx411c7mD"))
        assertTrue(http.requestedUrls[1].contains("cid=12345"))
        assertTrue(http.requestedUrls[1].contains("fnval=1"))
        assertTrue(http.seenUserAgents.isNotEmpty())
    }

    @Test
    fun missingCidThrows() {
        val http = FakeHttpText()
        http.stub("https://api.bilibili.com/x/web-interface/view", "{\"code\":0,\"data\":{}}")
        try {
            RealVideoUrlResolver(http).resolve("BV1bad")
            fail("应当抛 IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("cid"))
        }
    }

    @Test
    fun emptyDurlThrows() {
        val http = FakeHttpText()
        http.stub("https://api.bilibili.com/x/web-interface/view", "{\"code\":0,\"data\":{\"cid\":1}}")
        http.stub("https://api.bilibili.com/x/player/playurl", "{\"code\":0,\"data\":{}}")
        try {
            RealVideoUrlResolver(http).resolve("BV1x")
            fail("应当抛 IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("durl"))
        }
    }
}
