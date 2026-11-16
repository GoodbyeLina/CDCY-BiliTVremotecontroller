package tvrc.core

/** 一次 adb/播放器命令的回执。 */
data class Ack(val ok: Boolean, val detail: String = "")

/** 电视侧探针结果。alive=进程还在，playing=确实在播。 */
data class TvStatus(val alive: Boolean, val playing: Boolean)

/**
 * 电视控制端口 —— 契约已冻结（P4-3，2026-11-13，core API v1）。
 *
 * 真实实现走 adb（见 tvrc.core.adb.DadbClient + AdbTvController），测试实现是 FakeTv；
 * 状态机只依赖这个接口，所以纯 JVM 单测不需要电视、不需要 Android SDK。
 *
 * ### 接手实现须知
 * 1. **不抛异常**：没连上、电视关机、命令报错一律通过 Ack.ok=false 表达，
 *    调用方（PlaybackEngine）靠回执决定重试，抛出来会绕过重试/冷却逻辑。
 * 2. **不阻塞超过 10 秒**：每个方法内部自己超时（adb 侧建议 5s），
 *    因为引擎在持锁路径里调用它，长时间阻塞会把换台/停止一起卡住。
 * 3. **probe 必须区分「活着」和「在播」**：TvStatus.alive 用 pidof / ps 判断进程，
 *    TvStatus.playing 用 dumpsys media_session 的 state 判断；
 *    只给 alive=true 而 playing 恒为 false 会让看门狗把正常播放误判成卡死。
 * 4. **launch 的 target 是完整 intent 目标**（如 is.xyz.mpv/.MPVActivity），
 *    实现方负责拼 am start -n ... -d <url> 并做 shell 转义。
 * 5. **wake 只是点亮屏幕**（input keyevent 224），不代表已连上 adb；
 *    实现顺序必须是 先 connect / 再 wake。
 */
interface TvController {
    /** 点亮电视屏幕（KEYCODE_WAKEUP 224）。不等同于连接成功。 */
    fun wake(): Ack
    /** 拉起播放器并给播放地址。target 形如 pkg/.Activity。 */
    fun launch(target: String): Ack
    /** 停止播放（杀播放器或发 back/stop）。用户停止与看门狗都用它。 */
    fun stop(): Ack
    /** 探测：进程是否还在（alive）、是否真的在播（playing）。不抛异常。 */
    fun probe(): TvStatus
}
