package com.potolochnik.app.ui.drawing

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.potolochnik.app.R
import com.potolochnik.app.data.*
import com.potolochnik.app.databinding.ActivityDrawingBinding
import com.potolochnik.app.ui.pricelist.AddPositionActivity
import kotlin.math.sqrt

class DrawingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDrawingBinding
    private lateinit var storage: AppStorage
    private lateinit var project: Project
    private lateinit var room: Room

    private val engine = AutoCorrectionEngine()

    private var currentDimIndex = 0
    private var allConstraints = mutableListOf<DrawLine>()
    private var inputBuffer = ""
    private var isDimensionMode = false
    private var isAllDone = false

    // Режим редактирования размеров по тапу
    private var isDimEditModeActive = false
    private var editingLine: DrawLine? = null

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_AUTO_AB = "auto_ab"
        const val EXTRA_AUTO_BC = "auto_bc"

        fun start(context: Context, projectId: String, roomId: String) {
            val intent = Intent(context, DrawingActivity::class.java).apply {
                putExtra(EXTRA_PROJECT_ID, projectId)
                putExtra(EXTRA_ROOM_ID, roomId)
            }
            context.startActivity(intent)
        }

        fun startWithRect(context: Context, projectId: String, roomId: String, ab: Double, bc: Double) {
            val intent = Intent(context, DrawingActivity::class.java).apply {
                putExtra(EXTRA_PROJECT_ID, projectId)
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_AUTO_AB, ab)
                putExtra(EXTRA_AUTO_BC, bc)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDrawingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = AppStorage.getInstance(this)

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)!!
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)!!
        project = storage.getProject(projectId)!!
        room = project.rooms.find { it.id == roomId }!!

        setupToolbar()
        setupTabs()
        setupBlueprint()
        setupNumpad()
        setupBottomToolbar()
        setupElementPanel()
        loadExistingDrawing()
    }

    override fun onResume() {
        super.onResume()
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)!!
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)!!
        val refreshed = storage.getProject(projectId) ?: return
        project = refreshed
        val refreshedRoom = project.rooms.find { it.id == roomId } ?: return
        room = refreshedRoom
        if (binding.calculationContent.visibility == View.VISIBLE) {
            updateCalculationTab()
        }
        // При возврате в уже открытый чертёж — всегда подгоняем под экран
        if (isAllDone && binding.drawingContent.visibility == View.VISIBLE) {
            binding.blueprintView.post { binding.blueprintView.fitToScreen() }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = room.name
        binding.toolbar.navigationIcon?.let {
            DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(), Color.WHITE)
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.tab_drawing)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.tab_calculation)))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showDrawingTab()
                    1 -> showCalculationTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showDrawingTab() {
        binding.drawingContent.visibility = View.VISIBLE
        binding.calculationContent.visibility = View.GONE
    }

    private fun showCalculationTab() {
        binding.drawingContent.visibility = View.GONE
        binding.calculationContent.visibility = View.VISIBLE
        updateCalculationTab()
    }

    private fun setupBlueprint() {
        binding.blueprintView.onPointAdded = { _ ->
            updateStats()
        }

        // Автоматически запускаем ввод размеров когда фигура замкнулась
        binding.blueprintView.onFigureClosed = {
            if (!isDimensionMode && !isAllDone) {
                startDimensionInput()
            }
        }

        binding.blueprintView.onTapInDimensionMode = {
        }

        binding.blueprintView.onElementAdded = { elem ->
            room.ceilingElements.add(elem)
            storage.saveRoom(project.id, room)
            updateElementCount()
            // Автоматически выходим из режима размещения после добавления элемента
            exitElementMode()
        }

        binding.blueprintView.onElementLongPress = { elem ->
            showDeleteElementDialog(elem)
        }

        binding.blueprintView.onPointMoved = {
            updateStats()
            updateRoomData()
        }

        // Режим редактирования размеров: пользователь тапнул по стене или диагонали
        binding.blueprintView.onLineSelected = { line ->
            editingLine = line
            val pts = binding.blueprintView.points
            val p1 = pts.find { it.id == line.fromId }
            val p2 = pts.find { it.id == line.toId }
            val label = if (p1 != null && p2 != null) "${p1.label}–${p2.label}" else ""
            val kind = if (line.isDiagonal) "Диагональ" else "Стена"
            val current = line.measuredLength?.toInt()?.toString() ?: ""
            inputBuffer = current
            binding.tvDimDisplay.text = if (inputBuffer.isEmpty()) "0" else inputBuffer
            binding.tvDimLabel.text = "$kind $label, см"
            setNumpadVisible(true)
        }
    }

    private fun loadExistingDrawing() {
        if (room.points.isNotEmpty()) {
            binding.blueprintView.points = room.points
            binding.blueprintView.lines = room.lines
            binding.blueprintView.diagonals = room.diagonals
            binding.blueprintView.ceilingElements = room.ceilingElements
            binding.blueprintView.isClosed = room.isClosed
            binding.blueprintView.showDimensions = room.showDimensions
            binding.blueprintView.showAngles = room.showAngles
            binding.blueprintView.snapToAxes = true

            if (room.isClosed) {
                isAllDone = true
                binding.blueprintView.isDrawingMode = false
                showDoneBar()
            }
            // scheduleFit() устанавливает флаг и вызывает invalidate().
            // fitToScreen() сработает внутри onDraw — в этот момент width/height
            // blueprintView уже финальные (view рисуется = layout завершён).
            binding.blueprintView.scheduleFit()
            updateStats()
            updateElementCount()
        }

        if (room.type == RoomType.RECTANGLE && room.points.isEmpty()) {
            showRectInputDialog()
        }

        val autoAb = intent.getDoubleExtra(EXTRA_AUTO_AB, -1.0)
        val autoBc = intent.getDoubleExtra(EXTRA_AUTO_BC, -1.0)
        if (autoAb > 0 && autoBc > 0 && room.points.isEmpty()) {
            buildRectangle(autoAb, autoBc)
        }
    }

    private fun showRectInputDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rect_input, null)
        val etAB = dialogView.findViewById<EditText>(R.id.etAB)
        val etBC = dialogView.findViewById<EditText>(R.id.etBC)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_rect_sizes))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.build_drawing)) { _, _ ->
                val ab = etAB.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val bc = etBC.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                buildRectangle(ab, bc)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun buildRectangle(ab: Double, bc: Double) {
        // Вычисляем canvas-масштаб так, чтобы после fitToScreen
        // scaleFactor ≈ 1.5 — тогда сетка выглядит мелкой, как при ручном рисовании.
        // Отступы должны точно совпадать с теми, что использует fitToScreen().
        val d = resources.displayMetrics.density
        val mSide   = 30f * d
        val mTop    = 30f * d
        val mBottom = 120f * d   // toolbar 56dp + баннер 48dp + запас 16dp
        val vw = binding.blueprintView.width.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.toFloat()
        val vh = binding.blueprintView.height.toFloat().takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels * 0.55f)
        val availW = vw - mSide * 2
        val availH = vh - mTop - mBottom
        val targetSF = 1.5f
        val scale = minOf(
            availW / (ab.toFloat() * targetSF),
            availH / (bc.toFloat() * targetSF)
        ).coerceAtLeast(1f)

        val pts = listOf(
            DrawPoint(label = "A", x = 100f, y = 100f),
            DrawPoint(label = "B", x = 100f + (ab * scale).toFloat(), y = 100f),
            DrawPoint(label = "C", x = 100f + (ab * scale).toFloat(), y = 100f + (bc * scale).toFloat()),
            DrawPoint(label = "D", x = 100f, y = 100f + (bc * scale).toFloat())
        )
        val lns = listOf(
            DrawLine(fromId = pts[0].id, toId = pts[1].id, measuredLength = ab),
            DrawLine(fromId = pts[1].id, toId = pts[2].id, measuredLength = bc),
            DrawLine(fromId = pts[2].id, toId = pts[3].id, measuredLength = ab),
            DrawLine(fromId = pts[3].id, toId = pts[0].id, measuredLength = bc)
        )

        binding.blueprintView.points = pts.toMutableList()
        binding.blueprintView.lines = lns.toMutableList()
        binding.blueprintView.isClosed = true
        binding.blueprintView.isDrawingMode = false

        room.points.clear(); room.points.addAll(pts)
        room.lines.clear(); room.lines.addAll(lns)
        room.isClosed = true

        val diag1 = DrawLine(fromId = pts[0].id, toId = pts[2].id, isDiagonal = true,
            measuredLength = sqrt((ab * ab + bc * bc)))
        val diag2 = DrawLine(fromId = pts[1].id, toId = pts[3].id, isDiagonal = true,
            measuredLength = sqrt((ab * ab + bc * bc)))
        room.diagonals.addAll(listOf(diag1, diag2))
        binding.blueprintView.diagonals = room.diagonals

        updateRoomData()
        isAllDone = true
        showDoneBar()
        binding.blueprintView.scheduleFit()
        updateStats()
    }

    private fun setupNumpad() {
        val numKeys = listOf(
            binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6,
            binding.btn7, binding.btn8, binding.btn9,
            binding.btn0
        )
        numKeys.forEachIndexed { i, btn ->
            val digit = if (i < 9) (i + 1).toString() else "0"
            btn.setOnClickListener {
                inputBuffer += digit
                binding.tvDimDisplay.text = inputBuffer
            }
        }

        binding.btnBackspace.setOnClickListener {
            if (inputBuffer.isNotEmpty()) {
                inputBuffer = inputBuffer.dropLast(1)
                binding.tvDimDisplay.text = if (inputBuffer.isEmpty()) "0" else inputBuffer
            }
        }

        binding.btnConfirm.setOnClickListener {
            confirmDimension()
        }

        binding.btnUndo.setOnClickListener {
            if (currentDimIndex > 0 && isDimensionMode) {
                currentDimIndex--
                allConstraints[currentDimIndex].measuredLength = null
                inputBuffer = ""
                binding.tvDimDisplay.text = "0"
                binding.tvDimLabel.text = ""
                showCurrentPrompt()
                binding.blueprintView.highlightLine(currentDimIndex)
            }
        }

        binding.btnDelete.setOnClickListener {
            showDeleteRoomConfirm()
        }
    }

    private fun setupBottomToolbar() {
        binding.btnSnap.setOnClickListener {
            binding.blueprintView.snapToAxes = !binding.blueprintView.snapToAxes
            val msg = if (binding.blueprintView.snapToAxes)
                getString(R.string.snap_on) else getString(R.string.snap_off)
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            binding.btnSnap.alpha = if (binding.blueprintView.snapToAxes) 1.0f else 0.5f
        }

        binding.btnEditMode.setOnClickListener {
            binding.blueprintView.isEditMode = !binding.blueprintView.isEditMode
            val isActive = binding.blueprintView.isEditMode
            val msg = if (isActive) getString(R.string.edit_mode_on) else getString(R.string.edit_mode_off)
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            binding.btnEditMode.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isActive) 0xFFFF8C00.toInt() else 0xFF545454.toInt()
            )
        }

        binding.btnFitScreen.setOnClickListener {
            binding.blueprintView.fitToScreen()
        }

        // Кнопка редактирования размеров (карандаш)
        binding.btnEditDimensions.setOnClickListener {
            if (isDimEditModeActive) {
                exitDimEditMode()
            } else {
                showDimEditSheet()
            }
        }

        // Кнопка клавиатуры: toggle показа/скрытия нумпада
        binding.btnKeyboard.setOnClickListener {
            if (binding.numpadContainer.visibility == View.VISIBLE) {
                // Клавиатура открыта — скрываем
                setNumpadVisible(false)
            } else {
                when {
                    isDimensionMode -> setNumpadVisible(true)
                    binding.blueprintView.isClosed && !isAllDone -> startDimensionInput()
                    isAllDone -> setNumpadVisible(true)
                    else -> Snackbar.make(binding.root, "Сначала замкните фигуру", Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        // Fix 3: undo last drawn wall point (supports multiple undos)
        binding.btnUndoPoint.setOnClickListener {
            if (!isDimensionMode) {
                val wasBuilt = isAllDone || binding.blueprintView.isClosed
                binding.blueprintView.undoLastPoint()
                if (wasBuilt) {
                    isAllDone = false
                    binding.doneBanner.visibility = View.GONE
                    // Сбрасываем все введённые размеры, чтобы после повторного
                    // замыкания пользователь мог ввести их заново корректно
                    binding.blueprintView.lines.forEach { it.measuredLength = null }
                    binding.blueprintView.diagonals.clear()
                    allConstraints.forEach { it.measuredLength = null }
                    allConstraints.clear()
                    currentDimIndex = 0
                    inputBuffer = ""
                }
                updateStats()
            }
        }

        binding.btnAddElement.setOnClickListener {
            showElementPickerDialog()
        }
    }

    private fun setupElementPanel() {
        binding.btnCancelElement.setOnClickListener {
            exitElementMode()
        }
    }

    private fun showElementPickerDialog() {
        val elementTypes = CeilingElementType.values()
        val labels = elementTypes.map { it.label }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Добавить элемент")
            .setItems(labels) { _, which ->
                enterElementMode(elementTypes[which])
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun enterElementMode(type: CeilingElementType) {
        binding.blueprintView.elementPlacementType = type
        binding.blueprintView.cancelPendingElement()
        binding.elementModeBar.visibility = View.VISIBLE

        val isLine = type == CeilingElementType.LINE ||
            type == CeilingElementType.LIGHT_LINE ||
            type == CeilingElementType.CORNICE ||
            type == CeilingElementType.FLOATING_PROFILE

        binding.tvElementModeHint.text = if (isLine)
            "«${type.label}» — нажмите начало, затем конец"
        else
            "«${type.label}» — нажмите на чертёж для размещения"
    }

    private fun exitElementMode() {
        binding.blueprintView.elementPlacementType = null
        binding.blueprintView.cancelPendingElement()
        binding.elementModeBar.visibility = View.GONE
    }

    private fun updateElementCount() {
        val count = room.ceilingElements.size
        binding.btnAddElement.contentDescription =
            if (count > 0) "Элементы потолка ($count)" else "Элементы потолка"
        // Подсвечиваем иконку оранжевым когда есть добавленные элементы
        val tintColor = if (count > 0) 0xFFFF8C00.toInt() else 0xFF545454.toInt()
        binding.btnAddElement.imageTintList =
            android.content.res.ColorStateList.valueOf(tintColor)
    }

    private fun showDeleteElementDialog(elem: CeilingElement) {
        AlertDialog.Builder(this)
            .setTitle("Удалить элемент")
            .setMessage("Удалить «${elem.type.label}» с чертежа?")
            .setPositiveButton("Удалить") { _, _ ->
                binding.blueprintView.ceilingElements.remove(elem)
                room.ceilingElements.remove(elem)
                storage.saveRoom(project.id, room)
                updateElementCount()
                binding.blueprintView.invalidate()
                Snackbar.make(binding.root, "Элемент удалён", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun applyAutoDiagonals() {
        // Используем триангуляцию: ровно n-3 диагоналей, как в официальном приложении
        val diagPairs = engine.triangulationDiagonals(binding.blueprintView.points)
        binding.blueprintView.diagonals.clear()
        diagPairs.forEach { (fromId, toId) ->
            binding.blueprintView.diagonals.add(
                DrawLine(fromId = fromId, toId = toId, isDiagonal = true)
            )
        }
        room.diagonals.clear()
        room.diagonals.addAll(binding.blueprintView.diagonals)
        binding.blueprintView.invalidate()
        updateStats()
    }

    /**
     * Показывает или скрывает нумпад, одновременно сдвигая баннеры вверх,
     * чтобы они были видны поверх клавиатуры (которая теперь — overlay на чертеже).
     */
    private fun setNumpadVisible(show: Boolean) {
        binding.numpadContainer.visibility = if (show) View.VISIBLE else View.GONE

        // Нумпад занимает ~195dp над тулбаром (56dp); баннеры должны быть выше нумпада
        val numpadHeightDp = if (show) 56 + 195 else 56
        val px = (numpadHeightDp * resources.displayMetrics.density).toInt()

        listOf(binding.redBanner, binding.doneBanner, binding.elementModeBar).forEach { view ->
            val lp = view.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.bottomMargin = px
            view.layoutParams = lp
        }
    }

    private fun startDimensionInput() {
        // Всегда генерируем ровно n-3 диагоналей (триангуляция), как в официальном приложении
        applyAutoDiagonals()

        isDimensionMode = true
        allConstraints.clear()
        allConstraints.addAll(binding.blueprintView.lines)
        allConstraints.addAll(binding.blueprintView.diagonals)

        currentDimIndex = 0
        inputBuffer = ""
        setNumpadVisible(true)
        binding.redBanner.visibility = View.VISIBLE
        showCurrentPrompt()
    }

    private fun showCurrentPrompt() {
        if (currentDimIndex >= allConstraints.size) {
            finishDimensionInput()
            return
        }
        val constraint = allConstraints[currentDimIndex]
        val pointMap = binding.blueprintView.points.associateBy { it.id }
        val fromLabel = pointMap[constraint.fromId]?.label ?: "?"
        val toLabel   = pointMap[constraint.toId]?.label   ?: "?"

        if (constraint.isDiagonal) {
            showDiagonalPrompt(constraint, fromLabel, toLabel)
        } else {
            binding.redBanner.setBackgroundColor(android.graphics.Color.parseColor("#F44336"))
            binding.redBanner.text = getString(R.string.specify_length, fromLabel, toLabel)
            binding.tvDimLabel.text = "Длина $fromLabel–$toLabel, см"
        }

        binding.tvDimDisplay.text = if (inputBuffer.isEmpty()) "0" else inputBuffer
        binding.blueprintView.activeLineId = constraint.id
        binding.blueprintView.activeDimIndex = currentDimIndex
        binding.blueprintView.invalidate()
    }

    private fun showDiagonalPrompt(constraint: DrawLine, fromLabel: String, toLabel: String) {
        val totalDiags = allConstraints.count { it.isDiagonal }
        val diagsDone = allConstraints.take(currentDimIndex).count { it.isDiagonal }
        val current = diagsDone + 1
        binding.redBanner.setBackgroundColor(android.graphics.Color.parseColor("#FF8C00"))
        binding.redBanner.text = "ДИАГОНАЛЬ $fromLabel–$toLabel  ($current/$totalDiags, ✓ пропустить)"
        binding.tvDimLabel.text = "Диаг. $fromLabel–$toLabel, см"
        binding.tvDimDisplay.text = if (inputBuffer.isEmpty()) "0" else inputBuffer
        binding.blueprintView.activeLineId = constraint.id
        binding.blueprintView.activeDimIndex = currentDimIndex
        binding.blueprintView.invalidate()
    }

    private fun confirmDimension() {
        // Режим редактирования размеров по тапу — отдельная ветка
        if (isDimEditModeActive) {
            val line = editingLine ?: run {
                setNumpadVisible(false)
                return
            }
            val value = inputBuffer.toDoubleOrNull()
            if (value == null || value <= 0) {
                Toast.makeText(this, "Введите корректный размер", Toast.LENGTH_SHORT).show()
                return
            }
            line.measuredLength = value
            inputBuffer = ""
            binding.tvDimDisplay.text = "0"
            binding.tvDimLabel.text = ""
            editingLine = null
            binding.blueprintView.activeLineId = null
            setNumpadVisible(false)
            if (line.isDiagonal) {
                // Диагональ — только обновляем метку, точки не трогаем
                saveDiagonalLabel()
            } else {
                // Стена — пересчитываем по длинам стен (без диагоналей)
                applyWallCorrection()
            }
            return
        }

        if (!isDimensionMode || currentDimIndex >= allConstraints.size) return

        val constraint = allConstraints[currentDimIndex]
        val value = inputBuffer.toDoubleOrNull()

        if (value == null || value <= 0) {
            if (constraint.isDiagonal) {
                inputBuffer = ""
                binding.tvDimDisplay.text = "0"
                currentDimIndex++
                if (currentDimIndex >= allConstraints.size) {
                    finishDimensionInput()
                } else {
                    showCurrentPrompt()
                    binding.blueprintView.invalidate()
                }
                return
            } else {
                Toast.makeText(this, "Введите длину стороны", Toast.LENGTH_SHORT).show()
                return
            }
        }

        constraint.measuredLength = value
        inputBuffer = ""
        binding.tvDimDisplay.text = "0"
        currentDimIndex++

        // Live redraw: rebuild as far as we have measurements after every confirmed side
        // (только если автокорректировка включена)
        if (!constraint.isDiagonal && room.autoCorrection) {
            try {
                val vw = binding.blueprintView.width.toFloat().coerceAtLeast(600f)
                val vh = binding.blueprintView.height.toFloat().coerceAtLeast(600f)
                engine.correctPartial(
                    binding.blueprintView.points,
                    binding.blueprintView.lines,
                    targetW = vw * 0.85f,
                    targetH = vh * 0.85f
                )
            } catch (_: Exception) {}
        }

        if (currentDimIndex >= allConstraints.size) {
            finishDimensionInput()
        } else {
            showCurrentPrompt()
            updateStats()
            // fitToScreen сохраняет чертёж в зоне видимости после пересчёта координат
            binding.blueprintView.fitToScreen()
        }
    }

    private fun finishDimensionInput() {
        isDimensionMode = false
        setNumpadVisible(false)
        binding.redBanner.visibility = View.GONE
        binding.blueprintView.activeDimIndex = -1
        binding.blueprintView.activeLineId = null

        if (room.autoCorrection) {
            try {
                val vw = binding.blueprintView.width.toFloat().coerceAtLeast(600f)
                val vh = binding.blueprintView.height.toFloat().coerceAtLeast(600f)
                val allDiagonals = binding.blueprintView.diagonals
                val measuredDiags = allDiagonals
                    .filter { it.measuredLength != null && it.measuredLength!! > 0 }
                // Передаём ВСЕ ИЗМЕРЕННЫЕ диагонали в трилатерацию,
                // даже если пользователь измерил только часть из них.
                // Для вершин без диагонали алгоритм автоматически делает
                // fallback на угол из эскиза (directionFallback).
                // areaWithoutDiag влияет только на расчёт площади, НЕ на реконструкцию.
                val diagsForCorrection = measuredDiags
                engine.correct(
                    binding.blueprintView.points,
                    binding.blueprintView.lines,
                    diagsForCorrection,
                    targetW = vw * 0.85f,
                    targetH = vh * 0.85f
                )
            } catch (_: Exception) {}
        }

        binding.blueprintView.invalidate()
        updateRoomData()
        isAllDone = true
        showDoneBar()
        updateStats()
        binding.blueprintView.scheduleFit()
    }

    private fun showDoneBar() {
        binding.doneBanner.visibility = View.VISIBLE
        binding.doneBanner.text = getString(R.string.ready_built)
    }

    private fun updateRoomData() {
        room.points.clear()
        room.points.addAll(binding.blueprintView.points)
        room.lines.clear()
        room.lines.addAll(binding.blueprintView.lines)
        room.diagonals.clear()
        room.diagonals.addAll(binding.blueprintView.diagonals)
        room.isClosed = binding.blueprintView.isClosed

        // Считаем площадь и периметр только для замкнутой фигуры
        // (при незамкнутой фигуре данные ненадёжны)
        if (room.isClosed && room.lines.isNotEmpty()) {
            val perim = engine.calculatePerimeter(room.lines)
            room.perimeter = perim / 100.0

            // autoTriangle = true  → веерная триангуляция (Shoelace) — точный метод
            // autoTriangle = false → Shoelace без нормировки по периметру (грубее,
            //                        но не зависит от точности измерений сторон)
            room.area = if (room.autoTriangle) {
                engine.calculateRealAreaM2(room.points, room.lines)
            } else {
                engine.calculateRawShoelaceM2(room.points, room.lines)
            }

            // Обновляем позиции сметы с единицей кв.м
            room.estimateItems.filter { it.unit == "кв.м" }.forEach { it.quantity = room.area }
        }

        storage.saveRoom(project.id, room)
    }

    private fun updateStats() {
        val pts = binding.blueprintView.points
        val lns = binding.blueprintView.lines.filter { !it.isDiagonal }
        val diags = binding.blueprintView.diagonals

        val sidesText = lns.joinToString(", ") { line ->
            val fromLabel = pts.find { it.id == line.fromId }?.label ?: "?"
            val toLabel = pts.find { it.id == line.toId }?.label ?: "?"
            val len = line.measuredLength?.toInt() ?: 0
            "$fromLabel$toLabel=$len"
        }

        val diagText = diags.joinToString(", ") { d ->
            val fromLabel = pts.find { it.id == d.fromId }?.label ?: "?"
            val toLabel = pts.find { it.id == d.toId }?.label ?: "?"
            val len = d.measuredLength?.toInt() ?: 0
            "$fromLabel$toLabel=$len"
        }

        val area = if (room.area > 0) room.area else 0.0
        val perim = if (room.perimeter > 0) room.perimeter else 0.0

        binding.tvAreaPerimeter.text = getString(R.string.area_perimeter, area, perim)
        binding.tvSides.text = "${getString(R.string.sides)}$sidesText"
        binding.tvDiagonals.visibility = if (diags.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvDiagonals.text = "${getString(R.string.diagonals_label)}$diagText"
        binding.tvCorners.text = getString(R.string.corners_count, pts.size)
    }

    private fun updateCalculationTab() {
        binding.tvCalcRoomName.text = room.name
        binding.tvCalcTotal.text = "${room.totalPrice.toInt()} ₽"
        binding.tvCalcAreaValue.text = "S = ${String.format("%.2f", room.area)}"
        binding.tvCalcPerimValue.text = "P = ${String.format("%.2f", room.perimeter)}"
        binding.etRoomName.setText(room.name)
        binding.etComment.setText(room.comment)

        binding.calcItemsContainer.removeAllViews()
        room.estimateItems.forEachIndexed { index, item ->
            val row = layoutInflater.inflate(R.layout.item_calc_row, binding.calcItemsContainer, false)
            row.findViewById<TextView>(R.id.tvItemName).text = item.name
            row.findViewById<TextView>(R.id.tvItemPrice).text = "${(item.pricePerUnit * item.quantity).toInt()} ₽"
            row.findViewById<TextView>(R.id.tvItemUnit).text =
                if (item.unit == "кв.м") "${item.pricePerUnit.toInt()} ₽ / кв.м"
                else "${item.pricePerUnit.toInt()} ₽ / шт"

            val etQty = row.findViewById<EditText>(R.id.etItemQty)
            etQty.setText(
                if (item.unit == "кв.м") String.format("%.2f", item.quantity)
                else item.quantity.toInt().toString()
            )
            etQty.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val qty = s.toString().toDoubleOrNull() ?: return
                    item.quantity = qty
                    row.findViewById<TextView>(R.id.tvItemPrice).text =
                        "${(item.pricePerUnit * item.quantity).toInt()} ₽"
                    updateCalcTotal()
                    storage.saveRoom(project.id, room)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            row.findViewById<android.widget.ImageButton>(R.id.btnDeleteItem).setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Удалить позицию «${item.name}»?")
                    .setPositiveButton("Удалить") { _, _ ->
                        room.estimateItems.removeAt(index)
                        storage.saveRoom(project.id, room)
                        updateCalculationTab()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }

            binding.calcItemsContainer.addView(row)
        }

        binding.etRoomName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                room.name = s.toString()
                storage.saveRoom(project.id, room)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.etComment.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                room.comment = s.toString()
                storage.saveRoom(project.id, room)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnAddPosition.setOnClickListener {
            val intent = AddPositionActivity.start(
                this,
                intent.getStringExtra(EXTRA_PROJECT_ID)!!,
                intent.getStringExtra(EXTRA_ROOM_ID)!!
            )
            startActivity(intent)
        }
    }

    private fun updateCalcTotal() {
        binding.tvCalcTotal.text = "${room.totalPrice.toInt()} ₽"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.drawing_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            R.id.action_save -> {
                saveCurrentBlueprint()
                true
            }
            R.id.action_clear_blueprint -> {
                showClearBlueprintConfirm()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            getString(R.string.settings_auto_triangle),
            getString(R.string.settings_auto_correct),
            getString(R.string.settings_area_no_diag),
            getString(R.string.settings_auto_diag),
            getString(R.string.settings_calc_sides),
            getString(R.string.settings_show_angles),
            getString(R.string.settings_show_dims)
        )
        val checked = booleanArrayOf(
            room.autoTriangle,
            room.autoCorrection,
            room.areaWithoutDiag,
            room.autoDiagonals,
            room.calcSides,
            room.showAngles,
            room.showDimensions
        )

        AlertDialog.Builder(this)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> room.autoTriangle = isChecked
                    1 -> room.autoCorrection = isChecked
                    2 -> room.areaWithoutDiag = isChecked
                    3 -> {
                        room.autoDiagonals = isChecked
                        if (isChecked && binding.blueprintView.isClosed) {
                            applyAutoDiagonals()
                        }
                    }
                    4 -> room.calcSides = isChecked
                    5 -> {
                        room.showAngles = isChecked
                        binding.blueprintView.showAngles = isChecked
                        binding.blueprintView.invalidate()
                    }
                    6 -> {
                        room.showDimensions = isChecked
                        binding.blueprintView.showDimensions = isChecked
                        binding.blueprintView.invalidate()
                    }
                }
                storage.saveRoom(project.id, room)
            }
            .setPositiveButton(getString(R.string.ok)) { _, _ -> }
            .show()
    }

    /** Saves the current drawing state to storage at any point (mid-draw or finished). */
    private fun saveCurrentBlueprint() {
        room.points.clear()
        room.points.addAll(binding.blueprintView.points)
        room.lines.clear()
        room.lines.addAll(binding.blueprintView.lines)
        room.diagonals.clear()
        room.diagonals.addAll(binding.blueprintView.diagonals)
        room.ceilingElements.clear()
        room.ceilingElements.addAll(binding.blueprintView.ceilingElements)
        room.isClosed = binding.blueprintView.isClosed

        if (room.isClosed && room.lines.isNotEmpty()) {
            try {
                val perim = engine.calculatePerimeter(room.lines)
                room.perimeter = perim / 100.0
                room.area = if (room.autoTriangle) {
                    engine.calculateRealAreaM2(room.points, room.lines)
                } else {
                    engine.calculateRawShoelaceM2(room.points, room.lines)
                }
            } catch (_: Exception) {}
        }

        storage.saveRoom(project.id, room)
        Snackbar.make(binding.root, "Чертёж сохранён ✓", Snackbar.LENGTH_SHORT).show()
    }

    // Fix 2: clear the entire blueprint (points + lines + diagonals) without deleting the room
    private fun showClearBlueprintConfirm() {
        AlertDialog.Builder(this)
            .setTitle("Очистить чертёж")
            .setMessage("Удалить все стены и точки чертежа? Комната останется в проекте.")
            .setPositiveButton("Очистить") { _, _ -> clearBlueprint() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun clearBlueprint() {
        binding.blueprintView.points.clear()
        binding.blueprintView.lines.clear()
        binding.blueprintView.diagonals.clear()
        binding.blueprintView.ceilingElements.clear()
        binding.blueprintView.isClosed = false
        binding.blueprintView.isDrawingMode = true
        binding.blueprintView.activeDimIndex = -1
        binding.blueprintView.activeLineId = null
        binding.blueprintView.invalidate()

        isDimensionMode = false
        isAllDone = false
        allConstraints.clear()
        currentDimIndex = 0
        inputBuffer = ""

        setNumpadVisible(false)
        binding.redBanner.visibility = View.GONE
        binding.doneBanner.visibility = View.GONE

        room.points.clear()
        room.lines.clear()
        room.diagonals.clear()
        room.ceilingElements.clear()
        room.isClosed = false
        room.area = 0.0
        room.perimeter = 0.0
        storage.saveRoom(project.id, room)

        updateStats()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Редактирование размеров по тапу + добавление диагоналей
    // ─────────────────────────────────────────────────────────────────────────

    private fun showDimEditSheet() {
        if (!isAllDone) {
            Toast.makeText(this, "Сначала завершите чертёж", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(
            "✏️ Изменить размер стены или диагонали",
            "📐 Добавить диагональ"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Редактор размеров")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> enterDimEditMode()
                    1 -> showAddDiagonalDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun enterDimEditMode() {
        isDimEditModeActive = true
        binding.blueprintView.isDimEditMode = true
        binding.blueprintView.activeLineId = null
        binding.blueprintView.invalidate()
        // Подсвечиваем кнопку оранжевым чтобы было видно что режим активен
        binding.btnEditDimensions.imageTintList =
            android.content.res.ColorStateList.valueOf(0xFFFF8C00.toInt())
        Snackbar.make(binding.root,
            "Нажмите на стену или диагональ для изменения размера. Кнопка карандаш — выход.",
            Snackbar.LENGTH_LONG).show()
    }

    private fun exitDimEditMode() {
        isDimEditModeActive = false
        editingLine = null
        binding.blueprintView.isDimEditMode = false
        binding.blueprintView.activeLineId = null
        binding.blueprintView.invalidate()
        binding.btnEditDimensions.imageTintList =
            android.content.res.ColorStateList.valueOf(0xFF545454.toInt())
        setNumpadVisible(false)
        inputBuffer = ""
        binding.tvDimLabel.text = ""
    }

    /**
     * Пересчитывает позиции точек ТОЛЬКО по длинам стен (без диагоналей).
     * Диагонали никогда не используются для триатерации — они лишь метки.
     * Безопасно для любого количества углов.
     */
    private fun applyWallCorrection() {
        try {
            val vw = binding.blueprintView.width.toFloat().coerceAtLeast(600f)
            val vh = binding.blueprintView.height.toFloat().coerceAtLeast(600f)
            engine.correct(
                binding.blueprintView.points,
                binding.blueprintView.lines,
                emptyList(),          // диагонали НИКОГДА не двигают точки
                targetW = vw * 0.85f,
                targetH = vh * 0.85f
            )
        } catch (_: Exception) {}
        binding.blueprintView.invalidate()
        updateRoomData()
        binding.blueprintView.fitToScreen()
        updateStats()
        Snackbar.make(binding.root, "Размер стены обновлён ✓", Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Сохраняет диагональ как справочную метку — координаты точек НЕ меняются.
     * Чертёж никогда не может сломаться от добавления/изменения диагонали.
     */
    private fun saveDiagonalLabel() {
        room.diagonals.clear()
        room.diagonals.addAll(binding.blueprintView.diagonals)
        binding.blueprintView.invalidate()
        updateStats()
        storage.saveRoom(project.id, room)
    }

    private fun showAddDiagonalDialog() {
        val pts = binding.blueprintView.points
        if (pts.size < 3) {
            Toast.makeText(this, "Сначала нарисуйте чертёж", Toast.LENGTH_SHORT).show()
            return
        }
        // Пары точек, между которыми ещё нет прямой стены (т.е. не-смежные)
        val existingKeys = (binding.blueprintView.lines + binding.blueprintView.diagonals)
            .map { setOf(it.fromId, it.toId) }.toSet()
        val pairs = mutableListOf<Pair<DrawPoint, DrawPoint>>()
        for (i in pts.indices) {
            for (j in i + 1 until pts.size) {
                val key = setOf(pts[i].id, pts[j].id)
                if (key !in existingKeys) pairs.add(pts[i] to pts[j])
            }
        }
        if (pairs.isEmpty()) {
            Toast.makeText(this, "Все диагонали уже добавлены", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = pairs.map { (a, b) -> "${a.label} — ${b.label}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Выберите диагональ")
            .setItems(labels) { _, which ->
                val (p1, p2) = pairs[which]
                showDiagonalLengthInput(p1, p2)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDiagonalLengthInput(p1: DrawPoint, p2: DrawPoint) {
        val et = android.widget.EditText(this).apply {
            hint = "Длина ${p1.label}–${p2.label}, см"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 24, 48, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Диагональ ${p1.label} — ${p2.label}")
            .setView(et)
            .setPositiveButton("Добавить") { _, _ ->
                val len = et.text.toString().toDoubleOrNull()
                if (len != null && len > 0) {
                    // Убираем старую диагональ между этими точками (если была)
                    binding.blueprintView.diagonals.removeAll {
                        (it.fromId == p1.id && it.toId == p2.id) ||
                        (it.fromId == p2.id && it.toId == p1.id)
                    }
                    val newDiag = DrawLine(
                        fromId = p1.id,
                        toId = p2.id,
                        isDiagonal = true,
                        measuredLength = len
                    )
                    binding.blueprintView.diagonals.add(newDiag)
                    // Только сохраняем метку — точки не трогаем
                    saveDiagonalLabel()
                    Snackbar.make(binding.root,
                        "Диагональ ${p1.label}–${p2.label} добавлена ✓",
                        Snackbar.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Введите корректный размер", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
        et.requestFocus()
    }

    private fun showDeleteRoomConfirm() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage("Удалить ${room.name}?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                storage.removeRoomFromProject(project.id, room.id)
                finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onBackPressed() {
        if (isDimensionMode) {
            AlertDialog.Builder(this)
                .setMessage("Выйти без сохранения размеров?")
                .setPositiveButton("Выйти") { _, _ -> super.onBackPressed() }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else if (binding.blueprintView.elementPlacementType != null) {
            exitElementMode()
        } else {
            super.onBackPressed()
        }
    }
}
