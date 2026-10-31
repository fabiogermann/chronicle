package local.oss.chronicle.features.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.sources.plex.IPlexLoginRepo
import local.oss.chronicle.util.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for the process-death OAuth loop (B2): the OS kills the app
 * while the user authenticates in the browser (aggressive on Vivo/Oppo/Xiaomi;
 * reporter u/N3Pi_cs is on a Vivo Y21d). The app relaunches via the callback
 * deep link, AuthReturnActivity notifies AuthCoordinatorSingleton — but no
 * coordinator is registered (the old one died with the process), MainActivity
 * shows the login screen again, and NOTHING polls the PIN id that survives in
 * PlexPrefsRepo.oAuthTempId. The user taps login again → new PIN → browser →
 * same kill → loop, exactly as reported ("the app reopens, and the process
 * repeats in a loop").
 *
 * When the login screen starts with an unexpired persisted PIN, the app must
 * resume polling that PIN instead of stranding it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlexAuthCoordinatorResumeTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var plexLoginRepo: IPlexLoginRepo
    private lateinit var loginEvent: MutableLiveData<Event<IPlexLoginRepo.LoginState>>

    @Before
    fun setUp() {
        plexLoginRepo = mockk(relaxed = true)
        loginEvent = MutableLiveData(Event(IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS))
        every { plexLoginRepo.loginEvent } returns loginEvent
    }

    @Test
    fun `resumeAuth polls the persisted PIN immediately and reaches Success on a linked PIN`() =
        runTest {
            val persistedPinId = 4242L
            var linked = false
            val polledPinIds = mutableListOf<Long>()
            coEvery { plexLoginRepo.checkForOAuthAccessToken(any()) } answers {
                val id = firstArg<Long>()
                polledPinIds.add(id)
                if (linked && id == persistedPinId) {
                    // The user had completed auth in the browser before the process died;
                    // the PIN now carries a token, so the repo flips login state.
                    loginEvent.value = Event(IPlexLoginRepo.LoginState.LOGGED_IN_FULLY)
                }
            }

            // A brand-new coordinator, as constructed after a process restart — its
            // state is Idle, no PIN was created in this process.
            val coordinator = PlexAuthCoordinator(plexLoginRepo, this)
            assertEquals(PlexAuthState.Idle, coordinator.state.value)

            val flow = coordinator.resumeAuth(persistedPinId)

            // First poll happens immediately (the PIN expires after 15–30 min; the user
            // just came back from the browser and is waiting), then at the normal cadence.
            runCurrent()
            assertTrue(
                "an immediate check of the persisted PIN must happen on resume",
                polledPinIds.isNotEmpty() && polledPinIds.first() == persistedPinId,
            )
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)
            runCurrent()
            assertTrue(
                "expected Polling, was ${flow.value}",
                flow.value is PlexAuthState.Polling,
            )

            // The user's earlier browser auth lands on the server: polling alone must
            // carry the resumed flow to Success — no new PIN, no new browser round-trip.
            linked = true
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)
            runCurrent()
            assertEquals(
                "a resumed flow must reach Success by polling the persisted PIN",
                PlexAuthState.Success(),
                flow.value,
            )
            coVerify(exactly = 0) { plexLoginRepo.postOAuthPin(any()) }
            coordinator.dispose()
        }

    @Test
    fun `resumeAuth on an expired PIN ends in Timeout, not in an infinite poll`() =
        runTest {
            val persistedPinId = 7777L
            // PIN never gains a token (it expired server-side; getAuthPin 404s and the
            // repo keeps returning nothing useful) and login state never flips.
            coEvery { plexLoginRepo.checkForOAuthAccessToken(any()) } returns Unit

            // Timeouts compare against the wall clock, so drive it from the test.
            var fakeNow = 1_000_000L
            val coordinator =
                PlexAuthCoordinator(
                    plexLoginRepo,
                    this,
                    ticker = PlexAuthCoordinator.Ticker { fakeNow },
                )
            val flow = coordinator.resumeAuth(persistedPinId)

            // Resume is bounded by the PIN's remaining server-side lifetime, not the
            // full fresh-flow timeout — a dead PIN must fail fast so the user can retry.
            // Timeout is checked per poll iteration, so advance poll by poll.
            var remaining = PlexAuthCoordinator.RESUME_TIMEOUT_MS + PlexAuthCoordinator.POLLING_INTERVAL_MS
            while (remaining > 0) {
                fakeNow += PlexAuthCoordinator.POLLING_INTERVAL_MS
                advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS)
                runCurrent()
                remaining -= PlexAuthCoordinator.POLLING_INTERVAL_MS
            }

            assertEquals(PlexAuthState.Timeout, flow.value)
            assertTrue(
                "resume timeout (${PlexAuthCoordinator.RESUME_TIMEOUT_MS}ms) must not exceed the " +
                    "fresh-flow timeout (${PlexAuthCoordinator.TIMEOUT_MS}ms)",
                PlexAuthCoordinator.RESUME_TIMEOUT_MS <= PlexAuthCoordinator.TIMEOUT_MS,
            )
            coordinator.dispose()
        }
}
