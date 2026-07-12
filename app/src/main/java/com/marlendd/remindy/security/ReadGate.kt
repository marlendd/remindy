package com.marlendd.remindy.security

/**
 * Флаг «чтение разблокировано» на время одного запуска процесса (ТЗ/этап 5:
 * биометрия или app-PIN – один раз за запуск). `@Volatile` объект живёт, пока жив
 * процесс; холодный старт сбрасывает флаг в false автоматически. Запись не гейтится.
 */
object ReadGate {

    @Volatile
    var unlocked: Boolean = false
        private set

    fun markUnlocked() {
        unlocked = true
    }
}
