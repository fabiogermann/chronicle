package local.oss.chronicle.features.settings

/**
 * Input for building a shareable diagnostics report. Pure data — no Android types — so the
 * builder can be unit-tested as a plain JUnit test.
 *
 * [secrets] are the raw token values (account/user/server) that must never appear in the output.
 */
data class DiagnosticsReportInput(
    val appVersion: String,
    val versionCode: Int,
    val androidRelease: String,
    val securityPatch: String?,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val serverName: String?,
    val serverVersion: String?,
    val activeUrl: String?,
    val connectionState: String,
    val connectionTestSummary: String?,
    val secrets: List<String>,
)

/**
 * Builds the plain-text diagnostics report behind the "Copy diagnostics" / "Share" actions.
 *
 * Users paste these reports into public Reddit threads and GitHub issues, so the output is
 * secret-safe by construction:
 *
 *  1. Every declared [DiagnosticsReportInput.secrets] value is replaced with `[redacted]`
 *     wherever it appears in any field (URLs, test-result messages, …). Both the raw and the
 *     percent-encoded form of each secret are redacted, so a token that only ever appears inside
 *     a URL query string is still caught.
 *  2. Independently of the declared secrets, any `X-Plex-Token=` query parameter value embedded
 *     in a URL is redacted by pattern, so an undeclared token cannot leak through a URL either.
 *
 * The builder is pure and has no Android dependencies so the redaction guarantees are covered by
 * plain JUnit tests ([DiagnosticsReportBuilderTest]).
 */
class DiagnosticsReportBuilder {
    companion object {
        const val REDACTED = "[redacted]"

        // Token values are alphanumeric in practice; stop at URL delimiters (including ':' so a
        // "uri: status" rendering keeps its colon separator).
        private val PLEX_TOKEN_QUERY_PARAM = Regex("(?i)(X-Plex-Token=)[^&\\s:/?#]+")
    }

    fun build(input: DiagnosticsReportInput): String {
        val redactor = redactorFor(input.secrets)
        return buildString {
            appendLine("Chronicle diagnostics")
            appendLine("App version: ${redactor(input.appVersion)} (${input.versionCode})")
            appendLine(
                "Android: ${redactor(input.androidRelease)} (API ${input.sdkInt}), " +
                    "security patch: ${redactor(input.securityPatch ?: "unknown")}",
            )
            appendLine("Device: ${redactor(input.manufacturer)} ${redactor(input.model)}")
            appendLine("Server: ${redactor(input.serverName ?: "unknown")}")
            appendLine("Server version: ${redactor(input.serverVersion ?: "unknown")}")
            appendLine("Connection state: ${redactor(input.connectionState)}")
            appendLine("Active URL: ${redactor(input.activeUrl ?: "not connected")}")
            appendLine("Last connection test:")
            append(redactor(input.connectionTestSummary ?: "not run"))
        }
    }

    /**
     * Returns a function that redacts every declared secret (raw and percent-encoded forms)
     * plus any `X-Plex-Token=` query parameter value from an arbitrary string.
     */
    internal fun redactorFor(secrets: List<String>): (String) -> String {
        val replacements =
            secrets
                .filter { it.isNotBlank() }
                .flatMap { secret ->
                    // A token may appear in a URL percent-encoded; redact both forms.
                    listOf(secret, urlEncode(secret)).distinct()
                }
                // Longest-first so overlapping secrets can't leave a partial tail behind.
                .sortedByDescending { it.length }

        return { text ->
            var result =
                PLEX_TOKEN_QUERY_PARAM.replace(text) { match ->
                    match.groupValues[1] + REDACTED
                }
            for (secret in replacements) {
                result = result.replace(secret, REDACTED)
            }
            result
        }
    }

    /** Minimal percent-encoding matching how query parameter values appear in URLs. */
    private fun urlEncode(value: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        return buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = byte.toInt().toChar()
                if (char in safe) {
                    append(char)
                } else {
                    append('%')
                    append(String.format("%02X", byte))
                }
            }
        }
    }
}
