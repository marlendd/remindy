package com.marlendd.remindy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Сериализация записей бэкапа – чистая JVM, без Android/устройства. */
class BackupCodecTest {

    @Test fun roundTripPreservesRecords() {
        val records = listOf(
            BackupRecord("очки", "на столе", 1000L, 2000L),
            BackupRecord("паспорт", "", 3000L, 3000L),
            BackupRecord("ключи от дачи", "в кармане куртки", 5L, 9_999_999_999L),
        )
        assertEquals(records, BackupCodec.decode(BackupCodec.encode(records)))
    }

    @Test fun emptyListRoundTrips() {
        assertEquals(emptyList<BackupRecord>(), BackupCodec.decode(BackupCodec.encode(emptyList())))
    }

    @Test fun manyRecordsRoundTrip() {
        val records = (1..500).map {
            BackupRecord("предмет $it", "место $it", it.toLong(), (it * 2).toLong())
        }
        assertEquals(records, BackupCodec.decode(BackupCodec.encode(records)))
    }

    @Test fun unicodeAndEmojiPreserved() {
        val records = listOf(
            BackupRecord("зарядка 🔌", "тумбочка у кровати – верхний ящик", 1L, 1L),
        )
        assertEquals(records, BackupCodec.decode(BackupCodec.encode(records)))
    }

    @Test fun truncatedPayloadThrows() {
        val bytes = BackupCodec.encode(listOf(BackupRecord("очки", "на столе", 1L, 2L)))
        val truncated = bytes.copyOf(bytes.size - 3)
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(truncated) }
    }

    @Test fun garbageThrows() {
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(ByteArray(2)) }
    }

    @Test fun unknownVersionThrows() {
        val bytes = BackupCodec.encode(listOf(BackupRecord("x", "y", 1L, 2L)))
        // version записан первым int (big-endian) – ломаем младший байт
        bytes[3] = 99
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(bytes) }
    }
}
