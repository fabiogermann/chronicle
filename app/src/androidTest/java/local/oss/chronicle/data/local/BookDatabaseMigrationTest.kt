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
                INSERT INTO Audiobook (id, source, title, titleSort, author, thumb, parentId, genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, progress, favorited, viewedLeafCount, leafCount, viewCount, chapters)
                VALUES (12345, 0, 'Test Book', 'Test Book', 'Test Author', '/thumb.jpg', 0, '', '', 0, 1234567890, 1234567890, 1234567890, 36000000, 0, 50, 0, 0, 0, 0, '')
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BOOK_MIGRATION_8_9)

        // Verify schema changes
        db.query("PRAGMA table_info(Audiobook)").use { cursor ->
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
                INSERT INTO Audiobook (id, source, title, titleSort, author, thumb, parentId, genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, progress, favorited, viewedLeafCount, leafCount, viewCount, chapters)
                VALUES (12345, 0, 'Test Book', 'Test Book', 'Test Author', '/thumb.jpg', 0, '', '', 0, 1234567890, 1234567890, 1234567890, 36000000, 0, 50, 0, 0, 0, 0, '')
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BOOK_MIGRATION_8_9)

        // Verify ID conversion
        db.query("SELECT id FROM Audiobook WHERE title = 'Test Book'").use { cursor ->
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
                INSERT INTO Audiobook (id, source, title, titleSort, author, thumb, parentId, genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, progress, favorited, viewedLeafCount, leafCount, viewCount, chapters)
                VALUES (12345, 0, 'Test Book', 'Test Book', 'Test Author', '/thumb.jpg', 0, '', '', 0, 1234567890, 1234567890, 1234567890, 36000000, 0, 50, 0, 0, 0, 0, '')
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BOOK_MIGRATION_8_9)

        // Verify libraryId has a default value (will be updated by LegacyAccountMigration)
        db.query("SELECT libraryId FROM Audiobook WHERE title = 'Test Book'").use { cursor ->
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
                INSERT INTO Audiobook (id, source, title, titleSort, author, thumb, parentId, genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, progress, favorited, viewedLeafCount, leafCount, viewCount, chapters)
                VALUES (111, 0, 'Book One', 'Book One', 'Author One', '/thumb1.jpg', 0, '', '', 0, 1000, 2000, 3000, 10000, 0, 25, 1, 0, 0, 0, '')
            """,
            )
            execSQL(
                """
                INSERT INTO Audiobook (id, source, title, titleSort, author, thumb, parentId, genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, progress, favorited, viewedLeafCount, leafCount, viewCount, chapters)
                VALUES (222, 0, 'Book Two', 'Book Two', 'Author Two', '/thumb2.jpg', 0, '', '', 0, 4000, 5000, 6000, 20000, 0, 75, 0, 0, 0, 0, '')
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BOOK_MIGRATION_8_9)

        // Verify all data preserved
        db.query("SELECT * FROM Audiobook ORDER BY title").use { cursor ->
            assertThat(cursor.count).isEqualTo(2)

            // First book
            cursor.moveToFirst()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("id"))).isEqualTo("plex:111")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("title"))).isEqualTo("Book One")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("author"))).isEqualTo("Author One")
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow("progress"))).isEqualTo(25)
            assertThat(cursor.getInt(cursor.getColumnIndexOrThrow("favorited"))).isEqualTo(1)

            // Second book
            cursor.moveToNext()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("id"))).isEqualTo("plex:222")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("title"))).isEqualTo("Book Two")
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow("progress"))).isEqualTo(75)
        }
    }

    @Test
    fun migrate8To9_handlesEmptyDatabase() {
        // Create empty database at version 8
        helper.createDatabase(TEST_DB, 8).apply {
            close()
        }

        // Run migration - should not throw
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BOOK_MIGRATION_8_9)

        // Verify table exists and is empty
        db.query("SELECT COUNT(*) FROM Audiobook").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }
}
