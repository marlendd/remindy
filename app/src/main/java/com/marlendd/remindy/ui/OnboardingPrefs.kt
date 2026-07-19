package com.marlendd.remindy.ui

import android.content.Context
import androidx.core.content.edit

/**
 * Флаг «онбординг уже показан». Не секрет – обычные SharedPreferences. После чистой
 * установки данные приложения стёрты, поэтому первый запуск снова покажет подсказку
 * (удобно при передаче телефона). Из Настроек онбординг открывается всегда, минуя флаг.
 */
object OnboardingPrefs {

    private const val PREFS = "onboarding"
    private const val KEY_SEEN = "seen"

    fun isSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEEN, false)

    fun setSeen(context: Context) {
        prefs(context).edit { putBoolean(KEY_SEEN, true) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
