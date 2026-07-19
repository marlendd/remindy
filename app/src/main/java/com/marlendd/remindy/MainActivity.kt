package com.marlendd.remindy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.list.ListActivity
import com.marlendd.remindy.parse.UtteranceParser
import com.marlendd.remindy.record.ConfirmationActivity
import com.marlendd.remindy.search.SearchActivity
import com.marlendd.remindy.security.SettingsActivity
import com.marlendd.remindy.security.protectFromRecents
import com.marlendd.remindy.ui.IconLabel
import com.marlendd.remindy.ui.OnboardingPrefs
import com.marlendd.remindy.ui.UiScale
import com.marlendd.remindy.ui.theme.RemindyTheme
import com.marlendd.remindy.voice.VoskModelHolder
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException

// Vosk работает только с 16 кГц моно
private const val SAMPLE_RATE = 16000.0f

/**
 * Главный экран (Фаза 4: Compose, порт с View 1:1 + UX-улучшения). «Сказать» пишет ОДНУ
 * фразу: пользователь говорит, по паузе Vosk выдаёт финальный результат (onResult) – фраза
 * разбирается на предмет/место и открывается экран подтверждения. Тап «Стоп» финализирует
 * вручную. Логика Vosk/распознавания/разрешений осталась в Activity; UI – Compose-состояние.
 */
class MainActivity : AppCompatActivity(), RecognitionListener {

    private enum class MicDialog { NONE, RATIONALE, OPEN_SETTINGS }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null

    // UI-состояние (Compose наблюдает эти поля)
    private var status by mutableStateOf("")
    private var partial by mutableStateOf("")
    private var speakEnabled by mutableStateOf(false)
    private var listEnabled by mutableStateOf(true)
    private var modelLoading by mutableStateOf(true)
    private var capturing by mutableStateOf(false)
    private var micDialog by mutableStateOf(MicDialog.NONE)

