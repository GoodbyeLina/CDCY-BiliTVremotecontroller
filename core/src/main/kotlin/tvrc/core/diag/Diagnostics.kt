package tvrc.core.diag

import tvrc.core.config.AppConfig
import tvrc.core.config.JsonConfigRepository
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(val atMs: Long, val level: LogLevel, val tag: String, val message: String) {
    fun format(): String = "[" + atMs + "] " + level.name.padEnd(5) + " " + tag + ": " + message
}

/**
 * 环形日志：内存里只留最近 [capacity] 条，永不写文件、永不超过上限。
 * 电视端/手机端内存都很小，日志不能成为 OOM 的来源。
 */
class RingLog(val capacity: Int = 1000) {

    private val buf = ArrayDeque<LogEntry>(capacity)
    private var dropped = 0

    @Synchronized
    fun add(level: LogLevel, tag: String, message: String, atMs: Long = System.currentTimeMillis()) {
        if (buf.size == capacity) {
            buf.removeFirst()
            dropped++
        }
        buf.addLast(LogEntry(atMs, level, tag, message.take(MAX_MESSAGE)))
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = buf.toList()

    @Synchronized
    fun droppedCount(): Int = dropped

    @Synchronized
    fun clear() {
        buf.clear()
        dropped = 0
    }

    @Synchronized
    fun dump(): String {
        val sb = StringBuilder()
        sb.append("# 环形日志 共 ").append(buf.size).append(" 条，已丢弃 ").append(dropped).append(" 条\n")
        for (e in buf) sb.append(e.format()).append('\n')
        return sb.toString()
    }

    companion object {
        const val DEFAULT_CAPACITY = 1000
        const val MAX_MESSAGE = 2000
    }
}

data class DeviceInfo(
    val androidVersion: String = "unknown",
    val deviceModel: String = "unknown",
    val appVersion: String = "dev",
    val buildTime: String = "unknown"
) {
    fun format(): String = "android=" + androidVersion + "\nmodel=" + deviceModel + "\napp=" + appVersion + "\nbuild=" + buildTime + "\n"
}

/**
 * 诊断包：一个 ZIP，里面只有小文本文件，永远不打包媒体、不打包 B站直链（带签名，泄露等同事泄露账号）。
 * 上限 [MAX_BYTES] 字节；超了就截断日志并记一条说明。
 */
object DiagnosticsPack {

    const val MAX_BYTES = 256 * 1024
    const val CONFIG_ENTRY = "config.json"
    const val LOG_ENTRY = "log.txt"
    const val DEVICE_ENTRY = "device.txt"
    const val MANIFEST_ENTRY = "manifest.txt"

    fun build(
        config: AppConfig,
        log: RingLog,
        device: DeviceInfo,
        nowMs: Long = System.currentTimeMillis()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(MANIFEST_ENTRY, "generatedAt=" + nowMs + "\nlogEntries=" + log.snapshot().size +
                "\ndropped=" + log.droppedCount() + "\nmaxBytes=" + MAX_BYTES + "\n")
            put(CONFIG_ENTRY, JsonConfigRepository.toJson(config))
            put(DEVICE_ENTRY, device.format())
            put(LOG_ENTRY, clip(log.dump(), MAX_BYTES / 2))
        }
        val bytes = out.toByteArray()
        return if (bytes.size <= MAX_BYTES) bytes else bytes.copyOf(MAX_BYTES)
    }

    fun entriesOf(zipBytes: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                map[e.name] = zin.readBytes().toString(Charsets.UTF_8)
            }
        }
        return map
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "\n# …已截断（原 " + text.length + " 字符）\n"
}
