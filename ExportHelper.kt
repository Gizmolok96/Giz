package com.potolochnik.app.ui.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.potolochnik.app.data.Project
import java.io.File

object ExportHelper {

    private const val AUTHORITY = "com.potolochnik.app.fileprovider"

    fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        val uri: Uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    fun exportPdf(context: Context, project: Project) {
        val file = PdfExporter.export(context, project, summaryOnly = false)
        shareFile(context, file, "application/pdf", "Отправить PDF")
    }

    fun exportPdfSummary(context: Context, project: Project) {
        val file = PdfExporter.export(context, project, summaryOnly = true)
        shareFile(context, file, "application/pdf", "Отправить PDF")
    }

    fun exportJpg(context: Context, project: Project) {
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()

        val rooms = project.rooms.filter { it.includeInCalc && it.points.size >= 3 }
        if (rooms.isEmpty()) {
            exportText(context, project)
            return
        }

        if (rooms.size == 1) {
            // Single image
            val bitmap = BlueprintBitmapRenderer.renderRoom(rooms[0])
            val file = File(dir, "Чертеж_${rooms[0].name.replace(" ", "_")}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            shareFile(context, file, "image/jpeg", "Отправить JPG")
        } else {
            // Multiple images via ACTION_SEND_MULTIPLE
            val uris = ArrayList<Uri>()
            rooms.forEach { room ->
                val bitmap = BlueprintBitmapRenderer.renderRoom(room)
                val file = File(dir, "Чертеж_${room.name.replace(" ", "_")}.jpg")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                uris.add(FileProvider.getUriForFile(context, AUTHORITY, file))
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Отправить JPG"))
        }
    }

    fun exportText(context: Context, project: Project) {
        val sb = buildTextExport(project)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить текст"))
    }

    fun exportTxt(context: Context, project: Project) {
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        val text = buildTextExport(project)
        val file = File(dir, "Замер_PRO_${project.name.replace(" ", "_")}.txt")
        file.writeText(text)
        shareFile(context, file, "text/plain", "Отправить TXT")
    }

    fun exportXml(context: Context, project: Project) {
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        val xml = buildXmlExport(project)
        val file = File(dir, "Замер_PRO_${project.name.replace(" ", "_")}.xml")
        file.writeText(xml)
        shareFile(context, file, "text/xml", "Отправить XML")
    }

    private fun buildTextExport(project: Project): String {
        val sb = StringBuilder()
        // Множитель скидки/наценки: применяется к каждой позиции.
        // В текст клиенту не выводим строку скидки — только готовые цены.
        val multiplier = 1.0 + project.discount / 100.0
        sb.appendLine("═══════════════════════════════")
        sb.appendLine(project.name)
        if (project.client.isNotEmpty()) sb.appendLine("Клиент: ${project.client}")
        if (project.address.isNotEmpty()) sb.appendLine("Адрес: ${project.address}")
        if (project.measureDate.isNotEmpty()) sb.appendLine("Дата замера: ${project.measureDate}")
        if (project.installDate.isNotEmpty()) sb.appendLine("Дата монтажа: ${project.installDate}")
        sb.appendLine("Итого: ${project.totalPrice.toInt()} ₽")
        sb.appendLine("S = ${String.format("%.2f", project.totalArea)} кв.м  P = ${String.format("%.2f", project.totalPerimeter)} м")
        sb.appendLine("═══════════════════════════════")
        sb.appendLine()
        project.rooms.filter { it.includeInCalc }.forEachIndexed { i, room ->
            sb.appendLine("${i + 1}. ${room.name}")
            sb.appendLine("S = ${String.format("%.2f", room.area)} кв.м  P = ${String.format("%.2f", room.perimeter)} м")
            if (room.comment.isNotEmpty()) sb.appendLine("Комментарий: ${room.comment}")
            sb.appendLine()
            room.estimateItems.forEachIndexed { j, item ->
                val unitPriceAdj = (item.pricePerUnit * multiplier).toInt()
                val totalAdj = (item.total * multiplier).toInt()
                sb.appendLine("  ${j + 1}. ${item.name}")
                sb.appendLine("       $unitPriceAdj ₽ × ${String.format("%.2f", item.quantity)} ${item.unit} = $totalAdj ₽")
            }
            sb.appendLine("  Итого: ${(room.totalPrice * multiplier).toInt()} ₽")
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun buildXmlExport(project: Project): String {
        // Все цены в XML — уже с учётом скидки/наценки (как в PDF и тексте).
        val multiplier = 1.0 + project.discount / 100.0
        val exportDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.appendLine("<project>")
        sb.appendLine("  <name>${project.name}</name>")
        sb.appendLine("  <client>${project.client}</client>")
        sb.appendLine("  <address>${project.address}</address>")
        sb.appendLine("  <measureDate>${project.measureDate}</measureDate>")
        sb.appendLine("  <installDate>${project.installDate}</installDate>")
        sb.appendLine("  <exportDate>$exportDate</exportDate>")
        sb.appendLine("  <totalArea>${String.format("%.2f", project.totalArea)}</totalArea>")
        sb.appendLine("  <totalPerimeter>${String.format("%.2f", project.totalPerimeter)}</totalPerimeter>")
        sb.appendLine("  <totalPrice>${project.totalPrice.toInt()}</totalPrice>")
        sb.appendLine("  <rooms>")
        project.rooms.filter { it.includeInCalc }.forEachIndexed { idx, room ->
            sb.appendLine("    <room number=\"${idx + 1}\">")
            sb.appendLine("      <name>${room.name}</name>")
            sb.appendLine("      <area>${String.format("%.2f", room.area)}</area>")
            sb.appendLine("      <perimeter>${String.format("%.2f", room.perimeter)}</perimeter>")
            if (room.comment.isNotEmpty()) sb.appendLine("      <comment>${room.comment}</comment>")
            sb.appendLine("      <items>")
            room.estimateItems.filter { it.pricePerUnit >= 0 }.forEachIndexed { i, item ->
                val unitPriceAdj = (item.pricePerUnit * multiplier).toInt()
                val totalAdj = (item.total * multiplier).toInt()
                val qty = if (item.quantity == item.quantity.toLong().toDouble())
                    item.quantity.toLong().toString() else String.format("%.2f", item.quantity)
                sb.appendLine("        <item number=\"${i + 1}\">")
                sb.appendLine("          <name>${item.name}</name>")
                sb.appendLine("          <pricePerUnit>$unitPriceAdj</pricePerUnit>")
                sb.appendLine("          <unit>${item.unit}</unit>")
                sb.appendLine("          <quantity>$qty</quantity>")
                sb.appendLine("          <total>$totalAdj</total>")
                sb.appendLine("        </item>")
            }
            sb.appendLine("      </items>")
            sb.appendLine("      <totalPrice>${(room.totalPrice * multiplier).toInt()}</totalPrice>")
            sb.appendLine("    </room>")
        }
        sb.appendLine("  </rooms>")
        sb.appendLine("</project>")
        return sb.toString()
    }
}
