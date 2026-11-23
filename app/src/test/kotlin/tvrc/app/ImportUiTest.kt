package tvrc.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportUiTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val oldLink = "https://live.bilibili.com/26312812"
    private val newLink = "https://www.bilibili.com/video/BV1xx411c7mD"

    @Before
    fun openImportWithPreviousDraft() {
        Ctl.store = null
        Ctl.service = null
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Ctl.store!!.clearDiagnostics()
        // Seed the state left by an earlier import, without network requests.
        MainActivity::class.java.getDeclaredField("draft").apply {
            isAccessible = true
            set(activity, oldLink)
        }
        tap("节目单")
        tap("内容库")
        assertEquals(oldLink, input().text.toString())
    }

    @After
    fun closeActivity() {
        clipboard().clearPrimaryClip()
        controller.pause().stop().destroy()
        Ctl.store = null
        Ctl.service = null
    }

    @Test
    fun statusRefreshBetweenDeleteAndNativePasteKeepsFocusedViewAndNewText() {
        val field = input()
        field.requestFocus()
        field.setSelection(field.length())
        field.text.clear()
        refresh()
        assertSame(field, input())
        assertTrue(field.hasFocus())
        assertEquals("", field.text.toString())
        paste(newLink)
        refresh()
        assertSame(field, input())
        assertEquals(newLink, field.text.toString())
        assertEquals(field.length(), field.selectionStart)
    }

    @Test
    fun unFocusedRefreshKeepsReplacementAndDeletedDraft() {
        input().setText(newLink)
        input().clearFocus()
        refresh()
        assertEquals(newLink, input().text.toString())
        input().text.clear()
        input().clearFocus()
        refresh()
        assertEquals("", input().text.toString())
    }

    @Test
    fun switchingTabsKeepsWholePastedShareTextInsteadOfPreviousLink() {
        input().requestFocus()
        input().text.clear()
        val share = "新视频分享\n$newLink\n一起看"
        paste(share)
        tap("节目单")
        tap("内容库")
        assertEquals(share, input().text.toString())
    }

    @Test
    fun secondNativePasteReadsCurrentClipboardAndBlankSubmitDoesNotImportOldLink() {
        input().requestFocus()
        input().selectAll()
        paste(newLink)
        input().selectAll()
        paste(oldLink)
        assertEquals(oldLink, input().text.toString())
        input().text.clear()
        refresh()
        val before = Ctl.store!!.traces().count { it.area == "导入" && it.action == "识别输入" }
        tap("导入")
        assertEquals("", input().text.toString())
        assertEquals(before, Ctl.store!!.traces().count { it.area == "导入" && it.action == "识别输入" })
    }

    @Test
    fun submitUsesPastedReplacementAfterRefreshNotOldDraft() {
        input().requestFocus()
        input().text.clear()
        // Unsupported URL deliberately exercises the real submit boundary without B站/network I/O.
        val replacement = "https://example.invalid/new-import"
        paste(replacement)
        refresh()
        tap("导入")
        val started = Ctl.store!!.traces().first { it.area == "导入" && it.action == "识别输入" }
        assertEquals("输入：$replacement", started.details)
        assertEquals(replacement, input().text.toString())
    }

    private fun refresh() {
        Ctl.onStatusChanged(8100, alive = false, playing = false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clipboard() = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun paste(text: String) {
        clipboard().setPrimaryClip(ClipData.newPlainText("分享链接", text))
        assertTrue(input().onTextContextMenuItem(android.R.id.paste))
    }

    private fun input(): EditText = descendants(root()).filterIsInstance<EditText>()
        .first { it.hint?.toString() == "粘贴分享文案或网址" }

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
