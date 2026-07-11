package com.marlendd.remindy.data

import androidx.room.withTransaction
import com.marlendd.remindy.parse.TextNormalizer
import kotlinx.coroutines.flow.Flow

/**
 * Сохранение и чтение записей. Инкапсулирует логику upsert по name_norm:
 * повторная запись того же предмета перезаписывает место, старое уходит в
 * историю (ТЗ F3).
 */
class RecordRepository(private val db: RemindyDatabase) {

    private val itemDao = db.itemDao()
    private val historyDao = db.locationHistoryDao()
    private val synonymDao = db.synonymDao()

    fun observeAll(): Flow<List<Item>> = itemDao.observeAll()

    suspend fun findById(id: Long): Item? = itemDao.findById(id)

    /** Все предметы разово (для поиска). */
    suspend fun allItems(): List<Item> = itemDao.getAll()

    /** Алиасы (синонимы) по id предмета – для поиска. */
    suspend fun aliasesByItem(): Map<Long, List<String>> =
        synonymDao.getAll().groupBy({ it.itemId }, { it.aliasNorm })

    /**
     * Самообучение синонимов (ТЗ F2): когда поиск ничего не нашёл и пользователь
     * выбрал запись из полного списка руками, запоминаем его запрос как алиас.
     */
    suspend fun learnSynonym(query: String, itemId: Long) {
        val aliasNorm = TextNormalizer.normalize(query)
        if (aliasNorm.isBlank()) return
        synonymDao.upsert(Synonym(aliasNorm = aliasNorm, itemId = itemId))
    }

    /**
     * Сохраняет предмет с местом. Если предмет с таким же нормализованным именем
     * уже есть – обновляет место (старое непустое место кладёт в историю), иначе
     * создаёт новую запись. Атомарно.
     */
    suspend fun save(name: String, location: String, now: Long) {
        val nameNorm = TextNormalizer.normalize(name)
        db.withTransaction {
            val existing = itemDao.findByNameNorm(nameNorm)
            if (existing == null) {
                itemDao.insert(
                    Item(
                        name = name,
                        nameNorm = nameNorm,
                        location = location,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                if (existing.location.isNotBlank() && existing.location != location) {
                    historyDao.insert(
                        LocationHistory(
                            itemId = existing.id,
                            location = existing.location,
                            replacedAt = now,
                        ),
                    )
                }
                itemDao.update(
                    existing.copy(
                        name = name,
                        location = location,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    /**
     * Обновляет существующую запись (редактирование из списка). Если имя изменили
     * так, что оно совпало с ДРУГИМ предметом (тот же name_norm, UNIQUE), сливаем
     * в него по логике решения #4 (иначе был бы краш UNIQUE constraint), а
     * отредактированную строку удаляем.
     */
    suspend fun update(item: Item, now: Long) {
        val nameNorm = TextNormalizer.normalize(item.name)
        db.withTransaction {
            val collision = itemDao.findByNameNorm(nameNorm)
            if (collision != null && collision.id != item.id) {
                if (collision.location.isNotBlank() && collision.location != item.location) {
                    historyDao.insert(
                        LocationHistory(
                            itemId = collision.id,
                            location = collision.location,
                            replacedAt = now,
                        ),
                    )
                }
                itemDao.update(
                    collision.copy(name = item.name, location = item.location, updatedAt = now),
                )
                itemDao.delete(item)
            } else {
                itemDao.update(item.copy(nameNorm = nameNorm, updatedAt = now))
            }
        }
    }

    suspend fun delete(item: Item) = itemDao.delete(item)
}
