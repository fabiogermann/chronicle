package local.oss.chronicle.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import local.oss.chronicle.data.sources.plex.model.Connection

/**
 * Represents a media library within an account.
 *
 * ID format: "{provider}:library:{sectionId}"
 * Example: "plex:library:3"
 */
@Entity(
    tableName = "libraries",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["serverId"]),
        Index(value = ["isActive"]),
    ],
)
data class Library(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val serverId: String,
    val serverName: String,
    val name: String,
    /**
     * Library type (e.g., "artist" for Plex audiobooks, "audiobook" for Audiobookshelf)
     */
    val type: String,
    val lastSyncedAt: Long?,
    val itemCount: Int,
    /**
     * Whether this is the currently active library.
     * Only one library should be active at a time.
     */
    val isActive: Boolean,
    /**
     * The Plex server URL for this library (e.g., "https://192.168.1.100:32400").
     * Used for library-aware playback to route requests to the correct server.
     * Populated during library sync.
     */
    @ColumnInfo(name = "serverUrl")
    val serverUrl: String? = null,
    /**
     * The authentication token for this library's account.
     * Used for library-aware playback to authenticate requests correctly.
     * Populated during library sync.
     */
    @ColumnInfo(name = "authToken")
    val authToken: String? = null,
    /**
     * The full list of server [Connection]s discovered for this library's server (LAN + WAN + relay).
     *
     * This is persisted so that the app can re-probe and pick a different connection (e.g. WAN)
     * when the previously chosen URI becomes unreachable - typically when the user leaves their
     * home Wi-Fi. Without this, the app would only ever know about whatever URL was active at
     * login/sync time and playback would fail off-network.
     *
     * Stored as JSON via [AccountTypeConverters].
     */
    @ColumnInfo(name = "connections")
    val connections: List<Connection>? = null,
    /**
     * The URI of the currently selected (last successfully probed) [Connection] from [connections].
     *
     * This is a hint for the resolver - it does not replace [serverUrl], which still holds the
     * last-known-good URL for legacy callers. When [connections] is non-empty the resolver re-probes
     * the list whenever this choice is stale.
     */
    @ColumnInfo(name = "chosenConnectionUri")
    val chosenConnectionUri: String? = null,
    /**
     * Timestamp (epoch millis) of the last successful connection probe.
     * Used by [local.oss.chronicle.data.sources.plex.ServerConnectionResolver] to decide whether
     * the persisted choice is still considered fresh or needs re-probing.
     */
    @ColumnInfo(name = "lastConnectionCheckAt")
    val lastConnectionCheckAt: Long? = null,
)
