package tvrc.core.adb

import tvrc.core.Ack
import tvrc.core.TvController
import tvrc.core.TvStatus

/**
 * [TvController] 的 adb 真实现（L 清单 P1-1）。语义映射：
 *
 * - wake   → input keyevent 224（KEYCODE_WAKEUP，点亮电视自己的屏）
 * - launch → am start -a android.intent.action.VIEW -d <url> -n <pkg>/<activity>，
 *            成功后补 [AdbTransport.replenishPlayState]（keyevent 85 最多 3 次，F-03：
 *            mpv 换台后可能停在暂停态，画面定住没声音极易被误判成播放失败）
 * - stop   → am force-stop <pkg>
 * - probe  → pidof → ps -A → ps | grep 三级回退（F-04）
 *
 * TODO(P1-2)：[probe] 的 playing 字段当前恒为 false，等 MediaSessionProbe
 * （dumpsys media_session 文本判定）接入后补上；alive 已可用。
 */
class AdbTvController(
    private val transport: AdbTransport,
    private val mpvPackage: String = "is.xyz.mpv",
    private val mpvComponent: String = "is.xyz.mpv/.MPVActivity",
    private val replenishAttempts: Int = 3,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) : TvController {

    override fun wake(): Ack = transport.wake().toAck()

    override fun launch(target: String): Ack {
        val started = transport.startVideo(target, mpvComponent)
        if (!started.ok) return started.toAck()
        val resumed = transport.replenishPlayState(mpvPackage, replenishAttempts, sleeper)
        return Ack(true, "started; keyevent85 x$resumed")
    }

    override fun stop(): Ack = transport.stopApp(mpvPackage).toAck()

    override fun probe(): TvStatus = TvStatus(
        alive = transport.isAlive(mpvPackage),
        playing = false // TODO(P1-2)：MediaSessionProbe 接入后补
    )

    private fun ShellResult.toAck(): Ack = if (ok) {
        Ack(true, stdout.trim())
    } else {
        Ack(false, (if (stderr.isBlank()) stdout else stderr).trim())
    }
}
