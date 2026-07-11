package com.marlendd.remindy.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.marlendd.remindy.R
import com.marlendd.remindy.data.Item
import com.marlendd.remindy.data.RecordRepository
import com.marlendd.remindy.data.RemindyDatabase
import com.marlendd.remindy.list.ItemAdapter
import com.marlendd.remindy.record.ConfirmationActivity
import com.marlendd.remindy.voice.VoskModelHolder
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException

private const val SAMPLE_RATE = 16000.0f

/**
 * Экран поиска (ТЗ F2). Голосовой запрос или ручной ввод → нечёткий поиск
 * (нормализация → стеммер → Левенштейн → синонимы). Результаты крупным списком,
 * тап – редактирование. Если ничего не нашлось – полный список; выбор из него
 * молча запоминает запрос как синоним (самообучение).
 */
class SearchActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var repository: RecordRepository
    private val engine = SearchEngine(RussianStemmer)

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var capturing = false

    // не null → показан полный список после нулевого поиска, выбор обучит синоним
    private var learnQuery: String? = null

    private lateinit var rootSearch: View
    private lateinit var queryEdit: EditText
    private lateinit var voiceButton: Button
    private lateinit var statusText: TextView
    private lateinit var adapter: ItemAdapter

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        title = getString(R.string.title_search)

        repository = RecordRepository(RemindyDatabase.get(this))
        rootSearch = findViewById(R.id.rootSearch)
        queryEdit = findViewById(R.id.queryEdit)
        voiceButton = findViewById(R.id.voiceButton)
        statusText = findViewById(R.id.statusText)
        val recycler: RecyclerView = findViewById(R.id.recycler)

        adapter = ItemAdapter(onClick = ::onResultTap)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        applyWindowInsets()

        voiceButton.isEnabled = false
        voiceButton.setOnClickListener { toggleVoice() }
        queryEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(queryEdit.text.toString()); true
            } else {
                false
            }
        }

        if (!hasMicPermission()) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        loadModel()
    }

    private fun loadModel() {
        lifecycleScope.launch {
            try {
                model = VoskModelHolder.get(this@SearchActivity)
                voiceButton.isEnabled = true
            } catch (_: Exception) {
                // Голос недоступен, но текстовый поиск работает
                voiceButton.isEnabled = false
            }
        }
    }

    // --- Голос ------------------------------------------------------------------

    private fun toggleVoice() {
        if (capturing) speechService?.stop() else startVoice()
    }

    private fun startVoice() {
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
            voiceButton.setText(R.string.btn_stop)
            statusText.setText(R.string.search_listening)
        } catch (e: IOException) {
            releaseSpeechService()
            statusText.text = getString(R.string.status_error, e.message)
        }
    }

    private fun finishVoiceCapture(text: String) {
        if (!capturing) return
        capturing = false
        releaseSpeechService()
        voiceButton.setText(R.string.btn_find)
        queryEdit.setText(text)
        runSearch(text)
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

    // --- Поиск ------------------------------------------------------------------

    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            statusText.setText(R.string.search_query_empty)
            return
        }
        lifecycleScope.launch {
            val items = repository.allItems()
            if (items.isEmpty()) {
                learnQuery = null
                adapter.submitList(emptyList())
                statusText.setText(R.string.search_empty)
                return@launch
            }
            val aliases = repository.aliasesByItem()
            val targets = items.map {
                SearchTarget(it.id, it.nameNorm, aliases[it.id].orEmpty())
            }
            val matches = engine.search(query, targets)
            if (matches.isNotEmpty()) {
                learnQuery = null
                val byId = items.associateBy { it.id }
                val results = matches.mapNotNull { byId[it.id] }
                adapter.submitList(results)
                statusText.text = getString(R.string.search_found, results.size)
            } else {
                // Ничего не нашли – показываем полный список, выбор обучит синоним
                learnQuery = query
                adapter.submitList(items) // items уже отсортированы по updated_at DESC
                statusText.setText(R.string.search_none)
            }
        }
    }

    private fun onResultTap(item: Item) {
        val query = learnQuery
        learnQuery = null // одноразово: повторные тапы по списку не переобучают синоним
        if (query != null) {
            lifecycleScope.launch { repository.learnSynonym(query, item.id) }
            Toast.makeText(this, R.string.toast_learned, Toast.LENGTH_SHORT).show()
        }
        startActivity(
            Intent(this, ConfirmationActivity::class.java)
                .putExtra(ConfirmationActivity.EXTRA_ITEM_ID, item.id),
        )
    }

    // --- RecognitionListener ----------------------------------------------------

    override fun onPartialResult(hypothesis: String?) {
        val partial = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty()
        if (partial.isNotBlank()) queryEdit.setText(partial)
    }

    override fun onResult(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
        if (text.isNotBlank()) finishVoiceCapture(text)
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
        finishVoiceCapture(text)
    }

    override fun onError(exception: Exception?) {
        capturing = false
        releaseSpeechService()
        voiceButton.setText(R.string.btn_find)
        statusText.text = getString(R.string.status_error, exception?.message)
    }

    override fun onTimeout() {
        finishVoiceCapture(queryEdit.text.toString())
    }

    // --- Прочее -----------------------------------------------------------------

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun applyWindowInsets() {
        val base = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics,
        ).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(rootSearch) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(base + bars.left, base, base + bars.right, base + bars.bottom)
            insets
        }
    }

    override fun onStop() {
        super.onStop()
        if (capturing) {
            capturing = false
            releaseSpeechService()
            voiceButton.setText(R.string.btn_find)
            statusText.setText(R.string.search_prompt)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseSpeechService()
        model = null // общая модель, не закрываем
    }
}
