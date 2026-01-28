package local.oss.chronicle.features.account

import android.content.Context
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
        credentialManager = CredentialManager(context)
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
