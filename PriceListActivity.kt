package com.potolochnik.app.ui.pricelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.potolochnik.app.R
import com.potolochnik.app.data.AppStorage
import com.potolochnik.app.data.PriceListItem

class PriceListActivity : AppCompatActivity() {

    private lateinit var storage: AppStorage
    private lateinit var rvPriceList: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var adapter: PriceListManageAdapter
    private var allItems: MutableList<PriceListItem> = mutableListOf()

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PriceListActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_price_list)

        storage = AppStorage.getInstance(this)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Прайс-лист"

        etSearch = findViewById(R.id.etSearch)
        rvPriceList = findViewById(R.id.rvPriceList)

        adapter = PriceListManageAdapter(
            onEdit = { item -> CreatePriceItemActivity.start(this, item.id) },
            onDelete = { item -> confirmDelete(item) }
        )
        rvPriceList.layoutManager = LinearLayoutManager(this)
        rvPriceList.adapter = adapter

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCreateItem).setOnClickListener {
            CreatePriceItemActivity.start(this, null)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onResume() {
        super.onResume()
        allItems = storage.getPriceList()
        filterList(etSearch.text.toString())
    }

    private fun filterList(query: String) {
        val filtered = if (query.isBlank()) allItems
        else allItems.filter { it.name.contains(query, ignoreCase = true) }
        adapter.submitList(filtered.toMutableList())
    }

    private fun confirmDelete(item: PriceListItem) {
        AlertDialog.Builder(this)
            .setMessage("Удалить «${item.name}» из прайс-листа?")
            .setPositiveButton("Удалить") { _, _ ->
                storage.deletePriceListItem(item.id)
                allItems = storage.getPriceList()
                filterList(etSearch.text.toString())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

class PriceListManageAdapter(
    private val onEdit: (PriceListItem) -> Unit,
    private val onDelete: (PriceListItem) -> Unit
) : RecyclerView.Adapter<PriceListManageAdapter.VH>() {

    private var items: MutableList<PriceListItem> = mutableListOf()

    fun submitList(list: MutableList<PriceListItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_price_list_manage, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)

        fun bind(item: PriceListItem) {
            tvName.text = item.name
            tvPrice.text = "${item.pricePerUnit.toInt()} ₽ / ${item.unit}"
            btnEdit.setOnClickListener { onEdit(item) }
            btnDelete.setOnClickListener { onDelete(item) }
            itemView.setOnClickListener { onEdit(item) }
        }
    }
}
