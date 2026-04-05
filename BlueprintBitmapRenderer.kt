package com.potolochnik.app.ui.export

import android.graphics.*
import com.potolochnik.app.data.*
import kotlin.math.*

object BlueprintBitmapRenderer {

    fun renderRoom(room: Room, bitmapW: Int = 900, bitmapH: Int = 900): Bitmap {
        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas, room, bitmapW.toFloat(), bitmapH.toFloat())
        return bitmap
    }

    fun draw(canvas: Canvas, room: Room, w: Float, h: Float) {
        val points = room.points
        val lines = room.lines
        val diagonals = room.diagonals
        val elements = room.ceilingElements

        // Background
        canvas.drawRect(0f, 0f, w, h, Paint().apply { color = Color.WHITE })

        // Grid
        val paintGrid = Paint().apply {
            color = Color.parseColor("#E8E8E8")
            strokeWidth = 1f
            isAntiAlias = true
        }
        val gridStep = 40f
        var gx = 0f
        while (gx <= w) { canvas.drawLine(gx, 0f, gx, h, paintGrid); gx += gridStep }
        var gy = 0f
        while (gy <= h) { canvas.drawLine(0f, gy, w, gy, paintGrid); gy += gridStep }

        if (points.isEmpty()) return

        // Compute scale / offset to fit blueprint into the bitmap
        val margin = 80f
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val pw = maxX - minX
        val ph = maxY - minY
        val scale = if (pw > 0 && ph > 0)
            minOf((w - margin * 2) / pw, (h - margin * 2) / ph)
        else 1f
        val offX = (w - pw * scale) / 2f - minX * scale
        val offY = (h - ph * scale) / 2f - minY * scale

        fun tx(x: Float) = x * scale + offX
        fun ty(y: Float) = y * scale + offY

        // Paints
        val paintLine = Paint().apply {
            color = Color.parseColor("#4CAF50"); strokeWidth = 3f; isAntiAlias = true
            strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
        }
        val paintDiagonal = Paint().apply {
            color = Color.parseColor("#FF8C00"); strokeWidth = 2f; isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f); strokeCap = Paint.Cap.ROUND
        }
        val paintPointStroke = Paint().apply {
            color = Color.parseColor("#F44336"); style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true
        }
        val paintPointFill = Paint().apply {
            color = Color.parseColor("#FFEBEE"); style = Paint.Style.FILL; isAntiAlias = true
        }
        val paintLabel = Paint().apply {
            color = Color.parseColor("#1565C0"); textSize = 28f; isFakeBoldText = true
            isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        val paintDimText = Paint().apply {
            color = Color.parseColor("#1976D2"); textSize = 22f; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        val paintDiagText = Paint().apply {
            color = Color.parseColor("#FF8C00"); textSize = 22f; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }

        // Build point map
        val ptMap = points.associateBy { it.id }

        // Draw diagonal lines
        if (room.autoDiagonals || diagonals.isNotEmpty()) {
            diagonals.forEach { diag ->
                val a = ptMap[diag.fromId] ?: return@forEach
                val b = ptMap[diag.toId] ?: return@forEach
                canvas.drawLine(tx(a.x), ty(a.y), tx(b.x), ty(b.y), paintDiagonal)
                diag.measuredLength?.let { len ->
                    val mx = (tx(a.x) + tx(b.x)) / 2f
                    val my = (ty(a.y) + ty(b.y)) / 2f
                    canvas.drawText(len.toInt().toString(), mx, my + 7f, paintDiagText)
                }
            }
        }

        // Draw polygon lines
        val closingLine = if (room.isClosed && points.size >= 3)
            listOf(DrawLine(fromId = points.last().id, toId = points.first().id))
        else emptyList()
        val allLines = lines + closingLine

        allLines.forEachIndexed { i, line ->
            val a = ptMap[line.fromId] ?: return@forEachIndexed
            val b = ptMap[line.toId] ?: return@forEachIndexed
            canvas.drawLine(tx(a.x), ty(a.y), tx(b.x), ty(b.y), paintLine)

            // Dimension text on side
            if (room.showDimensions && i < lines.size) {
                lines.getOrNull(i)?.measuredLength?.let { len ->
                    val mx = (tx(a.x) + tx(b.x)) / 2f
                    val my = (ty(a.y) + ty(b.y)) / 2f
                    val dx = tx(b.x) - tx(a.x)
                    val dy = ty(b.y) - ty(a.y)
                    val angle = atan2(dy, dx)
                    val perpX = -sin(angle) * 22f
                    val perpY = cos(angle) * 22f
                    canvas.drawText(len.toInt().toString(), mx + perpX, my + perpY + 7f, paintDimText)
                }
            }
        }

        // Draw points
        val ptRadius = 10f
        points.forEachIndexed { i, pt ->
            canvas.drawCircle(tx(pt.x), ty(pt.y), ptRadius, paintPointFill)
            canvas.drawCircle(tx(pt.x), ty(pt.y), ptRadius, paintPointStroke)
            val label = if (pt.label.isNotEmpty()) pt.label else ('A' + i).toString()
            val labelOffX = when {
                tx(pt.x) < w / 3 -> -18f
                tx(pt.x) > w * 2 / 3 -> 18f
                else -> 0f
            }
            val labelOffY = when {
                ty(pt.y) < h / 3 -> -18f
                ty(pt.y) > h * 2 / 3 -> 18f
                else -> -18f
            }
            canvas.drawText(label, tx(pt.x) + labelOffX, ty(pt.y) + labelOffY, paintLabel)
        }

        // Draw ceiling elements
        drawCeilingElements(canvas, elements, scale, offX, offY)
    }

    private fun drawCeilingElements(
        canvas: Canvas,
        elements: List<CeilingElement>,
        scale: Float,
        offX: Float,
        offY: Float
    ) {
        fun tx(x: Float) = x * scale + offX
        fun ty(y: Float) = y * scale + offY

        val paintSpotFill = Paint().apply { color = Color.parseColor("#FFEB3B"); style = Paint.Style.FILL; isAntiAlias = true }
        val paintSpotCenter = Paint().apply { color = Color.parseColor("#FF8F00"); style = Paint.Style.FILL; isAntiAlias = true }
        val paintChandelier = Paint().apply { color = Color.parseColor("#FFEB3B"); strokeWidth = 2f; isAntiAlias = true }
        val paintLine2 = Paint().apply { color = Color.parseColor("#9E9E9E"); strokeWidth = 2.5f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
        val paintLightLine = Paint().apply { color = Color.parseColor("#42A5F5"); strokeWidth = 4f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
        val paintCornice = Paint().apply { color = Color.parseColor("#757575"); strokeWidth = 6f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
        val paintFloating = Paint().apply { color = Color.parseColor("#7E57C2"); strokeWidth = 3f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }

        elements.forEach { el ->
            val ex = tx(el.x); val ey = ty(el.y)
            val ex2 = tx(el.x2); val ey2 = ty(el.y2)
            when (el.type) {
                CeilingElementType.SPOTLIGHT -> {
                    canvas.drawCircle(ex, ey, 14f, paintSpotFill)
                    canvas.drawCircle(ex, ey, 6f, paintSpotCenter)
                }
                CeilingElementType.CHANDELIER -> {
                    val p = Paint().apply { color = Color.parseColor("#FFEB3B"); style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
                    canvas.drawCircle(ex, ey, 14f, p)
                    for (a in listOf(0, 90, 180, 270)) {
                        val rad = Math.toRadians(a.toDouble())
                        val lx = ex + cos(rad).toFloat() * 24f
                        val ly = ey + sin(rad).toFloat() * 24f
                        canvas.drawLine(ex, ey, lx, ly, paintChandelier)
                        canvas.drawCircle(lx, ly, 5f, Paint().apply { color = Color.parseColor("#FFEE58"); style = Paint.Style.FILL; isAntiAlias = true })
                    }
                }
                CeilingElementType.LINE -> canvas.drawLine(ex, ey, ex2, ey2, paintLine2)
                CeilingElementType.LIGHT_LINE -> {
                    val glow = Paint().apply { color = Color.parseColor("#42A5F5"); strokeWidth = 10f; isAntiAlias = true; alpha = 60 }
                    canvas.drawLine(ex, ey, ex2, ey2, glow)
                    canvas.drawLine(ex, ey, ex2, ey2, paintLightLine)
                }
                CeilingElementType.CORNICE -> canvas.drawLine(ex, ey, ex2, ey2, paintCornice)
                CeilingElementType.FLOATING_PROFILE -> {
                    val dx = ey2 - ey; val dy = ex - ex2
                    val len = sqrt(dx * dx + dy * dy)
                    if (len > 0) {
                        val nx = dx / len * 4; val ny = dy / len * 4
                        canvas.drawLine(ex + nx, ey + ny, ex2 + nx, ey2 + ny, paintFloating)
                        canvas.drawLine(ex - nx, ey - ny, ex2 - nx, ey2 - ny, paintFloating)
                    }
                }
            }
        }
    }
}
