package local.oss.chronicle.util

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * Configuration for retry behavior with exponential backoff.
 *
 * @param maxAttempts Maximum number of retry attempts (default 3)
 * @param initialDelayMs Initial delay before first retry in milliseconds (default 1000)
 * @param maxDelayMs Maximum delay cap in milliseconds (default 30000)
 * @param multiplier Multiplier for exponential backoff (default 2.0)
 */
data class RetryConfig(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30_000L,
    val multiplier: Double = 2.0,
) {
    companion object {
        val DEFAULT = RetryConfig()
        val AGGRESSIVE = RetryConfig(maxAttempts = 5, initialDelayMs = 500L)
        val CONSERVATIVE = RetryConfig(maxAttempts = 2, initialDelayMs = 2000L)
    }
}

/**
 * Result of a retry operation.
 */
sealed class RetryResult<out T> {
    data class Success<T>(val value: T, val attemptNumber: Int) : RetryResult<T>()

    data class Failure(val error: ChronicleError, val attemptsMade: Int) : RetryResult<Nothing>()
}

/**
 * Executes a suspending block with retry logic using exponential backoff.
 *
 * Important: Has a global retry limit as per engineer review (C9).
 *
 * @param config Retry configuration
 * @param shouldRetry Predicate to determine if the error is retryable
 * @param onRetry Callback invoked before each retry attempt
 * @param block The suspending block to execute
 */
suspend fun <T> withRetry(
    config: RetryConfig = RetryConfig.DEFAULT,
    shouldRetry: (Throwable) -> Boolean = { true },
    onRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    block: suspend (attempt: Int) -> T,
): RetryResult<T> {
    var lastError: Throwable? = null

    repeat(config.maxAttempts) { attempt ->
        try {
            val result = block(attempt + 1)
            return RetryResult.Success(result, attempt + 1)
        } catch (e: Throwable) {
            lastError = e

            if (!shouldRetry(e) || attempt == config.maxAttempts - 1) {
                return RetryResult.Failure(e.toChronicleError(), attempt + 1)
            }

            val delayMs =
                min(
                    config.maxDelayMs,
                    (config.initialDelayMs * config.multiplier.pow(attempt.toDouble())).toLong(),
                )

            onRetry(attempt + 1, delayMs, e)
            delay(delayMs)
        }
    }

    return RetryResult.Failure(
        lastError?.toChronicleError() ?: ChronicleError.UnknownError("Retry failed"),
        config.maxAttempts,
    )
}

/**
 * Extension function for simpler retry usage that throws on final failure.
 */
suspend fun <T> withRetryOrThrow(
    config: RetryConfig = RetryConfig.DEFAULT,
    shouldRetry: (Throwable) -> Boolean = { true },
    onRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    block: suspend (attempt: Int) -> T,
): T {
    return when (val result = withRetry(config, shouldRetry, onRetry, block)) {
        is RetryResult.Success -> result.value
        is RetryResult.Failure -> throw result.error.cause ?: Exception(result.error.message)
    }
}
