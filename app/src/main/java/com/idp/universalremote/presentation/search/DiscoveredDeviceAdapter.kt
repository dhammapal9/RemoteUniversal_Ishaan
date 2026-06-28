package com.idp.universalremote.presentation.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.idp.universalremote.databinding.ItemDiscoveredDeviceBinding
import com.idp.universalremote.domain.model.TvDevice

class DiscoveredDeviceAdapter(
    private val onClick: (TvDevice) -> Unit
) : ListAdapter<TvDevice, DiscoveredDeviceAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TvDevice>() {
            override fun areItemsTheSame(a: TvDevice, b: TvDevice) = a.id == b.id
            override fun areContentsTheSame(a: TvDevice, b: TvDevice) = a == b
        }
    }

    inner class VH(val binding: ItemDiscoveredDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(device: TvDevice) {
            binding.name.text = device.name
            binding.subtitle.text = listOfNotNull(device.brand.displayName, device.ipAddress)
                .joinToString(" • ")
            binding.root.setOnClickListener { onClick(device) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDiscoveredDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
