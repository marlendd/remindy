package com.marlendd.remindy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Item::class, Synonym::class, LocationHistory::class],
    version = 1,
    exportSchema = true,
)
abstract class RemindyDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun locationHistoryDao(): LocationHistoryDao
    abstract fun synonymDao(): SynonymDao

    companion object {
        @Volatile private var instance: RemindyDatabase? = null

        // База во внутреннем хранилище приложения. Этап 5: сюда встанет SQLCipher-фабрика.
        fun get(context: Context): RemindyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RemindyDatabase::class.java,
                    "remindy.db",
                ).build().also { instance = it }
            }
    }
}
