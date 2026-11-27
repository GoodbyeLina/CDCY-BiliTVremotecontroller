package tvrc.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkBatchTest {

    @Test
    fun splitsOneUrlPerLine() {
        val raw = """
            https://www.bilibili.com/video/BV1xx411c7mD
            https://live.bilibili.com/26312812
        """.trimIndent()
        assertEquals(
            listOf(
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "https://live.bilibili.com/26312812"
            ),
            LinkBatch.split(raw)
        )
    }

    @Test
    fun extractsUrlsFromShareTextLines() {
        val raw = """
            【第一集】 https://b23.tv/abc123
            第二集 https://b23.tv/def456 复制打开
        """.trimIndent()
        assertEquals(listOf("https://b23.tv/abc123", "https://b23.tv/def456"), LinkBatch.split(raw))
    }

    @Test
    fun stripsChinesePunctuationAfterUrl() {
        val raw = "看这个（https://b23.tv/abc123），很好看。"
        assertEquals(listOf("https://b23.tv/abc123"), LinkBatch.split(raw))
    }

    @Test
    fun acceptsBareB23AndBilibiliLinks() {
        val raw = """
            b23.tv/abc123
            www.bilibili.com/video/BV1xx411c7mD
        """.trimIndent()
        assertEquals(
            listOf("https://b23.tv/abc123", "https://www.bilibili.com/video/BV1xx411c7mD"),
            LinkBatch.split(raw)
        )
    }

    @Test
    fun collectsBvAndAvNumbersFromDocumentLines() {
        val raw = """
            第 1 集 BV1xx411c7mD
            第 2 集 av12345
            房间 26312812
        """.trimIndent()
        assertEquals(listOf("BV1xx411c7mD", "av12345", "26312812"), LinkBatch.split(raw))
    }

    @Test
    fun deduplicatesRepeatedLinks() {
        val raw = """
            https://b23.tv/abc123
            https://b23.tv/abc123
            https://b23.tv/def456
        """.trimIndent()
        assertEquals(listOf("https://b23.tv/abc123", "https://b23.tv/def456"), LinkBatch.split(raw))
    }

    @Test
    fun fallsBackToWholeTextWhenNothingMatches() {
        val raw = "这是一段没有任何链接的说明文字"
        assertEquals(listOf(raw), LinkBatch.split(raw))
    }

    @Test
    fun blankInputProducesNothing() {
        assertEquals(emptyList<String>(), LinkBatch.split("   \n  "))
    }
}
