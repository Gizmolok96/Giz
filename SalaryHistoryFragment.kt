package com.potolochnik.app.ui.salary

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.potolochnik.app.R
import com.potolochnik.app.data.AppStorage
import com.potolochnik.app.data.SalaryRecord
import com.potolochnik.app.data.WorkerResult
import com.potolochnik.app.databinding.FragmentSalaryHistoryBinding
import com.potolochnik.app.databinding.ItemSalaryHistoryBinding

class SalaryHistoryFragment : Fragment() {

    private var _binding: FragmentSalaryHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var storage: AppStorage
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSalaryHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storage = AppStorage.getInstance(requireContext())

        adapter = HistoryAdapter { record ->
            AlertDialog.Builder(requireContext())
                .setTitle("Удалить запись")
                .setMessage("Удалить расчёт от ${record.date}?")
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    storage.deleteSalaryRecord(record.id)
                    loadHistory()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter

        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        val history = storage.getSalaryHistory()
        adapter.submitList(history.toList())
        binding.emptyView.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        binding.rvHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = SalaryHistoryFragment()
    }

    // ─── History Adapter ──────────────────────────────────────────────────────

    class HistoryAdapter(
        private val onDelete: (SalaryRecord) -> Unit
    ) : ListAdapter<SalaryRecord, HistoryAdapter.VH>(DIFF) {

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<SalaryRecord>() {
                override fun areItemsTheSame(a: SalaryRecord, b: SalaryRecord) = a.id == b.id
                override fun areContentsTheSame(a: SalaryRecord, b: SalaryRecord) = a == b
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSalaryHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(private val b: ItemSalaryHistoryBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(record: SalaryRecord) {
                b.tvHistDate.text = record.date
                b.tvHistNote.text = record.note.ifEmpty { "Расчёт зарплаты" }
                b.tvHistTotal.text = "${record.totalAmount.toInt()} ₽"
                b.btnDeleteRecord.setOnClickListener { onDelete(record) }

                // Populate worker results
                b.containerResults.removeAllViews()
                record.results.forEach { r ->
                    val row = TextView(b.root.context).apply {
                        text = "${r.workerName} — ${r.workDays} дн. → ${r.amount.toInt()} ₽"
                        textSize = 13f
                        setTextColor(android.graphics.Color.parseColor("#424242"))
                        setPadding(0, 3, 0, 3)
                    }
                    b.containerResults.addView(row)
                }
            }
        }
    }
}
