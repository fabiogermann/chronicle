package local.oss.chronicle.util

/**
 * Test helper that retries an assertion block until it passes or times out.
 * Useful for testing asynchronous state transitions.
 *
 * @param timeoutMs Maximum time to wait for the assertion to pass
 * @param intervalMs Time between retry attempts
 * @param block Assertion block to execute
 */
fun eventually(
    timeoutMs: Long = 2000,
    intervalMs: Long = 50,
    block: () -> Unit
) {
    val start = System.currentTimeMillis()
    while (true) {
        try {
            block()
            return
        } catch (e: AssertionError) {
            if (System.currentTimeMillis() - start > timeoutMs) throw e
            Thread.sleep(intervalMs)
        }
    }
}

/**
 * Suspending version of eventually for coroutine tests.
 */
suspend fun eventuallySuspend(
    timeoutMs: Long = 2000,
    intervalMs: Long = 50,
    block: suspend () -> Unit
) {
    val start = System.currentTimeMillis()
    while (true) {
        try {
            block()
            return
        } catch (e: AssertionError) {
            if (System.currentTimeMillis() - start > timeoutMs) throw e
            kotlinx.coroutines.delay(intervalMs)
        }
    }
}
