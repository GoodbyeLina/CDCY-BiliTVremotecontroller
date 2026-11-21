package tvrc.app

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xhdpi")
class DiagnosticsTest {
    private lateinit var controller: ActivityController<MainActivity>

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val store = Store(context)
        store.clearDiagnostics()
        Ctl.store = store
        Ctl.service = null
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        Ctl.store?.clearDiagnostics()
        Ctl.store = null
        Ctl.service = null
    }

    @Test
    fun tracesPersistWithStatusTargetAndElapsedTime() {
        val store = Ctl.store!!
        store.trace(
            "导入", "完成", TraceStatus.SUCCESS, "已导入 1 条（1 条直播）",
            "目标：直播 #26312812 · 6点比赛", 1_234
        )
        store.trace(
            "播放", "电视回执", TraceStatus.ERROR, "电视端播放失败",
            "详情：is.xyz.mpv activity not found", 2_345
        )

        val reloaded = Store(RuntimeEnvironment.getApplication())
        val events = reloaded.traces()
        assertEquals(2, events.size)
        assertEquals("播放", events[0].area)
        assertEquals(TraceStatus.ERROR, events[0].status)
        assertEquals(2_345L, events[0].elapsedMs)
        assertEquals("导入", events[1].area)
        assertTrue(events[1].details.contains("#26312812"))
    }

    @Test
    fun dialogShowsImportAndPlaybackFeedbackWithoutExporting() {
        Ctl.store!!.trace(
            "导入", "完成", TraceStatus.SUCCESS, "已导入 1 条（1 条视频）",
            "目标：视频 · 测试视频 · BV1TEST", 800
        )
        Ctl.store!!.trace(
            "播放", "电视回执", TraceStatus.SUCCESS, "电视已接受起播命令",
            "项目：BV1TEST", 1_200
        )
        openActivity()
        tap("设备")
        tap("查看诊断")

        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val content = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
            .joinToString("\n") { it.text?.toString().orEmpty() }
        assertTrue(content.contains("诊断记录"))
        assertTrue(content.contains("已导入 1 条"))
        assertTrue(content.contains("BV1TEST"))
        assertTrue(content.contains("电视已接受起播命令"))
        assertTrue(content.contains("成功"))
    }

    @Test
    fun clearingDiagnosticsDoesNotRemoveSavedContent() {
        val store = Ctl.store!!
        store.upsert(LibItem(id = "", kind = ItemKind.VIDEO, title = "保留的视频", bvid = "BV1KEEP"))
        store.trace("播放", "请求", TraceStatus.INFO, "开始播放")
        store.clearDiagnostics()
        assertTrue(store.traces().isEmpty())
        assertTrue(store.items().any { it.bvid == "BV1KEEP" })
    }

    @Test
    fun exportedDiagnosticContainsEnvironmentAndStructuredEvents() {
        Ctl.store!!.trace(
            "播放", "电视回执", TraceStatus.ERROR, "电视端播放失败",
            "目标：直播 #26312812\n详情：播放器未响应", 1_500
        )
        openActivity()
        tap("设备")
        tap("导出诊断")

        val activity = controller.get()
        val dir = activity.getExternalFilesDir(null) ?: activity.filesDir
        val report = dir.listFiles { f -> f.name.startsWith("diag-") && f.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
        assertNotNull(report)
        val text = report!!.readText()
        assertTrue(text.contains("SDK："))
        assertTrue(text.contains("--- 最近诊断事件 ---"))
        assertTrue(text.contains("电视端播放失败"))
        assertTrue(text.contains("#26312812"))
        assertTrue(text.contains("1.5 秒"))
    }

    private fun openActivity() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    private fun tap(text: String) {
        val root = controller.get().findViewById<View>(android.R.id.content)
        descendants(root).filterIsInstance<TextView>()
            .first { it.text.toString() == text && it.isClickable }
            .performClick()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}
