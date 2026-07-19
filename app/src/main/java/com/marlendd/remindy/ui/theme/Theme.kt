package com.marlendd.remindy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.marlendd.remindy.ui.UiScale

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
    // Масштаб доступности: множим плотность → зумим весь UI (dp и sp), поверх системного
    // fontScale. UiScale.factor наблюдаемо → смена размера мгновенна на всех экранах.
    val base = LocalDensity.current
    val scaled = Density(base.density * UiScale.factor, base.fontScale)
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(colorScheme = colorScheme) {
            // Surface задаёт фон И LocalContentColor = onBackground, иначе Text без явного
            // цвета берёт дефолтный чёрный (в тёмной теме – чёрный на чёрном).
            Surface(
                color = colorScheme.background,
                contentColor = colorScheme.onBackground,
                content = content,
            )
        }
    }
}
