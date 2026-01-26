package local.oss.chronicle.data.sources.plex

import android.content.SharedPreferences
import com.squareup.moshi.Moshi
import io.mockk.*
import local.oss.chronicle.data.model.ServerModel
import local.oss.chronicle.data.sources.plex.model.Connection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SharedPreferencesPlexPrefsRepo].
 * 
 * Regression test for bug where local flag on server connections was lost during persistence,
 * causing all connections to appear as remote after app restart.
 */
class SharedPreferencesPlexPrefsRepoTest {
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var moshi: Moshi
    private lateinit var repo: SharedPreferencesPlexPrefsRepo

    @Before
    fun setup() {
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        moshi = Moshi.Builder().build()

        // Setup default mock behavior
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putStringSet(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.putLong(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        every { mockEditor.commit() } returns true
        every { mockEditor.apply() } just Runs

        repo = SharedPreferencesPlexPrefsRepo(mockPrefs, moshi)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    /**
     * Regression test: Verify local connections are saved to local key
     * 
     * Bug: Previously all connections were saved to BOTH local and remote keys
     * Fix: Filter connections by local flag before saving
     */
    @Test
    fun `server setter correctly separates local and remote connections when persisting`() {
        // Arrange
        val localConnection1 = Connection(uri = "http://192.168.1.100:32400", local = true)
        val localConnection2 = Connection(uri = "http://10.0.0.50:32400", local = true)
        val remoteConnection1 = Connection(uri = "https://external.plex.direct:32400", local = false)
        val remoteConnection2 = Connection(uri = "https://relay.plex.tv:32400", local = false)

        val serverModel = ServerModel(
            name = "Test Server",
            connections = listOf(localConnection1, localConnection2, remoteConnection1, remoteConnection2),
            serverId = "test-server-id",
            accessToken = "test-token",
            owned = true,
        )

        // Act
        repo.server = serverModel

        // Assert - Verify local connections saved to local key
        verify {
            mockEditor.putStringSet(
                "local_server_connections",
                match { set ->
                    set.size == 2 &&
                        set.contains("http://192.168.1.100:32400") &&
                        set.contains("http://10.0.0.50:32400")
                },
            )
        }

        // Assert - Verify remote connections saved to remote key
        verify {
            mockEditor.putStringSet(
                "remote_server_connections",
                match { set ->
                    set.size == 2 &&
                        set.contains("https://external.plex.direct:32400") &&
                        set.contains("https://relay.plex.tv:32400")
                },
            )
        }
    }

    /**
     * Regression test: Verify connections are restored with correct local flag
     * 
     * Bug: Previously all connections were restored with local = false (default)
     * Fix: Explicitly set local = true/false when reconstructing connections
     */
    @Test
    fun `server getter restores local connections with local flag set to true`() {
        // Arrange - Mock SharedPreferences to return connection URIs
        val localUris = setOf("http://192.168.1.100:32400", "http://10.0.0.50:32400")
        val remoteUris = setOf("https://external.plex.direct:32400")

        every { mockPrefs.getStringSet("local_server_connections", any()) } returns localUris
        every { mockPrefs.getStringSet("remote_server_connections", any()) } returns remoteUris
        every { mockPrefs.getString("server_name", "") } returns "Test Server"
        every { mockPrefs.getString("server_id", "") } returns "test-id"
        every { mockPrefs.getString("server_token", "") } returns "test-token"
        every { mockPrefs.getBoolean("server_owned", true) } returns true

        // Act
        val serverModel = repo.server

        // Assert
        assertNotNull("Server should not be null", serverModel)
        assertEquals("Should have 3 total connections", 3, serverModel!!.connections.size)

        // Verify local connections have local = true
        val localConnections = serverModel.connections.filter { it.local }
        assertEquals("Should have 2 local connections", 2, localConnections.size)
        assertTrue(
            "Local connections should include 192.168.1.100",
            localConnections.any { it.uri == "http://192.168.1.100:32400" },
        )
        assertTrue(
            "Local connections should include 10.0.0.50",
            localConnections.any { it.uri == "http://10.0.0.50:32400" },
        )

        // Verify remote connections have local = false
        val remoteConnections = serverModel.connections.filter { !it.local }
        assertEquals("Should have 1 remote connection", 1, remoteConnections.size)
        assertTrue(
            "Remote connection should be external.plex.direct",
            remoteConnections.any { it.uri == "https://external.plex.direct:32400" },
        )
    }

    /**
     * Test: Verify only local connections are persisted when server has only local connections
     */
    @Test
    fun `server setter handles server with only local connections`() {
        // Arrange
        val localConnection = Connection(uri = "http://192.168.1.100:32400", local = true)
        val serverModel = ServerModel(
            name = "Local Server",
            connections = listOf(localConnection),
            serverId = "local-id",
            accessToken = "local-token",
            owned = true,
        )

        // Act
        repo.server = serverModel

        // Assert - Local connections saved
        verify {
            mockEditor.putStringSet(
                "local_server_connections",
                match { set -> set.size == 1 && set.contains("http://192.168.1.100:32400") },
            )
        }

        // Assert - Remote connections empty
        verify {
            mockEditor.putStringSet(
                "remote_server_connections",
                match { set -> set.isEmpty() },
            )
        }
    }

    /**
     * Test: Verify only remote connections are persisted when server has only remote connections
     */
    @Test
    fun `server setter handles server with only remote connections`() {
        // Arrange
        val remoteConnection = Connection(uri = "https://external.plex.direct:32400", local = false)
        val serverModel = ServerModel(
            name = "Remote Server",
            connections = listOf(remoteConnection),
            serverId = "remote-id",
            accessToken = "remote-token",
            owned = false,
        )

        // Act
        repo.server = serverModel

        // Assert - Local connections empty
        verify {
            mockEditor.putStringSet(
                "local_server_connections",
                match { set -> set.isEmpty() },
            )
        }

        // Assert - Remote connections saved
        verify {
            mockEditor.putStringSet(
                "remote_server_connections",
                match { set -> set.size == 1 && set.contains("https://external.plex.direct:32400") },
            )
        }
    }

    /**
     * Test: Verify empty connection preservation
     */
    @Test
    fun `server getter returns null when no connections are stored`() {
        // Arrange - Mock empty SharedPreferences
        every { mockPrefs.getStringSet("local_server_connections", any()) } returns emptySet()
        every { mockPrefs.getStringSet("remote_server_connections", any()) } returns emptySet()
        every { mockPrefs.getString("server_name", "") } returns "Test Server"
        every { mockPrefs.getString("server_id", "") } returns ""
        every { mockPrefs.getString("server_token", "") } returns "test-token"

        // Act
        val serverModel = repo.server

        // Assert
        assertNull("Server should be null when connections are empty", serverModel)
    }

    /**
     * Test: Verify server can be cleared (set to null)
     */
    @Test
    fun `server setter clears all server preferences when set to null`() {
        // Act
        repo.server = null

        // Assert - Verify all server-related keys are removed
        verify { mockEditor.remove("server_id") }
        verify { mockEditor.remove("server_token") }
        verify { mockEditor.remove("server_owned") }
        verify { mockEditor.remove("local_server_connections") }
        verify { mockEditor.remove("remote_server_connections") }
        verify { mockEditor.remove("server_name") }
        verify { mockEditor.commit() }
    }

    /**
     * Integration test: Round-trip persistence
     * Verify that saving and loading a server preserves connection local flags
     */
    @Test
    fun `server round-trip preserves connection local flags`() {
        // Arrange - Setup in-memory storage simulation
        val storedPrefs = mutableMapOf<String, Any>()
        
        every { mockEditor.putString(any(), any()) } answers {
            storedPrefs[firstArg()] = secondArg<String>()
            mockEditor
        }
        every { mockEditor.putStringSet(any(), any()) } answers {
            storedPrefs[firstArg()] = secondArg<Set<String>>()
            mockEditor
        }
        every { mockEditor.putBoolean(any(), any()) } answers {
            storedPrefs[firstArg()] = secondArg<Boolean>()
            mockEditor
        }
        every { mockPrefs.getString(any(), any()) } answers {
            storedPrefs[firstArg()] as? String ?: secondArg()
        }
        every { mockPrefs.getStringSet(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            storedPrefs[firstArg()] as? Set<String> ?: secondArg()
        }
        every { mockPrefs.getBoolean(any(), any()) } answers {
            storedPrefs[firstArg()] as? Boolean ?: secondArg()
        }

        val originalServer = ServerModel(
            name = "Test Server",
            connections = listOf(
                Connection(uri = "http://192.168.1.100:32400", local = true),
                Connection(uri = "https://external.plex.direct:32400", local = false),
            ),
            serverId = "test-id",
            accessToken = "test-token",
            owned = true,
        )

        // Act - Save
        repo.server = originalServer

        // Act - Load
        val loadedServer = repo.server

        // Assert
        assertNotNull("Loaded server should not be null", loadedServer)
        assertEquals("Connection count should match", 2, loadedServer!!.connections.size)

        val loadedLocalConnections = loadedServer.connections.filter { it.local }
        val loadedRemoteConnections = loadedServer.connections.filter { !it.local }

        assertEquals("Should have 1 local connection", 1, loadedLocalConnections.size)
        assertEquals("Should have 1 remote connection", 1, loadedRemoteConnections.size)

        assertTrue(
            "Local connection flag should be preserved",
            loadedLocalConnections.first().uri == "http://192.168.1.100:32400" &&
                loadedLocalConnections.first().local,
        )
        assertTrue(
            "Remote connection flag should be preserved",
            loadedRemoteConnections.first().uri == "https://external.plex.direct:32400" &&
                !loadedRemoteConnections.first().local,
        )
    }
}
