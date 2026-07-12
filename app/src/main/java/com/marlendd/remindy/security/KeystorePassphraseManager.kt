package com.marlendd.remindy.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Оборачивает случайный пароль базы SQLCipher AES-256-GCM ключом из Android Keystore.
 *
 * Ключ-обёртка НЕ требует аутентификации пользователя (`setUserAuthenticationRequired(false)`).
 * Это сознательное решение (см. docs/privacy.md): база открывается молча, чтобы запись
 * оставалась «в одно касание». По javadoc AOSP автоматическая инвалидация ключа при
 * смене PIN/отпечатка/блокировки относится ТОЛЬКО к ключам с auth-required, поэтому наш
 * ключ переживает эти события – данные не теряются при смене отцом кода телефона.
 *
 * Все методы блокирующие (Keystore + файл) – вызывать с IO-потока.
 */
class KeystorePassphraseManager(context: Context) {

    private val filesDir = context.applicationContext.filesDir
    private val wrappedFile: File get() = File(filesDir, WRAPPED_FILE_NAME)

    /** Пароль базы: создаёт при первом запуске либо разворачивает существующий. */
    @Throws(PassphraseUnavailableException::class, IOException::class)
    fun getOrCreateDatabasePassphrase(): ByteArray {
        val key = getOrCreateWrappingKey()
        return if (wrappedFile.exists()) unwrapExisting(key) else createAndPersistNewPassphrase(key)
    }

    // --- Ключ-обёртка в Keystore ------------------------------------------------

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = try {
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: UnrecoverableKeyException) {
            // Запись ключа повреждена – пароль базы не восстановить, НЕ перегенерируем молча
            throw PassphraseUnavailableException("Ключ Keystore не читается (повреждён)", e)
        }
        if (existing != null) return existing
        // Ключа нет. Если обёртка пароля УЖЕ есть – ключ пропал (factory reset и т.п.):
        // генерация нового молча сделала бы обёртку нечитаемой. Явно сигналим потерю.
        if (wrappedFile.exists()) {
            throw PassphraseUnavailableException("Ключ Keystore пропал, а обёртка пароля осталась")
        }
        return generateWrappingKey() // первый запуск: ни ключа, ни обёртки
    }

    private fun generateWrappingKey(): SecretKey =
        try {
            generateWrappingKeyInternal(useStrongBox = true)
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException наследует ProviderException; ловим родителя, чтобы
            // не тянуть API-28-класс в catch на API 26-27, и заодно страхуемся от сбоев StrongBox
            generateWrappingKeyInternal(useStrongBox = false)
        }

    private fun generateWrappingKeyInternal(useStrongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    // --- Wrap / Unwrap ----------------------------------------------------------

    private fun createAndPersistNewPassphrase(key: SecretKey): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_LEN).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key) // провайдер сам генерирует 12-байтовый IV
        val iv = cipher.iv
        check(iv.size == GCM_IV_LEN) { "Неожиданная длина IV: ${iv.size}" }
        val ciphertext = cipher.doFinal(passphrase)

        // Файл пишем ДО создания базы этим паролем (ordering в RemindyDatabase.build),
        // поэтому обрыв процесса не оставит базу без ключа.
        atomicWrite(wrappedFile, iv + ciphertext)
        return passphrase
    }

    private fun unwrapExisting(key: SecretKey): ByteArray {
        val blob = wrappedFile.readBytes()
        if (blob.size <= GCM_IV_LEN) {
            throw PassphraseUnavailableException("Файл-обёртка повреждён (${blob.size} байт)")
        }
        val iv = blob.copyOfRange(0, GCM_IV_LEN)
        val ciphertext = blob.copyOfRange(GCM_IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Для ключа без auth-required в норме не должно случаться. НЕ перегенерируем
            // ключ молча: старый шифротекст станет нечитаемым → потеря доступа к базе.
            throw PassphraseUnavailableException("Ключ Keystore инвалидирован", e)
        } catch (e: GeneralSecurityException) {
            // AEADBadTagException/BadPaddingException: обёртка не расшифровалась
            // (порча файла или несовпадение ключа) – тоже потеря пароля, а не краш.
            throw PassphraseUnavailableException("Обёртка пароля не расшифровалась", e)
        }
    }

    private fun atomicWrite(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Не удалось атомарно записать $target")
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "remindy_db_passphrase_wrap_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12   // AOSP AndroidKeyStore: IV_LENGTH_BYTES
        private const val GCM_TAG_BITS = 128 // AOSP AndroidKeyStore: DEFAULT_TAG_LENGTH_BITS
        private const val PASSPHRASE_LEN = 32
        private const val WRAPPED_FILE_NAME = "db_passphrase.bin"
    }
}

/** Пароль базы недоступен (ключ Keystore потерян/инвалидирован/повреждён). */
class PassphraseUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
