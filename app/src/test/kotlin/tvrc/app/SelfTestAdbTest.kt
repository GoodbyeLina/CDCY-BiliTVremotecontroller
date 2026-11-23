package tvrc.app

import org.junit.Assert.*
import org.junit.Test
import tvrc.core.adb.AdbClient
import tvrc.core.adb.ShellResult
import tvrc.core.config.AppConfig
import java.net.ConnectException

class SelfTestAdbTest {
    private class Client(val packages: ShellResult, val connectionError: Exception? = null) : AdbClient {
        val commands = mutableListOf<String>()
        var closed = false
        override fun connect(): Boolean {
            connectionError?.let { throw it }
            return true
        }
        override fun shell(command: String, timeoutMs: Long): ShellResult {
            commands += command
            return if (command == "echo TVRC_ADB_OK") ShellResult(0, "TVRC_ADB_OK\n") else packages
        }
        override fun close() { closed = true }
    }
    private val config = AppConfig(tvHost = "192.168.10.139")

    @Test fun connectedAdbDoesNotRequireARunningPlayer() {
        val client = Client(ShellResult(0, "package:is.xyz.mpv\npackage:com.android.tv.settings\n"))
        val result = SelfTest.checkAdb(config) { client }
        assertTrue(result[0].ok)
        assertTrue(result[1].ok)
        assertEquals(listOf("echo TVRC_ADB_OK", "pm list packages"), client.commands)
        assertTrue(client.closed)
    }

    @Test fun missingPlayerDoesNotMeanAdbIsDisconnected() {
        val client = Client(ShellResult(0, "package:com.tv.launcher\n"))
        val result = SelfTest.checkAdb(config) { client }
        assertTrue(result[0].ok)
        assertFalse(result[1].ok)
        assertTrue(result[1].detail.startsWith("未安装"))
    }

    @Test fun connectionFailureDoesNotInventMissingPackages() {
        val client = Client(ShellResult(0), ConnectException("Connection refused"))
        val result = SelfTest.checkAdb(config) { client }
        assertFalse(result[0].ok)
        assertTrue(result[0].detail.contains("5555"))
        assertTrue(result[1].detail.startsWith("尚未检测"))
        assertFalse(result[1].detail.contains("未安装"))
        assertTrue(client.closed)
    }

    @Test fun runningTransportIsProbedWithoutOpeningOrClosingAnotherConnection() {
        val running = Client(ShellResult(0, "package:is.xyz.mpv\n"))
        val result = SelfTest.checkAdb(config, activeShell = running::shell) {
            error("must not create a second ADB connection")
        }
        assertTrue(result[0].ok)
        assertTrue(result[1].ok)
        assertEquals(listOf("echo TVRC_ADB_OK", "pm list packages"), running.commands)
        assertFalse(running.closed)
    }

    @Test fun failedRunningProbeDoesNotTrustCachedStateOrTryAnotherConnection() {
        val commands = mutableListOf<String>()
        val result = SelfTest.checkAdb(config, activeShell = { command, _ ->
            commands += command
            ShellResult(-1, stderr = "busy: another adb command is still running")
        }) { error("must not create a second ADB connection") }
        assertFalse(result[0].ok)
        assertTrue(result[0].detail.contains("busy"))
        assertTrue(result[1].detail.startsWith("尚未检测"))
        assertEquals(listOf("echo TVRC_ADB_OK"), commands)
    }

    @Test fun runningProbeMustReturnExpectedEchoNotJustSuccessfulExit() {
        val result = SelfTest.checkAdb(config, activeShell = { _, _ -> ShellResult(0, "other") }) {
            error("must not create a second ADB connection")
        }
        assertFalse(result[0].ok)
        assertTrue(result[0].detail.contains("other"))
    }

    @Test fun runningPackageFailureDoesNotInvalidateSuccessfulEcho() {
        val running = Client(ShellResult(1, stderr = "permission denied"))
        val result = SelfTest.checkAdb(config, activeShell = running::shell) {
            error("must not create a second ADB connection")
        }
        assertTrue(result[0].ok)
        assertFalse(result[1].ok)
        assertTrue(result[1].detail.startsWith("尚未确认"))
        assertFalse(running.closed)
    }

    @Test fun failedPackageQueryLeavesAdbConnected() {
        val client = Client(ShellResult(1, stderr = "permission denied"))
        val result = SelfTest.checkAdb(config) { client }
        assertTrue(result[0].ok)
        assertTrue(result[1].detail.startsWith("尚未确认"))
    }
}
