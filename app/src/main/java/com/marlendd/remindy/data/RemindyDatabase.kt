package com.marlendd.remindy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.marlendd.remindy.security.DatabaseKey
import com.marlendd.remindy.security.KeystorePassphraseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

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
        private const val DB_NAME = "remindy.db"

        @Volatile private var instance: RemindyDatabase? = null
        @Volatile private var sqlcipherLoaded = false

        /**
         * База во внутреннем хранилище, зашифрована SQLCipher (этап 5).
         *
         * Инициализация тяжёлая (Keystore + распаковка нативной либы + первое открытие),
         * поэтому строго с IO-потока. Может бросить [com.marlendd.remindy.security.PassphraseUnavailableException],
         * если ключ Keystore потерян – вызывающая сторона показывает ошибку, а не падает.
         */
        suspend fun getAsync(context: Context): RemindyDatabase =
            withContext(Dispatchers.IO) { get(context.applicationContext) }

        // Синхронный memoized-доступ. Публичный get оставлен для мест, которые УЖЕ на
        // фоновом потоке; из UI использовать getAsync.
        fun get(context: Context): RemindyDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(appContext: Context): RemindyDatabase {
            loadSqlcipher()
            val passphrase = KeystorePassphraseManager(appContext).getOrCreateDatabasePassphrase()
            val rawKey = DatabaseKey.rawKeySpec(passphrase)
            passphrase.fill(0) // сам пароль больше не нужен – дальше живёт только rawKey

            // rawKey НЕ зануляем: SupportOpenHelperFactory держит ссылку и перечитывает
            // ключ при каждом (пере)открытии соединения – зануление окирпичило бы базу.
            return Room.databaseBuilder(appContext, RemindyDatabase::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(rawKey))
                .build()
        }

        private fun loadSqlcipher() {
            if (sqlcipherLoaded) return
            synchronized(this) {
                if (!sqlcipherLoaded) {
                    System.loadLibrary("sqlcipher")
                    sqlcipherLoaded = true
                }
            }
        }
    }
}
