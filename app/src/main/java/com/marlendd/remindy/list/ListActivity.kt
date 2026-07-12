package com.marlendd.remindy.list

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.marlendd.remindy.ui.RecordRow
import com.marlendd.remindy.ui.theme.RemindyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Список записей (Фаза 4: Compose). Новые сверху; тап – правка, свайп-влево – удалить
 * с возможностью отмены (ТЗ F3 + UX-улучшение). Чтение под замком (этап 5): данные грузим
 * только после успешного входа, если замок включён ([LockSettings]).
 *
 * Undo: свайп прячет строку оптимистично ([pendingItems]) и показывает snackbar с «Отмена».
 * Реальное удаление – по истечении snackbar, на [appScope] (переживает уничтожение Activity,
 * чтобы уход с экрана в окне undo не потерял удаление). Отмена возвращает строку. Строку
 * снимает прунинг в observeAll(), когда удаление доедет до БД – без мигания «обратно».
 */
class ListActivity : AppCompatActivity() {

    private var repository: RecordRepository? = null

    private val snackbarHostState = SnackbarHostState()
    private var items by mutableStateOf<List<Item>>(emptyList())
    private var pendingItems by mutableStateOf<Map<Long, Item>>(emptyMap())
    private var loading by mutableStateOf(true)

    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) onUnlocked() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectFromRecents() // содержимое списка – не в снимок «недавних» (мимо гейта)
        enableEdgeToEdge()
        setContent { RemindyTheme { ListScreen() } }

        if (!LockSettings.isLockEnabled(this) || ReadGate.unlocked) onUnlocked()
        else unlockLauncher.launch(Intent(this, UnlockActivity::class.java))
    }

    private fun onUnlocked() {
        lifecycleScope.launch {
            val repo = acquireRepo() ?: return@launch
            repository = repo
            try {
                repo.observeAll().collect { list ->
                    items = list
                    loading = false
                    // Прунинг: снимаем из pending те, что уже исчезли из БД (удаление доехало).
                    // Строка остаётся скрытой ровно до этого момента – без мигания «обратно».
                    val liveIds = list.mapTo(HashSet()) { it.id }
                    pendingItems = pendingItems.filterKeys { it in liveIds }
                }
            } catch (e: Exception) {
                loading = false
                snackbarHostState.showSnackbar(getString(R.string.db_error))
            }
        }
    }

    private suspend fun acquireRepo(): RecordRepository? =
        try {
            RecordRepository(RemindyDatabase.getAsync(this))
        } catch (e: Exception) {
            loading = false
            snackbarHostState.showSnackbar(getString(R.string.db_error))
            finish()
            null
        }

    private fun openForEditing(item: Item) {
        startActivity(
            Intent(this, ConfirmationActivity::class.java)
                .putExtra(ConfirmationActivity.EXTRA_ITEM_ID, item.id),
        )
    }

    // Свайп: прячем строку оптимистично, реальное удаление – по истечении snackbar (undo отменяет).
    private fun scheduleDelete(item: Item) {
        pendingItems = pendingItems + (item.id to item)
        lifecycleScope.launch {
            val res = snackbarHostState.showSnackbar(
                message = getString(R.string.toast_deleted),
                actionLabel = getString(R.string.snackbar_undo),
                duration = SnackbarDuration.Short,
            )
            if (res == SnackbarResult.ActionPerformed) {
                pendingItems = pendingItems - item.id // отмена: строка возвращается, БД не тронута
            } else {
                // pendingItems здесь НЕ трогаем – строку снимет прунинг в observeAll(), когда Flow
                // обновится (иначе мигнула бы обратно на кадр). Room @Delete идемпотентен.
                commitDelete(item)
            }
        }
    }

    // Удаление на scope, переживающем уничтожение Activity: уход с экрана в окне undo его не теряет.
    private fun commitDelete(item: Item) {
        val repo = repository ?: return
        appScope.launch {
            try { repo.delete(item) } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Незакрытые (snackbar ещё висел) удаления коммитим на appScope – «ушёл с экрана в окне
        // undo» = удаляем ровно один раз (идемпотентно). Отменённые уже убраны из pendingItems.
        val repo = repository ?: return
        val toFlush = pendingItems.values.toList()
        if (toFlush.isNotEmpty()) {
            appScope.launch { toFlush.forEach { try { repo.delete(it) } catch (_: Exception) { } } }
        }
    }

    // --- UI -------------------------------------------------------------------

    @Composable
    private fun ListScreen() {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { inner ->
            val displayed = items.filter { it.id !in pendingItems }
            Column(Modifier.fillMaxSize().padding(inner)) {
                Text(
                    stringResource(R.string.title_list),
                    fontSize = 24.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        displayed.isEmpty() -> Text(
                            stringResource(R.string.list_empty),
                            fontSize = 20.sp,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(displayed, key = { it.id }) { item ->
                                SwipeableRow(item)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SwipeableRow(item: Item) {
        val dismissState = rememberSwipeToDismissBoxState()
        LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                scheduleDelete(item) // строка тут же уйдёт из списка (pendingItems)
            }
        }
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        stringResource(R.string.btn_delete),
                        color = MaterialTheme.colorScheme.onError,
                        fontSize = 18.sp,
                    )
                }
            },
        ) {
            RecordRow(
                item = item,
                onClick = { openForEditing(item) },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            )
        }
    }

    companion object {
        // Переживает уничтожение Activity: отложенное удаление обязано доехать, даже если
        // пользователь ушёл с экрана в окне undo (репозиторий/БД – синглтоны процесса).
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
