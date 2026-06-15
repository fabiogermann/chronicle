package local.oss.chronicle.data.local

import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-based migration test for [AccountDatabase] v2 → v3 (off-network playback fix).
 *
 * We deliberately avoid `androidx.room.testing.MigrationTestHelper` here because that helper
 * needs an Android instrumentation context and forces the whole test class into androidTest.
 * Instead we open a SQLite database at the v2 schema by running v2's `CREATE TABLE` directly,
 * insert a row of legacy data, and then run [AccountDatabase.MIGRATION_2_3] manually using
 * Room's `SupportSQLiteOpenHelperFactory`. This exercises the same migration block that ships
 * in production and asserts that existing rows survive the upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AccountDatabaseMigrationTest {
    private val factory = FrameworkSQLiteOpenHelperFactory()

    private fun openV2Database(): androidx.sqlite.db.SupportSQLiteDatabase {
        // Build a fresh SQLite database with the v2 schema. We use Room's plumbing to get a
        // standard SupportSQLiteDatabase, but with a fake DB name + version 2.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)
        val config =
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(V2_SCHEMA_VERSION) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE accounts (id TEXT PRIMARY KEY NOT NULL, providerType TEXT NOT NULL, displayName TEXT NOT NULL, avatarUrl TEXT, credentials TEXT NOT NULL, createdAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL)")
                            // v2 libraries table - includes serverUrl + authToken from MIGRATION_1_2
                            db.execSQL(
                                "CREATE TABLE libraries (" +
                                    "id TEXT PRIMARY KEY NOT NULL, " +
                                    "accountId TEXT NOT NULL, " +
                                    "serverId TEXT NOT NULL, " +
                                    "serverName TEXT NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "type TEXT NOT NULL, " +
                                    "lastSyncedAt INTEGER, " +
                                    "itemCount INTEGER NOT NULL, " +
                                    "isActive INTEGER NOT NULL, " +
                                    "serverUrl TEXT DEFAULT NULL, " +
                                    "authToken TEXT DEFAULT NULL, " +
                                    "FOREIGN KEY(accountId) REFERENCES accounts(id) ON DELETE CASCADE)",
                            )
                            db.execSQL("CREATE INDEX index_libraries_accountId ON libraries(accountId)")
                            db.execSQL("CREATE INDEX index_libraries_serverId ON libraries(serverId)")
                            db.execSQL("CREATE INDEX index_libraries_isActive ON libraries(isActive)")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build()
        return factory.create(config).writableDatabase
    }

    @After
    fun teardown() {
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(TEST_DB)
    }

    @Test
    fun `migration 2 to 3 adds the three new columns and preserves existing rows`() {
        val db = openV2Database()
        try {
            // Seed v2 data: one account + one library with the legacy `serverUrl` set.
            db.execSQL(
                "INSERT INTO accounts(id, providerType, displayName, avatarUrl, credentials, createdAt, lastUsedAt) " +
                    "VALUES('plex:account:abc', 'PLEX', 'Tester', NULL, '', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO libraries(id, accountId, serverId, serverName, name, type, lastSyncedAt, itemCount, isActive, serverUrl, authToken) " +
                    "VALUES('plex:library:1', 'plex:account:abc', 'server-1', 'Home', 'Audiobooks', 'artist', NULL, 0, 1, 'http://192.168.1.20:32400', 'token-1')",
            )

            // Run the production migration block.
            AccountDatabase.MIGRATION_2_3.migrate(db)

            // Assert the new columns exist and are NULL for legacy rows.
            db.query(
                "SELECT id, serverUrl, authToken, connections, chosenConnectionUri, lastConnectionCheckAt FROM libraries",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("plex:library:1")
                assertThat(cursor.getString(1)).isEqualTo("http://192.168.1.20:32400")
                assertThat(cursor.getString(2)).isEqualTo("token-1")
                // New columns: present, NULL by default.
                assertThat(cursor.isNull(3)).isTrue()
                assertThat(cursor.isNull(4)).isTrue()
                assertThat(cursor.isNull(5)).isTrue()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrated database accepts inserts that populate the new columns`() {
        val db = openV2Database()
        try {
            // Seed v2 data + migrate.
            db.execSQL(
                "INSERT INTO accounts(id, providerType, displayName, avatarUrl, credentials, createdAt, lastUsedAt) " +
                    "VALUES('plex:account:abc', 'PLEX', 'Tester', NULL, '', 0, 0)",
            )
            AccountDatabase.MIGRATION_2_3.migrate(db)

            // After migration the new columns must be writable.
            val now = 1_700_000_000_000L
            db.execSQL(
                "INSERT INTO libraries(id, accountId, serverId, serverName, name, type, lastSyncedAt, itemCount, isActive, serverUrl, authToken, connections, chosenConnectionUri, lastConnectionCheckAt) " +
                    "VALUES('plex:library:2', 'plex:account:abc', 'server-2', 'Remote', 'Books', 'artist', NULL, 0, 0, 'https://wan.example:32400', 'token-2', '[{\"uri\":\"https://wan.example:32400\",\"local\":false}]', 'https://wan.example:32400', $now)",
            )

            db.query(
                "SELECT connections, chosenConnectionUri, lastConnectionCheckAt FROM libraries WHERE id = 'plex:library:2'",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                val connectionsJson = cursor.getString(0)
                assertThat(connectionsJson).contains("wan.example")
                assertThat(cursor.getString(1)).isEqualTo("https://wan.example:32400")
                assertThat(cursor.getLong(2)).isEqualTo(now)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `Room can open the database after MIGRATION_2_3 runs against a v2 file`() {
        // Seed a v2 file on disk
        val db = openV2Database()
        db.execSQL(
            "INSERT INTO accounts(id, providerType, displayName, avatarUrl, credentials, createdAt, lastUsedAt) " +
                "VALUES('plex:account:abc', 'PLEX', 'Tester', NULL, '', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO libraries(id, accountId, serverId, serverName, name, type, lastSyncedAt, itemCount, isActive, serverUrl, authToken) " +
                "VALUES('plex:library:1', 'plex:account:abc', 'server-1', 'Home', 'Audiobooks', 'artist', NULL, 0, 1, 'http://lan:32400', 't1')",
        )
        // We must also persist the schema version that the migration framework reads. The
        // SupportSQLiteOpenHelper above already set user_version = 2.
        db.close()

        // Now ask Room to open the same file with the production migration set; this proves the
        // v2 -> v3 path works end-to-end.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val accountDb =
            Room.databaseBuilder(context, AccountDatabase::class.java, TEST_DB)
                .addMigrations(AccountDatabase.MIGRATION_1_2, AccountDatabase.MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()

        try {
            val cursor = accountDb.query("SELECT id, connections, chosenConnectionUri FROM libraries", emptyArray())
            cursor.use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("plex:library:1")
                // Legacy row: new columns default to NULL.
                assertThat(it.isNull(1)).isTrue()
                assertThat(it.isNull(2)).isTrue()
            }
        } finally {
            accountDb.close()
        }
    }

    companion object {
        private const val TEST_DB = "account-migration-test"
        private const val V2_SCHEMA_VERSION = 2
    }
}
