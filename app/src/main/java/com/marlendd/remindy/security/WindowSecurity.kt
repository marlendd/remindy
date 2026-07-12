package com.marlendd.remindy.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Убирает содержимое экрана из снимка «недавних» (переключатель задач), чтобы данные не
 * утекали мимо замка чтения (нашло adversarial-ревью этапа 5).
 *
 * На Android 13+ (API 33) гасим ТОЛЬКО миниатюру «недавних» через `setRecentsScreenshotEnabled` –
 * обычные скриншоты остаются рабочими для удалённой помощи (решение privacy.md #4). На старых
 * версиях такой гранулярности нет, поэтому ставим `FLAG_SECURE` (блокирует и миниатюру, и
 * скриншоты) – закрыть утечку важнее возможности скриншота на устаревшем железе.
 *
 * Ручной скриншот экрана данных сам по себе не опасен для целевой угрозы: чтобы попасть на
 * список/поиск, надо пройти гейт, который атакующего и остановит.
 */
fun Activity.protectFromRecents() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(false)
    } else {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}
