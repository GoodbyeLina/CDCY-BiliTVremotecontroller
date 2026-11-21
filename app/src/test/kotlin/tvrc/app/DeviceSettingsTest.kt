package tvrc.app

import android.os.Bundle
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceSettingsTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()

    @Before
    fun openDeviceSettings() {
        Ctl.store = null
        Ctl.service = null
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        tap("设备")
    }

    @After
    fun closeActivity() {
        controller.pause().stop().destroy()
        Ctl.store = null
        Ctl.service = null
    }

    @Test
    fun savesTvIpWithAnEmptyContentLibraryAndReloadsIt() {
        input("电视 IP").setText("192.168.10.139")
        tap("保存配置")
        assertEquals("192.168.10.139", Ctl.store!!.config().tvHost)
        assertTrue(Ctl.store!!.configProblems().isEmpty())
        assertTrue(Ctl.store!!.items().isEmpty())
        input("电视 IP").setText("192.168.10.140")
        tap("重新加载")
        assertEquals("192.168.10.139", input("电视 IP").text.toString())
        assertEquals("192.168.10.139", Store(activity).config().tvHost)
    }

    @Test
    fun validationFailureKeepsInputAndAllowsCorrection() {
        input("电视 IP").setText("192.168.10.139")
        input("代理端口").setText("8099")
        tap("保存配置")
        assertEquals("192.168.10.139", input("电视 IP").text.toString())
        assertEquals("8099", input("代理端口").text.toString())
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss()
        input("代理端口").setText("8100")
        tap("保存配置")
        assertEquals("192.168.10.139", Ctl.store!!.config().tvHost)
        assertTrue(Ctl.store!!.configProblems().isEmpty())
    }

    @Test
    fun backgroundRefreshKeepsTheSameFocusedInput() {
        val field = input("电视 IP")
        field.requestFocus()
        field.setText("192.168.10.")
        field.setSelection(field.length())
        Ctl.onStatusChanged(8100, alive = false, playing = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(field, input("电视 IP"))
        assertTrue(field.hasFocus())
        assertEquals("192.168.10.", field.text.toString())
        assertEquals(field.length(), field.selectionStart)
    }

    @Test
    fun switchingTabsKeepsUnsavedSettings() {
        input("电视 IP").setText("192.168.10.139")
        tap("内容库")
        tap("设备")
        assertEquals("192.168.10.139", input("电视 IP").text.toString())
    }

    @Test
    fun activityRecreationKeepsTheDeviceTabAndDraft() {
        input("电视 IP").setText("192.168.10.139")
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        assertEquals("192.168.10.139", input("电视 IP").text.toString())
    }

    private fun input(name: String): EditText =
        activity.findViewById<View>(android.R.id.content).findViewWithTag(name)

    private fun tap(text: String) {
        val root = activity.findViewById<View>(android.R.id.content)
        val target = descendants(root).filterIsInstance<TextView>()
            .first { it.text.toString() == text && it.isClickable }
        target.performClick()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}
