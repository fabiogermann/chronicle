package local.oss.chronicle.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.AccountTypeConverters
import local.oss.chronicle.data.model.Library

@Database(
    entities = [Account::class, Library::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(AccountTypeConverters::class)
abstract class AccountDatabase : RoomDatabase() {
    
    abstract fun accountDao(): AccountDao
    abstract fun libraryDao(): LibraryDao
    
    companion object {
        private const val DATABASE_NAME = "chronicle_accounts.db"
        
        @Volatile
        private var INSTANCE: AccountDatabase? = null
        
        fun getInstance(context: Context): AccountDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context): AccountDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AccountDatabase::class.java,
                DATABASE_NAME
            ).build()
        }
        
        /**
         * Creates an in-memory database for testing.
         */
        fun createInMemoryDatabase(context: Context): AccountDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AccountDatabase::class.java
            )
                .allowMainThreadQueries() // For testing only
                .build()
        }
    }
}
