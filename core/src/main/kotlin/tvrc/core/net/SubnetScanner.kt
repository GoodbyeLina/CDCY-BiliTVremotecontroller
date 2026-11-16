package tvrc.core.net

import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** 单主机存活探测（可注入：真机用 socket connect，测试用假实现）。 */
fun interface HostProbe {
    fun isAlive(host: String, port: Int): Boolean
}

/** 一次解析的结果：原地可用 / 漂移到新地址 / 全网段都不可达。 */
sealed interface TvHostResolution {
    data class Kept(val host: String) : TvHostResolution
    data class Moved(val host: String, val from: String) : TvHostResolution
    data object NotFound : TvHostResolution
}

/**
 * IP 漂移救援（L P1-3，对应 K 的 R-04）：电视 DHCP 换地址后，按 /24 网段重扫，
 * 找到第一个可达地址继续用。
 *
 * 约束（刻意收窄）：
 * - 只接受 /24（isSlash24 校验）；给 /16、/8 之类直接抛 IllegalArgumentException，
 *   避免误把整个内网扫一遍。
 * - 枚举 .1–.254（排除网络号 .0 与广播 .255）。
 * - 结果按 IP 升序确定，保证「取第一个可达」可复现。
 */
class SubnetRescanner(
    private val probe: HostProbe,
    private val parallelism: Int = 32
) {

    companion object {
        private val CIDR = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})$""")

        fun isSlash24(cidr: String): Boolean {
            val m = CIDR.matchEntire(cidr.trim()) ?: return false
            val octets = (1..4).map { m.groupValues[it].toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return m.groupValues[5] == "24"
        }
    }

    /** 枚举 /24 内全部候选（.1–.254），升序。 */
    fun candidates(cidr: String): List<String> {
        val m = CIDR.matchEntire(cidr.trim())
            ?: throw IllegalArgumentException("不是合法 CIDR：" + cidr)
        if (!isSlash24(cidr)) {
            throw IllegalArgumentException("只支持 /24 网段，拒绝：" + cidr)
        }
        val prefix = m.groupValues[1] + "." + m.groupValues[2] + "." + m.groupValues[3]
        return (1..254).map { prefix + "." + it }
    }

    /** 扫描 /24，返回可达地址（升序）。 */
    fun scan(cidr: String, port: Int): List<String> {
        val hosts = candidates(cidr)
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, 64))
        return try {
            val tasks = hosts.map { host -> Callable { host to probe.isAlive(host, port) } }
            pool.invokeAll(tasks)
                .map { it.get() }
                .filter { it.second }
                .map { it.first }
                .sortedBy { ip -> ip.substringAfterLast(".").toInt() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 先信原地址；失效则重扫，取第一个可达（排除原地址自身）。 */
    fun resolve(currentHost: String, port: Int, cidr: String): TvHostResolution {
        if (probe.isAlive(currentHost, port)) return TvHostResolution.Kept(currentHost)
        val found = scan(cidr, port).firstOrNull { it != currentHost }
            ?: return TvHostResolution.NotFound
        return TvHostResolution.Moved(found, currentHost)
    }
}
