package local.oss.chronicle.features.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.data.sources.plex.model.OAuthResponse
import local.oss.chronicle.util.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for the manual-linking PIN defect (B1): the app created a
 * *strong* PIN and displayed its code for manual entry at plex.tv/link, but a
 * strong PIN's code is 25 characters and plex.tv/link only accepts the
 * 4-character code from a NON-strong PIN (verified against the live Plex API
 * 2026-10-31: strong=true → code length 25, expiresIn 1800 s; strong=false →
 * code length 4, expiresIn 900 s). The user was shown a code they could not
 * enter anywhere.
 *
 * The OAuth/browser path must keep using the strong PIN (strong codes are what
 * app.plex.tv/auth expects); the manual path needs its own non-strong PIN, and
 * polling must then watch the PIN the user is actually linking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlexAuthCoordinatorManualPinTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var plexLoginRepo: IPlexLoginRepo
    private lateinit var loginEvent: MutableLiveData<Event<IPlexLoginRepo.LoginState>>

    private val strongPin =
        OAuthResponse(
            id = 1001L,
            clientIdentifier = "client-x",
            code = "LONGSTRONGPIN25CHARACTERSAB",
            authToken = null,
        )
    private val manualPin =
        OAuthResponse(
            id = 2002L,
            clientIdentifier = "client-x",
            code = "K7XW",
            authToken = null,
        )

    @Before
    fun setUp() {
        plexLoginRepo = mockk(relaxed = true)
        loginEvent = MutableLiveData(Event(IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS))
        every { plexLoginRepo.loginEvent } returns loginEvent
        coEvery { plexLoginRepo.checkForOAuthAccessToken(any()) } returns Unit
        coEvery { plexLoginRepo.postOAuthPin(strong = true) } returns strongPin
        coEvery { plexLoginRepo.postOAuthPin(strong = false) } returns manualPin
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
    fun `browser OAuth path requests a strong PIN`() =
        runCoordinatorTest { coordinator ->
            val flow = coordinator.startAuth()

            val state = flow.value
            assertTrue("expected WaitingForUser, was $state", state is PlexAuthState.WaitingForUser)
            state as PlexAuthState.WaitingForUser
            // The app.plex.tv/auth URL must carry the STRONG pin's code and poll the STRONG id.
            assertTrue(
                "auth URL must embed the strong pin code, was: ${state.authUrl}",
                state.authUrl.contains(strongPin.code),
            )
            assertEquals(strongPin.id, state.pinId)
            coVerify { plexLoginRepo.postOAuthPin(strong = true) }
        }

    @Test
    fun `manual-linking option requests a separate non-strong PIN with a 4-char code`() =
        runCoordinatorTest { coordinator ->
            val flow = coordinator.startAuth()
            assertTrue("expected WaitingForUser, was ${flow.value}", flow.value is PlexAuthState.WaitingForUser)

            // User taps the manual-linking hint.
            val manual = coordinator.requestManualLinkCode()
            assertNotNull("manual-linking PIN creation must succeed", manual)
            manual!!

            // plex.tv/link only accepts the 4-char non-strong code — a strong PIN's
            // 25-char code is rejected there, which is exactly the reported defect.
            coVerify { plexLoginRepo.postOAuthPin(strong = false) }
            assertEquals(manualPin.code, manual.pinCode)
            assertTrue(
                "manual code must be the short plex.tv/link code, was: '${manual.pinCode}' (${manual.pinCode.length} chars)",
                manual.pinCode.length <= 6,
            )
        }

    @Test
    fun `after choosing manual linking, polling watches the manual pin and reaches Success without browser return`() =
        runCoordinatorTest { coordinator ->
            val polledPinIds = mutableListOf<Long>()
            val pinIdSlot = slot<Long>()
            coEvery { plexLoginRepo.checkForOAuthAccessToken(capture(pinIdSlot)) } answers {
                polledPinIds.add(pinIdSlot.captured)
            }
            var manualLinked = false
            coEvery { plexLoginRepo.checkForOAuthAccessToken(manualPin.id) } answers {
                polledPinIds.add(manualPin.id)
                if (manualLinked) {
                    loginEvent.value = Event(IPlexLoginRepo.LoginState.LOGGED_IN_FULLY)
                }
            }

            val flow = coordinator.startAuth()
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)
            assertTrue("expected Polling, was ${flow.value}", flow.value is PlexAuthState.Polling)

            // The user switches to manual linking; from here on the strong pin is dead to us.
            val manual = coordinator.requestManualLinkCode()
            assertNotNull("manual-linking PIN creation must succeed", manual)
            manual!!
            assertEquals(manualPin.id, manual.pinId)
            polledPinIds.clear()

            // The user enters the 4-char code at plex.tv/link on another device. No deep
            // link fires, onBrowserReturned() is never called — the next poll of the
            // MANUAL pin id must carry the flow to Success.
            manualLinked = true
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)
            runCurrent()

            assertEquals(
                "polling alone must reach Success with no onBrowserReturned() call",
                PlexAuthState.Success(),
                flow.value,
            )
            assertTrue(
                "polls after switching to manual linking must target the manual pin id " +
                    "${manualPin.id}, got: $polledPinIds",
                polledPinIds.isNotEmpty() && polledPinIds.all { it == manualPin.id },
            )
        }

    @Test
    fun `manual path timeout is longer than the OAuth timeout but below the 900s non-strong PIN expiry`() {
        assertTrue(
            "manual timeout (${PlexAuthCoordinator.MANUAL_TIMEOUT_MS}ms) must exceed the OAuth " +
                "timeout (${PlexAuthCoordinator.TIMEOUT_MS}ms) — users link from another device",
            PlexAuthCoordinator.MANUAL_TIMEOUT_MS > PlexAuthCoordinator.TIMEOUT_MS,
        )
        assertTrue(
            "manual timeout (${PlexAuthCoordinator.MANUAL_TIMEOUT_MS}ms) must stay below the " +
                "900s non-strong PIN expiry",
            PlexAuthCoordinator.MANUAL_TIMEOUT_MS < 900_000L,
        )
    }
}
