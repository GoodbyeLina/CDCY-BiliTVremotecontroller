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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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

    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var navBar: LinearLayout

    private var tab = 0
    private var filter = "全部"
    private var searchQuery = ""
    private var draft = ""
    private var searchField: EditText? = null
    private var importing = false
    private var importMessage = ""
    private var selfTest: List<SelfTest.Item> = emptyList()
    private var selfTesting = false

    private val refreshCb: () -> Unit = { runOnUiThread { render() } }
    private val tabs = listOf("内容库", "节目单", "监控", "设备")
    private val filters = listOf("全部", "视频", "合集", "直播", "收藏")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Ctl.store ?: Store(this).also { Ctl.store = it }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(T.BG))
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

    // ---------------------------------------------------------------- 骨架

    private fun buildRoot() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.BG)
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
    }

    private fun topBar(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.CARD)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val r = row()
        r.addView(label("B站直播遥控器", 18f, T.TEXT, true))
        r.addView(hpad(8))
        r.addView(badge("v" + TvrcVersion.APP, T.ACCENT))
        wrap.addView(r)
        val status = row()
        status.addView(statusDot(if (Ctl.proxyPort > 0) T.OK else T.DIM))
        status.addView(hpad(6))
        val line = buildString {
            append(Ctl.stateText())
            if (Ctl.playingTitle.isNotBlank()) append(" · ").append(Ctl.playingTitle)
            append("　代理 ").append(if (Ctl.proxyPort > 0) ":" + Ctl.proxyPort else "未启动")
            append("　电视 ").append(if (Ctl.tvAlive) "在线" else "离线")
        }
        status.addView(label(line, 12f, T.DIM))
        wrap.addView(vpad(6))
        wrap.addView(status)
        return wrap
    }

    private fun render() {
        content.removeAllViews()
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

        val chips = row()
        filters.forEach { f ->
            chips.addView(chip(f, filter == f) { filter = f; render() })
            chips.addView(hpad(6))
        }
        content.addView(chips)
        content.addView(vpad(8))

        // 搜索（R-06）：标题 / BV / 房间号都能命中；本地过滤，零网络
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
                        render()
                    }
                }
            })
        }
        searchField = sInput
        searchRow.addView(sInput)
        searchRow.addView(hpad(8))
        searchRow.addView(button("清空", false) { searchQuery = ""; render() })
        content.addView(searchRow)
        content.addView(vpad(8))

        val list = store.items()
            .filter { item ->
                when (filter) {
                    "视频" -> item.kind == ItemKind.VIDEO
                    "合集" -> item.kind == ItemKind.SERIES
                    "直播" -> item.kind == ItemKind.LIVE
                    "收藏" -> item.favorite
                    else -> true
                }
            }
            .filter { searchQuery.isBlank() || it.searchKey().contains(searchQuery) }

        if (list.isEmpty()) {
            content.addView(emptyState(
                if (store.items().isEmpty()) "内容库还是空的" else "没有符合筛选的条目",
                "把 B站 分享链接粘到上面的输入框，点「导入」"
            ))
            return
        }
        list.forEach { content.addView(itemCard(it)) }
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
        c.addView(label("支持：房间号 / live.bilibili.com 网址 / BV 号 / 合集链接 / b23.tv 短链 / 整段分享文案", 11f, T.DIM))
        c.addView(vpad(8))
        val input = EditText(this).apply {
            hint = "粘贴分享文案或网址"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(T.TEXT)
            setHintTextColor(T.DIM)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(T.CARD_HI, 12, 1, T.LINE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 3
            setText(draft)
        }
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
        if (importMessage.isNotBlank()) r.addView(label(importMessage, 11f, T.DIM))
        c.addView(r)
        return c
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
            render()
        })
        actions.addView(hpad(8))
        actions.addView(button("删除", false) {
            confirm("删除「" + item.title + "」？", "同时会删掉引用它的节目单") {
                store.removeItem(item.id)
                render()
            }
        })
        c.addView(actions)
        return c
    }

    private fun doImport(raw: String) {
        if (importing) return
        importing = true
        importMessage = "正在识别…"
        render()
        Ctl.bg {
            val importer = LinkImporter(
                SourceExpander(UrlConnectionRedirectFollower()),
                RealBiliLiveApi(UrlConnectionHttpText()),
                RealBiliArchiveApi(UrlConnectionHttpText()),
                ViewInfoApi(UrlConnectionHttpText())
            )
            val outcome = importer.import(raw)
            runOnUiThread {
                importing = false
                when (outcome) {
                    is ImportOutcome.Ok -> {
                        val saved = outcome.items.map { store.upsert(it) }
                        store.log("import", outcome.note + " → " + saved.joinToString { it.title })
                        importMessage = "已导入 " + saved.size + " 条"
                        toast("已导入：" + saved.joinToString("、") { it.title })
                    }

                    is ImportOutcome.Fail -> {
                        importMessage = "导入失败：" + outcome.reason
                        toast(outcome.reason)
                    }
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
        head.addView(button("新建", true) { addScheduleDialog() })
        content.addView(head)
        content.addView(vpad(6))

        if (store.schedules().isEmpty()) {
            content.addView(emptyState("还没有节目单", "到点自动播放：点右上「新建」，选一条内容库条目"))
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
        val r = row()
        r.addView(label(s.timeText(), 22f, if (s.enabled) T.ACCENT else T.DIM, true))
        r.addView(hpad(12))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(s.title, 14f, T.TEXT, true))
        col.addView(label(s.daysText() + (if (s.wake) " · 先唤醒电视" else ""), 11f, T.DIM))
        r.addView(col)
        r.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val sw = Switch(this).apply {
            isChecked = s.enabled
            setOnCheckedChangeListener { _, v -> store.setScheduleEnabled(s.id, v); render() }
        }
        r.addView(sw)
        c.addView(r)
        c.addView(vpad(6))
        val actions = row()
        actions.addView(button("立即执行", false) {
            PlaybackService.playItem(this, s.itemId, "schedule")
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

    private fun addScheduleDialog() {
        val items = store.items()
        if (items.isEmpty()) {
            toast("先去内容库导入一条内容")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        box.addView(label("选内容", 12f, T.DIM))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                items.map { it.kindLabel + " · " + it.title })
        }
        box.addView(spinner)

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

        AlertDialog.Builder(this)
            .setTitle("新建节目单")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val idx = spinner.selectedItemPosition.coerceIn(0, items.size - 1)
                val item = items[idx]
                store.addSchedule(
                    ScheduleItem(
                        id = "", itemId = item.id, title = item.title,
                        hour = hour, minute = minute, days = picked.toSet(), wake = wakeSw.isChecked
                    )
                )
                store.log("schedule", "新增：" + item.title + " " + "%02d:%02d".format(hour, minute))
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
        card2.addView(field("播放器包名", cfg.mpvPkg, "is.xyz.mpv"))
        card2.addView(vpad(8))
        card2.addView(field("播放器 Activity", cfg.mpvActivity, "is.xyz.mpv/.MPVActivity"))
        content.addView(card2)

        val saveRow = row()
        saveRow.addView(button("保存配置", true) { saveConfigFromUi() })
        saveRow.addView(hpad(8))
        saveRow.addView(button("重新加载", false) { store.reload(); render() })
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
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        d.addView(label("导出诊断包：配置 + 最近日志，便于远程排查", 11f, T.DIM))
        d.addView(vpad(8))
        val dr = row()
        dr.addView(button("导出诊断", false) { exportDiag() })
        dr.addView(hpad(8))
        dr.addView(button("清空日志", false) { runCatching { store.logFile.writeText("") }; toast("已清空") })
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
            setText(value)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(T.TEXT)
            setHintTextColor(T.DIM)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(T.CARD_HI, 12, 1, T.LINE)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            tag = name
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
            mpvPkg = valOf("播放器包名", old.mpvPkg),
            mpvActivity = valOf("播放器 Activity", old.mpvActivity)
        )
        val errors = store.saveConfig(fresh)
        if (errors.isEmpty()) {
            store.log("config", "配置已保存：" + fresh.tvAddress)
            toast("已保存，重启服务生效")
            Ctl.service?.let { PlaybackService.ensureRunning(this) }
        } else {
            AlertDialog.Builder(this).setTitle("配置没通过校验").setMessage(errors.joinToString("\n"))
                .setPositiveButton("知道了", null).show()
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
            val results = SelfTest.run(cfg, store)
            runOnUiThread {
                selfTesting = false
                selfTest = results
                val failed = results.count { it.required && !it.ok }
                toast(if (failed == 0) "自检通过" else "有 " + failed + " 项必检项没通过")
                render()
            }
        }
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
        sb.append("本机 IP：").append(LanAddress.localIpv4()).append("\n")
        sb.append("代理端口：").append(Ctl.proxyPort).append("\n")
        sb.append("状态：").append(Ctl.stateText()).append("\n\n")
        sb.append("--- config.json ---\n")
        runCatching { sb.append(File(store.configPath).readText()) }
        sb.append("\n\n--- 日志尾部 ---\n")
        runCatching {
            val all = store.logFile.readLines()
            sb.append(all.takeLast(200).joinToString("\n"))
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
