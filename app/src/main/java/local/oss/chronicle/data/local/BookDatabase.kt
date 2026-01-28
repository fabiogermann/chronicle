package local.oss.chronicle.data.local

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import local.oss.chronicle.data.model.Audiobook

private const val BOOK_DATABASE_NAME = "book_db"

private lateinit var INSTANCE: BookDatabase

fun getBookDatabase(context: Context): BookDatabase {
    synchronized(BookDatabase::class.java) {
        if (!::INSTANCE.isInitialized) {
            INSTANCE =
                Room.databaseBuilder(
                    context.applicationContext,
                    BookDatabase::class.java,
                    BOOK_DATABASE_NAME,
                ).addMigrations(
                    BOOK_MIGRATION_1_2,
                    BOOK_MIGRATION_2_3,
                    BOOK_MIGRATION_3_4,
                    BOOK_MIGRATION_4_5,
                    BOOK_MIGRATION_5_6,
                    BOOK_MIGRATION_6_7,
                    BOOK_MIGRATION_7_8,
                    BOOK_MIGRATION_8_9,
                ).build()
        }
    }
    return INSTANCE
}

val BOOK_MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Do nothing lol
        }
    }

val BOOK_MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE Audiobook ADD COLUMN chapters TEXT NOT NULL DEFAULT ''")
        }
    }

val BOOK_MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE Audiobook ADD COLUMN source BIGINT NOT NULL DEFAULT -1")
        }
    }

val BOOK_MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE Audiobook ADD COLUMN progress BIGINT NOT NULL DEFAULT 0")
        }
    }

val BOOK_MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE Audiobook ADD COLUMN titleSort TEXT NOT NULL DEFAULT ''")
        }
    }

val BOOK_MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE Audiobook ADD COLUMN viewCount INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

val BOOK_MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE Audiobook ADD COLUMN year INTEGER NOT NULL DEFAULT 0")
        }
    }

val BOOK_MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create new table with updated schema
            db.execSQL("""
                CREATE TABLE Audiobook_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    libraryId TEXT NOT NULL DEFAULT 'legacy:pending',
                    source INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    titleSort TEXT NOT NULL,
                    author TEXT NOT NULL,
                    thumb TEXT NOT NULL,
                    parentId INTEGER NOT NULL,
                    genre TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    year INTEGER NOT NULL,
                    addedAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    lastViewedAt INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    isCached INTEGER NOT NULL,
                    progress INTEGER NOT NULL,
                    favorited INTEGER NOT NULL,
                    viewedLeafCount INTEGER NOT NULL,
                    leafCount INTEGER NOT NULL,
                    viewCount INTEGER NOT NULL,
                    chapters TEXT NOT NULL
                )
            """)
            
            // Copy data with ID transformation: convert Int to "plex:{id}"
            db.execSQL("""
                INSERT INTO Audiobook_new (id, libraryId, source, title, titleSort, author, thumb, parentId, genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, progress, favorited, viewedLeafCount, leafCount, viewCount, chapters)
                SELECT 'plex:' || id, 'legacy:pending', source, title, titleSort, author, thumb, parentId, genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, progress, favorited, viewedLeafCount, leafCount, viewCount, chapters
                FROM Audiobook
            """)
            
            // Drop old table
            db.execSQL("DROP TABLE Audiobook")
            
            // Rename new table to original name
            db.execSQL("ALTER TABLE Audiobook_new RENAME TO Audiobook")
            
            // Create index on libraryId
            db.execSQL("CREATE INDEX index_Audiobook_libraryId ON Audiobook(libraryId)")
        }
    }

@Database(entities = [Audiobook::class], version = 9, exportSchema = true)
abstract class BookDatabase : RoomDatabase() {
    abstract val bookDao: BookDao
}

/**
 * Note: for the weird isCached <= :isOfflineModeActive queries, this ensures that cached items
 * are returned even when offline mode is inactive. A simple equality check would return only
 * cached items during offline mode, but only uncached items when offline mode is inactive. This
 * is an easy way to implement it at the DB level, avoiding messing code in the repository
 */
@Dao
interface BookDao {
    @Query("SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive ORDER BY titleSort")
    fun getAllRows(offlineModeActive: Boolean): LiveData<List<Audiobook>>

    @Query("SELECT * FROM Audiobook")
    fun getAudiobooks(): List<Audiobook>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rows: List<Audiobook>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun update(audiobook: Audiobook)

    @Query("UPDATE Audiobook SET isCached = :cached WHERE id = :bookId")
    fun updateCachedStatus(
        bookId: String,
        cached: Boolean,
    )

    @Query("SELECT * FROM Audiobook WHERE id = :id AND isCached >= :isOfflineModeActive LIMIT 1")
    fun getAudiobook(
        id: String,
        isOfflineModeActive: Boolean,
    ): LiveData<Audiobook?>

    @Query(
        "SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive ORDER BY addedAt DESC LIMIT :bookCount",
    )
    fun getRecentlyAdded(
        bookCount: Int,
        offlineModeActive: Boolean,
    ): LiveData<List<Audiobook>>

    @Query(
        "SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive ORDER BY addedAt DESC LIMIT :bookCount",
    )
    suspend fun getRecentlyAddedAsync(
        bookCount: Int,
        offlineModeActive: Boolean,
    ): List<Audiobook>

