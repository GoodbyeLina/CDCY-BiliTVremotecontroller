package tvrc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 节目单排序的核心逻辑：列表移动 + ScheduleItem 按 itemIds 顺序播放。 */
class PlaylistOrderTest {

    @Test
    fun movesItemUp() {
        val list = mutableListOf("甲", "乙", "丙")
        assertTrue(list.moveItem(2, 1))
        assertEquals(listOf("甲", "丙", "乙"), list)
    }

    @Test
    fun movesItemDown() {
        val list = mutableListOf("甲", "乙", "丙")
        assertTrue(list.moveItem(0, 1))
        assertEquals(listOf("乙", "甲", "丙"), list)
    }

    @Test
    fun firstCannotMoveUpAndListUnchanged() {
        val list = mutableListOf("甲", "乙")
        assertFalse(list.moveItem(0, -1))
        assertEquals(listOf("甲", "乙"), list)
    }

    @Test
    fun lastCannotMoveDownAndListUnchanged() {
        val list = mutableListOf("甲", "乙")
        assertFalse(list.moveItem(1, 2))
        assertEquals(listOf("甲", "乙"), list)
    }

    @Test
    fun outOfBoundsIsNoOp() {
        val list = mutableListOf("甲")
        assertFalse(list.moveItem(5, 0))
        assertFalse(list.moveItem(0, 5))
        assertFalse(list.moveItem(-1, 0))
        assertEquals(listOf("甲"), list)
    }

    @Test
    fun repeatedMovesProduceRequestedOrder() {
        val list = mutableListOf("甲", "乙", "丙", "丁")
        // 把“丁”移到最前，再把“丙”移到第二
        assertTrue(list.moveItem(3, 0))
        assertTrue(list.moveItem(3, 1))
        assertEquals(listOf("丁", "丙", "甲", "乙"), list)
    }

    @Test
    fun schedulePlaysInItemIdsOrder() {
        val s = ScheduleItem(
            id = "s1", itemId = "a", title = "节目",
            hour = 20, minute = 0, days = emptySet(),
            itemIds = listOf("c", "a", "b")
        )
        assertEquals(listOf("c", "a", "b"), s.allItemIds)
    }

    @Test
    fun legacyScheduleWithoutItemIdsFallsBackToSingleItem() {
        val s = ScheduleItem(
            id = "s2", itemId = "b", title = "旧节目",
            hour = 8, minute = 30, days = setOf(1, 3)
        )
        assertEquals(listOf("b"), s.allItemIds)
    }
}
