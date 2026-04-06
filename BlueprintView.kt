package com.zamerpro.app.ui.drawing

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.zamerpro.app.data.CeilingElement
import com.zamerpro.app.data.CeilingElementType
import com.zamerpro.app.data.DrawLine
import com.zamerpro.app.data.DrawPoint
import com.zamerpro.app.data.Room
import kotlin.math.*

class BlueprintView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var points: MutableList<DrawPoint> = mutableListOf()
    var lines: MutableList<DrawLine> = mutableListOf()
    var diagonals: MutableList<DrawLine> = mutableListOf()
    var ceilingElements: MutableList<CeilingElement> = mutableListOf()
    var isClosed = false

    var isDrawingMode = true
    var isEditMode = false
    var snapToAxes = true

    /** Режим редактирования размеров: тап по стене/диагонали вызывает onLineSelected */
    var isDimEditMode = false
    var onLineSelected: ((DrawLine) -> Unit)? = null

    /** Вызывается когда фигура замкнулась (через касание или вручную) */
    var onFigureClosed: (() -> Unit)? = null

    var activeDimIndex: Int = -1
    var activeSegHighlight = true
    var activeLineId: String? = null

    var showDimensions = true
    var showDiagonals = true
    var showAngles = false

    var elementPlacementType: CeilingElementType? = null
    private var pendingLineElement: CeilingElement? = null

    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var initialOffsetSet = false

    // Флаг: вызвать fitToScreen() при ближайшем onDraw (когда размеры гарантированно известны)
    private var needsFitOnDraw = false

    /** Запланировать fit-to-screen на момент следующего draw-прохода */
    fun scheduleFit() {
        needsFitOnDraw = true
        invalidate()
    }

    private val paintGrid = Paint().apply {
        color = Color.parseColor("#E8E8E8")
        strokeWidth = 1f
        isAntiAlias = true
    }
    private val paintLine = Paint().apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 3f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val paintLinePending = Paint().apply {
        color = Color.parseColor("#F44336")
        strokeWidth = 3f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val paintLineActive = Paint().apply {
        color = Color.parseColor("#F44336")
        strokeWidth = 4f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val paintDiagonal = Paint().apply {
        color = Color.parseColor("#FF8C00")
        strokeWidth = 2f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        strokeCap = Paint.Cap.ROUND
    }
    private val paintPoint = Paint().apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }
    private val paintPointFill = Paint().apply {
        color = Color.parseColor("#FFEBEE")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintPointOrange = Paint().apply {
        color = Color.parseColor("#FF8C00")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintLabel = Paint().apply {
        color = Color.parseColor("#1565C0")
        textSize = 28f
        isFakeBoldText = true
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val paintDimText = Paint().apply {
        color = Color.parseColor("#1976D2")
        textSize = 22f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val paintLiveDimText = Paint().apply {
        color = Color.parseColor("#1976D2")
        textSize = 20f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        alpha = 180
    }
    private val paintDiagText = Paint().apply {
        color = Color.parseColor("#FF8C00")
        textSize = 22f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val paintAngleText = Paint().apply {
        color = Color.parseColor("#9C27B0")
        textSize = 20f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val paintBackground = Paint().apply {
        color = Color.WHITE
    }


    // Ceiling element paints
    private val paintSpotlight = Paint().apply {
        color = Color.parseColor("#FFC107")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintSpotlightGlow = Paint().apply {
        color = Color.parseColor("#FFECB3")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintSpotlightCenter = Paint().apply {
        color = Color.parseColor("#FF8F00")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintChandelier = Paint().apply {
        color = Color.parseColor("#FFC107")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintChandelierStroke = Paint().apply {
        color = Color.parseColor("#FF6F00")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val paintElementLine = Paint().apply {
        color = Color.parseColor("#607D8B")
        strokeWidth = 4f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val paintLightLine = Paint().apply {
        color = Color.parseColor("#03A9F4")
        strokeWidth = 5f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val paintLightLineGlow = Paint().apply {
        color = Color.parseColor("#B3E5FC")
        strokeWidth = 10f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val paintCornice = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        strokeWidth = 7f
        isAntiAlias = true
        strokeCap = Paint.Cap.SQUARE
    }
    private val paintCorniceInner = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 3f
        isAntiAlias = true
        strokeCap = Paint.Cap.SQUARE
    }
    private val paintFloatingProfile = Paint().apply {
        color = Color.parseColor("#7E57C2")
        strokeWidth = 4f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val paintFloatingProfileInner = Paint().apply {
        color = Color.parseColor("#EDE7F6")
        strokeWidth = 2f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val paintElementDelete = Paint().apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val paintPendingLine = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        strokeWidth = 2f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        strokeCap = Paint.Cap.ROUND
    }

    private var selectedPointId: String? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    var onPointAdded: ((DrawPoint) -> Unit)? = null
    var onPointMoved: (() -> Unit)? = null
    var onTapInDimensionMode: (() -> Unit)? = null
    var onElementAdded: ((CeilingElement) -> Unit)? = null
    var onElementLongPress: ((CeilingElement) -> Unit)? = null

    // Diagonal pick mode: user taps two corners to define a diagonal
    var isDiagonalPickMode = false
        set(value) {
            field = value
            firstDiagPickPoint = null
            invalidate()
        }
    var onDiagonalFirstPicked: ((DrawPoint) -> Unit)? = null
    var onDiagonalPicked: ((DrawPoint, DrawPoint) -> Unit)? = null
    private var firstDiagPickPoint: DrawPoint? = null

    private val paintPointSelected = Paint().apply {
        color = Color.parseColor("#FF8C00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.3f, 5f)
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleLongPress(e.x, e.y)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (elementPlacementType != null) return false
                if (!isDrawingMode || isEditMode) {
                    offsetX -= distanceX
                    offsetY -= distanceY
                    invalidate()
                }
                return true
            }
        })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialOffsetSet) {
            if (points.isNotEmpty()) {
                // Есть загруженные точки — подгоняем вид под них
                fitToScreen()
            } else {
                offsetX = w * 0.15f
                offsetY = h * 0.15f
                scaleFactor = 1.5f
            }
            initialOffsetSet = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Если запланирован fit — выполняем его здесь, где width/height уже точные.
        // fitToScreen() вызовет invalidate(), что запустит следующий onDraw с правильным масштабом.
        if (needsFitOnDraw && points.isNotEmpty() && width > 0 && height > 0) {
            needsFitOnDraw = false
            fitToScreen()
            return
        }

        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBackground)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scaleFactor, scaleFactor)

        drawGrid(canvas)
        drawDiagonals(canvas)
        drawLines(canvas)
        drawCeilingElements(canvas)
        drawPoints(canvas)
        if (showAngles && isClosed && points.size >= 3) {
            drawAngles(canvas)
        }

        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val gridStep = 40f
        val viewW = (width / scaleFactor) + gridStep * 2
        val viewH = (height / scaleFactor) + gridStep * 2
        val startX = -(offsetX / scaleFactor) - gridStep
        val startY = -(offsetY / scaleFactor) - gridStep

        val startXAligned = Math.floor((startX / gridStep).toDouble()).toFloat() * gridStep
        val startYAligned = Math.floor((startY / gridStep).toDouble()).toFloat() * gridStep

        var x = startXAligned
        while (x < startX + viewW) {
            canvas.drawLine(x, startYAligned, x, startYAligned + viewH, paintGrid)
            x += gridStep
        }
        var y = startYAligned
        while (y < startY + viewH) {
            canvas.drawLine(startXAligned, y, startXAligned + viewW, y, paintGrid)
            y += gridStep
        }
    }

    /** Returns the arithmetic centroid of all current points. */
    private fun computeCentroid(): Pair<Float, Float> {
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        return Pair(cx, cy)
    }

    private fun drawLines(canvas: Canvas) {
        val pointMap = points.associateBy { it.id }
        // Centroid used to determine which side of each wall is "outside"
        val (centX, centY) = if (points.size >= 3) computeCentroid() else Pair(0f, 0f)

        lines.forEachIndexed { index, line ->
            val p1 = pointMap[line.fromId] ?: return@forEachIndexed
            val p2 = pointMap[line.toId] ?: return@forEachIndexed

            val isActive = (line.id == activeLineId) || (activeLineId == null && index == activeDimIndex)
            val paint = when {
                isActive  -> paintLineActive
                isClosed  -> paintLine
                else      -> paintLinePending
            }
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)

            if (showDimensions) {
                val midX = (p1.x + p2.x) / 2
                val midY = (p1.y + p2.y) / 2
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val pixelLen = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

                // Perpendicular unit vector (one of two sides)
                val perpX = -dy / pixelLen
                val perpY =  dx / pixelLen

                // Dot with (midpoint → centroid): positive means perpendicular points toward center
                val toCentX = centX - midX
                val toCentY = centY - midY
                val dot = perpX * toCentX + perpY * toCentY
                // Flip so we always point AWAY from the centroid (outside the polygon)
                val outSign = if (dot > 0f) -1f else 1f

                val offsetDist = 30f / scaleFactor
                val labelX = midX + perpX * outSign * offsetDist
                val labelY = midY + perpY * outSign * offsetDist

                if (line.measuredLength != null) {
                    canvas.drawText("${line.measuredLength!!.toInt()}", labelX, labelY, paintDimText)
                } else if (!isClosed) {
                    val liveText = pixelLen.toInt().toString()
                    canvas.drawText(liveText, labelX, labelY, paintLiveDimText)
                }
            }
        }
    }

    private fun drawDiagonals(canvas: Canvas) {
        if (!showDiagonals) return
        val pointMap = points.associateBy { it.id }
        val perpOffset = 28f / scaleFactor

        diagonals.forEachIndexed { i, diag ->
            val p1 = pointMap[diag.fromId] ?: return@forEachIndexed
            val p2 = pointMap[diag.toId] ?: return@forEachIndexed

            val isActive = diag.id == activeLineId
            val paint = if (isActive) paintLineActive else paintDiagonal
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)

            if (showDimensions && (diag.measuredLength ?: 0.0) > 0) {
                val midX = (p1.x + p2.x) / 2f
                val midY = (p1.y + p2.y) / 2f
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.001f)
                val perpX = -dy / len
                val perpY =  dx / len
                val sign = if (i % 2 == 0) 1f else -1f
                val labelX = midX + perpX * perpOffset * sign
                val labelY = midY + perpY * perpOffset * sign
                canvas.drawText("${diag.measuredLength!!.toInt()}", labelX, labelY, paintDiagText)
            }
        }
    }

    private fun drawCeilingElements(canvas: Canvas) {
        ceilingElements.forEach { elem ->
            when (elem.type) {
                CeilingElementType.SPOTLIGHT -> drawSpotlight(canvas, elem.x, elem.y)
                CeilingElementType.CHANDELIER -> drawChandelier(canvas, elem.x, elem.y)
                CeilingElementType.LINE -> drawElementLine(canvas, elem)
                CeilingElementType.LIGHT_LINE -> drawLightLine(canvas, elem)
                CeilingElementType.CORNICE -> drawCornice(canvas, elem)
                CeilingElementType.FLOATING_PROFILE -> drawFloatingProfile(canvas, elem)
            }
        }

        // Draw pending first point of a line element
        pendingLineElement?.let { elem ->
            val r = 8f / scaleFactor
            canvas.drawCircle(elem.x, elem.y, r, paintPendingLine)
        }
    }

    private fun drawSpotlight(canvas: Canvas, cx: Float, cy: Float) {
        val r = 14f / scaleFactor
        val rGlow = 18f / scaleFactor
        val rCenter = 5f / scaleFactor
        // Outer glow
        canvas.drawCircle(cx, cy, rGlow, paintSpotlightGlow)
        // Main circle
        canvas.drawCircle(cx, cy, r, paintSpotlight)
        // Dark center dot
        canvas.drawCircle(cx, cy, rCenter, paintSpotlightCenter)
    }

    private fun drawChandelier(canvas: Canvas, cx: Float, cy: Float) {
        val r = 18f / scaleFactor
        val rInner = 8f / scaleFactor
        val armLen = 24f / scaleFactor
        val bulbR = 5f / scaleFactor
        val save = canvas.save()
        // Main body circle
        canvas.drawCircle(cx, cy, r, paintSpotlightGlow)
        canvas.drawCircle(cx, cy, r, paintChandelierStroke)
        // Inner circle
        canvas.drawCircle(cx, cy, rInner, paintChandelier)
        // Arms (4 directions)
        val armPaint = Paint(paintChandelierStroke).apply { strokeWidth = 1.5f / scaleFactor }
        val angles = listOf(0f, 90f, 180f, 270f)
        angles.forEach { angle ->
            val rad = Math.toRadians(angle.toDouble())
            val endX = cx + (armLen * cos(rad)).toFloat()
            val endY = cy + (armLen * sin(rad)).toFloat()
            canvas.drawLine(cx, cy, endX, endY, armPaint)
            canvas.drawCircle(endX, endY, bulbR, paintChandelier)
            canvas.drawCircle(endX, endY, bulbR, paintChandelierStroke)
        }
        canvas.restoreToCount(save)
    }

    private fun drawElementLine(canvas: Canvas, elem: CeilingElement) {
        canvas.drawLine(elem.x, elem.y, elem.x2, elem.y2, paintElementLine)
        val r = 5f / scaleFactor
        canvas.drawCircle(elem.x, elem.y, r, paintElementLine)
        canvas.drawCircle(elem.x2, elem.y2, r, paintElementLine)
    }

    private fun drawLightLine(canvas: Canvas, elem: CeilingElement) {
        canvas.drawLine(elem.x, elem.y, elem.x2, elem.y2, paintLightLineGlow)
        canvas.drawLine(elem.x, elem.y, elem.x2, elem.y2, paintLightLine)
        val r = 4f / scaleFactor
        val capPaint = Paint(paintLightLine).apply { style = Paint.Style.FILL }
        canvas.drawCircle(elem.x, elem.y, r, capPaint)
        canvas.drawCircle(elem.x2, elem.y2, r, capPaint)
    }

    private fun drawCornice(canvas: Canvas, elem: CeilingElement) {
        canvas.drawLine(elem.x, elem.y, elem.x2, elem.y2, paintCornice)
        // Decorative inner line offset perpendicular
        val dx = elem.x2 - elem.x
        val dy = elem.y2 - elem.y
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = -dy / len * (4f / scaleFactor)
        val ny = dx / len * (4f / scaleFactor)
        canvas.drawLine(elem.x + nx, elem.y + ny, elem.x2 + nx, elem.y2 + ny, paintCorniceInner)
    }

    private fun drawFloatingProfile(canvas: Canvas, elem: CeilingElement) {
        val dx = elem.x2 - elem.x
        val dy = elem.y2 - elem.y
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = -dy / len * (5f / scaleFactor)
        val ny = dx / len * (5f / scaleFactor)
        // Two parallel lines
        canvas.drawLine(elem.x + nx, elem.y + ny, elem.x2 + nx, elem.y2 + ny, paintFloatingProfile)
        canvas.drawLine(elem.x - nx, elem.y - ny, elem.x2 - nx, elem.y2 - ny, paintFloatingProfile)
        // Glow fill between them
        val path = Path().apply {
            moveTo(elem.x + nx, elem.y + ny)
            lineTo(elem.x2 + nx, elem.y2 + ny)
            lineTo(elem.x2 - nx, elem.y2 - ny)
            lineTo(elem.x - nx, elem.y - ny)
            close()
        }
        canvas.drawPath(path, paintFloatingProfileInner)
        canvas.drawPath(path, Paint(paintFloatingProfile).apply { style = Paint.Style.FILL; alpha = 60 })
    }

    private fun drawPoints(canvas: Canvas) {
        val pointRadius = 16f / scaleFactor
        val labelOffset = 22f / scaleFactor

        points.forEachIndexed { idx, point ->
            when {
                isDiagonalPickMode && isClosed -> {
                    // В режиме выбора диагонали: все вершины крупные и кликабельные
                    val isFirstPicked = point.id == firstDiagPickPoint?.id
                    val r = if (isFirstPicked) pointRadius * 1.5f else pointRadius
                    val fillPaint = if (isFirstPicked) paintPointOrange else paintPointFill
                    canvas.drawCircle(point.x, point.y, r, fillPaint)
                    canvas.drawCircle(point.x, point.y, r, if (isFirstPicked) paintPointSelected else paintPoint)
                }
                isEditMode -> {
                    // В режиме редактирования все точки — большие и кликабельные
                    val isSelected = point.id == selectedPointId
                    val fillPaint = if (isSelected) paintPointOrange else paintPointFill
                    val r = if (isSelected) pointRadius * 1.4f else pointRadius
                    canvas.drawCircle(point.x, point.y, r, fillPaint)
                    canvas.drawCircle(point.x, point.y, r, if (isSelected) paintPointSelected else paintPoint)
                }
                else -> {
                    val isActive = idx == points.size - 1 && !isClosed && isDrawingMode
                    val isFirst = idx == 0
                    if (isActive || isFirst) {
                        canvas.drawCircle(point.x, point.y, pointRadius, paintPointFill)
                        canvas.drawCircle(point.x, point.y, pointRadius, paintPoint)
                    } else {
                        canvas.drawCircle(point.x, point.y, pointRadius * 0.45f, paintPointOrange)
                    }
                }
            }
            canvas.drawText(point.label, point.x, point.y - labelOffset, paintLabel)
        }
    }

    private fun drawAngles(canvas: Canvas) {
        val n = points.size
        // Larger offset so labels spread well away from vertices and don't overlap each other
        val textOffset = 72f / scaleFactor
        // Text size is kept modest and capped so PDF rendering stays readable
        paintAngleText.textSize = (20f / scaleFactor).coerceIn(10f, 36f)

        // Polygon centroid — angle labels are pushed AWAY from it (exterior side)
        val (centX, centY) = computeCentroid()

        // Определяем ориентацию обхода через формулу Shoelace.
        var shoelace = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            shoelace += points[i].x.toDouble() * points[j].y.toDouble()
            shoelace -= points[j].x.toDouble() * points[i].y.toDouble()
        }

        for (i in 0 until n) {
            val prev = points[(i - 1 + n) % n]
            val curr = points[i]
            val next = points[(i + 1) % n]

            val ax = prev.x - curr.x
            val ay = prev.y - curr.y
            val bx = next.x - curr.x
            val by = next.y - curr.y

            val dot = (ax * bx + ay * by).toDouble()
            val crossSigned = (ax * by - ay * bx).toDouble()
            val crossMag = abs(crossSigned)

            var angleDeg = Math.toDegrees(atan2(crossMag, dot)).toInt()

            val isReflex = if (shoelace > 0) crossSigned > 0 else crossSigned < 0
            if (isReflex) angleDeg = 360 - angleDeg

            // Direction from vertex toward centroid (inward)
            val toCentX = centX - curr.x
            val toCentY = centY - curr.y
            val toCentLen = sqrt(toCentX * toCentX + toCentY * toCentY).coerceAtLeast(0.001f)

            // Label placed on the EXTERIOR side — away from centroid
            val labelX = curr.x - (toCentX / toCentLen) * textOffset
            val labelY = curr.y - (toCentY / toCentLen) * textOffset

            canvas.drawText("$angleDeg°", labelX, labelY, paintAngleText)
        }
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val canvasX = (screenX - offsetX) / scaleFactor
        val canvasY = (screenY - offsetY) / scaleFactor

        // Режим выбора углов для диагонали: пользователь тапает по двум вершинам
        if (isDiagonalPickMode && isClosed) {
            val threshold = 44f / scaleFactor
            val hit = points.minByOrNull { pt ->
                val dx = pt.x - canvasX
                val dy = pt.y - canvasY
                sqrt(dx * dx + dy * dy)
            }
            if (hit != null) {
                val dx = hit.x - canvasX
                val dy = hit.y - canvasY
                if (sqrt(dx * dx + dy * dy) < threshold) {
                    val first = firstDiagPickPoint
                    if (first == null) {
                        firstDiagPickPoint = hit
                        invalidate()
                        post { onDiagonalFirstPicked?.invoke(hit) }
                    } else if (first.id != hit.id) {
                        val f = first
                        firstDiagPickPoint = null
                        isDiagonalPickMode = false
                        invalidate()
                        post { onDiagonalPicked?.invoke(f, hit) }
                    }
                }
            }
            return
        }

        // Режим редактирования размеров: ищем ближайшую стену или диагональ
        if (isDimEditMode && isClosed) {
            val allLines: List<DrawLine> = lines.toList() + diagonals.toList()
            val threshold = 60f / scaleFactor
            val found = allLines.minByOrNull { line ->
                val p1 = points.find { it.id == line.fromId } ?: return@minByOrNull Float.MAX_VALUE
                val p2 = points.find { it.id == line.toId } ?: return@minByOrNull Float.MAX_VALUE
                distToSegment(canvasX, canvasY, p1.x, p1.y, p2.x, p2.y)
            }
            if (found != null) {
                val p1 = points.find { it.id == found.fromId }
                val p2 = points.find { it.id == found.toId }
                if (p1 != null && p2 != null &&
                    distToSegment(canvasX, canvasY, p1.x, p1.y, p2.x, p2.y) < threshold) {
                    activeLineId = found.id
                    invalidate()
                    onLineSelected?.invoke(found)
                }
            }
            return
        }

        // Element placement mode
        val placement = elementPlacementType
        if (placement != null) {
            handleElementPlacement(placement, canvasX, canvasY)
            return
        }

        if (!isDrawingMode || isClosed) {
            // Проверяем тап по элементу потолка — одиночный тап позволяет удалить
            val hit = findElementAtPoint(canvasX, canvasY)
            if (hit != null) {
                onElementLongPress?.invoke(hit)
                return
            }
            onTapInDimensionMode?.invoke()
            return
        }

        if (points.size >= 3) {
            val first = points[0]
            val dist = sqrt((canvasX - first.x).let { it * it } + (canvasY - first.y).let { it * it })
            if (dist < 30f / scaleFactor) {
                closeFigure()
                return
            }
        }

        addPoint(canvasX, canvasY)
    }

    private fun handleElementPlacement(type: CeilingElementType, cx: Float, cy: Float) {
        val isLine = type == CeilingElementType.LINE ||
            type == CeilingElementType.LIGHT_LINE ||
            type == CeilingElementType.CORNICE ||
            type == CeilingElementType.FLOATING_PROFILE

        if (!isLine) {
            // Point element — place immediately
            val elem = CeilingElement(type = type, x = cx, y = cy, x2 = cx, y2 = cy)
            ceilingElements.add(elem)
            onElementAdded?.invoke(elem)
            invalidate()
        } else {
            val pending = pendingLineElement
            if (pending == null) {
                // First tap — store start point
                pendingLineElement = CeilingElement(type = type, x = cx, y = cy)
                invalidate()
            } else {
                // Second tap — complete the line element
                pending.x2 = cx
                pending.y2 = cy
                ceilingElements.add(pending)
                pendingLineElement = null
                onElementAdded?.invoke(pending)
                invalidate()
            }
        }
    }

    private fun handleLongPress(screenX: Float, screenY: Float) {
        val canvasX = (screenX - offsetX) / scaleFactor
        val canvasY = (screenY - offsetY) / scaleFactor
        findElementAtPoint(canvasX, canvasY)?.let { onElementLongPress?.invoke(it) }
    }

    /**
     * Находит элемент потолка в точке касания.
     * Зона касания увеличена до 40f для надёжного попадания пальцем.
     */
    private fun findElementAtPoint(canvasX: Float, canvasY: Float): CeilingElement? {
        val touchRadius = 40f / scaleFactor
        return ceilingElements.firstOrNull { elem ->
            if (elem.isLineType) {
                distToSegment(canvasX, canvasY, elem.x, elem.y, elem.x2, elem.y2) < touchRadius
            } else {
                val dx = canvasX - elem.x
                val dy = canvasY - elem.y
                sqrt(dx * dx + dy * dy) < touchRadius
            }
        }
    }

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        val tClamped = t.coerceIn(0f, 1f)
        val nearX = ax + tClamped * dx
        val nearY = ay + tClamped * dy
        return sqrt((px - nearX) * (px - nearX) + (py - nearY) * (py - nearY))
    }

    fun cancelPendingElement() {
        pendingLineElement = null
        invalidate()
    }

    private fun addPoint(canvasX: Float, canvasY: Float) {
        var finalX = canvasX
        var finalY = canvasY

        if (snapToAxes && points.size >= 1) {
            val last = points.last()
            val dx = abs(canvasX - last.x)
            val dy = abs(canvasY - last.y)
            val len = sqrt(dx * dx + dy * dy)
            if (len > 2f) {
                // Снэппинг срабатывает только если угол в пределах ~15° от горизонтали/вертикали
                // tan(15°) ≈ 0.268 — если меньшая сторона / большая < 0.268, это почти H или V
                val ratio = minOf(dx, dy) / maxOf(dx, dy).coerceAtLeast(0.001f)
                if (ratio < 0.268f) {
                    if (dx < dy) {
                        finalX = last.x  // вертикаль
                    } else {
                        finalY = last.y  // горизонталь
                    }
                }
                // Иначе — рисуем под углом, без снэппинга
            }
        }

        val label = if (points.size < 26) ('A' + points.size).toString()
        else "P${points.size}"

        val newPoint = DrawPoint(x = finalX, y = finalY, label = label)
        points.add(newPoint)

        if (points.size >= 2) {
            val prev = points[points.size - 2]
            lines.add(DrawLine(fromId = prev.id, toId = newPoint.id))
        }

        onPointAdded?.invoke(newPoint)
        invalidate()
    }

    private fun closeFigure() {
        if (points.size < 3) return
        lines.add(DrawLine(fromId = points.last().id, toId = points.first().id))
        isClosed = true
        isDrawingMode = false
        invalidate()
        // Уведомляем Activity — фигура замкнута, можно начинать ввод размеров
        post { onFigureClosed?.invoke() }
    }

    /**
     * Undo the last drawn point (and the line connecting it).
     * If the figure is closed, first reopens it (removes the closing line).
     */
    fun undoLastPoint() {
        if (isClosed) {
            // Remove the closing line — re-open the figure so user can continue drawing
            if (lines.isNotEmpty()) lines.removeLast()
            isClosed = false
            isDrawingMode = true
            invalidate()
            return
        }
        if (points.isEmpty()) return

        // Remove the line that ends at the last point
        if (lines.isNotEmpty()) lines.removeLast()
        // Remove the last point
        points.removeLast()
        invalidate()
    }

    fun closeFigureManually() {
        closeFigure()
    }

    fun highlightLine(index: Int) {
        activeDimIndex = index
        invalidate()
    }

    fun updatePointPositions(newPoints: List<DrawPoint>) {
        for (p in newPoints) {
            val existing = points.find { it.id == p.id }
            existing?.let {
                it.x = p.x
                it.y = p.y
            }
        }
        invalidate()
    }

    fun resetDrawing() {
        points.clear()
        lines.clear()
        diagonals.clear()
        isClosed = false
        isDrawingMode = true
        activeDimIndex = -1
        activeLineId = null
        invalidate()
    }

    fun zoomIn() {
        scaleFactor = (scaleFactor * 1.25f).coerceAtMost(5f)
        invalidate()
    }

    fun zoomOut() {
        scaleFactor = (scaleFactor / 1.25f).coerceAtLeast(0.3f)
        invalidate()
    }

    fun fitToScreen() {
        if (points.isEmpty()) return
        // Если view ещё не измерен — ждём layout и повторяем
        if (width == 0 || height == 0) {
            post { fitToScreen() }
            return
        }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val w = maxX - minX
        val h = maxY - minY
        if (w <= 0 || h <= 0) {
            // Одна точка — просто центрируем её
            scaleFactor = 2f
            offsetX = this.width / 2f - minX * scaleFactor
            offsetY = this.height / 2f - minY * scaleFactor
            invalidate()
            return
        }
        // Плотность экрана для перевода dp → px
        val d = context.resources.displayMetrics.density
        // Горизонтальные и верхний отступы: 30dp
        val mSide = 30f * d
        val mTop  = 30f * d
        // Нижний отступ учитывает перекрытия поверх BlueprintView:
        //   toolbar (56dp) + зелёный баннер (48dp) + запас (16dp) = 120dp
        val mBottom = 120f * d
        val availW = this.width.toFloat()  - mSide * 2
        val availH = this.height.toFloat() - mTop - mBottom
        scaleFactor = (availW / w).coerceAtMost(availH / h).coerceIn(0.3f, 5f)
        // Центрируем чертёж внутри доступной зоны (не всего view)
        offsetX = mSide + (availW - w * scaleFactor) / 2f - minX * scaleFactor
        offsetY = mTop  + (availH - h * scaleFactor) / 2f - minY * scaleFactor
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // В режиме редактирования — перетаскиваем точки пальцем
        if (isEditMode && points.isNotEmpty()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val canvasX = (event.x - offsetX) / scaleFactor
                    val canvasY = (event.y - offsetY) / scaleFactor
                    val touchRadius = 48f / scaleFactor
                    // Ищем ближайшую точку к месту касания
                    val hit = points.minByOrNull { pt ->
                        val dx = pt.x - canvasX
                        val dy = pt.y - canvasY
                        dx * dx + dy * dy
                    }
                    if (hit != null) {
                        val dx = hit.x - canvasX
                        val dy = hit.y - canvasY
                        if (dx * dx + dy * dy <= touchRadius * touchRadius) {
                            selectedPointId = hit.id
                            isDragging = true
                            lastTouchX = event.x
                            lastTouchY = event.y
                            invalidate()
                            return true
                        }
                    }
                    // Касание не попало ни в одну точку — разрешаем пан
                    selectedPointId = null
                    isDragging = false
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging && selectedPointId != null) {
                        val canvasX = (event.x - offsetX) / scaleFactor
                        val canvasY = (event.y - offsetY) / scaleFactor
                        val pt = points.find { it.id == selectedPointId }
                        if (pt != null) {
                            pt.x = canvasX
                            pt.y = canvasY
                            invalidate()
                        }
                        return true
                    }
                    // Пан когда тянем не за точку
                    if (!isDragging) {
                        offsetX += event.x - lastTouchX
                        offsetY += event.y - lastTouchY
                        invalidate()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging && selectedPointId != null) {
                        isDragging = false
                        selectedPointId = null
                        onPointMoved?.invoke()
                        invalidate()
                        return true
                    }
                    isDragging = false
                    selectedPointId = null
                }
            }
        }

        scaleDetector.onTouchEvent(event)
        if (!isDragging) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Off-screen rendering: draws a Room to a Canvas using the EXACT same
    // private methods as onDraw() — the result is pixel-perfect identical to
    // what the user sees in the app.
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Renders [room] directly onto [canvas] at dimensions [w]×[h].
     * Used by PdfExporter to draw directly onto a PDF page canvas.
     */
    internal fun renderRoomToCanvas(room: Room, canvas: Canvas, w: Float, h: Float) {
        val savedPoints       = this.points
        val savedLines        = this.lines
        val savedDiagonals    = this.diagonals
        val savedElements     = this.ceilingElements
        val savedIsClosed     = this.isClosed
        val savedShowDims     = this.showDimensions
        val savedShowDiags    = this.showDiagonals
        val savedShowAngles   = this.showAngles
        val savedScale        = scaleFactor
        val savedOffX         = offsetX
        val savedOffY         = offsetY
        val savedActiveLineId = this.activeLineId
        val savedActiveDimIdx = this.activeDimIndex
        val savedIsDrawing    = this.isDrawingMode
        val savedIsEdit       = this.isEditMode
        val savedSelectedPt   = selectedPointId
        val savedFirstDiag    = firstDiagPickPoint

        try {
            this.points          = room.points.toMutableList()
            this.lines           = room.lines.toMutableList()
            this.diagonals       = room.diagonals.toMutableList()
            this.ceilingElements = room.ceilingElements.toMutableList()
            this.isClosed        = room.isClosed
            this.showDimensions  = room.showDimensions
            this.showDiagonals   = true
            this.showAngles      = room.showAngles
            this.activeLineId    = null
            this.activeDimIndex  = -1
            this.isDrawingMode   = false
            this.isEditMode      = false
            selectedPointId      = null
            firstDiagPickPoint   = null

            if (points.isEmpty()) {
                canvas.drawRect(0f, 0f, w, h, paintBackground)
                return
            }

            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val rw = (maxX - minX).coerceAtLeast(1f)
            val rh = (maxY - minY).coerceAtLeast(1f)
            val pad = minOf(w, h) * 0.13f
            val availW = w - pad * 2
            val availH = h - pad * 2
            scaleFactor = minOf(availW / rw, availH / rh).coerceIn(0.05f, 50f)
            offsetX = pad + (availW - rw * scaleFactor) / 2f - minX * scaleFactor
            offsetY = pad + (availH - rh * scaleFactor) / 2f - minY * scaleFactor

            canvas.drawRect(0f, 0f, w, h, paintBackground)

            val gridStepPx = 40f * scaleFactor
            var gx = offsetX.rem(gridStepPx).let { if (it < 0) it + gridStepPx else it }
            while (gx <= w) { canvas.drawLine(gx, 0f, gx, h, paintGrid); gx += gridStepPx }
            var gy = offsetY.rem(gridStepPx).let { if (it < 0) it + gridStepPx else it }
            while (gy <= h) { canvas.drawLine(0f, gy, w, gy, paintGrid); gy += gridStepPx }

            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scaleFactor, scaleFactor)

            drawDiagonals(canvas)
            drawLines(canvas)
            drawCeilingElements(canvas)
            drawPoints(canvas)
            if (showAngles && isClosed && points.size >= 3) {
                drawAngles(canvas)
            }

            canvas.restore()

        } finally {
            this.points          = savedPoints
            this.lines           = savedLines
            this.diagonals       = savedDiagonals
            this.ceilingElements = savedElements
            this.isClosed        = savedIsClosed
            this.showDimensions  = savedShowDims
            this.showDiagonals   = savedShowDiags
            this.showAngles      = savedShowAngles
            scaleFactor          = savedScale
            offsetX              = savedOffX
            offsetY              = savedOffY
            this.activeLineId    = savedActiveLineId
            this.activeDimIndex  = savedActiveDimIdx
            this.isDrawingMode   = savedIsDrawing
            this.isEditMode      = savedIsEdit
            selectedPointId      = savedSelectedPt
            firstDiagPickPoint   = savedFirstDiag
        }
    }

    /**
     * Renders [room] to a new [Bitmap] of size [bitmapW]×[bitmapH].
     * Used by ExportHelper for JPG export.
     */
    internal fun renderRoomToBitmap(room: Room, bitmapW: Int = 1200, bitmapH: Int = 1200): Bitmap {
        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        renderRoomToCanvas(room, Canvas(bitmap), bitmapW.toFloat(), bitmapH.toFloat())
        return bitmap
    }

    companion object {
        /** Static factory: creates a temporary off-screen view and renders the room. */
        fun renderRoom(context: Context, room: Room, bitmapW: Int = 1200, bitmapH: Int = 1200): Bitmap =
            BlueprintView(context).renderRoomToBitmap(room, bitmapW, bitmapH)

        /** Static factory: renders the room directly to an existing canvas. */
        fun drawToCanvas(context: Context, canvas: Canvas, room: Room, w: Float, h: Float) =
            BlueprintView(context).renderRoomToCanvas(room, canvas, w, h)
    }
}
