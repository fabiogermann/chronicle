package local.oss.chronicle.util

/**
 * Sealed class representing all Chronicle error types.
 * Used for structured error handling throughout the app.
 */
sealed class ChronicleError(
    open val message: String,
    open val cause: Throwable? = null
) {
    data class NetworkError(
        override val message: String,
        override val cause: Throwable? = null,
        val isRecoverable: Boolean = true
    ) : ChronicleError(message, cause)

    data class AuthenticationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ChronicleError(message, cause)

    data class PlaybackError(
        override val message: String,
        override val cause: Throwable? = null,
        val trackKey: String? = null
    ) : ChronicleError(message, cause)

    data class StorageError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ChronicleError(message, cause)

    data class UnknownError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ChronicleError(message, cause)
}

/**
 * Extension to convert any Throwable to a ChronicleError.
 */
fun Throwable.toChronicleError(): ChronicleError {
    return when (this) {
        is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is javax.net.ssl.SSLException ->
            ChronicleError.NetworkError(
                message = this.message ?: "Network error",
                cause = this,
                isRecoverable = true
            )
        is java.io.IOException ->
            ChronicleError.StorageError(
                message = this.message ?: "Storage error",
                cause = this
            )
        else -> ChronicleError.UnknownError(
            message = this.message ?: "Unknown error",
            cause = this
        )
    }
}

/**
 * Result type for operations that can fail with a ChronicleError.
 */
typealias ChronicleResult<T> = Result<T>

/**
 * Extension for Result to handle ChronicleError specifically.
 */
inline fun <T> ChronicleResult<T>.onChronicleError(action: (ChronicleError) -> Unit): ChronicleResult<T> {
    onFailure { error ->
        action(error.toChronicleError())
    }
    return this
}
