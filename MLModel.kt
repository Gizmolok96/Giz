package com.footballanalyzer.app.ml

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

data class MLFeatureVector(
    val homeWinRate: Double,
    val awayWinRate: Double,
    val homeAvgGoalsFor: Double,
    val awayAvgGoalsFor: Double,
    val homeAvgGoalsAgainst: Double,
    val awayAvgGoalsAgainst: Double,
    val homeRecentPoints: Double,
    val awayRecentPoints: Double,
    val homeTrend: Double,
    val awayTrend: Double,
    val homeXgFor: Double,
    val awayXgFor: Double,
    val h2hHomeWinRate: Double,
    val h2hAvgGoals: Double,
    val homeAdvantage: Double,
    val homePPG: Double,
    val awayPPG: Double,
    val homeGoalDiff: Double,
    val awayGoalDiff: Double,
    val formDiff: Double,
    val homeStrengthAdjWinRate: Double = 0.5,
    val awayStrengthAdjWinRate: Double = 0.5,
    val homeHomeWinRate: Double = 0.5,
    val awayAwayWinRate: Double = 0.5,
    val homeMotivation: Double = 1.0,
    val awayMotivation: Double = 1.0,
    /** Prematch 1X2 bookmaker odds. 0.0 = not available. */
    val homeOdds: Double = 0.0,
    val drawOdds: Double = 0.0,
    val awayOdds: Double = 0.0,
    /** Over 2.5 bookmaker odds. 0.0 = not available. */
    val over25Odds: Double = 0.0,
    /** Under 2.5 bookmaker odds. Used with over25Odds for margin-correct normalization. 0.0 = not available. */
    val under25Odds: Double = 0.0,
    /** BTTS Yes bookmaker odds. 0.0 = not available. */
    val bttsYesOdds: Double = 0.0,
    /** BTTS No bookmaker odds. Used with bttsYesOdds for margin-correct normalization. 0.0 = not available. */
    val bttsNoOdds: Double = 0.0,
    /** Glicko-2 rating difference (home − away). 0.0 when not available. */
    val glickoRatingDiff: Double = 0.0,
    val homeGlickoRating: Double = 1500.0,
    val awayGlickoRating: Double = 1500.0,
    val homeGlickoRd: Double = 350.0,
    val awayGlickoRd: Double = 350.0,
    // ── Rich stats: goalkeeper quality, defensive solidity, error rate ─────
    /**
     * Goalkeeper quality factor for home/away team.
     * Derived from goalsPreventedGk. Range [0.75, 1.25]. Neutral = 1.0.
     * Values < 1.0 → strong GK (saves more than expected → opponents score less).
     * Values > 1.0 → weak GK (concedes more than expected → opponents score more).
     */
    val homeGkQuality: Double = 1.0,
    val awayGkQuality: Double = 1.0,
    /**
     * Defensive solidity factor for home/away team.
     * Derived from interceptions + clearances per game, normalised to 1.0.
     * Range [0.60, 1.60]. Higher = more defensive activity.
     */
    val homeDefStrength: Double = 1.0,
    val awayDefStrength: Double = 1.0,
    /**
     * Average errors leading to goal per game for home/away team.
     * Higher = more defensively vulnerable. Typical range [0.0, 0.6].
     */
    val homeErrRate: Double = 0.0,
    val awayErrRate: Double = 0.0,
    /**
     * League average goals per game — allows ML to learn league-specific scoring bias.
     * Typical range [2.38, 3.10]. Default 2.60 (global average).
     */
    val leagueAvgGoals: Double = 2.60,
    /**
     * League home advantage multiplier — allows ML to learn venue-specific bias.
     * Typical range [1.03, 1.20]. Default 1.10 (global average).
     */
    val leagueHomeAdv: Double = 1.10
)

data class GoalMarketPrediction(
    val over25Prob: Double,
    val bttsProb: Double,
    val mlMostLikelyScore: String,
    val goalLines: List<GoalLineResult> = emptyList()
)

data class GoalLineResult(
    val line: Double,
    val overProb: Double,
    val underProb: Double,
    val alreadyGuaranteed: Boolean = false
)

data class ModelWeights(
    val treeWeights: List<Double> = listOf(0.20, 0.26, 0.20, 0.16, 0.10, 0.08),
    val homeWinRateW: Double = 1.0,
    val awayWinRateW: Double = 1.0,
    val homeRecentW: Double = 1.0,
    val awayRecentW: Double = 1.0,
    val homeTrendW: Double = 1.0,
    val awayTrendW: Double = 1.0,
    val xgRatioW: Double = 1.0,
    val h2hW: Double = 1.0,
    val homeAdvW: Double = 1.0,
    val ppgDiffW: Double = 1.0,
    val gdDiffW: Double = 1.0,
    val strengthAdjW: Double = 1.0,
    val homeAwayW: Double = 1.0,
    val motivationW: Double = 1.0,
    val homeAdvBias: Double = 0.05,
    val drawCalibration: Double = 0.22,
    val totalMatchesTrained: Int = 0,
    val lastTrainedAt: String = "",
    val accuracy7day: Double = 0.0,
    val accuracy30day: Double = 0.0,
    val version: Int = 1,
    // ── Goal market weights ────────────────────────────────────────────────
    val over25Bias: Double = 0.0,
    val over25XgW: Double = 1.2,
    val over25AvgGoalW: Double = 0.8,
    val bttsBias: Double = 0.0,
    val bttsHomeXgW: Double = 1.0,
    val bttsAwayXgW: Double = 1.0,
    /**
     * T6 — Market odds tree blend weight.
     * Controls how much bookmaker 1X2 odds influence W/D/L probabilities.
     * Range [0.0, 2.0]: 1.0 = up to 30% market influence, trained via gradient.
     * When no odds available, T6 is skipped entirely.
     */
    val oddsTreeW: Double = 1.0,

    // ── Platt Scaling calibration parameters ─────────────────────────────
    // Applied AFTER the forward pass to correct systematic probability bias.
    //
    // calibA (scale): adjusts the slope of the logit transform.
    //   calibA > 1.0 → probabilities pushed further from 0.5 (more extreme)
    //   calibA < 1.0 → probabilities pulled toward 0.5 (more conservative)
    //   Default 1.0 = neutral (no calibration effect).
    //
    // calibB (bias): shifts all home-win probabilities up or down.
    //   calibB > 0.0 → model was underestimating home wins → shift up
    //   calibB < 0.0 → model was overestimating home wins → shift down
    //   Default 0.0 = neutral.
    //
    // Both parameters are trained automatically during Scan & Train.
    // Calibration kicks in after ~20 trained matches and accumulates accuracy
    // as more completed matches are processed.
    val calibA: Double = 1.0,
    val calibB: Double = 0.0,
    // ── Momentum terms for Adam-like SGD update (initialized to 0) ──────────
    val momentumH: Double = 0.0,
    val momentumD: Double = 0.0,
    val momentumOver: Double = 0.0,
    val momentumBtts: Double = 0.0
)

