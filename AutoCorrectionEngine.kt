package com.zamerpro.app.ui.drawing

import com.zamerpro.app.data.DrawLine
import com.zamerpro.app.data.DrawPoint
import kotlin.math.*

class AutoCorrectionEngine {

    companion object {
        private const val MAX_ITERATIONS = 500
        private const val TOLERANCE = 0.1f
        private const val MIN_AREA_PX2 = 500.0
        // Порог качества треугольника: вершины, размещённые из треугольников
        // с качеством НИЖЕ этого значения, считаются «ненадёжными» и будут
        // переставлены в фазе постобработки. Значение 0.15 соответствует
        // sin(наименьшего угла) ≈ 8.6°: такой треугольник почти вырожден.
        private const val REPOSITION_QUALITY_THRESHOLD = 0.15
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Основная точка входа реконструкции.
     *
     * Стратегия (аналогично NewMatRos):
     * 1. BFS-триангуляция — всегда пробуется первой, работает с любым количеством
     *    диагоналей включая пересекающиеся. Строит максимально точную форму.
     * 2. Direction-fallback — если BFS не смог разместить все вершины (мало диагоналей),
     *    восстанавливаем цепочку по направлениям из эскиза.
     * 3. Spring-refinement — применяется ВСЕГДА после шага 1 или 2:
     *    распределяет погрешности рулетки по всем вершинам (автовыравнивание),
     *    учитывает ВСЕ замеры включая избыточные и пересекающиеся диагонали.
     * 4. Rescale — перемасштабирует на экран после пружинного уточнения.
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
                val allMeasured = wallLines + measuredDiags
                val distMap = buildDistMap(wallLines, measuredDiags)

                // Шаг 1: BFS-триангуляция — пробуем ВСЕГДА, не зависит от числа диагоналей.
                // Поддерживает пересекающиеся диагонали — они входят в distMap как обычные ребра.
                val bfsSuccess = reconstructByBFS(points, allMeasured, distMap, targetW, targetH)

                if (!bfsSuccess) {
                    // Шаг 1b: direction-fallback — строим цепочку по направлениям эскиза
                    reconstructByDirections(points, wallLines, targetW, targetH)
                }

                // Шаг 2: Пружинное уточнение — ВСЕГДА, даже без диагоналей.
                // При избыточных замерах распределяет погрешности по всем вершинам.
                // При пересекающихся диагоналях — учитывает все расстояния как ограничения.
                springRefineAll(points, wallLines, measuredDiags)

                // Шаг 3: Перемасштабирование после spring (координаты сдвинулись)
                rescaleToFit(points, targetW, targetH)

                // Контроль качества — откат при вырожденном результате
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
     * Перемасштабирует точки в текущих координатах на экран.
     * Вызывается после springRefineAll, которая двигает точки в пиксельном пространстве.
     */
    private fun rescaleToFit(points: MutableList<DrawPoint>, targetW: Float, targetH: Float) {
        val coords = Array(points.size) { i -> points[i].x.toDouble() to points[i].y.toDouble() }
        scaleToScreen(points, coords, targetW, targetH)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BFS trilateration reconstruction (primary method when all measured)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Реконструкция многоугольника методом BFS по треугольникам.
     *
     * Алгоритм:
     * 1. Строим граф из всех треугольников, у которых все 3 ребра измерены
     *    (стены + диагонали).
     * 2. Засеиваем первый треугольник: A = (0,0), B = (d_AB, 0),
     *    C выбирается по правилу «та же сторона, что в эскизе».
     * 3. BFS: пока есть треугольники с ровно 2 расставленными вершинами —
     *    трилатерируем третью, выбирая кандидата по позиции в эскизе.
     * 4. Масштабируем результат на экран.
     *
     * Именно так работает официальное приложение производителей потолков.
     * Метод robustly обрабатывает вогнутые многоугольники любой формы.
     */
    private fun reconstructByBFS(
        points: MutableList<DrawPoint>,
        allLines: List<DrawLine>,
        distMap: Map<Pair<String, String>, Double>,
        targetW: Float,
        targetH: Float
    ): Boolean {
        val n = points.size

        // Индекс: id точки → её порядковый номер
        val idToIdx = HashMap<String, Int>(n * 2)
        for (i in points.indices) idToIdx[points[i].id] = i

        // Координаты в reconstructed space; null = ещё не размещена
        val coords = arrayOfNulls<Pair<Double, Double>>(n)

        // Кодируем рёбра как min(i,j)*10000+max(i,j)
        fun edgeKey(a: Int, b: Int): Long =
            if (a < b) a.toLong() * 10000L + b else b.toLong() * 10000L + a

        // Заполняем measured edges
        val edgeDist = HashMap<Long, Double>()
        for (line in allLines) {
            val ml = line.measuredLength ?: continue
            if (ml <= 0) continue
            val i = idToIdx[line.fromId] ?: continue
            val j = idToIdx[line.toId] ?: continue
            edgeDist[edgeKey(i, j)] = ml
        }

        fun getDist(i: Int, j: Int): Double? = edgeDist[edgeKey(i, j)]

        // Находим все треугольники, у которых все 3 ребра имеют измеренную длину
        data class Tri(val a: Int, val b: Int, val c: Int)
        val triangles = mutableListOf<Tri>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (getDist(i, j) == null) continue
                for (k in j + 1 until n) {
                    if (getDist(i, k) == null) continue
                    if (getDist(j, k) == null) continue
                    triangles.add(Tri(i, j, k))
                }
            }
        }
        if (triangles.isEmpty()) return false

        // ── Вспомогательная функция: качество треугольника ───────────────────
        // Возвращает h/max_edge (sin наименьшего угла).
        // Чем выше — тем стабильнее трилатерация.
        fun triQuality(dAB: Double, dAC: Double, dBC: Double): Double {
            val s = (dAB + dAC + dBC) / 2.0
            val area2 = s * (s - dAB) * (s - dAC) * (s - dBC)
            if (area2 <= 0.0) return 0.0
            val area = sqrt(area2)
            return (2.0 * area) / maxOf(dAB, dAC, dBC)
        }

        // ── Шаг 1: Засеиваем первый треугольник ──────────────────────────────

        // Выбираем «лучший» начальный треугольник по качеству (h/max_edge).
        // Хорошее качество = треугольник далёк от вырождения.
        val seedTri = triangles.maxByOrNull { tri ->
            val dAB = getDist(tri.a, tri.b) ?: 0.0
            val dAC = getDist(tri.a, tri.c) ?: 0.0
            val dBC = getDist(tri.b, tri.c) ?: 0.0
            triQuality(dAB, dAC, dBC)
        } ?: triangles[0]

        val s0 = seedTri.a
        val s1 = seedTri.b
        val s2 = seedTri.c

        val d01 = getDist(s0, s1) ?: return false
        val d02 = getDist(s0, s2) ?: return false
        val d12 = getDist(s1, s2) ?: return false

        coords[s0] = 0.0 to 0.0
        coords[s1] = d01 to 0.0

        val seedCands = trilaterate(coords[s0]!!, d02, coords[s1]!!, d12) ?: return false

        // Выбираем кандидата для s2 по позиции в эскизе
        val sk0 = points[s0].x.toDouble() to points[s0].y.toDouble()
        val sk1 = points[s1].x.toDouble() to points[s1].y.toDouble()
        val sk2 = points[s2].x.toDouble() to points[s2].y.toDouble()
        coords[s2] = chooseCandidateBySketch(
            coords[s0]!!, coords[s1]!!, seedCands, sk0, sk1, sk2, d01
        )

        val placed = mutableSetOf(s0, s1, s2)

        // Качество размещения каждой вершины: -1 = ещё не размещена,
        // Double.MAX_VALUE = якорная (s0/s1 — всегда надёжны),
        // иначе — качество треугольника, из которого была трилатерирована.
        val placedQuality = DoubleArray(n) { -1.0 }
        placedQuality[s0] = Double.MAX_VALUE  // s0 — фиксированный якорь (0,0)
        placedQuality[s1] = Double.MAX_VALUE  // s1 — фиксированный якорь (d01, 0)
        val seedQuality = run {
            val dAB = getDist(s0, s1) ?: 0.0
            val dAC = getDist(s0, s2) ?: 0.0
            val dBC = getDist(s1, s2) ?: 0.0
            triQuality(dAB, dAC, dBC)
        }
        placedQuality[s2] = seedQuality

        // ── Шаг 2: Качество-осознанный BFS ───────────────────────────────────
        //
        // На каждом шаге перебираем ВСЕ треугольники с ровно 2 расставленными
        // вершинами и выбираем тот, у которого НАИЛУЧШЕЕ качество.
        // Это гарантирует, что вырожденные треугольники (например DIJ при IJ=14)
        // используются только если нет лучшей альтернативы.
        // Для каждой вершины запоминаем качество треугольника из которого она
        // была трилатерирована — это понадобится в шаге 3.
        val maxSteps = n * (n + 1)
        repeat(maxSteps) {
            if (placed.size >= n) return@repeat

            var bestTarget = -1
            var bestQuality = -1.0
            var bestChosen: Pair<Double, Double>? = null

            for (tri in triangles) {
                val verts = intArrayOf(tri.a, tri.b, tri.c)
                val placedVerts = verts.filter { it in placed }
                val unplacedVerts = verts.filter { it !in placed }

                // Нужно ровно 2 расставленных и 1 новая
                if (placedVerts.size != 2 || unplacedVerts.size != 1) continue

                val target = unplacedVerts[0]
                val r1 = placedVerts[0]
                val r2 = placedVerts[1]

                val dR1T = getDist(r1, target) ?: continue
                val dR2T = getDist(r2, target) ?: continue
                val c1 = coords[r1] ?: continue
                val c2 = coords[r2] ?: continue

                // Вычисляем качество данного треугольника
                val dBase = sqrt((c2.first - c1.first).pow(2) + (c2.second - c1.second).pow(2))
                if (dBase < 1e-9) continue
                val quality = triQuality(dR1T, dR2T, dBase)

                if (quality <= bestQuality) continue

                // Трилатерация и выбор кандидата по эскизу
                val cands = trilaterate(c1, dR1T, c2, dR2T) ?: continue

                val skR1 = points[r1].x.toDouble() to points[r1].y.toDouble()
                val skR2 = points[r2].x.toDouble() to points[r2].y.toDouble()
                val skTgt = points[target].x.toDouble() to points[target].y.toDouble()
                val chosen = chooseCandidateBySketch(c1, c2, cands, skR1, skR2, skTgt, dBase)

                if (!chosen.first.isFinite() || !chosen.second.isFinite()) continue

                bestQuality = quality
                bestTarget = target
                bestChosen = chosen
            }

            if (bestTarget == -1) return@repeat  // Ничего нового разместить нельзя
            coords[bestTarget] = bestChosen!!
            placed.add(bestTarget)
            placedQuality[bestTarget] = bestQuality  // Запоминаем качество размещения
        }

        // Если не все вершины размещены — BFS не справился
        if (placed.size < n) return false

        // ── Шаг 3: Перерасстановка — ТОЛЬКО низкокачественные вершины ────────
        //
        // Теперь все вершины размещены. Некоторые из них (например I из DIJ при IJ=14)
        // могли попасть в неверную позицию из-за вырожденного треугольника.
        //
        // ВАЖНО: переставляем ТОЛЬКО вершины, которые были размещены из треугольников
        // с качеством ниже REPOSITION_QUALITY_THRESHOLD. Хорошо поставленные
        // вершины (якоря и вершины из нормальных треугольников) НЕ ТРОГАЕМ —
        // это предотвращает искажение правильно построенных частей фигуры.
        //
        // Делаем несколько проходов для конвергенции: после того как I поставлена
        // из F,H,I (q≈0.8) вместо D,I,J (q≈0.06), остальные вершины пересчитываются
        // уже с правильным соседом.
        repeat(4) {
            for (target in 0 until n) {
                // Пропускаем якоря и вершины с достаточно высоким качеством
                if (placedQuality[target] >= REPOSITION_QUALITY_THRESHOLD) continue

                var bestQual = 0.0
                var bestNew: Pair<Double, Double>? = null

                for (tri in triangles) {
                    val verts = intArrayOf(tri.a, tri.b, tri.c)
                    if (target !in verts) continue

                    val others = verts.filter { it != target }
                    val r1 = others[0]; val r2 = others[1]
                    val c1 = coords[r1] ?: continue
                    val c2 = coords[r2] ?: continue

                    val dR1T = getDist(r1, target) ?: continue
                    val dR2T = getDist(r2, target) ?: continue
                    val dBase = sqrt((c2.first - c1.first).pow(2) + (c2.second - c1.second).pow(2))
                    if (dBase < 1e-9) continue

                    val qual = triQuality(dR1T, dR2T, dBase)
                    if (qual <= bestQual) continue

                    val cands = trilaterate(c1, dR1T, c2, dR2T) ?: continue

                    val skR1 = points[r1].x.toDouble() to points[r1].y.toDouble()
                    val skR2 = points[r2].x.toDouble() to points[r2].y.toDouble()
                    val skTgt = points[target].x.toDouble() to points[target].y.toDouble()
                    val chosen = chooseCandidateBySketch(c1, c2, cands, skR1, skR2, skTgt, dBase)

                    if (!chosen.first.isFinite() || !chosen.second.isFinite()) continue

                    bestQual = qual
                    bestNew = chosen
                }

                if (bestNew != null) {
                    coords[target] = bestNew
                    // Обновляем качество чтобы следующий проход не переставлял снова,
                    // если вершина уже переставлена из хорошего треугольника
                    placedQuality[target] = bestQual
                }
            }
        }

        // Безопасно разворачиваем nullable — каждый элемент должен быть заполнен
        val coordsArr = Array(n) { i -> coords[i] ?: return false }

        return scaleToScreen(points, coordsArr, targetW, targetH)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Candidate selection helper
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Выбирает из двух кандидатов трилатерации правильный по эскизной позиции.
     *
     * Основной критерий: та же сторона линии ref1–ref2, что и в эскизе.
     * Резервный критерий (когда цель почти на линии, угол ≈ 0 — типично для
     * пересекающихся диагоналей или очень грубого эскиза): выбираем кандидата
     * ближе к проецированной позиции цели в эскизе.
     */
    private fun chooseCandidateBySketch(
        c1: Pair<Double, Double>,
        c2: Pair<Double, Double>,
        cands: Pair<Pair<Double, Double>, Pair<Double, Double>>,
        skR1: Pair<Double, Double>,
        skR2: Pair<Double, Double>,
        skTgt: Pair<Double, Double>,
        dBase: Double
    ): Pair<Double, Double> {
        val sketchSide = sideOfLine(skR1, skR2, skTgt)
        val sideCA = sideOfLine(c1, c2, cands.first)

        return if (abs(sketchSide) > 1e-6) {
            if (sideCA * sketchSide >= 0) cands.first else cands.second
        } else {
            // Резервный: ближайший к проекции скетч-позиции цели
            val skScale = sqrt(
                (skR2.first - skR1.first).pow(2) + (skR2.second - skR1.second).pow(2)
            ).coerceAtLeast(1.0)
            val reScale = if (dBase > 1e-9) dBase / skScale else 1.0
            fun distToSketch(cand: Pair<Double, Double>): Double {
                val dx = (cand.first  - c1.first)  / reScale
                val dy = (cand.second - c1.second) / reScale
                val sx = skTgt.first  - skR1.first
                val sy = skTgt.second - skR1.second
                return (dx - sx).pow(2) + (dy - sy).pow(2)
            }
            if (distToSketch(cands.first) <= distToSketch(cands.second)) cands.first else cands.second
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Spring refine (распределяет погрешности, учитывает пересекающиеся диагонали)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Уточняет позиции вершин после BFS/direction реконструкции с помощью
     * пружинной релаксации по ВСЕМ ограничениям: стены + все замеренные диагонали.
     * При избыточных замерах (больше n-3 диагоналей) распределяет погрешности
     * рулетки по всем вершинам — автовыравнивание как в NewMatRos.
     */
    private fun springRefineAll(
        points: MutableList<DrawPoint>,
        wallLines: List<DrawLine>,
        measuredDiags: List<DrawLine>
    ) {
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
            pMap[anchorId]?.also { it.x = anchorX; it.y = anchorY }
            if (maxErr < TOLERANCE) return
        }

        for (p in points) pMap[p.id]?.let { c -> p.x = c.x; p.y = c.y }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Direction-based reconstruction (fallback)
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
    // Trilateration reconstruction (kept for reference, not used in main path)
    // ──────────────────────────────────────────────────────────────────────────

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
     */
    fun triangulationDiagonals(points: List<DrawPoint>): List<Pair<String, String>> {
        val n = points.size
        if (n < 4) return emptyList()

        var area2 = 0.0
        for (i in 0 until n) {
            val c = points[i]; val nx = points[(i + 1) % n]
            area2 += c.x.toDouble() * nx.y - nx.x.toDouble() * c.y
        }
        val isCCW = area2 > 0

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
            if (!earFound) break
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
     */
    fun generateDiagonals(points: List<DrawPoint>): List<Pair<String, String>> {
        val n = points.size
        if (n < 4) return emptyList()

        val result = mutableListOf<Pair<String, String>>()
        val added = mutableSetOf<Pair<String, String>>()

        for (i in 0 until n) {
            for (j in i + 2 until n) {
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
                if (i == 0 && j == n - 1) continue
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

    /**
     * Возвращает знак ориентации точки [point] относительно прямой [lineStart]→[lineEnd].
     * > 0: точка слева, < 0: справа, = 0: на прямой.
     */
    private fun sideOfLine(
        lineStart: Pair<Double, Double>,
        lineEnd: Pair<Double, Double>,
        point: Pair<Double, Double>
    ): Double {
        return (lineEnd.first - lineStart.first) * (point.second - lineStart.second) -
               (lineEnd.second - lineStart.second) * (point.first - lineStart.first)
    }

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
        val scale = measuredPerim / pixelPerim
        return (pixelArea * scale * scale) / 10000.0
    }

    /**
     * Упрощённый расчёт площади: масштаб вычисляется как среднее отношений
     * измеренная_длина/пиксельная_длина по каждой стене отдельно.
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
        val avgScale = ratios.average()

        val pixelArea = calculateArea(points)
        return (pixelArea * avgScale * avgScale) / 10000.0
    }

    fun calculatePerimeter(lines: List<DrawLine>): Double =
        lines.filter { !it.isDiagonal }.sumOf { it.measuredLength ?: 0.0 }

    fun calculateExtraCorners(points: List<DrawPoint>): Int = maxOf(0, points.size - 4)
}
