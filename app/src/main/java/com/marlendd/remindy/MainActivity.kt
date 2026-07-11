package com.marlendd.remindy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.list.ListActivity
import com.marlendd.remindy.parse.UtteranceParser
import com.marlendd.remindy.record.ConfirmationActivity
import com.marlendd.remindy.search.SearchActivity
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
 * Главный экран. «Сказать» пишет ОДНУ фразу: пользователь говорит, по паузе Vosk
 * выдаёт финальный результат (onResult) – фраза разбирается на предмет/место и
 * открывается экран подтверждения. Тап «Стоп» финализирует вручную.
 */
class MainActivity : AppCompatActivity(), RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var capturing = false

    private lateinit var rootLayout: View
    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var toggleButton: Button
    private lateinit var findButton: Button
    private lateinit var listButton: Button

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) statusText.text = getString(R.string.status_permission_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        statusText = findViewById(R.id.statusText)
        partialText = findViewById(R.id.partialText)
        toggleButton = findViewById(R.id.toggleButton)
        findButton = findViewById(R.id.findButton)
        listButton = findViewById(R.id.listButton)

        applyWindowInsets()

        toggleButton.isEnabled = false
        toggleButton.setOnClickListener { toggleRecognition() }
        findButton.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        listButton.setOnClickListener {
            startActivity(Intent(this, ListActivity::class.java))
        }

        LibVosk.setLogLevel(LogLevel.INFO)
        if (!hasMicPermission()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        initModel()
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

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun initModel() {
        statusText.setText(R.string.status_loading_model)
        toggleButton.isEnabled = false
        lifecycleScope.launch {
            try {
                model = VoskModelHolder.get(this@MainActivity) // общая на процесс модель
                toggleButton.isEnabled = true
                statusText.setText(R.string.status_ready)
            } catch (e: Exception) {
                toggleButton.isEnabled = true // тап повторит загрузку
                statusText.text = getString(R.string.status_model_error, e.message)
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
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val loadedModel = model ?: return
        try {
            val rec = Recognizer(loadedModel, SAMPLE_RATE)
            recognizer = rec
            speechService = SpeechService(rec, SAMPLE_RATE).also { it.startListening(this) }
            capturing = true
            partialText.text = ""
            toggleButton.setText(R.string.btn_stop)
            listButton.isEnabled = false // не уходим в список посреди записи
            statusText.setText(R.string.status_listening)
        } catch (e: IOException) {
            releaseSpeechService()
            statusText.text = getString(R.string.status_error, e.message)
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
        toggleButton.setText(R.string.btn_speak)
        listButton.isEnabled = true
        partialText.text = ""
        statusText.setText(R.string.status_ready)
    }

    // --- RecognitionListener: колбэки в main thread -----------------------------

    override fun onPartialResult(hypothesis: String?) {
        partialText.text = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty()
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
        statusText.text = getString(R.string.status_error, exception?.message)
    }

    override fun onTimeout() {
        finishCapture("")
    }

    private fun applyWindowInsets() {
        val base = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics,
        ).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(base + bars.left, base, base + bars.right, base + bars.bottom)
            insets
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseSpeechService()
        model = null // модель общая (VoskModelHolder), здесь не закрываем
    }
}
