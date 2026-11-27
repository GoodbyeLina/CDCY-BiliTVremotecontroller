package tvrc.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 调整后的节目单顺序必须落盘，重启后仍然按新顺序播放。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScheduleOrderPersistenceTest {

    @Test
    fun reorderedItemIdsSurviveRestart() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = Store(ctx)
        store.addSchedule(
            ScheduleItem(
                id = "", itemId = "a", title = "节目",
                hour = 20, minute = 0, days = emptySet(),
                itemIds = listOf("a", "b", "c")
            )
        )
        val id = store.schedules().single().id
        store.setScheduleItems(id, listOf("c", "a", "b"))

        val reloaded = Store(ctx).schedules().single()
        assertEquals(listOf("c", "a", "b"), reloaded.allItemIds)
        assertEquals("c", reloaded.itemId)
    }

    @Test
    fun emptyOrderIsIgnored() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = Store(ctx)
        store.addSchedule(
            ScheduleItem(
                id = "", itemId = "a", title = "节目",
                hour = 8, minute = 0, days = emptySet(),
                itemIds = listOf("a", "b")
            )
        )
        val id = store.schedules().single().id
        store.setScheduleItems(id, emptyList())
        assertEquals(listOf("a", "b"), Store(ctx).schedules().single().allItemIds)
    }
}
