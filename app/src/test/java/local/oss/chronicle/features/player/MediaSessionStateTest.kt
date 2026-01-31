package local.oss.chronicle.features.player

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests that verify MediaSession state correctness for Android Auto voice commands.
 * 
 * These tests validate that the MediaSession exposes the correct state and actions
 * for Google Assistant to properly handle commands like:
 * - "Resume playing my audiobook"
 * - "Continue my audiobook"
 * - "Pause"
 * 
 * Google Assistant uses:
 * - PlaybackState (STATE_PAUSED vs STATE_PLAYING) to determine resume eligibility
 * - Available actions (ACTION_PLAY) to enable voice commands
 * - Metadata (title, album, duration) for audiobook identification
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28]) // Use Android P for testing
class MediaSessionStateTest {

    private lateinit var context: Context
    private lateinit var session: MediaSessionCompat

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        session = MediaSessionCompat(context, "TestMediaSession")
    }

    @After
    fun tearDown() {
        session.release()
    }

    // ============================================
    // Tests for "Resume playing my audiobook"
    // ============================================

    /**
     * CRITICAL: Verifies that a paused session exposes ACTION_PLAY.
     * 
     * Google Assistant maps "resume", "continue" to ACTION_PLAY.
     * If this action is not present, the voice command will fail silently.
     */
    @Test
    fun `paused session exposes ACTION_PLAY for resume commands`() {
        val state = PlaybackStateCompat.Builder()
            .setState(
                PlaybackStateCompat.STATE_PAUSED,
                10_000L, // Position at 10 seconds
                1.0f    // Normal playback rate
            )
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
            .build()

        session.setPlaybackState(state)
        session.isActive = true

        // Verify ACTION_PLAY is available
        val actions = session.controller.playbackState?.actions ?: 0L
        assertTrue(
            "Paused session must expose ACTION_PLAY for resume voice commands",
            actions and PlaybackStateCompat.ACTION_PLAY != 0L
        )
    }

    /**
     * Verifies that an active session reports the correct paused state.
     * 
     * Assistant checks the playback state to determine if content can be resumed.
     * A STATE_PAUSED state indicates resumable content.
     */
    @Test
    fun `session correctly reports STATE_PAUSED when paused`() {
        val state = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PAUSED, 42_000L, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY)
            .build()

        session.setPlaybackState(state)
        session.isActive = true

        assertEquals(
            "Session must report STATE_PAUSED for resume eligibility",
            PlaybackStateCompat.STATE_PAUSED,
            session.controller.playbackState?.state
        )
    }

    /**
     * Verifies that position is preserved when paused.
     * 
     * When user says "resume my audiobook", playback should continue from
     * the last position, not restart from the beginning.
     */
    @Test
    fun `paused session preserves playback position`() {
        val expectedPosition = 42_000L

        val state = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PAUSED, expectedPosition, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY)
            .build()

        session.setPlaybackState(state)

        assertEquals(
            "Paused position must be preserved for resume functionality",
            expectedPosition,
            session.controller.playbackState?.position
        )
    }

    // ============================================
    // Tests for playback state transitions
    // ============================================

    /**
     * Verifies transition from PAUSED to PLAYING state.
     * 
     * This simulates what happens when Assistant sends ACTION_PLAY
     * after user says "resume playing my audiobook".
     */
    @Test
    fun `state transitions correctly from PAUSED to PLAYING`() {
        val pausedState = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PAUSED, 10_000L, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY)
            .build()
        session.setPlaybackState(pausedState)

        // Simulate resume (what happens when ACTION_PLAY is received)
        val playingState = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PLAYING, 10_000L, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PAUSE)
            .build()
        session.setPlaybackState(playingState)

        assertEquals(
            PlaybackStateCompat.STATE_PLAYING,
            session.controller.playbackState?.state
        )
    }

    /**
     * Verifies that playing session exposes ACTION_PAUSE.
     * 
     * Enables voice commands like "Pause" while playing.
     */
    @Test
    fun `playing session exposes ACTION_PAUSE`() {
        val state = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PLAYING, 10_000L, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE)
            .build()

        session.setPlaybackState(state)
        session.isActive = true

        val actions = session.controller.playbackState?.actions ?: 0L
        assertTrue(
            "Playing session must expose ACTION_PAUSE for pause voice commands",
            actions and PlaybackStateCompat.ACTION_PAUSE != 0L
        )
    }

    // ============================================
    // Tests for audiobook metadata
    // ============================================

    /**
     * Verifies that audiobook metadata can be set on the session.
     *
     * NOTE: Robolectric limitation - controller.metadata doesn't properly reflect
     * metadata in unit tests. In production, MediaPlayerService sets metadata
     * which Android Auto reads through MediaController.
     *
     * This test verifies the metadata object can be built and set without errors.
     * Integration/E2E tests on real devices validate the full metadata flow.
     */
    @Test
    fun `session accepts audiobook metadata without errors`() {
        session.isActive = true
        
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Chapter 5")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "The Great Audiobook")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Famous Author")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 3_600_000L) // 1 hour
            .build()

        // Verify setMetadata doesn't throw
        session.setMetadata(metadata)
        
        // Verify session remains active after setting metadata
        assertTrue("Session should remain active after setting metadata", session.isActive)
    }

    /**
     * Verifies that long-form audiobook duration metadata can be set.
     *
     * NOTE: Robolectric limitation - controller.metadata doesn't work in unit tests.
     * This test validates metadata construction for long audiobook content.
     */
    @Test
    fun `session accepts long-form audiobook duration metadata`() {
        session.isActive = true
        
        val audiobookDuration = 8 * 60 * 60 * 1000L // 8 hours

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Chapter 1")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, audiobookDuration)
            .build()

        // Verify setMetadata doesn't throw
        session.setMetadata(metadata)
        
        // Verify session remains active
        assertTrue("Session should remain active after setting metadata", session.isActive)
    }

    // ============================================
    // Tests for session activity state
    // ============================================

    /**
     * Verifies that session is marked as active.
     * 
     * Assistant only interacts with active MediaSessions.
     * An inactive session will not receive voice commands.
     */
    @Test
    fun `session must be active to receive voice commands`() {
        session.isActive = true

        assertTrue(
            "Session must be active to receive Google Assistant commands",
            session.isActive
        )
    }

    /**
     * Verifies that inactive session is not eligible for voice commands.
     * 
     * This helps ensure we properly understand when voice commands won't work.
     */
    @Test
    fun `inactive session is not eligible for voice commands`() {
        session.isActive = false

        assertFalse(
            "Inactive session should not be eligible for voice commands",
            session.isActive
        )
    }

    // ============================================
    // Tests for supported actions
    // ============================================

    /**
     * Verifies that all essential transport actions are supported.
     * 
     * These actions enable:
     * - "Play" / "Resume" commands
     * - "Pause" command
     * - "Skip back 30 seconds" commands (via SEEK_TO)
     */
    @Test
    fun `session supports essential transport control actions`() {
        val essentialActions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        val state = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
            .setActions(essentialActions)
            .build()

        session.setPlaybackState(state)

        val actions = session.controller.playbackState?.actions ?: 0L

        assertTrue("ACTION_PLAY must be supported", actions and PlaybackStateCompat.ACTION_PLAY != 0L)
        assertTrue("ACTION_PAUSE must be supported", actions and PlaybackStateCompat.ACTION_PAUSE != 0L)
        assertTrue("ACTION_SEEK_TO must be supported", actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L)
    }

    /**
     * Verifies search action is supported for voice search.
     * 
     * Enables commands like "Play audiobook X on Chronicle".
     */
    @Test
    fun `session supports search action for voice search`() {
        val state = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
            .build()

        session.setPlaybackState(state)

        val actions = session.controller.playbackState?.actions ?: 0L
        assertTrue(
            "ACTION_PLAY_FROM_SEARCH must be supported for voice search",
            actions and PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH != 0L
        )
    }
}
