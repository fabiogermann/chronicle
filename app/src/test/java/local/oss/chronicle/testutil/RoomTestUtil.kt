package local.oss.chronicle.testutil

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Utilities for testing Room databases.
 *
 * Note: Some utilities require Android context and are only available in instrumented tests
 * or when using Robolectric for unit tests.
 */
object RoomTestUtil {
    /**
     * Creates an in-memory database for testing.
     * This is primarily for instrumented tests (androidTest).
     *
     * For unit tests, consider using Robolectric or mocking the database layer.
     *
     * @param context Android context (use InstrumentationRegistry.getInstrumentation().targetContext)
     * @return In-memory database instance
     */
    inline fun <reified T : RoomDatabase> createInMemoryDatabase(context: android.content.Context): T {
        return Room.inMemoryDatabaseBuilder(context, T::class.java)
            .allowMainThreadQueries()
            .build()
    }

    /**
     * Extension function to execute SQL and return results as a list of maps.
     * Useful for verifying database state in tests.
     *
     * Example:
     * ```
     * val results = database.openHelper.readableDatabase.queryAsList("SELECT * FROM accounts")
     * assertThat(results.size, `is`(2))
     * ```
     */
    fun SupportSQLiteDatabase.queryAsList(sql: String): List<Map<String, Any?>> {
        val cursor = query(sql)
        val results = mutableListOf<Map<String, Any?>>()

        cursor.use {
            val columnNames = cursor.columnNames
            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, Any?>()
                columnNames.forEachIndexed { index, name ->
                    row[name] =
                        when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                            android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                            else -> null
                        }
                }
                results.add(row)
            }
        }
        return results
    }

    /**
     * Extension function to execute a SQL query and return count of rows.
     * Useful for quick assertions about table size.
     *
     * Example:
     * ```
     * val count = database.openHelper.readableDatabase.queryCount("SELECT * FROM accounts")
     * assertThat(count, `is`(2))
     * ```
     */
    fun SupportSQLiteDatabase.queryCount(sql: String): Int {
        val cursor = query(sql)
        return cursor.use {
            var count = 0
            while (cursor.moveToNext()) {
                count++
            }
            count
        }
    }

    /**
     * Extension function to verify a table exists in the database.
     *
     * Example:
     * ```
     * assertThat(database.openHelper.readableDatabase.tableExists("accounts"), `is`(true))
     * ```
     */
    fun SupportSQLiteDatabase.tableExists(tableName: String): Boolean {
        val cursor =
            query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName),
            )
        return cursor.use {
            cursor.moveToFirst()
        }
    }

    /**
     * Extension function to get column names for a table.
     * Useful for verifying schema in migration tests.
     *
     * Example:
     * ```
     * val columns = database.openHelper.readableDatabase.getTableColumns("accounts")
     * assertThat(columns, hasItem("displayName"))
     * ```
     */
    fun SupportSQLiteDatabase.getTableColumns(tableName: String): List<String> {
        val cursor = query("PRAGMA table_info($tableName)")
        val columns = mutableListOf<String>()

        cursor.use {
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex))
            }
        }
        return columns
    }
}
