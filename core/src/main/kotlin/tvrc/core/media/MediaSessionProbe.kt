package tvrc.core.media

import tvrc.core.adb.AdbTransport

/** 取 dumpsys 文本这一层（L P1-2）：判定逻辑纯字符串可测，「去电视上取文本」由实现方给出。 */
interface MediaSessionTextSource {
    /** 返回 dumpsys media_session 的原始输出；失败返回空串。 */
    fun dumpMediaSessions(): String
}

/** adb 取文本实现：走 AdbTransport（注意 H-7：真实 ROM 的 dumpsys 格式差异未验，属交接项）。 */
class AdbMediaSessionSource(
    private val transport: AdbTransport,
    private val timeoutMs: Long = 10_000
) : MediaSessionTextSource {
    override fun dumpMediaSessions(): String {
        val result = transport.shell("dumpsys media_session", timeoutMs)
        return if (result.ok) result.stdout else ""
    }
}

data class MediaSessionState(
    val packageName: String,
    val active: Boolean,
    val playing: Boolean
)

/**
 * dumpsys media_session 文本判定（P1-2）。约定格式（基于 Android 8–13 常见输出）：
 *
 *   Media sessions:
 *     Session 2uhash: is.xyz.mpv/... (flag ...)
 *         active true
 *         PlaybackState: state=3, position=...  ← state 3 = Playing
 *
 * 规则：
 * - 会话头 = 含 "session" 且 "包名/" 形式的行；
 * - playing = 该会话块内出现 state=3（STATE_PLAYING）；
 * - 同包多会话：优先 playing 的那个，否则第一个；
 * - 找不到目标包的会话块 → null（调用方据此判「未在播」并保留 alive 语义）。
 */
object MediaSessionProbe {

    private val SESSION_HEADER = Regex("""(?i)session[^:]*:\s*([A-Za-z0-9_.]+)/""")
    private val STATE = Regex("""(?i)state\s*=\s*(\d+)""")
    private val ACTIVE = Regex("""(?i)\bactive\b\s*[=: ]\s*(true|false)""")
    private const val STATE_PLAYING = 3

    fun isPlaying(dump: String, targetPackage: String): Boolean =
        parse(dump, targetPackage)?.playing == true

    fun parse(dump: String, targetPackage: String): MediaSessionState? {
        if (dump.isBlank()) return null
        var current: MutableSession? = null
        val sessions = ArrayList<MediaSessionState>()

        for (rawLine in dump.lineSequence()) {
            val header = SESSION_HEADER.find(rawLine)
            if (header != null) {
                if (current != null) sessions.add(current.build())
                current = MutableSession(header.groupValues[1])
                continue
            }
            current?.let { s ->
                STATE.find(rawLine)?.let { m ->
                    val value = m.groupValues[1].toIntOrNull() ?: return@let
                    if (!s.sawState) {
                        s.sawState = true
                        s.playing = value == STATE_PLAYING
                    }
                }
                ACTIVE.find(rawLine)?.let { m ->
                    s.active = m.groupValues[1].equals("true", ignoreCase = true)
                }
            }
        }
        current?.let { sessions.add(it.build()) }

        val matching = sessions.filter { it.packageName == targetPackage }
        if (matching.isEmpty()) return null
        return matching.firstOrNull { it.playing } ?: matching.first()
    }

    private class MutableSession(val packageName: String) {
        var active: Boolean = false
        var playing: Boolean = false
        var sawState: Boolean = false

        fun build() = MediaSessionState(packageName, active, playing)
    }
}
