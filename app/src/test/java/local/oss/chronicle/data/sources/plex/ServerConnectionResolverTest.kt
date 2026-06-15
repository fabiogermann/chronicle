package local.oss.chronicle.data.sources.plex

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.features.account.CredentialManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class ServerConnectionResolverTest {
    @Mock
    private lateinit var mockLibraryRepository: LibraryRepository

    @Mock
    private lateinit var mockAccountRepository: AccountRepository

    @Mock
    private lateinit var mockCredentialManager: CredentialManager

    @Mock
    private lateinit var mockPlexConfig: PlexConfig

    @Mock
    private lateinit var mockPlexPrefsRepo: PlexPrefsRepo

    @Mock
    private lateinit var mockConnectionProber: ConnectionProber

    private lateinit var resolver: ServerConnectionResolver

    private val testLibraryId1 = "plex:library:1"
    private val testLibraryId2 = "plex:library:2"
    private val testAccountId1 = "plex:account:account1"
    private val testServerUrl1 = "https://server1.plex.direct:32400"
    private val testServerUrl2 = "https://server2.plex.direct:32400"
    private val testToken1 = "token-lib1"
    private val testToken2 = "token-lib2"
    private val fallbackUrl = "https://fallback.plex.direct:32400"
    private val fallbackToken = "fallback-token"
    private val testCredentialsJson = """{"userToken":"$testToken1","serverToken":"server-token-1"}"""

    @Before
    fun setup() {
        // Setup default fallback behavior for PlexConfig
        whenever(mockPlexConfig.url).thenReturn(fallbackUrl)

        // Setup default fallback behavior for PlexPrefsRepo
        whenever(mockPlexPrefsRepo.server).thenReturn(null)
        whenever(mockPlexPrefsRepo.user).thenReturn(null)
        whenever(mockPlexPrefsRepo.accountAuthToken).thenReturn(fallbackToken)

        resolver =
            ServerConnectionResolver(
                mockLibraryRepository,
                mockAccountRepository,
                mockCredentialManager,
                mockPlexConfig,
                mockPlexPrefsRepo,
                mockConnectionProber,
            )
    }

    // ===== Test 1: Resolve returns library-specific connection from database =====

    @Test
    fun `resolve returns library-specific connection when database has serverUrl and authToken`() =
        runTest {
            // Given: Database has complete connection info for library
            val dbConnection = ServerConnection(testServerUrl1, testToken1)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)

            // When: Resolving connection for library
            val result = resolver.resolve(testLibraryId1)

            // Then: Returns library-specific connection from database
            assertThat(result.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result.authToken).isEqualTo(testToken1)
            verify(mockLibraryRepository).getServerConnection(testLibraryId1)
        }

    // ===== Test 2: Fallback to PlexConfig when database returns null =====

    @Test
    fun `resolve falls back to PlexConfig when database returns null`() =
        runTest {
            // Given: Database has no connection info for library
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(null)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(null)

            // When: Resolving connection for library
            val result = resolver.resolve(testLibraryId1)

            // Then: Returns PlexConfig fallback values
            assertThat(result.serverUrl).isEqualTo(fallbackUrl)
            assertThat(result.authToken).isEqualTo(fallbackToken)
            verify(mockLibraryRepository).getServerConnection(testLibraryId1)
            verify(mockPlexConfig).url
        }

    // ===== Test 3: Fallback to PlexConfig when database returns null fields =====

    @Test
    fun `resolve falls back to PlexConfig when database returns null fields`() =
        runTest {
            // Given: Database returns connection with null fields
            val dbConnection = ServerConnection(null, null)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(null)

            // When: Resolving connection for library
            val result = resolver.resolve(testLibraryId1)

            // Then: Returns PlexConfig fallback values
            assertThat(result.serverUrl).isEqualTo(fallbackUrl)
            assertThat(result.authToken).isEqualTo(fallbackToken)
            verify(mockPlexConfig).url
        }

    // ===== Test 4: Caching - database queried only once =====

    @Test
    fun `resolve caches results and does not query database on second call`() =
        runTest {
            // Given: Database has connection info
            val dbConnection = ServerConnection(testServerUrl1, testToken1)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)

            // When: Resolving same library twice
            val result1 = resolver.resolve(testLibraryId1)
            val result2 = resolver.resolve(testLibraryId1)

            // Then: Results are identical and database queried only once
            assertThat(result1.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result1.authToken).isEqualTo(testToken1)
            assertThat(result2.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result2.authToken).isEqualTo(testToken1)
            verify(mockLibraryRepository, times(1)).getServerConnection(testLibraryId1)
        }

    // ===== Test 5: clearCache causes next resolve to query database again =====

    @Test
    fun `clearCache causes next resolve to query database again`() =
        runTest {
            // Given: Database has connection info and cache is populated
            val dbConnection = ServerConnection(testServerUrl1, testToken1)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)
            resolver.resolve(testLibraryId1) // First call - populates cache

            // When: Clearing cache and resolving again
            resolver.clearCache()
            val result = resolver.resolve(testLibraryId1)

            // Then: Database queried twice (before and after clearCache)
            assertThat(result.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result.authToken).isEqualTo(testToken1)
            verify(mockLibraryRepository, times(2)).getServerConnection(testLibraryId1)
        }

    // ===== Test 6: invalidate clears only specific library cache entry =====

    @Test
    fun `invalidate clears only specific library cache entry`() =
        runTest {
            // Given: Two libraries with different connections
            val dbConnection1 = ServerConnection(testServerUrl1, testToken1)
            val dbConnection2 = ServerConnection(testServerUrl2, testToken2)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection1)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId2)).thenReturn(dbConnection2)

            // Populate cache for both libraries
            resolver.resolve(testLibraryId1)
            resolver.resolve(testLibraryId2)

            // When: Invalidating only library 1
            resolver.invalidate(testLibraryId1)

            // Resolve both again
            val result1 = resolver.resolve(testLibraryId1)
            val result2 = resolver.resolve(testLibraryId2)

            // Then: Library 1 queried twice (before and after invalidate), library 2 only once
            assertThat(result1.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result2.serverUrl).isEqualTo(testServerUrl2)
            verify(mockLibraryRepository, times(2)).getServerConnection(testLibraryId1)
            verify(mockLibraryRepository, times(1)).getServerConnection(testLibraryId2)
        }

    // ===== Test 7: Partial data - serverUrl present but authToken null =====

    @Test
    fun `resolve handles partial data - serverUrl present but authToken null`() =
        runTest {
            // Given: Database has serverUrl but no authToken
            val dbConnection = ServerConnection(testServerUrl1, null)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(null)

            // When: Resolving connection
            val result = resolver.resolve(testLibraryId1)

            // Then: serverUrl from DB, authToken from PlexConfig fallback
            assertThat(result.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result.authToken).isEqualTo(fallbackToken)
        }

    @Test
    fun `resolve handles partial data - authToken present but serverUrl null`() =
        runTest {
            // Given: Database has authToken but no serverUrl
            val dbConnection = ServerConnection(null, testToken1)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)

            // When: Resolving connection
            val result = resolver.resolve(testLibraryId1)

            // Then: serverUrl from PlexConfig fallback, authToken from DB
            assertThat(result.serverUrl).isEqualTo(fallbackUrl)
            assertThat(result.authToken).isEqualTo(testToken1)
            verify(mockPlexConfig).url
        }

    // ===== Test 8: Thread safety with concurrent access =====

    @Test
    fun `resolve is thread-safe with concurrent access`() =
        runTest {
            // Given: Database has connection info
            val dbConnection = ServerConnection(testServerUrl1, testToken1)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)

            // When: Multiple concurrent resolve calls for same library
            val results =
                List(10) {
                    async {
                        resolver.resolve(testLibraryId1)
                    }
                }.awaitAll()

            // Then: All results are consistent and database queried only once (cache hit)
            results.forEach { result ->
                assertThat(result.serverUrl).isEqualTo(testServerUrl1)
                assertThat(result.authToken).isEqualTo(testToken1)
            }
            // Database should be queried only once due to caching
            verify(mockLibraryRepository, times(1)).getServerConnection(testLibraryId1)
        }

    @Test
    fun `concurrent access to different libraries works correctly`() =
        runTest {
            // Given: Two libraries with different connections
            val dbConnection1 = ServerConnection(testServerUrl1, testToken1)
            val dbConnection2 = ServerConnection(testServerUrl2, testToken2)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection1)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId2)).thenReturn(dbConnection2)

            // When: Concurrent resolve calls for different libraries
            val results =
                listOf(
                    async { resolver.resolve(testLibraryId1) },
                    async { resolver.resolve(testLibraryId2) },
                    async { resolver.resolve(testLibraryId1) },
                    async { resolver.resolve(testLibraryId2) },
                ).awaitAll()

            // Then: Results are correct for each library
            assertThat(results[0].serverUrl).isEqualTo(testServerUrl1)
            assertThat(results[1].serverUrl).isEqualTo(testServerUrl2)
            assertThat(results[2].serverUrl).isEqualTo(testServerUrl1)
            assertThat(results[3].serverUrl).isEqualTo(testServerUrl2)

            // Each library queried at least once
            verify(mockLibraryRepository, times(1)).getServerConnection(testLibraryId1)
            verify(mockLibraryRepository, times(1)).getServerConnection(testLibraryId2)
        }

    // ===== Additional edge case tests =====

    @Test
    fun `resolve caches incomplete data and does not refetch`() =
        runTest {
            // Given: Database returns incomplete connection (only serverUrl)
            // This tests that even incomplete data gets cached
            val dbConnection = ServerConnection(testServerUrl1, null)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(null)

            // When: Resolving twice
            val result1 = resolver.resolve(testLibraryId1)
            val result2 = resolver.resolve(testLibraryId1)

            // Then: Database queried only once (incomplete data was cached)
            assertThat(result1.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result1.authToken).isEqualTo(fallbackToken)
            assertThat(result2.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result2.authToken).isEqualTo(fallbackToken)
            verify(mockLibraryRepository, times(1)).getServerConnection(testLibraryId1)
        }

    @Test
    fun `resolve caches resolved data even if database data is incomplete`() =
        runTest {
            // Given: Database returns incomplete connection (only serverUrl)
            // Implementation resolves this with fallback token and caches the result
            val incompleteConnection = ServerConnection(testServerUrl1, null)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(incompleteConnection)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(null)

            // When: Resolving twice
            val result1 = resolver.resolve(testLibraryId1) // First call - queries DB, fills fallback, caches
            val result2 = resolver.resolve(testLibraryId1) // Second call - uses cached resolved data

            // Then: Database queried only once because resolved data (with fallbacks) was cached
            assertThat(result1.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result1.authToken).isEqualTo(fallbackToken) // Fallback token used
            assertThat(result2.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result2.authToken).isEqualTo(fallbackToken)
            verify(mockLibraryRepository, times(1)).getServerConnection(testLibraryId1)
        }

    @Test
    fun `clearCache allows fresh data to be fetched`() =
        runTest {
            // Given: Connection info changes in database
            val oldConnection = ServerConnection(testServerUrl1, testToken1)
            val newConnection = ServerConnection(testServerUrl2, testToken2)

            whenever(mockLibraryRepository.getServerConnection(testLibraryId1))
                .thenReturn(oldConnection)
                .thenReturn(newConnection)

            // When: Resolving, clearing cache, then resolving again
            val result1 = resolver.resolve(testLibraryId1)
            resolver.clearCache()
            val result2 = resolver.resolve(testLibraryId1)

            // Then: First result has old data, second has new data
            assertThat(result1.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result1.authToken).isEqualTo(testToken1)
            assertThat(result2.serverUrl).isEqualTo(testServerUrl2)
            assertThat(result2.authToken).isEqualTo(testToken2)
        }

    // ===== Test 9: Credential JSON parsing tests =====

    @Test
    fun `resolve parses userToken from credentials JSON when library authToken is empty`() =
        runTest {
            // Given: Library has no authToken but Account has credentials JSON
            val library =
                Library(
                    id = testLibraryId1,
                    accountId = testAccountId1,
                    serverId = "test-server",
                    serverName = "Test Server",
                    name = "Test Library",
                    type = "artist",
                    lastSyncedAt = null,
                    itemCount = 0,
                    isActive = true,
                    serverUrl = testServerUrl1,
                    authToken = null,
                )
            val dbConnection = ServerConnection(testServerUrl1, null)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(library)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)
            whenever(mockCredentialManager.getCredentials(testAccountId1)).thenReturn(testCredentialsJson)

            // When: Resolving connection
            val result = resolver.resolve(testLibraryId1)

            // Then: userToken is extracted from JSON and used
            assertThat(result.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result.authToken).isEqualTo(testToken1)
            verify(mockCredentialManager).getCredentials(testAccountId1)
            // Self-healing should update the library
            verify(mockLibraryRepository).updateLibrary(eq(library.copy(authToken = testToken1)))
        }

    @Test
    fun `resolve handles malformed credentials JSON gracefully`() =
        runTest {
            // Given: Library has no authToken and Account has invalid JSON
            val library =
                Library(
                    id = testLibraryId1,
                    accountId = testAccountId1,
                    serverId = "test-server",
                    serverName = "Test Server",
                    name = "Test Library",
                    type = "artist",
                    lastSyncedAt = null,
                    itemCount = 0,
                    isActive = true,
                    serverUrl = testServerUrl1,
                    authToken = null,
                )
            val dbConnection = ServerConnection(testServerUrl1, null)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(library)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)
            whenever(mockCredentialManager.getCredentials(testAccountId1)).thenReturn("invalid-json{")

            // When: Resolving connection
            val result = resolver.resolve(testLibraryId1)

            // Then: Falls back to PlexPrefsRepo token
            assertThat(result.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result.authToken).isEqualTo(fallbackToken)
            verify(mockCredentialManager).getCredentials(testAccountId1)
        }

    @Test
    fun `resolve detects and repairs corrupted authToken (raw JSON)`() =
        runTest {
            // Given: Library has corrupted authToken (raw JSON string from previous bug)
            val corruptedToken = """{"userToken":"real-token","serverToken":"server-token"}"""
            val library =
                Library(
                    id = testLibraryId1,
                    accountId = testAccountId1,
                    serverId = "test-server",
                    serverName = "Test Server",
                    name = "Test Library",
                    type = "artist",
                    lastSyncedAt = null,
                    itemCount = 0,
                    isActive = true,
                    serverUrl = testServerUrl1,
                    authToken = corruptedToken,
                )
            val dbConnection = ServerConnection(testServerUrl1, corruptedToken)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(library)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)
            whenever(mockCredentialManager.getCredentials(testAccountId1)).thenReturn(testCredentialsJson)

            // When: Resolving connection
            val result = resolver.resolve(testLibraryId1)

            // Then: Corrupted token is detected, corrected token is resolved from credentials
            assertThat(result.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result.authToken).isEqualTo(testToken1)
            verify(mockCredentialManager).getCredentials(testAccountId1)
            // Self-healing should update the library with correct token
            verify(mockLibraryRepository).updateLibrary(eq(library.copy(authToken = testToken1)))
        }

    @Test
    fun `resolve treats authToken starting with left brace as corrupted`() =
        runTest {
            // Given: Library has authToken that starts with '{'
            val corruptedToken = "{some-corrupted-data"
            val library =
                Library(
                    id = testLibraryId1,
                    accountId = testAccountId1,
                    serverId = "test-server",
                    serverName = "Test Server",
                    name = "Test Library",
                    type = "artist",
                    lastSyncedAt = null,
                    itemCount = 0,
                    isActive = true,
                    serverUrl = testServerUrl1,
                    authToken = corruptedToken,
                )
            val dbConnection = ServerConnection(testServerUrl1, corruptedToken)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(library)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)
            whenever(mockCredentialManager.getCredentials(testAccountId1)).thenReturn(testCredentialsJson)

            // When: Resolving connection
            val result = resolver.resolve(testLibraryId1)

            // Then: Token is treated as corrupted and replaced
            assertThat(result.serverUrl).isEqualTo(testServerUrl1)
            assertThat(result.authToken).isEqualTo(testToken1)
            verify(mockCredentialManager).getCredentials(testAccountId1)
        }

    @Test
    fun `resolve extracts userToken from credentials JSON with serverToken field`() =
        runTest {
            // Given: Credentials JSON contains both userToken and serverToken
            val credentialsWithBothTokens = """{"userToken":"user-abc","serverToken":"server-xyz"}"""
            val library =
                Library(
                    id = testLibraryId1,
                    accountId = testAccountId1,
                    serverId = "test-server",
                    serverName = "Test Server",
                    name = "Test Library",
                    type = "artist",
                    lastSyncedAt = null,
                    itemCount = 0,
                    isActive = true,
                    serverUrl = testServerUrl1,
                    authToken = null,
                )
            val dbConnection = ServerConnection(testServerUrl1, null)
            whenever(mockLibraryRepository.getLibraryById(testLibraryId1)).thenReturn(library)
            whenever(mockLibraryRepository.getServerConnection(testLibraryId1)).thenReturn(dbConnection)
            whenever(mockCredentialManager.getCredentials(testAccountId1)).thenReturn(credentialsWithBothTokens)

            // When: Resolving connection
            val result = resolver.resolve(testLibraryId1)

            // Then: Only userToken is extracted and used (serverToken is ignored)
            assertThat(result.authToken).isEqualTo("user-abc")
        }
}
