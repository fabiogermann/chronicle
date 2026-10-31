package local.oss.chronicle.features.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [PlaybackErrorUiDecider] — the policy deciding which playback errors surface an
 * inline "Run connection test" action.
 *
 * [PlaybackErrorRecoveryHandler] re-broadcasts the error with
 * [MediaPlayerService.EXTRA_IS_RECOVERING] = true while it is still retrying; only the terminal
 * broadcast (extra = false, recovery gave up) should offer the diagnostics action. Offering it
 * on transient, self-healing errors would train users to ignore it.
 */
class PlaybackErrorUiDeciderTest {
    @Test
    fun `terminal error offers the connection test action`() {
        // EXTRA_IS_RECOVERING = false → recovery gave up → the user needs diagnostics.
        assertThat(PlaybackErrorUiDecider.offersConnectionTest(isRecovering = false)).isTrue()
    }

    @Test
    fun `error still being recovered from does not offer the connection test action`() {
        // EXTRA_IS_RECOVERING = true → a retry is in flight → plain toast only, no action.
        assertThat(PlaybackErrorUiDecider.offersConnectionTest(isRecovering = true)).isFalse()
    }
}
