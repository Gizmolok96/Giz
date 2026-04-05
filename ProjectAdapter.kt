package com.potolochnik.app.ui.projects

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.potolochnik.app.data.Project
import com.potolochnik.app.databinding.ItemProjectBinding

class ProjectAdapter(
    private val onClick: (Project) -> Unit
) : ListAdapter<Project, ProjectAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProjectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemProjectBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(project: Project) {
            binding.tvProjectName.text = project.name
            if (project.client.isNotEmpty()) {
                binding.tvProjectClient.text = project.client
            } else {
                binding.tvProjectClient.text = project.address.ifEmpty { "Без клиента" }
            }
            binding.tvProjectTotal.text = "${project.totalPrice.toInt()} ₽"
            binding.tvProjectArea.text =
                "S = ${String.format("%.2f", project.totalArea)} кв.м  " +
                        "P = ${String.format("%.2f", project.totalPerimeter)} м"
            binding.tvProjectRooms.text = "Комнат: ${project.rooms.size}"
            binding.stageDot.background.setTint(Color.parseColor(project.stage.colorHex))
            binding.tvStageName.text = project.stage.label

            binding.root.setOnClickListener { onClick(project) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Project>() {
        override fun areItemsTheSame(oldItem: Project, newItem: Project) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Project, newItem: Project) =
            oldItem.stage == newItem.stage &&
                    oldItem.totalPrice == newItem.totalPrice &&
                    oldItem.rooms.size == newItem.rooms.size
    }
}
