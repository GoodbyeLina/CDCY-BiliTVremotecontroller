package tvrc.app

import tvrc.core.adb.AdbKeyStore
import tvrc.core.adb.AdbTransport
import tvrc.core.adb.DadbClient
import tvrc.core.bili.RealBiliLiveApi
import tvrc.core.bili.UrlConnectionHttpText
import tvrc.core.config.AppConfig
import java.net.ServerSocket

/**
 * 向导自检（R-09/H-3 的落地版）。
 *
 * 原则：每条都能给出「怎么修」，不是只报红。
 * 顺序按依赖排：本机网络 → 局域网地址 → 代理端口 → 电视 ADB → 播放器 → 试播。
 */
object SelfTest {

    data class Item(val name: String, val required: Boolean, val ok: Boolean, val detail: String)

    fun run(cfg: AppConfig, store: Store): List<Item> {
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
            var alive = false
            var packages = ""
            runCatching {
                val t = AdbTransport({ DadbClient(cfg.tvHost, cfg.tvPort, keyStore) }, keyStore, defaultTimeoutMs = 12_000)
                alive = t.isAlive(cfg.mpvPkg)
                if (t.isAlive("android")) packages = t.shell("pm list packages", 10_000L).stdout
                t.close()
            }
            results += Item(
                "电视 ADB " + cfg.tvHost + ":" + cfg.tvPort, true, alive,
                if (alive) "已连上，mpv 进程在跑" else "连不上：电视开启「ADB 网络调试」，确认同网段、端口 5555；首次连接要在电视上点允许"
            )
            val mpvOk = packages.contains(cfg.mpvPkg)
            results += Item(
                "播放器 " + cfg.mpvPkg, false, mpvOk,
                if (mpvOk) "已安装" else "电视上没装 mpv：装上 " + cfg.mpvPkg + " 后再试（不装则无法播放）"
            )
            val browserOk = packages.contains("com.android.tv.settings") || packages.contains("com.android.browser")
            results += Item(
                "电视桌面/浏览器", false, browserOk,
                if (browserOk) "系统组件正常" else "没探到系统浏览器（部分电视用内置播放器，可忽略）"
            )
        }

        // 6. 内容库
        val liveCount = store.items().count { it.kind == ItemKind.LIVE }
        results += Item(
            "内容库", false, store.items().isNotEmpty(),
            "共 " + store.items().size + " 条（直播 " + liveCount + "）；空的话先回「内容库」导入一个链接"
        )
        return results
    }
}
