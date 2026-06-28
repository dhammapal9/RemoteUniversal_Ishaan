package com.idp.universalremote.presentation.cast

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.idp.universalremote.databinding.ItemMediaCellBinding
import com.idp.universalremote.domain.model.MediaItem
import com.idp.universalremote.domain.model.MediaType

class MediaAdapter : ListAdapter<MediaItem, MediaAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.id == b.id
            override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
        }
    }

    inner class VH(val binding: ItemMediaCellBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            Glide.with(binding.thumbnail)
                .load(item.uri)
                .centerCrop()
                .into(binding.thumbnail)
            binding.title.text = item.title
            binding.title.visibility = if (item.mediaType == MediaType.AUDIO) android.view.View.VISIBLE
                                       else android.view.View.GONE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMediaCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
