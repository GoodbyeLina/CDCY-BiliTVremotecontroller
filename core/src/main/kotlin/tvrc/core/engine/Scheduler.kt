package tvrc.core.engine

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 可取消的一次性任务。 */
interface Cancellable {
    fun cancel()
}

/**
 * 定时器抽象 —— 契约已冻结（P4-3，2026-11-13，core API v1）。
 *
 * 生产用 RealScheduler（ScheduledExecutorService，Android 上可换成 Handler 实现），
 * 测试用 VirtualScheduler（手动拨表，0 真等待、0 抖动）。
 *
 * ### 接手实现须知
 * 1. **回调在别的线程**：postDelayed 的 action 不在调用线程执行，
 *    实现方不要假设线程亲和；引擎内部已用 synchronized 兜住。
 * 2. **action 抛异常必须被吞掉**，否则一次回调异常会杀死整个定时线程，后续换台全停。
 * 3. **cancelAll 要真的取消**：停止播放时靠它一次性清掉换台/探测/空闲定时器；
 *    只标记不取消会让停止后仍触发一次换台（用户侧表现为「停了又自己播了」）。
 * 4. **delayMs<0 视为 0**，不要抛异常（引擎恢复路径会算出 0 或负值）。
 * 5. Android 侧替换时用 Handler(Looper.getMainLooper()).postDelayed 即可，
 *    cancelAll 用一个 token 集合实现；语义与本接口完全一致，不需要改引擎。
 */
interface Scheduler {
    fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable
    fun cancelAll()
}

/** 时钟抽象，测试可用虚拟时钟。 */
interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

class RealScheduler : Scheduler, Closeable {

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "playback-timer").apply { isDaemon = true } }

    private val pending = ConcurrentLinkedQueue<ScheduledFuture<*>>()

    override fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable {
        val future = executor.schedule({ runCatching(action) }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        pending.add(future)
        return object : Cancellable {
            override fun cancel() {
                future.cancel(false)
            }
        }
    }

    override fun cancelAll() {
        while (true) {
            val f = pending.poll() ?: break
            if (!f.isDone) f.cancel(false)
        }
    }

    override fun close() {
        cancelAll()
        executor.shutdownNow()
    }
}
