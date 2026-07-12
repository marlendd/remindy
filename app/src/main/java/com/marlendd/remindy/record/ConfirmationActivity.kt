package com.marlendd.remindy.record

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.R
import com.marlendd.remindy.data.RecordRepository
import com.marlendd.remindy.data.RemindyDatabase
import com.marlendd.remindy.security.protectFromRecents
import kotlinx.coroutines.launch

/**
 * Экран подтверждения записи. Два режима:
 *  - новая запись из голоса: поля предзаполнены разбором, кнопка «Переписать»;
 *  - редактирование из списка (передан EXTRA_ITEM_ID): кнопка «Отмена», Save обновляет.
 * Ручной ввод доступен всегда (поля редактируемы, даже если распознавание пустое).
 *
 * Запись/правку не гейтим (этап 5): «одно касание» цело. База поднимается на IO-потоке,
 * «Сохранить» доступна по её готовности.
 */
class ConfirmationActivity : AppCompatActivity() {

    private var repository: RecordRepository? = null
    private lateinit var itemEdit: EditText
    private lateinit var locationEdit: EditText

    private var editingId: Long = NO_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectFromRecents() // при правке из списка тут видно место вещи – прячем из «недавних»
        setContentView(R.layout.activity_confirm)
        title = getString(R.string.title_confirm)

        itemEdit = findViewById(R.id.itemEdit)
        locationEdit = findViewById(R.id.locationEdit)
        val saveButton: Button = findViewById(R.id.saveButton)
        val rewriteButton: Button = findViewById(R.id.rewriteButton)

        applyWindowInsets(findViewById(R.id.rootConfirm))

        editingId = intent.getLongExtra(EXTRA_ITEM_ID, NO_ID)
        if (editingId != NO_ID) {
            rewriteButton.setText(R.string.btn_cancel)
        } else {
            itemEdit.setText(intent.getStringExtra(EXTRA_ITEM).orEmpty())
            locationEdit.setText(intent.getStringExtra(EXTRA_LOCATION).orEmpty())
        }

        saveButton.setOnClickListener { save() }
        rewriteButton.setOnClickListener { finish() }

        // База открывается на IO (Keystore + SQLCipher); до готовности «Сохранить» неактивна
        saveButton.isEnabled = false
        lifecycleScope.launch {
            val repo = try {
                RecordRepository(RemindyDatabase.getAsync(this@ConfirmationActivity))
            } catch (e: Exception) {
                Toast.makeText(this@ConfirmationActivity, R.string.db_error, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            repository = repo
            saveButton.isEnabled = true
            if (editingId != NO_ID) loadForEditing(repo, editingId)
        }
    }

    private suspend fun loadForEditing(repo: RecordRepository, id: Long) {
        val existing = repo.findById(id) ?: return
        // Не затираем то, что пользователь успел набрать, пока шёл запрос
        if (itemEdit.text.isNullOrEmpty()) itemEdit.setText(existing.name)
        if (locationEdit.text.isNullOrEmpty()) locationEdit.setText(existing.location)
    }

    private fun save() {
        val repo = repository ?: return
        val name = itemEdit.text.toString().trim()
        val location = locationEdit.text.toString().trim()
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
            } catch (e: Exception) {
                // Не роняем приложение – показываем ошибку, даём поправить ввод
                Toast.makeText(this@ConfirmationActivity, R.string.toast_save_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyWindowInsets(root: View) {
        val base = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics,
        ).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(base + bars.left, base, base + bars.right, base + bars.bottom)
            insets
        }
    }

    companion object {
        const val EXTRA_ITEM = "extra_item"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_ITEM_ID = "extra_item_id"
        private const val NO_ID = -1L
    }
}
