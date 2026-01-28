package local.oss.chronicle.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for BookDatabase migration from version 8 to version 9.
 *
 * Migration changes:
 * - id: INTEGER → TEXT (with "plex:" prefix)
 * - Added libraryId: TEXT (foreign key to libraries table, NOT NULL with default)
 */
@RunWith(AndroidJUnit4::class)
class BookDatabaseMigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            BookDatabase::class.java.canonicalName,
            FrameworkSQLiteOpenHelperFactory(),
        )

    // ===== Schema Migration Tests =====

    @Test
    fun migrate8To9_createsNewSchema() {
        // Create database at version 8
        helper.createDatabase(TEST_DB, 8).apply {
            // Insert test data with old schema
            execSQL(
                """
                INSERT INTO books (id, title, author, thumb, duration, 
                    addedAt, updatedAt, lastListenedAt, playbackProgress, 
                    lastListenedTrackKey, cachedState, favorite, playlistTrackKey)
                VALUES (12345, 'Test Book', 'Test Author', '/thumb.jpg', 36000000,
                    1234567890, 1234567890, 1234567890, 50,
                    67890, 0, 0, 67890)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BookDatabase.MIGRATION_8_9)

        // Verify schema changes
        db.query("PRAGMA table_info(books)").use { cursor ->
            val columns = mutableMapOf<String, String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                columns[name] = type
            }

            // Verify id is now TEXT
            assertThat(columns["id"]).isEqualTo("TEXT")

            // Verify libraryId column exists and is TEXT
            assertThat(columns["libraryId"]).isEqualTo("TEXT")
        }
    }

    @Test
    fun migrate8To9_convertsIdToStringWithPrefix() {
        // Create database at version 8
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, thumb, duration, 
                    addedAt, updatedAt, lastListenedAt, playbackProgress, 
                    lastListenedTrackKey, cachedState, favorite, playlistTrackKey)
                VALUES (12345, 'Test Book', 'Test Author', '/thumb.jpg', 36000000,
                    1234567890, 1234567890, 1234567890, 50,
                    67890, 0, 0, 67890)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BookDatabase.MIGRATION_8_9)

        // Verify ID conversion
        db.query("SELECT id FROM books WHERE title = 'Test Book'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            val newId = cursor.getString(0)
            assertThat(newId).isEqualTo("plex:12345")
        }
    }

    @Test
    fun migrate8To9_setsDefaultLibraryId() {
        // Create database at version 8
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, thumb, duration, 
                    addedAt, updatedAt, lastListenedAt, playbackProgress, 
                    lastListenedTrackKey, cachedState, favorite, playlistTrackKey)
                VALUES (12345, 'Test Book', 'Test Author', '/thumb.jpg', 36000000,
                    1234567890, 1234567890, 1234567890, 50,
                    67890, 0, 0, 67890)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BookDatabase.MIGRATION_8_9)

        // Verify libraryId has a default value (will be updated by LegacyAccountMigration)
        db.query("SELECT libraryId FROM books WHERE title = 'Test Book'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            val libraryId = cursor.getString(0)
            // During migration, we set a placeholder that will be updated later
            assertThat(libraryId).isEqualTo("legacy:pending")
        }
    }

    @Test
    fun migrate8To9_preservesAllData() {
        // Create database at version 8 with multiple books
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, thumb, duration, 
                    addedAt, updatedAt, lastListenedAt, playbackProgress, 
                    lastListenedTrackKey, cachedState, favorite, playlistTrackKey)
                VALUES (111, 'Book One', 'Author One', '/thumb1.jpg', 10000,
                    1000, 2000, 3000, 25, 1001, 1, 1, 1001)
            """,
            )
            execSQL(
                """
                INSERT INTO books (id, title, author, thumb, duration, 
                    addedAt, updatedAt, lastListenedAt, playbackProgress, 
                    lastListenedTrackKey, cachedState, favorite, playlistTrackKey)
                VALUES (222, 'Book Two', 'Author Two', '/thumb2.jpg', 20000,
                    4000, 5000, 6000, 75, 2001, 2, 0, 2001)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BookDatabase.MIGRATION_8_9)

        // Verify all data preserved
        db.query("SELECT * FROM books ORDER BY title").use { cursor ->
            assertThat(cursor.count).isEqualTo(2)

            // First book
            cursor.moveToFirst()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("id"))).isEqualTo("plex:111")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("title"))).isEqualTo("Book One")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("author"))).isEqualTo("Author One")
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow("playbackProgress"))).isEqualTo(25)
            assertThat(cursor.getInt(cursor.getColumnIndexOrThrow("favorite"))).isEqualTo(1)

            // Second book
            cursor.moveToNext()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("id"))).isEqualTo("plex:222")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("title"))).isEqualTo("Book Two")
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow("playbackProgress"))).isEqualTo(75)
        }
    }

    @Test
    fun migrate8To9_handlesEmptyDatabase() {
        // Create empty database at version 8
        helper.createDatabase(TEST_DB, 8).apply {
            close()
        }

        // Run migration - should not throw
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BookDatabase.MIGRATION_8_9)

        // Verify table exists and is empty
        db.query("SELECT COUNT(*) FROM books").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migrate8To9_convertsTrackKeys() {
        // Create database at version 8
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, thumb, duration, 
                    addedAt, updatedAt, lastListenedAt, playbackProgress, 
                    lastListenedTrackKey, cachedState, favorite, playlistTrackKey)
                VALUES (12345, 'Test Book', 'Test Author', '/thumb.jpg', 36000000,
                    1234567890, 1234567890, 1234567890, 50,
                    67890, 0, 0, 99999)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BookDatabase.MIGRATION_8_9)

        // Verify track key references are also converted to string format
        db.query("SELECT lastListenedTrackKey, playlistTrackKey FROM books").use { cursor ->
            cursor.moveToFirst()
            val lastListenedTrackKey = cursor.getString(0)
            val playlistTrackKey = cursor.getString(1)

            // Track keys should also be prefixed
            assertThat(lastListenedTrackKey).isEqualTo("plex:67890")
            assertThat(playlistTrackKey).isEqualTo("plex:99999")
        }
    }
}
