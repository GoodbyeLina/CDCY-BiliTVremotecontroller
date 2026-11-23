package tvrc.core.http

/** Transport telemetry only: bytes delivered to a socket do NOT prove a TV displayed a frame. */
data class MediaTransferEvent(
    val requestId: Long,
    /** Only room:<digits>, BV identifier, or direct; never a URL or signed query. */
    val mediaIdentity: String,
    val kind: MediaKind,
    val phase: MediaPhase,
    val httpStatus: Int? = null,
    val elapsedMs: Long = 0,
    val bytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val attempt: Int = 1,
    val failure: MediaFailure? = null,
    /** Class simple name only; exception messages may contain credentials. */
    val exceptionType: String? = null,
    val headOnly: Boolean = false
)

enum class MediaKind { LIVE, VIDEO }
enum class MediaPhase { REQUEST, RESPONSE, FIRST_BYTE, PROGRESS, END, FAILURE, RETRY }
enum class MediaFailure {
    RESOLVE, UPSTREAM_OPEN, UPSTREAM_READ, UPSTREAM_STATUS, CLIENT_WRITE, SERVER_CLOSED, INTERNAL
}
