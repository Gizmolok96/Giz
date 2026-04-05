package com.potolochnik.app.ui.project

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.potolochnik.app.data.Room
import com.potolochnik.app.databinding.ItemRoomBinding

class RoomAdapter(
    private val onRoomClick: (Room) -> Unit,
    private val onRoomLongClick: (Room) -> Unit
) : ListAdapter<Room, RoomAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRoomBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(room: Room) {
            binding.tvRoomName.text = room.name
            binding.tvRoomTotal.text = "${room.totalPrice.toInt()} ₽"
            binding.tvRoomAreaPerim.text =
                "S = ${String.format("%.2f", room.area)} кв.м   P = ${String.format("%.2f", room.perimeter)} м"
            binding.switchInclude.isChecked = room.includeInCalc
            binding.root.setOnClickListener { onRoomClick(room) }
            binding.root.setOnLongClickListener { onRoomLongClick(room); true }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Room>() {
        override fun areItemsTheSame(oldItem: Room, newItem: Room) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Room, newItem: Room) =
            oldItem.area == newItem.area && oldItem.totalPrice == newItem.totalPrice
    }
}
