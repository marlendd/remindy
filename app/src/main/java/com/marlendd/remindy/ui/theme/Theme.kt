package com.marlendd.remindy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Тема приложения для Compose-контента (Фаза 4). Оконная тема Activity остаётся
 * `Theme.AppCompat.DayNight.NoActionBar` (совместима, BiometricPrompt требует AppCompat);
 * здесь – только Material 3 цвета контента, светлые/тёмные по системе.
 *
 * Крупные шрифты и большие кнопки задаются на местах (как в исходных XML), чтобы не
 * регрессировать UX для пожилого пользователя. Динамические (Material You) цвета НЕ берём:
 * на разных прошивках непредсказуемый контраст – держим стабильную дефолтную палитру M3.
 */
@Composable
fun RemindyTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}
