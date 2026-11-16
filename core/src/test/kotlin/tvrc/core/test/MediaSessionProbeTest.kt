package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.media.MediaSessionProbe

/** P1-2：判定逻辑纯字符串可测；「去电视上取文本」是 H-7 交接项。 */
class MediaSessionProbeTest {

    private val pkg = "is.xyz.mpv"

    @Test
    fun playingWhenStateThree() {
        val dump = """
            MEDIA_SESSION_SERVICE
            Media sessions:
              Session 2uhash: is.xyz.mpv/tvrc (flag 0x1)
                active true
                PlaybackState: state=3, position=12345, bufferedPosition=12345
        """.trimIndent()
        val state = MediaSessionProbe.parse(dump, pkg)!!
        assertTrue(state.playing)
        assertTrue(state.active)
        assertTrue(MediaSessionProbe.isPlaying(dump, pkg))
    }

    @Test
    fun pausedWhenStateNotThree() {
        val dump = """
            Media sessions:
              Session abc: is.xyz.mpv/x
                active true
                PlaybackState: state=2, position=10
        """.trimIndent()
        val state = MediaSessionProbe.parse(dump, pkg)!!
        assertFalse(state.playing)
        assertFalse(MediaSessionProbe.isPlaying(dump, pkg))
    }

    @Test
    fun emptyDumpIsNull() {
        assertNull(MediaSessionProbe.parse("", pkg))
        assertNull(MediaSessionProbe.parse("   \n  \n", pkg))
        assertFalse(MediaSessionProbe.isPlaying("", pkg))
    }

    @Test
    fun picksPlayingSessionAmongMultipleOfSamePackage() {
        val dump = """
            Media sessions:
              Session a: is.xyz.mpv/one
                active false
                PlaybackState: state=2
              Session b: is.xyz.mpv/two
                active true
                PlaybackState: state=3
              Session c: com.other.app/x
                active true
                PlaybackState: state=3
        """.trimIndent()
        val state = MediaSessionProbe.parse(dump, pkg)!!
        assertTrue(state.playing)
        assertTrue(state.active, "应选中 playing 且 active 的那个会话")
    }

    @Test
    fun otherPackageOnlyIsNull() {
        val dump = """
            Media sessions:
              Session c: com.other.app/x
                active true
                PlaybackState: state=3
        """.trimIndent()
        assertNull(MediaSessionProbe.parse(dump, pkg))
        assertFalse(MediaSessionProbe.isPlaying(dump, pkg))
    }

    @Test
    fun malformedOutputIsNull() {
        assertNull(MediaSessionProbe.parse("Media session service crashed\nrandom garbage", pkg))
        assertNull(MediaSessionProbe.parse("Session without package suffix", pkg))
    }

    @Test
    fun activeEqualsFalseSyntaxAndKeyVariants() {
        val dump = """
            Media sessions:
              Session k: is.xyz.mpv/m
                active=false
                state=3
        """.trimIndent()
        val state = MediaSessionProbe.parse(dump, pkg)!!
        assertFalse(state.active)
        assertTrue(state.playing, "state=3 独立成行也应识别")
    }
}
