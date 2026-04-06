package com.zamerpro.app.ui.project

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zamerpro.app.R
import com.zamerpro.app.data.*
import com.zamerpro.app.databinding.FragmentRoomsBinding
import com.zamerpro.app.ui.drawing.DrawingActivity
import com.zamerpro.app.ui.quickcalc.QuickCalcActivity

class RoomsFragment : Fragment() {

    private var _binding: FragmentRoomsBinding? = null
    private val binding get() = _binding!!
    private lateinit var storage: AppStorage
    private lateinit var projectId: String
    private lateinit var project: Project
    private lateinit var adapter: RoomAdapter

    companion object {
        private const val ARG_PROJECT_ID = "project_id"
        fun newInstance(projectId: String) = RoomsFragment().apply {
            arguments = Bundle().also { it.putString(ARG_PROJECT_ID, projectId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRoomsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storage = AppStorage.getInstance(requireContext())
        projectId = requireArguments().getString(ARG_PROJECT_ID)!!
        project = storage.getProject(projectId)!!

        setupRecyclerView()
        setupAddButton()
        refreshRooms()
    }

    override fun onResume() {
        super.onResume()
        val fresh = storage.getProject(projectId) ?: return
        project = fresh
        refreshRooms()
    }

    private fun openRoom(room: Room) {
        if (room.type == RoomType.QUICK && room.points.isEmpty()) {
            QuickCalcActivity.start(requireContext(), projectId, room.id)
        } else {
            DrawingActivity.start(requireContext(), projectId, room.id)
        }
    }

    private fun setupRecyclerView() {
        adapter = RoomAdapter(
            onRoomClick = { room -> openRoom(room) },
            onRoomLongClick = { room -> showRoomOptions(room) }
        )
        binding.rvRooms.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRooms.adapter = adapter
    }

    private fun setupAddButton() {
        binding.btnAddRoom.setOnClickListener {
            showAddRoomSheet()
        }
    }

    private fun refreshRooms() {
        val isEmpty = project.rooms.isEmpty()
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvRooms.visibility = if (isEmpty) View.GONE else View.VISIBLE

        binding.tvTotal.text = "${project.totalPrice.toInt()} ₽"
        binding.tvAreaPerim.text =
            "S = ${String.format("%.2f", project.totalArea)} кв.м   " +
                    "P = ${String.format("%.2f", project.totalPerimeter)} м"

        adapter.submitList(project.rooms.toMutableList())
    }

    private fun showAddRoomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.sheet_add_room, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.btnRectRoom).setOnClickListener {
            dialog.dismiss()
            showNameDialog(RoomType.RECTANGLE)
        }
        view.findViewById<View>(R.id.btnPolygonRoom).setOnClickListener {
            dialog.dismiss()
            showNameDialog(RoomType.POLYGON)
        }
        view.findViewById<View>(R.id.btnQuickRoom).setOnClickListener {
            dialog.dismiss()
            showNameDialog(RoomType.QUICK)
        }

        dialog.show()
    }

    private fun showNameDialog(type: RoomType) {
        val et = EditText(requireContext()).apply {
            hint = getString(R.string.room_name_hint)
            setSingleLine(true)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Название помещения")
            .setView(et)
            .setPositiveButton("Добавить") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { getString(R.string.room_name_hint) }
                addRoom(type, name)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
        et.requestFocus()
    }

    private fun addRoom(type: RoomType, name: String = getString(R.string.room_name_hint)) {
        val room = Room(type = type, name = name)
        // Атомарно добавляем комнату — не трогаем другие данные проекта
        storage.addRoomToProject(projectId, room)
        // Обновляем локальную копию
        project = storage.getProject(projectId) ?: project
        openRoom(room)
    }

    private fun showRoomOptions(room: Room) {
        val openLabel = if (room.type == RoomType.QUICK && room.points.isEmpty()) "Открыть расчет" else "Открыть чертёж"
        val options = arrayOf(openLabel, "Переименовать", getString(R.string.delete))
        AlertDialog.Builder(requireContext())
            .setTitle(room.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openRoom(room)
                    1 -> showRenameDialog(room)
                    2 -> {
                        // Атомарно удаляем только эту комнату
                        storage.removeRoomFromProject(projectId, room.id)
                        project = storage.getProject(projectId) ?: project
                        refreshRooms()
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(room: Room) {
        val et = EditText(requireContext()).apply {
            setText(room.name)
            hint = getString(R.string.room_name_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Переименовать")
            .setView(et)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newName = et.text.toString().ifEmpty { "Комната" }
                // Атомарно обновляем только имя этой комнаты
                storage.saveRoom(projectId, room.copy(name = newName))
                project = storage.getProject(projectId) ?: project
                refreshRooms()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