data class ModelState(
    val weights: ModelWeights = ModelWeights(),
    val completedRecords: List<String> = emptyList()
)

data class TrainEntry(
    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeResult: Int,
    val awayResult: Int,
    val homeWinPct: Double,
    val drawPct: Double,
    val awayWinPct: Double,
    val homeAvgGoalsFor: Double = 0.0,
    val homeAvgGoalsAgainst: Double = 0.0,
    val awayAvgGoalsFor: Double = 0.0,
    val awayAvgGoalsAgainst: Double = 0.0,
    val homePPG: Double = 0.0,
    val awayPPG: Double = 0.0,
    val homeTrend: Double = 0.5,
    val awayTrend: Double = 0.5,
    val homeXgFor: Double = 0.0,
    val awayXgFor: Double = 0.0,
    val h2hHomeWinRate: Double = 0.35,
    val h2hAvgGoals: Double = 2.5,
    val homeStrengthAdjWinRate: Double = 0.5,
    val awayStrengthAdjWinRate: Double = 0.5,
    val homeRecentPoints: Double = 0.0,
    val awayRecentPoints: Double = 0.0,
    val homeGoalDiff: Double = 0.0,
    val awayGoalDiff: Double = 0.0,
    val formDiff: Double = 0.0,
    val homeMotivation: Double = 1.0,
    val awayMotivation: Double = 1.0,
    val matchTimestamp: Long = 0L,
    val homeOdds: Double = 0.0,
    val drawOdds: Double = 0.0,
    val awayOdds: Double = 0.0,
    val over25Odds: Double = 0.0,
    val bttsYesOdds: Double = 0.0,
    // Actual season win rates — aligned with what enrichWithMlPrediction() uses.
    // Previously homeWinPct/100 (Poisson probability) was used in training, causing a
    // feature scale mismatch. These fields fix that inconsistency.
    val homeActualWinRate: Double = 0.0,
    val awayActualWinRate: Double = 0.0,
    val homeHomeActualWinRate: Double = 0.0,
    val awayAwayActualWinRate: Double = 0.0,
    // Glicko-2 data — now included in the ML feature vector so the model can learn
    // from the same Glicko signals that drive the Poisson xG computation.
    val glickoRatingDiff: Double = 0.0,
    val homeGlickoRating: Double = 1500.0,
    val awayGlickoRating: Double = 1500.0,
    val homeGlickoRd: Double = 350.0,
    val awayGlickoRd: Double = 350.0,
    // ── Rich stats: goalkeeper quality, defensive solidity, error rate ─────
    val homeGkQuality: Double = 1.0,
    val awayGkQuality: Double = 1.0,
    val homeDefStrength: Double = 1.0,
    val awayDefStrength: Double = 1.0,
    val homeErrRate: Double = 0.0,
    val awayErrRate: Double = 0.0
)

class MLModel(private val context: Context) {

    private val gson = Gson()
    private val prefs by lazy {
        context.getSharedPreferences("ml_model", Context.MODE_PRIVATE)
    }
    private val STATE_KEY = "model_state_v3"

    private var state: ModelState = loadState()

    /** Returns current model weights for UI display (e.g. confidence indicator). */
    fun currentWeights(): ModelWeights = state.weights

    private fun loadState(): ModelState {
        return try {
            val raw = prefs.getString(STATE_KEY, null) ?: return ModelState()
            gson.fromJson(raw, ModelState::class.java) ?: ModelState()
        } catch (e: Exception) {
            ModelState()
        }
    }

