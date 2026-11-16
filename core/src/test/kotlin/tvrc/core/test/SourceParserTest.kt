package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.bili.Source
import tvrc.core.bili.SourceParser

/** SourceParser 口径对齐二代 parse_input（F-09）。 */
class SourceParserTest {

    @Test
    fun pureDigitsIsLiveRoom() {
        assertEquals(Source.LiveRoom(5017134L), SourceParser.parse("5017134"))
    }

    @Test
    fun liveUrlIsLiveRoom() {
        assertEquals(Source.LiveRoom(123L), SourceParser.parse("https://live.bilibili.com/123"))
        assertEquals(Source.LiveRoom(456L), SourceParser.parse("https://live.bilibili.com/blanc/456?foo=1"))
    }

    @Test
    fun liveUrlInsideTextIsFound() {
        assertEquals(Source.LiveRoom(999L), SourceParser.parse("点这个 https://live.bilibili.com/999 看看"))
    }

    @Test
    fun shortLinkIsRecognizedNotExpanded() {
        val s = SourceParser.parse("https://b23.tv/abcDEF123")
        assertTrue(s is Source.ShortLink && s.url == "https://b23.tv/abcDEF123")
    }

    @Test
    fun videoUrlAndBareBv() {
        assertEquals(Source.Video("BV1xx411c7mD"), SourceParser.parse("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertEquals(Source.Video("BV1AbCdEfGh2"), SourceParser.parse("BV1AbCdEfGh2"))
    }

    @Test
    fun seriesUrl() {
        assertEquals(
            Source.Series(23191782L, 1424266L),
            SourceParser.parse("https://space.bilibili.com/23191782/lists/1424266?ftype=collect")
        )
    }

    @Test
    fun seasonUrl() {
        assertEquals(
            Source.Season(100L, 200L),
            SourceParser.parse("https://space.bilibili.com/100/channel/collectiondetail?sid=200")
        )
    }

    @Test
    fun plainSpaceIsUp() {
        assertEquals(Source.Up(23191782L), SourceParser.parse("https://space.bilibili.com/23191782"))
    }

    @Test
    fun garbageAndEmptyAreUnknown() {
        assertEquals(Source.Unknown, SourceParser.parse(null))
        assertEquals(Source.Unknown, SourceParser.parse("   "))
        assertEquals(Source.Unknown, SourceParser.parse("随便看点啥"))
        assertEquals(Source.Unknown, SourceParser.parse("http://example.com/123"))
    }

    @Test
    fun zeroWidthSpaceAndSurroundingTextTolerated() {
        assertEquals(Source.LiveRoom(77L), SourceParser.parse("\u200b 77 \u200b"))
    }
}
