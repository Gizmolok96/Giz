package com.potolochnik.app.ui.salary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.potolochnik.app.R
import com.potolochnik.app.data.AppStorage
import com.potolochnik.app.data.SalaryRecord
import com.potolochnik.app.data.Worker
import com.potolochnik.app.data.WorkerResult
import com.potolochnik.app.databinding.FragmentSalaryCalcBinding
import com.potolochnik.app.databinding.ItemSalaryResultBinding
import java.text.SimpleDateFormat
import java.util.*

class SalaryCalculationFragment : Fragment() {

    private var _binding: FragmentSalaryCalcBinding? = null
    private val binding get() = _binding!!
    private lateinit var storage: AppStorage

    private var lastResults: List<WorkerResult> = emptyList()
    private lateinit var resultAdapter: ResultAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSalaryCalcBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storage = AppStorage.getInstance(requireContext())

        resultAdapter = ResultAdapter()
        binding.rvResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvResults.adapter = resultAdapter

        binding.btnCalculate.setOnClickListener { calculate() }
        binding.btnSaveToHistory.setOnClickListener { saveToHistory() }

        checkWorkers()
    }

    override fun onResume() {
        super.onResume()
        checkWorkers()
    }

    private fun checkWorkers() {
        val workers = storage.getWorkers()
        val hasWorkers = workers.any { it.workDays.isNotEmpty() }
        binding.emptyView.visibility = if (!hasWorkers) View.VISIBLE else View.GONE
        binding.btnCalculate.isEnabled = hasWorkers
    }

    private fun calculate() {
        val totalStr = binding.etTotalAmount.text.toString().trim()
        val total = totalStr.toDoubleOrNull()
        if (total == null || total <= 0) {
            Toast.makeText(requireContext(), "Введите общую сумму заработка", Toast.LENGTH_SHORT).show()
            return
        }

        val workers = storage.getWorkers().filter { it.workDays.isNotEmpty() }
        if (workers.isEmpty()) {
            Toast.makeText(requireContext(), "Нет работников с рабочими днями", Toast.LENGTH_SHORT).show()
            return
        }

        val totalManDays = workers.sumOf { it.workDays.size }
        if (totalManDays == 0) return

        val ratePerDay = total / totalManDays

        lastResults = workers.map { w ->
            val amount = ratePerDay * w.workDays.size
            WorkerResult(
                workerId = w.id,
                workerName = w.name,
                workerPosition = w.position,
                workDays = w.workDays.size,
                amount = amount
            )
        }

        binding.tvManDaysInfo.visibility = View.VISIBLE
        binding.tvManDaysInfo.text = "Всего человеко-дней: $totalManDays  •  Ставка: ${ratePerDay.toInt()} ₽/день"

        resultAdapter.submitList(lastResults)
        binding.rvResults.visibility = View.VISIBLE
        binding.btnSaveToHistory.visibility = View.VISIBLE
    }

    private fun saveToHistory() {
        if (lastResults.isEmpty()) return

        val total = binding.etTotalAmount.text.toString().toDoubleOrNull() ?: return
        val note = binding.etNote.text.toString().trim()
        val dateStr = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")).format(Date())

        val record = SalaryRecord(
            date = dateStr,
            totalAmount = total,
            note = note,
            results = lastResults.toMutableList()
        )

        storage.addSalaryRecord(record)
        Toast.makeText(requireContext(), "Расчёт сохранён в историю", Toast.LENGTH_SHORT).show()
        binding.btnSaveToHistory.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = SalaryCalculationFragment()
    }

    // ─── Result Adapter ───────────────────────────────────────────────────────

    class ResultAdapter : ListAdapter<WorkerResult, ResultAdapter.VH>(DIFF) {

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<WorkerResult>() {
                override fun areItemsTheSame(a: WorkerResult, b: WorkerResult) = a.workerId == b.workerId
                override fun areContentsTheSame(a: WorkerResult, b: WorkerResult) = a == b
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSalaryResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(private val b: ItemSalaryResultBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(r: WorkerResult) {
                b.tvResultName.text = r.workerName
                b.tvResultPosition.text = r.workerPosition.ifEmpty { "Без должности" }
                b.tvResultDays.text = "${r.workDays} ${daysWord(r.workDays)}"
                b.tvResultAmount.text = "${r.amount.toInt()} ₽"
            }

            private fun daysWord(n: Int): String = when {
                n % 100 in 11..19 -> "дней"
                n % 10 == 1 -> "день"
                n % 10 in 2..4 -> "дня"
                else -> "дней"
            }
        }
    }
}
