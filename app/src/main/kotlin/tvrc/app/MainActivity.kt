package tvrc.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import tvrc.core.TvrcVersion
import tvrc.core.bili.RealBiliArchiveApi
import tvrc.core.bili.RealBiliLiveApi
import tvrc.core.bili.SourceExpander
import tvrc.core.bili.UrlConnectionHttpText
import tvrc.core.bili.UrlConnectionRedirectFollower
import tvrc.core.config.AppConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 唯一 Activity（四个人工页 + 底部导航）。
 *
 * 为什么全手写 View：见 Ui.kt 头注释（零第三方依赖）。这里只做「渲染当前页」，
 * 状态一律从 [Ctl] 与 [Store] 现取 —— Activity 不持有业务状态，转屏/回收都不会乱。
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_PICK_TEXT = 1001
        const val MAX_IMPORT_BYTES = 1024 * 1024
    }

    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var statusIndicator: TextView
    private lateinit var statusText: TextView

    private var tab = 0
    private var filter = "全部"
    private var searchQuery = ""
    private var draft = ""
    private var importField: EditText? = null
    private var searchField: EditText? = null
    private var monitorField: EditText? = null
    private var libraryChips: LinearLayout? = null
    private var libraryList: LinearLayout? = null
    private var importing = false
    private var importMessage = ""
    private var selfTest: List<SelfTest.Item> = emptyList()
    private var selfTesting = false
    private var deviceDraft = Bundle()

    // 状态探针不能重建正在编辑的表单，否则焦点、键盘和系统粘贴目标都会丢失。
    private val refreshCb: () -> Unit = { runOnUiThread {
        val editingForm = importField?.hasFocus() == true ||
            searchField?.hasFocus() == true ||
            monitorField?.hasFocus() == true
        if (tab == 3 || editingForm) updateStatus() else render()
    } }
    private val tabs = listOf("内容库", "节目单", "监控", "设备")
    private val filters = listOf("全部", "视频", "合集", "直播", "收藏")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab", 0) ?: 0
        deviceDraft = savedInstanceState?.getBundle("deviceDraft") ?: Bundle()
        store = Ctl.store ?: Store(this).also { Ctl.store = it }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(T.BG))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        buildRoot()
        askNotificationPermission()
        render()
    }

    override fun onStart() {
        super.onStart()
        Ctl.onChange(refreshCb)
        runCatching { PlaybackService.ensureRunning(this) }
        render()
    }

    override fun onStop() {
        Ctl.offChange(refreshCb)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        outState.putBundle("deviceDraft", Bundle(deviceDraft))
        super.onSaveInstanceState(outState)
    }

    // ---------------------------------------------------------------- 骨架

    private fun buildRoot() {
        val root = LinearLayout(this).apply {
            tag = "screenRoot"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.BG)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    val keyboard = insets.getInsets(WindowInsets.Type.ime())
                    view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        root.addView(topBar())

        val sv = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        sv.addView(content)
        root.addView(sv)

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(T.CARD)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.addView(navBar)

        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
        root.requestApplyInsets()
    }

    private fun topBar(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.CARD)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val r = row()
        r.addView(label("B站直播遥控器", 18f, T.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        r.addView(hpad(8))
        r.addView(badge("v" + TvrcVersion.APP, T.ACCENT))
        wrap.addView(r)
        val status = row()
        statusIndicator = statusDot(T.DIM)
        status.addView(statusIndicator)
        status.addView(hpad(6))
        statusText = label("", 12f, T.DIM).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        status.addView(statusText)
        wrap.addView(vpad(6))
        wrap.addView(status)
        updateStatus()
        return wrap
    }

    private fun updateStatus() {
        statusIndicator.setTextColor(if (Ctl.proxyPort > 0) T.OK else T.DIM)
        val line = buildString {
            append(Ctl.stateText())
            if (Ctl.playingTitle.isNotBlank()) append(" · ").append(Ctl.playingTitle)
            append("\n代理 ").append(if (Ctl.proxyPort > 0) ":" + Ctl.proxyPort else "未启动")
            append("　电视 ").append(if (Ctl.tvAlive) "在线" else "离线")
            if (Ctl.mediaText.isNotBlank()) append("\n").append(Ctl.mediaText)
        }
        statusText.text = line
    }

    private fun render() {
        // 正在编辑搜索框时不能重建整页：输入框被 removeAllViews 移除后，焦点、输入法
        // 和系统粘贴目标都会丢。只重画筛选块与结果列表，其它状态变化仍然即时可见。
        if (tab == 0 && searchField?.hasFocus() == true) {
            updateStatus()
            renderLibraryResults()
            return
        }
        updateStatus()
        content.removeAllViews()
        importField = null
        searchField = null
        monitorField = null
        libraryChips = null
        libraryList = null
        when (tab) {
            0 -> renderHome()
            1 -> renderSchedule()
            2 -> renderMonitor()
            3 -> renderDevice()
        }
        renderNav()
    }

    private fun renderNav() {
        navBar.removeAllViews()
        tabs.forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (i == tab) T.ACCENT else T.DIM)
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                setOnClickListener {
                    if (tab != i) {
                        tab = i
                        render()
                    }
                }
            }
            navBar.addView(tv)
        }
    }

    private fun section(text: String): View {
        val tv = label(text, 13f, T.DIM, true)
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        return tv
    }

    // ---------------------------------------------------------------- 内容库

    private fun renderHome() {
        content.addView(statusCard())
        content.addView(importCard())
        content.addView(vpad(4))

        libraryChips = row()
        content.addView(libraryChips)
        renderLibraryFilter()
        content.addView(vpad(8))

        // 搜索（R-06）：标题 / BV / 房间号都能命中；本地过滤，零网络。
        // 输入时只刷新结果列表，不重建整页——否则输入框会被移除，焦点和输入法一起消失。
        val searchRow = row()
        val sInput = EditText(this).apply {
            hint = "搜索标题 / BV / 房间号"
            setText(searchQuery)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(T.TEXT)
            setHintTextColor(T.DIM)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(T.CARD_HI, 12, 1, T.LINE)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString()?.trim() ?: ""
                    if (q != searchQuery) {
                        searchQuery = q
                        renderLibraryResults()
                    }
                }
            })
        }
        searchField = sInput
        searchRow.addView(sInput)
        searchRow.addView(hpad(8))
        searchRow.addView(button("清空", false) {
            searchQuery = ""
            searchField?.setText("")
            renderLibraryResults()
        })
        content.addView(searchRow)
        content.addView(vpad(8))

        libraryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(libraryList)
        renderLibraryResults()
    }

    private fun renderLibraryFilter() {
        val chips = libraryChips ?: return
        chips.removeAllViews()
        filters.forEach { f ->
            chips.addView(chip(f, filter == f) {
                filter = f
                renderLibraryFilter()
                renderLibraryResults()
            })
            chips.addView(hpad(6))
        }
    }

    /** 只重建结果列表；搜索框本身保持原对象，焦点、光标和输入法都不受影响。 */
    private fun renderLibraryResults() {
        val list = libraryList ?: return
        list.removeAllViews()
        val items = store.items()
            .filter { item ->
                when (filter) {
                    "视频" -> item.kind == ItemKind.VIDEO
                    "合集" -> item.kind == ItemKind.SERIES
                    "直播" -> item.kind == ItemKind.LIVE
                    "收藏" -> item.favorite
                    else -> true
                }
            }
            .filter { searchQuery.isBlank() || it.searchKey().contains(searchQuery.lowercase()) }

        if (items.isEmpty()) {
            list.addView(emptyState(
                if (store.items().isEmpty()) "内容库还是空的" else "没有符合筛选的条目",
                "把 B站 分享链接粘到上面的输入框，点「导入」"
            ))
            return
        }
        items.forEach { list.addView(itemCard(it)) }
    }

    private fun statusCard(): View {
        val c = card()
        val r = row()
        r.addView(label("运行状态", 15f, T.TEXT, true))
        r.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        r.addView(badge(if (Ctl.proxyPort > 0) "服务运行中" else "服务未启动", if (Ctl.proxyPort > 0) T.OK else T.DIM))
        c.addView(r)
        c.addView(vpad(6))
        c.addView(label(Ctl.stateText() + if (Ctl.playingTitle.isNotBlank()) " · " + Ctl.playingTitle else "", 13f, T.TEXT))
        c.addView(vpad(4))
        val info = buildString {
            append("代理：")
            append(if (Ctl.proxyPort > 0) "http://" + LanAddress.localIpv4() + ":" + Ctl.proxyPort else "未启动")
            append("\n电视：")
            append(if (Ctl.tvAlive) "在线" else "离线")
            if (Ctl.tvPlaying) append("（正在播放）")
        }
        c.addView(label(info, 12f, T.DIM))
        c.addView(vpad(10))
        val btns = row()
        btns.addView(button("停止播放", false) { PlaybackService.stopPlayback(this); toast("已发送停止") })
        btns.addView(hpad(8))
        btns.addView(button("打开状态页", false) {
            if (Ctl.proxyPort > 0) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + Ctl.proxyPort + "/status"))) }
                    .onFailure { toast("没有可用浏览器") }
            } else toast("服务还没启动")
        })
        c.addView(btns)
        return c
    }

    private fun importCard(): View {
        val c = card()
        c.addView(label("导入内容", 15f, T.TEXT, true))
        c.addView(vpad(4))
        c.addView(label("支持：房间号 / live.bilibili.com 网址 / BV 号 / 合集链接 / b23.tv 短链 / 整段分享文案。可一次粘贴多条（每行一条），也可以从 txt 文档导入。", 11f, T.DIM))
        c.addView(vpad(8))
        val input = EditText(this).apply {
            hint = "粘贴分享文案或网址"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(T.TEXT)
            setHintTextColor(T.DIM)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(T.CARD_HI, 12, 1, T.LINE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 8
            setText(draft)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // 清空也是有效草稿；不能等点击导入才同步，否则刷新会恢复上次的链接。
                    draft = s?.toString().orEmpty()
                }
            })
        }
        importField = input
        c.addView(input)
        c.addView(vpad(8))
        val r = row()
        r.addView(button(if (importing) "导入中…" else "导入", true) {
            val raw = input.text.toString().trim()
            if (raw.isEmpty()) {
                toast("先粘贴一个链接")
            } else {
                draft = raw
                doImport(raw)
            }
        })
        r.addView(hpad(8))
        r.addView(button("从文件导入", false) { pickTextFile() })
        r.addView(hpad(8))
        if (importMessage.isNotBlank()) r.addView(label(importMessage, 11f, T.DIM))
        c.addView(r)
        return c
    }

    private fun pickTextFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        runCatching { startActivityForResult(intent, REQ_PICK_TEXT) }
            .onFailure { toast("没有可用的文件选择器") }
    }

    @Deprecated("Android 原生 Activity，沿用 startActivityForResult 避免引入 AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_TEXT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Ctl.bg {
            val text = runCatching { readTextFile(uri, MAX_IMPORT_BYTES) }.getOrNull()
            runOnUiThread {
                if (text.isNullOrBlank()) {
                    toast("文件是空的或不是 UTF-8 文本")
                    return@runOnUiThread
                }
                toast("已读取 " + LinkBatch.split(text).size + " 个链接，开始导入")
                draft = text
                render()
                doImport(text)
            }
        }
    }

    private fun readTextFile(uri: Uri, maxBytes: Int): String {
        val input = contentResolver.openInputStream(uri) ?: return ""
        return input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < maxBytes) {
                val n = stream.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, minOf(n, maxBytes - out.size()))
            }
            out.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun itemCard(item: LibItem): View {
        val c = card(12)
        val r1 = row()
        r1.addView(badge(item.kindLabel, when (item.kind) {
            ItemKind.LIVE -> T.ERR
            ItemKind.SERIES -> T.WARN
            else -> T.ACCENT
        }))
        r1.addView(hpad(8))
        r1.addView(label(item.title, 15f, T.TEXT, true))
        if (item.favorite) {
            r1.addView(hpad(6))
            r1.addView(label("★", 13f, T.WARN))
        }
        c.addView(r1)
        if (item.subtitle.isNotBlank()) {
            c.addView(vpad(4))
            c.addView(label(item.subtitle, 12f, T.DIM))
        }
        c.addView(vpad(8))
        val actions = row()
        actions.addView(button("播放", true) {
            PlaybackService.playItem(this, item.id)
            toast("正在拉起：" + item.title)
        })
        actions.addView(hpad(8))
        actions.addView(button(if (item.favorite) "取消收藏" else "收藏", false) {
            store.setFavorite(item.id, !item.favorite)
            renderLibraryResults()
        })
        actions.addView(hpad(8))
        actions.addView(button("删除", false) {
            confirm("删除「" + item.title + "」？", "同时会删掉引用它的节目单") {
                store.removeItem(item.id)
                renderLibraryResults()
            }
        })
        c.addView(actions)
        return c
    }

    private fun doImport(raw: String) {
        if (importing) return
        importing = true
        importMessage = "正在识别…"
        val startedAt = System.currentTimeMillis()
        val links = LinkBatch.split(raw)
        store.trace(
            "导入", "识别输入", TraceStatus.INFO,
            if (links.size > 1) "开始识别 " + links.size + " 个链接" else "开始识别分享文案或链接",
            "输入：" + raw.take(800)
        )
        render()
        Ctl.bg {
            val importer = LinkImporter(
                SourceExpander(UrlConnectionRedirectFollower()),
                RealBiliLiveApi(UrlConnectionHttpText()),
                RealBiliArchiveApi(UrlConnectionHttpText()),
                ViewInfoApi(UrlConnectionHttpText())
            )
            val saved = mutableListOf<LibItem>()
            val notes = mutableListOf<String>()
            val failures = mutableListOf<String>()
            for (link in links) {
                val outcome = try {
                    importer.import(link)
                } catch (e: Throwable) {
                    ImportOutcome.Fail(e.message ?: e.javaClass.simpleName)
                }
                when (outcome) {
                    is ImportOutcome.Ok -> {
                        saved += outcome.items.map { store.upsert(it) }
                        notes += outcome.note
                    }
                    is ImportOutcome.Fail -> failures += outcome.reason
                }
            }
            runOnUiThread {
                importing = false
                val savedItems = saved.distinctBy { it.id }
                if (savedItems.isNotEmpty()) {
                    val grouped = savedItems.groupingBy { it.kindLabel }.eachCount()
                        .entries.joinToString("、") { it.value.toString() + " 条" + it.key }
                    val failNote = if (failures.isEmpty()) "" else "，失败 " + failures.size + " 个"
                    store.log("import", "批量导入 " + savedItems.size + " 条" + failNote + " → " + savedItems.joinToString { it.title })
                    store.trace(
                        "导入", "完成",
                        if (failures.isEmpty()) TraceStatus.SUCCESS else TraceStatus.WARNING,
                        "已导入 " + savedItems.size + " 条（" + grouped + "）" +
                            if (links.size > 1) "，共识别 " + links.size + " 个链接" + failNote else "",
                        buildString {
                            append(notes.distinct().joinToString("\n"))
                            savedItems.forEach { item ->
                                append("\n目标：").append(diagnosticTarget(item))
                            }
                            failures.forEach { append("\n失败：").append(it) }
                        },
                        System.currentTimeMillis() - startedAt
                    )
                    importMessage = "已导入 " + savedItems.size + " 条" + failNote
                    toast("已导入 " + savedItems.size + " 条" + failNote)
                } else {
                    val reason = failures.firstOrNull() ?: "没有识别到可导入的链接"
                    store.trace(
                        "导入", "完成", TraceStatus.ERROR, reason,
                        "输入：" + raw.take(800) +
                            if (failures.size > 1) "\n其他失败：\n" + failures.drop(1).joinToString("\n") else "",
                        System.currentTimeMillis() - startedAt
                    )
                    importMessage = "导入失败：" + reason
                    toast(reason)
                }
                render()
            }
        }
    }

    // ---------------------------------------------------------------- 节目单

    private fun renderSchedule() {
        val head = row()
        head.addView(label("节目单", 15f, T.TEXT, true))
        head.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        head.addView(button("顺序播放", false) { startPlaylistDialog() })
        head.addView(hpad(6))
        head.addView(button("新建", true) { addScheduleDialog() })
        content.addView(head)
        content.addView(vpad(6))

        if (store.schedules().isEmpty()) {
            content.addView(emptyState(
                "还没有节目单",
                "点「新建」勾选多条内容，到点按顺序播放；急用就点「顺序播放」马上开始"
            ))
        } else {
            store.schedules().forEach { content.addView(scheduleCard(it)) }
        }

        content.addView(section("执行记录"))
        val runs = store.runs().filter { it.kind == "schedule" || it.kind == "play" || it.kind == "monitor" }.take(15)
        if (runs.isEmpty()) {
            content.addView(label("暂无记录", 12f, T.DIM))
        } else {
            runs.forEach { content.addView(runRow(it)) }
        }
    }

    private fun scheduleCard(s: ScheduleItem): View {
        val c = card(12)
        val ids = s.allItemIds
        val r = row()
        r.addView(label(s.timeText(), 22f, if (s.enabled) T.ACCENT else T.DIM, true))
        r.addView(hpad(12))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(s.title, 14f, T.TEXT, true))
        col.addView(label(
            s.daysText() + (if (s.wake) " · 先唤醒电视" else "") +
                (if (ids.size > 1) " · " + ids.size + " 个内容" else ""),
            11f, T.DIM
        ))
        r.addView(col)
        r.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val sw = Switch(this).apply {
            isChecked = s.enabled
            setOnCheckedChangeListener { _, v -> store.setScheduleEnabled(s.id, v); render() }
        }
        r.addView(sw)
        c.addView(r)
        c.addView(vpad(6))
        if (ids.size > 1) {
            // 卡片上直接展示当前播放顺序，避免“排了但看不见”。
            ids.take(5).forEachIndexed { index, id ->
                val t = store.item(id)?.title ?: "（已删除）"
                c.addView(label((index + 1).toString() + ". " + t, 11f, T.DIM))
            }
            if (ids.size > 5) c.addView(label("… 共 " + ids.size + " 个内容", 11f, T.DIM))
            c.addView(vpad(6))
        }
        val actions = row()
        if (ids.size > 1) {
            actions.addView(button("调整顺序", false) {
                val libItems = ids.mapNotNull { store.item(it) }
                if (libItems.size < 2) {
                    toast("内容不足 2 个，无法调整")
                } else {
                    orderItemsDialog("调整顺序 · " + s.title, libItems) { ordered ->
                        store.setScheduleItems(s.id, ordered.map { it.id })
                        store.log("schedule", "调整顺序：" + s.title + " → " +
                            ordered.joinToString(" > ") { it.title })
                        toast("顺序已保存")
                        render()
                    }
                }
            })
            actions.addView(hpad(8))
        }
        actions.addView(button("立即执行", false) {
            if (ids.size > 1) PlaybackService.playPlaylist(this, ids, "schedule")
            else ids.firstOrNull()?.let { PlaybackService.playItem(this, it, "schedule") }
            toast("已触发：" + s.title)
        })
        actions.addView(hpad(8))
        actions.addView(button("删除", false) {
            store.removeSchedule(s.id)
            render()
        })
        c.addView(actions)
        return c
    }

    private fun startPlaylistDialog() {
        pickItemsDialog("顺序播放", "下一步") { chosen ->
            orderItemsDialog("调整播放顺序", chosen) { ordered ->
                PlaybackService.playPlaylist(this, ordered.map { it.id }, "manual")
                toast("已开始顺序播放：" + ordered.size + " 个内容")
            }
        }
    }

    private fun addScheduleDialog() {
        pickItemsDialog("新建节目单", "下一步") { chosen ->
            orderItemsDialog("调整播放顺序", chosen) { ordered ->
                showScheduleTimeDialog(ordered)
            }
        }
    }

    /**
     * 已选内容的播放顺序编辑：↑/↓ 移动，确定后按当前顺序播放。
     * 合集内部仍按 B站剧集顺序展开，这里只调整「内容与内容之间」的先后。
     */
    private fun orderItemsDialog(title: String, initial: List<LibItem>, onConfirm: (List<LibItem>) -> Unit) {
        if (initial.size <= 1) {
            onConfirm(initial)
            return
        }
        val order = initial.toMutableList()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        box.addView(label("按下面的顺序播放；点 ↑ ↓ 调整先后。", 11f, T.DIM))
        box.addView(vpad(6))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            )
        }
        box.addView(scroll)

        fun rebuild() {
            list.removeAllViews()
            order.forEachIndexed { index, item ->
                val r = row()
                r.addView(label((index + 1).toString() + ".", 12f, T.DIM))
                r.addView(hpad(6))
                val name = label(
                    item.title + (if (item.kind == ItemKind.SERIES) "（" + item.bvids.size + " 集）" else ""),
                    13f, T.TEXT
                )
                name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                name.maxLines = 2
                name.ellipsize = TextUtils.TruncateAt.END
                r.addView(name)
                val up = chip("↑", false) {
                    if (order.moveItem(index, index - 1)) rebuild()
                }
                val down = chip("↓", false) {
                    if (order.moveItem(index, index + 1)) rebuild()
                }
                if (index == 0) up.alpha = 0.35f
                if (index == order.size - 1) down.alpha = 0.35f
                r.addView(up)
                r.addView(hpad(4))
                r.addView(down)
                list.addView(r)
                list.addView(vpad(4))
            }
        }
        rebuild()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onConfirm(order.toList()) }
            .show()
    }

    /**
     * 多选内容库条目；勾选顺序就是播放顺序，合集会在播放服务里自动展开成每一集。
     */
    private fun pickItemsDialog(title: String, positive: String, onPick: (List<LibItem>) -> Unit) {
        val items = store.items()
        if (items.isEmpty()) {
            toast("先去内容库导入内容")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        box.addView(label("先勾选内容，下一步可调整播放顺序；合集会自动展开为每一集。", 11f, T.DIM))
        box.addView(vpad(6))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val picked = LinkedHashSet<String>()
        val checks = mutableListOf<CheckBox>()
        items.forEach { item ->
            val cb = CheckBox(this).apply {
                text = item.kindLabel + " · " + item.title +
                    (if (item.kind == ItemKind.SERIES) "（" + item.bvids.size + " 集）" else "")
                setTextColor(T.TEXT)
                textSize = 14f
                setOnCheckedChangeListener { _, checked ->
                    if (checked) picked += item.id else picked -= item.id
                }
            }
            checks += cb
            list.addView(cb)
        }
        val bulk = row()
        bulk.addView(button("全选", false) { checks.forEach { it.isChecked = true } })
        bulk.addView(hpad(6))
        bulk.addView(button("清空", false) { checks.forEach { it.isChecked = false } })
        box.addView(bulk)
        box.addView(vpad(4))
        val scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            )
        }
        box.addView(scroll)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton(positive) { _, _ ->
                val chosen = picked.mapNotNull { id -> items.firstOrNull { it.id == id } }
                if (chosen.isEmpty()) toast("至少选一条")
                else onPick(chosen)
            }
            .show()
    }

    private fun showScheduleTimeDialog(chosen: List<LibItem>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val episodes = chosen.sumOf { it.episodeCount }
        box.addView(label(
            if (chosen.size == 1) "内容：" + chosen.first().title
            else "内容：" + chosen.size + " 个，共 " + episodes + " 集",
            12f, T.DIM
        ))

        box.addView(vpad(10))
        box.addView(label("时间", 12f, T.DIM))
        val timeBtn = button("20:00", false) { }
        var hour = 20
        var minute = 0
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                hour = h; minute = m
                timeBtn.text = "%02d:%02d".format(h, m)
            }, hour, minute, true).show()
        }
        box.addView(timeBtn)

        box.addView(vpad(10))
        box.addView(label("重复（不选=每天）", 12f, T.DIM))
        val daysRow = row()
        val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
        val picked = mutableSetOf<Int>()
        dayNames.forEachIndexed { idx, name ->
            val dayChip = chip(name, false) { }
            dayChip.setOnClickListener {
                val d = idx + 1
                if (!picked.add(d)) picked.remove(d)
                dayChip.background = if (d in picked) rounded(T.ACCENT, 20) else rounded(T.CARD_HI, 20, 1, T.LINE)
                dayChip.setTextColor(if (d in picked) 0xFF141414.toInt() else T.DIM)
            }
            daysRow.addView(dayChip)
            daysRow.addView(hpad(4))
        }
        box.addView(daysRow)

        box.addView(vpad(10))
        val wakeRow = row()
        wakeRow.addView(label("先唤醒电视", 13f, T.TEXT))
        wakeRow.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val wakeSw = Switch(this).apply { isChecked = true }
        wakeRow.addView(wakeSw)
        box.addView(wakeRow)

        val title = if (chosen.size == 1) chosen.first().title
        else chosen.size.toString() + " 个内容 · 共 " + episodes + " 集"
        AlertDialog.Builder(this)
            .setTitle("新建节目单")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                store.addSchedule(
                    ScheduleItem(
                        id = "", itemId = chosen.first().id, title = title,
                        hour = hour, minute = minute, days = picked.toSet(), wake = wakeSw.isChecked,
                        itemIds = chosen.map { it.id }
                    )
                )
                store.log("schedule", "新增：" + title + " " + "%02d:%02d".format(hour, minute) +
                    "（" + chosen.size + " 个内容 / " + episodes + " 集）")
                render()
            }
            .show()
    }

    // ---------------------------------------------------------------- 监控

    private fun renderMonitor() {
        val head = row()
        head.addView(label("开播监控", 15f, T.TEXT, true))
        head.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val sw = Switch(this).apply {
            isChecked = store.monitorEnabled
            setOnCheckedChangeListener { _, v ->
                store.setMonitorSwitch(v)
                Ctl.service?.let { PlaybackService.ensureRunning(this@MainActivity) }
                render()
            }
        }
        head.addView(sw)
        content.addView(head)
        content.addView(vpad(4))
        content.addView(label("开关打开后每 " + store.pollSeconds + " 秒轮询一次；有房间在播自动降频省电", 11f, T.DIM))
        content.addView(vpad(8))

        val addRow = row()
        val input = EditText(this).apply {
            hint = "房间号或直播间链接"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(T.TEXT)
            setHintTextColor(T.DIM)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(T.CARD_HI, 12, 1, T.LINE)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        monitorField = input
        addRow.addView(input)
        addRow.addView(hpad(8))
        addRow.addView(button("添加", true) {
            val raw = input.text.toString().trim()
            if (raw.isEmpty()) { toast("填房间号"); return@button }
            Ctl.bg {
                val source = SourceExpander(UrlConnectionRedirectFollower()).expand(raw).source
                if (source !is tvrc.core.bili.Source.LiveRoom) {
                    runOnUiThread { toast("不是直播间：填房间号或直播间网址") }
                    return@bg
                }
                val info = runCatching { RealBiliLiveApi(UrlConnectionHttpText()).roomInfo(source.roomId) }.getOrNull()
                store.addMonitor(
                    MonitorItem(
                        roomId = source.roomId,
                        title = info?.title?.takeIf { it.isNotBlank() } ?: ("直播间 " + source.roomId),
                        lastLive = info?.live ?: false
                    )
                )
                runOnUiThread { render(); toast("已加入监控") }
            }
        })
        content.addView(addRow)
        content.addView(vpad(10))

        if (store.monitors().isEmpty()) {
            content.addView(emptyState("还没有监控房间", "加一个关注的直播间，开播时会提醒（可设为重点抢播）"))
        } else {
            store.monitors().forEach { content.addView(monitorCard(it)) }
        }

        content.addView(section("监控日志"))
        val runs = store.runs().filter { it.kind == "monitor" }.take(12)
        if (runs.isEmpty()) content.addView(label("暂无记录", 12f, T.DIM)) else runs.forEach { content.addView(runRow(it)) }
    }

    private fun monitorCard(m: MonitorItem): View {
        val c = card(12)
        val r = row()
        r.addView(statusDot(if (m.lastLive) T.ERR else T.DIM))
        r.addView(hpad(6))
        r.addView(label(m.title, 14f, T.TEXT, true))
        r.addView(hpad(6))
        r.addView(badge(if (m.lastLive) "直播中" else "未开播", if (m.lastLive) T.ERR else T.DIM))
        if (m.priority > 0) {
            r.addView(hpad(4))
            r.addView(badge("重点", T.WARN))
        }
        c.addView(r)
        c.addView(vpad(2))
        c.addView(label("房间 " + m.roomId, 11f, T.DIM))
        c.addView(vpad(8))

        c.addView(toggleRow("启用", m.enabled) { v -> store.updateMonitor(m.copy(enabled = v)); render() })
        c.addView(toggleRow("重点抢播（会打断当前播放）", m.priority > 0) { v ->
            store.updateMonitor(m.copy(priority = if (v) 1 else 0)); render()
        })
        c.addView(toggleRow("允许覆盖手动停止", m.allowOverride) { v ->
            store.updateMonitor(m.copy(allowOverride = v)); render()
        })
        c.addView(toggleRow("抢播前先唤醒电视", m.autoWake) { v ->
            store.updateMonitor(m.copy(autoWake = v)); render()
        })
        c.addView(vpad(6))
        val actions = row()
        actions.addView(button("立即播放", false) {
            PlaybackService.playRoom(this, m.roomId, "monitor")
            toast("尝试播放：" + m.title)
        })
        actions.addView(hpad(8))
        actions.addView(button("删除", false) { store.removeMonitor(m.roomId); render() })
        c.addView(actions)
        return c
    }

    private fun toggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val r = row()
        r.setPadding(0, dp(4), 0, dp(4))
        r.addView(label(text, 13f, T.TEXT))
        r.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        r.addView(sw)
        return r
    }

    // ---------------------------------------------------------------- 设备

    private fun renderDevice() {
        configInputs.clear()
        val cfg = store.config()
        content.addView(section("电视与代理"))

        val card1 = card()
        card1.addView(field("电视 IP", cfg.tvHost, "192.168.1.50")); 
        card1.addView(vpad(8))
        card1.addView(field("ADB 端口", cfg.tvPort.toString(), "5555", numeric = true))
        card1.addView(vpad(8))
        card1.addView(field("代理端口", cfg.port.toString(), "8100", numeric = true))
        card1.addView(vpad(8))
        card1.addView(field("本机对外地址（留空自动取局域网 IP）", cfg.publicHost, "自动", numeric = false))
        content.addView(card1)

        content.addView(section("播放策略"))
        val card2 = card()
        card2.addView(field("切换间隔（秒）", cfg.switchSeconds.toString(), "1800", numeric = true))
        card2.addView(vpad(8))
        card2.addView(field("每批取几条", cfg.pickCount.toString(), "5", numeric = true))
        card2.addView(vpad(8))
        card2.addView(field("状态刷新（秒）", cfg.statusSeconds.toString(), "10", numeric = true))
        card2.addView(vpad(8))
        card2.addView(field("空闲自动停止（分钟，0=不自动）", cfg.idleTimeoutMinutes.toString(), "0", numeric = true))
        card2.addView(vpad(8))
        card2.addView(field("直播画质 qn（80/150/250/400/10000）", cfg.liveQn.toString(), "250", numeric = true))
        card2.addView(vpad(8))
        card2.addView(field("播放器包名", cfg.mpvPkg, "is.xyz.mpv"))
        card2.addView(vpad(8))
        card2.addView(field("播放器 Activity", cfg.mpvActivity, "is.xyz.mpv/.MPVActivity"))
        content.addView(card2)

        val saveRow = row()
        saveRow.addView(button("保存配置", true) { saveConfigFromUi() })
        saveRow.addView(hpad(8))
        saveRow.addView(button("重新加载", false) { store.reload(); deviceDraft.clear(); render() })
        content.addView(saveRow)
        content.addView(vpad(10))

        content.addView(section("向导自检"))
        val t = card()
        t.addView(label("按顺序检查：外网 → 局域网 → 端口 → 电视 ADB → 播放器 → 内容库", 11f, T.DIM))
        t.addView(vpad(8))
        t.addView(button(if (selfTesting) "检测中…" else "开始自检", true) { runSelfTest() })
        if (selfTest.isNotEmpty()) {
            t.addView(vpad(10))
            selfTest.forEach { r ->
                val rr = row()
                rr.addView(statusDot(if (r.ok) T.OK else if (r.required) T.ERR else T.WARN))
                rr.addView(hpad(6))
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(label(r.name + if (r.required) "" else "（可选）", 13f, T.TEXT, true))
                col.addView(label(r.detail, 11f, T.DIM))
                rr.addView(col)
                t.addView(rr)
                t.addView(vpad(6))
            }
        }
        content.addView(t)

        content.addView(section("诊断"))
        val d = card()
        d.addView(label("查看最近的导入、播放、电视反馈与失败原因；导出时包含配置、运行环境和完整事件。", 11f, T.DIM))
        d.addView(vpad(8))
        val dr = row()
        dr.addView(button("查看诊断", true) { showDiagnosticsDialog() })
        dr.addView(hpad(8))
        dr.addView(button("导出诊断", false) { exportDiag() })
        d.addView(dr)
        content.addView(d)

        content.addView(section("关于"))
        val about = card()
        about.addView(label("B站直播遥控器 v" + TvrcVersion.APP, 14f, T.TEXT, true))
        about.addView(vpad(4))
        about.addView(label("core API v" + TvrcVersion.CORE_API + " · 单元测试基线 " + TvrcVersion.MIN_TEST_COUNT + " 条", 12f, T.DIM))
        about.addView(vpad(4))
        about.addView(label("代理协议：/live?room=  ·  /video?bvid=  ·  /status", 12f, T.DIM))
        content.addView(about)

        content.addView(vpad(10))
        content.addView(label(if (store.configProblems().isEmpty()) "配置校验：正常" else "配置校验：" + store.configProblems().joinToString("；"), 11f,
            if (store.configProblems().isEmpty()) T.OK else T.ERR))
    }

    private fun field(name: String, value: String, hint: String, numeric: Boolean = false): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(label(name, 11f, T.DIM))
        wrap.addView(vpad(4))
        val e = EditText(this).apply {
            this.hint = hint
            setText(deviceDraft.getString(name) ?: value)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(T.TEXT)
            setHintTextColor(T.DIM)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(T.CARD_HI, 12, 1, T.LINE)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            minHeight = dp(48)
            tag = name
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    deviceDraft.putString(name, s?.toString().orEmpty())
                }
            })
        }
        configInputs[name] = e
        wrap.addView(e)
        return wrap
    }

    private val configInputs = HashMap<String, EditText>()

    private fun saveConfigFromUi() {
        fun valOf(name: String, def: String = ""): String = configInputs[name]?.text?.toString()?.trim() ?: def
        fun intOf(name: String, def: Int): Int = valOf(name).toIntOrNull() ?: def
        val old = store.config()
        val fresh = old.copy(
            tvHost = valOf("电视 IP"),
            tvPort = intOf("ADB 端口", old.tvPort),
            port = intOf("代理端口", old.port),
            publicHost = valOf("本机对外地址（留空自动取局域网 IP）"),
            switchSeconds = intOf("切换间隔（秒）", old.switchSeconds),
            pickCount = intOf("每批取几条", old.pickCount),
            statusSeconds = intOf("状态刷新（秒）", old.statusSeconds),
            idleTimeoutMinutes = intOf("空闲自动停止（分钟，0=不自动）", old.idleTimeoutMinutes),
            liveQn = intOf("直播画质 qn（80/150/250/400/10000）", old.liveQn),
            mpvPkg = valOf("播放器包名", old.mpvPkg),
            mpvActivity = valOf("播放器 Activity", old.mpvActivity)
        )
        val errors = store.saveConfig(fresh)
        if (errors.isEmpty()) {
            deviceDraft.clear()
            store.log("config", "配置已保存：" + fresh.tvAddress)
            store.trace(
                "配置", "保存", TraceStatus.SUCCESS, "配置已保存",
                "电视：" + fresh.tvAddress + "\n代理端口：" + fresh.port +
                    "\n播放器：" + fresh.mpvPkg + "/" + fresh.mpvActivity
            )
            toast("已保存，重启服务生效")
            Ctl.service?.let { PlaybackService.ensureRunning(this) }
        } else {
            store.trace(
                "配置", "保存", TraceStatus.ERROR, "配置没有通过校验",
                errors.joinToString("\n")
            )
            AlertDialog.Builder(this).setTitle("配置没通过校验").setMessage(errors.joinToString("\n"))
                .setPositiveButton("知道了", null).show()
            return
        }
        render()
    }

    private fun runSelfTest() {
        if (selfTesting) return
        selfTesting = true
        selfTest = emptyList()
        render()
        val cfg = store.config()
        Ctl.bg {
            // 优先复用播放服务里已握手的 ADB 连接；不在一台电视或服务未启动时才独立连接。
            val results = SelfTest.run(cfg, store, Ctl.service?.activeAdbShell(cfg))
            runOnUiThread {
                selfTesting = false
                selfTest = results
                val failed = results.count { it.required && !it.ok }
                store.trace(
                    "自检", "完成",
                    if (failed == 0) TraceStatus.SUCCESS else TraceStatus.ERROR,
                    if (failed == 0) "全部必检项通过" else "有 " + failed + " 项必检未通过",
                    results.joinToString("\n") { it.name + "：" + it.detail }
                )
                toast(if (failed == 0) "自检通过" else "有 " + failed + " 项必检项没通过")
                render()
            }
        }
    }

    private fun showDiagnosticsDialog() {
        val events = store.traces(150)
        val body = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(T.TEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)
            text = if (events.isEmpty()) {
                "还没有诊断记录。\n\n完成一次导入、自检或播放后，再打开这里即可看到每一步的结果。"
            } else {
                formatDiagnosticEvents(events)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val summary = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(T.DIM)
            text = buildString {
                append("当前：").append(Ctl.stateText())
                append("\n本机：").append(LanAddress.localIpv4())
                append("　代理：").append(if (Ctl.proxyPort > 0) Ctl.proxyPort.toString() else "未启动")
                append("\n电视：").append(store.config().tvAddress)
                append("　状态：").append(if (Ctl.tvAlive) "在线" else "离线")
                if (store.configProblems().isNotEmpty()) {
                    append("\n配置问题：").append(store.configProblems().joinToString("；"))
                }
            }
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        val scroll = ScrollView(this).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { height = dp(430) }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(summary)
            addView(View(this@MainActivity).apply {
                setBackgroundColor(T.LINE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            addView(scroll)
        }
        AlertDialog.Builder(this)
            .setTitle("诊断记录")
            .setView(container)
            .setPositiveButton("导出") { _, _ -> exportDiag() }
            .setNeutralButton("清空记录") { _, _ ->
                confirm("清空诊断记录？", "导入、播放、自检和日志都会清空，电视与内容配置不受影响。") {
                    store.clearDiagnostics()
                    toast("诊断记录已清空")
                    render()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun formatDiagnosticEvents(events: List<TraceEvent>): String {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        return events.joinToString("\n\n") { e ->
            buildString {
                append("[").append(time.format(Date(e.atMs))).append("] ")
                append(e.status.label).append(" · ").append(e.area).append(" / ").append(e.action)
                if (e.elapsedMs != null) {
                    append("  (").append(String.format(Locale.CHINA, "%.1f", e.elapsedMs / 1000.0)).append(" 秒)")
                }
                append("\n").append(e.summary)
                if (e.details.isNotBlank()) append("\n").append(e.details)
            }
        }
    }

    private fun diagnosticTarget(item: LibItem): String = when (item.kind) {
        ItemKind.LIVE -> "直播 #" + (item.roomId ?: 0) + " · " + item.title
        ItemKind.VIDEO -> "视频 · " + item.title + " · " + (item.bvid ?: "无 BV")
        ItemKind.SERIES -> "合集 · " + item.title + " · " + item.bvids.size + " 集"
    }

    private fun exportDiag() {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val file = File(getExternalFilesDir(null) ?: filesDir, "diag-" + ts + ".txt")
        val sb = StringBuilder()
        sb.append("B站直播遥控器诊断包\n")
        sb.append("时间：").append(Date()).append("\n")
        sb.append("版本：").append(TvrcVersion.APP).append(" / core ").append(TvrcVersion.CORE_API).append("\n")
        sb.append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE).append("\n")
        sb.append("SDK：").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("本机 IP：").append(LanAddress.localIpv4()).append("\n")
        sb.append("代理端口：").append(Ctl.proxyPort).append("\n")
        sb.append("状态：").append(Ctl.stateText()).append("\n")
        sb.append("媒体传输：").append(Ctl.mediaText.ifBlank { "未知" }).append("\n")
        sb.append("说明：接收/发送字节不等于电视已显示画面；首帧、缓冲、解码状态未知。\n")
        sb.append("电视：").append(store.config().tvAddress)
            .append(" / ").append(if (Ctl.tvAlive) "在线" else "离线")
            .append(" / ").append(if (Ctl.tvPlaying) "确认在播" else "未确认在播").append("\n")
        sb.append("配置问题：").append(
            if (store.configProblems().isEmpty()) "无" else store.configProblems().joinToString("；")
        ).append("\n")
        sb.append("内容库：").append(store.items().size).append(" 条\n")
        sb.append("最近播放：").append(Ctl.playingTitle.ifBlank { "无" }).append("\n\n")
        sb.append("--- config.json ---\n")
        runCatching { sb.append(File(store.configPath).readText()) }
        sb.append("\n\n--- 最近诊断事件 ---\n")
        val events = store.traces(200)
        sb.append(if (events.isEmpty()) "无\n" else formatDiagnosticEvents(events)).append("\n")
        sb.append("\n\n--- 日志尾部 ---\n")
        runCatching {
            val all = store.logFile.readLines()
            sb.append(all.takeLast(500).joinToString("\n"))
        }
        runCatching {
            file.writeText(sb.toString())
            AlertDialog.Builder(this).setTitle("诊断包已导出")
                .setMessage("路径：\n" + file.absolutePath)
                .setPositiveButton("好", null).show()
        }.onFailure { toast("导出失败：" + it.message) }
    }

    // ---------------------------------------------------------------- 小工具

    private fun emptyState(title: String, hint: String): View {
        val c = card(bg = T.CARD)
        c.addView(label(title, 14f, T.TEXT, true))
        c.addView(vpad(4))
        c.addView(label(hint, 12f, T.DIM))
        return c
    }

    private fun runRow(r: RunRecord): View {
        val rr = row()
        rr.setPadding(0, dp(4), 0, dp(4))
        rr.addView(label(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(r.atMs)), 11f, T.DIM))
        rr.addView(hpad(8))
        rr.addView(label(r.text, 12f, T.TEXT))
        return rr
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun confirm(title: String, message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onOk() }
            .show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
    }
}
