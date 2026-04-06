package com.zamerpro.app.ui.export

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.zamerpro.app.data.Project
import com.zamerpro.app.data.Room
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object PdfExporter {

    private const val PAGE_W = 595   // A4 points width
    private const val PAGE_H = 842   // A4 points height
    private const val MARGIN = 50f
    private const val CONTENT_W = PAGE_W - MARGIN * 2

    fun export(
        context: Context,
        project: Project,
        summaryOnly: Boolean = false,
        includeBlueprints: Boolean = true,
        blueprintsOnly: Boolean = false
    ): File {
        val doc = PdfDocument()
        var pageNum = 1

        // ── Page 1: Commercial Proposal (пропускается если blueprintsOnly=true) ─
        if (!blueprintsOnly) {
            val page1Info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create()
            val page1 = doc.startPage(page1Info)
            drawProposalPage(page1.canvas, project, summaryOnly)
            doc.finishPage(page1)
        }

        // ── Blueprint pages: one per room ─────────────────────────────────────
        if (includeBlueprints || blueprintsOnly) {
            project.rooms.filter { it.includeInCalc && it.points.size >= 3 }.forEach { room ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create()
                val page = doc.startPage(pageInfo)
                drawBlueprintPage(context, page.canvas, room, project.rooms.indexOf(room) + 1)
                doc.finishPage(page)
            }
        }

        // Save to cache
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        val file = File(dir, "Замер_PRO_${project.name.replace(" ", "_")}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Page 1: Commercial proposal (estimate summary)
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawProposalPage(canvas: Canvas, project: Project, summaryOnly: Boolean = false) {
        val now = Date()
        val dateStr = SimpleDateFormat("d MMM yyyy г.", Locale("ru")).format(now)

        var y = MARGIN

        // --- Header "Коммерческое предложение" ---
        val paintGray = Paint().apply {
            color = Color.parseColor("#9E9E9E"); textSize = 14f; isAntiAlias = true
        }
        canvas.drawText("Коммерческое предложение", MARGIN, y + 14f, paintGray)
        y += 32f

        // --- Project name (blue, large) ---
        val paintTitle = Paint().apply {
            color = Color.parseColor("#1565C0"); textSize = 28f; isFakeBoldText = true; isAntiAlias = true
        }
        canvas.drawText(project.name, MARGIN, y + 28f, paintTitle)
        y += 52f

        // --- Client info block ---
        val paintLabel = Paint().apply { color = Color.parseColor("#212121"); textSize = 13f; isAntiAlias = true }
        val paintValue = Paint().apply { color = Color.parseColor("#212121"); textSize = 13f; isAntiAlias = true }

        fun drawRow(label: String, value: String) {
            canvas.drawText("$label: ", MARGIN, y, paintLabel)
            canvas.drawText(value, MARGIN + paintLabel.measureText("$label: "), y, paintValue)
        }

        drawRow("Клиент", project.client); y += 20f
        drawRow("Телефон", project.phone); y += 20f
        drawRow("Адрес", project.address); y += 24f
        y += 6f
        drawRow("Дата замера", project.measureDate); y += 20f
        drawRow("Дата монтажа", project.installDate); y += 20f
        val roomCount = project.rooms.count { it.includeInCalc }
        drawRow("Расчеты", "$roomCount шт."); y += 24f
        y += 6f
        drawRow("Комментарий", ""); y += 24f

        // Separator line
        val paintSep = Paint().apply { color = Color.parseColor("#E0E0E0"); strokeWidth = 1f }
        canvas.drawLine(MARGIN, y, MARGIN + CONTENT_W, y, paintSep)
        y += 20f

        // --- Rooms estimate tables ---
        val multiplier = 1.0 + project.discount / 100.0
        project.rooms.filter { it.includeInCalc }.forEachIndexed { idx, room ->
            if (y > PAGE_H - 200f) return  // don't overflow page 1

            // Room heading — в обоих режимах просто название без цены
            val paintRoomTitle = Paint().apply {
                color = Color.parseColor("#1565C0"); textSize = 16f; isFakeBoldText = true; isAntiAlias = true
            }
            canvas.drawText("${idx + 1}. ${room.name}", MARGIN, y + 16f, paintRoomTitle)
            y += 26f

            val paintSmall = Paint().apply { color = Color.parseColor("#616161"); textSize = 12f; isAntiAlias = true }
            canvas.drawText(
                "Площадь: ${String.format("%.2f", room.area)} кв.м / Периметр: ${String.format("%.2f", room.perimeter)} м.",
                MARGIN, y + 12f, paintSmall
            )
            y += 18f
            if (room.comment.isNotEmpty()) {
                canvas.drawText("Комментарий: ${room.comment}", MARGIN, y + 12f, paintSmall)
                y += 18f
            }

            y += 4f
            if (summaryOnly) {
                // Список позиций БЕЗ цен
                y = drawEstimateTableSummaryOnly(canvas, room, y)
                y += 16f
            } else {
                // Полная таблица с ценами
                y = drawEstimateTable(canvas, room, y, multiplier)
                y += 16f
            }
        }

        // --- Project total summary ---
        val finalPrice = project.totalPrice
        val tableRight = MARGIN + CONTENT_W
        val boxH = 28f
        val paintTotalBg = Paint().apply { color = Color.parseColor("#1565C0") }
        val paintTotalText = Paint().apply {
            color = Color.WHITE; textSize = 14f; isFakeBoldText = true; isAntiAlias = true
        }

        if (y < PAGE_H - 80f) {
            canvas.drawRect(MARGIN, y, tableRight, y + boxH, paintTotalBg)
            canvas.drawText("К оплате:", MARGIN + 8f, y + boxH - 8f, paintTotalText)
            canvas.drawText("${finalPrice.toInt()} ₽", tableRight - 80f, y + boxH - 8f, paintTotalText)
            y += boxH + 20f
        }

        // --- Footer ---
        val paintFooter = Paint().apply {
            color = Color.parseColor("#9E9E9E"); textSize = 11f; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        canvas.drawLine(MARGIN, PAGE_H - 40f, MARGIN + CONTENT_W, PAGE_H - 40f, paintSep)
        canvas.drawText(
            "Дата создания документа - $dateStr  Цены актуальны 7 календарных дней",
            PAGE_W / 2f, PAGE_H - 22f, paintFooter
        )
        val paintPageNum = Paint().apply { color = Color.parseColor("#9E9E9E"); textSize = 11f; isAntiAlias = true }
        canvas.drawText("стр. 1", MARGIN, PAGE_H - 10f, paintPageNum)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Таблица позиций БЕЗ цен (для режима "только итог")
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawEstimateTableSummaryOnly(canvas: Canvas, room: Room, startY: Float): Float {
        var y = startY
        val col0 = MARGIN
        val col1 = MARGIN + CONTENT_W * 0.08f
        val col2 = MARGIN + CONTENT_W * 0.72f
        val tableRight = MARGIN + CONTENT_W
        val rowH = 22f

        val paintHeader = Paint().apply {
            color = Color.parseColor("#424242"); textSize = 11f; isFakeBoldText = true; isAntiAlias = true
        }
        val paintCell = Paint().apply { color = Color.parseColor("#212121"); textSize = 11f; isAntiAlias = true }
        val paintBorder = Paint().apply { color = Color.parseColor("#BDBDBD"); strokeWidth = 0.7f; style = Paint.Style.STROKE }
        val paintBg = Paint().apply { color = Color.parseColor("#F5F5F5") }

        canvas.drawRect(col0, y, tableRight, y + rowH, paintBg)
        canvas.drawRect(col0, y, tableRight, y + rowH, paintBorder)
        canvas.drawText("Товары/Услуги", col1 + 2f, y + rowH - 6f, paintHeader)
        canvas.drawText("Количество", col2 + 2f, y + rowH - 6f, paintHeader)
        y += rowH

        val visibleItems = room.estimateItems.filter { it.pricePerUnit >= 0 || it.quantity != 0.0 }
        visibleItems.forEachIndexed { i, item ->
            val bgColor = if (i % 2 == 0) Color.WHITE else Color.parseColor("#FAFAFA")
            canvas.drawRect(col0, y, tableRight, y + rowH, Paint().apply { color = bgColor })
            canvas.drawRect(col0, y, tableRight, y + rowH, paintBorder)
            canvas.drawText("${i + 1}.", col0 + 3f, y + rowH - 6f, paintCell)
            canvas.drawText(item.name, col1 + 2f, y + rowH - 6f, paintCell)
            val qtyStr = if (item.quantity == item.quantity.toLong().toDouble())
                "${item.quantity.toLong()} ${item.unit}"
            else
                "${String.format("%.2f", item.quantity)} ${item.unit}"
            canvas.drawText(qtyStr, col2 + 2f, y + rowH - 6f, paintCell)
            y += rowH
        }
        return y
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Полная таблица с ценами
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawEstimateTable(canvas: Canvas, room: Room, startY: Float, multiplier: Double = 1.0): Float {
        var y = startY
        val col0 = MARGIN
        val col1 = MARGIN + CONTENT_W * 0.46f
        val col2 = MARGIN + CONTENT_W * 0.65f
        val col3 = MARGIN + CONTENT_W * 0.82f
        val rowH = 22f
        val tableRight = MARGIN + CONTENT_W

        val paintHeader = Paint().apply {
            color = Color.parseColor("#424242"); textSize = 11f; isFakeBoldText = true; isAntiAlias = true
        }
        val paintCell = Paint().apply { color = Color.parseColor("#212121"); textSize = 11f; isAntiAlias = true }
        val paintBorder = Paint().apply { color = Color.parseColor("#BDBDBD"); strokeWidth = 0.7f }
        val paintBg = Paint().apply { color = Color.parseColor("#F5F5F5") }

        canvas.drawRect(col0, y, tableRight, y + rowH, paintBg)
        canvas.drawRect(col0, y, tableRight, y + rowH, paintBorder.apply { style = Paint.Style.STROKE })
        canvas.drawText("Товары/Услуги", col0 + 4f, y + rowH - 6f, paintHeader)
        canvas.drawText("Цена за ед.", col1 + 2f, y + rowH - 6f, paintHeader)
        canvas.drawText("Количество", col2 + 2f, y + rowH - 6f, paintHeader)
        canvas.drawText("Сумма", col3 + 2f, y + rowH - 6f, paintHeader)
        y += rowH

        val visibleItems = room.estimateItems.filter { it.pricePerUnit >= 0 || it.quantity != 0.0 }
        visibleItems.forEachIndexed { i, item ->
            if (i % 2 == 0) {
                canvas.drawRect(col0, y, tableRight, y + rowH, Paint().apply { color = Color.WHITE })
            }
            canvas.drawRect(col0, y, tableRight, y + rowH, paintBorder.apply { style = Paint.Style.STROKE })
            val unitPriceAdj = item.pricePerUnit * multiplier
            val totalAdj = item.total * multiplier
            canvas.drawText("${i + 1}. ${item.name}", col0 + 4f, y + rowH - 6f, paintCell)
            canvas.drawText("${unitPriceAdj.toInt()} ₽ / ${item.unit}", col1 + 2f, y + rowH - 6f, paintCell)
            val qtyStr = if (item.quantity == item.quantity.toLong().toDouble())
                item.quantity.toLong().toString() else String.format("%.2f", item.quantity)
            canvas.drawText(qtyStr, col2 + 2f, y + rowH - 6f, paintCell)
            val totalStr = if (totalAdj < 0) "−${(-totalAdj).toInt()} ₽" else "${totalAdj.toInt()} ₽"
            canvas.drawText(totalStr, col3 + 2f, y + rowH - 6f, paintCell)
            y += rowH
        }

        val roomTotal = visibleItems.filter { it.pricePerUnit >= 0 }.sumOf { it.total * multiplier }
        val paintTotalBg = Paint().apply { color = Color.parseColor("#EEEEEE") }
        canvas.drawRect(col0, y, tableRight, y + rowH, paintTotalBg)
        canvas.drawRect(col0, y, tableRight, y + rowH, paintBorder.apply { style = Paint.Style.STROKE })
        canvas.drawText("Итого", col0 + 4f, y + rowH - 6f, paintHeader)
        canvas.drawText("${roomTotal.toInt()} ₽", col3 + 2f, y + rowH - 6f, paintHeader)
        y += rowH

        return y
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Blueprint page
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawBlueprintPage(context: Context, canvas: Canvas, room: Room, roomIdx: Int) {
        val dateStr = SimpleDateFormat("d MMM yyyy г.", Locale("ru")).format(Date())

        val paintTitle = Paint().apply {
            color = Color.parseColor("#1565C0"); textSize = 18f; isFakeBoldText = true; isAntiAlias = true
        }
        canvas.drawText("$roomIdx. ${room.name}", MARGIN, MARGIN + 18f, paintTitle)

        val paintSmall = Paint().apply {
            color = Color.parseColor("#424242"); textSize = 12f; isAntiAlias = true
        }
        var infoY = MARGIN + 40f
        val areaStr = "S = ${String.format("%.2f", room.area)} кв.м   P = ${String.format("%.2f", room.perimeter)} м"
        canvas.drawText(areaStr, MARGIN, infoY, paintSmall)
        infoY += 18f

        // Helper: draws text with word-wrap at ", " boundaries, fitting within CONTENT_W pixels.
        // Returns the new infoY after drawing.
        fun drawWrapped(text: String, startY: Float): Float {
            var y = startY
            if (paintSmall.measureText(text) <= CONTENT_W) {
                canvas.drawText(text, MARGIN, y, paintSmall)
                y += 18f
            } else {
                val tokens = text.split(", ")
                var currentLine = ""
                tokens.forEach { token ->
                    val candidate = if (currentLine.isEmpty()) token else "$currentLine, $token"
                    if (paintSmall.measureText(candidate) > CONTENT_W && currentLine.isNotEmpty()) {
                        canvas.drawText(currentLine, MARGIN, y, paintSmall)
                        y += 16f
                        currentLine = token
                    } else {
                        currentLine = candidate
                    }
                }
                if (currentLine.isNotEmpty()) {
                    canvas.drawText(currentLine, MARGIN, y, paintSmall)
                    y += 16f
                }
                y += 2f
            }
            return y
        }

        if (room.lines.isNotEmpty()) {
            val sidesStr = room.lines.mapIndexed { i, l ->
                val from = ('A' + i)
                val to = if (i + 1 < room.points.size) ('A' + i + 1) else 'A'
                val len = l.measuredLength?.toInt() ?: 0
                "$from$to=$len"
            }.joinToString(", ")
            infoY = drawWrapped("Стороны: $sidesStr", infoY)
        }

        val ptMap = room.points.associateBy { it.id }
        val realDiags = room.diagonals
            .filter { (it.measuredLength ?: 0.0) > 0 }
            .sortedWith(Comparator { a, b ->
                val aF = ptMap[a.fromId]?.label ?: ""; val aT = ptMap[a.toId]?.label ?: ""
                val bF = ptMap[b.fromId]?.label ?: ""; val bT = ptMap[b.toId]?.label ?: ""
                val aKey = if (aF <= aT) "$aF$aT" else "$aT$aF"
                val bKey = if (bF <= bT) "$bF$bT" else "$bT$bF"
                aKey.compareTo(bKey)
            })
        if (realDiags.isNotEmpty()) {
            val diagStr = realDiags.map { d ->
                val f = ptMap[d.fromId]?.label ?: "?"; val t = ptMap[d.toId]?.label ?: "?"
                val lA = if (f <= t) f else t; val lB = if (f <= t) t else f
                "$lA$lB=${d.measuredLength!!.toInt()}"
            }.joinToString(", ")
            infoY = drawWrapped("Диагонали: $diagStr", infoY)
        }

        canvas.drawText("Количество углов: ${room.points.size} шт.", MARGIN, infoY, paintSmall)
        infoY += 28f

        val bpTop = infoY
        val bpBottom = PAGE_H - 60f
        val bpLeft = MARGIN
        val bpRight = PAGE_W - MARGIN
        val bpW = bpRight - bpLeft
        val bpH = bpBottom - bpTop

        canvas.save()
        canvas.clipRect(bpLeft, bpTop, bpRight, bpBottom)
        canvas.translate(bpLeft, bpTop)
        BlueprintBitmapRenderer.draw(context, canvas, room, bpW, bpH)
        canvas.restore()

        val paintFooter = Paint().apply {
            color = Color.parseColor("#9E9E9E"); textSize = 11f; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        val paintSep = Paint().apply { color = Color.parseColor("#E0E0E0"); strokeWidth = 1f }
        canvas.drawLine(MARGIN, PAGE_H - 40f, PAGE_W - MARGIN, PAGE_H - 40f, paintSep)
        canvas.drawText(
            "Дата создания документа - $dateStr  Цены актуальны 7 календарных дней",
            PAGE_W / 2f, PAGE_H - 22f, paintFooter
        )
        val paintPageNum = Paint().apply { color = Color.parseColor("#9E9E9E"); textSize = 11f; isAntiAlias = true }
        canvas.drawText("стр. ${roomIdx + 1}", MARGIN, PAGE_H - 10f, paintPageNum)
    }
}
