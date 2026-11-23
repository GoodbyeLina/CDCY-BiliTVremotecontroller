package tvrc.core.adb

import tvrc.core.Ack
import tvrc.core.TvController
import tvrc.core.TvStatus
import tvrc.core.media.MediaSessionProbe
import tvrc.core.media.MediaSessionTextSource

/**
 * [TvController] 的 adb 真实现（L 清单 P1-1）。语义映射：
 *
 * - wake   → input keyevent 224（KEYCODE_WAKEUP，点亮电视自己的屏）
 * - launch → am start -a android.intent.action.VIEW -d <url> -n <pkg>/<activity>，
 *            由播放器处理自动起播；不发送全局媒体键，避免暂停 mpv 或唤醒其他音乐应用
 * - stop   → am force-stop <pkg>
 * - probe  → 进程存活 pidof → ps -A → ps | grep 三级回退（F-04），
 *            是否真的在播由 [MediaSessionTextSource]（dumpsys media_session）判定（P1-2 已接入）
 */
class AdbTvController(
    private val transport: AdbTransport,
    private val mpvPackage: String = "is.xyz.mpv",
    private val mpvComponent: String = "is.xyz.mpv/.MPVActivity",
    /**
     * 取 dumpsys 文本的来源。为 null 时 playing 恒 false（老测试的零依赖用法）。
     * 生产由 AdbMediaSessionSource(transport) 注入。
     */
    private val mediaSession: MediaSessionTextSource? = null
) : TvController {

    override fun wake(): Ack = transport.wake().toAck()

    override fun launch(target: String): Ack {
        val started = transport.startVideo(target, mpvComponent)
        if (!started.ok) return started.toAck()
        return Ack(true, "started; no global media keys")
    }

    override fun stop(): Ack = transport.stopApp(mpvPackage).toAck()

    /**
     * 探针顺序有讲究：进程已死就直接 playing=false，不再白花一次 dumpsys
     * （dumpsys media_session 在低端电视上能跑 1–3 秒，探测每 5 秒来一次，省下来是实打实的）。
     */
    override fun probe(): TvStatus {
        val alive = transport.isAlive(mpvPackage)
        if (!alive) return TvStatus(alive = false, playing = false)
        val source = mediaSession ?: return TvStatus(alive = true, playing = false)
        val playing = runCatching {
            MediaSessionProbe.isPlaying(source.dumpMediaSessions(), mpvPackage)
        }.getOrDefault(false)
        return TvStatus(alive = true, playing = playing)
    }

    private fun ShellResult.toAck(): Ack = if (ok) {
        Ack(true, stdout.trim())
    } else {
        Ack(false, (if (stderr.isBlank()) stdout else stderr).trim())
    }
}
