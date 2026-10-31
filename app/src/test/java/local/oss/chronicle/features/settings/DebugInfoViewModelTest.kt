package local.oss.chronicle.features.settings

import androidx.lifecycle.MutableLiveData
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import local.oss.chronicle.data.model.ServerModel
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.data.sources.plex.PlexMediaService
import local.oss.chronicle.data.sources.plex.PlexPrefsRepo
import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.data.sources.plex.model.PlexUser
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [DebugInfoViewModel.buildDiagnosticsReport] — the single choke point behind the
 * "Copy diagnostics" / "Share" actions.
 *
 * The integrator's hard requirement: the copied/shared text must come ONLY from
 * [DiagnosticsReportBuilder], and the Plex token must never appear in it — even when the chosen
 * connection URI and the last connection-test summary carry an `X-Plex-Token=` value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DebugInfoViewModelTest {
    private val accountToken = "acc0un7T0kenABCDEFGHIJ"
    private val userToken = "u5erT0kenABCDEFGHIJKLMN"
    private val serverToken = "5erverT0kenABCDEFGHIJKL"

    private lateinit var plexPrefsRepo: PlexPrefsRepo
    private lateinit var plexConfig: PlexConfig
    private lateinit var plexMediaService: PlexMediaService

    @Before
    fun setUp() {
        val serverModel =
            ServerModel(
                name = "Basement Plex",
                connections =
                    listOf(
                        Connection(uri = "https://192-168-1-100.plex.direct:32400?X-Plex-Token=$serverToken", local = true),
                    ),
                serverId = "server-id",
                accessToken = serverToken,
                owned = true,
            )
        plexPrefsRepo =
            mockk {
                every { accountAuthToken } returns accountToken
                every { user } returns PlexUser(id = 1L, username = "user", authToken = userToken)
                every { server } returns serverModel
            }
        plexConfig = PlexConfig(plexPrefsRepo, mockk(relaxed = true), mockk(relaxed = true))
        plexConfig.url = "https://192-168-1-100.plex.direct:32400?X-Plex-Token=$serverToken"
        plexMediaService = mockk(relaxed = true)
    }

    private fun viewModel() = DebugInfoViewModel(plexConfig, plexPrefsRepo, plexMediaService)

    @Test
    fun `report text equals DiagnosticsReportBuilder output for the same input`() {
        val report = viewModel().buildDiagnosticsReport()

        // The ViewModel must not invent its own formatting: whatever the builder produces for
        // an equivalent input is what the user pastes. We assert structure + key fields instead
        // of a golden string so device-dependent Build fields (VERSION.RELEASE etc.) stay
        // environment-independent.
        assertThat(report).startsWith("Chronicle diagnostics\n")
        assertThat(report).contains("Server: Basement Plex")
        assertThat(report).contains("Connection state:")
        assertThat(report).contains("Active URL:")
        assertThat(report).contains("Last connection test:")
    }

    @Test
    fun `report never contains account user or server tokens even with token-laden URI and summary`() {
        val vm = viewModel()

        // Seed the last connection-test summary with the token (as would happen when a
        // connection URI carrying X-Plex-Token= is listed in the results).
        val resultsField =
            DebugInfoViewModel::class.java.getDeclaredField("_connectionResults").apply {
                isAccessible = true
            }

        @Suppress("UNCHECKED_CAST")
        val liveData = resultsField.get(vm) as MutableLiveData<List<ConnectionTestResult>>
        liveData.value =
            listOf(
                ConnectionTestResult(
                    uri = "https://192-168-1-100.plex.direct:32400?X-Plex-Token=$serverToken",
                    isLocal = true,
                    status = ConnectionStatus.FAILED,
                ),
                // A raw token outside any query parameter: only declared-secret redaction
                // (not the X-Plex-Token= pattern) can catch this one.
                ConnectionTestResult(
                    uri = "https://remote.example.com:32400 (401 for token $userToken)",
                    isLocal = false,
                    status = ConnectionStatus.FAILED,
                ),
            )

        val report = vm.buildDiagnosticsReport()

        assertThat(report).doesNotContain(accountToken)
        assertThat(report).doesNotContain(userToken)
        assertThat(report).doesNotContain(serverToken)
        assertThat(report).doesNotContain("X-Plex-Token=$serverToken")
        // The URL itself stays useful for debugging.
        assertThat(report).contains("192-168-1-100.plex.direct")
        assertThat(report).contains("failed")
    }

    @Test
    fun `report with no connections and no server still contains no token`() {
        every { plexPrefsRepo.server } returns null
        plexConfig.url = PlexConfig.PLACEHOLDER_URL

        val report = viewModel().buildDiagnosticsReport()

        assertThat(report).doesNotContain(accountToken)
        assertThat(report).doesNotContain(userToken)
        assertThat(report).doesNotContain(serverToken)
        assertThat(report).contains("not run")
        assertThat(report).contains("not connected")
    }
}
