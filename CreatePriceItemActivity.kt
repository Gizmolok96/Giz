package com.potolochnik.app.ui.pricelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.potolochnik.app.R
import com.potolochnik.app.data.AppStorage
import com.potolochnik.app.data.PriceListItem

class CreatePriceItemActivity : AppCompatActivity() {

    private lateinit var storage: AppStorage
    private var editItemId: String? = null
    private var existingItem: PriceListItem? = null

    private lateinit var etName: EditText
    private lateinit var etPrice: EditText
    private lateinit var etCostPrice: EditText
    private lateinit var spinnerUnit: Spinner
    private lateinit var spinnerCalcType: Spinner
    private lateinit var cbAutoFill: CheckBox

    companion object {
        private const val EXTRA_ITEM_ID = "item_id"

        fun start(context: Context, itemId: String?) {
            val intent = Intent(context, CreatePriceItemActivity::class.java).apply {
                itemId?.let { putExtra(EXTRA_ITEM_ID, it) }
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_price_item)

        storage = AppStorage.getInstance(this)
        editItemId = intent.getStringExtra(EXTRA_ITEM_ID)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (editItemId != null) "Редактировать позицию" else "Создать позицию"

        etName = findViewById(R.id.etName)
        etPrice = findViewById(R.id.etPrice)
        etCostPrice = findViewById(R.id.etCostPrice)
        spinnerUnit = findViewById(R.id.spinnerUnit)
        spinnerCalcType = findViewById(R.id.spinnerCalcType)
        cbAutoFill = findViewById(R.id.cbAutoFill)

        setupSpinners()

        if (editItemId != null) {
            existingItem = storage.getPriceList().find { it.id == editItemId }
            existingItem?.let { populateFields(it) }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave).setOnClickListener {
            saveItem()
        }
    }

    private fun setupSpinners() {
        val units = arrayOf("шт", "кв.м", "м.п.", "компл.")
        spinnerUnit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)

        val calcTypes = arrayOf("Количество", "Площадь помещения", "Периметр помещения")
        spinnerCalcType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, calcTypes)
    }

    private fun populateFields(item: PriceListItem) {
        etName.setText(item.name)
        etPrice.setText(if (item.pricePerUnit != 0.0) item.pricePerUnit.toInt().toString() else "")
        etCostPrice.setText(if (item.costPrice != 0.0) item.costPrice.toInt().toString() else "")
        cbAutoFill.isChecked = item.autoFill

        val units = listOf("шт", "кв.м", "м.п.", "компл.")
        val unitIdx = units.indexOf(item.unit).coerceAtLeast(0)
        spinnerUnit.setSelection(unitIdx)

        val calcIdx = when (item.calcType) {
            "area" -> 1
            "perimeter" -> 2
            else -> 0
        }
        spinnerCalcType.setSelection(calcIdx)
    }

    private fun saveItem() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            etName.error = "Введите название"
            return
        }

        val price = etPrice.text.toString().toDoubleOrNull() ?: 0.0
        val costPrice = etCostPrice.text.toString().toDoubleOrNull() ?: 0.0

        val unit = spinnerUnit.selectedItem.toString()
        val calcType = when (spinnerCalcType.selectedItemPosition) {
            1 -> "area"
            2 -> "perimeter"
            else -> "quantity"
        }

        val item = existingItem?.copy(
            name = name,
            pricePerUnit = price,
            costPrice = costPrice,
            unit = unit,
            calcType = calcType,
            autoFill = cbAutoFill.isChecked
        ) ?: PriceListItem(
            name = name,
            pricePerUnit = price,
            costPrice = costPrice,
            unit = unit,
            calcType = calcType,
            autoFill = cbAutoFill.isChecked
        )

        storage.savePriceListItem(item)
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
