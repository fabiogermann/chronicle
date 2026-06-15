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
 * Tests for TrackDatabase migration from version 6 to version 7.
 *
 * Migration changes:
 * - id: INTEGER → TEXT (with "plex:" prefix)
 * - parentServerId → parentKey: INTEGER → TEXT (with "plex:" prefix)
 * - Added libraryId: TEXT (NOT NULL with default)
 */
@RunWith(AndroidJUnit4::class)
class TrackDatabaseMigrationTest {
    private val TEST_DB = "track-migration-test"

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            TrackDatabase::class.java.canonicalName,
            FrameworkSQLiteOpenHelperFactory(),
        )

    // ===== Schema Migration Tests =====

    @Test
    fun migrate6To7_createsNewSchema() {
        // Create database at version 6
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO MediaItemTrack (id, serverId, parentServerId, title, playQueueItemID, thumb, `index`, discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, progress, lastViewedAt, updatedAt, size, source)
                VALUES (111, 111, 12345, 'Chapter 1', 0, NULL, 1, 1, 300000, '', '', '', '', 0, NULL, 0, 0, 0, 0, 0, 0)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        // Verify schema changes
        db.query("PRAGMA table_info(MediaItemTrack)").use { cursor ->
            val columns = mutableMapOf<String, String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                columns[name] = type
            }

            // Verify id is now TEXT
            assertThat(columns["id"]).isEqualTo("TEXT")

            // Verify parentKey is now TEXT
            assertThat(columns["parentKey"]).isEqualTo("TEXT")

            // Verify libraryId column exists and is TEXT
            assertThat(columns["libraryId"]).isEqualTo("TEXT")
        }
    }

    @Test
    fun migrate6To7_convertsIdAndParentKeyToStringWithPrefix() {
        // Create database at version 6
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO MediaItemTrack (id, serverId, parentServerId, title, playQueueItemID, thumb, `index`, discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, progress, lastViewedAt, updatedAt, size, source)
                VALUES (111, 111, 12345, 'Chapter 1', 0, NULL, 1, 1, 300000, '', '', '', '', 0, NULL, 0, 0, 0, 0, 0, 0)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        // Verify ID and parentKey conversion
        db.query("SELECT id, parentKey FROM MediaItemTrack").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()

            val newId = cursor.getString(0)
            val newParentKey = cursor.getString(1)

            assertThat(newId).isEqualTo("plex:111")
            assertThat(newParentKey).isEqualTo("plex:12345")
        }
    }

    @Test
    fun migrate6To7_setsDefaultLibraryId() {
        // Create database at version 6
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO MediaItemTrack (id, serverId, parentServerId, title, playQueueItemID, thumb, `index`, discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, progress, lastViewedAt, updatedAt, size, source)
                VALUES (111, 111, 12345, 'Chapter 1', 0, NULL, 1, 1, 300000, '', '', '', '', 0, NULL, 0, 0, 0, 0, 0, 0)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        // Verify libraryId has a default value
        db.query("SELECT libraryId FROM MediaItemTrack").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            val libraryId = cursor.getString(0)
            // Migration sets empty string as default (updated later by LegacyAccountMigration)
            assertThat(libraryId).isEqualTo("")
        }
    }

    @Test
    fun migrate6To7_preservesAllData() {
        // Create database at version 6 with multiple tracks
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO MediaItemTrack (id, serverId, parentServerId, title, playQueueItemID, thumb, `index`, discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, progress, lastViewedAt, updatedAt, size, source)
                VALUES (111, 111, 12345, 'Chapter 1', 0, NULL, 1, 1, 300000, '', '', '', '', 0, NULL, 0, 0, 0, 0, 0, 0)
            """,
            )
            execSQL(
                """
                INSERT INTO MediaItemTrack (id, serverId, parentServerId, title, playQueueItemID, thumb, `index`, discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, progress, lastViewedAt, updatedAt, size, source)
                VALUES (222, 222, 12345, 'Chapter 2', 0, NULL, 2, 1, 400000, '', '', '', '', 0, NULL, 0, 0, 0, 0, 0, 0)
            """,
            )
            execSQL(
                """
                INSERT INTO MediaItemTrack (id, serverId, parentServerId, title, playQueueItemID, thumb, `index`, discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, progress, lastViewedAt, updatedAt, size, source)
                VALUES (333, 333, 67890, 'Intro', 0, NULL, 1, 1, 60000, '', '', '', '', 0, NULL, 0, 0, 0, 0, 0, 0)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        // Verify all data preserved
        db.query("SELECT * FROM MediaItemTrack ORDER BY id").use { cursor ->
            assertThat(cursor.count).isEqualTo(3)

            // First track
            cursor.moveToFirst()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("id"))).isEqualTo("plex:111")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("parentKey"))).isEqualTo("plex:12345")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("title"))).isEqualTo("Chapter 1")
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow("duration"))).isEqualTo(300000)
            assertThat(cursor.getInt(cursor.getColumnIndexOrThrow("index"))).isEqualTo(1)

            // Second track
            cursor.moveToNext()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("id"))).isEqualTo("plex:222")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("parentKey"))).isEqualTo("plex:12345")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("title"))).isEqualTo("Chapter 2")

            // Third track
            cursor.moveToNext()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("id"))).isEqualTo("plex:333")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("parentKey"))).isEqualTo("plex:67890")
        }
    }

    @Test
    fun migrate6To7_handlesEmptyDatabase() {
        // Create empty database at version 6
        helper.createDatabase(TEST_DB, 6).apply {
            close()
        }

        // Run migration - should not throw
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        // Verify table exists and is empty
        db.query("SELECT COUNT(*) FROM MediaItemTrack").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migrate6To7_preservesNullFilePaths() {
        // Create database at version 6 with null thumb (no filePath column in this schema)
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO MediaItemTrack (id, serverId, parentServerId, title, playQueueItemID, thumb, `index`, discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, progress, lastViewedAt, updatedAt, size, source)
                VALUES (111, 111, 12345, 'Streaming Chapter', 0, NULL, 1, 1, 300000, '', '', '', '', 0, NULL, 0, 0, 0, 0, 0, 0)
            """,
            )
            close()
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        // Verify null thumb preserved
        db.query("SELECT thumb FROM MediaItemTrack WHERE id = 'plex:111'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
    }
}
