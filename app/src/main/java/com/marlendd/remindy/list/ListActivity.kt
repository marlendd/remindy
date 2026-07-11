package com.marlendd.remindy.list

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.launch

/** Список записей (новые сверху): свайп – удалить, тап – редактировать (ТЗ F3). */
class ListActivity : AppCompatActivity() {

    private lateinit var repository: RecordRepository
    private lateinit var emptyText: TextView
    private lateinit var adapter: ItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        title = getString(R.string.title_list)

        repository = RecordRepository(RemindyDatabase.get(this))
        emptyText = findViewById(R.id.emptyText)
        val recycler: RecyclerView = findViewById(R.id.recycler)

        adapter = ItemAdapter(onClick = ::openForEditing)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        applyWindowInsets(recycler)
        attachSwipeToDelete(recycler)
        observeItems()
    }

    private fun observeItems() {
        // Сбор Flow до onDestroy; для одного простого экрана списка этого достаточно
        lifecycleScope.launch {
            repository.observeAll().collect { items ->
                adapter.submitList(items)
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
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
                val item = adapter.currentList[viewHolder.bindingAdapterPosition]
                lifecycleScope.launch {
                    repository.delete(item)
                    Toast.makeText(this@ListActivity, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
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
