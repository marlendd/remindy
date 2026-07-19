package com.marlendd.remindy.security

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Отдельный код приложения (не системный) как запасной вход в чтение.
 *
 * Хранит соль + PBKDF2-хеш кода, а не сам код. От перебора – счётчик неудач с
 * нарастающим локом. Это UI-гейт: против рута он не спасает (там читают базу через
 * уже открытое приложение / TEE-ключ напрямую), но останавливает человека, знающего
 * лишь системный PIN телефона – ровно ту угрозу, ради которой код и вводится.
 *
 * Лок меряется настенными часами ([clock]): перевод времени вперёд снимает его
 * раньше срока. Персистентных монотонных часов на уровне приложения нет (elapsed
 * сбрасывается ребутом); принято – счётчик неудач всё равно персистентен, а PBKDF2
 * держит перебор медленным. Перевод часов назад капится (не удлиняем лок сверх MAX).
 *
 * Все методы блокирующие (файл + PBKDF2) – вызывать с IO-потока.
 * [dir] и [clock] в конструкторе – чтобы логика тестировалась на JVM без Android.
 */
class AppPin(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val hashFile: File get() = File(dir, HASH_FILE)
    private val lockFile: File get() = File(dir, LOCK_FILE)

    // Валидна только запись ровно ожидаемого размера ([saltLen][salt][hash]); пустой/усечённый
    // файл трактуем как «код не задан» – тогда экран входа предложит установить код заново,
    // а не заклинит в режиме ввода на битом файле.
    fun isSet(): Boolean = hashFile.exists() && hashFile.length() == (1 + SALT_LEN + HASH_LEN).toLong()

    /** Задаёт код. `pin` вызывающая сторона зануляет сама после вызова. */
    @Synchronized
    fun setPin(pin: CharArray) {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        // [saltLen:1][salt][hash]
        val blob = ByteArray(1 + salt.size + hash.size)
        blob[0] = salt.size.toByte()
        System.arraycopy(salt, 0, blob, 1, salt.size)
        System.arraycopy(hash, 0, blob, 1 + salt.size, hash.size)
        atomicWrite(hashFile, blob)
        hash.fill(0)
        clearLock()
    }

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val attemptsLeft: Int) : Result
        data class Locked(val remainingMs: Long) : Result
    }

    /**
     * Проверяет код. `pin` вызывающая сторона зануляет сама после вызова.
     * `@Synchronized` сериализует read-modify-write счётчика неудач – два быстрых вызова
     * не теряют инкремент (в UnlockActivity клавиатура ещё и гасится на время проверки).
     */
    @Synchronized
    fun verify(pin: CharArray): Result {
        val now = clock()
        val state = readLock()
        val lockedUntil = clampedLockedUntil(state, now)
        if (lockedUntil > now) return Result.Locked(lockedUntil - now)

        val blob = if (hashFile.exists()) hashFile.readBytes() else ByteArray(0)
        // Пустой/битый файл: не обращаемся к blob[0] до проверки размера – иначе краш
        if (blob.isEmpty()) return Result.Wrong(MAX_FAILS)
        val saltLen = blob[0].toInt() and 0xFF
        if (saltLen == 0 || blob.size != 1 + saltLen + HASH_LEN) return Result.Wrong(MAX_FAILS)
        val salt = blob.copyOfRange(1, 1 + saltLen)
        val stored = blob.copyOfRange(1 + saltLen, blob.size)

        val candidate = pbkdf2(pin, salt)
        val ok = constantTimeEquals(candidate, stored)
        candidate.fill(0)

        return if (ok) {
            clearLock()
            Result.Ok
        } else {
            val fails = state.fails + 1
            if (fails >= MAX_FAILS) {
                val lockMs = lockDurationMs(fails)
                writeLock(fails, now + lockMs)
                Result.Locked(lockMs)
            } else {
                writeLock(fails, 0L)
                Result.Wrong(MAX_FAILS - fails)
            }
        }
    }

    /** Оставшееся время активного лока (0 – не залочен). Блокирующий (файл) – IO-поток. */
    @Synchronized
    fun lockRemainingMs(): Long {
        val now = clock()
        return (clampedLockedUntil(readLock(), now) - now).coerceAtLeast(0L)
    }

    // --- Лок от перебора --------------------------------------------------------

    private data class LockState(val fails: Int, val lockedUntil: Long)

    // Часы перевели назад → «оставшееся» время раздулось бы сверх любого лока;
    // капим на MAX_LOCK_MS от текущего момента. Перевод вперёд не ловим (см. KDoc).
    private fun clampedLockedUntil(state: LockState, now: Long): Long =
        state.lockedUntil.coerceAtMost(now + MAX_LOCK_MS)

    private fun readLock(): LockState {
        if (!lockFile.exists()) return LockState(0, 0L)
        return try {
            val b = ByteBuffer.wrap(lockFile.readBytes())
            LockState(b.int, b.long)
        } catch (e: Exception) {
            LockState(0, 0L)
        }
    }

    private fun writeLock(fails: Int, lockedUntil: Long) {
        val buf = ByteBuffer.allocate(12).putInt(fails).putLong(lockedUntil).array()
        atomicWrite(lockFile, buf)
    }

    private fun clearLock() {
        lockFile.delete()
    }

    // После MAX_FAILS неудач – лок, растущий с каждой следующей ошибкой (кап на MAX_LOCK_MS)
    private fun lockDurationMs(fails: Int): Long {
        val over = fails - MAX_FAILS // 0,1,2,...
        val ms = BASE_LOCK_MS shl over.coerceAtMost(6)
        return ms.coerceAtMost(MAX_LOCK_MS)
    }

    // --- Крипто -----------------------------------------------------------------

    private fun pbkdf2(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        private const val HASH_FILE = "app_pin.bin"
        private const val LOCK_FILE = "app_pin_lock.bin"
        private const val SALT_LEN = 16
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256
        private const val HASH_LEN = KEY_BITS / 8 // длина PBKDF2-хеша в байтах (32)
        private const val PBKDF2_ALG = "PBKDF2WithHmacSHA256"

        const val MIN_LEN = 4
        const val MAX_LEN = 8
        private const val MAX_FAILS = 5
        private const val BASE_LOCK_MS = 30_000L
        private const val MAX_LOCK_MS = 3_600_000L
    }
}
