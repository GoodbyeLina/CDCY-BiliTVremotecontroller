package tvrc.app

/**
 * 把列表中 [from] 位置的元素移动到 [to] 位置。
 * 越界、原地不动或列表为空时返回 false，列表保持不变。
 *
 * 节目单排序（↑/↓）和顺序播放的排序页共用这段逻辑，单独放出来便于单测。
 */
fun <T> MutableList<T>.moveItem(from: Int, to: Int): Boolean {
    if (from == to || from !in indices || to !in indices) return false
    add(to, removeAt(from))
    return true
}
