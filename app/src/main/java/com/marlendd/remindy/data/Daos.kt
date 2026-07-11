package com.marlendd.remindy.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    /** Все записи, новые сверху (ТЗ F3). Flow – список сам обновляется после изменений. */
    @Query("SELECT * FROM items ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<Item>>

    /** Разовый снимок для поиска (без наблюдения). */
    @Query("SELECT * FROM items ORDER BY updated_at DESC")
    suspend fun getAll(): List<Item>

    @Query("SELECT * FROM items WHERE name_norm = :nameNorm LIMIT 1")
    suspend fun findByNameNorm(nameNorm: String): Item?

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): Item?

    @Insert
    suspend fun insert(item: Item): Long

    @Update
    suspend fun update(item: Item)

    @Delete
    suspend fun delete(item: Item)
}

@Dao
interface LocationHistoryDao {

    @Insert
    suspend fun insert(history: LocationHistory)

    @Query("SELECT * FROM location_history WHERE item_id = :itemId ORDER BY replaced_at DESC")
    suspend fun forItem(itemId: Long): List<LocationHistory>
}

@Dao
interface SynonymDao {

    @Insert
    suspend fun insert(synonym: Synonym): Long

    // Самообучение синонимов: если такой алиас уже есть, переназначаем его на
    // выбранный сейчас предмет (последнее намерение пользователя важнее)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(synonym: Synonym)

    @Query("SELECT * FROM synonyms")
    suspend fun getAll(): List<Synonym>

    @Query("SELECT * FROM synonyms WHERE item_id = :itemId")
    suspend fun forItem(itemId: Long): List<Synonym>
}
