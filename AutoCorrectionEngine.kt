package com.potolochnik.app.ui.drawing

import com.potolochnik.app.data.DrawLine
import com.potolochnik.app.data.DrawPoint
import kotlin.math.*

class AutoCorrectionEngine {

    companion object {
        private const val MAX_ITERATIONS = 500
        private const val TOLERANCE = 0.1f
        // Минимальная площадь (px²) после коррекции — если меньше, значит чертёж схлопнулся
        private const val MIN_AREA_PX2 = 500.0
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Reconstructs point positions from measured lengths.
     *
     * Strategy:
     * 1. All sides measured → direction-based reconstruction (correct lengths, sketch angles).
     *    If diagonals also measured → spring refinement in pixel space corrects angles.
     * 2. Incomplete sides → spring relaxation fallback.
     */
    fun correct(
        points: MutableList<DrawPoint>,
        lines: List<DrawLine>,
        diagonals: List<DrawLine>,
        targetW: Float = 700f,
        targetH: Float = 800f
    ) {
        if (points.size < 3) return

        val wallLines = lines.filter { !it.isDiagonal }
        val sideCount = wallLines.count { it.measuredLength != null && it.measuredLength!! > 0 }
        val measuredDiags = diagonals.filter { it.measuredLength != null && it.measuredLength!! > 0 }

        val savedX = points.map { it.x }
        val savedY = points.map { it.y }

        when {
            sideCount == wallLines.size -> {
                // Шаг 1: строим форму по направлениям — стороны точные, углы как в эскизе
                reconstructByDirections(points, wallLines, targetW, targetH)

                // Шаг 2: если есть измеренные диагонали — уточняем углы через
                // пружинную релаксацию по ВСЕМ ограничениям (стены + диагонали).
                // Стены удерживают правильные длины, диагонали корректируют углы.
                if (measuredDiags.isNotEmpty()) {
                    springRefineAll(points, wallLines, measuredDiags)
                }

                val resultArea = abs(calculateArea(points))
                val hasNaN = points.any { !it.x.isFinite() || !it.y.isFinite() }
                if (hasNaN || !resultArea.isFinite() || resultArea < MIN_AREA_PX2) {
                    points.forEachIndexed { i, p -> p.x = savedX[i]; p.y = savedY[i] }
                }
            }
            else -> {
                val distMap = buildDistMap(lines, diagonals)
                springRelax(points, distMap)
            }
        }
    }

    /**
     * Уточняет позиции вершин после direction-based реконструкции с помощью
     * пружинной релаксации по ВСЕМ ограничениям одновременно: стены + диагонали.
     *
     * Ключевое отличие от старого springRefineWithDiagonals:
     * - Стены тоже входят в систему пружин → они удерживают правильные длины
     *   пока диагонали корректируют углы. Без этого диагональные пружины
     *   тянут точки в нужную сторону, но стены «тянутся» и форма не меняется.
     *
     * Работает в ПИКСЕЛЬНОМ пространстве: все длины конвертируются через
     * текущий масштаб чертежа (px/cm).
     */
    private fun springRefineAll(
        points: MutableList<DrawPoint>,
        wallLines: List<DrawLine>,
        measuredDiags: List<DrawLine>
    ) {
        // Вычисляем текущий масштаб: пиксели на 1 см
        val pixelPerim = wallLines.sumOf { line ->
            val p1 = points.find { it.id == line.fromId } ?: return@sumOf 0.0
            val p2 = points.find { it.id == line.toId } ?: return@sumOf 0.0
            val dx = (p2.x - p1.x).toDouble()
            val dy = (p2.y - p1.y).toDouble()
            sqrt(dx * dx + dy * dy)
        }
        val measuredPerim = wallLines.sumOf { it.measuredLength ?: 0.0 }
        if (measuredPerim < 1e-9 || pixelPerim < 1e-9) return

        val cmToPixel = pixelPerim / measuredPerim

        // Строим единую карту целевых расстояний в пикселях: стены + диагонали
        val pixelTargets = mutableMapOf<Pair<String, String>, Float>()
        for (line in wallLines) {
            val len = line.measuredLength ?: continue
            if (len <= 0) continue
            pixelTargets[line.fromId to line.toId] = (len * cmToPixel).toFloat()
        }
        for (diag in measuredDiags) {
            val len = diag.measuredLength ?: continue
            if (len <= 0) continue
            pixelTargets[diag.fromId to diag.toId] = (len * cmToPixel).toFloat()
        }

        val anchorId = points[0].id
        val anchorX = points[0].x
        val anchorY = points[0].y
        val pMap = points.associateBy { it.id }.toMutableMap()

        repeat(MAX_ITERATIONS) {
            var maxErr = 0f
            for ((key, targetPx) in pixelTargets) {
                val p1 = pMap[key.first] ?: continue
                val p2 = pMap[key.second] ?: continue
                val err = applySpring(p1, p2, targetPx, anchorId, pMap)
                if (err > maxErr) maxErr = err
            }
            // Якорная точка (A) не двигается
            pMap[anchorId]?.also { it.x = anchorX; it.y = anchorY }
            if (maxErr < TOLERANCE) return
        }

        for (p in points) pMap[p.id]?.let { c -> p.x = c.x; p.y = c.y }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Direction-based reconstruction (primary method)
    // ──────────────────────────────────────────────────────────────────────────

    private fun reconstructByDirections(
        points: MutableList<DrawPoint>,
        lines: List<DrawLine>,
        targetW: Float,
        targetH: Float
    ) {
        val n = points.size
        val coords = Array(n) { 0.0 to 0.0 }
        coords[0] = 0.0 to 0.0

        for (i in 1 until n) {
            val line = lines.getOrNull(i - 1) ?: break
            val measuredLen = line.measuredLength ?: break

            val prev = points[i - 1]
            val curr = points[i]

            val dx = (curr.x - prev.x).toDouble()
            val dy = (curr.y - prev.y).toDouble()
            val origLen = sqrt(dx * dx + dy * dy)

            if (origLen < 1e-9) {
                coords[i] = coords[i - 1]
                continue
            }

            val nx = dx / origLen
            val ny = dy / origLen

            coords[i] = (coords[i - 1].first + nx * measuredLen) to
                        (coords[i - 1].second + ny * measuredLen)
        }

        scaleToScreen(points, coords, targetW, targetH)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Trilateration reconstruction
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Возвращает true если реконструкция успешна и точки были обновлены,
     * false если были NaN/Inf и точки остались без изменений.
     */
    private fun reconstructByTrilateration(
        points: MutableList<DrawPoint>,
        lines: List<DrawLine>,
        distMap: Map<Pair<String, String>, Double>,
        targetW: Float,
        targetH: Float
    ): Boolean {
        val n = points.size
        val coords = Array(n) { 0.0 to 0.0 }

        coords[0] = 0.0 to 0.0

        val ab = lookupDist(points[0], points[1], distMap)
        coords[1] = ab to 0.0

        val origWinding = computeSketchWinding(points)

        val bc = lookupDist(points[1], points[2], distMap)
        val anchorForC = findBestAnchor(2, points, distMap, placed = setOf(0, 1), exclude = 1)

        val coordC = if (anchorForC != null) {
            val (anchorIdx, anchorDist) = anchorForC
            val cands = trilaterate(coords[1], bc, coords[anchorIdx], anchorDist)
            if (cands != null) {
                if (origWinding <= 0) {
                    if (cands.first.second >= cands.second.second) cands.first else cands.second
                } else {
                    if (cands.first.second <= cands.second.second) cands.first else cands.second
                }
            } else {
                directionFallback(coords[1], points[1], points[2], bc)
            }
        } else {
            directionFallback(coords[1], points[1], points[2], bc)
        }
        coords[2] = coordC

        val windingSign = cross(coords[0], coords[1], coords[1], coords[2]).sign()

        for (i in 3 until n) {
            val sideLen = lookupDist(points[i - 1], points[i], distMap)

            val anchor = findBestAnchor(
                i, points, distMap,
                placed = (0 until i).toSet(),
                exclude = i - 1
            )

            coords[i] = if (anchor != null) {
                val (anchorIdx, anchorDist) = anchor
                val cands = trilaterate(coords[i - 1], sideLen, coords[anchorIdx], anchorDist)
                if (cands != null) {
                    val (ca, cb) = cands
                    val crossA = cross(coords[i - 2], coords[i - 1], coords[i - 1], ca)
                    val crossB = cross(coords[i - 2], coords[i - 1], coords[i - 1], cb)
                    when {
                        windingSign > 0 -> if (crossA >= crossB) ca else cb
                        windingSign < 0 -> if (crossA <= crossB) ca else cb
                        else            -> if (crossA >= 0) ca else cb
                    }
                } else {
                    directionFallback(coords[i - 1], points[i - 1], points[i], sideLen)
                }
            } else {
                directionFallback(coords[i - 1], points[i - 1], points[i], sideLen)
            }

            // Ранняя проверка: если появился NaN — немедленно выходим
            if (!coords[i].first.isFinite() || !coords[i].second.isFinite()) {
                return false
            }
        }

        return scaleToScreen(points, coords, targetW, targetH)
    }

    private fun computeSketchWinding(points: List<DrawPoint>): Double {
        var sum = 0.0
        val n = points.size
        for (i in 0 until n) {
            val cur = points[i]
            val nxt = points[(i + 1) % n]
            sum += (nxt.x - cur.x).toDouble() * (nxt.y + cur.y).toDouble()
        }
        return sum
    }

    private fun directionFallback(
        prevCoord: Pair<Double, Double>,
        prevPoint: DrawPoint,
        currPoint: DrawPoint,
        measuredLen: Double
    ): Pair<Double, Double> {
        val dx = (currPoint.x - prevPoint.x).toDouble()
        val dy = (currPoint.y - prevPoint.y).toDouble()
        val origLen = sqrt(dx * dx + dy * dy)
        if (origLen < 1e-9) return prevCoord

        val nx = dx / origLen
        val ny = dy / origLen
        return (prevCoord.first + nx * measuredLen) to
               (prevCoord.second + ny * measuredLen)
    }

    private fun findBestAnchor(
        targetIdx: Int,
        points: List<DrawPoint>,
        distMap: Map<Pair<String, String>, Double>,
        placed: Set<Int>,
        exclude: Int
    ): Pair<Int, Double>? {
        val target = points[targetIdx]
        val excPoint = points[exclude]

        var bestIdx: Int? = null
        var bestDist = Double.NEGATIVE_INFINITY

        for (idx in placed) {
            if (idx == exclude) continue
            val p = points[idx]
            val d = distMap[p.id to target.id] ?: distMap[target.id to p.id] ?: continue
            val dx = p.x - excPoint.x; val dy = p.y - excPoint.y
            val separationSq = (dx * dx + dy * dy).toDouble()
            if (separationSq > bestDist) {
                bestDist = separationSq
                bestIdx = idx
            }
        }

        return bestIdx?.let { idx ->
            val p = points[idx]
            val d = distMap[p.id to target.id] ?: distMap[target.id to p.id]!!
            idx to d
        }
    }

    /**
     * Масштабирует вычисленные координаты на экран.
     * Возвращает true если успешно, false если координаты содержат NaN/Inf.
     */
    private fun scaleToScreen(
        points: MutableList<DrawPoint>,
        coords: Array<Pair<Double, Double>>,
        targetW: Float,
        targetH: Float
    ): Boolean {
        if (coords.any { !it.first.isFinite() || !it.second.isFinite() }) return false

        val minX = coords.minOf { it.first }
        val maxX = coords.maxOf { it.first }
        val minY = coords.minOf { it.second }
        val maxY = coords.maxOf { it.second }

        val rangeX = (maxX - minX).coerceAtLeast(1.0)
        val rangeY = (maxY - minY).coerceAtLeast(1.0)

        val margin = 80.0
        val scale = minOf(
            (targetW - 2 * margin) / rangeX,
            (targetH - 2 * margin) / rangeY
        )
        val drawW = rangeX * scale
        val drawH = rangeY * scale
        val ox = margin + (targetW - 2 * margin - drawW) / 2
        val oy = margin + (targetH - 2 * margin - drawH) / 2

        for (i in points.indices) {
            points[i].x = ((coords[i].first - minX) * scale + ox).toFloat()
            points[i].y = ((coords[i].second - minY) * scale + oy).toFloat()
        }
        return true
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Spring relaxation fallback
    // ──────────────────────────────────────────────────────────────────────────

    private fun springRelax(
        points: MutableList<DrawPoint>,
        distMap: Map<Pair<String, String>, Double>
    ) {
        val anchorId = points[0].id
        val anchorX = points[0].x
        val anchorY = points[0].y
        val pMap = points.associateBy { it.id }.toMutableMap()

        repeat(MAX_ITERATIONS) {
            var maxErr = 0f
            for ((key, target) in distMap) {
                val p1 = pMap[key.first]  ?: continue
                val p2 = pMap[key.second] ?: continue
                val err = applySpring(p1, p2, target.toFloat(), anchorId, pMap)
                if (err > maxErr) maxErr = err
            }
            pMap[anchorId]?.also { it.x = anchorX; it.y = anchorY }
            if (maxErr < TOLERANCE) return
        }

        for (p in points) pMap[p.id]?.let { c -> p.x = c.x; p.y = c.y }
    }

    private fun applySpring(
        p1: DrawPoint, p2: DrawPoint,
        target: Float,
        anchorId: String,
        pMap: Map<String, DrawPoint>
    ): Float {
        val dx = p2.x - p1.x; val dy = p2.y - p1.y
        val cur = sqrt(dx * dx + dy * dy)
        if (cur < 0.001f) return 0f

        val delta = target - cur
        val d1 = distToAnchor(p1, anchorId, pMap)
        val d2 = distToAnchor(p2, anchorId, pMap)
        val total = d1 + d2
        val w1 = if (total > 0) d2 / total else 0.5f
        val w2 = if (total > 0) d1 / total else 0.5f

        val nx = dx / cur; val ny = dy / cur
        if (p1.id != anchorId) { p1.x -= nx * delta * w1; p1.y -= ny * delta * w1 }
        if (p2.id != anchorId) { p2.x += nx * delta * w2; p2.y += ny * delta * w2 }
        return abs(delta)
    }

    private fun distToAnchor(p: DrawPoint, anchorId: String, pMap: Map<String, DrawPoint>): Float {
        val a = pMap[anchorId] ?: return 1f
        val dx = p.x - a.x; val dy = p.y - a.y
        return sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Diagonal generation — triangulation (minimum set) and all interior
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Возвращает минимальный набор диагоналей (ровно n−3 штуки) для триангуляции
     * многоугольника методом отсечения ушей (ear-clipping).
     *
     * Именно столько диагоналей запрашивает официальное приложение:
     * для 10-угольника — 7 диагоналей, для 6-угольника — 3, и т.д.
     * Работает для выпуклых и вогнутых простых многоугольников.
     */
    fun triangulationDiagonals(points: List<DrawPoint>): List<Pair<String, String>> {
        val n = points.size
        if (n < 4) return emptyList()

        // Знак площади определяет направление обхода
        var area2 = 0.0
        for (i in 0 until n) {
            val c = points[i]; val nx = points[(i + 1) % n]
            area2 += c.x.toDouble() * nx.y - nx.x.toDouble() * c.y
        }
        val isCCW = area2 > 0

        // Стороны многоугольника — не диагонали
        val sides = mutableSetOf<Pair<String, String>>()
        for (i in 0 until n) {
            val a = points[i].id; val b = points[(i + 1) % n].id
            sides += a to b; sides += b to a
        }

        val remaining = points.toMutableList()
        val diagonals = mutableListOf<Pair<String, String>>()

        while (remaining.size > 3) {
            val m = remaining.size
            var earFound = false
            for (i in 0 until m) {
                val prev = remaining[(i - 1 + m) % m]
                val curr = remaining[i]
                val next = remaining[(i + 1) % m]
                if (isEarVertex(prev, curr, next, remaining, isCCW)) {
                    val key = prev.id to next.id
                    if (key !in sides && (next.id to prev.id) !in sides) {
                        diagonals += key
                    }
                    remaining.removeAt(i)
                    earFound = true
                    break
                }
            }
            if (!earFound) break // Вырожденный случай
        }
        return diagonals
    }

    private fun isEarVertex(
        prev: DrawPoint, curr: DrawPoint, next: DrawPoint,
        polygon: List<DrawPoint>, isCCW: Boolean
    ): Boolean {
        val cross = (curr.x - prev.x).toDouble() * (next.y - prev.y) -
                    (curr.y - prev.y).toDouble() * (next.x - prev.x)
        val isConvex = if (isCCW) cross > 0 else cross < 0
        if (!isConvex) return false
        for (p in polygon) {
            if (p.id == prev.id || p.id == curr.id || p.id == next.id) continue
            if (pointInEarTriangle(p.x, p.y, prev, curr, next)) return false
        }
        return true
    }

    private fun pointInEarTriangle(
        px: Float, py: Float,
        a: DrawPoint, b: DrawPoint, c: DrawPoint
    ): Boolean {
        fun s(p1x: Float, p1y: Float, p2: DrawPoint, p3: DrawPoint) =
            (p1x - p3.x) * (p2.y - p3.y) - (p2.x - p3.x) * (p1y - p3.y)
        val d1 = s(px, py, a, b); val d2 = s(px, py, b, c); val d3 = s(px, py, c, a)
        return !((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))
    }

    /**
     * Генерирует ВСЕ внутренние диагонали многоугольника (не только из точки 0).
     * Для каждой пары несмежных вершин проверяет, что диагональ не пересекает
     * стены и её середина лежит внутри многоугольника.
     */
    fun generateDiagonals(points: List<DrawPoint>): List<Pair<String, String>> {
        val n = points.size
        if (n < 4) return emptyList()

        val result = mutableListOf<Pair<String, String>>()
        val added = mutableSetOf<Pair<String, String>>()

        for (i in 0 until n) {
            for (j in i + 2 until n) {
                // Пропускаем «закрывающую» сторону n-1 → 0
                if (i == 0 && j == n - 1) continue

                val a = points[i]
                val b = points[j]
                val key = if (a.id < b.id) a.id to b.id else b.id to a.id
                if (key in added) continue

                if (isDiagonalInterior(a, b, points)) {
                    result.add(a.id to b.id)
                    added.add(key)
                }
            }
        }
        return result
    }

    private fun isDiagonalInterior(
        a: DrawPoint, b: DrawPoint,
        points: List<DrawPoint>
    ): Boolean {
        val n = points.size

        for (i in 0 until n) {
            val p = points[i]
            val q = points[(i + 1) % n]
            if (p.id == a.id || p.id == b.id || q.id == a.id || q.id == b.id) continue
            if (segmentsProperlyIntersect(a, b, p, q)) return false
        }

        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        return pointInPolygon(mx, my, points)
    }

    /**
     * Проверяет, является ли многоугольник самопересекающимся.
     */
    fun isSelfIntersecting(points: List<DrawPoint>): Boolean {
        val n = points.size
        for (i in 0 until n) {
            val a = points[i]
            val b = points[(i + 1) % n]
            for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue  // смежные стороны
                val c = points[j]
                val d = points[(j + 1) % n]
                if (segmentsProperlyIntersect(a, b, c, d)) return true
            }
        }
        return false
    }

    private fun segmentsProperlyIntersect(
        p1: DrawPoint, p2: DrawPoint,
        p3: DrawPoint, p4: DrawPoint
    ): Boolean {
        val d1 = cross2(p3, p4, p1)
        val d2 = cross2(p3, p4, p2)
        val d3 = cross2(p1, p2, p3)
        val d4 = cross2(p1, p2, p4)

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true

        return false
    }

    private fun cross2(o: DrawPoint, a: DrawPoint, b: DrawPoint): Float {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }

    private fun pointInPolygon(x: Float, y: Float, points: List<DrawPoint>): Boolean {
        val n = points.size
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = points[i].x; val yi = points[i].y
            val xj = points[j].x; val yj = points[j].y
            if ((yi > y) != (yj > y) &&
                x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Geometry helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun trilaterate(
        c1: Pair<Double, Double>, r1: Double,
        c2: Pair<Double, Double>, r2: Double
    ): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
        val dx = c2.first - c1.first
        val dy = c2.second - c1.second
        val d = sqrt(dx * dx + dy * dy)
        if (d < 1e-9) return null

        val r1min = abs(d - r2)
        val r1max = d + r2
        if (r1min > r1max) return null
        val r1c = r1.coerceIn(r1min, r1max)
        val a = (r1c * r1c - r2 * r2 + d * d) / (2 * d)
        val h2 = r1c * r1c - a * a
        val h = if (h2 < 0) 0.0 else sqrt(h2)

        val mx = c1.first  + a * dx / d
        val my = c1.second + a * dy / d
        val px = -dy / d * h
        val py =  dx / d * h

        return (mx + px to my + py) to (mx - px to my - py)
    }

    private fun cross(
        a: Pair<Double, Double>, b: Pair<Double, Double>,
        c: Pair<Double, Double>, d: Pair<Double, Double>
    ): Double {
        val dx1 = b.first - a.first;  val dy1 = b.second - a.second
        val dx2 = d.first - c.first;  val dy2 = d.second - c.second
        return dx1 * dy2 - dy1 * dx2
    }

    private fun Double.sign() = when {
        this > 0 ->  1; this < 0 -> -1; else -> 0
    }

    private fun lookupDist(
        a: DrawPoint, b: DrawPoint,
        distMap: Map<Pair<String, String>, Double>
    ): Double {
        return distMap[a.id to b.id]
            ?: distMap[b.id to a.id]
            ?: run {
                val dx = (a.x - b.x).toDouble()
                val dy = (a.y - b.y).toDouble()
                sqrt(dx * dx + dy * dy)
            }
    }

    private fun buildDistMap(
        lines: List<DrawLine>,
        diagonals: List<DrawLine>
    ): Map<Pair<String, String>, Double> {
        val map = mutableMapOf<Pair<String, String>, Double>()
        for (ln in lines + diagonals) {
            val l = ln.measuredLength ?: continue
            if (l <= 0) continue
            map[ln.fromId to ln.toId] = l
            map[ln.toId to ln.fromId] = l
        }
        return map
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers used by DrawingActivity
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Частичная коррекция во время ввода размеров (live-перестройка).
     * Затрагивает только стены — диагонали не используются для перемещения точек.
     */
    fun correctPartial(
        points: MutableList<DrawPoint>,
        lines: List<DrawLine>,
        targetW: Float,
        targetH: Float
    ) {
        val n = points.size
        if (n < 2) return

        var lastMeasured = -1
        for (i in lines.indices) {
            val ml = lines[i].measuredLength
            if (ml != null && ml > 0) lastMeasured = i
            else break
        }
        if (lastMeasured < 0) return

        val coords = Array(n) { i -> points[i].x.toDouble() to points[i].y.toDouble() }
        coords[0] = 0.0 to 0.0

        for (i in 1..minOf(lastMeasured + 1, n - 1)) {
            val line = lines.getOrNull(i - 1) ?: break
            val measuredLen = line.measuredLength ?: break
            val prev = points[i - 1]
            val curr = points[i]
            val dx = (curr.x - prev.x).toDouble()
            val dy = (curr.y - prev.y).toDouble()
            val origLen = sqrt(dx * dx + dy * dy)
            if (origLen < 1e-9) { coords[i] = coords[i - 1]; continue }
            val nx = dx / origLen
            val ny = dy / origLen
            coords[i] = (coords[i - 1].first  + nx * measuredLen) to
                        (coords[i - 1].second + ny * measuredLen)
        }

        val lastReconIdx = minOf(lastMeasured + 1, n - 1)
        if (lastReconIdx < n - 1) {
            val reconPt = coords[lastReconIdx]
            val origPt = points[lastReconIdx].x.toDouble() to points[lastReconIdx].y.toDouble()
            val shiftX = reconPt.first  - origPt.first
            val shiftY = reconPt.second - origPt.second
            for (i in lastReconIdx + 1 until n) {
                coords[i] = (points[i].x.toDouble() + shiftX) to (points[i].y.toDouble() + shiftY)
            }
        }

        scaleToScreen(points, coords, targetW, targetH)
    }

    /**
     * Площадь многоугольника в пикселях² (формула Гаусса / Shoelace).
     */
    fun calculateArea(points: List<DrawPoint>): Double {
        if (points.size < 3) return 0.0
        var area = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += points[i].x.toDouble() * points[j].y.toDouble()
            area -= points[j].x.toDouble() * points[i].y.toDouble()
        }
        return abs(area) / 2.0
    }

    /**
     * Площадь в м² — вычисляется по реальным измеренным длинам стен
     * и текущим пиксельным координатам (после коррекции).
     * Формула: pixelArea × (measuredPerim / pixelPerim)² / 10000
     */
    fun calculateRealAreaM2(points: List<DrawPoint>, lines: List<DrawLine>): Double {
        if (points.size < 3) return 0.0
        val wallLines = lines.filter { !it.isDiagonal }

        val pixelPerim = wallLines.sumOf { line ->
            val p1 = points.find { it.id == line.fromId } ?: return@sumOf 0.0
            val p2 = points.find { it.id == line.toId } ?: return@sumOf 0.0
            val dx = (p2.x - p1.x).toDouble()
            val dy = (p2.y - p1.y).toDouble()
            sqrt(dx * dx + dy * dy)
        }
        val measuredPerim = wallLines.sumOf { it.measuredLength ?: 0.0 }

        if (pixelPerim < 1e-9 || measuredPerim < 1e-9) return 0.0

        val pixelArea = calculateArea(points)
        val scale = measuredPerim / pixelPerim  // см на пиксель
        return (pixelArea * scale * scale) / 10000.0  // м²
    }

    /**
     * Упрощённый расчёт площади: масштаб вычисляется как **среднее** отношений
     * измеренная_длина/пиксельная_длина по каждой стене отдельно.
     * Используется когда autoTriangle = false — менее точен, зато не зависит
     * от суммарной точности всего периметра.
     */
    fun calculateRawShoelaceM2(points: List<DrawPoint>, lines: List<DrawLine>): Double {
        if (points.size < 3) return 0.0
        val wallLines = lines.filter { !it.isDiagonal && it.measuredLength != null && it.measuredLength!! > 0 }
        if (wallLines.isEmpty()) return 0.0

        val ratios = wallLines.mapNotNull { line ->
            val p1 = points.find { it.id == line.fromId } ?: return@mapNotNull null
            val p2 = points.find { it.id == line.toId } ?: return@mapNotNull null
            val dx = (p2.x - p1.x).toDouble()
            val dy = (p2.y - p1.y).toDouble()
            val pixLen = sqrt(dx * dx + dy * dy)
            if (pixLen < 1e-6) null else line.measuredLength!! / pixLen
        }
        if (ratios.isEmpty()) return 0.0
        val avgScale = ratios.average()  // среднее по всем стенам

        val pixelArea = calculateArea(points)
        return (pixelArea * avgScale * avgScale) / 10000.0  // м²
    }

    fun calculatePerimeter(lines: List<DrawLine>): Double =
        lines.filter { !it.isDiagonal }.sumOf { it.measuredLength ?: 0.0 }

    fun calculateExtraCorners(points: List<DrawPoint>): Int = maxOf(0, points.size - 4)
}
