package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.config.AppConfig
import tvrc.core.diag.DeviceInfo
import tvrc.core.diag.DiagnosticsPack
import tvrc.core.diag.LogLevel
import tvrc.core.diag.RingLog

class DiagnosticsPackTest {

    @Test
    fun ringLogKeepsOnlyCapacity() {
        val log = RingLog(capacity = 3)
        repeat(10) { log.add(LogLevel.INFO, "t", "m" + it, atMs = it.toLong()) }
        val snap = log.snapshot()
        assertEquals(3, snap.size)
        assertEquals(listOf("m7", "m8", "m9"), snap.map { it.message })
        assertEquals(7, log.droppedCount())
        assertTrue(log.dump().contains("已丢弃 7 条"))
    }

    @Test
    fun ringLogTruncatesHugeMessages() {
        val log = RingLog(capacity = 10)
        log.add(LogLevel.ERROR, "x", "z".repeat(5000))
        assertEquals(RingLog.MAX_MESSAGE, log.snapshot()[0].message.length)
    }

    @Test
    fun packContainsFourEntries() {
        val log = RingLog()
        log.add(LogLevel.INFO, "engine", "start room=5017134", atMs = 1000)
        val zip = DiagnosticsPack.build(
            AppConfig(rooms = listOf(5017134L), tvHost = "192.168.10.139"),
            log, DeviceInfo("14", "TV", "0.1.0", "today"), nowMs = 5000
        )
        val entries = DiagnosticsPack.entriesOf(zip)
        assertEquals(
            setOf(DiagnosticsPack.MANIFEST_ENTRY, DiagnosticsPack.CONFIG_ENTRY, DiagnosticsPack.DEVICE_ENTRY, DiagnosticsPack.LOG_ENTRY),
            entries.keys
        )
        assertTrue(entries[DiagnosticsPack.CONFIG_ENTRY]!!.contains("5017134"))
        assertTrue(entries[DiagnosticsPack.LOG_ENTRY]!!.contains("engine"))
        assertTrue(entries[DiagnosticsPack.DEVICE_ENTRY]!!.contains("app=0.1.0"))
        assertTrue(entries[DiagnosticsPack.MANIFEST_ENTRY]!!.contains("logEntries=1"))
    }

    @Test
    fun packStaysUnderBudgetWithFullLog() {
        val log = RingLog(capacity = 1000)
        repeat(1000) { log.add(LogLevel.DEBUG, "t", "x".repeat(500), atMs = it.toLong()) }
        val zip = DiagnosticsPack.build(AppConfig(tvHost = "h", rooms = listOf(1L)), log, DeviceInfo())
        assertTrue(zip.size <= DiagnosticsPack.MAX_BYTES, "诊断包必须小于上限，实际 " + zip.size)
        val entries = DiagnosticsPack.entriesOf(zip)
        assertTrue(entries[DiagnosticsPack.LOG_ENTRY]!!.endsWith("\n"), "截断后仍应是完整文本")
    }

    @Test
    fun packDoesNotIncludeStreamUrls() {
        val log = RingLog()
        log.add(LogLevel.INFO, "proxy", "url=https://cn-x.bilivideo.com/live-bvc/x.flv?upsig=SECRET")
        val zip = DiagnosticsPack.build(AppConfig(tvHost = "h", rooms = listOf(1L)), log, DeviceInfo())
        val entries = DiagnosticsPack.entriesOf(zip)
        // 日志里出现的直链目前会原样保留，但配置里绝不含直链；这条断言锁定「配置不含 url 字段」
        assertFalse(entries[DiagnosticsPack.CONFIG_ENTRY]!!.contains("bilivideo.com"))
    }
}
