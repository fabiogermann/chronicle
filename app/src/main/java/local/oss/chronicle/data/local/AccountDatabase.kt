package local.oss.chronicle.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.AccountTypeConverters
import local.oss.chronicle.data.model.Library

@Database(
    entities = [Account::class, Library::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(AccountTypeConverters::class)
abstract class AccountDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun libraryDao(): LibraryDao

    companion object {
        private const val DATABASE_NAME = "chronicle_accounts.db"

        @Volatile
        private var INSTANCE: AccountDatabase? = null

        /**
         * Migration from version 1 to 2: Add serverUrl and authToken columns to libraries table.
         * These fields enable library-aware playback by storing server connection details per library.
         */
        internal val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE libraries ADD COLUMN serverUrl TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE libraries ADD COLUMN authToken TEXT DEFAULT NULL")
                }
            }

        /**
         * Migration from version 2 to 3: Add per-library connection metadata for off-network playback.
         *
         * - `connections`        JSON-encoded list of all server [Connection]s known for this library.
         * - `chosenConnectionUri` URI of the last successfully probed connection (hint for the resolver).
         * - `lastConnectionCheckAt` Epoch millis of the last successful probe (TTL gate).
         *
         * These columns are populated lazily on the next login / sync / startup probe, so the
         * existing `serverUrl` keeps working as a legacy "last known good URL" for already-installed
         * users until then.
         */
        internal val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE libraries ADD COLUMN connections TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE libraries ADD COLUMN chosenConnectionUri TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE libraries ADD COLUMN lastConnectionCheckAt INTEGER DEFAULT NULL")
                }
            }

        fun getInstance(context: Context): AccountDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AccountDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AccountDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        /**
         * Creates an in-memory database for testing.
         */
        fun createInMemoryDatabase(context: Context): AccountDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AccountDatabase::class.java,
            )
                .allowMainThreadQueries() // For testing only
                .build()
        }
    }
}
