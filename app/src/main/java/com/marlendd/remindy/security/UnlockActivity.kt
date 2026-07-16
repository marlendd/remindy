package com.marlendd.remindy.security

import android.os.Bundle
import android.os.CountDownTimer
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marlendd.remindy.ui.IconLabel
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.R
import com.marlendd.remindy.ui.theme.RemindyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Замок чтения (этап 5, Фаза 4: Compose). Вход по биометрии ИЛИ отдельному коду приложения
 * (не системному). Успех → [ReadGate.markUnlocked] + RESULT_OK; отказ/назад → RESULT_CANCELED.
 * Первый запуск (или [EXTRA_FORCE_SETUP] из настроек) – режим установки/смены кода. Биометрия –
 * только `BIOMETRIC_STRONG`, БЕЗ device credential, иначе телефонный PIN открывал бы приложение.
 */
class UnlockActivity : AppCompatActivity() {

    private lateinit var appPin: AppPin

    private val entered = StringBuilder()
    private var setupMode = false
    private var forceSetup = false
    private var setupFirst: String? = null
    private var lockTimer: CountDownTimer? = null

    // UI-состояние
    private var titleText by mutableStateOf("")
    private var dotsCount by mutableIntStateOf(0)
    private var errorText by mutableStateOf("")
    private var showBiometric by mutableStateOf(false)
    private var keypadEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appPin = AppPin(this)
        forceSetup = intent.getBooleanExtra(EXTRA_FORCE_SETUP, false)

        setContent { RemindyTheme { UnlockScreen() } }

        // Уже разблокировано в этой сессии – ничего не спрашиваем (кроме принудительной
        // установки/смены кода из настроек, где мы намеренно хотим задать новый код).
        if (ReadGate.unlocked && !forceSetup) {
            grant()
            return
        }

