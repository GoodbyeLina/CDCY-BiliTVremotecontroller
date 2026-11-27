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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * 监控页「添加房间」输入框的同类回归：后台状态刷新不能整页重建正在编辑的输入框。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MonitorInputUiTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()

    @Before
    fun openMonitorTab() {
        Ctl.store = null
        Ctl.service = null
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        tap("监控")
    }

    @After
    fun closeActivity() {
        controller.pause().stop().destroy()
        Ctl.store = null
        Ctl.service = null
    }

    @Test
    fun statusRefreshKeepsFocusedRoomField() {
        val field = roomField()
        field.requestFocus()
        field.append("612978")

        Ctl.onStatusChanged(8100, alive = false, playing = false)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame("状态刷新不能重建正在编辑的输入框", field, roomField())
        assertTrue(field.hasFocus())
        assertEquals("612978", field.text.toString())
    }

    private fun roomField(): EditText = descendants(root()).filterIsInstance<EditText>()
        .first { it.hint?.toString() == "房间号或直播间链接" }

    private fun root(): View = activity.findViewById(android.R.id.content)

    private fun tap(text: String) {
        descendants(root()).filterIsInstance<TextView>()
            .first { it.text.toString() == text && it.isClickable }.performClick()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}
