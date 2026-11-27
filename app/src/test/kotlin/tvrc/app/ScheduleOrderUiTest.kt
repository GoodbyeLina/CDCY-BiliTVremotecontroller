package tvrc.app

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** 走一遍真实 UI：新建节目单 → 勾选 → ↑ 调整顺序 → 保存 → 校验 itemIds。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScheduleOrderUiTest {

    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()

    @Before
    fun setUp() {
        Ctl.store = null
        Ctl.service = null
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val store = Ctl.store!!
        store.upsert(LibItem(id = "", kind = ItemKind.VIDEO, title = "视频甲", bvid = "BV1xx411c7mD"))
        store.upsert(LibItem(id = "", kind = ItemKind.VIDEO, title = "视频乙", bvid = "BV1xx411c7mE"))
        store.upsert(LibItem(id = "", kind = ItemKind.VIDEO, title = "视频丙", bvid = "BV1xx411c7mF"))
        tap("节目单")
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        Ctl.store = null
        Ctl.service = null
    }

    @Test
    fun newScheduleSavesTheAdjustedOrder() {
        tap("新建")
        val pick = latestDialog()
        listOf("视频甲", "视频乙", "视频丙").forEach { title ->
            checkBox(pick, title).performClick()
        }
        clickPositive(pick)

        // 初始：甲、乙、丙。把“丙”上移两次 → 丙、甲、乙
        val ups = upChips(latestDialog())
        assertEquals(3, ups.size)
        ups[2].performClick()
        idle()

        val upsAfterFirst = upChips(latestDialog())
        upsAfterFirst[1].performClick()
        idle()

        clickPositive(latestDialog()) // 排序页“确定”
        val timeDialog = latestDialog()
        assertEquals("保存", timeDialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        clickPositive(timeDialog) // 节目单保存

        val saved = Ctl.store!!.schedules().single()
        val order = saved.allItemIds.map { Ctl.store!!.item(it)!!.title }
        assertEquals(listOf("视频丙", "视频甲", "视频乙"), order)
    }

    private fun upChips(dialog: AlertDialog): List<TextView> =
        descendants(dialog.window!!.decorView)
            .filterIsInstance<TextView>()
            .filter { it.text.toString() == "↑" }
            .toList()

    private fun checkBox(dialog: AlertDialog, title: String): CheckBox =
        descendants(dialog.window!!.decorView).filterIsInstance<CheckBox>()
            .first { it.text.toString().contains(title) }

    private fun clickPositive(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun latestDialog(): AlertDialog {
        idle()
        return ShadowAlertDialog.getLatestAlertDialog() ?: error("没有弹出对话框")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun tap(text: String) {
        val root = activity.findViewById(android.R.id.content) as View
        descendants(root).filterIsInstance<TextView>()
            .first { it.text.toString() == text && it.isClickable }
            .performClick()
        idle()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}
