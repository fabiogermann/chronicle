package local.oss.chronicle.features.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages secure storage of account credentials using EncryptedSharedPreferences.
 *
 * Credentials are encrypted using Android Keystore-backed keys.
 */
@Singleton
class CredentialManager @Inject constructor(
    private val context: Context
) {
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Store credentials for an account.
     * @param accountId The account ID (e.g., "plex:account:xxx")
     * @param token The authentication token to store
     */
    fun storeCredentials(accountId: String, token: String) {
        encryptedPrefs.edit()
            .putString(credentialKey(accountId), token)
            .apply()
    }

    /**
     * Retrieve credentials for an account.
     * @param accountId The account ID
     * @return The stored token, or null if not found
     */
    fun getCredentials(accountId: String): String? {
        return encryptedPrefs.getString(credentialKey(accountId), null)
    }

    /**
     * Delete credentials for an account.
     * @param accountId The account ID
     */
    fun deleteCredentials(accountId: String) {
        encryptedPrefs.edit()
            .remove(credentialKey(accountId))
            .apply()
    }

    /**
     * Check if credentials exist for an account.
     */
    fun hasCredentials(accountId: String): Boolean {
        return encryptedPrefs.contains(credentialKey(accountId))
    }

    /**
     * Clear all stored credentials.
     * Use with caution - this removes ALL account credentials.
     */
    fun clearAllCredentials() {
        encryptedPrefs.edit().clear().apply()
    }

    private fun credentialKey(accountId: String): String = "credential_$accountId"

    companion object {
        private const val ENCRYPTED_PREFS_FILE = "chronicle_credentials"
    }
}
