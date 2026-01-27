package local.oss.chronicle.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["serverId"]),
        Index(value = ["isActive"])
    ]
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
    val isActive: Boolean
)
