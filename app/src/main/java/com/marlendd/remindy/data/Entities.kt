package com.marlendd.remindy.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Предмет и его текущее место. name_norm уникален – один предмет на имя (ТЗ F3).
 * photo_file – ИМЯ файла фото места в приватной папке `filesDir/photos/` (не абсолютный
 * путь: файл резолвится через PhotoStore). Null – фото нет. В зашифрованный бэкап фото
 * не входит (решение пользователя), сам файл не шифруется – папка приложения недоступна
 * другим приложениям, просмотр в UI за гейтом.
 */
@Entity(
    tableName = "items",
    indices = [Index(value = ["name_norm"], unique = true)],
)
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "name_norm") val nameNorm: String,
    val location: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "photo_file") val photoFile: String? = null,
)

/** Синоним (алиас) предмета. Таблица создаётся сейчас, наполняется на этапе 3 (поиск). */
@Entity(
    tableName = "synonyms",
    foreignKeys = [
        ForeignKey(
            entity = Item::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("item_id"), Index(value = ["alias_norm"], unique = true)],
)
data class Synonym(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "alias_norm") val aliasNorm: String,
    @ColumnInfo(name = "item_id") val itemId: Long,
)

/** История прошлых мест предмета: при перезаписи старое место уходит сюда (ТЗ F3). */
@Entity(
    tableName = "location_history",
    foreignKeys = [
        ForeignKey(
            entity = Item::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("item_id")],
)
data class LocationHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_id") val itemId: Long,
    val location: String,
    @ColumnInfo(name = "replaced_at") val replacedAt: Long,
)
