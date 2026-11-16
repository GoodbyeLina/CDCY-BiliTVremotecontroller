package tvrc.core.test

import tvrc.core.adb.AdbClient
import tvrc.core.adb.ShellResult
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** adb 替身：记录命令、可脚本化回执、可注入异常、可检测并发重叠。 */
class FakeAdbClient(
    connectResults: List<Boolean> = listOf(true),
    private val latencyMs: Long = 0
) : AdbClient {

    private val connectPlan = connectResults.toMutableList()
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val responders = mutableListOf<Pair<(String) -> Boolean, (String) -> ShellResult>>()
    private val concurrent = AtomicInteger(0)

    @Volatile var connectCalls: Int = 0
        private set
    @Volatile var maxConcurrent: Int = 0
        private set
    @Volatile var failNextWith: IOException? = null
    @Volatile var closed: Boolean = false

    fun respond(predicate: (String) -> Boolean, result: (String) -> ShellResult) {
        responders.add(predicate to result)
    }

    override fun connect(): Boolean {
        connectCalls++
        val idx = connectCalls - 1
        return if (idx < connectPlan.size) connectPlan[idx] else (connectPlan.lastOrNull() ?: true)
    }

    override fun shell(command: String, timeoutMs: Long): ShellResult {
        val now = concurrent.incrementAndGet()
        if (now > maxConcurrent) maxConcurrent = now
        try {
            commands.add(command)
            if (latencyMs > 0) Thread.sleep(latencyMs)
            failNextWith?.let { e -> failNextWith = null; throw e }
            for ((predicate, result) in responders) if (predicate(command)) return result(command)
            return ShellResult(0, "", "")
        } finally {
            concurrent.decrementAndGet()
        }
    }

    override fun close() {
        closed = true
    }
}
