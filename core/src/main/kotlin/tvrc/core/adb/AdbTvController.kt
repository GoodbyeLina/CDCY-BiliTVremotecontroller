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
 *            成功后补 [AdbTransport.replenishPlayState]（keyevent 85 最多 3 次，F-03：
 *            mpv 换台后可能停在暂停态，画面定住没声音极易被误判成播放失败）
 * - stop   → am force-stop <pkg>
 * - probe  → 进程存活 pidof → ps -A → ps | grep 三级回退（F-04），
 *            是否真的在播由 [MediaSessionTextSource]（dumpsys media_session）判定（P1-2 已接入）
 */
class AdbTvController(
    private val transport: AdbTransport,
    private val mpvPackage: String = "is.xyz.mpv",
    private val mpvComponent: String = "is.xyz.mpv/.MPVActivity",
    private val replenishAttempts: Int = 3,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
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
        val resumed = transport.replenishPlayState(mpvPackage, replenishAttempts, sleeper)
        return Ack(true, "started; keyevent85 x$resumed")
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
