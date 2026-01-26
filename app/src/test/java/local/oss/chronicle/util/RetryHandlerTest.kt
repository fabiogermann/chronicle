package local.oss.chronicle.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryHandlerTest {
    @Test
    fun `withRetry succeeds on first attempt`() =
        runTest {
            var callCount = 0

            val result =
                withRetry {
                    callCount++
                    "success"
                }

            assertTrue(result is RetryResult.Success)
            assertEquals("success", (result as RetryResult.Success).value)
            assertEquals(1, result.attemptNumber)
            assertEquals(1, callCount)
        }

    @Test
    fun `withRetry succeeds after one failure`() =
        runTest {
            var callCount = 0

            val result =
                withRetry(RetryConfig(maxAttempts = 3, initialDelayMs = 10)) {
                    callCount++
                    if (callCount == 1) {
                        throw IOException("First attempt fails")
                    }
                    "success"
                }

            assertTrue(result is RetryResult.Success)
            assertEquals("success", (result as RetryResult.Success).value)
            assertEquals(2, result.attemptNumber)
            assertEquals(2, callCount)
        }

    @Test
    fun `withRetry exhausts all attempts`() =
        runTest {
            var callCount = 0

            val result =
                withRetry(RetryConfig(maxAttempts = 3, initialDelayMs = 10)) {
                    callCount++
                    throw IOException("Always fails")
                }

            assertTrue(result is RetryResult.Failure)
            val failure = result as RetryResult.Failure
            assertEquals(3, failure.attemptsMade)
            assertEquals(3, callCount)
            assertTrue(failure.error is ChronicleError.StorageError)
            assertEquals("Always fails", failure.error.message)
        }

    @Test
    fun `withRetry respects shouldRetry predicate`() =
        runTest {
            var callCount = 0

            val result =
                withRetry(
                    config = RetryConfig(maxAttempts = 5, initialDelayMs = 10),
                    shouldRetry = { it is IOException },
                ) {
                    callCount++
                    throw RuntimeException("Non-retryable error")
                }

            assertTrue(result is RetryResult.Failure)
            assertEquals(1, callCount) // Should not retry
            assertEquals(1, (result as RetryResult.Failure).attemptsMade)
        }

    @Test
    fun `withRetry invokes onRetry callback`() =
        runTest {
            val retryCallbacks = mutableListOf<Triple<Int, Long, Throwable>>()
            var callCount = 0

            withRetry(
                config = RetryConfig(maxAttempts = 3, initialDelayMs = 100, maxDelayMs = 1000),
                onRetry = { attempt, delayMs, error ->
                    retryCallbacks.add(Triple(attempt, delayMs, error))
                },
            ) {
                callCount++
                if (callCount < 3) {
                    throw IOException("Fail $callCount")
                }
                "success"
            }

            assertEquals(2, retryCallbacks.size)

            // First retry
            assertEquals(1, retryCallbacks[0].first)
            assertEquals(100L, retryCallbacks[0].second) // initialDelay
            assertEquals("Fail 1", retryCallbacks[0].third.message)

            // Second retry
            assertEquals(2, retryCallbacks[1].first)
            assertEquals(200L, retryCallbacks[1].second) // initialDelay * 2
            assertEquals("Fail 2", retryCallbacks[1].third.message)
        }

    @Test
    fun `withRetry calculates exponential backoff correctly`() =
        runTest {
            val delays = mutableListOf<Long>()

            withRetry(
                config = RetryConfig(maxAttempts = 4, initialDelayMs = 100, multiplier = 2.0, maxDelayMs = 10000),
                onRetry = { _, delayMs, _ ->
                    delays.add(delayMs)
                },
            ) { attempt ->
                if (attempt < 4) {
                    throw IOException("Fail")
                }
                "success"
            }

            assertEquals(3, delays.size)
            assertEquals(100L, delays[0]) // 100 * 2^0 = 100
            assertEquals(200L, delays[1]) // 100 * 2^1 = 200
            assertEquals(400L, delays[2]) // 100 * 2^2 = 400
        }

    @Test
    fun `withRetry respects maxDelayMs cap`() =
        runTest {
            val delays = mutableListOf<Long>()

            withRetry(
                config = RetryConfig(maxAttempts = 5, initialDelayMs = 1000, multiplier = 2.0, maxDelayMs = 2500),
                onRetry = { _, delayMs, _ ->
                    delays.add(delayMs)
                },
            ) { attempt ->
                if (attempt < 5) {
                    throw IOException("Fail")
                }
                "success"
            }

            assertEquals(4, delays.size)
            assertEquals(1000L, delays[0]) // 1000 * 2^0 = 1000
            assertEquals(2000L, delays[1]) // 1000 * 2^1 = 2000
            assertEquals(2500L, delays[2]) // 1000 * 2^2 = 4000, capped at 2500
            assertEquals(2500L, delays[3]) // 1000 * 2^3 = 8000, capped at 2500
        }

    @Test
    fun `withRetryOrThrow returns value on success`() =
        runTest {
            val result =
                withRetryOrThrow(RetryConfig(maxAttempts = 3, initialDelayMs = 10)) {
                    "success"
                }

            assertEquals("success", result)
        }

    @Test
    fun `withRetryOrThrow throws on failure`() =
        runTest {
            var exceptionThrown = false

            try {
                withRetryOrThrow(RetryConfig(maxAttempts = 2, initialDelayMs = 10)) {
                    throw IOException("Always fails")
                }
            } catch (e: Throwable) {
                exceptionThrown = true
                assertTrue(e is IOException)
                assertEquals("Always fails", e.message)
            }

            assertTrue(exceptionThrown)
        }

    @Test
    fun `RetryConfig DEFAULT has expected values`() {
        val config = RetryConfig.DEFAULT

        assertEquals(3, config.maxAttempts)
        assertEquals(1000L, config.initialDelayMs)
        assertEquals(30_000L, config.maxDelayMs)
        assertEquals(2.0, config.multiplier, 0.001)
    }

    @Test
    fun `RetryConfig AGGRESSIVE has expected values`() {
        val config = RetryConfig.AGGRESSIVE

        assertEquals(5, config.maxAttempts)
        assertEquals(500L, config.initialDelayMs)
    }

    @Test
    fun `RetryConfig CONSERVATIVE has expected values`() {
        val config = RetryConfig.CONSERVATIVE

        assertEquals(2, config.maxAttempts)
        assertEquals(2000L, config.initialDelayMs)
    }

    @Test
    fun `withRetry passes attempt number to block`() =
        runTest {
            val attemptNumbers = mutableListOf<Int>()

            withRetry(RetryConfig(maxAttempts = 3, initialDelayMs = 10)) { attempt ->
                attemptNumbers.add(attempt)
                if (attempt < 3) {
                    throw IOException("Fail")
                }
                "success"
            }

            assertEquals(listOf(1, 2, 3), attemptNumbers)
        }

    @Test
    fun `RetryResult Success contains correct attempt number`() =
        runTest {
            var callCount = 0

            val result =
                withRetry(RetryConfig(maxAttempts = 5, initialDelayMs = 10)) {
                    callCount++
                    if (callCount < 3) {
                        throw IOException("Fail")
                    }
                    "success"
                }

            assertTrue(result is RetryResult.Success)
            assertEquals(3, (result as RetryResult.Success).attemptNumber)
        }

    @Test
    fun `RetryResult Failure contains correct attempts made`() =
        runTest {
            val result =
                withRetry(RetryConfig(maxAttempts = 4, initialDelayMs = 10)) {
                    throw IOException("Always fails")
                }

            assertTrue(result is RetryResult.Failure)
            assertEquals(4, (result as RetryResult.Failure).attemptsMade)
        }
}
