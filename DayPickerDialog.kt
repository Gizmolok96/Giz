package com.zamerpro.app.ui.salary

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zamerpro.app.R
import com.zamerpro.app.databinding.DialogDayPickerBinding
import java.text.SimpleDateFormat
import java.util.*

class DayPickerDialog(
    private val workerName: String,
    private val initialSelected: Set<String>,
    private val onConfirm: (Set<String>) -> Unit
) : DialogFragment() {

    private var _binding: DialogDayPickerBinding? = null
    private val binding get() = _binding!!

    private val selected = mutableSetOf<String>().apply { addAll(initialSelected) }
    private var displayCal = Calendar.getInstance()
    private lateinit var dayAdapter: DayAdapter

    private val monthFormat = SimpleDateFormat("LLLL yyyy", Locale("ru"))
    private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogDayPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dayAdapter = DayAdapter(selected) { key ->
            if (selected.contains(key)) selected.remove(key) else selected.add(key)
            updateSelectedCount()
        }

        binding.rvDays.apply {
            layoutManager = GridLayoutManager(requireContext(), 7)
            adapter = dayAdapter
        }

        binding.btnPrevMonth.setOnClickListener {
            displayCal.add(Calendar.MONTH, -1)
            refreshCalendar()
        }
        binding.btnNextMonth.setOnClickListener {
            displayCal.add(Calendar.MONTH, 1)
            refreshCalendar()
        }
        binding.btnClear.setOnClickListener {
            selected.clear()
            refreshCalendar()
            updateSelectedCount()
        }
        binding.btnConfirm.setOnClickListener {
            onConfirm(selected.toSet())
            dismiss()
        }

        refreshCalendar()
        updateSelectedCount()
    }

    private fun refreshCalendar() {
        val cal = displayCal.clone() as Calendar
        binding.tvMonthYear.text = monthFormat.format(cal.time).replaceFirstChar { it.uppercase() }

        cal.set(Calendar.DAY_OF_MONTH, 1)
        val items = mutableListOf<DayItem>()

        // Leading empty cells (Mon = 0, ..., Sun = 6)
        var firstDow = cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY
        if (firstDow < 0) firstDow += 7
        repeat(firstDow) { items.add(DayItem("", false, false)) }

        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        for (d in 1..daysInMonth) {
            val c = Calendar.getInstance().apply { set(year, month, d) }
            val key = dayKeyFormat.format(c.time)
            val dow = c.get(Calendar.DAY_OF_WEEK)
            val isWeekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            items.add(DayItem(key, selected.contains(key), isWeekend, d))
        }

        dayAdapter.submitList(items)
    }

    private fun updateSelectedCount() {
        binding.tvSelectedCount.text = "Выбрано: ${selected.size} ${daysWord(selected.size)}"
    }

    private fun daysWord(n: Int): String = when {
        n % 100 in 11..19 -> "дней"
        n % 10 == 1 -> "день"
        n % 10 in 2..4 -> "дня"
        else -> "дней"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class DayItem(
        val key: String,
        val isSelected: Boolean,
        val isWeekend: Boolean,
        val dayNumber: Int = 0
    )

    inner class DayAdapter(
        private val selected: MutableSet<String>,
        private val onToggle: (String) -> Unit
    ) : RecyclerView.Adapter<DayAdapter.VH>() {

        private var items: List<DayItem> = emptyList()

        fun submitList(list: List<DayItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val dp44 = (44 * parent.context.resources.displayMetrics.density).toInt()
            val v = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, dp44
                )
                gravity = android.view.Gravity.CENTER
                textSize = 14f
            }
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val tv = holder.tv
            if (item.dayNumber == 0) {
                tv.text = ""
                tv.background = null
                tv.setOnClickListener(null)
                return
            }
            tv.text = item.dayNumber.toString()
            val ctx = tv.context
            if (selected.contains(item.key)) {
                tv.setTextColor(Color.WHITE)
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(ctx, R.color.primary))
                }
                tv.background = bg
            } else {
                tv.background = null
                tv.setTextColor(
                    if (item.isWeekend) Color.parseColor("#F44336")
                    else ContextCompat.getColor(ctx, R.color.text_primary)
                )
            }
            tv.setOnClickListener {
                onToggle(item.key)
                val newSel = selected.contains(item.key)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = items.size

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    }
}
