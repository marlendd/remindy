package com.marlendd.remindy.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Одна запись в резервной копии. Только то, что нужно, чтобы «не потерять, где что
 * лежит»: предмет, место и даты. Синонимы/историю мест в бэкап не кладём (решение
 * пользователя) – они со временем восстанавливаются сами при использовании.
 */
data class BackupRecord(
    val name: String,
    val location: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Бинарная (де)сериализация записей бэкапа. Без внешних зависимостей и без Android API,
 * поэтому тестируется на чистой JVM. Это ПЛЕЙНТЕКСТ – наружу выходит только внутри
 * зашифрованного конверта [com.marlendd.remindy.security.BackupCrypto].
 *
 * Формат payload:
 *   int   version (=1)
 *   int   count
 *   count раз:
 *     UTF  name        (writeUTF: [len:2][utf8], ≤ 65535 байт)
 *     UTF  location
 *     long createdAt
 *     long updatedAt
 */
object BackupCodec {

    const val PAYLOAD_VERSION = 1

    fun encode(records: List<BackupRecord>): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(PAYLOAD_VERSION)
            out.writeInt(records.size)
            for (r in records) {
                out.writeUTF(r.name)
                out.writeUTF(r.location)
                out.writeLong(r.createdAt)
                out.writeLong(r.updatedAt)
            }
        }
        return bos.toByteArray()
    }

    /** @throws BackupFormatException при неизвестной версии или усечённых данных. */
    fun decode(bytes: ByteArray): List<BackupRecord> {
        try {
            DataInputStream(bytes.inputStream()).use { inp ->
                val version = inp.readInt()
                if (version != PAYLOAD_VERSION) {
                    throw BackupFormatException("Неподдерживаемая версия копии: $version")
                }
                val count = inp.readInt()
                if (count < 0) throw BackupFormatException("Повреждённая копия (count=$count)")
                // Начальную ёмкость кэпим: битый файл с огромным count не должен сразу
                // отъедать память – реальные данные всё равно упрутся в EOF ниже.
                val list = ArrayList<BackupRecord>(count.coerceAtMost(1024))
                repeat(count) {
                    val name = inp.readUTF()
                    val location = inp.readUTF()
                    val createdAt = inp.readLong()
                    val updatedAt = inp.readLong()
                    list.add(BackupRecord(name, location, createdAt, updatedAt))
                }
                return list
            }
        } catch (e: EOFException) {
            throw BackupFormatException("Повреждённая копия (данные усечены)", e)
        }
    }
}

/** Плейнтекст расшифровался, но это не наш формат (версия/усечение). */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