    private fun saveState() {
        try {
            val toSave = state.copy(completedRecords = state.completedRecords.takeLast(6000))
            prefs.edit().putString(STATE_KEY, gson.toJson(toSave)).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    /**
     * Forward pass through 5 statistical trees + T6 market odds blending + T7 rich defensive stats.
     *
     * T1 — Win rate + home advantage
     * T2 — xG / goal expectation
     * T3 — H2H history + form diff
     * T4 — Goal differential + trend
     * T5 — Strength-adjusted win rate + home/away split + motivation
     * T6 — Bookmaker market implied probabilities (blended in when odds available)
     * T7 — Goalkeeper quality + defensive solidity + error rate (rich stats, optional)
     *
     * T6 blending: the market removes overround and is weighted by oddsTreeW.
     * When odds are unavailable, T6 is skipped — result is pure stats (T1–T5/T7).
     * T7 is applied as a soft adjustment after T1–T5 are combined, so it never
     * dominates when rich stats are unavailable (neutral = 0.0 adjustment).
     */
    private fun forwardPass(f: MLFeatureVector, w: ModelWeights): Triple<Double, Double, Double> {
        val t1H = 0.3  * f.homeWinRate * w.homeWinRateW + 0.15 * f.homeRecentPoints * w.homeRecentW -
                  0.1  * f.awayWinRate * w.awayWinRateW  + 0.15 * f.homeAdvantage * w.homeAdvW
        val t1D = w.drawCalibration - 0.05 * abs(f.homeWinRate - f.awayWinRate) - 0.05 * f.homeTrend * w.homeTrendW
        val t1A = 0.3  * f.awayWinRate * w.awayWinRateW  + 0.1  * f.awayRecentPoints * w.awayRecentW -
                  0.05 * f.homeAdvantage * w.homeAdvW

        val t2H = 0.25 * f.homeXgFor * w.xgRatioW - 0.15 * f.awayXgFor * w.xgRatioW + 0.1 * f.homePPG * w.ppgDiffW
        val t2D = 0.15 - 0.08 * abs(f.homeXgFor - f.awayXgFor) * w.xgRatioW
        val t2A = 0.25 * f.awayXgFor * w.xgRatioW - 0.1  * f.homeXgFor * w.xgRatioW + 0.08 * f.awayPPG * w.ppgDiffW

        val t3H = 0.4  * f.h2hHomeWinRate * w.h2hW + 0.2 * f.formDiff * w.homeTrendW + w.homeAdvBias
        val t3D = 0.25 - 0.1 * abs(f.formDiff) * w.homeTrendW
        val t3A = 0.4  * (1 - f.h2hHomeWinRate) * w.h2hW - 0.2 * f.formDiff * w.homeTrendW

        val t4H = 0.3  * f.homeGoalDiff * w.gdDiffW + 0.2 * (f.homeTrend - f.awayTrend) * w.homeTrendW + w.homeAdvBias
        val t4D = 0.20 - 0.05 * abs(f.homeGoalDiff - f.awayGoalDiff) * w.gdDiffW
        val t4A = 0.3  * f.awayGoalDiff.coerceAtLeast(0.0) * w.gdDiffW + 0.1 * (f.awayTrend - f.homeTrend) * w.awayTrendW

        val strengthDiff = f.homeStrengthAdjWinRate - f.awayStrengthAdjWinRate
        val motivDiff    = f.homeMotivation - f.awayMotivation
        // Glicko-2 signal: normalise rating diff to [-0.5, 0.5] range.
        // Positive → home team is rated higher → boosts home prediction.
        // Weight is zero when glickoRatingDiff == 0.0 (data not available).
        val glickoNorm = if (f.glickoRatingDiff != 0.0)
            (f.glickoRatingDiff / 1000.0).coerceIn(-0.5, 0.5) else 0.0

        val t5H = 0.32 * f.homeStrengthAdjWinRate * w.strengthAdjW +
                  0.18 * f.homeHomeWinRate         * w.homeAwayW   +
                  0.13 * f.homeMotivation           * w.motivationW +
                  0.08 * glickoNorm                * w.strengthAdjW +
                  0.04 * strengthDiff               * w.strengthAdjW
        val t5D = w.drawCalibration * 0.85 - 0.08 * abs(strengthDiff) * w.strengthAdjW -
                  0.06 * abs(motivDiff) * w.motivationW
        val t5A = 0.32 * f.awayStrengthAdjWinRate * w.strengthAdjW +
                  0.18 * f.awayAwayWinRate          * w.homeAwayW   +
                  0.13 * f.awayMotivation            * w.motivationW -
                  0.08 * glickoNorm                * w.strengthAdjW -
                  0.04 * strengthDiff               * w.strengthAdjW

        // ── T8: Rich Defensive Quality tree ─────────────────────────────────
        // Captures goalkeeper quality, defensive solidity and error rate in a
        // dedicated tree so these signals are weighted independently from T7 nudges.
        // Only meaningful when rich stats are present (non-neutral values).
        val t8Home = (0.25 * (1.0 - f.homeGkQuality) +
                      0.22 * (f.homeDefStrength - 1.0) * 0.8 -
                      0.18 * f.homeErrRate +
                      0.15 * f.awayErrRate).coerceIn(-0.15, 0.15)
        val t8Draw = -0.12 * kotlin.math.abs(t8Home)
        val t8Away = -t8Home

        val tw = w.treeWeights.let { if (it.size >= 6) it else it + listOf(0.10, 0.08).drop(it.size - 5) }

        var rawH = (tw[0]*t1H + tw[1]*t2H + tw[2]*t3H + tw[3]*t4H + tw[4]*t5H + tw[5]*t8Home).coerceIn(0.05, 0.85)
        var rawD = (tw[0]*t1D + tw[1]*t2D + tw[2]*t3D + tw[3]*t4D + tw[4]*t5D + tw[5]*t8Draw).coerceIn(0.05, 0.60)
        var rawA = (tw[0]*t1A + tw[1]*t2A + tw[2]*t3A + tw[3]*t4A + tw[4]*t5A + tw[5]*t8Away).coerceIn(0.05, 0.85)

        // ── T7: Rich defensive stats adjustment ──────────────────────────────
        // Applied as a soft multiplicative nudge after T1–T5 are aggregated.
        // Only active when rich stats were computed (non-neutral values present).
        // Each signal is capped to avoid dominating the combined prediction.
        //
        // GK quality: home GK factor < 1.0 → home team concedes less → raise homeH.
        // A below-average home GK (factor > 1.0) makes the home team more vulnerable.
        // We flip: factor < 1.0 (strong GK) → home concedes less → HOME wins more likely.
        val homeGkNudge = if (f.homeGkQuality != 1.0) (1.0 - f.homeGkQuality).coerceIn(-0.06, 0.06) else 0.0
        val awayGkNudge = if (f.awayGkQuality != 1.0) (1.0 - f.awayGkQuality).coerceIn(-0.06, 0.06) else 0.0

        // Defensive solidity: home team with high defStrength concedes less → home advantage.
        // Normalised to neutral at 1.0; effect capped at ±4%.
        val homeDefNudge = if (f.homeDefStrength != 1.0) ((f.homeDefStrength - 1.0) * 0.08).coerceIn(-0.04, 0.04) else 0.0
        val awayDefNudge = if (f.awayDefStrength != 1.0) ((f.awayDefStrength - 1.0) * 0.08).coerceIn(-0.04, 0.04) else 0.0

        // Error rate: high away error rate → home team benefits → nudge rawH up.
        // Symmetric: high home error rate → away team benefits.
        val errDiff = (f.awayErrRate - f.homeErrRate).coerceIn(-0.5, 0.5)
        val errNudge = (errDiff * 0.06).coerceIn(-0.03, 0.03)

        // Net T7 adjustment: positive → home more likely, negative → away more likely.
        // Draw probability is reduced when teams are differentiated by rich stats.
        val t7HomeAdj = homeGkNudge - awayGkNudge + homeDefNudge - awayDefNudge + errNudge
        val t7DrawAdj = -abs(t7HomeAdj) * 0.3
        val t7AwayAdj = -t7HomeAdj

        rawH = (rawH + t7HomeAdj).coerceIn(0.05, 0.85)
        rawD = (rawD + t7DrawAdj).coerceIn(0.05, 0.60)
        rawA = (rawA + t7AwayAdj).coerceIn(0.05, 0.85)

        // ── League bias adjustment ────────────────────────────────────────────
        // Leagues with high avgGoals (> 2.70) tend to produce more home wins.
        // Leagues with high homeAdv (> 1.12) amplify home win probability.
        // Both signals are normalized so they are neutral at the global average.
        if (f.leagueAvgGoals > 0.0) {
            val leagueGoalBias = ((f.leagueAvgGoals - 2.60) * 0.02).coerceIn(-0.03, 0.03)
            val leagueAdvBias  = ((f.leagueHomeAdv  - 1.10) * 0.08).coerceIn(-0.03, 0.03)
            rawH = (rawH + leagueGoalBias + leagueAdvBias).coerceIn(0.05, 0.85)
            rawA = (rawA - leagueAdvBias).coerceIn(0.05, 0.85)
        }

        // ── T6: Market odds blending ─────────────────────────────────────────
        // Only applied when all three 1X2 odds are valid (> 1.0).
        // Removes bookmaker overround by normalizing implied probabilities.
        // blend factor: oddsTreeW controls influence (1.0 → 30% market, 0.0 → no market).
        val hasOdds = f.homeOdds > 1.0 && f.drawOdds > 1.0 && f.awayOdds > 1.0
        if (hasOdds) {
            val hI  = 1.0 / f.homeOdds
            val dI  = 1.0 / f.drawOdds
            val aI  = 1.0 / f.awayOdds
            val tot = hI + dI + aI
            val mktH = hI / tot
            val mktD = dI / tot
            val mktA = aI / tot

            // Blend weight: caps at 35% market influence so stats still matter
            val blend = (w.oddsTreeW * 0.30).coerceIn(0.0, 0.35)
            rawH = rawH * (1.0 - blend) + mktH * blend
            rawD = rawD * (1.0 - blend) + mktD * blend
            rawA = rawA * (1.0 - blend) + mktA * blend
        }

        val total = rawH + rawD + rawA
        return Triple(rawH / total, rawD / total, rawA / total)
    }

    /**
     * Apply Platt scaling calibration to the raw forward-pass probabilities.
     *
     * Each class probability is mapped through:
     *   logit(p) = ln(p / (1 - p))
     *   calibrated_p = sigmoid(calibA * logit(p) + calibB)
     *
     * Then re-normalized to sum to 1.0 (since we calibrate all three independently).
     *
     * Calibration is skipped if weights are at default (calibA=1.0, calibB=0.0)
     * to preserve backward compatibility for newly installed models.
     */
    private fun applyPlattCalibration(rawH: Double, rawD: Double, rawA: Double, w: ModelWeights): Triple<Double, Double, Double> {
        if (abs(w.calibA - 1.0) < 0.001 && abs(w.calibB) < 0.001) return Triple(rawH, rawD, rawA)
        fun calibrate(p: Double): Double {
            val pc = p.coerceIn(0.001, 0.999)
            val logit = ln(pc / (1.0 - pc))
            return sigmoid(w.calibA * logit + w.calibB)
        }
        val cH = calibrate(rawH)
        val cD = calibrate(rawD)
        val cA = calibrate(rawA)
        val total = cH + cD + cA
        return if (total > 0.01) Triple(cH / total, cD / total, cA / total)
               else Triple(rawH, rawD, rawA)
    }

    fun predict(features: MLFeatureVector): Triple<Double, Double, Double> {
        val (rawH, rawD, rawA) = forwardPass(features, state.weights)

        val calibratedH = applyPlattScaling(rawH, state.weights.calibA, state.weights.calibB)
        val calibratedA = applyPlattScaling(rawA, state.weights.calibA, state.weights.calibB * 0.85)

        var pH = calibratedH
        var pA = calibratedA
        var pD = 1.0 - pH - pA

        val s = pH + pD + pA
        if (s > 0.001) { pH /= s; pD /= s; pA /= s }

        return Triple(pH.coerceIn(0.02, 0.96), pD.coerceIn(0.05, 0.60), pA.coerceIn(0.02, 0.96))
    }

    private fun applyPlattScaling(raw: Double, a: Double, b: Double): Double {
        val logit = raw * a + b
        return 1.0 / (1.0 + exp(-logit))
    }

    /**
     * Adjusts xG values based on bookmaker 1X2 odds for score prediction.
     *
     * Example — home=1.35, away=8.0:
     *   homeStrength = 0.856 → homeXg scales up ~1.97x
     *   awayXg scales down to 0.60x
     *   Total xG is redistributed (not inflated): home xG rises, away xG falls.
     *
     * Returns unchanged values when odds are 0.0 (not available).
     */
    private fun applyOddsAdjustment(
        homeXg: Double,
        awayXg: Double,
        homeOdds: Double,
        awayOdds: Double
    ): Pair<Double, Double> {
        if (homeOdds <= 1.0 || awayOdds <= 1.0) return Pair(homeXg, awayXg)
        val homeImplied = 1.0 / homeOdds
        val awayImplied = 1.0 / awayOdds
        val total = homeImplied + awayImplied
        if (total <= 0.01) return Pair(homeXg, awayXg)
        val homeStrength = homeImplied / total
        // Scale so that at 50/50 both multipliers = 1.0 (neutral, no inflation).
        // At 70/30: home = 1.40, away = 0.60 — redistributes xG without inflating total.
        // Previous formula (0.55 + 1.45 * strength) gave 1.275 for both at 50/50,
        // inflating combined xG by 27.5 % and causing wildly high score predictions.
        val homeScale = (homeStrength * 2.0).coerceIn(0.40, 1.60)
        val awayScale = ((1.0 - homeStrength) * 2.0).coerceIn(0.40, 1.60)
        return Pair(homeXg * homeScale, awayXg * awayScale)
    }

    /**
     * Goal market predictions with bookmaker odds integration.
     *
     * Over 2.5 blending (when over25Odds available):
     *   - Statistical model (logistic + Poisson): 55%
     *   - Market implied probability (1 / over25Odds, normalized): 45%
     *   This means if market says Over 2.5 = 1.75 (implied 57%), it pulls the final
     *   probability toward 57% regardless of what xG says.
     *
     * BTTS blending (when bttsYesOdds available):
     *   Same approach — 55% stats + 45% market.
     *
     * Most Likely Score:
     *   Uses odds-adjusted xG (via applyOddsAdjustment).
     *   Example: 1.35 vs 8.0 → home xG doubles → score becomes 2-1 instead of 1-1.
     */
    fun predictGoalMarkets(
        f: MLFeatureVector,
        elapsed: Int? = null,
        currentHomeScore: Int = 0,
        currentAwayScore: Int = 0,
        liveHomeXg: Double? = null,
        liveAwayXg: Double? = null
    ): GoalMarketPrediction {
        val w = state.weights
        val isLive = elapsed != null

        val minutesPlayed = elapsed?.coerceIn(1, 120) ?: 90
        val totalExpected = if (minutesPlayed >= 90) 95 else 90
        val minutesLeft   = if (isLive) (totalExpected - minutesPlayed).coerceAtLeast(0) else 90
        val remainFrac    = minutesLeft / totalExpected.toDouble()

        val liveWeight = if (isLive) (minutesPlayed / 90.0).coerceIn(0.0, 1.0) else 0.0
        val histWeight = 1.0 - liveWeight

        val histHomeRem = f.homeXgFor * remainFrac
        val histAwayRem = f.awayXgFor * remainFrac

        val liveHomeRem = if (isLive && liveHomeXg != null && liveHomeXg > 0)
            (liveHomeXg / minutesPlayed) * minutesLeft else null
        val liveAwayRem = if (isLive && liveAwayXg != null && liveAwayXg > 0)
            (liveAwayXg / minutesPlayed) * minutesLeft else null

        val effHomeXgRaw = if (liveHomeRem != null) liveHomeRem * liveWeight + histHomeRem * histWeight else histHomeRem
        val effAwayXgRaw = if (liveAwayRem != null) liveAwayRem * liveWeight + histAwayRem * histWeight else histAwayRem

        val alreadyOver25 = currentHomeScore + currentAwayScore > 2
        val alreadyBtts   = currentHomeScore > 0 && currentAwayScore > 0
        val minBound = if (isLive) 0.0 else 0.05

        // ── Over 2.5 ─────────────────────────────────────────────────────────
        val over25Prob: Double = if (alreadyOver25) {
            1.0
        } else {
            val xgSum    = effHomeXgRaw + effAwayXgRaw
            val avgGFSum = f.homeAvgGoalsFor + f.awayAvgGoalsFor
            val goalsNeeded = (3 - (currentHomeScore + currentAwayScore)).coerceAtLeast(1)

            val over25Score = w.over25Bias +
                              w.over25XgW      * (xgSum    - 2.5) +
                              w.over25AvgGoalW * (avgGFSum - 2.5)
            val logistic    = sigmoid(over25Score)
            val poissonBase = poissonOverThreshold(xgSum, goalsNeeded)
            val statsBased  = (logistic * 0.65 + poissonBase * 0.35).coerceIn(minBound, 0.95)

            // Blend in bookmaker Over 2.5 odds when available.
            // When both Over and Under odds are present, normalize to remove bookmaker margin.
            // Formula: normalizedOverProb = overImplied / (overImplied + underImplied)
            if (!isLive && f.over25Odds > 1.0) {
                val overImplied  = 1.0 / f.over25Odds
                val normalizedOver = if (f.under25Odds > 1.0) {
                    val underImplied = 1.0 / f.under25Odds
                    (overImplied / (overImplied + underImplied)).coerceIn(0.05, 0.95)
                } else {
                    overImplied.coerceIn(0.05, 0.95)
                }
                // 55% stats, 45% market — market is a strong signal for totals
                (statsBased * 0.55 + normalizedOver * 0.45).coerceIn(minBound, 0.95)
            } else {
                statsBased
            }
        }

        // ── BTTS ─────────────────────────────────────────────────────────────
        val bttsProb: Double = if (alreadyBtts) {
            1.0
        } else {
            val needHome = currentHomeScore == 0
            val needAway = currentAwayScore  == 0
            val poissonBtts = (if (needHome) (1.0 - poissonPmf(effHomeXgRaw, 0)) else 1.0) *
                              (if (needAway) (1.0 - poissonPmf(effAwayXgRaw, 0)) else 1.0)
            val bttsScore = w.bttsBias +
                            w.bttsHomeXgW * (effHomeXgRaw - 0.8) +
                            w.bttsAwayXgW * (effAwayXgRaw - 0.8)
            val logistic  = sigmoid(bttsScore)
            val statsBased = (logistic * 0.65 + poissonBtts * 0.35).coerceIn(minBound, 0.95)

            // Blend in bookmaker BTTS Yes odds when available.
            // When both Yes and No odds are present, normalize to remove bookmaker margin.
            if (!isLive && f.bttsYesOdds > 1.0) {
                val bttsYesImplied = 1.0 / f.bttsYesOdds
                val normalizedBtts = if (f.bttsNoOdds > 1.0) {
                    val bttsNoImplied = 1.0 / f.bttsNoOdds
                    (bttsYesImplied / (bttsYesImplied + bttsNoImplied)).coerceIn(0.05, 0.95)
                } else {
                    bttsYesImplied.coerceIn(0.05, 0.95)
                }
                (statsBased * 0.55 + normalizedBtts * 0.45).coerceIn(minBound, 0.95)
            } else {
                statsBased
            }
        }

        // ── Most Likely Score — odds-adjusted xG ─────────────────────────────
        val (effHomeXg, effAwayXg) = if (!isLive) {
            applyOddsAdjustment(effHomeXgRaw, effAwayXgRaw, f.homeOdds, f.awayOdds)
        } else {
            Pair(effHomeXgRaw, effAwayXgRaw)
        }

        val rho = -0.130
        var bestProb  = 0.0
        var bestScore = "$currentHomeScore-$currentAwayScore"
        for (addH in 0..6) {
            for (addA in 0..6) {
                var p = poissonPmf(effHomeXg, addH) * poissonPmf(effAwayXg, addA)
                val tau = when {
                    addH == 0 && addA == 0 -> 1.0 - effHomeXg * effAwayXg * rho
                    addH == 1 && addA == 0 -> 1.0 + effAwayXg * rho
                    addH == 0 && addA == 1 -> 1.0 + effHomeXg * rho
                    addH == 1 && addA == 1 -> 1.0 - rho
                    else                   -> 1.0
                }
                p *= tau.coerceAtLeast(0.0)
                val fH = currentHomeScore + addH
                val fA = currentAwayScore + addA
                if (p > bestProb) { bestProb = p; bestScore = "$fH-$fA" }
            }
        }

        val goalLines = if (isLive) computeGoalLines(
            f = f, effHomeXg = effHomeXgRaw, effAwayXg = effAwayXgRaw,
            currentHomeScore = currentHomeScore, currentAwayScore = currentAwayScore,
            isLive = true
        ) else emptyList()

        return GoalMarketPrediction(
            over25Prob        = over25Prob,
            bttsProb          = bttsProb,
            mlMostLikelyScore = bestScore,
            goalLines         = goalLines
        )
    }

    private fun computeGoalLines(
        f: MLFeatureVector,
        effHomeXg: Double,
        effAwayXg: Double,
        currentHomeScore: Int,
        currentAwayScore: Int,
        isLive: Boolean
    ): List<GoalLineResult> {
        val w = state.weights
        val currentGoals = currentHomeScore + currentAwayScore
        val allLines = listOf(0.5, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5, 9.5, 10.5)
        val xgSum = effHomeXg + effAwayXg
        val minBound = if (isLive) 0.0 else 0.05

        val relevantLines = allLines.filter { line -> currentGoals < line }.take(3)

        return relevantLines.map { line ->
            val goalsNeeded = (line + 0.5).toInt() - currentGoals
            val overProb = if (goalsNeeded <= 0) {
                1.0
            } else {
                val poissonBase = poissonOverThreshold(xgSum, goalsNeeded)
                val logisticScore = w.over25Bias +
                    w.over25XgW      * (xgSum - line) +
                    w.over25AvgGoalW * (f.homeAvgGoalsFor + f.awayAvgGoalsFor - line)
                val logistic = sigmoid(logisticScore)
                (logistic * 0.65 + poissonBase * 0.35).coerceIn(minBound, 0.99)
            }
            GoalLineResult(
                line = line,
                overProb = overProb,
                underProb = (1.0 - overProb).coerceIn(minBound, 0.99),
                alreadyGuaranteed = goalsNeeded <= 0
            )
        }
    }

    private fun poissonOverThreshold(xgSum: Double, goalsNeeded: Int): Double {
        if (goalsNeeded <= 0) return 1.0
        val half  = xgSum / 2.0
        var under = 0.0
        val max   = goalsNeeded - 1
        for (h in 0..max) for (a in 0..(max - h)) under += poissonPmf(half, h) * poissonPmf(half, a)
        return (1.0 - under).coerceIn(0.0, 1.0)
    }


    fun getModelStats(): ModelWeights = state.weights

    suspend fun trainFromHistory(
        entries: List<TrainEntry>,
        extraLrFactor: Double = 1.0,
        retrainPass: Boolean = false
    ): Int = withContext(Dispatchers.Default) {
        var trained = 0
        var weights = state.weights.copy()
        val completed = state.completedRecords.toMutableList()
        val alreadyTrained = if (retrainPass) emptySet() else completed.toSet()

        val nowMs = System.currentTimeMillis()
        val sorted = entries
            .filter { retrainPass || it.matchId !in alreadyTrained }
            .sortedBy { if (it.matchTimestamp > 0) it.matchTimestamp else 0L }

        for (entry in sorted) {
            val homeGoals   = entry.homeResult
            val awayGoals   = entry.awayResult
            val actualOutcome = when {
                homeGoals > awayGoals  -> 0
                homeGoals == awayGoals -> 1
                else                   -> 2
            }
            val actualOver25 = (homeGoals + awayGoals) > 2
            val actualBtts   = homeGoals > 0 && awayGoals > 0

            val adaptiveLr = 0.008 / (1.0 + 0.0008 * weights.totalMatchesTrained)
            val ageDays    = if (entry.matchTimestamp > 0) (nowMs - entry.matchTimestamp) / 86_400_000.0 else 60.0
            val temporalWeight = exp(-ageDays * 0.018).coerceIn(0.22, 1.0)
            val effectiveLr = adaptiveLr * temporalWeight * extraLrFactor

            val hasRealFeatures = entry.homeAvgGoalsFor > 0.0 || entry.homeXgFor > 0.0
            val features = if (hasRealFeatures) {
                // Use actual season win rates (homeActualWinRate) when available.
                // Old entries that pre-date this fix fall back to homeStrengthAdjWinRate which
                // is closer in scale to winRate than homeWinPct/100 (Poisson probability).
                val hWinRate  = entry.homeActualWinRate.takeIf { it > 0.0 }
                    ?: entry.homeStrengthAdjWinRate
                val aWinRate  = entry.awayActualWinRate.takeIf { it > 0.0 }
                    ?: entry.awayStrengthAdjWinRate
                val hHomeRate = entry.homeHomeActualWinRate.takeIf { it > 0.0 }
                    ?: hWinRate
                val aAwayRate = entry.awayAwayActualWinRate.takeIf { it > 0.0 }
                    ?: aWinRate

                MLFeatureVector(
                    homeWinRate            = hWinRate,
                    awayWinRate            = aWinRate,
                    homeAvgGoalsFor        = entry.homeAvgGoalsFor,
                    awayAvgGoalsFor        = entry.awayAvgGoalsFor,
                    homeAvgGoalsAgainst    = entry.homeAvgGoalsAgainst,
                    awayAvgGoalsAgainst    = entry.awayAvgGoalsAgainst,
                    homeRecentPoints       = entry.homeRecentPoints / 15.0,
                    awayRecentPoints       = entry.awayRecentPoints / 15.0,
                    homeTrend              = entry.homeTrend,
                    awayTrend              = entry.awayTrend,
                    homeXgFor              = entry.homeXgFor,
                    awayXgFor              = entry.awayXgFor,
                    h2hHomeWinRate         = entry.h2hHomeWinRate,
                    h2hAvgGoals            = entry.h2hAvgGoals,
                    homeAdvantage          = 0.5,
                    homePPG                = entry.homePPG,
                    awayPPG                = entry.awayPPG,
                    homeGoalDiff           = entry.homeGoalDiff,
                    awayGoalDiff           = entry.awayGoalDiff,
                    formDiff               = entry.formDiff,
                    homeStrengthAdjWinRate = entry.homeStrengthAdjWinRate,
                    awayStrengthAdjWinRate = entry.awayStrengthAdjWinRate,
                    homeHomeWinRate        = hHomeRate,
                    awayAwayWinRate        = aAwayRate,
                    homeMotivation         = entry.homeMotivation,
                    awayMotivation         = entry.awayMotivation,
                    homeOdds               = entry.homeOdds,
                    drawOdds               = entry.drawOdds,
                    awayOdds               = entry.awayOdds,
                    over25Odds             = entry.over25Odds,
                    bttsYesOdds            = entry.bttsYesOdds,
                    glickoRatingDiff       = entry.glickoRatingDiff,
                    homeGlickoRating       = entry.homeGlickoRating,
                    awayGlickoRating       = entry.awayGlickoRating,
                    homeGlickoRd           = entry.homeGlickoRd,
                    awayGlickoRd           = entry.awayGlickoRd,
                    homeGkQuality          = entry.homeGkQuality,
                    awayGkQuality          = entry.awayGkQuality,
                    homeDefStrength        = entry.homeDefStrength,
                    awayDefStrength        = entry.awayDefStrength,
                    homeErrRate            = entry.homeErrRate,
                    awayErrRate            = entry.awayErrRate
                )
            } else {
                MLFeatureVector(
                    homeWinRate            = entry.homeWinPct / 100.0,
                    awayWinRate            = entry.awayWinPct / 100.0,
                    homeAvgGoalsFor        = entry.homeWinPct / 33.3,
                    awayAvgGoalsFor        = entry.awayWinPct / 33.3,
                    homeAvgGoalsAgainst    = entry.awayWinPct / 33.3,
                    awayAvgGoalsAgainst    = entry.homeWinPct / 33.3,
                    homeRecentPoints       = entry.homeWinPct / 100.0,
                    awayRecentPoints       = entry.awayWinPct / 100.0,
                    homeTrend = 0.5, awayTrend = 0.5,
                    homeXgFor              = entry.homeWinPct / 33.3,
                    awayXgFor              = entry.awayWinPct / 33.3,
                    h2hHomeWinRate = 0.35, h2hAvgGoals = 2.5,
                    homeAdvantage = 0.5,
                    homePPG                = entry.homeWinPct / 100.0,
                    awayPPG                = entry.awayWinPct / 100.0,
                    homeGoalDiff           = (entry.homeWinPct - entry.awayWinPct) / 100.0,
                    awayGoalDiff           = (entry.awayWinPct - entry.homeWinPct) / 100.0,
                    formDiff               = (entry.homeWinPct - entry.awayWinPct) / 100.0,
                    homeStrengthAdjWinRate = entry.homeWinPct / 100.0,
                    awayStrengthAdjWinRate = entry.awayWinPct / 100.0,
                    homeHomeWinRate        = entry.homeWinPct / 100.0,
                    awayAwayWinRate        = entry.awayWinPct / 100.0,
                    homeMotivation = 1.0, awayMotivation = 1.0,
                    homeOdds = entry.homeOdds, drawOdds = entry.drawOdds, awayOdds = entry.awayOdds,
                    over25Odds = entry.over25Odds, bttsYesOdds = entry.bttsYesOdds
                )
            }

            val result = trainOnExample(features, actualOutcome, actualOver25, actualBtts, weights, effectiveLr)
            weights = result.first
            completed.add(entry.matchId)
            trained++
        }

        if (trained > 0) {
            state = state.copy(
                weights = weights.copy(
                    totalMatchesTrained = weights.totalMatchesTrained + trained,
                    version = weights.version + 1,
                    lastTrainedAt = java.util.Date().toString()
                ),
                completedRecords = completed.takeLast(6000)
            )
            saveState()
        }
        trained
    }

    private fun trainOnExample(
        features: MLFeatureVector,
        actualOutcome: Int,
        actualOver25: Boolean,
        actualBtts: Boolean,
        weights: ModelWeights,
        lr: Double = 0.01
    ): Pair<ModelWeights, Double> {
        // Raw forward pass — gradients computed on raw probs (not calibrated)
        val (rawH, rawD, rawA) = forwardPass(features, weights)

        // Softmax over raw outputs → training probabilities
        val expH = exp(rawH); val expD = exp(rawD); val expA = exp(rawA)
        val softSum = expH + expD + expA + 1e-8
        val pH = expH / softSum
        val pD = expD / softSum
        val pA = expA / softSum

        val targetH = if (actualOutcome == 0) 1.0 else 0.0
        val targetD = if (actualOutcome == 1) 1.0 else 0.0
        val targetA = if (actualOutcome == 2) 1.0 else 0.0

        val loss = -(targetH * ln(pH.coerceAtLeast(1e-7)) +
                     targetD * ln(pD.coerceAtLeast(1e-7)) +
                     targetA * ln(pA.coerceAtLeast(1e-7)))

        val errH = pH - targetH
        val errD = pD - targetD

        // ── Momentum-based update (Adam-lite with decay=0.92) ─────────────────
        // Smooths noisy gradient updates by exponentially weighting past gradients.
        // This stabilises training on small datasets where individual results
        // can be misleading. LR is reduced slightly to compensate for the smoothing.
        val momentum   = 0.92
        val lrScaled   = lr * 0.65  // effective LR after momentum scaling
        val newMomH    = momentum * weights.momentumH    + (1.0 - momentum) * errH
        val newMomD    = momentum * weights.momentumD    + (1.0 - momentum) * errD

        // ── Platt calibration gradient ─────────────────────────────────────────
        // calibA and calibB are trained with a slow learning rate to correct
        // systematic bias discovered over many matches. Small lr prevents calibration
        // from overfitting to any single result. Only trains after the main weights
        // have accumulated some data (totalMatchesTrained >= 20).
        val calibLr = (lr * 0.12).coerceAtMost(0.0015)
        val rawHSafe = rawH.coerceIn(0.001, 0.999)
        val logitH   = ln(rawHSafe / (1.0 - rawHSafe))
        val dSigma   = pH * (1.0 - pH)
        val newCalibA = if (weights.totalMatchesTrained >= 15)
            (weights.calibA - calibLr * errH * logitH * dSigma).coerceIn(0.65, 1.55)
        else weights.calibA
        val newCalibB = if (weights.totalMatchesTrained >= 15)
            (weights.calibB - calibLr * errH * dSigma * 0.6).coerceIn(-0.75, 0.75)
        else weights.calibB

        // ── Over 2.5 logistic gradient ───────────────────────────────────────
        val xgSum    = features.homeXgFor + features.awayXgFor
        val avgGFSum = features.homeAvgGoalsFor + features.awayAvgGoalsFor
        val over25Score = weights.over25Bias +
                          weights.over25XgW * (xgSum - 2.5) +
                          weights.over25AvgGoalW * (avgGFSum - 2.5)
        val pOver25 = sigmoid(over25Score)
        val tOver25 = if (actualOver25) 1.0 else 0.0
        val errOver = pOver25 - tOver25

        // ── BTTS logistic gradient ───────────────────────────────────────────
        val bttsScore = weights.bttsBias +
                        weights.bttsHomeXgW * (features.homeXgFor - 0.8) +
                        weights.bttsAwayXgW * (features.awayXgFor - 0.8)
        val pBtts   = sigmoid(bttsScore)
        val tBtts   = if (actualBtts) 1.0 else 0.0
        val errBtts = pBtts - tBtts

        val lrGoal = lr * 0.8

        // ── T6 odds tree gradient ─────────────────────────────────────────────
        // When market odds were available, train oddsTreeW based on how much the
        // market improved the prediction. If market was correct and stats were wrong,
        // increase oddsTreeW. If market was wrong, decrease it.
        val hasOdds = features.homeOdds > 1.0 && features.drawOdds > 1.0 && features.awayOdds > 1.0
        val newOddsTreeW = if (hasOdds) {
            val hI  = 1.0 / features.homeOdds
            val dI  = 1.0 / features.drawOdds
            val aI  = 1.0 / features.awayOdds
            val tot = hI + dI + aI
            val mktH = hI / tot
            // Gradient: if market H prob was closer to actual than model's H prob,
            // market was useful → increase oddsTreeW
            val mktErrH   = mktH - targetH           // market error on home
            val modelErrH = errH                   // model error on home
            // When |mktErrH| < |modelErrH| → market was better → increase weight
            val oddsGrad = (modelErrH - mktErrH) * 0.1
            (weights.oddsTreeW - lr * oddsGrad).coerceIn(0.0, 2.0)
        } else {
            weights.oddsTreeW
        }

        // ── Over/BTTS momentum ────────────────────────────────────────────────
        val newMomOver = momentum * weights.momentumOver + (1.0 - momentum) * errOver
        val newMomBtts = momentum * weights.momentumBtts + (1.0 - momentum) * errBtts

        return Pair(
            weights.copy(
                homeAdvBias     = (weights.homeAdvBias     - lrScaled * newMomH * features.homeAdvantage).coerceIn(-0.2, 0.3),
                drawCalibration = (weights.drawCalibration - lrScaled * newMomD).coerceIn(0.10, 0.40),
                homeWinRateW    = (weights.homeWinRateW    - lrScaled * newMomH * features.homeWinRate * 0.3).coerceIn(0.3, 2.0),
                awayWinRateW    = (weights.awayWinRateW    + lrScaled * newMomH * features.awayWinRate * 0.1).coerceIn(0.3, 2.0),
                h2hW            = (weights.h2hW            - lrScaled * newMomH * features.h2hHomeWinRate * 0.4).coerceIn(0.3, 2.0),
                strengthAdjW    = (weights.strengthAdjW    - lrScaled * newMomH * features.homeStrengthAdjWinRate * 0.35).coerceIn(0.3, 2.0),
                homeAwayW       = (weights.homeAwayW       - lrScaled * newMomH * features.homeHomeWinRate * 0.2).coerceIn(0.3, 2.0),
                motivationW     = (weights.motivationW     - lrScaled * newMomH * (features.homeMotivation - 1.0) * 0.15).coerceIn(0.3, 2.0),
                xgRatioW        = (weights.xgRatioW        - lrScaled * newMomH * (features.homeXgFor - features.awayXgFor) * 0.25).coerceIn(0.3, 2.0),
                ppgDiffW        = (weights.ppgDiffW        - lrScaled * newMomH * (features.homePPG - features.awayPPG) * 0.10).coerceIn(0.3, 2.0),
                gdDiffW         = (weights.gdDiffW         - lrScaled * newMomH * (features.homeGoalDiff - features.awayGoalDiff) * 0.15).coerceIn(0.3, 2.0),
                homeRecentW     = (weights.homeRecentW     - lrScaled * newMomH * features.homeRecentPoints * 0.15).coerceIn(0.3, 2.0),
                awayRecentW     = (weights.awayRecentW     + lrScaled * newMomH * features.awayRecentPoints * 0.10).coerceIn(0.3, 2.0),
                homeTrendW      = (weights.homeTrendW      - lrScaled * newMomH * features.homeTrend * 0.20).coerceIn(0.3, 2.0),
                awayTrendW      = (weights.awayTrendW      + lrScaled * newMomH * features.awayTrend * 0.10).coerceIn(0.3, 2.0),
                over25Bias      = (weights.over25Bias      - lrGoal * newMomOver).coerceIn(-2.0, 2.0),
                over25XgW       = (weights.over25XgW       - lrGoal * newMomOver * (xgSum - 2.5)).coerceIn(0.3, 3.0),
                over25AvgGoalW  = (weights.over25AvgGoalW  - lrGoal * newMomOver * (avgGFSum - 2.5) * 0.5).coerceIn(0.3, 3.0),
                bttsBias        = (weights.bttsBias        - lrGoal * newMomBtts).coerceIn(-2.0, 2.0),
                bttsHomeXgW     = (weights.bttsHomeXgW     - lrGoal * newMomBtts * (features.homeXgFor - 0.8)).coerceIn(0.3, 3.0),
                bttsAwayXgW     = (weights.bttsAwayXgW     - lrGoal * newMomBtts * (features.awayXgFor - 0.8)).coerceIn(0.3, 3.0),
                oddsTreeW       = newOddsTreeW,
                calibA          = newCalibA,
                calibB          = newCalibB,
                momentumH       = newMomH,
                momentumD       = newMomD,
                momentumOver    = newMomOver,
                momentumBtts    = newMomBtts
            ),
            loss
        )
    }

    private fun poissonPmf(lambda: Double, k: Int): Double {
        if (lambda <= 0) return if (k == 0) 1.0 else 0.0
        var logP = -lambda + k * ln(lambda)
        for (i in 1..k) logP -= ln(i.toDouble())
        return exp(logP)
    }
}
