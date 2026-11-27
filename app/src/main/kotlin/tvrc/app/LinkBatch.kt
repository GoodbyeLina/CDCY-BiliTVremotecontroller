package tvrc.app

/**
 * 批量导入的输入切分：整段分享文案 / 多行链接 / 文档里的 BV、av、房间号。
 *
 * 规则：
 * - 一行里出现的所有 http(s) 链接都算（分享文案里夹着链接也能取到）；
 * - 没有全链接时，识别 `b23.tv/xxx`、`bilibili.com/...` 这种省略协议的写法；
 * - 再没有就找行内的 BV/av 号；整行只是数字时当作直播间号；
 * - 结果去重并保持出现顺序；什么都没识别到时退回整段文本，交给单链接导入器报错。
 */
object LinkBatch {

    private val URL = Regex(
        """https?://[^\s<>"'，。、；：）】》”’]+""",
        RegexOption.IGNORE_CASE
    )
    private val BARE = Regex(
        """(?<![\w./])((?:www\.)?(?:b23\.tv|bilibili\.com)/[^\s<>"'，。、；：）】》”’]+)""",
        RegexOption.IGNORE_CASE
    )
    private val BV = Regex("""(?<![0-9A-Za-z])(BV[0-9A-Za-z]{10}|av\d+)""", RegexOption.IGNORE_CASE)
    private val ROOM = Regex("""\d{3,12}""")
    private val ROOM_HINT = Regex("""(?:房间|直播间)\D{0,6}(\d{3,12})""")
    private val TRAILING = charArrayOf(
        '。', '，', '、', '；', '：', '）', '】', '》', '”', '’',
        ')', ']', '}', '>', ',', '.', ';', ':', '"', '\''
    )

    fun split(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (line in raw.lineSequence()) {
            var found = false
            for (m in URL.findAll(line)) {
                out += clean(m.value)
                found = true
            }
            if (!found) {
                for (m in BARE.findAll(line)) {
                    out += clean("https://" + m.groupValues[1])
                    found = true
                }
            }
            if (!found) {
                for (m in BV.findAll(line)) {
                    out += m.value
                    found = true
                }
            }
            if (!found) {
                val t = line.trim()
                if (ROOM.matches(t)) {
                    out += t
                } else {
                    ROOM_HINT.find(line)?.let { m ->
                        out += m.groupValues[1]
                    }
                }
            }
        }
        if (out.isEmpty()) out += raw.trim()
        return out.toList()
    }

    private fun clean(url: String): String = url.trimEnd(*TRAILING)
}
