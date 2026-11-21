package tvrc.app

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xhdpi")
class PhoneLayoutTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var root: LinearLayout
    @Before fun open() {
        Ctl.store = null
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        root = controller.get().findViewById<View>(android.R.id.content).findViewWithTag("screenRoot")
    }
    @After fun close() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        Ctl.store = null
    }

    private fun applyInsets(keyboard: Int) {
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 70, 0, 48))
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
            .setVisible(WindowInsets.Type.ime(), keyboard > 0)
            .build()
        root.dispatchApplyWindowInsets(insets)
        root.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1746, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 786, 1746)
    }

    @Test fun contentStaysBetweenStatusBarAndGestureArea() {
        applyInsets(0)
        assertEquals(70, root.paddingTop)
        assertEquals(48, root.paddingBottom)
        assertTrue(root.getChildAt(0).top >= 70)
        assertTrue(root.getChildAt(root.childCount - 1).bottom <= 1746 - 48)
    }

    @Test fun keyboardInsetsKeepNavigationAccessibleAndRestoreOnDismiss() {
        applyInsets(600)
        assertEquals(600, root.paddingBottom)
        assertTrue(root.getChildAt(root.childCount - 1).bottom <= 1746 - 600)
        applyInsets(0)
        assertEquals(48, root.paddingBottom)
    }

    @Test fun titleAndVersionStayWithinTheHeaderWidth() {
        applyInsets(0)
        val header = root.getChildAt(0) as LinearLayout
        val titleRow = header.getChildAt(0) as LinearLayout
        val title = titleRow.getChildAt(0) as TextView
        val version = titleRow.getChildAt(titleRow.childCount - 1)
        assertTrue(title.measuredWidth > 0)
        assertTrue(version.right <= titleRow.measuredWidth)
        assertEquals(1, title.maxLines)
    }
}
