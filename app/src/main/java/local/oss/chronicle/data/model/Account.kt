package local.oss.chronicle.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Represents a user account for a content provider.
 *
 * ID format: "{provider}:account:{uuid}"
 * Example: "plex:account:550e8400-e29b-41d4-a716-446655440000"
 */
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey
    val id: String,
    val providerType: ProviderType,
    val displayName: String,
    val avatarUrl: String?,
    /**
     * Encrypted credentials (token, etc.)
     * Encryption handled by CredentialManager
     */
    val credentials: String,
    val createdAt: Long,
    val lastUsedAt: Long,
)

/**
 * Type converters for Account entity
 */
class AccountTypeConverters {
    @TypeConverter
    fun fromProviderType(value: ProviderType): String = value.name

    @TypeConverter
    fun toProviderType(value: String): ProviderType = ProviderType.valueOf(value)
}
