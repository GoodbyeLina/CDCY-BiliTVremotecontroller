package tvrc.core.test

import tvrc.core.Ack
import tvrc.core.TvController
import tvrc.core.TvStatus

/**
 * 测试替身：
 * - 记录收到的每条命令，便于断言顺序；
 * - 可注入失败（wake/launch/stop 返回 ok=false）与掉线（probe 返回 alive=false）。
 */
class FakeTv : TvController {

    val commands = mutableListOf<String>()

    var wakeOk: Boolean = true
    var stopOk: Boolean = true
    private val launchQueue = ArrayDeque<Boolean>()

    var status: TvStatus = TvStatus(alive = true, playing = true)
    var probeCount: Int = 0
        private set

    fun enqueueLaunch(vararg results: Boolean) {
        launchQueue.addAll(results.toList())
    }

    override fun wake(): Ack {
        commands += "wake"
        return Ack(wakeOk, if (wakeOk) "" else "wake failed")
    }

    override fun launch(target: String): Ack {
        commands += "launch:$target"
        val ok = if (launchQueue.isEmpty()) true else launchQueue.removeFirst()
        return Ack(ok, if (ok) "" else "launch failed: $target")
    }

    override fun stop(): Ack {
        commands += "stop"
        return Ack(stopOk, if (stopOk) "" else "stop failed")
    }

    override fun probe(): TvStatus {
        probeCount += 1
        commands += "probe"
        return status
    }

    fun reset() {
        commands.clear()
        launchQueue.clear()
        probeCount = 0
    }
}
