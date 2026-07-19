package com.marlendd.remindy.security

import com.marlendd.remindy.data.BackupCodec
import com.marlendd.remindy.data.BackupRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Крипта конверта бэкапа – чистая JVM, без Android/устройства. */
class BackupCryptoTest {

    private fun pw(s: String) = s.toCharArray()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test fun roundTripReturnsOriginal() {
        val data = "очки на столе; паспорт в шкафу".toByteArray()
        val envelope = BackupCrypto.encrypt(data, pw("secret123"))
        assertArrayEquals(data, BackupCrypto.decrypt(envelope, pw("secret123")))
    }

    @Test fun emptyPayloadRoundTrips() {
        val data = ByteArray(0)
        val envelope = BackupCrypto.encrypt(data, pw("password"))
        assertArrayEquals(data, BackupCrypto.decrypt(envelope, pw("password")))
    }

    @Test fun wrongPasswordThrows() {
        val envelope = BackupCrypto.encrypt("data".toByteArray(), pw("correct-horse"))
        assertThrows(WrongPasswordException::class.java) {
            BackupCrypto.decrypt(envelope, pw("wrong-horse"))
        }
    }

    @Test fun tamperedCiphertextThrows() {
        val envelope = BackupCrypto.encrypt("hello world".toByteArray(), pw("password"))
        // Портим последний байт (часть GCM-тега) – аутентификация должна упасть
        envelope[envelope.size - 1] = (envelope[envelope.size - 1] + 1).toByte()
        assertThrows(WrongPasswordException::class.java) {
            BackupCrypto.decrypt(envelope, pw("password"))
        }
    }

    @Test fun foreignFileThrows() {
        // Не наш формат: нет MAGIC "RMDY" в начале
        assertThrows(BadBackupFileException::class.java) {
            BackupCrypto.decrypt("это просто текст, а не резервная копия".toByteArray(), pw("password"))
        }
    }

    @Test fun truncatedFileThrows() {
        assertThrows(BadBackupFileException::class.java) {
            BackupCrypto.decrypt(ByteArray(5), pw("password"))
        }
    }

    @Test fun unknownEnvelopeVersionThrows() {
        val envelope = BackupCrypto.encrypt("x".toByteArray(), pw("password"))
        envelope[4] = 9 // байт версии сразу после MAGIC (4 байта)
        assertThrows(BadBackupFileException::class.java) {
            BackupCrypto.decrypt(envelope, pw("password"))
        }
    }

    @Test fun eachExportUsesFreshRandomization() {
        val data = "same data".toByteArray()
        val a = BackupCrypto.encrypt(data, pw("password"))
        val b = BackupCrypto.encrypt(data, pw("password"))
        // Разные salt/iv → разный конверт при тех же данных и пароле
        assertFalse(a.contentEquals(b))
        // Но оба расшифровываются в исходные данные
        assertArrayEquals(data, BackupCrypto.decrypt(a, pw("password")))
        assertArrayEquals(data, BackupCrypto.decrypt(b, pw("password")))
    }

    @Test fun cyrillicPasswordRoundTrips() {
        // Смысл фичи – восстановление на другом устройстве; пароль может быть кириллическим.
        val data = "данные".toByteArray()
        val envelope = BackupCrypto.encrypt(data, pw("Пароль-Копии2"))
        assertArrayEquals(data, BackupCrypto.decrypt(envelope, pw("Пароль-Копии2")))
    }

    @Test fun fullFileRoundTripThroughCodec() {
        // Интеграция: записи → кодек → шифр → дешифр → кодек → те же записи
        val records = listOf(
            BackupRecord("очки", "на столе", 1L, 2L),
            BackupRecord("паспорт", "в шкафу", 3L, 4L),
        )
        val file = BackupCrypto.encrypt(BackupCodec.encode(records), pw("пароль123"))
        val restored = BackupCodec.decode(BackupCrypto.decrypt(file, pw("пароль123")))
        assertEquals(records, restored)
    }

    @Test fun pbkdf2MatchesPublishedVectors() {
        // Опубликованные тест-векторы PBKDF2-HMAC-SHA256 (P="password", S="salt").
        // Совпадение доказывает, что деривация детерминирована и совместима между устройствами.
        val p = "password".toByteArray()
        val s = "salt".toByteArray()
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(BackupCrypto.pbkdf2(p, s, 1, 32)),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(BackupCrypto.pbkdf2(p, s, 2, 32)),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(BackupCrypto.pbkdf2(p, s, 4096, 32)),
        )
    }
}
