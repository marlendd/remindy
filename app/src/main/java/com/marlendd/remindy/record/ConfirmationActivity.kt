package com.marlendd.remindy.record

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.R
import com.marlendd.remindy.data.RecordRepository
import com.marlendd.remindy.data.RemindyDatabase
import com.marlendd.remindy.security.protectFromRecents
import com.marlendd.remindy.ui.IconLabel
import com.marlendd.remindy.ui.UiScale
import com.marlendd.remindy.ui.theme.RemindyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Экран подтверждения записи (Фаза 4: Compose). Два режима:
 *  - новая запись из голоса: поля предзаполнены разбором, кнопка «Переписать»;
 *  - редактирование из списка (передан EXTRA_ITEM_ID): заголовок «Изменить запись»,
 *    кнопка «Отмена» + «Удалить» (с подтверждением), Save обновляет.
 * Ручной ввод доступен всегда. Запись/правку не гейтим (этап 5): «одно касание» цело.
 * База поднимается на IO-потоке, «Сохранить» доступна по её готовности.
 */
class ConfirmationActivity : AppCompatActivity() {

    private var repository: RecordRepository? = null
    private var editingId: Long = NO_ID

    private var itemText by mutableStateOf("")
    private var locationText by mutableStateOf("")
    private var saveEnabled by mutableStateOf(false)
    private var dbLoading by mutableStateOf(true)
    private var showDeleteConfirm by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectFromRecents() // при правке из списка тут видно место вещи – прячем из «недавних»
        enableEdgeToEdge()
        UiScale.ensureLoaded(this)

        editingId = intent.getLongExtra(EXTRA_ITEM_ID, NO_ID)
        if (editingId == NO_ID) {
            itemText = intent.getStringExtra(EXTRA_ITEM).orEmpty()
            locationText = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        }

        setContent { RemindyTheme { ConfirmScreen() } }

        // База открывается на IO (Keystore + SQLCipher); до готовности «Сохранить» неактивна
        lifecycleScope.launch {
            val repo = try {
                RecordRepository(RemindyDatabase.getAsync(this@ConfirmationActivity))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@ConfirmationActivity, R.string.db_error, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            repository = repo
            dbLoading = false
            saveEnabled = true
            if (editingId != NO_ID) loadForEditing(repo, editingId)
        }
    }

    private suspend fun loadForEditing(repo: RecordRepository, id: Long) {
        val existing = repo.findById(id) ?: return
        // Не затираем то, что пользователь успел набрать, пока шёл запрос
        if (itemText.isEmpty()) itemText = existing.name
        if (locationText.isEmpty()) locationText = existing.location
    }

    // --- UI -------------------------------------------------------------------

    @Composable
    private fun ConfirmScreen() {
        val editing = editingId != NO_ID
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(if (editing) R.string.title_edit else R.string.title_confirm),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            Text(
                stringResource(R.string.label_item),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = itemText,
                onValueChange = { itemText = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.hint_item)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                textStyle = TextStyle(fontSize = 24.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.size(16.dp))
            Text(
                stringResource(R.string.label_location),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = locationText,
                onValueChange = { locationText = it },
                placeholder = { Text(stringResource(R.string.hint_location)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                textStyle = TextStyle(fontSize = 24.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.size(24.dp))
            if (dbLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.status_opening_db),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
            }

            Button(
                onClick = { save() },
                enabled = saveEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            ) {
                IconLabel(R.drawable.ic_check, stringResource(R.string.btn_save), 24.sp)
            }
            Spacer(Modifier.size(12.dp))
            OutlinedButton(
                onClick = { finish() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(if (editing) R.string.btn_cancel else R.string.btn_rewrite), fontSize = 20.sp)
            }
            if (editing) {
                Spacer(Modifier.size(12.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = saveEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    IconLabel(R.drawable.ic_delete, stringResource(R.string.btn_delete), 20.sp, iconSize = 22.dp)
                }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(stringResource(R.string.confirm_delete_title)) },
                text = { Text(stringResource(R.string.confirm_delete_message, itemText)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        performDelete()
                    }) { Text(stringResource(R.string.btn_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                },
            )
        }
    }

    // --- Действия -------------------------------------------------------------

    private fun save() {
        val repo = repository ?: return
        val name = itemText.trim()
        val location = locationText.trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.toast_item_required, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val now = System.currentTimeMillis()
                val id = editingId
                if (id != NO_ID) {
                    val existing = repo.findById(id)
                    if (existing != null) {
                        repo.update(existing.copy(name = name, location = location), now)
                    }
                } else {
                    repo.save(name, location, now)
                }
                Toast.makeText(this@ConfirmationActivity, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Не роняем приложение – показываем ошибку, даём поправить ввод
                Toast.makeText(this@ConfirmationActivity, R.string.toast_save_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performDelete() {
        val repo = repository ?: return
        val id = editingId
        if (id == NO_ID) return
        lifecycleScope.launch {
            try {
                val existing = repo.findById(id)
                if (existing != null) repo.delete(existing)
                Toast.makeText(this@ConfirmationActivity, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@ConfirmationActivity, R.string.db_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_ITEM = "extra_item"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_ITEM_ID = "extra_item_id"
        private const val NO_ID = -1L
    }
}
