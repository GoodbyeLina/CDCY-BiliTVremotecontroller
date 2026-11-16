package tvrc.core.bili

import java.io.IOException

/** 判型结论：类型由接口返回决定，不由字符串特征猜（K 裁决 / L P2-3）。 */
enum class SourceKind { SERIES, SEASON, UP, UNKNOWN }

data class ResolvedSource(
    val kind: SourceKind,
    val items: List<ArchiveItem>,
    val note: String
)

/** UP 主维度探测（真实现走 space 系接口；测试注入假实现）。 */
fun interface UpSeriesLister {
    @Throws(IOException::class)
    fun hasPlayableSeries(mid: Long): Boolean
}

/**
 * 判型层（P2-3）：[SourceParser] 只负责**识别入口**（把输入归类成候选），
 * 真正的 series / season / UP 判定必须调用接口确认：
 * - Series → 调 seriesPage，拿到稿件才算成立；
 * - Season → 调 seasonPage；
 * - Up     → 调 UpSeriesLister 确认该 UP 有可播合集。
 * 接口报错或返回空 → UNKNOWN（不猜、不降级成字符串匹配）。
 */
class SourceResolver(
    private val archive: BiliArchiveApi,
    private val upLister: UpSeriesLister
) {

    fun resolve(parsed: Source): ResolvedSource = when (parsed) {
        is Source.Series -> probe(
            SourceKind.SERIES,
            "series mid=" + parsed.mid + " id=" + parsed.listId
        ) { archive.seriesPage(parsed.mid, parsed.listId, 1, 50) }

        is Source.Season -> probe(
            SourceKind.SEASON,
            "season mid=" + parsed.mid + " id=" + parsed.seasonId
        ) { archive.seasonPage(parsed.mid, parsed.seasonId, 1, 50) }

        is Source.Up -> {
            val ok = runCatching { upLister.hasPlayableSeries(parsed.mid) }.getOrDefault(false)
            if (ok) {
                ResolvedSource(SourceKind.UP, emptyList(), "UP mid=" + parsed.mid + " 有可播合集")
            } else {
                ResolvedSource(SourceKind.UNKNOWN, emptyList(), "UP mid=" + parsed.mid + " 无可用合集")
            }
        }

        else -> ResolvedSource(SourceKind.UNKNOWN, emptyList(), "非合集/系列/UP 输入：" + parsed)
    }

    private fun probe(kind: SourceKind, note: String, fetch: () -> ArchivePage): ResolvedSource {
        val page = runCatching { fetch() }.getOrElse {
            return ResolvedSource(SourceKind.UNKNOWN, emptyList(), note + " 接口失败：" + (it.message ?: it.javaClass.simpleName))
        }
        return if (page.items.isEmpty()) {
            ResolvedSource(SourceKind.UNKNOWN, emptyList(), note + " 接口返回空列表")
        } else {
            ResolvedSource(kind, page.items, note + " 命中 " + page.items.size + " 条")
        }
    }
}
