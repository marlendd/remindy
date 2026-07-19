package com.marlendd.remindy.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.R
import com.marlendd.remindy.data.Item
import com.marlendd.remindy.data.RecordRepository
import com.marlendd.remindy.data.RemindyDatabase
import com.marlendd.remindy.record.ConfirmationActivity
import com.marlendd.remindy.security.LockSettings
import com.marlendd.remindy.security.ReadGate
import com.marlendd.remindy.security.UnlockActivity
import com.marlendd.remindy.security.protectFromRecents
import com.marlendd.remindy.ui.IconLabel
import com.marlendd.remindy.ui.RecordRow
import com.marlendd.remindy.ui.UiScale
import com.marlendd.remindy.ui.theme.RemindyTheme
import com.marlendd.remindy.voice.VoskModelHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException

private const val SAMPLE_RATE = 16000.0f

/**
 * Экран поиска (ТЗ F2, Фаза 4: Compose). Голосовой запрос или ручной ввод → нечёткий поиск
 * (нормализация → стеммер → Левенштейн → синонимы). Результаты крупным списком, тап –
 * редактирование. Если ничего не нашлось – полный список; выбор из него молча запоминает
 * запрос как синоним (самообучение). Чтение под замком (этап 5): база и модель поднимаются
 * только после входа, если замок включён ([LockSettings]).
 */
class SearchActivity : AppCompatActivity(), RecognitionListener {

    private var repository: RecordRepository? = null
    private val engine = SearchEngine(RussianStemmer)

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null

    // не null → показан полный список после нулевого поиска, выбор обучит синоним
    private var learnQuery: String? = null

    // UI-состояние
    private var query by mutableStateOf("")
    private var statusText by mutableStateOf("")
    private var results by mutableStateOf<List<Item>>(emptyList())
    private var voiceEnabled by mutableStateOf(false)
    private var capturing by mutableStateOf(false)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            when {
                granted -> startVoice() // доступ дали по тапу голоса – сразу пишем
                // «навсегда отклонён» → подсказываем путь в настройки (не тупик: текст ищет всегда)
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                    Toast.makeText(this, R.string.mic_denied_settings, Toast.LENGTH_LONG).show()
            }
        }

    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) onUnlocked() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectFromRecents() // результаты поиска – не в снимок «недавних» (мимо гейта)
        enableEdgeToEdge()
        UiScale.ensureLoaded(this)
        statusText = getString(R.string.search_prompt)
        setContent { RemindyTheme { SearchScreen() } }

        if (!LockSettings.isLockEnabled(this) || ReadGate.unlocked) onUnlocked()
        else unlockLauncher.launch(Intent(this, UnlockActivity::class.java))
    }

    private fun onUnlocked() {
        lifecycleScope.launch {
            val repo = try {
                RecordRepository(RemindyDatabase.getAsync(this@SearchActivity))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@SearchActivity, R.string.db_error, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            repository = repo
            // Микрофон спрашиваем не заранее, а по тапу голоса (startVoice) – меньше лишних
            // запросов, а отказ обрабатывается там же.
            loadModel()
        }
    }

    private fun loadModel() {
        lifecycleScope.launch {
            try {
                model = VoskModelHolder.get(this@SearchActivity)
                voiceEnabled = true
            } catch (_: Exception) {
                // Голос недоступен, но текстовый поиск работает
                voiceEnabled = false
            }
        }
    }

    // --- UI -------------------------------------------------------------------

    @Composable
    private fun SearchScreen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.title_search),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
                textStyle = TextStyle(fontSize = 22.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(10.dp))
            Button(
                onClick = { toggleVoice() },
                enabled = voiceEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            ) {
                IconLabel(
                    R.drawable.ic_mic,
                    stringResource(if (capturing) R.string.btn_stop else R.string.btn_find),
                    24.sp,
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(statusText, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(10.dp))
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results, key = { it.id }) { item ->
                    RecordRow(item = item, onClick = { onResultTap(item) })
                }
            }
        }
    }

    // --- Голос ----------------------------------------------------------------

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
            statusText = getString(R.string.search_listening)
        } catch (e: IOException) {
            releaseSpeechService()
            statusText = getString(R.string.status_error, e.message)
        }
    }

    private fun finishVoiceCapture(text: String) {
        if (!capturing) return
        capturing = false
        releaseSpeechService()
        query = text
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

    // --- Поиск ----------------------------------------------------------------

    private fun runSearch(rawQuery: String) {
        val q = rawQuery.trim()
        if (q.isEmpty()) {
            statusText = getString(R.string.search_query_empty)
            return
        }
        val repo = repository ?: return
        lifecycleScope.launch {
            try {
                val allItems = repo.allItems()
                if (allItems.isEmpty()) {
                    learnQuery = null
                    results = emptyList()
                    statusText = getString(R.string.search_empty)
                    return@launch
                }
                val aliases = repo.aliasesByItem()
                val targets = allItems.map {
                    SearchTarget(it.id, it.nameNorm, aliases[it.id].orEmpty())
                }
                val matches = engine.search(q, targets)
                if (matches.isNotEmpty()) {
                    learnQuery = null
                    val byId = allItems.associateBy { it.id }
                    val found = matches.mapNotNull { byId[it.id] }
                    results = found
                    statusText = getString(R.string.search_found, found.size)
                } else {
                    // Ничего не нашли – показываем полный список, выбор обучит синоним
                    learnQuery = q
                    results = allItems // уже отсортированы по updated_at DESC
                    statusText = getString(R.string.search_none)
                }
            } catch (e: CancellationException) {
                throw e // выход с экрана во время поиска – не ошибка
            } catch (e: Exception) {
                // Ошибка БД (переполнен диск/повреждение) – показываем, а не падаем
                statusText = getString(R.string.status_error, e.message)
            }
        }
    }

    private fun onResultTap(item: Item) {
        val q = learnQuery
        learnQuery = null // одноразово: повторные тапы по списку не переобучают синоним
        val repo = repository
        if (q != null && repo != null) {
            lifecycleScope.launch {
                try { repo.learnSynonym(q, item.id) } catch (_: Exception) { }
            }
            Toast.makeText(this, R.string.toast_learned, Toast.LENGTH_SHORT).show()
        }
        startActivity(
            Intent(this, ConfirmationActivity::class.java)
                .putExtra(ConfirmationActivity.EXTRA_ITEM_ID, item.id),
        )
    }

    // --- RecognitionListener --------------------------------------------------

    override fun onPartialResult(hypothesis: String?) {
        val partial = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty()
        if (partial.isNotBlank()) query = partial
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
        statusText = getString(R.string.status_error, exception?.message)
    }

    override fun onTimeout() {
        finishVoiceCapture(query)
    }

    // --- Прочее ---------------------------------------------------------------

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onStop() {
        super.onStop()
        if (capturing) {
            capturing = false
            releaseSpeechService()
            statusText = getString(R.string.search_prompt)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseSpeechService()
        model = null // общая модель, не закрываем
    }
}
