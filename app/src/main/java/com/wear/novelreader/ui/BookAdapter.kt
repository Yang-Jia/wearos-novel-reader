package com.wear.novelreader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wear.novelreader.R
import com.wear.novelreader.data.BookItem
import com.wear.novelreader.databinding.ItemBookBinding

class BookAdapter(
    private val onClick: (BookItem) -> Unit,
    private val onLongClick: (BookItem) -> Unit
) : ListAdapter<BookItem, BookAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BookItem) {
            binding.bookTitle.text = item.meta.title
            val percentText = if (item.progress.hasProgress && item.percent == 0) {
                "<1%"
            } else {
                "${item.percent}%"
            }
            val chapterPosition = item.progress.chapterIndex + 1
            binding.bookMeta.text = if (item.meta.author.isBlank()) {
                binding.root.context.getString(
                    R.string.book_meta_chapters,
                    chapterPosition,
                    item.meta.chapters.size,
                    percentText
                )
            } else {
                binding.root.context.getString(
                    R.string.book_meta_author_chapters,
                    item.meta.author,
                    chapterPosition,
                    item.meta.chapters.size,
                    percentText
                )
            }
            binding.bookProgress.progress = item.percent
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<BookItem>() {
        override fun areItemsTheSame(oldItem: BookItem, newItem: BookItem): Boolean {
            return oldItem.meta.id == newItem.meta.id
        }

        override fun areContentsTheSame(oldItem: BookItem, newItem: BookItem): Boolean {
            return oldItem.meta.title == newItem.meta.title &&
                oldItem.meta.author == newItem.meta.author &&
                oldItem.meta.chapters.size == newItem.meta.chapters.size &&
                oldItem.progress.chapterIndex == newItem.progress.chapterIndex &&
                oldItem.percent == newItem.percent
        }
    }
}
