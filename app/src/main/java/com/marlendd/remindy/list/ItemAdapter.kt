package com.marlendd.remindy.list

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.marlendd.remindy.R
import com.marlendd.remindy.data.Item
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ItemAdapter(
    private val onClick: (Item) -> Unit,
) : ListAdapter<Item, ItemAdapter.ItemViewHolder>(DIFF) {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("ru"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_row, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.nameText)
        private val locationText: TextView = view.findViewById(R.id.locationText)
        private val dateText: TextView = view.findViewById(R.id.dateText)

        fun bind(item: Item) {
            nameText.text = item.name
            locationText.text = item.location
            dateText.text = itemView.context.getString(
                R.string.row_updated,
                dateFormat.format(Date(item.updatedAt)),
            )
            itemView.setOnClickListener { onClick(item) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
        }
    }
}
