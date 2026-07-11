package com.marlendd.remindy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

// Vosk работает только с 16 кГц моно
private const val SAMPLE_RATE = 16000.0f

class MainActivity : AppCompatActivity(), RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null

    private lateinit var rootLayout: View
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var partialText: TextView
    private lateinit var resultScroll: ScrollView
    private lateinit var toggleButton: Button

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) statusText.text = getString(R.string.status_permission_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        partialText = findViewById(R.id.partialText)
        resultScroll = findViewById(R.id.resultScroll)
        toggleButton = findViewById(R.id.toggleButton)

        applyWindowInsets()

        toggleButton.setOnClickListener { toggleRecognition() }

        LibVosk.setLogLevel(LogLevel.INFO)
        if (!hasMicPermission()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        initModel()
    }

    // targetSdk 36 на Android 15+ форсит edge-to-edge; отступаем от системных баров,
    // чтобы навбар не перекрывал большую кнопку. Верх отдаём ActionBar'у AppCompat.
    private fun applyWindowInsets() {
        val base = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
        ).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(base + bars.left, base, base + bars.right, base + bars.bottom)
            insets
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun initModel() {
        statusText.setText(R.string.status_loading_model)
        toggleButton.isEnabled = false
        // Распаковывает assets/model-ru-small в files-каталог приложения (асинхронно,
        // колбэки в main thread); при совпадении uuid повторно не копирует
        StorageService.unpack(
            this, "model-ru-small", "model",
            { loadedModel ->
                model = loadedModel
                toggleButton.isEnabled = true
                statusText.setText(R.string.status_ready)
            },
            { exception ->
                // Оставляем кнопку активной: тап повторит загрузку (см. toggleRecognition)
                toggleButton.isEnabled = true
                statusText.text = getString(R.string.status_model_error, exception.message)
            },
        )
    }

    private fun toggleRecognition() {
        if (model == null) {
            initModel() // повторная попытка загрузки модели после ошибки
            return
        }
        if (speechService != null) {
            stopRecognition()
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
            toggleButton.setText(R.string.btn_stop)
            statusText.setText(R.string.status_listening)
        } catch (e: IOException) {
            releaseSpeechService()
            statusText.text = getString(R.string.status_error, e.message)
        }
    }

    private fun stopRecognition() {
        releaseSpeechService()
        resetToggle()
    }

    // Единый безопасный демонтаж: stop() останавливает поток и выдаёт финальный
    // результат, shutdown() освобождает AudioRecord, close() – нативный Recognizer
    // (иначе он течёт: shutdown() его не трогает)
    private fun releaseSpeechService() {
        speechService?.let {
            it.stop()
            it.shutdown()
        }
        speechService = null
        recognizer?.close()
        recognizer = null
    }

    private fun resetToggle() {
        toggleButton.setText(R.string.btn_speak)
        if (model != null) {
            statusText.setText(R.string.status_ready)
        }
    }

    // --- RecognitionListener: колбэки приходят в main thread --------------------

    override fun onPartialResult(hypothesis: String?) {
        partialText.text = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty()
    }

    // Финальный результат фразы (Vosk определил конец по тишине, слушание продолжается)
    override fun onResult(hypothesis: String?) {
        appendFinal(hypothesis)
    }

    // Финальный результат после остановки записи
    override fun onFinalResult(hypothesis: String?) {
        appendFinal(hypothesis)
    }

    override fun onError(exception: Exception?) {
        // Полный демонтаж: без stop() перед shutdown() поток распознавателя
        // продолжил бы читать уже освобождённый AudioRecord и уронил приложение
        releaseSpeechService()
        toggleButton.setText(R.string.btn_speak)
        statusText.text = getString(R.string.status_error, exception?.message)
    }

    override fun onTimeout() {
        stopRecognition()
    }

    private fun appendFinal(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
        if (text.isNotBlank()) {
            resultText.append(text + "\n")
            resultScroll.post { resultScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        partialText.text = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseSpeechService()
        model?.close()
        model = null
    }
}
