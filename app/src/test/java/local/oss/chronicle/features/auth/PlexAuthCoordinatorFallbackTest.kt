package local.oss.chronicle.features.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Tests for the manual-fallback auth path (fix: auth deep-link loop on devices
 * where the App Link redirect doesn't fire, e.g. Vivo/Funtouch).
 *
 * When the OAuth browser page can't redirect back into the app, the user is
 * stuck: the page freezes and the app just keeps polling. The fix surfaces the
 * PIN code so the user can complete linking manually at plex.tv/link, and
 * exposes a slow-poll hint so the UI knows to offer that fallback.
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
        coEvery { plexLoginRepo.checkForOAuthAccessToken() } returns Unit
    }

    @Test
    fun `polling state carries the pin code so the UI can offer manual linking`() =
        runTest {
            coEvery { plexLoginRepo.postOAuthPin() } returns
                OAuthResponse(id = 42L, clientIdentifier = "client-x", code = "ABCD", authToken = null)

            val coordinator = PlexAuthCoordinator(plexLoginRepo, this)
            val flow = coordinator.startAuth()

            // Move past the initial WaitingForUser into Polling
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)

            val state = flow.value
            assertTrue("expected Polling, was $state", state is PlexAuthState.Polling)
            assertEquals("ABCD", (state as PlexAuthState.Polling).pinCode)
            coordinator.dispose()
        }

    @Test
    fun `polling beyond the fallback threshold flags shouldShowManualFallback`() =
        runTest {
            coEvery { plexLoginRepo.postOAuthPin() } returns
                OAuthResponse(id = 7L, clientIdentifier = "client-x", code = "WXYZ", authToken = null)

            val coordinator = PlexAuthCoordinator(plexLoginRepo, this)
            val flow = coordinator.startAuth()

            // Before threshold: no fallback yet
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)
            var state = flow.value as PlexAuthState.Polling
            assertTrue("should not show fallback immediately", !state.shouldShowManualFallback)

            // Past threshold: fallback surfaces so the UI can offer plex.tv/link + code
            advanceTimeBy(PlexAuthCoordinator.MANUAL_FALLBACK_AFTER_MS + 1_000)
            state = flow.value as PlexAuthState.Polling
            assertTrue("expected manual fallback after threshold", state.shouldShowManualFallback)
            coordinator.dispose()
        }
}
