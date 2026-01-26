package local.oss.chronicle.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Manages scoped coroutines with proper lifecycle handling.
 * Replaces GlobalScope usage with managed scopes that can be cancelled.
 *
 * Usage:
 * ```
 * class MyService {
 *     private val scopeManager = ScopedCoroutineManager()
 *
 *     fun doWork() {
 *         scopeManager.launch("download-task") {
 *             // work here
 *         }
 *     }
 *
 *     fun onDestroy() {
 *         scopeManager.cancelAll()
 *     }
 * }
 * ```
 */
class ScopedCoroutineManager
    @Inject
    constructor() {
        private val job = SupervisorJob()

        private val errorHandler =
            CoroutineExceptionHandler { _, throwable ->
                Timber.e(throwable, "Uncaught exception in scoped coroutine")
            }

        private val scope = CoroutineScope(Dispatchers.Main + job + errorHandler)

        private val activeJobs = ConcurrentHashMap<String, Job>()

        /**
         * Launches a coroutine with the given tag for tracking.
         * If a job with the same tag exists, it will be cancelled first.
         *
         * @param tag Unique identifier for this job
         * @param block The suspending block to execute
         * @return The launched Job
         */
        fun launch(
            tag: String,
            block: suspend CoroutineScope.() -> Unit,
        ): Job {
            // Cancel existing job with same tag
            activeJobs[tag]?.cancel()

            val job =
                scope.launch {
                    try {
                        block()
                    } finally {
                        activeJobs.remove(tag)
                    }
                }

            activeJobs[tag] = job
            return job
        }

        /**
         * Launches a coroutine with error handling.
         *
         * @param tag Unique identifier for this job
         * @param onError Callback invoked if an error occurs
         * @param block The suspending block to execute
         * @return The launched Job
         */
        fun launchSafe(
            tag: String,
            onError: (ChronicleError) -> Unit = { Timber.e("Error in $tag: ${it.message}") },
            block: suspend CoroutineScope.() -> Unit,
        ): Job {
            return launch(tag) {
                try {
                    block()
                } catch (e: Throwable) {
                    onError(e.toChronicleError())
                }
            }
        }

        /**
         * Launches a coroutine with retry logic.
         *
         * @param tag Unique identifier for this job
         * @param config Retry configuration
         * @param onError Callback invoked if all retries fail
         * @param block The suspending block to execute
         * @return The launched Job
         */
        fun launchWithRetry(
            tag: String,
            config: RetryConfig = RetryConfig.DEFAULT,
            onError: (ChronicleError) -> Unit = { Timber.e("Retry failed for $tag: ${it.message}") },
            block: suspend (attempt: Int) -> Unit,
        ): Job {
            return launch(tag) {
                when (val result = withRetry(config) { attempt -> block(attempt) }) {
                    is RetryResult.Success -> { /* Success, nothing to do */ }
                    is RetryResult.Failure -> onError(result.error)
                }
            }
        }

        /**
         * Cancels a specific job by tag.
         */
        fun cancel(tag: String) {
            activeJobs[tag]?.cancel()
            activeJobs.remove(tag)
        }

        /**
         * Cancels all active jobs.
         */
        fun cancelAll() {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }

        /**
         * Returns true if a job with the given tag is currently running.
         */
        fun isActive(tag: String): Boolean = activeJobs[tag]?.isActive == true

        /**
         * Returns the number of active jobs.
         */
        val activeJobCount: Int get() = activeJobs.count { it.value.isActive }
    }