    // Диалоги об отказе показываем только для запроса, начатого тапом «Сказать»:
    // авто-запрос в onCreate при «навсегда отклонён» возвращает отказ мгновенно,
    // и без этого флага пользователь получал бы диалог при КАЖДОМ запуске
    private var micRequestedByTap = false

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val fromTap = micRequestedByTap
            micRequestedByTap = false
            if (!granted && fromTap) handleMicDenied()
        }

    // Первый запуск: сперва онбординг, и только по возврату просим микрофон (иначе системный
    // диалог разрешения выскочил бы поверх подсказки). Заодно помечаем онбординг показанным.
    private val onboardingLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            OnboardingPrefs.setSeen(this)
            requestMicIfNeeded()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectFromRecents() // при записи на экране живой текст фразы – не в снимок «недавних»
        enableEdgeToEdge()
        UiScale.ensureLoaded(this)
        setContent { RemindyTheme { MainScreen() } }

        LibVosk.setLogLevel(LogLevel.INFO)
        // Первый запуск → онбординг (микрофон отложен до его закрытия); иначе сразу микрофон.
        if (OnboardingPrefs.isSeen(this)) {
            requestMicIfNeeded()
        } else {
            onboardingLauncher.launch(Intent(this, OnboardingActivity::class.java))
        }
        initModel()
    }

    private fun requestMicIfNeeded() {
        if (!hasMicPermission()) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onResume() {
        super.onResume()
        // Вернулись с экрана подтверждения/списка – привести к состоянию покоя
        if (!capturing && model != null) {
            resetIdleUi()
        }
    }

    override fun onStop() {
        super.onStop()
        // Ушли с экрана во время записи – глушим микрофон (иначе он писал бы в фоне,
        // а поздний колбэк дёрнул бы startActivity из stopped-состояния)
        if (capturing) {
            capturing = false
            releaseSpeechService()
            resetIdleUi()
        }
    }

    // --- UI -------------------------------------------------------------------

    @Composable
    private fun MainScreen() {
        Scaffold { inner ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                // Статус-строка только для реальных состояний (загрузка/«Слушаю…»/ошибка);
                // в покое пусто – никакого «Готово», подсказка живёт по центру.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 26.dp),
                ) {
                    Text(
                        status,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (modelLoading) {
                        CircularProgressIndicator(
                            Modifier.padding(start = 12.dp).size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                }

                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (partial.isNotBlank()) {
                        // Идёт распознавание – показываем живой текст
                        Text(
                            partial,
                            fontSize = 24.sp,
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        // Покой – иконка микрофона и мягкая подсказка вместо статусной строки
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_mic),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                            Spacer(Modifier.size(16.dp))
                            Text(
                                stringResource(R.string.main_hint),
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Button(
                    onClick = { toggleRecognition() },
                    enabled = speakEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                ) {
                    IconLabel(
                        R.drawable.ic_mic,
                        stringResource(if (capturing) R.string.btn_stop else R.string.btn_speak),
                        26.sp,
                    )
                }
                Spacer(Modifier.size(12.dp))
                FilledTonalButton(
                    onClick = { startActivity(Intent(this@MainActivity, SearchActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                ) {
                    IconLabel(R.drawable.ic_search, stringResource(R.string.btn_find), 24.sp)
                }
                Spacer(Modifier.size(12.dp))
                FilledTonalButton(
                    onClick = { startActivity(Intent(this@MainActivity, ListActivity::class.java)) },
                    enabled = listEnabled,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    IconLabel(R.drawable.ic_list, stringResource(R.string.btn_list), 21.sp, iconSize = 24.dp)
                }
                Spacer(Modifier.size(8.dp))
                TextButton(
                    onClick = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    IconLabel(R.drawable.ic_settings, stringResource(R.string.btn_settings), 17.sp, iconSize = 20.dp)
                }
            }
        }
        MicPermissionDialog()
    }

    @Composable
    private fun MicPermissionDialog() {
        when (micDialog) {
            MicDialog.NONE -> Unit
            MicDialog.RATIONALE -> AlertDialog(
                onDismissRequest = { micDialog = MicDialog.NONE },
                title = { Text(stringResource(R.string.mic_rationale_title)) },
                text = { Text(stringResource(R.string.mic_rationale)) },
                confirmButton = {
                    TextButton(onClick = {
                        micDialog = MicDialog.NONE
                        micRequestedByTap = true
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text(stringResource(R.string.btn_allow)) }
                },
                dismissButton = {
                    TextButton(onClick = { micDialog = MicDialog.NONE }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                },
            )
            MicDialog.OPEN_SETTINGS -> AlertDialog(
                onDismissRequest = { micDialog = MicDialog.NONE },
                title = { Text(stringResource(R.string.mic_denied_title)) },
                text = { Text(stringResource(R.string.mic_denied_settings)) },
                confirmButton = {
                    TextButton(onClick = {
                        micDialog = MicDialog.NONE
                        openAppSettings()
                    }) { Text(stringResource(R.string.btn_open_settings)) }
                },
                dismissButton = {
                    TextButton(onClick = { micDialog = MicDialog.NONE }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                },
            )
        }
    }

    // --- Разрешение микрофона --------------------------------------------------

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun handleMicDenied() {
        // rationale=true → отклонили один раз, объясняем и предлагаем повтор;
        // rationale=false → отклонили «навсегда» (или системно недоступно) → в настройки
        micDialog = if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            MicDialog.RATIONALE
        } else {
            MicDialog.OPEN_SETTINGS
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    // --- Vosk ------------------------------------------------------------------

    private fun initModel() {
        status = getString(R.string.status_loading_model)
        speakEnabled = false
        modelLoading = true
        lifecycleScope.launch {
            try {
                model = VoskModelHolder.get(this@MainActivity) // общая на процесс модель
                speakEnabled = true
                status = "" // покой: подсказка живёт по центру, статус-строка пустая
            } catch (e: Exception) {
                speakEnabled = true // тап повторит загрузку
                status = getString(R.string.status_model_error, e.message)
            } finally {
                modelLoading = false
            }
        }
    }

    private fun toggleRecognition() {
        if (model == null) {
            initModel()
            return
        }
        if (capturing) {
            speechService?.stop() // финальный результат придёт в onFinalResult
        } else {
            startRecognition()
        }
    }

    private fun startRecognition() {
        if (!hasMicPermission()) {
            // Отклоняли один раз → сперва объясняем, зачем микрофон; иначе сразу запрос
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                micDialog = MicDialog.RATIONALE
            } else {
                micRequestedByTap = true
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        val loadedModel = model ?: return
        try {
            val rec = Recognizer(loadedModel, SAMPLE_RATE)
            recognizer = rec
            speechService = SpeechService(rec, SAMPLE_RATE).also { it.startListening(this) }
            capturing = true
            partial = ""
            listEnabled = false // не уходим в список посреди записи
            status = getString(R.string.status_listening)
        } catch (e: IOException) {
            releaseSpeechService()
            status = getString(R.string.status_error, e.message)
        }
    }

    // Завершает захват фразы: освобождает распознаватель, разбирает текст,
    // открывает экран подтверждения. Guard capturing защищает от двойного вызова.
    private fun finishCapture(text: String) {
        if (!capturing) return
        capturing = false
        releaseSpeechService()
        resetIdleUi()

        val parsed = UtteranceParser.parse(text)
        startActivity(
            Intent(this, ConfirmationActivity::class.java)
                .putExtra(ConfirmationActivity.EXTRA_ITEM, parsed.item)
                .putExtra(ConfirmationActivity.EXTRA_LOCATION, parsed.location),
        )
    }

    private fun releaseSpeechService() {
        speechService?.let {
            it.stop()
            it.shutdown()
        }
        speechService = null
        recognizer?.close()
        recognizer = null
    }

    private fun resetIdleUi() {
        listEnabled = true
        partial = ""
        status = "" // покой: без «Готово», подсказка по центру
    }

    // --- RecognitionListener: колбэки в main thread ----------------------------

    override fun onPartialResult(hypothesis: String?) {
        partial = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty()
    }

    // Конец фразы по тишине – авто-подтверждение (одно касание на всю запись)
    override fun onResult(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
        if (text.isNotBlank()) finishCapture(text)
    }

    // Финал после ручного «Стоп» – подтверждаем даже пустой текст (ручной ввод)
    override fun onFinalResult(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
        finishCapture(text)
    }

    override fun onError(exception: Exception?) {
        capturing = false
        releaseSpeechService()
        resetIdleUi()
        status = getString(R.string.status_error, exception?.message)
    }

    override fun onTimeout() {
        finishCapture("")
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseSpeechService()
        model = null // модель общая (VoskModelHolder), здесь не закрываем
    }
}
