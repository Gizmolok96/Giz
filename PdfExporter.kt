package com.potolochnik.app.ui.export

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.potolochnik.app.data.Project
import com.potolochnik.app.data.Room
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object PdfExporter {

    private const val PAGE_W = 595   // A4 points width
    private const val PAGE_H = 842   // A4 points height
    private const val MARGIN = 50f
    private const val CONTENT_W = PAGE_W - MARGIN * 2

    fun export(context: Context, project: Project, summaryOnly: Boolean = false): File {
        val doc = PdfDocument()
        var pageNum = 1

        // ── Page 1: Commercial Proposal ──────────────────────────────────────
        val page1Info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create()
        val page1 = doc.startPage(page1Info)
        drawProposalPage(page1.canvas, project, summaryOnly)
        doc.finishPage(page1)

        // ── Blueprint pages: one per room ─────────────────────────────────────
        project.rooms.filter { it.includeInCalc && it.points.size >= 3 }.forEach { room ->
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create()
            val page = doc.startPage(pageInfo)
            drawBlueprintPage(page.canvas, room, project.rooms.indexOf(room) + 1)
            doc.finishPage(page)
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
        drawRow("Адрес", project.address); y += 20f
        drawRow("Телефон", ""); y += 24f
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

            // Room heading
            val paintRoomTitle = Paint().apply {
                color = Color.parseColor("#1565C0"); textSize = 16f; isFakeBoldText = true; isAntiAlias = true
            }
            val roomFinal = (room.totalPrice * multiplier).toInt()
            val roomHeading = if (summaryOnly)
                "${idx + 1}. ${room.name}  —  ${roomFinal} ₽"
            else
                "${idx + 1}. ${room.name}"
            canvas.drawText(roomHeading, MARGIN, y + 16f, paintRoomTitle)
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

            if (summaryOnly) {
                y += 10f  // small gap between rooms
            } else {
                y += 4f
                // Full table with all positions
                y = drawEstimateTable(canvas, room, y, multiplier)
                y += 16f
            }
        }

        // --- Project total summary (with discount) ---
        val discount = project.discount
        val basePrice = project.basePrice
        val finalPrice = project.totalPrice
        val paintSumLabel = Paint().apply { color = Color.parseColor("#212121"); textSize = 13f; isAntiAlias = true }
        val paintSumValue = Paint().apply { color = Color.parseColor("#212121"); textSize = 13f; isFakeBoldText = true; isAntiAlias = true }

        val tableRight = MARGIN + CONTENT_W
        val boxH = 28f
        val paintTotalBg = Paint().apply { color = Color.parseColor("#1565C0") }
        val paintTotalText = Paint().apply {
            color = Color.WHITE; textSize = 14f; isFakeBoldText = true; isAntiAlias = true
        }

        // Синяя плашка с итоговой суммой — уже с учётом скидки/наценки.
        // Скидка/наценка в документ не выводится: клиент видит только готовые цены.
        if (y < PAGE_H - 80f) {
            val totalLabel = "К оплате:"
            canvas.drawRect(MARGIN, y, tableRight, y + boxH, paintTotalBg)
            canvas.drawText(totalLabel, MARGIN + 8f, y + boxH - 8f, paintTotalText)
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

        // Header row background
        canvas.drawRect(col0, y, tableRight, y + rowH, paintBg)
        canvas.drawRect(col0, y, tableRight, y + rowH, paintBorder.apply { style = Paint.Style.STROKE })
        canvas.drawText("Товары/Услуги", col0 + 4f, y + rowH - 6f, paintHeader)
        canvas.drawText("Цена за ед.", col1 + 2f, y + rowH - 6f, paintHeader)
        canvas.drawText("Количество", col2 + 2f, y + rowH - 6f, paintHeader)
        canvas.drawText("Сумма", col3 + 2f, y + rowH - 6f, paintHeader)
        y += rowH

        // Data rows — skip legacy negative-price placeholder items (e.g. "Скидка. Общая" -1 ₽/шт with 0 qty)
        val visibleItems = room.estimateItems.filter { it.pricePerUnit >= 0 || it.quantity != 0.0 }
        visibleItems.forEachIndexed { i, item ->
            if (i % 2 == 0) {
                canvas.drawRect(col0, y, tableRight, y + rowH, Paint().apply { color = Color.WHITE })
            }
            canvas.drawRect(col0, y, tableRight, y + rowH, paintBorder.apply { style = Paint.Style.STROKE })
            val rowNum = "${i + 1}."
            val unitPriceAdj = item.pricePerUnit * multiplier
            val totalAdj = item.total * multiplier
            canvas.drawText("$rowNum ${item.name}", col0 + 4f, y + rowH - 6f, paintCell)
            canvas.drawText("${unitPriceAdj.toInt()} ₽ / ${item.unit}", col1 + 2f, y + rowH - 6f, paintCell)
            val qtyStr = if (item.quantity == item.quantity.toLong().toDouble())
                item.quantity.toLong().toString() else String.format("%.2f", item.quantity)
            canvas.drawText(qtyStr, col2 + 2f, y + rowH - 6f, paintCell)
            val totalStr = if (totalAdj < 0) "−${(-totalAdj).toInt()} ₽" else "${totalAdj.toInt()} ₽"
            canvas.drawText(totalStr, col3 + 2f, y + rowH - 6f, paintCell)
            y += rowH
        }

        // Room subtotal row — цена уже с учётом скидки/наценки проекта
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

    private fun drawBlueprintPage(canvas: Canvas, room: Room, roomIdx: Int) {
        val dateStr = SimpleDateFormat("d MMM yyyy г.", Locale("ru")).format(Date())

        // Room title
        val paintTitle = Paint().apply {
            color = Color.parseColor("#1565C0"); textSize = 18f; isFakeBoldText = true; isAntiAlias = true
        }
        canvas.drawText("$roomIdx. ${room.name}", MARGIN, MARGIN + 18f, paintTitle)

        // Dimensions summary
        val paintSmall = Paint().apply {
            color = Color.parseColor("#424242"); textSize = 12f; isAntiAlias = true
        }
        var infoY = MARGIN + 40f
        val areaStr = "S = ${String.format("%.2f", room.area)} кв.м   P = ${String.format("%.2f", room.perimeter)} м"
        canvas.drawText(areaStr, MARGIN, infoY, paintSmall)
        infoY += 18f

        // Sides
        if (room.lines.isNotEmpty()) {
            val sidesStr = room.lines.mapIndexed { i, l ->
                val from = ('A' + i)
                val to = if (i + 1 < room.points.size) ('A' + i + 1) else 'A'
                val len = l.measuredLength?.toInt() ?: 0
                "$from$to=$len"
            }.joinToString(", ")
            canvas.drawText("Стороны: $sidesStr", MARGIN, infoY, paintSmall)
            infoY += 18f
        }

        // Diagonals
        if (room.diagonals.isNotEmpty()) {
            val diagStr = room.diagonals.mapIndexed { i, d ->
                val len = d.measuredLength?.toInt() ?: 0
                val from = ('A' + 0)
                val to = ('A' + 2 + i)
                "$from$to=$len"
            }.joinToString(", ")
            canvas.drawText("Диагонали: $diagStr", MARGIN, infoY, paintSmall)
            infoY += 18f
        }

        // Corners count
        canvas.drawText("Количество углов: ${room.points.size} шт.", MARGIN, infoY, paintSmall)
        infoY += 28f

        // Blueprint drawing area
        val bpTop = infoY
        val bpBottom = PAGE_H - 60f
        val bpLeft = MARGIN
        val bpRight = PAGE_W - MARGIN
        val bpW = bpRight - bpLeft
        val bpH = bpBottom - bpTop

        // Save canvas state and clip to blueprint area
        canvas.save()
        canvas.clipRect(bpLeft, bpTop, bpRight, bpBottom)
        canvas.translate(bpLeft, bpTop)

        BlueprintBitmapRenderer.draw(canvas, room, bpW, bpH)

        canvas.restore()

        // Footer
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
