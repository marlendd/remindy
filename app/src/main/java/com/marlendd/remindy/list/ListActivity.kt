package com.marlendd.remindy.list

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.R
import kotlinx.coroutines.CancellationException
import com.marlendd.remindy.data.Item
import com.marlendd.remindy.data.RecordRepository
import com.marlendd.remindy.data.RemindyDatabase
import com.marlendd.remindy.record.ConfirmationActivity
import com.marlendd.remindy.security.LockSettings
import com.marlendd.remindy.security.ReadGate
import com.marlendd.remindy.security.UnlockActivity
import com.marlendd.remindy.security.protectFromRecents
import com.marlendd.remindy.ui.RecordCardShape
import com.marlendd.remindy.ui.RecordRow
import com.marlendd.remindy.ui.UiScale
import com.marlendd.remindy.ui.theme.RemindyTheme
import kotlinx.coroutines.launch

/**
 * Список записей (Фаза 4: Compose). Новые сверху; тап – правка, свайп-влево – удалить
 * **с подтверждением** (ТЗ F3 + защита от случайного удаления у пожилого пользователя:
 * порог свайпа высокий + диалог «Удалить запись?»). Чтение под замком (этап 5): данные
 * грузим только после успешного входа, если замок включён ([LockSettings]).
 */
class ListActivity : AppCompatActivity() {

    private var repository: RecordRepository? = null

    private var items by mutableStateOf<List<Item>>(emptyList())
    private var loading by mutableStateOf(true)
    private var pendingConfirm by mutableStateOf<Item?>(null) // строка, для которой открыт диалог

    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) onUnlocked() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectFromRecents() // содержимое списка – не в снимок «недавних» (мимо гейта)
        enableEdgeToEdge()
        UiScale.ensureLoaded(this)
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
                }
            } catch (e: CancellationException) {
                throw e // выход с экрана отменяет сбор Flow – это не ошибка БД
            } catch (e: Exception) {
                loading = false
                Toast.makeText(this@ListActivity, R.string.db_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun acquireRepo(): RecordRepository? =
        try {
            RecordRepository(RemindyDatabase.getAsync(this))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loading = false
            Toast.makeText(this, R.string.db_error, Toast.LENGTH_LONG).show()
            finish()
            null
        }

    private fun openForEditing(item: Item) {
        startActivity(
            Intent(this, ConfirmationActivity::class.java)
                .putExtra(ConfirmationActivity.EXTRA_ITEM_ID, item.id),
        )
    }

    private fun deleteConfirmed(item: Item) {
        pendingConfirm = null
        val repo = repository ?: return
        lifecycleScope.launch {
            try {
                repo.delete(item) // строку уберёт эмиссия observeAll()
                Toast.makeText(this@ListActivity, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ListActivity, R.string.db_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- UI -------------------------------------------------------------------

    @Composable
    private fun ListScreen() {
        Scaffold { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                Text(
                    stringResource(R.string.title_list),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        items.isEmpty() -> Text(
                            stringResource(R.string.list_empty),
                            fontSize = 20.sp,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(items, key = { it.id }) { item ->
                                SwipeableRow(item)
                            }
                        }
                    }
                }
            }
        }

        pendingConfirm?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingConfirm = null },
                title = { Text(stringResource(R.string.confirm_delete_title)) },
                text = { Text(stringResource(R.string.confirm_delete_message, item.name)) },
                confirmButton = {
                    TextButton(onClick = { deleteConfirmed(item) }) {
                        Text(stringResource(R.string.btn_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirm = null }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                },
            )
        }
    }

    @Composable
    private fun SwipeableRow(item: Item) {
        // Высокий порог: нужен осознанный свайп почти на всю ширину (защита от случайного).
        val dismissState = rememberSwipeToDismissBoxState(
            positionalThreshold = { totalDistance -> totalDistance * 0.75f },
        )
        // Свайп завершён → спрашиваем подтверждение (НЕ удаляем сразу).
        LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                pendingConfirm = item
            }
        }
        // Диалог закрыт без удаления этой строки (отмена) → вернуть строку на место.
        LaunchedEffect(pendingConfirm) {
            if (pendingConfirm?.id != item.id &&
                dismissState.currentValue != SwipeToDismissBoxValue.Settled
            ) {
                dismissState.reset()
            }
        }
        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                // Красный фон в форме карточки: корзина + «Удалить» у правого края
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RecordCardShape)
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_delete),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.btn_delete),
                            color = MaterialTheme.colorScheme.onError,
                            fontSize = 18.sp,
                        )
                    }
                }
            },
        ) {
            RecordRow(item = item, onClick = { openForEditing(item) })
        }
    }
}
