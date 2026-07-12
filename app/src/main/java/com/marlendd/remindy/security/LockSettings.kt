package com.marlendd.remindy.security

import android.content.Context

/**
 * Пользовательские настройки замка чтения (ТЗ F4: замок опционален).
 *
 * Персистентно между запусками (в отличие от сессионного [ReadGate]). Хранит:
 *  - `lockEnabled` – спрашивать ли код/биометрию при открытии списка и поиска.
 *    **По умолчанию включён** (решение пользователя): приватность важнее удобства.
 *  - `biometricEnabled` – предлагать ли вход по отпечатку (если оборудование есть).
 *
 * Это не секреты, а лишь «спрашивать ли» – обычный SharedPreferences, без шифрования.
 * Сам код (его PBKDF2-хеш) по-прежнему в [AppPin]; выключение замка код НЕ удаляет,
 * чтобы повторное включение не требовало заново придумывать код.
 */
object LockSettings {

    private const val PREFS = "lock_settings"
    private const val KEY_LOCK_ENABLED = "lock_enabled"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCK_ENABLED, true) // замок вкл по умолчанию

    fun setLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
    }

    fun isBiometricEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, true) // отпечаток вкл по умолчанию

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }
}
