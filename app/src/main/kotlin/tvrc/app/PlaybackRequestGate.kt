package tvrc.app

import java.util.concurrent.atomic.AtomicLong

/** Invalidates slow preparation when a newer play/stop command arrives. No network calls. */
internal class PlaybackRequestGate {
    private val generation = AtomicLong()
    fun next(): Long = generation.incrementAndGet()
    fun isCurrent(ticket: Long): Boolean = generation.get() == ticket
    fun snapshot(): Long = generation.get()
    fun nextIfCurrent(expected: Long): Long? =
        if (generation.compareAndSet(expected, expected + 1)) expected + 1 else null
}
