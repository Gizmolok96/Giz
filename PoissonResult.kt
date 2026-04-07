package com.footballanalyzer.app.ml.poisson

data class PoissonResult(
    val homeWinProb: Double,
    val drawProb: Double,
    val awayWinProb: Double,
    val over25Prob: Double,
    val bttsProb: Double,
    val mostLikelyScore: String,
    val expectedTotalGoals: Double,
    val scoreProbabilities: Map<String, Double> = emptyMap()
)
