package local.oss.chronicle.features.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [DiagnosticsReportBuilder] — the secret-safe builder behind the
 * "Copy diagnostics" / "Share" actions on the connection-test screen.
 *
 * Users paste these reports into public Reddit threads and GitHub issues, so the report must be
 * safe by construction: no Plex token (account, user, or server) may appear anywhere in the
 * output, including when the token is embedded in a URL as an `X-Plex-Token=` query parameter or
 * inside a connection-test result message.
 */
class DiagnosticsReportBuilderTest {
    private val accountToken = "acc0un7T0kenABCDEFGHIJ"
    private val userToken = "u5erT0kenABCDEFGHIJKLMN"
    private val serverToken = "5erverT0kenABCDEFGHIJKL"

    private fun fullInput() =
        DiagnosticsReportInput(
            appVersion = "0.62.6",
            versionCode = 69,
            androidRelease = "14",
            securityPatch = "2024-05-05",
            sdkInt = 34,
            manufacturer = "Google",
            model = "Pixel 7",
            serverName = "Basement Plex",
            serverVersion = "1.40.2.8395",
            activeUrl = "https://192-168-1-100.plex.direct:32400?X-Plex-Token=$serverToken",
            connectionState = "CONNECTED",
            connectionTestSummary =
                "https://192-168-1-100.plex.direct:32400: ✓ successful (HTTP 200, token $serverToken)",
            secrets = listOf(accountToken, userToken, serverToken),
        )

    @Test
    fun `report contains all non-secret diagnostic fields`() {
        val report = DiagnosticsReportBuilder().build(fullInput())

        assertThat(report).contains("0.62.6")
        assertThat(report).contains("69")
        assertThat(report).contains("14")
        assertThat(report).contains("2024-05-05")
        assertThat(report).contains("34")
        assertThat(report).contains("Google")
        assertThat(report).contains("Pixel 7")
        assertThat(report).contains("Basement Plex")
        assertThat(report).contains("1.40.2.8395")
        assertThat(report).contains("CONNECTED")
        assertThat(report).contains("successful")
    }

    @Test
    fun `report never contains account user or server tokens`() {
        val report = DiagnosticsReportBuilder().build(fullInput())

        assertThat(report).doesNotContain(accountToken)
        assertThat(report).doesNotContain(userToken)
        assertThat(report).doesNotContain(serverToken)
    }

    @Test
    fun `report strips X-Plex-Token query parameter from URLs but keeps the URL`() {
        val input =
            fullInput().copy(
                activeUrl = "https://192-168-1-100.plex.direct:32400?X-Plex-Token=$serverToken",
            )

        val report = DiagnosticsReportBuilder().build(input)

        assertThat(report).contains("192-168-1-100.plex.direct")
        assertThat(report).doesNotContain(serverToken)
        assertThat(report).doesNotContain("X-Plex-Token=$serverToken")
    }

    @Test
    fun `report redacts any token-shaped X-Plex-Token value even if it was not declared as a secret`() {
        val undeclaredToken = "undeclaredT0kenXYZ12345678"
        val input =
            fullInput().copy(
                activeUrl = "https://example.com:32400/?X-Plex-Token=$undeclaredToken&foo=bar",
            )

        val report = DiagnosticsReportBuilder().build(input)

        assertThat(report).doesNotContain(undeclaredToken)
        assertThat(report).contains("foo=bar")
    }

    @Test
    fun `report redacts secrets inside connection-test result messages`() {
        val input =
            fullInput().copy(
                connectionTestSummary =
                    "https://server:32400: ✗ failed (401 Unauthorized for token $userToken)",
            )

        val report = DiagnosticsReportBuilder().build(input)

        assertThat(report).contains("failed")
        assertThat(report).doesNotContain(userToken)
    }

    @Test
    fun `null optional fields render as unknown placeholders without secrets`() {
        val input =
            fullInput().copy(
                serverName = null,
                serverVersion = null,
                activeUrl = null,
                securityPatch = null,
                connectionTestSummary = null,
            )

        val report = DiagnosticsReportBuilder().build(input)

        assertThat(report).doesNotContain(accountToken)
        assertThat(report).doesNotContain(userToken)
        assertThat(report).doesNotContain(serverToken)
        assertThat(report).isNotEmpty()
    }

    @Test
    fun `url-encoded secrets are also redacted`() {
        // A token with characters that would be percent-encoded inside a URL query string.
        val weirdToken = "tok en+with/special=chars"
        val encoded = "tok%20en%2Bwith%2Fspecial%3Dchars"
        val input =
            fullInput().copy(
                activeUrl = "https://example.com:32400/?X-Plex-Token=$encoded",
                secrets = listOf(weirdToken),
            )

        val report = DiagnosticsReportBuilder().build(input)

        assertThat(report).doesNotContain(weirdToken)
        assertThat(report).doesNotContain(encoded)
    }
}
