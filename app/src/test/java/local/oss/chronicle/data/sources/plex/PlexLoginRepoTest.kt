package local.oss.chronicle.data.sources.plex

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.sources.plex.model.OAuthResponse
import local.oss.chronicle.features.account.AccountManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Unit tests for [PlexLoginRepo].
 *
 * Tests OAuth PIN ID corruption bug fix:
 * - Transient network errors should NOT corrupt the PIN ID
 * - Only HTTP 404 errors (PIN expired) should clear the PIN ID
 */
class PlexLoginRepoTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var plexPrefsRepo: FakePlexPrefsRepo
    private lateinit var plexLoginService: PlexLoginService
    private lateinit var plexConfig: PlexConfig
    private lateinit var accountManager: AccountManager
    private lateinit var plexLoginRepo: PlexLoginRepo

    @Before
    fun setUp() {
        plexPrefsRepo = FakePlexPrefsRepo()
        plexLoginService = mockk(relaxed = true)
        plexConfig = mockk(relaxed = true)
        accountManager = mockk(relaxed = true)

        plexLoginRepo =
            PlexLoginRepo(
                plexPrefsRepo = plexPrefsRepo,
                plexLoginService = plexLoginService,
                plexConfig = plexConfig,
                accountManager = accountManager,
            )
    }

    @Test
    fun `checkForOAuthAccessToken does not corrupt PIN ID on network error`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: Network error occurs (DNS resolution failure)
            coEvery { plexLoginService.getAuthPin(originalPinId) } throws
                UnknownHostException("Unable to resolve host plex.tv")

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: PIN ID should be preserved (NOT corrupted to -1)
            assertEquals(
                "PIN ID should not be corrupted on transient network error",
                originalPinId,
                plexPrefsRepo.oAuthTempId,
            )
        }

    @Test
    fun `checkForOAuthAccessToken does not corrupt PIN ID on timeout`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: Timeout occurs
            coEvery { plexLoginService.getAuthPin(originalPinId) } throws
                SocketTimeoutException("timeout")

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: PIN ID should be preserved
            assertEquals(
                "PIN ID should not be corrupted on timeout",
                originalPinId,
                plexPrefsRepo.oAuthTempId,
            )
        }

    @Test
    fun `checkForOAuthAccessToken does not corrupt PIN ID on IOException`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: IOException occurs
            coEvery { plexLoginService.getAuthPin(originalPinId) } throws
                IOException("Connection reset")

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: PIN ID should be preserved
            assertEquals(
                "PIN ID should not be corrupted on IOException",
                originalPinId,
                plexPrefsRepo.oAuthTempId,
            )
        }

    @Test
    fun `checkForOAuthAccessToken clears PIN ID on HTTP 404`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: HTTP 404 error occurs (PIN expired/not found)
            val errorBody = "Not Found".toResponseBody("text/plain".toMediaType())
            val httpException = HttpException(Response.error<Any>(404, errorBody))
            coEvery { plexLoginService.getAuthPin(originalPinId) } throws httpException

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: PIN ID should be cleared (set to -1)
            assertEquals(
                "PIN ID should be cleared on HTTP 404 (PIN expired)",
                -1L,
                plexPrefsRepo.oAuthTempId,
            )
        }

    @Test
    fun `checkForOAuthAccessToken succeeds and stores token on valid response`() =
        runTest {
            // Given: Valid PIN ID stored
            val originalPinId = 12345L
            plexPrefsRepo.oAuthTempId = originalPinId

            // When: Successful response with auth token
            val validToken = "valid-auth-token-12345"
            val oauthResponse =
                OAuthResponse(
                    id = originalPinId,
                    clientIdentifier = "test-client-id",
                    code = "test-code",
                    authToken = validToken,
                )
            coEvery { plexLoginService.getAuthPin(originalPinId) } returns oauthResponse

            // Mock the subsequent getUsersForAccount call to avoid NPE
            coEvery { plexLoginService.getUsersForAccount() } throws IOException("Not testing user flow")

            // Call the method
            plexLoginRepo.checkForOAuthAccessToken()

            // Then: Auth token should be stored
            assertEquals(
                "Auth token should be stored on successful response",
                validToken,
                plexPrefsRepo.accountAuthToken,
            )
        }

    @Test
    fun `chooseLibrary forwards the full server Connection list to AccountManager`() =
        kotlinx.coroutines.test.runTest {
            // Given: A fully-authenticated state with a known server / user / library
            val connections =
                listOf(
                    local.oss.chronicle.data.sources.plex.model.Connection(
                        uri = "http://192.168.1.50:32400",
                        local = true,
                    ),
                    local.oss.chronicle.data.sources.plex.model.Connection(
                        uri = "https://wan.plex.direct:32400",
                        local = false,
                    ),
                )
            val server =
                local.oss.chronicle.data.model.ServerModel(
                    name = "Home",
                    connections = connections,
                    serverId = "server-uuid",
                    accessToken = "server-token",
                    owned = true,
                )
            val user =
                local.oss.chronicle.data.sources.plex.model.PlexUser(
                    uuid = "user-uuid",
                    username = "tester",
                    title = "Tester",
                    thumb = "",
                    authToken = "user-token",
                )
            val library =
                local.oss.chronicle.data.model.PlexLibrary(
                    name = "Audiobooks",
                    type = local.oss.chronicle.data.sources.plex.model.MediaType.ARTIST,
                    id = "3",
                )
            plexPrefsRepo.accountAuthToken = "account-token"
            plexPrefsRepo.user = user
            plexPrefsRepo.server = server

            // The login flow's updateServerForSync resolves to the LAN URI
            coEvery { plexConfig.updateServerForSync(connections, "server-token") } returns true
            io.mockk.every { plexConfig.url } returns "http://192.168.1.50:32400"

            // When: User picks the library at the end of OAuth
            plexLoginRepo.chooseLibrary(library)
            // chooseLibrary launches into Dispatchers.IO; busy-wait for the verification to settle.
            val deadline = System.currentTimeMillis() + 5000
            var verified = false
            while (System.currentTimeMillis() < deadline && !verified) {
                try {
                    io.mockk.coVerify {
                        accountManager.addPlexAccountWithLibrary(
                            userUuid = "user-uuid",
                            username = "tester",
                            userThumb = "",
                            serverId = "server-uuid",
                            serverName = "Home",
                            libraryId = "3",
                            libraryName = "Audiobooks",
                            libraryType = "artist",
                            userAuthToken = "user-token",
                            serverAccessToken = "server-token",
                            serverUrl = "http://192.168.1.50:32400",
                            connections = connections,
                        )
                    }
                    verified = true
                } catch (e: AssertionError) {
                    Thread.sleep(25)
                }
            }
            assertTrue(
                "AccountManager.addPlexAccountWithLibrary was not called with the full Connection list",
                verified,
            )
        }
}
