package com.marlendd.remindy.security

import android.os.Bundle
import android.os.CountDownTimer
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                titleText,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Text(
                "•".repeat(dotsCount),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 16.dp),
            )
            Text(
                errorText,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp),
            )

            Column(Modifier.weight(1f).fillMaxWidth()) {
                KeypadRow("1", "2", "3")
                KeypadRow("4", "5", "6")
                KeypadRow("7", "8", "9")
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    KeypadKey(stringResource(R.string.btn_backspace), Modifier.weight(1f)) { onBackspace() }
                    KeypadKey("0", Modifier.weight(1f)) { onDigit(0) }
                    KeypadKey(stringResource(R.string.btn_ok), Modifier.weight(1f)) { onSubmit() }
                }
            }

            if (showBiometric) {
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { showBiometricPrompt() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.btn_biometric), fontSize = 20.sp)
                }
            }
        }
    }

    // Extension на ColumnScope, чтобы Modifier.weight(1f) у Row резолвился (равные по высоте ряды)
    @Composable
    private fun ColumnScope.KeypadRow(a: String, b: String, c: String) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            KeypadKey(a, Modifier.weight(1f)) { onDigit(a.toInt()) }
            KeypadKey(b, Modifier.weight(1f)) { onDigit(b.toInt()) }
            KeypadKey(c, Modifier.weight(1f)) { onDigit(c.toInt()) }
        }
    }

    @Composable
    private fun KeypadKey(label: String, modifier: Modifier, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            enabled = keypadEnabled,
            modifier = modifier.fillMaxHeight().heightIn(min = 56.dp).padding(4.dp),
        ) {
            Text(label, fontSize = 26.sp)
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
