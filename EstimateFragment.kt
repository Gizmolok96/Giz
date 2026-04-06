package com.zamerpro.app.ui.project

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.zamerpro.app.R
import com.zamerpro.app.data.AppStorage
import com.zamerpro.app.data.Project
import com.zamerpro.app.databinding.FragmentEstimateBinding
import kotlin.math.abs

class EstimateFragment : Fragment() {

    private var _binding: FragmentEstimateBinding? = null
    private val binding get() = _binding!!
    private lateinit var storage: AppStorage
    private lateinit var project: Project

    companion object {
        private const val ARG_PROJECT_ID = "project_id"
        fun newInstance(projectId: String) = EstimateFragment().apply {
            arguments = Bundle().also { it.putString(ARG_PROJECT_ID, projectId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEstimateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storage = AppStorage.getInstance(requireContext())
        val projectId = requireArguments().getString(ARG_PROJECT_ID)!!
        project = storage.getProject(projectId)!!

        populateEstimate()
    }

    override fun onResume() {
        super.onResume()
        val projectId = requireArguments().getString(ARG_PROJECT_ID)!!
        project = storage.getProject(projectId) ?: return
        populateEstimate()
    }

    private fun populateEstimate() {
        val isEmpty = project.rooms.isEmpty()
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.estimateContent.visibility = if (isEmpty) View.GONE else View.VISIBLE

        if (isEmpty) return

        val discount = project.discount          // e.g. -10.0 или +5.0
        val hasDiscount = discount != 0.0
        val multiplier = 1.0 + discount / 100.0  // 0.9 при -10%, 1.05 при +5%

        // ── Итоговая карточка ──────────────────────────────────────────────
        val baseTotal = project.basePrice
        val finalTotal = project.totalPrice      // уже с учётом скидки/наценки

        binding.tvTotal.text = "${finalTotal.toInt()} ₽"
        binding.tvAreaPerim.text =
            "S = ${String.format("%.2f", project.totalArea)} кв.м   P = ${String.format("%.2f", project.totalPerimeter)} м"

        // Строка скидки/наценки в итоговой карточке
        if (hasDiscount) {
            binding.discountRow.visibility = View.VISIBLE
            val diff = finalTotal - baseTotal   // отрицательное при скидке
            val sign = if (discount < 0) "−" else "+"
            val pct = abs(discount).toInt()
            binding.tvDiscountLabel.text = if (discount < 0) "Скидка $sign$pct%" else "Наценка $sign$pct%"
            binding.tvDiscountAmount.text = "${if (diff < 0) "−" else "+"}${abs(diff).toInt()} ₽"
            binding.tvDiscountAmount.setTextColor(if (discount < 0) Color.parseColor("#F44336") else Color.parseColor("#4CAF50"))
        } else {
            binding.discountRow.visibility = View.GONE
        }

        // ── Позиции сметы ──────────────────────────────────────────────────
        binding.estimateItemsContainer.removeAllViews()

        // Группируем позиции всех комнат по наименованию
        val merged = mutableMapOf<String, Pair<Double, Double>>() // name -> (pricePerUnit, totalQty)

        project.rooms.filter { it.includeInCalc }.forEach { room ->
            room.estimateItems.forEach { item ->
                val existing = merged[item.name]
                if (existing != null) {
                    merged[item.name] = Pair(item.pricePerUnit, existing.second + item.quantity)
                } else {
                    merged[item.name] = Pair(item.pricePerUnit, item.quantity)
                }
            }
        }

        merged.forEach { (name, pair) ->
            val (pricePerUnit, totalQty) = pair
            val baseItemTotal = pricePerUnit * totalQty
            val finalItemTotal = baseItemTotal * multiplier

            val row = layoutInflater.inflate(R.layout.item_estimate_row, binding.estimateItemsContainer, false)
            row.findViewById<TextView>(R.id.tvEstItemName).text = name
            row.findViewById<TextView>(R.id.tvEstItemTotal).text = "${finalItemTotal.toInt()} ₽"
            row.findViewById<TextView>(R.id.tvEstItemUnit).text =
                "${pricePerUnit.toInt()} ₽ / ${if (pricePerUnit < 0) "шт" else "кв.м или шт"}"
            row.findViewById<TextView>(R.id.tvEstItemQty).text =
                if (totalQty == totalQty.toLong().toDouble()) totalQty.toLong().toString()
                else String.format("%.2f", totalQty)

            // Бейдж скидки/наценки рядом с ценой
            val badge = row.findViewById<TextView>(R.id.tvEstItemDiscount)
            if (hasDiscount) {
                badge.visibility = View.VISIBLE
                val sign = if (discount < 0) "−" else "+"
                val pct = abs(discount).toInt()
                badge.text = "$sign$pct%"
                badge.setTextColor(if (discount < 0) Color.parseColor("#F44336") else Color.parseColor("#4CAF50"))
            } else {
                badge.visibility = View.GONE
            }

            binding.estimateItemsContainer.addView(row)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
