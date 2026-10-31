package local.oss.chronicle.features.player

/**
 * Pure decision unit behind the playback-error broadcast handler in MainActivity.
 *
 * [PlaybackErrorRecoveryHandler] re-broadcasts [MediaPlayerService.ACTION_PLAYBACK_ERROR] with
 * [MediaPlayerService.EXTRA_IS_RECOVERING] = true while it is still retrying in the background,
 * and re-sends the same action with the extra = false when recovery has given up. Only the
 * terminal error should nag the user with an inline action; the transient ones get a plain
 * message so a self-healing hiccup doesn't cry wolf.
 *
 * Kept Android-free so the policy is unit-testable as a plain JUnit test.
 */
object PlaybackErrorUiDecider {
    /**
     * True when this playback error should be rendered with an inline "Run connection test"
     * action (i.e. recovery has given up and the failure is terminal). Errors that are still
     * being recovered from never offer the action.
     */
    fun offersConnectionTest(isRecovering: Boolean): Boolean = !isRecovering
}
