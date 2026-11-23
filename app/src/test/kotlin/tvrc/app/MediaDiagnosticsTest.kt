package tvrc.app

import org.junit.Assert.*
import org.junit.Test
import tvrc.core.http.*

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class MediaDiagnosticsTest {
    private fun event(phase: MediaPhase, failure: MediaFailure? = null, status: Int? = null) =
        MediaTransferEvent(7, "room:740", MediaKind.LIVE, phase, httpStatus = status,
            bytes = 4096, elapsedMs = 15000, bytesPerSecond = 273, failure = failure)

    @Test fun firstByteDoesNotClaimFirstFrame() {
        val d = MediaDiagnostics.describe(event(MediaPhase.FIRST_BYTE))
        assertTrue(d.summary.contains("不代表首帧"))
        assertTrue(d.details.contains("4096"))
        assertTrue(d.details.contains("273"))
        assertTrue(d.details.contains("画面流畅度未知"))
    }
    @Test fun clientCloseIsNotNetworkFailureOrAutomaticRestart() {
        val d = MediaDiagnostics.describe(event(MediaPhase.FAILURE, MediaFailure.CLIENT_WRITE))
        assertEquals(TraceStatus.INFO, d.status)
        assertTrue(d.summary.contains("拖动进度"))
        assertTrue(d.summary.contains("不自动重开"))
    }
    @Test fun restrictedAndRateLimitedResponsesHaveSpecificGuidance() {
        assertTrue(MediaDiagnostics.describe(event(MediaPhase.FAILURE, MediaFailure.UPSTREAM_STATUS, 403)).summary.contains("访问受限"))
        assertTrue(MediaDiagnostics.describe(event(MediaPhase.FAILURE, MediaFailure.UPSTREAM_STATUS, 429)).summary.contains("避免连续点击"))
    }
    @Test fun midstreamFailureDoesNotPromiseSeamlessRecovery() {
        val d = MediaDiagnostics.describe(event(MediaPhase.FAILURE, MediaFailure.UPSTREAM_READ))
        assertEquals(TraceStatus.ERROR, d.status)
        assertTrue(d.summary.contains("视频不会自动从头重开"))
        assertTrue(d.summary.contains("确认开播"))
    }
    @Test fun retryIsExplicitAndBoundToRequestNotPlayerRestart() {
        val d = MediaDiagnostics.describe(event(MediaPhase.RETRY).copy(attempt = 3))
        assertEquals(TraceStatus.WARNING, d.status)
        assertTrue(d.summary.contains("第 3 次"))
        assertTrue(d.summary.contains("尚未重新启动电视"))
    }
    @Test fun staleMonitorCannotSupersedeStop() {
        val gate = PlaybackRequestGate()
        val observed = gate.snapshot()
        val stop = gate.next()
        assertNull(gate.nextIfCurrent(observed))
        assertTrue(gate.isCurrent(stop))
        val fresh = gate.nextIfCurrent(gate.snapshot())
        assertNotNull(fresh)
        assertTrue(gate.isCurrent(fresh!!))
    }
    @Test fun playbackCallbackCannotClearManualStop() {
        Ctl.onUserStopped()
        Ctl.onPlaying("late preparation")
        assertTrue(Ctl.userStopped)
        Ctl.onExplicitPlay()
        assertFalse(Ctl.userStopped)
    }
    @Test fun stopOrNewPlayInvalidatesEarlierPreparation() {
        val gate = PlaybackRequestGate()
        val old = gate.next()
        assertTrue(gate.isCurrent(old))
        val stop = gate.next()
        assertFalse(gate.isCurrent(old))
        val newer = gate.next()
        assertFalse(gate.isCurrent(stop))
        assertTrue(gate.isCurrent(newer))
    }
}
