package com.footballanalyzer.app.ml

import com.footballanalyzer.app.data.model.GlickoData
import com.footballanalyzer.app.data.model.TeamFeatures
import com.footballanalyzer.app.ml.poisson.PoissonEngine

data class HybridPrediction(
    val homeWinProb: Double,
    val drawProb: Double,
    val awayWinProb: Double,
    val over25Prob: Double,
    val bttsProb: Double,
    val mostLikelyScore: String,
    val confidence: Double,
    val expectedTotalGoals: Double = 0.0,
    val source: String = "Hybrid v2 (Poisson + ML + Glicko)"
)

class HybridPredictor(
    private val poissonEngine: PoissonEngine = PoissonEngine(),
    private val mlModel: MLModel
) {

    fun predict(
        home: TeamFeatures,
        away: TeamFeatures,
        glicko: GlickoData?,
        homeOdds: Double = 0.0,
        drawOdds: Double = 0.0,
        awayOdds: Double = 0.0,
        over25Odds: Double = 0.0,
        under25Odds: Double = 0.0,
        bttsYesOdds: Double = 0.0,
        bttsNoOdds: Double = 0.0,
        h2hHomeWinRate: Double = 0.35,
        h2hAvgGoals: Double = 2.5,
        leagueAvgGoals: Double = 2.60,
        leagueHomeAdv: Double = 1.10,
        leagueRho: Double = -0.125
    ): HybridPrediction {

        val poisson = poissonEngine.calculateFromFeatures(home, away, leagueAvgGoals, leagueHomeAdv, leagueRho)

        val mlFeatures = createMLFeatureVector(
            home           = home,
            away           = away,
            glicko         = glicko,
            homeOdds       = homeOdds,
            drawOdds       = drawOdds,
            awayOdds       = awayOdds,
            over25Odds     = over25Odds,
            under25Odds    = under25Odds,
            bttsYesOdds    = bttsYesOdds,
            bttsNoOdds     = bttsNoOdds,
            h2hHomeWinRate = h2hHomeWinRate,
            h2hAvgGoals    = h2hAvgGoals,
            leagueAvgGoals = leagueAvgGoals,
            leagueHomeAdv  = leagueHomeAdv
        )
        val (mlHome, mlDraw, mlAway) = mlModel.predict(mlFeatures)
        val goalMarkets = mlModel.predictGoalMarkets(mlFeatures)

        // ── Adaptive Glicko weight based on rating reliability ─────────────────
        val glickoWeight = when {
            glicko?.available != true                               -> 0.0
            glicko.homeRd < 90 && glicko.awayRd < 90               -> 0.45
            glicko.homeRd < 130 || glicko.awayRd < 130             -> 0.28
            glicko.homeRd < 180                                     -> 0.20
            else                                                    -> 0.12
        }

        val gH = glicko?.homeWinProbability ?: 0.0
        val gA = glicko?.awayWinProbability ?: 0.0
        val gD = if (gH + gA > 0) (1.0 - gH - gA).coerceAtLeast(0.05) else poisson.drawProb

        // ── Adaptive ML weight based on how much history was trained ──────────
        val trainedMatches = mlModel.getModelStats().totalMatchesTrained
        val mlW = when {
            trainedMatches > 120 -> 0.48
            trainedMatches > 80  -> 0.43
            trainedMatches > 40  -> 0.38
            else                 -> 0.32
        }
        val poissonW = 0.42
        val gW = glickoWeight

        var finalHome = poisson.homeWinProb * poissonW + mlHome * mlW + gH * gW
        var finalDraw = poisson.drawProb    * poissonW + mlDraw * mlW + gD * gW
        var finalAway = poisson.awayWinProb * poissonW + mlAway * mlW + gA * gW

        val total = finalHome + finalDraw + finalAway
        if (total > 0.001) {
            finalHome /= total
            finalDraw /= total
            finalAway /= total
        }

        val finalOver25 = poisson.over25Prob * 0.55 + goalMarkets.over25Prob * 0.45
        val finalBtts   = poisson.bttsProb   * 0.55 + goalMarkets.bttsProb   * 0.45

        val confidence = ((finalHome - 0.33).coerceIn(-0.25, 0.25) * 1.8 + 0.55).coerceIn(0.40, 0.88)

        // ── Use Poisson score matrix for most likely score ─────────────────────
        // The Poisson matrix already computes the joint probability for every
        // scoreline up to 5-5. Using it directly is far more accurate than any
        // heuristic formula based on final probabilities.
        val mostLikelyScore = poisson.mostLikelyScore

        return HybridPrediction(
            homeWinProb        = finalHome,
            drawProb           = finalDraw,
            awayWinProb        = finalAway,
            over25Prob         = finalOver25,
            bttsProb           = finalBtts,
            mostLikelyScore    = mostLikelyScore,
            confidence         = confidence,
            expectedTotalGoals = poisson.expectedTotalGoals
        )
    }

    private fun createMLFeatureVector(
        home: TeamFeatures,
        away: TeamFeatures,
        glicko: GlickoData?,
        homeOdds: Double,
        drawOdds: Double,
        awayOdds: Double,
        over25Odds: Double,
        under25Odds: Double,
        bttsYesOdds: Double,
        bttsNoOdds: Double,
        h2hHomeWinRate: Double,
        h2hAvgGoals: Double,
        leagueAvgGoals: Double,
        leagueHomeAdv: Double
    ): MLFeatureVector = MLFeatureVector(
        homeWinRate            = home.winRate,
        awayWinRate            = away.winRate,
        homeAvgGoalsFor        = home.avgGoalsFor,
        awayAvgGoalsFor        = away.avgGoalsFor,
        homeAvgGoalsAgainst    = home.avgGoalsAgainst,
        awayAvgGoalsAgainst    = away.avgGoalsAgainst,
        homeRecentPoints       = home.recentPoints.toDouble() / 15.0,
        awayRecentPoints       = away.recentPoints.toDouble() / 15.0,
        homeTrend              = home.trend,
        awayTrend              = away.trend,
        homeXgFor              = home.xgForAvg,
        awayXgFor              = away.xgForAvg,
        h2hHomeWinRate         = h2hHomeWinRate,
        h2hAvgGoals            = h2hAvgGoals,
        homeAdvantage          = home.homeAdvantage,
        homePPG                = home.pointsPerGame,
        awayPPG                = away.pointsPerGame,
        homeGoalDiff           = home.avgGoalsFor - home.avgGoalsAgainst,
        awayGoalDiff           = away.avgGoalsFor - away.avgGoalsAgainst,
        formDiff               = home.winRate - away.winRate,
        homeStrengthAdjWinRate = home.strengthAdjustedWinRate,
        awayStrengthAdjWinRate = away.strengthAdjustedWinRate,
        homeHomeWinRate        = home.homeWinRate,
        awayAwayWinRate        = away.awayWinRate,
        homeMotivation         = home.motivationFactor,
        awayMotivation         = away.motivationFactor,
        homeGkQuality          = home.gkQualityFactor,
        awayGkQuality          = away.gkQualityFactor,
        homeDefStrength        = home.defSolidityFactor,
        awayDefStrength        = away.defSolidityFactor,
        homeErrRate            = home.avgErrorsLeadingToGoal,
        awayErrRate            = away.avgErrorsLeadingToGoal,
        glickoRatingDiff       = glicko?.ratingDiff   ?: 0.0,
        homeGlickoRating       = glicko?.homeRating   ?: 1500.0,
        awayGlickoRating       = glicko?.awayRating   ?: 1500.0,
        homeGlickoRd           = glicko?.homeRd       ?: 350.0,
        awayGlickoRd           = glicko?.awayRd       ?: 350.0,
        homeOdds               = homeOdds,
        drawOdds               = drawOdds,
        awayOdds               = awayOdds,
        over25Odds             = over25Odds,
        under25Odds            = under25Odds,
        bttsYesOdds            = bttsYesOdds,
        bttsNoOdds             = bttsNoOdds,
        leagueAvgGoals         = leagueAvgGoals,
        leagueHomeAdv          = leagueHomeAdv
    )
}
