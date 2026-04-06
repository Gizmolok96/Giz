package com.zamerpro.app.data

import java.util.UUID

enum class ProjectStage(val label: String, val colorHex: String) {
    NEW_REQUEST("Новая заявка", "#F44336"),
    CONSULTATION("Просчет и консультация", "#FF8C00"),
    MEASUREMENT("Замер", "#2196F3"),
    CONTRACT("Заключение договора", "#00BCD4"),
    THINKING("Думает", "#795548"),
    AWAITING_PAYMENT("Ожидание оплаты", "#9C27B0"),
    IN_PRODUCTION("В производстве", "#3F51B5"),
    INSTALLATION("Монтаж", "#673AB7"),
    INSTALLED("Установлен", "#4CAF50"),
    WARRANTY("Гарантийное обслуживание", "#8BC34A")
}

enum class RoomType { RECTANGLE, POLYGON, QUICK }

enum class CeilingElementType(val label: String) {
    SPOTLIGHT("Точечный светильник"),
    CHANDELIER("Люстра"),
    LINE("Линия"),
    LIGHT_LINE("Световая линия"),
    CORNICE("Потолочный карниз"),
    FLOATING_PROFILE("Парящий профиль")
}

data class CeilingElement(
    val id: String = UUID.randomUUID().toString(),
    var type: CeilingElementType = CeilingElementType.SPOTLIGHT,
    var x: Float = 0f,
    var y: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f
) {
    val isLineType: Boolean get() = type == CeilingElementType.LINE ||
        type == CeilingElementType.LIGHT_LINE ||
        type == CeilingElementType.CORNICE ||
        type == CeilingElementType.FLOATING_PROFILE
}

data class Project(
    val id: String = UUID.randomUUID().toString(),
    var number: Int = 0,
    var client: String = "",
    var phone: String = "",
    var address: String = "",
    var measureDate: String = "",
    var installDate: String = "",
    var stage: ProjectStage = ProjectStage.NEW_REQUEST,
    var discount: Double = 0.0,  // % со знаком: -10 = скидка 10%, +5 = наценка 5%
    val rooms: MutableList<Room> = mutableListOf()
) {
    /** Базовая сумма без скидки/наценки */
    val basePrice: Double get() = rooms.filter { it.includeInCalc }.sumOf { it.totalPrice }
    /** Итоговая сумма с учётом скидки/наценки */
    val totalPrice: Double get() = if (discount == 0.0) basePrice else basePrice * (1.0 + discount / 100.0)
    val totalArea: Double get() = rooms.filter { it.includeInCalc }.sumOf { it.area }
    val totalPerimeter: Double get() = rooms.filter { it.includeInCalc }.sumOf { it.perimeter }
    val name: String get() = "Проект №$number"
}

data class Room(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Комната",
    var type: RoomType = RoomType.POLYGON,
    var area: Double = 0.0,
    var perimeter: Double = 0.0,
    var comment: String = "",
    val points: MutableList<DrawPoint> = mutableListOf(),
    val lines: MutableList<DrawLine> = mutableListOf(),
    val diagonals: MutableList<DrawLine> = mutableListOf(),
    var estimateItems: MutableList<EstimateItem> = mutableListOf(),
    val ceilingElements: MutableList<CeilingElement> = mutableListOf(),
    var isClosed: Boolean = false,
    var includeInCalc: Boolean = true,
    var autoTriangle: Boolean = true,
    var autoCorrection: Boolean = true,
    var areaWithoutDiag: Boolean = true,
    var autoDiagonals: Boolean = false,
    var calcSides: Boolean = true,
    var showAngles: Boolean = false,
    var showDimensions: Boolean = true
) {
    val totalPrice: Double get() = estimateItems.sumOf { it.total }
    val extrasCount: Int get() = points.size
}

data class DrawPoint(
    val id: String = UUID.randomUUID().toString(),
    var x: Float = 0f,
    var y: Float = 0f,
    var label: String = ""
)

data class DrawLine(
    val id: String = UUID.randomUUID().toString(),
    val fromId: String = "",
    val toId: String = "",
    var measuredLength: Double? = null,
    var isDiagonal: Boolean = false
)

data class EstimateItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var pricePerUnit: Double = 0.0,
    var costPrice: Double = 0.0,
    var unit: String = "кв.м",
    var quantity: Double = 0.0
) {
    val total: Double get() = pricePerUnit * quantity
}

data class PriceListItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var pricePerUnit: Double = 0.0,
    var costPrice: Double = 0.0,
    var unit: String = "шт",
    var calcType: String = "quantity",
    var autoFill: Boolean = false
)

// ─── Salary module ────────────────────────────────────────────────────────────

data class Worker(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var position: String = "",
    val workDays: MutableList<String> = mutableListOf()  // "yyyy-MM-dd"
)

data class WorkerResult(
    val workerId: String = "",
    val workerName: String = "",
    val workerPosition: String = "",
    val workDays: Int = 0,
    val amount: Double = 0.0
)

data class SalaryRecord(
    val id: String = UUID.randomUUID().toString(),
    var date: String = "",
    var totalAmount: Double = 0.0,
    var note: String = "",
    val results: MutableList<WorkerResult> = mutableListOf()
)

// ─────────────────────────────────────────────────────────────────────────────

fun createDefaultEstimateItems(area: Double, extraCorners: Int): MutableList<EstimateItem> {
    return mutableListOf(
        EstimateItem(name = "Мат. Белый", pricePerUnit = 500.0, unit = "кв.м", quantity = area),
        EstimateItem(name = "Доп. углы", pricePerUnit = 500.0, unit = "шт", quantity = extraCorners.toDouble()),
        EstimateItem(name = "Точечные светильники", pricePerUnit = 500.0, unit = "шт", quantity = 0.0),
        EstimateItem(name = "Скидка. Общая", pricePerUnit = -1.0, unit = "шт", quantity = 0.0)
    )
}
