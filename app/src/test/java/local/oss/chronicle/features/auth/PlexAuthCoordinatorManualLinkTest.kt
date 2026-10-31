package local.oss.chronicle.features.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.data.sources.plex.model.OAuthResponse
import local.oss.chronicle.util.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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
        coEvery { plexLoginRepo.checkForOAuthAccessToken() } returns Unit
        coEvery { plexLoginRepo.postOAuthPin() } returns
            OAuthResponse(id = 42L, clientIdentifier = "client-x", code = "ABCD", authToken = null)
    }

    @Test
    fun `manual-linking code is available from the very start of polling`() =
        runTest {
            val coordinator = PlexAuthCoordinator(plexLoginRepo, this)
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
            coordinator.dispose()
        }

    @Test
    fun `polling alone reaches success without the browser ever returning`() =
        runTest {
            var tokenIssued = false
            coEvery { plexLoginRepo.checkForOAuthAccessToken() } answers {
                if (tokenIssued) {
                    loginEvent.value = Event(IPlexLoginRepo.LoginState.LOGGED_IN_FULLY)
                }
            }

            val coordinator = PlexAuthCoordinator(plexLoginRepo, this)
            val flow = coordinator.startAuth()
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)
            assertTrue("expected Polling, was ${flow.value}", flow.value is PlexAuthState.Polling)

            // The user finishes auth on the frozen browser page by manually linking
            // (e.g. at plex.tv/link). No deep link fires, onBrowserReturned() is
            // never called — polling alone must still carry the flow to Success.
            tokenIssued = true
            advanceUntilIdle()

            assertEquals(
                "polling alone must reach Success with no onBrowserReturned() call",
                PlexAuthState.Success(),
                flow.value,
            )
            coordinator.dispose()
        }
}
