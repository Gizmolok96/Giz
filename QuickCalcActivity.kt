package com.zamerpro.app.ui.quickcalc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.zamerpro.app.R
import com.zamerpro.app.data.AppStorage
import com.zamerpro.app.data.EstimateItem
import com.zamerpro.app.data.Project
import com.zamerpro.app.data.Room
import com.zamerpro.app.ui.drawing.DrawingActivity
import com.zamerpro.app.ui.pricelist.AddPositionActivity

class QuickCalcActivity : AppCompatActivity() {

    private lateinit var storage: AppStorage
    private lateinit var project: Project
    private lateinit var room: Room

    private lateinit var tvRoomName: TextView
    private lateinit var tvTotalPrice: TextView
    private lateinit var tvAreaPerim: TextView
    private lateinit var etLengthAB: EditText
    private lateinit var etWidthBC: EditText
    private lateinit var etArea: EditText
    private lateinit var etPerimeter: EditText
    private lateinit var etRoomName: EditText
    private lateinit var etComment: EditText
    private lateinit var calcItemsContainer: LinearLayout
    private lateinit var btnAddPosition: MaterialButton
    private lateinit var btnBuildDrawing: MaterialButton

    // Flags to prevent infinite loops when auto-computing S/P from AB/BC
    private var updatingFromABBC = false
    private var updatingFromSP = false

    companion object {
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_ROOM_ID = "room_id"

        fun start(context: Context, projectId: String, roomId: String) {
            context.startActivity(Intent(context, QuickCalcActivity::class.java).apply {
                putExtra(EXTRA_PROJECT_ID, projectId)
                putExtra(EXTRA_ROOM_ID, roomId)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_calc)

        storage = AppStorage.getInstance(this)
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)!!
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)!!

        project = storage.getProject(projectId)!!
        room = project.rooms.find { it.id == roomId }!!

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        tvRoomName = findViewById(R.id.tvRoomName)
        tvTotalPrice = findViewById(R.id.tvTotalPrice)
        tvAreaPerim = findViewById(R.id.tvAreaPerim)
        etLengthAB = findViewById(R.id.etLengthAB)
        etWidthBC = findViewById(R.id.etWidthBC)
        etArea = findViewById(R.id.etArea)
        etPerimeter = findViewById(R.id.etPerimeter)
        etRoomName = findViewById(R.id.etRoomName)
        etComment = findViewById(R.id.etComment)
        calcItemsContainer = findViewById(R.id.calcItemsContainer)
        btnAddPosition = findViewById(R.id.btnAddPosition)
        btnBuildDrawing = findViewById(R.id.btnBuildDrawing)

        loadRoomData()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)!!
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)!!
        project = storage.getProject(projectId) ?: return
        room = project.rooms.find { it.id == roomId } ?: return
        refreshCalcItems()
        refreshHeader()
    }

    private fun loadRoomData() {
        etRoomName.setText(room.name)
        etComment.setText(room.comment)

        if (room.area > 0) {
            etArea.setText(String.format("%.2f", room.area).replace(',', '.'))
        }
        if (room.perimeter > 0) {
            etPerimeter.setText(String.format("%.2f", room.perimeter).replace(',', '.'))
        }

        // Restore AB/BC from lines if available (rectangular room)
        val ab = room.lines.firstOrNull()?.measuredLength
        val bc = room.lines.getOrNull(1)?.measuredLength
        if (ab != null && ab > 0) etLengthAB.setText(ab.toInt().toString())
        if (bc != null && bc > 0) etWidthBC.setText(bc.toInt().toString())

        refreshCalcItems()
        refreshHeader()
    }

    private fun setupListeners() {
        // AB/BC → auto-compute S and P
        val abBcWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromSP) return
                val ab = etLengthAB.text.toString().toDoubleOrNull() ?: return
                val bc = etWidthBC.text.toString().toDoubleOrNull() ?: return
                updatingFromABBC = true
                val area = ab * bc / 10000.0
                val perim = 2.0 * (ab + bc) / 100.0
                etArea.setText(String.format("%.2f", area).replace(',', '.'))
                etPerimeter.setText(String.format("%.2f", perim).replace(',', '.'))
                updatingFromABBC = false
                saveAndRefresh()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        etLengthAB.addTextChangedListener(abBcWatcher)
        etWidthBC.addTextChangedListener(abBcWatcher)

        // S/P direct input
        val spWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromABBC) return
                saveAndRefresh()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        etArea.addTextChangedListener(spWatcher)
        etPerimeter.addTextChangedListener(spWatcher)

        // Name / comment
        etRoomName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { saveAndRefresh() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etComment.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { saveAndRefresh() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnAddPosition.setOnClickListener {
            startActivity(AddPositionActivity.start(this, project.id, room.id))
        }

        btnBuildDrawing.setOnClickListener {
            val ab = etLengthAB.text.toString().toDoubleOrNull()
            val bc = etWidthBC.text.toString().toDoubleOrNull()
            if (ab != null && ab > 0 && bc != null && bc > 0) {
                // Save current data before opening drawing
                saveAndRefresh()
                DrawingActivity.startWithRect(this, project.id, room.id, ab, bc)
            } else {
                DrawingActivity.start(this, project.id, room.id)
            }
        }
    }

    private fun saveAndRefresh() {
        val name = etRoomName.text.toString().trim()
        room.name = name.ifEmpty { "Комната" }
        room.comment = etComment.text.toString().trim()
        room.area = etArea.text.toString().toDoubleOrNull() ?: 0.0
        room.perimeter = etPerimeter.text.toString().toDoubleOrNull() ?: 0.0

        // Update auto-fill estimate items that use area/perimeter
        room.estimateItems.forEach { item ->
            if (item.unit == "кв.м" && item.name != "Скидка. Общая") {
                item.quantity = room.area
            }
        }

        storage.saveRoom(project.id, room)
        refreshHeader()
    }

    private fun refreshHeader() {
        tvRoomName.text = room.name
        tvTotalPrice.text = "${room.totalPrice.toInt()} ₽"
        tvAreaPerim.text =
            "S = ${String.format("%.2f", room.area)} кв.м   P = ${String.format("%.2f", room.perimeter)} м"
    }

    private fun refreshCalcItems() {
        calcItemsContainer.removeAllViews()
        room.estimateItems.forEachIndexed { index, item ->
            val row = layoutInflater.inflate(R.layout.item_calc_row, calcItemsContainer, false)

            row.findViewById<TextView>(R.id.tvItemName).text = item.name
            row.findViewById<TextView>(R.id.tvItemUnit).text =
                "${item.pricePerUnit.toInt()} ₽ / ${item.unit}"

            val etQty = row.findViewById<EditText>(R.id.etItemQty)
            val tvPrice = row.findViewById<TextView>(R.id.tvItemPrice)

            val qtyStr = if (item.quantity == item.quantity.toLong().toDouble())
                item.quantity.toLong().toString()
            else String.format("%.2f", item.quantity)
            etQty.setText(qtyStr)
            tvPrice.text = "${item.total.toInt()} ₽"

            etQty.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val qty = s.toString().toDoubleOrNull() ?: 0.0
                    item.quantity = qty
                    tvPrice.text = "${item.total.toInt()} ₽"
                    storage.saveRoom(project.id, room)
                    refreshHeader()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            row.findViewById<ImageButton>(R.id.btnDeleteItem).setOnClickListener {
                room.estimateItems.removeAt(index)
                storage.saveRoom(project.id, room)
                refreshCalcItems()
                refreshHeader()
            }

            calcItemsContainer.addView(row)
        }
        refreshHeader()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
