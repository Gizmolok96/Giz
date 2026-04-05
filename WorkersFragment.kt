package com.potolochnik.app.ui.salary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.potolochnik.app.R
import com.potolochnik.app.data.AppStorage
import com.potolochnik.app.data.Worker
import com.potolochnik.app.databinding.FragmentWorkersBinding

class WorkersFragment : Fragment() {

    private var _binding: FragmentWorkersBinding? = null
    private val binding get() = _binding!!
    private lateinit var storage: AppStorage
    private lateinit var adapter: WorkerAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWorkersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storage = AppStorage.getInstance(requireContext())

        adapter = WorkerAdapter(
            onPickDays = { worker -> showDayPicker(worker) },
            onDelete = { worker -> confirmDeleteWorker(worker) }
        )

        binding.rvWorkers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWorkers.adapter = adapter

        binding.fabAddWorker.setOnClickListener { showAddWorkerDialog() }

        loadWorkers()
    }

    override fun onResume() {
        super.onResume()
        loadWorkers()
    }

    private fun loadWorkers() {
        val workers = storage.getWorkers()
        adapter.submitList(workers.toList())
        binding.emptyView.visibility = if (workers.isEmpty()) View.VISIBLE else View.GONE
        binding.rvWorkers.visibility = if (workers.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAddWorkerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_worker, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etWorkerName)
        val etPos = dialogView.findViewById<TextInputEditText>(R.id.etWorkerPosition)

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Добавить") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isNotEmpty()) {
                    val worker = Worker(
                        name = name,
                        position = etPos.text.toString().trim()
                    )
                    storage.saveWorker(worker)
                    loadWorkers()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDayPicker(worker: Worker) {
        val dialog = DayPickerDialog(worker.name, worker.workDays.toSet()) { selected ->
            worker.workDays.clear()
            worker.workDays.addAll(selected.sorted())
            storage.saveWorker(worker)
            loadWorkers()
        }
        dialog.show(parentFragmentManager, "day_picker_${worker.id}")
    }

    private fun confirmDeleteWorker(worker: Worker) {
        AlertDialog.Builder(requireContext())
            .setTitle("Удалить работника")
            .setMessage("Удалить ${worker.name}?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                storage.deleteWorker(worker.id)
                loadWorkers()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WorkersFragment()
    }
}
