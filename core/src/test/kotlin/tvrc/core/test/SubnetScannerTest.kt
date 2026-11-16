package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.net.HostProbe
import tvrc.core.net.SubnetRescanner
import tvrc.core.net.TvHostResolution

/** P1-3：注入假 probe，纯逻辑验证选目标行为。 */
class SubnetScannerTest {

    private val cidr = "192.168.10.0/24"

    private fun rescanner(alive: Set<String>) =
        SubnetRescanner(probe = HostProbe { host, _ -> host in alive }, parallelism = 8)

    @Test
    fun keepsCurrentWhenStillAlive() {
        val r = rescanner(setOf("192.168.10.139")).resolve("192.168.10.139", 5555, cidr)
        assertEquals(TvHostResolution.Kept("192.168.10.139"), r)
    }

    @Test
    fun movesToFirstReachableWhenCurrentDead() {
        val r = rescanner(setOf("192.168.10.23", "192.168.10.50"))
            .resolve("192.168.10.139", 5555, cidr)
        assertEquals(TvHostResolution.Moved("192.168.10.23", "192.168.10.139"), r, "按 IP 升序取第一个可达")
    }

    @Test
    fun multipleReachableReturnsSortedAscending() {
        val scanner = rescanner(setOf("192.168.10.200", "192.168.10.7", "192.168.10.31"))
        assertEquals(
            listOf("192.168.10.7", "192.168.10.31", "192.168.10.200"),
            scanner.scan(cidr, 5555)
        )
    }

    @Test
    fun allDeadMeansNotFound() {
        val r = rescanner(emptySet()).resolve("192.168.10.139", 5555, cidr)
        assertEquals(TvHostResolution.NotFound, r)
        assertTrue(rescanner(emptySet()).scan(cidr, 5555).isEmpty())
    }

    @Test
    fun rejectsNonSlash24AndInvalidCidr() {
        val scanner = rescanner(emptySet())
        assertThrows(IllegalArgumentException::class.java) { scanner.scan("192.168.0.0/16", 5555) }
        assertThrows(IllegalArgumentException::class.java) { scanner.scan("10.0.0.0/8", 5555) }
        assertThrows(IllegalArgumentException::class.java) { scanner.scan("192.168.10.1", 5555) }
        assertThrows(IllegalArgumentException::class.java) { scanner.scan("999.1.1.0/24", 5555) }
        assertFalse(SubnetRescanner.isSlash24("192.168.10.0/25"))
        assertTrue(SubnetRescanner.isSlash24(cidr))
    }

    @Test
    fun candidatesAreOneTo254Only() {
        val list = rescanner(emptySet()).candidates("192.168.10.0/24")
        assertEquals(254, list.size)
        assertEquals("192.168.10.1", list.first())
        assertEquals("192.168.10.254", list.last())
        assertFalse(list.contains("192.168.10.0"), "不扫网络号")
        assertFalse(list.contains("192.168.10.255"), "不扫广播地址")
    }
}
