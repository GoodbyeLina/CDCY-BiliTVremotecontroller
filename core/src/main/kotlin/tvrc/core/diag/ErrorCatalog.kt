package tvrc.core.diag

import tvrc.core.ErrorKind

/**
 * 错误文案表：每个错误码都必须给出「是什么 / 为什么 / 怎么做」三句话，
 * App 的 Error 界面直接显示，不允许只显示异常类名（H 方案验收线 4：App 内看到错误原因）。
 */
enum class ErrorCode(
    val code: String,
    val title: String,
    val cause: String,
    val action: String
) {
    TV_UNREACHABLE("E-ADB-01", "连不上电视", "电视没开机、没连同一个 WiFi，或调试端口关了", "检查电视电源和 WiFi，重开「开发者选项 → USB/网络调试」后再点重试"),
    ADB_UNAUTHORIZED("E-ADB-02", "电视没授权这台手机", "电视上弹过「允许调试吗」被拒了，或授权记录被清掉", "在电视上勾选「一律允许」并确定；找不到弹窗就在电视里撤销调试授权后重试"),
    ADB_OFFLINE("E-ADB-03", "电视调试连接断了", "电视休眠、WiFi 抖动，或二代 Termux 也在抢同一个 adb", "把电视设为不休眠，确认没有别的设备连它；App 会自动重连一次"),
    MPV_MISSING("E-MPV-01", "电视上没装播放器", "电视缺 mpv（或包名不是 is.xyz.mpv）", "在电视上装 mpv（可从手机推送安装包），装完点重试"),
    MPV_LAUNCH_FAILED("E-MPV-02", "播放器起不来", "mpv 被电视系统限制后台启动，或 Activity 名不对", "手动在电视上打开一次 mpv；仍失败就把电视型号发我，换 Activity 名"),
    PROXY_UPSTREAM_403("E-NET-01", "B站拒绝了取流请求", "直链过期（一般 2 小时）或请求头被 CDN 判定异常", "点重试会重新取直链；连续失败请检查手机时间是否准确"),
    PROXY_UPSTREAM_TIMEOUT("E-NET-02", "取流超时", "手机到 B站 CDN 的网络太慢或被运营商限速", "换 WiFi/热点再试；避开 20:00–23:00 高峰"),
    PROXY_PORT_BUSY("E-NET-03", "代理端口被占用", "8100 端口被别的 App 占了，或二代 Termux 还在跑", "关掉二代服务或改端口；App 会提示具体占用方"),
    SOURCE_EMPTY("E-SRC-01", "这个片源没有可播内容", "直播间没开播、合集为空，或 UP 主没有合集", "换一个直播间/合集，或先确认对方正在直播"),
    SOURCE_API_FAIL("E-SRC-02", "B站接口报错", "接口风控、需要登录，或网络被劫持", "稍后重试；反复失败请在设置里重新扫码登录"),
    CONFIG_INVALID("E-CFG-01", "配置有错", "配置文件被改坏，或从二代迁移时有字段不合法", "重新跑一遍配置向导；原文件不会被覆盖，可随时回退"),
    WIZARD_UNFINISHED("E-CFG-02", "配置还没做完", "向导没走完就退出了", "点「继续配置」从中断的那一步接着做"),
    PERMISSION_DENIED("E-SYS-01", "缺少系统权限", "通知/前台服务权限被拒，后台会被杀", "在系统设置里允许通知，并把 App 加入电池白名单"),
    INTERNAL("E-SYS-99", "App 内部错误", "未预料到的异常，详情在日志里", "导出诊断包后重启 App；仍复现请带上诊断包反馈");

    companion object {

        fun of(kind: ErrorKind): ErrorCode = when (kind) {
            ErrorKind.TV_UNREACHABLE -> TV_UNREACHABLE
            ErrorKind.PLAY_FAILED -> MPV_LAUNCH_FAILED
            ErrorKind.NO_CONTENT -> SOURCE_EMPTY
            ErrorKind.INTERNAL -> INTERNAL
        }

        fun byCode(code: String): ErrorCode? = entries.firstOrNull { it.code == code }

        /** 播放层错误统一走这里，保证 UI 文案只有一个来源。 */
        fun describe(kind: ErrorKind): String {
            val c = of(kind)
            return c.title + "：" + c.cause + "。怎么办：" + c.action
        }
    }
}
