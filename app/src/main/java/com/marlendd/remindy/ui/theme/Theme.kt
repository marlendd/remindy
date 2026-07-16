package com.marlendd.remindy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Тема приложения для Compose-контента (Фаза 4 UX). Оконная тема Activity остаётся
 * `Theme.AppCompat.DayNight.NoActionBar` (совместима, BiometricPrompt требует AppCompat);
 * здесь – тёплая спокойная палитра Material 3, светлая/тёмная по системе.
 *
 * Крупные шрифты и большие кнопки задаются на местах (как в исходных XML), чтобы не
 * регрессировать UX для пожилого пользователя. Динамические (Material You) цвета НЕ берём:
 * на разных прошивках непредсказуемый контраст – держим стабильную тёплую палитру.
 */

private val LightColors = lightColorScheme(
    primary = WarmPrimary,
    onPrimary = WarmOnPrimary,
    primaryContainer = WarmPrimaryContainer,
    onPrimaryContainer = WarmOnPrimaryContainer,
    secondary = WarmSecondary,
    onSecondary = WarmOnSecondary,
    secondaryContainer = WarmSecondaryContainer,
    onSecondaryContainer = WarmOnSecondaryContainer,
    background = WarmBackground,
    onBackground = WarmOnBackground,
    surface = WarmSurface,
    onSurface = WarmOnSurface,
    surfaceVariant = WarmSurfaceVariant,
    onSurfaceVariant = WarmOnSurfaceVariant,
    outline = WarmOutline,
    outlineVariant = WarmOutlineVariant,
    error = WarmError,
    onError = WarmOnError,
    errorContainer = WarmErrorContainer,
    onErrorContainer = WarmOnErrorContainer,
)

private val DarkColors = darkColorScheme(
    primary = WarmPrimaryDark,
    onPrimary = WarmOnPrimaryDark,
    primaryContainer = WarmPrimaryContainerDark,
    onPrimaryContainer = WarmOnPrimaryContainerDark,
    secondary = WarmSecondaryDark,
    onSecondary = WarmOnSecondaryDark,
    secondaryContainer = WarmSecondaryContainerDark,
    onSecondaryContainer = WarmOnSecondaryContainerDark,
    background = WarmBackgroundDark,
    onBackground = WarmOnBackgroundDark,
    surface = WarmSurfaceDark,
    onSurface = WarmOnSurfaceDark,
    surfaceVariant = WarmSurfaceVariantDark,
    onSurfaceVariant = WarmOnSurfaceVariantDark,
    outline = WarmOutlineDark,
    outlineVariant = WarmOutlineVariantDark,
    error = WarmErrorDark,
    onError = WarmOnErrorDark,
    errorContainer = WarmErrorContainerDark,
    onErrorContainer = WarmOnErrorContainerDark,
)

@Composable
fun RemindyTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
