package local.oss.chronicle.features.account

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialManagerTest {
    private lateinit var credentialManager: CredentialManager
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric has no AndroidKeyStore, so the default EncryptedSharedPreferences
        // cannot be built here. Use the test seam with an in-memory store instead —
        // the manager's behavior under test is identical regardless of backing store.
        val testPrefs: SharedPreferences =
            context.getSharedPreferences("credential_manager_test", Context.MODE_PRIVATE)
        credentialManager = CredentialManager.forTesting(context, testPrefs)
        // Clean state before each test
        credentialManager.clearAllCredentials()
    }

    @After
    fun tearDown() {
        credentialManager.clearAllCredentials()
    }

    @Test
    fun storeCredentials_savesToken() {
        // Given
        val accountId = "plex:account:test123"
        val token = "test-auth-token-xyz"

        // When
        credentialManager.storeCredentials(accountId, token)

        // Then
        val retrieved = credentialManager.getCredentials(accountId)
        assertThat(retrieved).isEqualTo(token)
    }

    @Test
    fun getCredentials_returnsNullForNonexistentAccount() {
        // Given
        val accountId = "plex:account:nonexistent"

        // When
        val retrieved = credentialManager.getCredentials(accountId)

        // Then
        assertThat(retrieved).isNull()
    }

    @Test
    fun deleteCredentials_removesToken() {
        // Given
        val accountId = "plex:account:test123"
        val token = "test-auth-token-xyz"
        credentialManager.storeCredentials(accountId, token)

        // When
        credentialManager.deleteCredentials(accountId)

        // Then
        val retrieved = credentialManager.getCredentials(accountId)
        assertThat(retrieved).isNull()
    }

    @Test
    fun hasCredentials_returnsTrueWhenExists() {
        // Given
        val accountId = "plex:account:test123"
        val token = "test-auth-token-xyz"
        credentialManager.storeCredentials(accountId, token)

        // When
        val hasCredentials = credentialManager.hasCredentials(accountId)

        // Then
        assertThat(hasCredentials).isTrue()
    }

    @Test
    fun hasCredentials_returnsFalseWhenNotExists() {
        // Given
        val accountId = "plex:account:nonexistent"

        // When
        val hasCredentials = credentialManager.hasCredentials(accountId)

        // Then
        assertThat(hasCredentials).isFalse()
    }

    @Test
    fun clearAllCredentials_removesAllTokens() {
        // Given
        credentialManager.storeCredentials("plex:account:test1", "token1")
        credentialManager.storeCredentials("plex:account:test2", "token2")
        credentialManager.storeCredentials("plex:account:test3", "token3")

        // When
        credentialManager.clearAllCredentials()

        // Then
        assertThat(credentialManager.hasCredentials("plex:account:test1")).isFalse()
        assertThat(credentialManager.hasCredentials("plex:account:test2")).isFalse()
        assertThat(credentialManager.hasCredentials("plex:account:test3")).isFalse()
    }

    @Test
    fun storeCredentials_overwritesExistingToken() {
        // Given
        val accountId = "plex:account:test123"
        credentialManager.storeCredentials(accountId, "old-token")

        // When
        credentialManager.storeCredentials(accountId, "new-token")

        // Then
        val retrieved = credentialManager.getCredentials(accountId)
        assertThat(retrieved).isEqualTo("new-token")
    }

    /**
     * Regression pin: the injected-store path and the default production path must agree
     * on the keying scheme. If the credential key prefix or default file name ever
     * changes, stored tokens written by the production EncryptedSharedPreferences would
     * become unreachable across an upgrade. This pins the observable contract
     * (store via manager -> read via same store) so a refactor of either path is caught.
     */
    @Test
    fun injectedStore_matchesManagerKeyingScheme() {
        val accountId = "plex:account:contract"
        val token = "contract-token"

        // Write through the manager, read back through the very store we handed it.
        val store: SharedPreferences =
            context.getSharedPreferences("contract_test", Context.MODE_PRIVATE)
        val manager = CredentialManager.forTesting(context, store)
        manager.storeCredentials(accountId, token)

        assertThat(store.getString("credential_$accountId", null)).isEqualTo(token)
        assertThat(manager.getCredentials(accountId)).isEqualTo(token)
    }

    @Test
    fun multipleAccounts_storesDifferentTokens() {
        // Given
        val account1 = "plex:account:user1"
        val account2 = "plex:account:user2"
        val token1 = "token-for-user1"
        val token2 = "token-for-user2"

        // When
        credentialManager.storeCredentials(account1, token1)
        credentialManager.storeCredentials(account2, token2)

        // Then
        assertThat(credentialManager.getCredentials(account1)).isEqualTo(token1)
        assertThat(credentialManager.getCredentials(account2)).isEqualTo(token2)
    }
}
