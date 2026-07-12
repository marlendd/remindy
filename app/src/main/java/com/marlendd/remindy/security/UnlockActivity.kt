package com.marlendd.remindy.security

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Замок чтения (этап 5). Вход по биометрии ИЛИ отдельному коду приложения (не системному).
 * Успех → [ReadGate.markUnlocked] + RESULT_OK; отказ/назад → RESULT_CANCELED (вызывающий
 * экран закрывается). Первый запуск: режим установки кода. Биометрия – только `BIOMETRIC_STRONG`,
 * БЕЗ device credential, иначе телефонный PIN открывал бы приложение – ровно та дыра, что закрываем.
 */
class UnlockActivity : AppCompatActivity() {

    private lateinit var appPin: AppPin
    private lateinit var titleView: TextView
    private lateinit var pinDisplay: TextView
    private lateinit var errorView: TextView
    private lateinit var biometricButton: Button
    private lateinit var keys: List<Button>

    private val entered = StringBuilder()
    private var setupMode = false
    private var setupFirst: String? = null
    private var lockTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)
        title = getString(R.string.title_unlock)

        appPin = AppPin(this)
        titleView = findViewById(R.id.titleUnlock)
        pinDisplay = findViewById(R.id.pinDisplay)
        errorView = findViewById(R.id.errorUnlock)
        biometricButton = findViewById(R.id.btnBiometric)

        wireKeypad()
        biometricButton.setOnClickListener { showBiometricPrompt() }

        // Уже разблокировано в этой сессии – ничего не спрашиваем
        if (ReadGate.unlocked) {
            grant()
            return
        }

        lifecycleScope.launch {
            setupMode = !withContext(Dispatchers.IO) { appPin.isSet() }
            titleView.setText(if (setupMode) R.string.unlock_create else R.string.unlock_enter)
            if (!setupMode && biometricAvailable()) {
                biometricButton.visibility = View.VISIBLE
                showBiometricPrompt()
            }
        }
    }

    private fun wireKeypad() {
        val digitIds = intArrayOf(
            R.id.btnKey0, R.id.btnKey1, R.id.btnKey2, R.id.btnKey3, R.id.btnKey4,
            R.id.btnKey5, R.id.btnKey6, R.id.btnKey7, R.id.btnKey8, R.id.btnKey9,
        )
        val digitButtons = digitIds.mapIndexed { digit, id ->
            findViewById<Button>(id).also { it.setOnClickListener { _ -> onDigit(digit) } }
        }
        val backspace: Button = findViewById(R.id.btnBackspace)
        val ok: Button = findViewById(R.id.btnOk)
        backspace.setOnClickListener { onBackspace() }
        ok.setOnClickListener { onSubmit() }
        keys = digitButtons + backspace + ok
    }

    private fun onDigit(digit: Int) {
        clearError()
        if (entered.length >= AppPin.MAX_LEN) return
        entered.append(digit)
        renderDots()
    }

    private fun onBackspace() {
        clearError()
        if (entered.isNotEmpty()) {
            entered.deleteCharAt(entered.length - 1)
            renderDots()
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
            titleView.setText(R.string.unlock_repeat)
        } else if (pin == first) {
            setKeypadEnabled(false) // не даём повторный тап во время записи
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { appPin.setPin(pin.toCharArray()) }
                } catch (e: Exception) {
                    setKeypadEnabled(true)
                    setupFirst = null
                    resetInput()
                    titleView.setText(R.string.unlock_create)
                    showError(getString(R.string.db_error))
                    return@launch
                }
                grant()
            }
        } else {
            setupFirst = null
            resetInput()
            titleView.setText(R.string.unlock_create)
            showError(getString(R.string.unlock_mismatch))
        }
    }

    private fun handleEntry(pin: String) {
        setKeypadEnabled(false) // гасим клавиатуру на время проверки: без гонки двойного тапа
        lifecycleScope.launch {
            val res = try {
                withContext(Dispatchers.IO) { appPin.verify(pin.toCharArray()) }
            } catch (e: Exception) {
                setKeypadEnabled(true)
                resetInput()
                showError(getString(R.string.db_error))
                return@launch
            }
            when (res) {
                AppPin.Result.Ok -> grant()
                is AppPin.Result.Wrong -> {
                    setKeypadEnabled(true)
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

    // --- Биометрия --------------------------------------------------------------

    private fun biometricAvailable(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun showBiometricPrompt() {
        if (!biometricAvailable()) return
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

    // --- Прочее -----------------------------------------------------------------

    private fun grant() {
        ReadGate.markUnlocked()
        setResult(RESULT_OK)
        finish()
    }

    private fun renderDots() {
        pinDisplay.text = "•".repeat(entered.length)
    }

    private fun resetInput() {
        entered.setLength(0)
        renderDots()
    }

    private fun showError(text: String) {
        errorView.text = text
    }

    private fun clearError() {
        if (errorView.text.isNotEmpty()) errorView.text = ""
    }

    private fun setKeypadEnabled(enabled: Boolean) {
        keys.forEach { it.isEnabled = enabled }
    }

    private fun startLockCountdown(remainingMs: Long) {
        setKeypadEnabled(false)
        lockTimer?.cancel()
        lockTimer = object : CountDownTimer(remainingMs, 1000) {
            override fun onTick(msLeft: Long) {
                showError(getString(R.string.unlock_locked, (msLeft / 1000) + 1))
            }
            override fun onFinish() {
                clearError()
                setKeypadEnabled(true)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        lockTimer?.cancel()
    }
}
