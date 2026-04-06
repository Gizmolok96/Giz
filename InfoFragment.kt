package com.zamerpro.app.ui.project

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.zamerpro.app.R
import com.zamerpro.app.data.AppStorage
import com.zamerpro.app.data.Project
import com.zamerpro.app.data.ProjectStage
import com.zamerpro.app.databinding.FragmentInfoBinding

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!
    private lateinit var storage: AppStorage
    private lateinit var projectId: String
    private lateinit var project: Project

    // -1 = скидка (минус), +1 = наценка (плюс)
    private var discountSign = -1

    // Подавляет TextWatcher при программном заполнении полей
    private var isPopulating = false

    companion object {
        private const val ARG_PROJECT_ID = "project_id"
        fun newInstance(id: String) = InfoFragment().apply {
            arguments = Bundle().also { it.putString(ARG_PROJECT_ID, id) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storage = AppStorage.getInstance(requireContext())
        projectId = requireArguments().getString(ARG_PROJECT_ID)!!
        project = storage.getProject(projectId)!!

        populateFields()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        val fresh = storage.getProject(projectId) ?: return
        project = fresh
        populateFields()
    }

    private fun populateFields() {
        isPopulating = true

        binding.etAddress.setText(project.address)
        binding.etClient.setText(project.client)
        binding.etPhone.setText(project.phone)
        binding.etMeasureDate.setText(project.measureDate)
        binding.etInstallDate.setText(project.installDate)

        if (project.discount != 0.0) {
            discountSign = if (project.discount < 0) -1 else 1
            binding.etDiscount.setText(Math.abs(project.discount).let {
                if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
            })
        } else {
            discountSign = -1
            binding.etDiscount.setText("")
        }

        updateSignButtons()
        updateStageDot()

        isPopulating = false
    }

    private fun updateSignButtons() {
        binding.btnDiscountMinus.alpha = if (discountSign == -1) 1.0f else 0.35f
        binding.btnDiscountPlus.alpha  = if (discountSign ==  1) 1.0f else 0.35f
    }

    private fun saveField(update: (Project) -> Unit) {
        storage.updateProject(projectId, update)
        storage.getProject(projectId)?.let { project = it }
    }

    private fun saveDiscount() {
        val absValue = binding.etDiscount.text.toString().toDoubleOrNull() ?: 0.0
        saveField { it.discount = absValue * discountSign }
    }

    private fun setupListeners() {
        fun watcher(onChanged: (String) -> Unit) = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isPopulating) onChanged(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        binding.etAddress.addTextChangedListener(watcher { v ->
            saveField { it.address = v }
        })
        binding.etClient.addTextChangedListener(watcher { v ->
            saveField { it.client = v }
        })
        binding.etPhone.addTextChangedListener(watcher { v ->
            saveField { it.phone = v }
        })
        binding.etMeasureDate.addTextChangedListener(watcher { v ->
            saveField { it.measureDate = v }
        })
        binding.etInstallDate.addTextChangedListener(watcher { v ->
            saveField { it.installDate = v }
        })
        binding.etDiscount.addTextChangedListener(watcher {
            saveDiscount()
        })

        binding.btnDiscountMinus.setOnClickListener {
            discountSign = -1
            updateSignButtons()
            saveDiscount()
        }

        binding.btnDiscountPlus.setOnClickListener {
            discountSign = 1
            updateSignButtons()
            saveDiscount()
        }

        binding.stageRow.setOnClickListener {
            showStagePicker()
        }
    }

    private fun showStagePicker() {
        val stages = ProjectStage.values()
        val names = stages.map { it.label }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Выберите этап")
            .setItems(names) { _, which ->
                saveField { it.stage = stages[which] }
                updateStageDot()
            }
            .show()
    }

    private fun updateStageDot() {
        binding.stageDot.background.setTint(Color.parseColor(project.stage.colorHex))
        binding.etStage.text = project.stage.label
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
