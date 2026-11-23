package tvrc.core.test

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvrc.core.http.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MediaTransferTest {
    private val secretUrl = "https://cdn.invalid/video?signature=DO_NOT_LOG"
    private val bv = "BV1xx411c7mD"
    private fun response(status: Int = 200, body: InputStream = ByteArrayInputStream("abcd".toByteArray()), length: Long = 4) =
        UpstreamResponse(status, emptyMap(), body, length)

    private class Fetch(val action: (Int, Map<String, String>) -> UpstreamResponse) : UpstreamFetcher {
        val calls = AtomicInteger()
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        override fun open(url: String, requestHeaders: Map<String, String>, timeoutMs: Int): UpstreamResponse {
            headers.add(requestHeaders.toMap())
            return action(calls.incrementAndGet(), requestHeaders)
        }
    }
    private fun request(port: Int, method: String = "GET", live: Boolean = false, range: Boolean = false): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 4000
            val path = if (live) "/live?room=7" else "/video?bvid=$bv"
            s.getOutputStream().write(("$method $path HTTP/1.1\r\n" +
                (if (range) "Range: bytes=1-2\r\n" else "") + "\r\n").toByteArray())
            String(s.getInputStream().readBytes(), Charsets.ISO_8859_1)
        }
    private fun server(fetch: UpstreamFetcher, events: MutableList<MediaTransferEvent>, resolve: () -> String? = { secretUrl }) =
        MiniHttpServer(port = 0, bindAddress = "127.0.0.1", fetcher = fetch,
            liveRooms = { setOf(7L) }, resolveLive = { resolve() }, resolveVideo = { resolve() },
            onMediaEvent = { events.add(it) }, retryBackoffMs = 0)

    @Test fun eventsAreSafeAndCountActualBodyBytes() {
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        server(Fetch { _, _ -> response() }, events).use { s ->
            assertTrue(request(s.start()).endsWith("abcd"))
        }
        assertEquals(listOf(MediaPhase.REQUEST, MediaPhase.RESPONSE, MediaPhase.FIRST_BYTE, MediaPhase.END), events.map { it.phase })
        assertEquals(4L, events.last().bytes)
        assertTrue(events.all { it.mediaIdentity == bv && it.kind == MediaKind.VIDEO })
        assertEquals(1, events.map { it.requestId }.distinct().size)
        assertFalse(events.toString().contains("signature"))
        assertFalse(events.toString().contains("https://"))
    }

    @Test fun temporaryStatusHasTwoRetriesAndClosesEveryAttempt() {
        val closed = AtomicInteger()
        val fetch = Fetch { _, _ -> response(503, object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed.incrementAndGet(); super.close() }
        }, 0) }
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        server(fetch, events).use { assertTrue(request(it.start()).startsWith("HTTP/1.1 502")) }
        assertEquals(3, fetch.calls.get())
        assertEquals(3, closed.get())
        assertEquals(2, events.count { it.phase == MediaPhase.RETRY })
        assertEquals(MediaFailure.UPSTREAM_STATUS, events.last().failure)
        assertEquals(503, events.last().httpStatus)
    }

    @Test fun transientOpenRetriesButHeadDoesNotRetry() {
        val fetch = Fetch { _, _ -> throw SocketTimeoutException(secretUrl) }
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        server(fetch, events).use { request(it.start()) }
        assertEquals(3, fetch.calls.get())
        assertEquals(MediaFailure.UPSTREAM_OPEN, events.last().failure)
        assertEquals("SocketTimeoutException", events.last().exceptionType)
        assertFalse(events.toString().contains("DO_NOT_LOG"))
        val head = Fetch { _, _ -> response(503) }
        server(head, CopyOnWriteArrayList()).use { request(it.start(), method = "HEAD") }
        assertEquals(1, head.calls.get())
    }

    @Test fun expiredUrlIsResolvedOnlyOnceAgainAndPreservesRange() {
        val resolved = AtomicInteger()
        val fetch = Fetch { n, _ -> response(if (n == 1) 403 else 200) }
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        server(fetch, events) { resolved.incrementAndGet(); secretUrl }.use {
            val reply = request(it.start(), range = true)
            assertTrue(reply.startsWith("HTTP/1.1 206"))
            assertTrue(reply.endsWith("bc"))
        }
        assertEquals(2, resolved.get())
        assertEquals(2, fetch.calls.get())
        assertTrue(fetch.headers.all { it["Range"] == "bytes=1-2" })
        val foreverExpired = Fetch { _, _ -> response(410) }
        server(foreverExpired, CopyOnWriteArrayList()).use { request(it.start()) }
        assertEquals(2, foreverExpired.calls.get())
    }

    @Test fun liveRetriesNeverAddRangeAndUseRoomIdentity() {
        val fetch = Fetch { n, _ -> response(if (n == 1) 429 else 200) }
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        server(fetch, events).use { request(it.start(), live = true, range = true) }
        assertEquals(2, fetch.calls.get())
        assertTrue(fetch.headers.all { h -> h.keys.none { it.equals("Range", true) } })
        assertTrue(events.all { it.mediaIdentity == "room:7" && it.kind == MediaKind.LIVE })
    }

    @Test fun upstreamReadFailureAfterCommitNeverRestartsVideo() {
        val closed = AtomicInteger()
        val fetch = Fetch { _, _ -> response(body = object : InputStream() {
            var reads = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reads++ > 0) throw SocketTimeoutException(secretUrl)
                b[off] = 42
                return 1
            }
            override fun close() { closed.incrementAndGet() }
        }, length = -1) }
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        server(fetch, events).use { request(it.start()) }
        assertEquals(1, fetch.calls.get())
        assertEquals(1, closed.get())
        assertEquals(MediaFailure.UPSTREAM_READ, events.last().failure)
        assertEquals(1L, events.last().bytes)
        assertFalse(events.any { it.phase == MediaPhase.RETRY })
        assertFalse(events.any { it.phase == MediaPhase.END })
    }

    @Test fun genericIoIsNotBlindlyRetriedAndResolverFailureIsSafe() {
        val fetch = Fetch { _, _ -> throw IOException(secretUrl) }
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        server(fetch, events).use { request(it.start()) }
        assertEquals(1, fetch.calls.get())
        assertEquals(MediaFailure.UPSTREAM_OPEN, events.last().failure)
        val resolving = CopyOnWriteArrayList<MediaTransferEvent>()
        server(fetch, resolving) { throw IllegalStateException(secretUrl) }.use { request(it.start()) }
        assertEquals(MediaFailure.RESOLVE, resolving.last().failure)
        assertFalse(resolving.toString().contains("DO_NOT_LOG"))
    }

    @Test fun closeUnblocksActiveUpstreamAndClientSocket() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val fetch = Fetch { _, _ -> response(body = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                released.await(3, TimeUnit.SECONDS)
                throw IOException("closed")
            }
            override fun close() { released.countDown() }
        }, length = -1) }
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        val s = server(fetch, events)
        Socket("127.0.0.1", s.start()).use { client ->
            client.soTimeout = 3000
            client.getOutputStream().write("GET /video?bvid=$bv HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            s.close()
            assertTrue(released.await(1, TimeUnit.SECONDS))
            client.getInputStream().readBytes()
        }
    }

    @Test fun clientDisconnectIsWriteFailureAndNeverRetried() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        val fetch = Fetch { _, _ ->
            opened.countDown()
            release.await(2, TimeUnit.SECONDS)
            response(body = ByteArrayInputStream(ByteArray(1024 * 1024)), length = 1024 * 1024)
        }
        MiniHttpServer(port = 0, bindAddress = "127.0.0.1", fetcher = fetch, resolveVideo = { secretUrl },
            onMediaEvent = { events.add(it); if (it.phase == MediaPhase.FAILURE) failed.countDown() },
            retryBackoffMs = 0).use { s ->
            val client = Socket("127.0.0.1", s.start())
            client.getOutputStream().write("GET /video?bvid=$bv HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            client.setSoLinger(true, 0)
            client.close()
            release.countDown()
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertEquals(MediaFailure.CLIENT_WRITE, events.last().failure)
            assertEquals(1, fetch.calls.get())
        }
    }

    @Test fun headHasNoMediaBodyEventsAndRejectedIdentityIsSanitized() {
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        val fetch = Fetch { _, _ -> response() }
        server(fetch, events).use { s ->
            request(s.start(), method = "HEAD")
            assertTrue(events.all { it.headOnly })
            assertFalse(events.any { it.phase in listOf(MediaPhase.FIRST_BYTE, MediaPhase.PROGRESS, MediaPhase.END) })
            Socket("127.0.0.1", s.boundPort).use { client ->
                client.soTimeout = 2000
                client.getOutputStream().write("GET /video?bvid=https%3A%2F%2Fsecret.invalid HTTP/1.1\r\n\r\n".toByteArray())
                client.getInputStream().readBytes()
            }
        }
        assertEquals("invalid", events.last().mediaIdentity)
        assertEquals(MediaPhase.FAILURE, events.last().phase)
        assertFalse(events.toString().contains("secret.invalid"))
    }

    @Test fun cancelMediaKeepsStatusAndNewMediaAvailable() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fetch = Fetch { n, _ ->
            if (n == 1) {
                opened.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            response()
        }
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        server(fetch, events).use { s ->
            val client = Socket("127.0.0.1", s.start())
            client.getOutputStream().write("GET /video?bvid=$bv HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            s.cancelMediaRequests()
            release.countDown()
            client.soTimeout = 2000
            assertTrue(client.getInputStream().readBytes().isEmpty())
            client.close()
            Socket("127.0.0.1", s.boundPort).use { status ->
                status.soTimeout = 2000
                status.getOutputStream().write("GET /status HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(String(status.getInputStream().readBytes()).startsWith("HTTP/1.1 200"))
            }
            assertTrue(request(s.boundPort).endsWith("abcd"))
        }
    }

    @Test fun progressRequiresDeliveredBytesNotAnIdleWatchdog() {
        val events = CopyOnWriteArrayList<MediaTransferEvent>()
        val fetch = Fetch { _, _ -> response(body = object : ByteArrayInputStream("abcd".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(5)
                return super.read(b, off, minOf(len, 1))
            }
        }) }
        MiniHttpServer(port = 0, bindAddress = "127.0.0.1", fetcher = fetch, resolveVideo = { secretUrl },
            progressIntervalMs = 1, onMediaEvent = { events.add(it) }).use { request(it.start()) }
        assertTrue(events.any { it.phase == MediaPhase.PROGRESS && it.bytes > 0 && it.elapsedMs > 0 })
        assertEquals(4L, events.last().bytes)
    }

    @Test fun throwingObserverCannotBreakMediaTransfer() {
        MiniHttpServer(port = 0, bindAddress = "127.0.0.1", fetcher = Fetch { _, _ -> response() },
            resolveVideo = { secretUrl }, onMediaEvent = { throw IllegalStateException(secretUrl) }).use {
            assertTrue(request(it.start()).endsWith("abcd"))
        }
    }
}
