package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.bili.Buvid
import tvrc.core.bili.BuvidParser
import tvrc.core.bili.WbiKeysParser
import tvrc.core.bili.WbiSigner

/**
 * P2-1：固定向量（由独立 Python hashlib 实现算出，非本 Kotlin 代码自证），
 * 覆盖 6 条验收要求的边界。真实网络请求另由 tools/verify-wbi.sh 验证。
 */
class WbiSignerTest {

    private val img = "7cd084941338484aae1ad9425b84077c"
    private val sub = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun mixinKeyMatchesFixedVector() {
        assertEquals("ea1db124af3c7062474693fa704f4ff8", WbiSigner.mixinKey(img, sub))
        assertEquals(
            "bbaabaababaababbababbabaaaababba",
            WbiSigner.mixinKey("a".repeat(32), "b".repeat(32))
        )
    }

    @Test
    fun signMatchesFixedVectorOne() {
        val signed = WbiSigner.sign(mapOf("foo" to "114", "bar" to "514", "zab" to 1919810), WbiSigner.mixinKey(img, sub), 1702204169)
        assertEquals("bar=514&foo=114&zab=1919810&wts=1702204169", signed.query)
        assertEquals("2c59c7b4ee066a5b35a17330fd23cf4b", signed.wRid)
    }

    @Test
    fun signMatchesFixedVectorRoomPlay() {
        val signed = WbiSigner.sign(
            mapOf("room_id" to "5017134", "qn" to "10000", "platform" to "web"),
            WbiSigner.mixinKey(img, sub), 1702204169
        )
        assertEquals("platform=web&qn=10000&room_id=5017134&wts=1702204169", signed.query)
        assertEquals("6a641c60fe6ada9462f74b544e0c3d51", signed.wRid)
    }

    @Test
    fun illegalCharsAreStrippedFromValues() {
        val signed = WbiSigner.sign(mapOf("title" to "a!b'c(d)e*f", "x" to "1"), WbiSigner.mixinKey(img, sub), 1702204169)
        assertEquals("title=abcdef&x=1&wts=1702204169", signed.query)
        assertEquals("332ca91f25cc279d089459665c9ab0de", signed.wRid)
        assertEquals("abcdef", WbiSigner.filterValue("a!b'c(d)e*f"))
    }

    @Test
    fun keysSortedAndNumbersStringified() {
        val signed = WbiSigner.sign(mapOf("b" to "2", "a" to "1", "c" to "3"), WbiSigner.mixinKey("a".repeat(32), "b".repeat(32)), 1700000000)
        assertEquals("a=1&b=2&c=3&wts=1700000000", signed.query)
        assertEquals("90a1395ad5f8679ebdd4e1bf4572626e", signed.wRid)
    }

    @Test
    fun encodeIsRfc3986AndKeysParsedFromNav() {
        assertEquals("a%20b", WbiSigner.encode("a b"))
        assertEquals("http%3A%2F%2Fx%2Fy", WbiSigner.encode("http://x/y"))
        assertEquals("-_.~", WbiSigner.encode("-_.~"))

        val nav = """{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
        val keys = WbiKeysParser.parse(nav)
        assertNotNull(keys)
        assertEquals(img, keys!!.imgKey)
        assertEquals(sub, keys.subKey)
        assertEquals("ea1db124af3c7062474693fa704f4ff8", keys.mixinKey)
        assertNull(WbiKeysParser.parse("{}"))
        assertNull(WbiKeysParser.parse("not json"))
    }

    @Test
    fun buvidParsedFromSpiResponse() {
        val spi = """{"code":0,"data":{"b_3":"XY-abc-123","b_4":"ZW-def-456"}}"""
        assertEquals(Buvid("XY-abc-123", "ZW-def-456"), BuvidParser.parse(spi))
        assertNull(BuvidParser.parse("""{"code":-1}"""))
    }
}
