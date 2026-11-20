package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.bili.ExpandResult
import tvrc.core.bili.RedirectFollower
import tvrc.core.bili.ShareText
import tvrc.core.bili.Source
import tvrc.core.bili.SourceExpander
import java.io.IOException

/** R-01/R-02：分享文案提取、b23.tv 短链展开、失败降级。全部零网络。 */
class ShortLinkTest {

    private class FakeFollower(
        var finalUrl: String? = null,
        var error: Exception? = null
    ) : RedirectFollower {
        val calls = mutableListOf<String>()
        override fun follow(url: String, timeoutMs: Int): String {
            calls += url
            error?.let { throw it }
            return finalUrl ?: url
        }
    }

    private fun expander(f: FakeFollower) = SourceExpander(f, timeoutMs = 1_000)

    @Test
    fun extractsUrlFromShareCopy() {
        val share = "【芙宁娜的直播间】 https://b23.tv/ZSX0hxs \n快来一起看"
        assertEquals(listOf("https://b23.tv/ZSX0hxs"), ShareText.extractUrls(share))
        assertEquals("https://b23.tv/ZSX0hxs", ShareText.pickBiliUrl(share))
    }

    @Test
    fun ignoresChinesePunctuationAfterUrl() {
        val urls = ShareText.extractUrls("看这个 https://www.bilibili.com/video/BV15H45zUE7T，很好看。")
        assertEquals(listOf("https://www.bilibili.com/video/BV15H45zUE7T"), urls)
    }

    @Test
    fun noUrlReturnsEmpty() {
        assertTrue(ShareText.extractUrls("612978").isEmpty())
        assertTrue(ShareText.extractUrls(null).isEmpty())
    }

    @Test
    fun shortLinkExpandsToLiveRoom() {
        val f = FakeFollower("https://live.bilibili.com/612978?share_source=copy")
        val r: ExpandResult = expander(f).expand("【直播间】https://b23.tv/abc123")
        assertTrue(r.source is Source.LiveRoom)
        assertEquals(612978L, (r.source as Source.LiveRoom).roomId)
        assertEquals(2, r.hops.size, "跳转链要留证：短链 + 落地地址")
        assertEquals("https://b23.tv/abc123", f.calls.single())
    }

    @Test
    fun shortLinkExpandsToVideo() {
        val f = FakeFollower("https://www.bilibili.com/video/BV1Atsc6gEbb?share_source=copy")
        val r = expander(f).expand("https://b23.tv/ZSX0hxs")
        assertEquals("BV1Atsc6gEbb", (r.source as Source.Video).bvid)
    }

    @Test
    fun plainUrlNeedsNoNetwork() {
        val f = FakeFollower("should-not-be-used")
        val r = expander(f).expand("https://live.bilibili.com/5017134")
        assertEquals(5017134L, (r.source as Source.LiveRoom).roomId)
        assertTrue(f.calls.isEmpty(), "普通网址不应发展开请求")
    }

    @Test
    fun bareRoomNumberNeedsNoNetwork() {
        val f = FakeFollower(null)
        val r = expander(f).expand("612978")
        assertTrue(r.source is Source.LiveRoom)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun failureKeepsShortLinkAndExplains() {
        val f = FakeFollower(error = IOException("timeout 8000ms"))
        val r = expander(f).expand("https://b23.tv/broken")
        assertTrue(r.isShortLinkUnresolved)
        assertTrue(r.note.contains("短链展开失败"), r.note)
        assertTrue(r.note.contains("timeout"), r.note)
    }

    @Test
    fun redirectThatStaysShortLinkIsReported() {
        val f = FakeFollower("https://b23.tv/loop")
        val r = expander(f).expand("https://b23.tv/loop")
        assertTrue(r.isShortLinkUnresolved)
        assertTrue(r.note.contains("仍不是可识别地址"), r.note)
    }

    @Test
    fun emptyInputIsUnknown() {
        val r = expander(FakeFollower(null)).expand("   ")
        assertTrue(r.source is Source.Unknown)
        assertFalse(r.isShortLinkUnresolved)
    }
}
