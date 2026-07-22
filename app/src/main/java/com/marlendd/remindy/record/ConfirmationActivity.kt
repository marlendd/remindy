package com.marlendd.remindy.record

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.R
import com.marlendd.remindy.data.RecordRepository
import com.marlendd.remindy.data.RemindyDatabase
import com.marlendd.remindy.photo.PhotoStore
import com.marlendd.remindy.security.protectFromRecents
import com.marlendd.remindy.ui.IconLabel
import com.marlendd.remindy.ui.UiScale
import com.marlendd.remindy.ui.rememberPhotoBitmap
import com.marlendd.remindy.ui.theme.RemindyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    // --- Фото места (ненавязчиво: одна кнопка «Сфотографировать место») ---------
    // photoFile – имя файла ЖЕЛАЕМОГО фото записи (null = нет/убрано); в БД попадает
    // только по «Сохранить». takenFiles – снятые в этой сессии, ещё ничьи: некоммитнутые
    // удаляются при выходе. Camera-приложение может убить наш процесс – состояние
    // переживает через onSaveInstanceState.
    private var photoFile by mutableStateOf<String?>(null)
    private var photoTouched = false // пользователь менял фото – loadForEditing не затирает
    private var showPhotoFull by mutableStateOf(false)
    private val takenFiles = mutableListOf<String>()
    private var committed = false
    private var pendingCapture: File? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = pendingCapture
            pendingCapture = null
            if (f == null) return@registerForActivityResult
            if (ok && f.length() > 0) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { PhotoStore.compressInPlace(f) }
                    takenFiles += f.name
                    photoTouched = true
                    photoFile = f.name
                }
            } else {
                f.delete() // отменил съёмку или камера ничего не записала
            }
        }

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
        if (savedInstanceState != null) {
            photoFile = savedInstanceState.getString(KEY_PHOTO)
            photoTouched = savedInstanceState.getBoolean(KEY_PHOTO_TOUCHED)
            takenFiles.addAll(savedInstanceState.getStringArrayList(KEY_TAKEN).orEmpty())
            savedInstanceState.getString(KEY_PENDING)?.let {
                pendingCapture = PhotoStore.fileFor(this, it)
            }
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
        val existing = repo.findById(id)
        if (existing == null) {
            // Запись успели удалить (например, тап по устаревшему результату поиска) –
            // честно говорим и закрываемся, а не открываем пустую правку
            Toast.makeText(this, R.string.toast_item_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Не затираем то, что пользователь успел набрать/снять, пока шёл запрос
        if (itemText.isEmpty()) itemText = existing.name
        if (locationText.isEmpty()) locationText = existing.location
        if (!photoTouched) photoFile = existing.photoFile
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PHOTO, photoFile)
        outState.putBoolean(KEY_PHOTO_TOUCHED, photoTouched)
        outState.putStringArrayList(KEY_TAKEN, ArrayList(takenFiles))
        outState.putString(KEY_PENDING, pendingCapture?.name)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ушли без сохранения – снятые в этой сессии файлы никому не принадлежат
        if (isFinishing && !committed) {
            takenFiles.forEach { PhotoStore.delete(this, it) }
        }
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

            // Фото места – ненавязчиво: без фото это одна негромкая кнопка,
            // с фото – превью (тап – во весь экран) и мелкие «Переснять»/«Убрать»
            Spacer(Modifier.size(14.dp))
            PhotoBlock()

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

        if (showPhotoFull && photoFile != null) {
            // Просмотр во весь экран: тап в любом месте закрывает
            Dialog(onDismissRequest = { showPhotoFull = false }) {
                val full = rememberPhotoBitmap(photoFile, 2048)
                if (full != null) {
                    Image(
                        full,
                        contentDescription = stringResource(R.string.cd_place_photo),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showPhotoFull = false },
                    )
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

    @Composable
    private fun PhotoBlock() {
        val current = photoFile
        if (current == null) {
            OutlinedButton(
                onClick = ::takePhoto,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                IconLabel(R.drawable.ic_camera, stringResource(R.string.btn_photo_add), 17.sp, iconSize = 20.dp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val thumb = rememberPhotoBitmap(current, 512)
                if (thumb != null) {
                    Image(
                        thumb,
                        contentDescription = stringResource(R.string.cd_place_photo),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showPhotoFull = true },
                    )
                } else {
                    // Пока декодируется – держим место, чтобы вёрстка не прыгала
                    Spacer(Modifier.size(96.dp))
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    TextButton(onClick = ::takePhoto) {
                        Text(stringResource(R.string.btn_photo_retake), fontSize = 16.sp)
                    }
                    TextButton(onClick = {
                        photoTouched = true
                        photoFile = null // файл удалится по «Сохранить» (orphan) или при выходе
                    }) {
                        Text(
                            stringResource(R.string.btn_photo_remove),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // --- Действия -------------------------------------------------------------

    private fun takePhoto() {
        val f = PhotoStore.newFile(this)
        pendingCapture = f
        try {
            takePicture.launch(PhotoStore.uriFor(this, f))
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            f.delete()
            Toast.makeText(this, R.string.photo_no_camera, Toast.LENGTH_LONG).show()
        }
    }

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
                val photo = photoFile
                val orphans: List<String>
                if (id != NO_ID) {
                    val existing = repo.findById(id)
                    if (existing == null) {
                        // Запись удалили, пока экран был открыт – «Сохранено» было бы враньём
                        Toast.makeText(this@ConfirmationActivity, R.string.toast_item_missing, Toast.LENGTH_LONG).show()
                        finish()
                        return@launch
                    }
                    orphans = repo.update(existing.copy(name = name, location = location, photoFile = photo), now)
                } else {
                    orphans = repo.save(name, location, now, photo)
                }
                // Файлы, которые больше не принадлежат ни одной записи + пересъёмки этой сессии
                committed = true
                withContext(Dispatchers.IO) {
                    orphans.forEach { PhotoStore.delete(this@ConfirmationActivity, it) }
                    takenFiles.filter { it != photo }
                        .forEach { PhotoStore.delete(this@ConfirmationActivity, it) }
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
                committed = true
                withContext(Dispatchers.IO) {
                    PhotoStore.delete(this@ConfirmationActivity, existing?.photoFile)
                    takenFiles.forEach { PhotoStore.delete(this@ConfirmationActivity, it) }
                }
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
        private const val KEY_PHOTO = "photo_file"
        private const val KEY_PHOTO_TOUCHED = "photo_touched"
        private const val KEY_TAKEN = "taken_files"
        private const val KEY_PENDING = "pending_capture"
    }
}
