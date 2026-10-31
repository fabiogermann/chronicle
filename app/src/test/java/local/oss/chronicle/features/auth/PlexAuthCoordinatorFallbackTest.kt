package local.oss.chronicle.features.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.data.sources.plex.model.OAuthResponse
import local.oss.chronicle.util.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the manual-fallback auth path (fix: auth deep-link loop on devices
 * where the App Link redirect doesn't fire, e.g. Vivo/Funtouch).
 *
 * When the OAuth browser page can't redirect back into the app, the user is
 * stuck: the page freezes and the app just keeps polling. The fix offers the
 * manual-linking path (plex.tv/link) from the very start of polling — users in
 * the loop must not have to wait on the right screen at the right moment to
 * discover it. The 4-char code itself only exists once the user asks for it
 * (it needs a separate non-strong PIN), so Polling carries the strong PIN's
 * code until then; the UI keys off [PlexAuthState.Polling.shouldShowManualFallback].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlexAuthCoordinatorFallbackTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var plexLoginRepo: IPlexLoginRepo

    @Before
    fun setUp() {
        plexLoginRepo = mockk(relaxed = true)
        // Never becomes logged in (user is stuck on the frozen browser page)
        val loginEvent = MutableLiveData<Event<IPlexLoginRepo.LoginState>>()
        loginEvent.value = Event(IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS)
        every { plexLoginRepo.loginEvent } returns loginEvent
        // Match the CURRENT signature — the coordinator polls with an explicit pin id.
        coEvery { plexLoginRepo.checkForOAuthAccessToken(any()) } returns Unit
    }

    /**
     * Runs [block] with a coordinator whose poll loop is (a) driven by the
     * test's virtual clock so wall-clock timeouts fire in virtual time, and
     * (b) launched in [backgroundScope], which runTest cancels at the end —
     * both so a failing expectation can never spin the loop at full CPU speed
     * and OOM the test worker (previously happened with no ticker + no
     * cancellation). [runTest] itself is also given a 10 s dispatch timeout.
     */
    private fun runCoordinatorTest(
        block: suspend TestScope.(PlexAuthCoordinator) -> Unit,
    ) = runTest(timeout = 10.seconds) {
        val coordinator =
            PlexAuthCoordinator(
                plexLoginRepo,
                backgroundScope,
                ticker = PlexAuthCoordinator.Ticker { testScheduler.currentTime },
            )
        block(coordinator)
        coordinator.dispose()
    }

    @Test
    fun `polling state carries the pin code so the UI can offer manual linking`() =
        runCoordinatorTest { coordinator ->
            coEvery { plexLoginRepo.postOAuthPin(any()) } returns
                OAuthResponse(id = 42L, clientIdentifier = "client-x", code = "ABCD", authToken = null)

            val flow = coordinator.startAuth()

            // Move past the initial WaitingForUser into Polling
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)

            val state = flow.value
            assertTrue("expected Polling, was $state", state is PlexAuthState.Polling)
            assertEquals("ABCD", (state as PlexAuthState.Polling).pinCode)
        }

    @Test
    fun `manual fallback is flagged from the first polling emission`() =
        runCoordinatorTest { coordinator ->
            coEvery { plexLoginRepo.postOAuthPin(any()) } returns
                OAuthResponse(id = 7L, clientIdentifier = "client-x", code = "WXYZ", authToken = null)

            val flow = coordinator.startAuth()

            // First Polling emission already offers plex.tv/link + the code: on devices
            // with broken App Links the deep link never fires, so there is nothing to
            // wait for.
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)
            val state = flow.value as PlexAuthState.Polling
            assertTrue(
                "manual fallback must be offered immediately, not only after " +
                    "MANUAL_FALLBACK_AFTER_MS",
                state.shouldShowManualFallback,
            )
        }
}