    @Query("SELECT * FROM Audiobook ORDER BY updatedAt DESC LIMIT 25")
    fun getOnDeck(): LiveData<List<Audiobook>>

    @Query(
        """
        SELECT * FROM Audiobook 
        WHERE isCached >= :offlineModeActive AND lastViewedAt != 0 AND progress > 10000 AND progress < duration - 120000 
        ORDER BY lastViewedAt DESC 
        LIMIT :bookCount
        """,
    )
    fun getRecentlyListened(
        bookCount: Int,
        offlineModeActive: Boolean,
    ): LiveData<List<Audiobook>>

    @Query(
        """
        SELECT * FROM Audiobook 
        WHERE isCached >= :offlineModeActive AND lastViewedAt != 0 AND progress > 10000 AND progress < duration - 120000 
        ORDER BY lastViewedAt DESC
        LIMIT :bookCount
        """,
    )
    suspend fun getRecentlyListenedAsync(
        bookCount: Int,
        offlineModeActive: Boolean,
    ): List<Audiobook>

    @Query(
        "UPDATE Audiobook SET lastViewedAt = :currentTime, progress = :progress WHERE lastViewedAt < :currentTime AND id = :bookId",
    )
    fun updateProgress(
        bookId: String,
        currentTime: Long,
        progress: Long,
    )

    @Query(
        "UPDATE Audiobook SET duration = :duration, leafCount = :trackCount, progress = :progress WHERE id = :bookId",
    )
    suspend fun updateTrackData(
        bookId: String,
        progress: Long,
        duration: Long,
        trackCount: Int,
    )

    @Query(
        "SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive AND (title LIKE :query OR author LIKE :query)",
    )
    fun search(
        query: String,
        offlineModeActive: Boolean,
    ): LiveData<List<Audiobook>>

    @Query(
        "SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive AND (title LIKE :query OR author LIKE :query)",
    )
    fun searchAsync(
        query: String,
        offlineModeActive: Boolean,
    ): List<Audiobook>

    @Query("DELETE FROM Audiobook")
    suspend fun clear()

    @Query("SELECT * FROM Audiobook ORDER BY lastViewedAt DESC LIMIT 1")
    suspend fun getMostRecent(): Audiobook?

    @Query("SELECT * FROM Audiobook WHERE id = :bookId LIMIT 1")
    suspend fun getAudiobookAsync(bookId: String): Audiobook?

    @Query("SELECT * FROM Audiobook WHERE isCached >= :isCached")
    fun getCachedAudiobooks(isCached: Boolean = true): LiveData<List<Audiobook>>

    @Query("SELECT * FROM Audiobook WHERE isCached >= :isCached")
    fun getCachedAudiobooksAsync(isCached: Boolean = true): List<Audiobook>

    @Query("UPDATE Audiobook SET isCached = :isCached")
    suspend fun uncacheAll(isCached: Boolean = false)

    @Query("SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive ORDER BY titleSort ASC")
    fun getAllBooksAsync(offlineModeActive: Boolean): List<Audiobook>

    @Query("SELECT COUNT(*) FROM Audiobook")
    suspend fun getBookCount(): Int

    @Query("DELETE FROM Audiobook WHERE id IN (:booksToRemove)")
    fun removeAll(booksToRemove: List<String>): Int

    @Query("SELECT * FROM Audiobook ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomBookAsync(): Audiobook?

    @Query("UPDATE Audiobook SET progress = 0 WHERE id = :bookId")
    suspend fun resetBookProgress(bookId: String)

    @Query("UPDATE Audiobook SET viewCount = viewCount + 1 WHERE id = :bookId")
    suspend fun setWatched(bookId: String)

    @Query("UPDATE Audiobook SET viewCount = 0 WHERE id = :bookId")
    suspend fun setUnwatched(bookId: String)
    
    // Library-scoped DAO methods
    @Query("SELECT * FROM Audiobook WHERE libraryId = :libraryId")
    fun getBooksForLibrary(libraryId: String): kotlinx.coroutines.flow.Flow<List<Audiobook>>

    @Query("SELECT * FROM Audiobook WHERE libraryId = :libraryId AND (title LIKE :query OR author LIKE :query)")
    fun searchBooksInLibrary(libraryId: String, query: String): kotlinx.coroutines.flow.Flow<List<Audiobook>>

    @Query("SELECT * FROM Audiobook WHERE libraryId = :libraryId AND favorited = 1")
    fun getFavoritesForLibrary(libraryId: String): kotlinx.coroutines.flow.Flow<List<Audiobook>>

    @Query("SELECT * FROM Audiobook WHERE libraryId = :libraryId AND lastViewedAt != 0 ORDER BY lastViewedAt DESC LIMIT :limit")
    fun getRecentlyListenedForLibrary(libraryId: String, limit: Int): kotlinx.coroutines.flow.Flow<List<Audiobook>>

    @Query("DELETE FROM Audiobook WHERE libraryId = :libraryId")
    suspend fun deleteByLibraryId(libraryId: String)

    @Query("UPDATE Audiobook SET libraryId = :libraryId WHERE libraryId = 'legacy:pending' OR libraryId = ''")
    suspend fun updateLegacyLibraryIds(libraryId: String)
}
