package com.marlendd.remindy.security

import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование файла резервной копии паролем, НЕЗАВИСИМЫМ от Keystore.
 *
 * Именно независимость даёт смысл бэкапу: ключ основной базы завёрнут в Android Keystore
 * и теряется при переустановке / factory reset / смене телефона. Пароль знает только
 * пользователь, поэтому копию можно восстановить на новом устройстве.
 *
 * Формат конверта (файл .rmdy):
 *   MAGIC "RMDY" (4)      – опознание файла до расшифровки
 *   version   (1)         – версия конверта
 *   salt      (16)        – для PBKDF2
 *   iv        (12)        – nonce для AES-GCM (новый на каждый экспорт)
 *   ciphertext (остаток)  – AES-256-GCM(payload); тег аутентификации внутри
 *
 * key = PBKDF2-HMAC-SHA256(UTF-8(password), salt, 210000, 256 бит).
 *
 * Деривацию ключа делаем сами (см. [pbkdf2]) поверх стандартного HMAC-SHA256, а пароль
 * кодируем в UTF-8 ЯВНО. Это устраняет провайдеро-зависимость кодировки char[] внутри
 * PBEKeySpec: не-ASCII пароль (кириллица) даёт один и тот же ключ на любом устройстве –
 * иначе восстановление копии на новом телефоне могло бы не сойтись именно из-за пароля.
 *
 * Чистая JVM-крипта – тестируется без Android/устройства. Пароль (CharArray) вызывающая
 * сторона зануляет сама после вызова (как в [AppPin]).
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf(
        'R'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), 'Y'.code.toByte(),
    )
    private const val VERSION: Byte = 1

    private const val SALT_LEN = 16
    private const val IV_LEN = 12          // рекомендуемая длина nonce для GCM
    private const val TAG_BITS = 128
    private const val TAG_LEN = TAG_BITS / 8
    private const val ITERATIONS = 210_000 // для локального файла-копии достаточно
    private const val KEY_LEN = 32         // 256-бит ключ AES
    private const val HMAC_ALG = "HmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val HEADER_LEN = 4 + 1 + SALT_LEN + IV_LEN

    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val keyBytes = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            val ciphertext = cipher.doFinal(payload)
            val out = ByteArrayOutputStream(HEADER_LEN + ciphertext.size)
            out.write(MAGIC)
            out.write(VERSION.toInt())
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
            return out.toByteArray()
        } finally {
            keyBytes.fill(0) // SecretKeySpec скопировал ключ внутрь – наш экземпляр зануляем
        }
    }

    /**
     * @throws BadBackupFileException если это не наш конверт (нет MAGIC / чужая версия / обрезан).
     * @throws WrongPasswordException если пароль неверный или файл повреждён (GCM-тег не сошёлся).
     */
    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        if (envelope.size < HEADER_LEN + TAG_LEN) throw BadBackupFileException()
        for (i in MAGIC.indices) {
            if (envelope[i] != MAGIC[i]) throw BadBackupFileException()
        }
        if (envelope[4] != VERSION) throw BadBackupFileException("Неподдерживаемая версия копии")

        var off = 5
        val salt = envelope.copyOfRange(off, off + SALT_LEN); off += SALT_LEN
        val iv = envelope.copyOfRange(off, off + IV_LEN); off += IV_LEN
        val ciphertext = envelope.copyOfRange(off, envelope.size)

        val keyBytes = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException(e)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val pwBytes = utf8Bytes(password)
        try {
            return pbkdf2(pwBytes, salt, ITERATIONS, KEY_LEN)
        } finally {
            pwBytes.fill(0)
        }
    }

    // Пароль → UTF-8 байты без промежуточной String (её нельзя занулить) и без зависимости
    // от того, как JCE-провайдер кодирует char[] внутри PBEKeySpec.
    private fun utf8Bytes(password: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        if (bb.hasArray()) bb.array().fill(0) // затираем временный буфер кодировщика
        return bytes
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018) поверх javax [Mac]. Реализуем сами, чтобы кодировка
     * пароля была детерминированной (UTF-8) и одинаковой на любом устройстве – провайдер
     * участвует только в самом HMAC-SHA256 (стандартный примитив). Корректность проверена
     * против опубликованных тест-векторов (см. BackupCryptoTest). `internal` – для теста.
     */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(password, HMAC_ALG))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val intBlock = ByteArray(4)
        for (i in 1..blocks) {
            intBlock[0] = (i ushr 24).toByte()
            intBlock[1] = (i ushr 16).toByte()
            intBlock[2] = (i ushr 8).toByte()
            intBlock[3] = i.toByte()
            mac.update(salt)
            var u = mac.doFinal(intBlock)   // U1 = PRF(P, S || INT(i))
            val t = u.copyOf()              // T = U1
            for (iter in 2..iterations) {
                u = mac.doFinal(u)          // Uj = PRF(P, Uj-1)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return if (out.size == dkLen) out else out.copyOf(dkLen)
    }
}

/** Файл не является резервной копией нашего формата. */
class BadBackupFileException(message: String = "Это не файл резервной копии") : Exception(message)

/** Неверный пароль или повреждённый файл (GCM-тег не прошёл проверку). */
class WrongPasswordException(cause: Throwable? = null) : Exception("Неверный пароль или повреждённый файл", cause)
