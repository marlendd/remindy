package com.marlendd.remindy.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Логика кода приложения на JVM: конструктор AppPin(File, clock) существует ровно
 * для этого – Android Context не нужен, часы подконтрольны тесту.
 */
class AppPinTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L

    private fun pin() = AppPin(tmp.root, clock = { now })

    @Test fun setAndVerifyOk() {
        val p = pin()
        assertFalse(p.isSet())
        p.setPin("1234".toCharArray())
        assertTrue(p.isSet())
        assertEquals(AppPin.Result.Ok, p.verify("1234".toCharArray()))
    }

    @Test fun wrongPinCountsDownAttempts() {
        val p = pin()
        p.setPin("1234".toCharArray())
        assertEquals(AppPin.Result.Wrong(4), p.verify("0000".toCharArray()))
        assertEquals(AppPin.Result.Wrong(3), p.verify("9999".toCharArray()))
    }

    @Test fun locksAfterMaxFailsEvenForCorrectPin() {
        val p = pin()
        p.setPin("1234".toCharArray())
        repeat(4) { p.verify("0000".toCharArray()) }
        assertEquals(AppPin.Result.Locked(30_000L), p.verify("0000".toCharArray()))
        // Во время лока не пускаем даже верный код и репортим остаток
        assertTrue(p.verify("1234".toCharArray()) is AppPin.Result.Locked)
        assertEquals(30_000L, p.lockRemainingMs())
    }

    @Test fun lockExpiresAndCorrectPinResetsCounter() {
        val p = pin()
        p.setPin("1234".toCharArray())
        repeat(5) { p.verify("0000".toCharArray()) }
        now += 30_001 // базовый лок (30 с) истёк
        assertEquals(0L, p.lockRemainingMs())
        assertEquals(AppPin.Result.Ok, p.verify("1234".toCharArray()))
        // Успех очистил счётчик: следующая ошибка снова даёт 4 попытки
        assertEquals(AppPin.Result.Wrong(4), p.verify("0000".toCharArray()))
    }

    @Test fun lockEscalatesAfterRepeatedFailures() {
        val p = pin()
        p.setPin("1234".toCharArray())
        repeat(5) { p.verify("0000".toCharArray()) }
        now += 30_001
        // Первая же ошибка после лока – новый лок вдвое длиннее
        assertEquals(AppPin.Result.Locked(60_000L), p.verify("0000".toCharArray()))
    }

    @Test fun stateSurvivesNewInstance() {
        pin().setPin("5678".toCharArray())
        val fresh = pin()
        assertTrue(fresh.isSet())
        assertEquals(AppPin.Result.Ok, fresh.verify("5678".toCharArray()))
    }

    @Test fun corruptHashFileMeansNotSet() {
        // Усечённый файл = «код не задан» (экран предложит установить заново), не краш
        File(tmp.root, "app_pin.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(pin().isSet())
    }

    @Test fun clockMovedBackDoesNotExtendLockBeyondCap() {
        val p = pin()
        p.setPin("1234".toCharArray())
        repeat(5) { p.verify("0000".toCharArray()) }
        now -= 86_400_000 // часы прыгнули на сутки назад
        assertEquals(3_600_000L, p.lockRemainingMs()) // кап, а не сутки + 30 с
    }
}
