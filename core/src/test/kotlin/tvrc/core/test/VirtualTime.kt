package tvrc.core.test

import tvrc.core.engine.Cancellable
import tvrc.core.engine.Clock
import tvrc.core.engine.Scheduler
import java.util.PriorityQueue

/** 虚拟时钟：测试里手动拨表，0 真等待。 */
class VirtualClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

/** 虚拟调度器：按时间顺序执行到期任务。 */
class VirtualScheduler(private val clock: VirtualClock) : Scheduler {

    private class Task(
        val at: Long,
        val seq: Long,
        val action: () -> Unit
    ) : Cancellable {
        @Volatile var cancelled: Boolean = false
        override fun cancel() {
            cancelled = true
        }
    }

    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var seq = 0L

    override fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable {
        val task = Task(clock.now + delayMs.coerceAtLeast(0), seq++, action)
        queue.add(task)
        return task
    }

    override fun cancelAll() {
        for (t in queue) t.cancelled = true
        queue.clear()
    }

    /** 拨快 ms，并按时间顺序执行所有到期任务。 */
    fun advanceBy(ms: Long) {
        val target = clock.now + ms
        while (true) {
            val head = queue.peek() ?: break
            if (head.at > target) break
            queue.poll()
            clock.now = head.at
            if (!head.cancelled) head.action()
        }
        clock.now = target
    }

    fun pendingCount(): Int = queue.count { !it.cancelled }
}
