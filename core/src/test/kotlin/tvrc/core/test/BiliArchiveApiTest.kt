package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.Mode
import tvrc.core.bili.BiliApiException
import tvrc.core.bili.ArchiveItem
import tvrc.core.bili.HttpText
import tvrc.core.bili.RealBiliArchiveApi
import tvrc.core.bili.VideoTargets
import tvrc.core.bili.videoSession
import tvrc.core.bili.LiveSessions

/** P2-2：回放固定 JSON（不联网），覆盖单页 / 多页拼装 / 空列表 / code!=0 / 缺字段 / 单集直链。 */
class BiliArchiveApiTest {

    private class FakeHttp(private val responses: Map<String, String>) : HttpText {
        val calls = ArrayList<String>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): String {
            calls.add(url)
            return responses.entries.firstOrNull { url.contains(it.key) }?.value
                ?: throw AssertionError("no canned response for " + url)
        }
    }

    private fun page(vararg bvids: String, total: Int = bvids.size): String {
        val items = bvids.joinToString(",") { bv ->
            """{"bvid":"""" + bv + """","title":"T-""" + bv + """","cover":"http://c/""" + bv + """.jpg","duration":123,"pubdate":1700000000}"""
        }
        return """{"code":0,"data":{"archives":[""" + items + """],"page":{"total":""" + total + """}}}"""
    }

    @Test
    fun parsesSinglePage() {
        val api = RealBiliArchiveApi(FakeHttp(mapOf("series/archives" to page("BV1a", "BV1b"))))
        val result = api.seriesPage(23191782, 1424266, 1, 50)
        assertEquals(2, result.items.size)
        assertEquals(2, result.total)
        assertEquals("BV1a", result.items[0].bvid)
        assertEquals("T-BV1a", result.items[0].title)
        assertEquals(123L, result.items[0].durationSec)
    }

    @Test
    fun assemblesMultiplePages() {
        val fake = FakeHttp(
            mapOf(
                "pn=1" to page("BV1a", "BV1b", total = 3),
                "pn=2" to page("BV1c", total = 3)
            )
        )
        val api = RealBiliArchiveApi(fake)
        val all = api.seriesAll(23191782, 1424266, pageSize = 2)
        assertEquals(listOf("BV1a", "BV1b", "BV1c"), all.map { it.bvid })
        assertEquals(2, fake.calls.size, "应恰好翻两页，取满 total 即停")
    }

    @Test
    fun emptyListIsEmpty() {
        val api = RealBiliArchiveApi(FakeHttp(mapOf("seasons_archives_list" to """{"code":0,"data":{"archives":[],"page":{"total":0}}}""")))
        assertTrue(api.seasonPage(1, 2, 1, 50).items.isEmpty())
        assertTrue(api.seasonAll(1, 2).isEmpty())
    }

    @Test
    fun nonZeroCodeThrows() {
        val api = RealBiliArchiveApi(FakeHttp(mapOf("series/archives" to """{"code":-404,"message":"啥都木有"}""")))
        val e = assertThrows(BiliApiException::class.java) { api.seriesPage(1, 2, 1, 50) }
        assertTrue(e.message!!.contains("-404"))
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val api = RealBiliArchiveApi(FakeHttp(mapOf(
            "seasons_archives_list" to """{"code":0,"data":{"archives":[{"bvid":"BV1x"},{"title":"no bvid"}],"page":{"total":2}}}"""
        )))
        val items = api.seasonPage(1, 2, 1, 50).items
        assertEquals(1, items.size, "缺 bvid 的条目被跳过")
        assertEquals("", items[0].title)
        assertEquals(0L, items[0].durationSec)
        assertEquals("", items[0].cover)
    }

    @Test
    fun singleVideoTargetsAndSession() {
        assertEquals("http://192.168.10.241:8100/video?bvid=BV1a", VideoTargets.target("BV1a", "192.168.10.241", 8100))
        val items = listOf(
            ArchiveItem("BV1a", "A", "", 0, 0),
            ArchiveItem("BV1b", "B", "", 0, 0)
        )
        val session = LiveSessions.videoSession(items, "192.168.10.241", 8100, 1800)
        assertEquals(Mode.VIDEO, session.mode)
        assertEquals(
            listOf("http://192.168.10.241:8100/video?bvid=BV1a", "http://192.168.10.241:8100/video?bvid=BV1b"),
            session.items
        )
        assertEquals(1800L, session.switchSeconds)
    }
}
