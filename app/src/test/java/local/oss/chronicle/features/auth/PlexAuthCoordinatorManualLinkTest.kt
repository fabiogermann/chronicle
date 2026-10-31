package local.oss.chronicle.features.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
 * Regression test for the OAuth login loop on devices where Android App Links
 * do not verify (e.g. some Vivo/Funtouch builds): the browser opens
 * auth.chronicleapp.net as a plain page and the deep-link back into the app
 * never fires. In that situation login must still succeed via polling alone,
 * and the manual-linking path (plex.tv/link) must be offered to the UI from
 * the very start of polling — not only after a 20 s timeout.
 *
 * The Polling state's pinCode here is the STRONG PIN's code (the browser
 * flow's PIN); the 4-char manual code only exists after the user requests it
 * — that behaviour lives in PlexAuthCoordinatorManualPinTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlexAuthCoordinatorManualLinkTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var plexLoginRepo: IPlexLoginRepo
    private lateinit var loginEvent: MutableLiveData<Event<IPlexLoginRepo.LoginState>>

    @Before
    fun setUp() {
        plexLoginRepo = mockk(relaxed = true)
        loginEvent = MutableLiveData(Event(IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS))
        every { plexLoginRepo.loginEvent } returns loginEvent
        // Match the CURRENT signature — the coordinator polls with an explicit pin id.
        coEvery { plexLoginRepo.checkForOAuthAccessToken(any()) } returns Unit
        coEvery { plexLoginRepo.postOAuthPin(any()) } returns
            OAuthResponse(id = 42L, clientIdentifier = "client-x", code = "ABCD", authToken = null)
    }

    /**
     * Coordinator under test runs in [backgroundScope] (auto-cancelled by runTest)
     * and on the test's virtual clock (timeouts fire in virtual time), so a
     * failing expectation can never spin the poll loop at full CPU speed and
     * OOM the test worker; the 10 s dispatch timeout is a further guard.
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
    fun `manual-linking code is available from the very start of polling`() =
        runCoordinatorTest { coordinator ->
            val flow = coordinator.startAuth()

            // Move past WaitingForUser into the first Polling emission.
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)

            val state = flow.value
            assertTrue("expected Polling, was $state", state is PlexAuthState.Polling)
            state as PlexAuthState.Polling
            assertEquals("ABCD", state.pinCode)
            assertTrue(
                "manual-linking path must be offered immediately, not only after " +
                    "${PlexAuthCoordinator.MANUAL_FALLBACK_AFTER_MS}ms",
                state.shouldShowManualFallback,
            )
        }

    @Test
    fun `polling alone reaches success without the browser ever returning`() =
        runCoordinatorTest { coordinator ->
            var tokenIssued = false
            coEvery { plexLoginRepo.checkForOAuthAccessToken(any()) } answers {
                if (tokenIssued) {
                    loginEvent.value = Event(IPlexLoginRepo.LoginState.LOGGED_IN_FULLY)
                }
            }

            val flow = coordinator.startAuth()
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)
            assertTrue("expected Polling, was ${flow.value}", flow.value is PlexAuthState.Polling)

            // The user finishes auth on the frozen browser page by manually linking
            // (e.g. at plex.tv/link). No deep link fires, onBrowserReturned() is
            // never called — polling alone must still carry the flow to Success.
            tokenIssued = true
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)
            runCurrent()

            assertEquals(
                "polling alone must reach Success with no onBrowserReturned() call",
                PlexAuthState.Success(),
                flow.value,
            )
        }
}