        lifecycleScope.launch {
            setupMode = forceSetup || !withContext(Dispatchers.IO) { appPin.isSet() }
            titleText = getString(if (setupMode) R.string.unlock_create else R.string.unlock_enter)
            if (!setupMode && biometricEnabledAndAvailable()) {
                showBiometric = true
                showBiometricPrompt()
            }
        }
    }

    // --- UI -------------------------------------------------------------------

    @Composable
    private fun UnlockScreen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                titleText,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            Spacer(Modifier.size(28.dp))
            // Точки-индикатор: по кружку на введённую цифру (длина кода 4–8)
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.heightIn(min = 20.dp),
            ) {
                repeat(dotsCount) {
                    Box(
                        Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Text(
                errorText,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(top = 16.dp),
            )

            Spacer(Modifier.weight(1f))

            // Компактный круглый пад по центру снизу (не растянут на весь экран).
            // alpha гасит пад во время проверки/блокировки (keypadEnabled=false).
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(if (keypadEnabled) 1f else 0.45f),
            ) {
                KeypadRow(1, 2, 3)
                Spacer(Modifier.size(16.dp))
                KeypadRow(4, 5, 6)
                Spacer(Modifier.size(16.dp))
                KeypadRow(7, 8, 9)
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BackspaceKey()
                    DigitKey(0)
                    OkKey()
                }
            }

            Spacer(Modifier.weight(1f))

            if (showBiometric) {
                OutlinedButton(
                    onClick = { showBiometricPrompt() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    IconLabel(R.drawable.ic_fingerprint, stringResource(R.string.btn_biometric), 20.sp, iconSize = 22.dp)
                }
            }
        }
    }

    @Composable
    private fun KeypadRow(a: Int, b: Int, c: Int) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DigitKey(a)
            DigitKey(b)
            DigitKey(c)
        }
    }

    private val KEY_SIZE = 72.dp

    @Composable
    private fun DigitKey(n: Int) {
        Box(
            Modifier
                .size(KEY_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(enabled = keypadEnabled) { onDigit(n) },
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }

    @Composable
    private fun OkKey() {
        Box(
            Modifier
                .size(KEY_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(enabled = keypadEnabled) { onSubmit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.btn_ok),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
    }

    @Composable
    private fun BackspaceKey() {
        Box(
            Modifier
                .size(KEY_SIZE)
                .clip(CircleShape)
                .clickable(enabled = keypadEnabled) { onBackspace() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_backspace),
                contentDescription = stringResource(R.string.btn_backspace),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }

    // --- Ввод кода ------------------------------------------------------------

    private fun onDigit(digit: Int) {
        clearError()
        if (entered.length >= AppPin.MAX_LEN) return
        entered.append(digit)
        dotsCount = entered.length
    }

    private fun onBackspace() {
        clearError()
        if (entered.isNotEmpty()) {
            entered.deleteCharAt(entered.length - 1)
            dotsCount = entered.length
        }
    }

    private fun onSubmit() {
        val pin = entered.toString()
        if (pin.length < AppPin.MIN_LEN) {
            showError(getString(R.string.unlock_too_short))
            return
        }
        if (setupMode) handleSetup(pin) else handleEntry(pin)
    }

    private fun handleSetup(pin: String) {
        val first = setupFirst
        if (first == null) {
            setupFirst = pin
            resetInput()
            titleText = getString(R.string.unlock_repeat)
        } else if (pin == first) {
            keypadEnabled = false // не даём повторный тап во время записи
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { appPin.setPin(pin.toCharArray()) }
                } catch (e: Exception) {
                    keypadEnabled = true
                    setupFirst = null
                    resetInput()
                    titleText = getString(R.string.unlock_create)
                    showError(getString(R.string.db_error))
                    return@launch
                }
                grant()
            }
        } else {
            setupFirst = null
            resetInput()
            titleText = getString(R.string.unlock_create)
            showError(getString(R.string.unlock_mismatch))
        }
    }

    private fun handleEntry(pin: String) {
        keypadEnabled = false // гасим клавиатуру на время проверки: без гонки двойного тапа
        lifecycleScope.launch {
            val res = try {
                withContext(Dispatchers.IO) { appPin.verify(pin.toCharArray()) }
            } catch (e: Exception) {
                keypadEnabled = true
                resetInput()
                showError(getString(R.string.db_error))
                return@launch
            }
            when (res) {
                AppPin.Result.Ok -> grant()
                is AppPin.Result.Wrong -> {
                    keypadEnabled = true
                    resetInput()
                    showError(getString(R.string.unlock_wrong, res.attemptsLeft))
                }
                is AppPin.Result.Locked -> {
                    resetInput()
                    startLockCountdown(res.remainingMs) // сама держит клавиатуру выключенной
                }
            }
        }
    }

    // --- Биометрия ------------------------------------------------------------

    private fun biometricEnabledAndAvailable(): Boolean =
        LockSettings.isBiometricEnabled(this) && biometricAvailable()

    private fun biometricAvailable(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun showBiometricPrompt() {
        if (!biometricEnabledAndAvailable()) return
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    grant()
                }
                // Ошибка/отмена/«Ввести код» → остаёмся на клавиатуре кода, не пускаем и не выходим
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = Unit
                override fun onAuthenticationFailed() = Unit
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_biometric_title))
            .setSubtitle(getString(R.string.unlock_biometric_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.unlock_use_pin))
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    // --- Прочее ---------------------------------------------------------------

    private fun grant() {
        ReadGate.markUnlocked()
        setResult(RESULT_OK)
        finish()
    }

    private fun resetInput() {
        entered.setLength(0)
        dotsCount = 0
    }

    private fun showError(text: String) {
        errorText = text
    }

    private fun clearError() {
        if (errorText.isNotEmpty()) errorText = ""
    }

    private fun startLockCountdown(remainingMs: Long) {
        keypadEnabled = false
        lockTimer?.cancel()
        lockTimer = object : CountDownTimer(remainingMs, 1000) {
            override fun onTick(msLeft: Long) {
                showError(getString(R.string.unlock_locked, (msLeft / 1000) + 1))
            }
            override fun onFinish() {
                clearError()
                keypadEnabled = true
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        lockTimer?.cancel()
    }

    companion object {
        /** Принудительный режим установки/смены кода (из настроек), даже если код уже задан. */
        const val EXTRA_FORCE_SETUP = "extra_force_setup"
    }
}
