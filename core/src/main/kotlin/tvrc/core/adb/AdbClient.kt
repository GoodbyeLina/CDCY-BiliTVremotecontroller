package tvrc.core.adb

import java.io.Closeable
import java.io.IOException

/** 一条 adb shell 命令的执行结果。 */
data class ShellResult(val exitCode: Int, val stdout: String = "", val stderr: String = "") {
    val ok: Boolean get() = exitCode == 0
}

/** 命令超时（与「连接断了」区分：超时不重试，避免把慢命令放大成雪崩）。 */
class AdbTimeoutException(message: String) : IOException(message)

/**
 * adb 传输层抽象 —— 契约已冻结（P4-3，2026-11-13，core API v1）。
 *
 * 真实实现是本仓库自研的 DadbClient（直连 5555 + 自实现 RSA 握手，见 M-ADR-0006），
 * 测试实现是 FakeAdbClient / FakeAdbServer。上层只依赖这个接口。
 *
 * ### 接手实现须知
 * 1. **不要起 adb server**：不调 adb start-server、不 adb connect，
 *    直接 TCP 连 <tvHost>:5555 走 ADB 协议（二代现场验证过电视常开网络调试）。
 * 2. **connect 幂等**：重复调用要么返回 true，要么重新握手；不要在已连上时抛异常。
 * 3. **shell 退出码**：legacy shell 协议本身不带退出码，实现用标记行方案
 *    （__TVRC_EXIT__:<code>）自行拼装；拿不到退出码时退化为 0 并在 stdout 说明。
 * 4. **超时语义**：超过 timeoutMs 抛 AdbTimeoutException（不重试）；连接被对端关闭
 *    抛 IOException（可重连一次）。两者不可混用，引擎对它们的重试策略不同。
 * 5. **close 必须释放 socket**：不留后台线程、不留半开连接；
 *    引擎会在换台/停止时复用同一个 client，不要 close 后继续用。
 */
interface AdbClient : Closeable {
    /** 建立到设备 5555 的直连（含 RSA 握手）。返回是否成功；幂等。 */
    fun connect(): Boolean

    /** 执行一条 shell 命令。实现方负责按 timeoutMs 超时并抛 AdbTimeoutException。 */
    fun shell(command: String, timeoutMs: Long): ShellResult
}
