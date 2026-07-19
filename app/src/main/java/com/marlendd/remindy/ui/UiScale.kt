package com.marlendd.remindy.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * Масштаб интерфейса (доступность: крупнее для слабого зрения, ТЗ – по просьбе).
 * Множитель плотности зумит ВЕСЬ UI равномерно – текст, кнопки, иконки, отступы, тап-цели.
 *
 * Персистентно (SharedPreferences) и наблюдаемо: [factor] – snapshot-состояние процесса,
 * поэтому смена в настройках мгновенно перерисовывает все живые экраны (тема читает его).
 * Значение грузим синхронно в onCreate каждой Activity (до setContent) – без мигания.
 */
object UiScale {
    private const val PREFS = "ui_scale"
    private const val KEY = "factor"

    const val NORMAL = 1.0f
    const val LARGE = 1.25f
    const val XLARGE = 1.5f

    private var loaded = false

    var factor by mutableFloatStateOf(NORMAL)
        private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        factor = prefs(context).getFloat(KEY, NORMAL)
        loaded = true
    }

    fun set(context: Context, value: Float) {
        factor = value
        prefs(context).edit { putFloat(KEY, value) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
