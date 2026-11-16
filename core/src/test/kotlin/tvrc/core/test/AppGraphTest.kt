package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.AppGraph
import tvrc.core.PlaybackState
import tvrc.core.bili.BiliLiveApi
import tvrc.core.bili.FlvStream
import tvrc.core.bili.RoomInfo
import tvrc.core.bili.VideoUrlResolver
import tvrc.core.config.AppConfig
import java.io.IOException
import java.net.Socket

class AppGraphTest {

    private class FakeLiveApi(
        private val url: String = "http://up.example/live.flv",
        private val failRooms: Set<Long> = emptySet()
    ) : BiliLiveApi {
        override fun roomInfo(roomId: Long): RoomInfo = RoomInfo(roomId, true, "title", 1L)
        override fun flvStream(roomId: Long): FlvStream {
            if (roomId in failRooms) throw IOException("room offline: " + roomId)
            return FlvStream(roomId, url, "up.example")
        }
    }

    private class FakeVideoResolver(private val url: String = "http://up.example/v.mp4") : VideoUrlResolver {
        override fun resolve(bvid: String): String = url
    }

    private fun cfg(rooms: List<Long> = listOf(5017134L, 5064126L), idle: Int = 0) = AppConfig(
        source = "live",
        rooms = rooms,
        port = 0,
        publicHost = "127.0.0.1",
        switchSeconds = 60,
        idleTimeoutMinutes = idle
    )

    private fun newGraph(
        config: AppConfig = cfg(),
        tv: FakeTv = FakeTv(),
        clock: VirtualClock = VirtualClock(1000L),
        sched: VirtualScheduler = VirtualScheduler(clock),
        liveApi: FakeLiveApi = FakeLiveApi(),
        upstream: FakeUpstream = FakeUpstream().route(
            "http://up.example/live.flv", 200, mapOf("Content-Type" to "video/x-flv"), ByteArray(2048)
        ).route("http://up.example/v.mp4", 200, mapOf("Content-Type" to "video/mp4"), ByteArray(1024))
    ): AppGraph = AppGraph(
        config = config,
        tv = tv,
        liveApi = liveApi,
        videoResolver = FakeVideoResolver(),
        fetcher = upstream,
        scheduler = sched,
        clock = clock
    )

    private fun http(port: Int, path: String): Pair<Int, String> {
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").toByteArray())
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            val status = text.substringAfter("HTTP/1.1 ").substringBefore(" ").trim().toIntOrNull() ?: -1
            return status to text
        }
    }

    @Test
    fun startWiresProxyAndPlayback() {
        val tv = FakeTv()
        val g = newGraph(tv = tv)
        val port = g.start()
        try {
            assertTrue(port > 0, "端口应 >0")
            assertTrue(g.isRunning)
            assertEquals(listOf("wake", "launch:http://127.0.0.1:" + port + "/live?room=5017134"), tv.commands)
            assertTrue(g.engine.currentState is PlaybackState.Playing)
        } finally {
            g.close()
        }
    }

    @Test
    fun statusAndWhitelistRoutesServed() {
        val g = newGraph()
        val port = g.start()
        try {
            val (s200, body) = http(port, "/status")
            assertEquals(200, s200)
            assertTrue(body.contains("\"rooms\""))
            assertEquals(200, http(port, "/live?room=5017134").first)
            assertEquals(400, http(port, "/live?room=999").first)
            assertEquals(200, http(port, "/video?bvid=BV1xx411c7mD").first)
            assertEquals(404, http(port, "/nope").first)
            assertEquals(400, http(port, "/live?url=http%3A%2F%2Fevil").first)
        } finally {
            g.close()
        }
    }

    @Test
    fun watchdogRestartsWithPriorState() {
        val clock = VirtualClock(1000L)
        val sched = VirtualScheduler(clock)
        val g = newGraph(clock = clock, sched = sched)
        g.start()
        try {
            assertEquals(0, g.watchdogRestartCount)
            clock.now += 40_000L
            g.checkWatchdog()
            assertEquals(1, g.watchdogRestartCount)
            assertTrue(g.engine.currentState is PlaybackState.Starting || g.engine.currentState is PlaybackState.Playing)
        } finally {
            g.close()
        }
    }

    @Test
    fun idleTimeoutClosesProxy() {
        val clock = VirtualClock(1000L)
        val sched = VirtualScheduler(clock)
        val g = newGraph(config = cfg(idle = 1), clock = clock, sched = sched)
        g.start()
        val wasRunning = g.isRunning
        g.stop()
        sched.advanceBy(60_000L)
        assertTrue(wasRunning)
        assertFalse(g.isRunning, "空闲超时应关闭代理")
        g.close()
    }

    @Test
    fun upstreamResolveFailureBecomes502() {
        val g = newGraph(liveApi = FakeLiveApi(failRooms = setOf(5064126L)))
        val port = g.start()
        try {
            assertEquals(200, http(port, "/live?room=5017134").first)
            assertEquals(502, http(port, "/live?room=5064126").first)
        } finally {
            g.close()
        }
    }
}
