package com.footballanalyzer.app.ml.poisson

import com.footballanalyzer.app.data.model.TeamFeatures
import kotlin.math.exp
import kotlin.math.max

class PoissonEngine {

    private val MAX_GOALS = 7

    fun calculateMatch(
        lambdaHome: Double,
        lambdaAway: Double,
        leagueRho: Double = -0.125,
        homeAdvantage: Double = 1.0
    ): PoissonResult {

        val adjHome = lambdaHome * homeAdvantage
        val adjAway = lambdaAway

        var homeWin = 0.0
        var draw = 0.0
        var awayWin = 0.0
        var over25 = 0.0
        var btts = 0.0
        var totalProb = 0.0
        var bestScore = "0-0"
        var bestProb = 0.0
        var expectedTotal = 0.0

        val matrix = Array(MAX_GOALS + 1) { DoubleArray(MAX_GOALS + 1) }

        for (h in 0..MAX_GOALS) {
            for (a in 0..MAX_GOALS) {
                var p = poissonPmf(adjHome, h) * poissonPmf(adjAway, a)

                val tau = when {
                    h == 0 && a == 0 -> 1.0 - adjHome * adjAway * leagueRho
                    h == 1 && a == 0 -> 1.0 + adjAway * leagueRho
                    h == 0 && a == 1 -> 1.0 + adjHome * leagueRho
                    h == 1 && a == 1 -> 1.0 - leagueRho
                    else -> 1.0
                }
                p *= max(tau, 0.0)

                matrix[h][a] = p
                totalProb += p
                expectedTotal += p * (h + a)

                when {
                    h > a -> homeWin += p
                    h == a -> draw += p
                    else -> awayWin += p
                }

                if (h + a > 2) over25 += p
                if (h > 0 && a > 0) btts += p

                if (p > bestProb) {
                    bestProb = p
                    bestScore = "$h-$a"
                }
            }
        }

        if (totalProb > 0.0) {
            homeWin /= totalProb
            draw /= totalProb
            awayWin /= totalProb
            over25 /= totalProb
            btts /= totalProb
        }

        val scoreMap = mutableMapOf<String, Double>()
        for (h in 0..5) for (a in 0..5) {
            val prob = matrix[h][a] / totalProb
            if (prob > 0.008) scoreMap["$h-$a"] = prob
        }

        return PoissonResult(
            homeWinProb = homeWin.coerceIn(0.0, 1.0),
            drawProb = draw.coerceIn(0.0, 1.0),
            awayWinProb = awayWin.coerceIn(0.0, 1.0),
            over25Prob = over25.coerceIn(0.0, 1.0),
            bttsProb = btts.coerceIn(0.0, 1.0),
            mostLikelyScore = bestScore,
            expectedTotalGoals = expectedTotal,
            scoreProbabilities = scoreMap
        )
    }

    private fun poissonPmf(lambda: Double, k: Int): Double {
        if (lambda <= 0.0) return if (k == 0) 1.0 else 0.0
        var logP = -lambda + k * kotlin.math.ln(lambda)
        for (i in 1..k) logP -= kotlin.math.ln(i.toDouble())
        return exp(logP)
    }

    fun calculateFromFeatures(
        home: TeamFeatures,
        away: TeamFeatures,
        leagueAvgGoals: Double = 2.60,
        leagueHomeAdv: Double = 1.10,
        leagueRho: Double = -0.125
    ): PoissonResult {

        // richXgEstimate threshold lowered to 0.4 (was 0.5):
        // The rich xG calculation has a floor of ~0.20 for missing data and
        // realistic estimates typically start at 0.40+. Using richXg whenever
        // it exceeds 0.40 means we capture more stat-backed estimates.
        val homeAttack = when {
            home.richXgEstimate > 0.4 -> home.richXgEstimate
            home.xgForAvg > 0.4       -> home.xgForAvg
            else                       -> home.avgGoalsFor
        }

        val awayAttack = when {
            away.richXgEstimate > 0.4 -> away.richXgEstimate
            away.xgForAvg > 0.4       -> away.xgForAvg
            else                       -> away.avgGoalsFor
        }

        val homeDefense = when {
            home.richXgAgainstEstimate > 0.4 -> home.richXgAgainstEstimate
            home.xgAgainstAvg > 0.4           -> home.xgAgainstAvg
            else                               -> home.avgGoalsAgainst
        }

        val awayDefense = when {
            away.richXgAgainstEstimate > 0.4 -> away.richXgAgainstEstimate
            away.xgAgainstAvg > 0.4           -> away.xgAgainstAvg
            else                               -> away.avgGoalsAgainst
        }

        val lambdaHome = (homeAttack * awayDefense / leagueAvgGoals) * leagueHomeAdv
        val lambdaAway = awayAttack * homeDefense / leagueAvgGoals

        return calculateMatch(lambdaHome, lambdaAway, leagueRho, leagueHomeAdv)
    }
}
