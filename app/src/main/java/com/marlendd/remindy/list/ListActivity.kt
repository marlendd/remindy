package com.marlendd.remindy.list

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.marlendd.remindy.R
import com.marlendd.remindy.data.Item
import com.marlendd.remindy.data.RecordRepository
import com.marlendd.remindy.data.RemindyDatabase
import com.marlendd.remindy.record.ConfirmationActivity
import com.marlendd.remindy.security.ReadGate
import com.marlendd.remindy.security.UnlockActivity
import com.marlendd.remindy.security.protectFromRecents
import kotlinx.coroutines.launch

/**
 * Список записей (новые сверху): свайп – удалить, тап – редактировать (ТЗ F3).
 * Чтение под замком (этап 5): данные грузим только после успешного входа.
 */
class ListActivity : AppCompatActivity() {

    private var repository: RecordRepository? = null
    private lateinit var emptyText: TextView
    private lateinit var adapter: ItemAdapter

    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) onUnlocked() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectFromRecents() // содержимое списка – не в снимок «недавних» (мимо гейта)
        setContentView(R.layout.activity_list)
        title = getString(R.string.title_list)

        emptyText = findViewById(R.id.emptyText)
        val recycler: RecyclerView = findViewById(R.id.recycler)

        adapter = ItemAdapter(onClick = ::openForEditing)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        applyWindowInsets(recycler)
        attachSwipeToDelete(recycler)

        if (ReadGate.unlocked) onUnlocked()
        else unlockLauncher.launch(Intent(this, UnlockActivity::class.java))
    }

    private fun onUnlocked() {
        lifecycleScope.launch {
            val repo = acquireRepo() ?: return@launch
            repository = repo
            try {
                repo.observeAll().collect { items ->
                    adapter.submitList(items)
                    emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                Toast.makeText(this@ListActivity, R.string.db_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun acquireRepo(): RecordRepository? =
        try {
            RecordRepository(RemindyDatabase.getAsync(this))
        } catch (e: Exception) {
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

    private fun attachSwipeToDelete(recycler: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT, // ТЗ F3: свайп влево – удалить
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val repo = repository ?: return
                val position = viewHolder.bindingAdapterPosition
                if (position !in adapter.currentList.indices) return
                val item = adapter.currentList[position]
                lifecycleScope.launch {
                    try {
                        repo.delete(item)
                        Toast.makeText(this@ListActivity, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@ListActivity, R.string.db_error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

    private fun applyWindowInsets(recycler: RecyclerView) {
        val base = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics,
        ).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(recycler) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, base, bars.right, bars.bottom)
            insets
        }
    }
}
