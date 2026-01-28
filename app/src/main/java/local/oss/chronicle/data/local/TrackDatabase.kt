package local.oss.chronicle.data.local

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import local.oss.chronicle.data.model.MediaItemTrack

private const val TRACK_DATABASE_NAME = "track_db"

private lateinit var INSTANCE: TrackDatabase

fun getTrackDatabase(context: Context): TrackDatabase {
    synchronized(TrackDatabase::class.java) {
        if (!::INSTANCE.isInitialized) {
            INSTANCE =
                Room.databaseBuilder(
                    context.applicationContext,
                    TrackDatabase::class.java,
                    TRACK_DATABASE_NAME,
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                ).build()
        }
    }
    return INSTANCE
}

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE MediaItemTrack ADD COLUMN size INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE MediaItemTrack ADD COLUMN viewCount INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE MediaItemTrack ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 1",
            )
        }
    }

val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Placeholder migration - no schema changes
        }
    }

val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Placeholder migration - no schema changes
        }
    }

val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Ensure parentServerId column exists (may be missing from older databases)
            try {
                db.execSQL("ALTER TABLE MediaItemTrack ADD COLUMN parentServerId INTEGER NOT NULL DEFAULT 0")
            } catch (e: Exception) {
                // Column already exists, continue
            }
            
            // Ensure source column exists (may be missing from databases migrated from v1-v5)
            try {
                db.execSQL("ALTER TABLE MediaItemTrack ADD COLUMN source INTEGER NOT NULL DEFAULT 0")
            } catch (e: Exception) {
                // Column already exists, continue
            }
            
            // Create new table with updated schema
            db.execSQL("""
                CREATE TABLE MediaItemTrack_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    parentKey TEXT NOT NULL,
                    libraryId TEXT NOT NULL DEFAULT '',
                    title TEXT NOT NULL,
                    playQueueItemID INTEGER NOT NULL,
                    thumb TEXT,
                    `index` INTEGER NOT NULL,
                    discNumber INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    media TEXT NOT NULL,
                    album TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    genre TEXT NOT NULL,
                    cached INTEGER NOT NULL,
                    artwork TEXT,
                    viewCount INTEGER NOT NULL,
                    progress INTEGER NOT NULL,
                    lastViewedAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    source INTEGER NOT NULL
                )
            """)
            
            // Copy data with ID transformation: convert Int to "plex:{id}"
            db.execSQL("""
                INSERT INTO MediaItemTrack_new (id, parentKey, libraryId, title, playQueueItemID, thumb, `index`, discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, progress, lastViewedAt, updatedAt, size, source)
                SELECT 'plex:' || id, 'plex:' || parentServerId, '', title, playQueueItemID, thumb, `index`, discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, progress, lastViewedAt, updatedAt, size, source
                FROM MediaItemTrack
            """)
            
            // Drop old table
            db.execSQL("DROP TABLE MediaItemTrack")
            
            // Rename new table to original name
            db.execSQL("ALTER TABLE MediaItemTrack_new RENAME TO MediaItemTrack")
            
            // Create indices
            db.execSQL("CREATE INDEX index_MediaItemTrack_libraryId ON MediaItemTrack(libraryId)")
            db.execSQL("CREATE INDEX index_MediaItemTrack_parentKey ON MediaItemTrack(parentKey)")
        }
    }

@Database(entities = [MediaItemTrack::class], version = 7, exportSchema = true)
abstract class TrackDatabase : RoomDatabase() {
    abstract val trackDao: TrackDao
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM MediaItemTrack")
    fun getAllTracks(): LiveData<List<MediaItemTrack>>

    @Query("SELECT * FROM MediaItemTrack")
    suspend fun getAllTracksAsync(): List<MediaItemTrack>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rows: List<MediaItemTrack>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun update(track: MediaItemTrack)

    @Query("SELECT * FROM MediaItemTrack WHERE id = :id LIMIT 1")
    suspend fun getTrackAsync(id: String): MediaItemTrack?

    @Query(
        "SELECT * FROM MediaItemTrack WHERE parentKey = :bookId AND cached >= :isOfflineMode ORDER BY `discNumber` ASC, `index` ASC",
    )
    fun getTracksForAudiobook(
        bookId: String,
        isOfflineMode: Boolean,
    ): LiveData<List<MediaItemTrack>>

    @Query(
        "SELECT * FROM MediaItemTrack WHERE parentKey = :id AND cached >= :offlineModeActive ORDER BY `discNumber` ASC, `index` ASC",
    )
    suspend fun getTracksForAudiobookAsync(
        id: String,
        offlineModeActive: Boolean,
    ): List<MediaItemTrack>

    @Query("SELECT COUNT(*) FROM MediaItemTrack WHERE parentKey = :bookId")
    suspend fun getTrackCountForAudiobookAsync(bookId: String): Int

    @Query(
        "UPDATE MediaItemTrack SET progress = :trackProgress, lastViewedAt = :lastViewedAt WHERE id = :trackId",
    )
    fun updateProgress(
        trackProgress: Long,
        trackId: String,
        lastViewedAt: Long,
    )

    @Query("DELETE FROM MediaItemTrack")
    fun clear()

    @Query("UPDATE MediaItemTrack SET cached = :isCached WHERE id = :trackId")
    fun updateCachedStatus(
        trackId: String,
        isCached: Boolean,
    ): Int

    @Query("SELECT * FROM MediaItemTrack WHERE cached = :isCached")
    fun getCachedTracksAsync(isCached: Boolean = true): List<MediaItemTrack>

    @Query("SELECT COUNT(*) FROM MediaItemTrack WHERE cached = :isCached AND parentKey = :bookId")
    suspend fun getCachedTrackCountForBookAsync(
        bookId: String,
        isCached: Boolean = true,
    ): Int

    @Query("UPDATE MediaItemTrack SET cached = :isCached")
    suspend fun uncacheAll(isCached: Boolean = false)

    @Query("SELECT * FROM MediaItemTrack WHERE title LIKE :title")
    suspend fun findTrackByTitle(title: String): MediaItemTrack?
    
    // Library-scoped DAO methods
    @Query("SELECT * FROM MediaItemTrack WHERE libraryId = :libraryId")
    fun getTracksForLibrary(libraryId: String): Flow<List<MediaItemTrack>>

    @Query("DELETE FROM MediaItemTrack WHERE libraryId = :libraryId")
    suspend fun deleteByLibraryId(libraryId: String)
}
