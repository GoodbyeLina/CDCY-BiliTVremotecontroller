package tvrc.app

import tvrc.core.adb.AdbKeyStore
import tvrc.core.adb.AdbClient
import tvrc.core.adb.DadbClient
import tvrc.core.adb.ShellResult
import tvrc.core.bili.RealBiliLiveApi
import tvrc.core.bili.UrlConnectionHttpText
import tvrc.core.config.AppConfig
import java.net.ServerSocket
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.io.IOException

/**
 * 向导自检（R-09/H-3 的落地版）。
 *
 * 原则：每条都能给出「怎么修」，不是只报红。
 * 顺序按依赖排：本机网络 → 局域网地址 → 代理端口 → 电视 ADB → 播放器 → 试播。
 */
object SelfTest {

    data class Item(val name: String, val required: Boolean, val ok: Boolean, val detail: String)

    fun run(cfg: AppConfig, store: Store, activeShell: ((String, Long) -> ShellResult)? = null): List<Item> {
        val results = mutableListOf<Item>()

        // 1. 外网（B站接口）
        val netOk = runCatching {
            UrlConnectionHttpText().get("https://api.bilibili.com/x/web-interface/nav", RealBiliLiveApi.HEADERS, 8_000)
            true
        }.getOrDefault(false)
        results += Item(
            "外网 · B站接口", true, netOk,
            if (netOk) "api.bilibili.com 可达" else "连不上 api.bilibili.com：检查手机网络 / 代理 / 是否被限流"
        )

        // 2. 局域网地址（电视必须能访问到）
        val ip = LanAddress.localIpv4()
        val ipOk = ip != "127.0.0.1"
        results += Item(
            "局域网地址", true, ipOk,
            if (ipOk) "本机 " + ip + "，电视请与手机同一 Wi-Fi" else "只拿到 127.0.0.1：先连上 Wi-Fi"
        )

        // 3. 代理端口
        val portOk = runCatching { ServerSocket(cfg.port).use { true } }.getOrDefault(false)
        results += Item(
            "代理端口 " + cfg.port, true, portOk || Ctl.proxyPort == cfg.port,
            when {
                Ctl.proxyPort == cfg.port -> "本服务已占用（正常）"
                portOk -> "端口可用"
                else -> "端口被别的进程占了：换一个 1024-65535 的端口"
            }
        )

        // 4/5. 电视 ADB 与播放器
        if (cfg.tvHost.isBlank()) {
            results += Item("电视 ADB", true, false, "电视地址为空：填电视 IP（电视设置里看）")
        } else {
            val keyStore = AdbKeyStore(store.keyDir, "adbkey")
            val adbResults = checkAdb(cfg, activeShell) { DadbClient(cfg.tvHost, cfg.tvPort, keyStore) }
            results += adbResults
            // 自检结果不覆写运行服务的连接/播放状态：独立握手失败不能证明已有连接离线。
            adbResults.forEach { store.log("self-test", it.name + ": " + it.detail) }
        }

        // 6. 内容库
        val liveCount = store.items().count { it.kind == ItemKind.LIVE }
        results += Item(
            "内容库", false, store.items().isNotEmpty(),
            "共 " + store.items().size + " 条（直播 " + liveCount + "）；空的话先回「内容库」导入一个链接"
        )
        return results
    }

    /**
     * activeShell 必须来自与 cfg 同一目标的运行传输层，并由传输层串行化命令。
     * 借用时只执行真实 echo/包查询，不连接或关闭它；没有运行连接时才拥有独立 client。
     * 连通性、授权、安装状态分别检查，不以播放器进程或缓存在线状态判断 ADB。
     */
    internal fun checkAdb(
        cfg: AppConfig,
        activeShell: ((String, Long) -> ShellResult)? = null,
        clientFactory: () -> AdbClient
    ): List<Item> {
        var connected = false
        var failure = ""
        var packages: Set<String>? = null
        var packageFailure = ""
        fun inspect(shell: (String, Long) -> ShellResult) {
            val probe = shell("echo TVRC_ADB_OK", 8_000)
            if (!probe.ok || probe.stdout.trim() != "TVRC_ADB_OK") {
                throw IOException("ADB shell 未返回验证结果：" + probe.stderr.ifBlank { probe.stdout })
            }
            connected = true
            try {
                val list = shell("pm list packages", 10_000)
                val names = list.stdout.lineSequence().map { it.trim() }
                    .filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toSet()
                if (list.ok && names.isNotEmpty()) packages = names
                else packageFailure = "应用列表查询失败：" + list.stderr.ifBlank { list.stdout }
            } catch (e: Exception) {
                packageFailure = e.message ?: e.javaClass.simpleName
            }
        }
        try {
            if (activeShell != null) {
                inspect(activeShell)
            } else {
                clientFactory().use { client ->
                    if (!client.connect()) throw IOException("电视拒绝了 ADB 认证，请在电视上允许调试")
                    inspect(client::shell)
                }
            }
        } catch (e: Exception) {
            failure = when (e) {
                is ConnectException -> "电视未接受连接，请确认网络 ADB 端口 ${cfg.tvPort} 已开启"
                is NoRouteToHostException -> "无法到达电视，请检查同一局域网及路由器设备隔离"
                is SocketTimeoutException -> "连接电视超时，请检查 IP、网络 ADB 端口及设备隔离"
                else -> e.message ?: e.javaClass.simpleName
            }
        }
        val unknown = if (!connected) "尚未检测：需先连接并授权电视 ADB" else "尚未确认：$packageFailure"
        val installed = packages?.contains(cfg.mpvPkg) == true
        val browser = packages?.any { it == "com.android.tv.settings" || it == "com.android.browser" } == true
        return listOf(
            Item("电视 ADB ${cfg.tvHost}:${cfg.tvPort}", true, connected,
                if (connected) "已连接、已授权，shell 命令正常" else failure),
            Item("播放器 ${cfg.mpvPkg}", true, installed, when {
                packages == null -> unknown
                installed -> "已安装（无需预先运行播放器）"
                else -> "未安装：请在电视安装 ${cfg.mpvPkg} 后重试"
            }),
            Item("电视桌面/浏览器", false, browser, when {
                packages == null -> unknown
                browser -> "系统组件正常"
                else -> "未发现对应系统组件，部分电视可忽略"
            })
        )
    }
}
