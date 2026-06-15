package local.oss.chronicle.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import local.oss.chronicle.data.sources.plex.model.Connection
import timber.log.Timber

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
 * Type converters for Account / Library entities.
 *
 * Note: Room TypeConverters are instantiated by Room via no-arg constructor, so we keep
 * the Moshi adapter as a process-wide lazy singleton rather than injecting it.
 */
class AccountTypeConverters {
    @TypeConverter
    fun fromProviderType(value: ProviderType): String = value.name

    @TypeConverter
    fun toProviderType(value: String): ProviderType = ProviderType.valueOf(value)

    @TypeConverter
    fun fromConnectionList(value: List<Connection>?): String? {
        if (value == null) return null
        return try {
            connectionListAdapter.toJson(value)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to serialize connection list (${value.size} entries)")
            null
        }
    }

    @TypeConverter
    fun toConnectionList(value: String?): List<Connection>? {
        if (value.isNullOrBlank()) return null
        return try {
            connectionListAdapter.fromJson(value)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to deserialize connection list from JSON")
            null
        }
    }

    companion object {
        // Build Moshi with the reflective Kotlin factory so `@JsonClass(generateAdapter = true)`
        // data classes (e.g. [Connection]) round-trip without codegen. The app-wide Moshi in
        // `AppModule.moshi()` is configured the same way; mirror it here so the JSON written by
        // this Room TypeConverter is byte-compatible with anything serialized by the network
        // layer.
        private val moshi: Moshi by lazy {
            Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
        }

        @OptIn(ExperimentalStdlibApi::class)
        private val connectionListAdapter by lazy {
            moshi.adapter<List<Connection>>(
                Types.newParameterizedType(List::class.java, Connection::class.java),
            )
        }
    }
}
