package com.idp.universalremote.presentation.apps

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.idp.universalremote.databinding.ItemAppShortcutBinding

class AppsAdapter(
    private val onClick: (AppShortcut) -> Unit
) : ListAdapter<AppShortcut, AppsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppShortcutBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class VH(private val binding: ItemAppShortcutBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AppShortcut, onClick: (AppShortcut) -> Unit) {
            // Tile background = brand colour. Using a rounded GradientDrawable so we
            // don't need a per-app drawable in res/drawable.
            val tile = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = binding.root.resources.displayMetrics.density * 16f
                setColor(item.brandColor)
            }
            binding.tile.background = tile
            binding.initial.text = item.displayName.firstOrNull()?.uppercase() ?: "?"
            binding.label.text = item.displayName
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppShortcut>() {
            override fun areItemsTheSame(old: AppShortcut, new: AppShortcut) = old.id == new.id
            override fun areContentsTheSame(old: AppShortcut, new: AppShortcut) = old == new
        }
    }
}
