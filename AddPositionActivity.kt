package com.zamerpro.app.ui.pricelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zamerpro.app.R
import com.zamerpro.app.data.AppStorage
import com.zamerpro.app.data.EstimateItem
import com.zamerpro.app.data.PriceListItem

class AddPositionActivity : AppCompatActivity() {

    private lateinit var storage: AppStorage
    private lateinit var rvItems: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var adapter: AddPositionAdapter
    private var allItems: MutableList<PriceListItem> = mutableListOf()
    private lateinit var projectId: String
    private lateinit var roomId: String

    companion object {
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_ROOM_ID = "room_id"

        fun start(context: Context, projectId: String, roomId: String): Intent {
            return Intent(context, AddPositionActivity::class.java).apply {
                putExtra(EXTRA_PROJECT_ID, projectId)
                putExtra(EXTRA_ROOM_ID, roomId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_position)

        storage = AppStorage.getInstance(this)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID)!!
        roomId = intent.getStringExtra(EXTRA_ROOM_ID)!!

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Товары и услуги"

        etSearch = findViewById(R.id.etSearch)
        rvItems = findViewById(R.id.rvItems)

        adapter = AddPositionAdapter { item, qty ->
            addItemToRoom(item, qty)
        }
        rvItems.layoutManager = LinearLayoutManager(this)
        rvItems.adapter = adapter

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCreate).setOnClickListener {
            CreatePriceItemActivity.start(this, null)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadItems()
    }

    override fun onResume() {
        super.onResume()
        loadItems()
    }

    private fun loadItems() {
        allItems = storage.getPriceList()
        filterList(etSearch.text.toString())
    }

    private fun filterList(query: String) {
        val filtered = if (query.isBlank()) allItems
        else allItems.filter { it.name.contains(query, ignoreCase = true) }
        adapter.submitList(filtered.toMutableList())
    }

    private fun addItemToRoom(item: PriceListItem, qty: Double) {
        val project = storage.getProject(projectId) ?: return
        val room = project.rooms.find { it.id == roomId } ?: return

        // Auto-fill: items measured in sq.m always use the known room area
        val actualQty = when {
            item.unit == "кв.м" || item.calcType == "area" -> room.area
            item.calcType == "perimeter" -> room.perimeter
            else -> qty
        }

        val estimateItem = EstimateItem(
            name = item.name,
            pricePerUnit = item.pricePerUnit,
            costPrice = item.costPrice,
            unit = item.unit,
            quantity = actualQty
        )
        room.estimateItems.add(estimateItem)
        storage.saveProject(project)

        val qtyLabel = if (item.unit == "кв.м" || item.calcType == "area")
            " (площадь: ${"%.2f".format(actualQty)} кв.м)"
        else ""
        Toast.makeText(this, "«${item.name}» добавлена в расчет$qtyLabel", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

class AddPositionAdapter(
    private val onAdd: (PriceListItem, Double) -> Unit
) : RecyclerView.Adapter<AddPositionAdapter.VH>() {

    private var items: MutableList<PriceListItem> = mutableListOf()

    fun submitList(list: MutableList<PriceListItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_price_list_add, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val etQty: EditText = view.findViewById(R.id.etQty)
        val btnAdd: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnAddToCalc)

        fun bind(item: PriceListItem) {
            tvName.text = item.name
            tvPrice.text = "${item.pricePerUnit.toInt()} ₽ / ${item.unit}"

            // sq.m items always auto-fill from room area
            val isAreaItem = item.unit == "кв.м" || item.calcType == "area"
            val isPerimItem = item.calcType == "perimeter"
            val isAutoCalc = isAreaItem || isPerimItem
            etQty.hint = when {
                isAreaItem -> "Авто (кв.м)"
                isPerimItem -> "Авто (периметр)"
                else -> "Количество"
            }
            etQty.isEnabled = !isAutoCalc

            btnAdd.setOnClickListener {
                val qty = etQty.text.toString().toDoubleOrNull() ?: 0.0
                onAdd(item, qty)
                etQty.setText("")
            }
        }
    }
}
