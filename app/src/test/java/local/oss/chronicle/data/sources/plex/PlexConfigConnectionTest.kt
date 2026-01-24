package local.oss.chronicle.data.sources.plex

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.oss.chronicle.data.sources.plex.model.PlexMediaContainer
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Tests for PlexConfig connection retry logic.
 * Verifies retry behavior, timeout handling, and connection state management.
 */
@ExperimentalCoroutinesApi
@InternalCoroutinesApi
class PlexConfigConnectionTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var plexPrefsRepo: PlexPrefsRepo
    private lateinit var plexMediaService: PlexMediaService
    private lateinit var plexConfig: PlexConfig

    @Before
    fun setup() {
        // Set the main dispatcher for tests (required for LiveData)
        Dispatchers.setMain(testDispatcher)
        
        plexPrefsRepo = mockk(relaxed = true)
        plexMediaService = mockk(relaxed = true)
        plexConfig = PlexConfig(plexPrefsRepo)
        // Setup test connections
        plexConfig.setPotentialConnections(
            listOf(
                local.oss.chronicle.data.sources.plex.model.Connection(
                    uri = "http://test-server.local:32400",
                    local = true
                )
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    /**
     * Test 1: Successful first attempt
     * Verify connection succeeds on first try without retries
     */
    @Test
    fun `connectToServerWithRetry succeeds on first attempt`() = runTest {
        // Arrange
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer(any()) } returns successResponse

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertTrue("Connection should succeed on first attempt", result)
        assertEquals("URL should be set", "http://test-server.local:32400", plexConfig.url)
        coVerify(exactly = 1) { plexMediaService.checkServer(any()) }
    }

    /**
     * Test 2: Successful retry
     * Verify connection succeeds after initial failure
     */
    @Test
    fun `connectToServerWithRetry succeeds after retry`() = runTest {
        // Arrange
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer(any()) } throws
            SocketTimeoutException("Timeout") andThen successResponse

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertTrue("Connection should succeed after retry", result)
        assertEquals("URL should be set", "http://test-server.local:32400", plexConfig.url)
        coVerify(exactly = 2) { plexMediaService.checkServer(any()) }
    }

    /**
     * Test 3: All retries fail
     * Verify returns false after max attempts exhausted
     */
    @Test
    fun `connectToServerWithRetry fails after all retries exhausted`() = runTest {
        // Arrange - fail all attempts
        coEvery { plexMediaService.checkServer(any()) } throws SocketTimeoutException("Timeout")

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertFalse("Connection should fail after all retries", result)
        // Should attempt 3 times (maxAttempts = 3)
        coVerify(exactly = 3) { plexMediaService.checkServer(any()) }
    }

    /**
     * Test 4: Non-retryable error
     * Verify non-network errors fail immediately without retry
     */
    @Test
    fun `connectToServerWithRetry fails immediately on non-retryable error`() = runTest {
        // Arrange - throw non-retryable error
        coEvery { plexMediaService.checkServer(any()) } throws IllegalStateException("Invalid state")

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertFalse("Connection should fail immediately", result)
        // Should only attempt once for non-retryable error
        coVerify(exactly = 1) { plexMediaService.checkServer(any()) }
    }

    /**
     * Test 5: Retryable error types
     * Verify all expected network errors trigger retry
     */
    @Test
    fun `connectToServerWithRetry retries on SocketTimeoutException`() = runTest {
        // Arrange
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer(any()) } throws
            SocketTimeoutException("Timeout") andThen successResponse

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertTrue("Should retry and succeed", result)
        coVerify(exactly = 2) { plexMediaService.checkServer(any()) }
    }

    @Test
    fun `connectToServerWithRetry retries on UnknownHostException`() = runTest {
        // Arrange
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer(any()) } throws
            UnknownHostException("Unknown host") andThen successResponse

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertTrue("Should retry and succeed", result)
        coVerify(exactly = 2) { plexMediaService.checkServer(any()) }
    }

    @Test
    fun `connectToServerWithRetry retries on ConnectException`() = runTest {
        // Arrange
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer(any()) } throws
            ConnectException("Connection refused") andThen successResponse

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertTrue("Should retry and succeed", result)
        coVerify(exactly = 2) { plexMediaService.checkServer(any()) }
    }

    @Test
    fun `connectToServerWithRetry retries on IOException`() = runTest {
        // Arrange
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer(any()) } throws
            IOException("I/O error") andThen successResponse

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertTrue("Should retry and succeed", result)
        coVerify(exactly = 2) { plexMediaService.checkServer(any()) }
    }

    /**
     * Test 6: HTTP error response handling
     * Verify failed HTTP responses trigger retry
     */
    @Test
    fun `connectToServerWithRetry handles HTTP error responses`() = runTest {
        // Arrange
        val errorResponse = Response.error<PlexMediaContainer>(500, "Server error".toResponseBody())
        coEvery { plexMediaService.checkServer(any()) } returns errorResponse

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertFalse("Connection should fail on HTTP error", result)
    }

    /**
     * Test 7: Multiple connections fallback
     * Verify tries all available connections
     */
    @Test
    fun `connectToServerWithRetry tries multiple connections`() = runTest {
        // Arrange - setup multiple connections
        plexConfig.setPotentialConnections(
            listOf(
                local.oss.chronicle.data.sources.plex.model.Connection(
                    uri = "http://local.server:32400",
                    local = true
                ),
                local.oss.chronicle.data.sources.plex.model.Connection(
                    uri = "http://remote.server:32400",
                    local = false
                )
            )
        )
        
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer("http://local.server:32400") } throws
            SocketTimeoutException("Timeout")
        coEvery { plexMediaService.checkServer("http://remote.server:32400") } returns successResponse

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertTrue("Should succeed with fallback connection", result)
    }

    /**
     * Test 8: Connection state management with retry
     * Verify state updates correctly with new retry method
     */
    @Test
    fun `connectToServerWithRetryAndState updates connection state on success`() = runTest {
        // Arrange
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer(any()) } returns successResponse

        // Act
        plexConfig.connectToServerWithRetryAndState(plexMediaService)
        
        // Advance test dispatcher to complete all coroutines
        advanceUntilIdle()

        // Assert
        assertTrue("isConnected should be true", plexConfig.isConnected.value == true)
    }

    @Test
    fun `connectToServerWithRetryAndState updates connection state on failure`() = runTest {
        // Arrange
        coEvery { plexMediaService.checkServer(any()) } throws SocketTimeoutException("Timeout")

        // Act
        plexConfig.connectToServerWithRetryAndState(plexMediaService)
        
        // Advance test dispatcher to complete all coroutines (including retries and backoff)
        advanceUntilIdle()

        // Assert
        assertFalse("isConnected should be false", plexConfig.isConnected.value == true)
    }

    /**
     * Test 9: Verify timeout is reduced from 15s to 10s
     * This is a constant check rather than runtime test
     */
    @Test
    fun `verify connection timeout constant is 10 seconds`() {
        // Assert
        assertEquals(
            "Connection timeout should be 10 seconds",
            10_000L,
            PlexConfig.CONNECTION_TIMEOUT_MS
        )
    }

    /**
     * Test 10: Verify max total connection time
     */
    @Test
    fun `verify max connection time constant is 30 seconds`() {
        // Assert
        assertEquals(
            "Max connection time should be 30 seconds",
            30_000L,
            PlexConfig.MAX_CONNECTION_TIME_MS
        )
    }

    /**
     * Test 11: Backward compatibility
     * Verify deprecated method still works
     */
    @Test
    fun `deprecated connectToServer still works for backward compatibility`() = runTest {
        // Arrange
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer(any()) } returns successResponse

        // Act
        @Suppress("DEPRECATION")
        plexConfig.connectToServer(plexMediaService)
        
        // Advance test dispatcher to complete all coroutines
        advanceUntilIdle()

        // Assert
        assertEquals("URL should be set", "http://test-server.local:32400", plexConfig.url)
        assertTrue("Connection should succeed", plexConfig.isConnected.value == true)
    }

    /**
     * Test 12: Connection retry preserves URL on success
     */
    @Test
    fun `connectToServerWithRetry sets URL correctly on success`() = runTest {
        // Arrange
        val testUrl = "http://test-server.local:32400"
        plexConfig.setPotentialConnections(
            listOf(
                local.oss.chronicle.data.sources.plex.model.Connection(
                    uri = testUrl,
                    local = true
                )
            )
        )
        val successResponse = Response.success(mockk<PlexMediaContainer>())
        coEvery { plexMediaService.checkServer(testUrl) } returns successResponse

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertTrue("Connection should succeed", result)
        assertEquals("URL should match successful connection", testUrl, plexConfig.url)
    }

    /**
     * Test 13: Connection retry does not modify URL on failure
     */
    @Test
    fun `connectToServerWithRetry preserves URL on failure`() = runTest {
        // Arrange
        val originalUrl = plexConfig.url
        coEvery { plexMediaService.checkServer(any()) } throws SocketTimeoutException("Timeout")

        // Act
        val result = plexConfig.connectToServerWithRetry(plexMediaService)

        // Assert
        assertFalse("Connection should fail", result)
        // URL should not change when connection fails
        assertEquals("URL should remain unchanged on failure", originalUrl, plexConfig.url)
    }
}
