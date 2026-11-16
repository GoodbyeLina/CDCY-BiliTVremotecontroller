package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.bili.ArchiveItem
import tvrc.core.bili.ArchivePage
import tvrc.core.bili.BiliArchiveApi
import tvrc.core.bili.Source
import tvrc.core.bili.SourceKind
import tvrc.core.bili.SourceParser
import tvrc.core.bili.SourceResolver
import tvrc.core.bili.UpSeriesLister

/** P2-3：判型必须调接口——用记录调用的假接口证明「不是靠字符串特征猜」。 */
class SourceResolverTest {

    private class FakeArchive(
        val seriesItems: List<ArchiveItem> = emptyList(),
        val seasonItems: List<ArchiveItem> = emptyList(),
        val fail: Boolean = false
    ) : BiliArchiveApi {
        val seriesCalls = ArrayList<Pair<Long, Long>>()
        val seasonCalls = ArrayList<Pair<Long, Long>>()

        override fun seriesPage(mid: Long, seriesId: Long, pageNum: Int, pageSize: Int): ArchivePage {
            seriesCalls.add(mid to seriesId)
            if (fail) throw java.io.IOException("net down")
            return ArchivePage(seriesItems, seriesItems.size)
        }

        override fun seasonPage(mid: Long, seasonId: Long, pageNum: Int, pageSize: Int): ArchivePage {
            seasonCalls.add(mid to seasonId)
            if (fail) throw java.io.IOException("net down")
            return ArchivePage(seasonItems, seasonItems.size)
        }

        override fun seriesAll(mid: Long, seriesId: Long, pageSize: Int, maxPages: Int) = seriesItems
        override fun seasonAll(mid: Long, seasonId: Long, pageSize: Int, maxPages: Int) = seasonItems
    }

    private val item = ArchiveItem("BV1a", "A", "", 0, 0)

    @Test
    fun seriesInputIsConfirmedBySeriesApi() {
        val archive = FakeArchive(seriesItems = listOf(item))
        val resolver = SourceResolver(archive, UpSeriesLister { false })
        val parsed = SourceParser.parse("https://space.bilibili.com/23191782/lists/1424266")
        val resolved = resolver.resolve(parsed)

        assertEquals(SourceKind.SERIES, resolved.kind)
        assertEquals(listOf(23191782L to 1424266L), archive.seriesCalls, "必须真的调 seriesPage")
        assertTrue(archive.seasonCalls.isEmpty())
        assertEquals(1, resolved.items.size)
    }

    @Test
    fun seasonInputIsConfirmedBySeasonApi() {
        val archive = FakeArchive(seasonItems = listOf(item))
        val resolver = SourceResolver(archive, UpSeriesLister { false })
        val parsed = SourceParser.parse("https://space.bilibili.com/23191782/channel/collectiondetail?sid=98765")
        val resolved = resolver.resolve(parsed)

        assertEquals(SourceKind.SEASON, resolved.kind)
        assertEquals(listOf(23191782L to 98765L), archive.seasonCalls)
        assertTrue(archive.seriesCalls.isEmpty())
    }

    @Test
    fun upInputNeedsListerAndApiFailureFallsBackToUnknown() {
        val parsed = SourceParser.parse("https://space.bilibili.com/23191782")
        assertEquals(SourceKind.UP, SourceResolver(FakeArchive(), UpSeriesLister { true }).resolve(parsed).kind)
        assertEquals(SourceKind.UNKNOWN, SourceResolver(FakeArchive(), UpSeriesLister { false }).resolve(parsed).kind)

        val failing = FakeArchive(fail = true, seriesItems = listOf(item))
        val resolved = SourceResolver(failing, UpSeriesLister { true })
            .resolve(Source.Series(23191782, 1424266))
        assertEquals(SourceKind.UNKNOWN, resolved.kind, "接口失败不得靠正则猜成 SERIES")
        assertTrue(resolved.note.contains("接口失败"))
    }
}
