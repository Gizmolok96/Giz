package com.zamerpro.app.ui.salary

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.zamerpro.app.data.Worker
import com.zamerpro.app.databinding.ItemWorkerCardBinding
import java.text.SimpleDateFormat
import java.util.*

class WorkerAdapter(
    private val onPickDays: (Worker) -> Unit,
    private val onDelete: (Worker) -> Unit
) : ListAdapter<Worker, WorkerAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Worker>() {
            override fun areItemsTheSame(a: Worker, b: Worker) = a.id == b.id
            override fun areContentsTheSame(a: Worker, b: Worker) = a == b
        }
    }

    private val displayFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val parseFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWorkerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemWorkerCardBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(worker: Worker) {
            b.tvWorkerName.text = worker.name
            b.tvWorkerPosition.text = worker.position.ifEmpty { "Без должности" }
            b.tvDaysCount.text = "Рабочих дней: ${worker.workDays.size}"

            // Chips for selected days
            b.chipGroupDays.removeAllViews()
            val sorted = worker.workDays.sorted().take(10)
            sorted.forEach { key ->
                try {
                    val date = parseFormat.parse(key)
                    val label = if (date != null) displayFormat.format(date) else key
                    val chip = Chip(b.root.context).apply {
                        text = label
                        isClickable = false
                        isCheckable = false
                        textSize = 11f
                    }
                    b.chipGroupDays.addView(chip)
                } catch (_: Exception) {}
            }
            if (worker.workDays.size > 10) {
                val chip = Chip(b.root.context).apply {
                    text = "+${worker.workDays.size - 10} ещё"
                    isClickable = false
                    isCheckable = false
                    textSize = 11f
                }
                b.chipGroupDays.addView(chip)
            }

            b.btnPickDays.setOnClickListener { onPickDays(worker) }
            b.btnDeleteWorker.setOnClickListener { onDelete(worker) }
        }
    }
}
