package tvrc.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * 内容库搜索的回归测试：输入字符只能刷新结果列表，
 * 不能整页重建——否则输入框被移除，焦点和输入法会一起消失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibrarySearchUiTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()

    @Before
    fun openWithTwoItems() {
        Ctl.store = null
        Ctl.service = null
        val store = Store(RuntimeEnvironment.getApplication())
        store.upsert(LibItem(id = "", kind = ItemKind.VIDEO, title = "猫和老鼠合集", bvid = "BV1aa411c7mD"))
        store.upsert(LibItem(id = "", kind = ItemKind.LIVE, title = "晚间直播", roomId = 26312812L))
        Ctl.store = store
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @After
    fun closeActivity() {
        controller.pause().stop().destroy()
        Ctl.store = null
        Ctl.service = null
    }

    @Test
    fun typingKeepsSameFieldAndFocusAndFiltersResults() {
        val field = search()
        field.requestFocus()
        assertTrue(field.hasFocus())

        field.append("猫")

        assertSame("输入后搜索框不能被重建", field, search())
        assertTrue("输入后搜索框必须保留焦点", field.hasFocus())
        assertTrue(hasText("猫和老鼠合集"))
        assertFalse(hasText("晚间直播"))
    }

    @Test
    fun statusRefreshWhileSearchingKeepsFocusedFieldAndFilter() {
        val field = search()
        field.requestFocus()
        field.append("直播")
        assertTrue(hasText("晚间直播"))

        Ctl.onStatusChanged(8100, alive = false, playing = false)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame("状态刷新不能重建正在编辑的搜索框", field, search())
        assertTrue(field.hasFocus())
        assertTrue(hasText("晚间直播"))
        assertFalse(hasText("猫和老鼠合集"))
    }

    private fun search(): EditText = descendants(root()).filterIsInstance<EditText>()
        .first { it.hint?.toString() == "搜索标题 / BV / 房间号" }

    private fun hasText(text: String): Boolean = descendants(root()).filterIsInstance<TextView>()
        .any { it.text.toString() == text }

    private fun root(): View = activity.findViewById(android.R.id.content)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}
