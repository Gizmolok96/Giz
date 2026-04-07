package com.footballanalyzer.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footballanalyzer.app.data.local.HistoryStorage
import com.footballanalyzer.app.data.model.AnalysisResponse
import com.footballanalyzer.app.data.model.BookmakerOdds
import com.footballanalyzer.app.data.model.GoalLinePrediction
import com.footballanalyzer.app.data.model.HistoryEntry
import com.footballanalyzer.app.data.model.MLPrediction
import com.footballanalyzer.app.data.model.TrainingStats
import com.footballanalyzer.app.data.model.restoreAnalysisSnapshot
import com.footballanalyzer.app.data.model.toSavedMlPrediction
import com.footballanalyzer.app.data.model.withLockedMlPrediction
import com.footballanalyzer.app.data.model.withLockedPoissonSnapshot
import com.footballanalyzer.app.data.repository.FootballRepository
import com.footballanalyzer.app.ml.HybridPredictor
import com.footballanalyzer.app.ml.MLFeatureVector
import com.footballanalyzer.app.ml.MLModel
import com.footballanalyzer.app.viewmodel.MLStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class AnalysisUiState(
    val isLoading: Boolean = false,
    val result: AnalysisResponse? = null,
    val error: String? = null,
    val mlStats: MLStats? = null
)

class AnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val repository      = FootballRepository()
    private val historyStorage  = HistoryStorage(application)
    private val mlModel         = MLModel(application)
    private val hybridPredictor = HybridPredictor(mlModel = mlModel)

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState

    fun analyze(matchId: String) {
        _uiState.value = AnalysisUiState(isLoading = true)
        viewModelScope.launch {
            try {
                val result = repository.analyzeMatch(matchId)

                val finalResult = if (result.match.isFinished()) {
                    val savedEntry = historyStorage.getAllEntries()
                        .find { it.matchId == matchId }

                    var workingAnalysis = result.analysis

                    if (savedEntry?.poissonSnapshotSaved == true) {
                        workingAnalysis = savedEntry.restoreAnalysisSnapshot(workingAnalysis)
                    }

                    if (savedEntry?.mlPredictionSaved == true) {
                        val savedMl = savedEntry.toSavedMlPrediction().copy(
                            trainingStats = result.analysis.mlPrediction?.trainingStats
                        )
                        workingAnalysis = workingAnalysis.copy(mlPrediction = savedMl)
                    }

                    result.copy(analysis = workingAnalysis)
                } else {
                    enrichWithHybridPrediction(result)
                }

                val weights = mlModel.currentWeights()
                val stats = MLStats(
                    totalTrained  = weights.totalMatchesTrained,
                    modelVersion  = weights.version,
                    accuracy7day  = weights.accuracy7day,
                    accuracy30day = weights.accuracy30day
                )
                _uiState.value = AnalysisUiState(result = finalResult, mlStats = stats)
                saveToHistory(finalResult)
            } catch (e: Exception) {
                _uiState.value = AnalysisUiState(error = e.message ?: "Analysis failed")
            }
        }
    }

    fun retry(matchId: String) { analyze(matchId) }

    private fun pickBestOdds(prematch: List<BookmakerOdds>): BookmakerOdds? {
        return prematch.firstOrNull {
            it.homeWin != null && it.homeWin > 1.0 &&
            it.draw    != null && it.draw    > 1.0 &&
            it.awayWin != null && it.awayWin > 1.0
        }
    }

    /**
     * Picks the best Over 2.5 odds from prematch bookmakers.
     * Returns 0.0 if not available.
     */
    private fun pickOver25Odds(prematch: List<BookmakerOdds>): Double {
        return prematch.firstOrNull { it.over25 != null && it.over25 > 1.0 }?.over25 ?: 0.0
    }

    /**
     * Picks the best Under 2.5 odds from prematch bookmakers.
     * Returns 0.0 if not available.
     */
    private fun pickUnder25Odds(prematch: List<BookmakerOdds>): Double {
        return prematch.firstOrNull { it.under25 != null && it.under25 > 1.0 }?.under25 ?: 0.0
    }

    /**
     * Picks the best BTTS Yes odds from prematch bookmakers.
     * Returns 0.0 if not available.
     */
    private fun pickBttsYesOdds(prematch: List<BookmakerOdds>): Double {
        return prematch.firstOrNull { it.bttsYes != null && it.bttsYes > 1.0 }?.bttsYes ?: 0.0
    }

    /**
     * Picks the best BTTS No odds from prematch bookmakers.
     * Returns 0.0 if not available.
     */
    private fun pickBttsNoOdds(prematch: List<BookmakerOdds>): Double {
        return prematch.firstOrNull { it.bttsNo != null && it.bttsNo > 1.0 }?.bttsNo ?: 0.0
    }

    /**
     * Enriches the response with a HybridPredictor prediction (Poisson + ML + Glicko-2).
     *
     * IMPORTANT: Only mlPrediction is updated here. The analysis-level percentages
     * (homeWinPct, drawPct, awayWinPct, etc.) are intentionally left untouched —
     * they hold the pure Poisson+DC values from the repository and are displayed
     * in the UI as the "Poisson+DC" column. The hybrid result goes into mlPrediction,
     * shown as the "Neural Net" column, so the Delta is meaningful.
     */
    private fun enrichWithHybridPrediction(response: AnalysisResponse): AnalysisResponse {
        val home   = response.analysis.homeFeatures
        val away   = response.analysis.awayFeatures
        val glicko = response.analysis.glickoData
        val h2h    = response.analysis.h2h

        val prematch    = response.analysis.matchOdds.prematch
        val bestOdds    = pickBestOdds(prematch)
        val homeOdds    = bestOdds?.homeWin  ?: 0.0
        val drawOdds    = bestOdds?.draw     ?: 0.0
        val awayOdds    = bestOdds?.awayWin  ?: 0.0
        val over25Odds  = pickOver25Odds(prematch)
        val under25Odds = pickUnder25Odds(prematch)
        val bttsYesOdds = pickBttsYesOdds(prematch)
        val bttsNoOdds  = pickBttsNoOdds(prematch)

        val h2hHomeWinRate = if (h2h.total > 0) h2h.homeWins.toDouble() / h2h.total else 0.35
        val h2hAvgGoals    = h2h.avgGoals

        val hybrid = hybridPredictor.predict(
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
            h2hAvgGoals    = h2hAvgGoals
        )

        val mlPrediction = MLPrediction(
            homeWinProb       = hybrid.homeWinProb,
            drawProb          = hybrid.drawProb,
            awayWinProb       = hybrid.awayWinProb,
            over25Prob        = hybrid.over25Prob,
            bttsProb          = hybrid.bttsProb,
            mlMostLikelyScore = hybrid.mostLikelyScore,
            confidence        = hybrid.confidence,
            modelUsed         = hybrid.source,
            available         = true,
            isDefaultWeights  = false,
            trainingStats     = response.analysis.mlPrediction?.trainingStats
        )

        // Only mlPrediction is updated — Poisson percentages stay intact for UI comparison
        return response.copy(
            analysis = response.analysis.copy(mlPrediction = mlPrediction)
        )
    }

        private fun saveToHistory(response: AnalysisResponse) {
        val match    = response.match
        val analysis = response.analysis
        val hf  = analysis.homeFeatures
        val af  = analysis.awayFeatures
        val h2h = analysis.h2h
        val glicko = analysis.glickoData

        // ── Extract odds to save alongside the history entry ─────────────────
        val prematch    = analysis.matchOdds.prematch
        val bestOdds    = pickBestOdds(prematch)
        val homeOdds    = bestOdds?.homeWin ?: 0.0
        val drawOdds    = bestOdds?.draw    ?: 0.0
        val awayOdds    = bestOdds?.awayWin ?: 0.0
        val over25Odds  = pickOver25Odds(prematch)
        val under25Odds = pickUnder25Odds(prematch)
        val bttsYesOdds = pickBttsYesOdds(prematch)
        val bttsNoOdds  = pickBttsNoOdds(prematch)

        // ── Check if this match already has saved ML / Poisson predictions ───
        // If so, we must NOT overwrite them (they are the pre-match snapshot).
        val existingEntry = historyStorage.getAllEntries()
            .find { it.matchId == match.getMatchId() }

        val entry = HistoryEntry(
            id              = existingEntry?.id ?: UUID.randomUUID().toString(),
            matchId         = match.getMatchId(),
            homeTeam        = match.homeTeamName,
            awayTeam        = match.awayTeamName,
            homeResult      = match.homeResult,
            awayResult      = match.awayResult,
            statusName      = match.statusName,
            league          = match.leagueName,
            homeWinPct      = analysis.homeWinPct,
            drawPct         = analysis.drawPct,
            awayWinPct      = analysis.awayWinPct,
            confidenceScore = analysis.confidenceScore,
            mostLikelyScore = analysis.mostLikelyScore,
            viewedAt        = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
            mlModelVersion  = analysis.mlPrediction?.trainingStats?.modelVersion,
            homeAvgGoalsFor        = hf.avgGoalsFor,
            homeAvgGoalsAgainst    = hf.avgGoalsAgainst,
            awayAvgGoalsFor        = af.avgGoalsFor,
            awayAvgGoalsAgainst    = af.avgGoalsAgainst,
            homePPG                = hf.pointsPerGame,
            awayPPG                = af.pointsPerGame,
            homeTrend              = hf.trend,
            awayTrend              = af.trend,
            homeXgFor              = hf.xgForAvg,
            awayXgFor              = af.xgForAvg,
            h2hHomeWinRate         = if (h2h.total > 0) h2h.homeWins.toDouble() / h2h.total else 0.35,
            h2hAvgGoals            = h2h.avgGoals,
            homeStrengthAdjWinRate = hf.strengthAdjustedWinRate.takeIf { it > 0 } ?: hf.winRate,
            awayStrengthAdjWinRate = af.strengthAdjustedWinRate.takeIf { it > 0 } ?: af.winRate,
            homeRecentPoints       = hf.recentPoints.toDouble(),
            awayRecentPoints       = af.recentPoints.toDouble(),
            homeGoalDiff           = hf.avgGoalsFor - hf.avgGoalsAgainst,
            awayGoalDiff           = af.avgGoalsFor - af.avgGoalsAgainst,
            formDiff               = hf.winRate - af.winRate,
            homeMotivation         = hf.motivationFactor,
            awayMotivation         = af.motivationFactor,
            matchTimestamp         = existingEntry?.matchTimestamp?.takeIf { it > 0 }
                                         ?: System.currentTimeMillis(),
            // ── Save bookmaker odds for ML training ───────────────────────────
            homeOdds               = homeOdds,
            drawOdds               = drawOdds,
            awayOdds               = awayOdds,
            over25Odds             = over25Odds,
            under25Odds            = under25Odds,
            bttsYesOdds            = bttsYesOdds,
            bttsNoOdds             = bttsNoOdds,
            // ── Actual win rates aligned with ML prediction feature vector ────
            homeActualWinRate      = hf.winRate,
            awayActualWinRate      = af.winRate,
            homeHomeActualWinRate  = if (hf.homeMatches >= 2) hf.homeWinRate else hf.winRate,
            awayAwayActualWinRate  = if (af.awayMatches >= 2) af.awayWinRate else af.winRate,
            // ── Glicko-2 data for ML feature vector ───────────────────────────
            glickoRatingDiff       = glicko?.ratingDiff  ?: 0.0,
            homeGlickoRating       = glicko?.homeRating   ?: 1500.0,
            awayGlickoRating       = glicko?.awayRating   ?: 1500.0,
            homeGlickoRd           = glicko?.homeRd       ?: 350.0,
            awayGlickoRd           = glicko?.awayRd       ?: 350.0,
            // ── Rich stats: GK quality, defensive solidity, error rate ────────
            homeGkQuality          = hf.gkQualityFactor,
            awayGkQuality          = af.gkQualityFactor,
            homeDefStrength        = if (hf.defSolidityFactor > 0.0) hf.defSolidityFactor else 1.0,
            awayDefStrength        = if (af.defSolidityFactor > 0.0) af.defSolidityFactor else 1.0,
            homeErrRate            = hf.avgErrorsLeadingToGoal,
            awayErrRate            = af.avgErrorsLeadingToGoal
        )

        val ml = analysis.mlPrediction

        val finalEntry = when {
              // Case 1: match is upcoming (not started, not live, not finished) → lock pre-match
              //   ML prediction AND Poisson analysis snapshot.
              //   IMPORTANT: Only lock on FIRST analysis. If a snapshot already exists from a
              //   previous open, preserve it unchanged so the user always sees the same numbers
              //   when re-opening an upcoming match from history (no unexpected recalculations).
              !match.isFinished() && !match.isLive() && ml != null && ml.available -> {
                  if (existingEntry?.poissonSnapshotSaved == true) {
                      // Snapshot was already captured on first open — keep it frozen
                      entry.copy(
                          savedMlHomeWinProb          = existingEntry.savedMlHomeWinProb,
                          savedMlDrawProb             = existingEntry.savedMlDrawProb,
                          savedMlAwayWinProb          = existingEntry.savedMlAwayWinProb,
                          savedMlOver25Prob           = existingEntry.savedMlOver25Prob,
                          savedMlBttsProb             = existingEntry.savedMlBttsProb,
                          savedMlMostLikelyScore      = existingEntry.savedMlMostLikelyScore,
                          mlPredictionSaved           = true,
                          savedHomeXg                 = existingEntry.savedHomeXg,
                          savedAwayXg                 = existingEntry.savedAwayXg,
                          savedPoissonHomeWinPct      = existingEntry.savedPoissonHomeWinPct,
                          savedPoissonDrawPct         = existingEntry.savedPoissonDrawPct,
                          savedPoissonAwayWinPct      = existingEntry.savedPoissonAwayWinPct,
                          savedPoissonMostLikelyScore = existingEntry.savedPoissonMostLikelyScore,
                          savedOver25Pct              = existingEntry.savedOver25Pct,
                          savedBttsPct                = existingEntry.savedBttsPct,
                          savedExpectedTotal          = existingEntry.savedExpectedTotal,
                          savedConfidenceScore        = existingEntry.savedConfidenceScore,
                          poissonSnapshotSaved        = true
                      )
                  } else {
                      // First time this match is opened — save the snapshot now
                      entry.withLockedMlPrediction(ml).withLockedPoissonSnapshot(analysis)
                  }
              }
              // Case 2: match finished, and we already saved the pre-match snapshot → keep it.
              // Restore ALL saved fields so repeated saves don't overwrite the snapshot.
              match.isFinished() && existingEntry?.mlPredictionSaved == true -> {
                  entry.copy(
                      // Restore ML snapshot
                      savedMlHomeWinProb     = existingEntry.savedMlHomeWinProb,
                      savedMlDrawProb        = existingEntry.savedMlDrawProb,
                      savedMlAwayWinProb     = existingEntry.savedMlAwayWinProb,
                      savedMlOver25Prob      = existingEntry.savedMlOver25Prob,
                      savedMlBttsProb        = existingEntry.savedMlBttsProb,
                      savedMlMostLikelyScore = existingEntry.savedMlMostLikelyScore,
                      mlPredictionSaved      = true,
                      // Restore Poisson snapshot
                      savedHomeXg                = existingEntry.savedHomeXg,
                      savedAwayXg                = existingEntry.savedAwayXg,
                      savedPoissonHomeWinPct     = existingEntry.savedPoissonHomeWinPct,
                      savedPoissonDrawPct        = existingEntry.savedPoissonDrawPct,
                      savedPoissonAwayWinPct     = existingEntry.savedPoissonAwayWinPct,
                      savedPoissonMostLikelyScore = existingEntry.savedPoissonMostLikelyScore,
                      savedOver25Pct             = existingEntry.savedOver25Pct,
                      savedBttsPct               = existingEntry.savedBttsPct,
                      savedExpectedTotal         = existingEntry.savedExpectedTotal,
                      savedConfidenceScore       = existingEntry.savedConfidenceScore,
                      poissonSnapshotSaved       = existingEntry.poissonSnapshotSaved
                  )
              }
              // Case 3: match finished but no saved snapshot (first scan or very old entry).
              // Save whatever the neural net says now as the "best available" snapshot.
              match.isFinished() && ml != null && ml.available -> {
                  entry.withLockedMlPrediction(ml).withLockedPoissonSnapshot(analysis)
              }
              // Case 4: live match (or upcoming with no ML) — preserve any existing snapshot
              // so it is not erased while the match is in progress.
              else -> {
                  if (existingEntry?.mlPredictionSaved == true) {
                      entry.copy(
                          savedMlHomeWinProb          = existingEntry.savedMlHomeWinProb,
                          savedMlDrawProb             = existingEntry.savedMlDrawProb,
                          savedMlAwayWinProb          = existingEntry.savedMlAwayWinProb,
                          savedMlOver25Prob           = existingEntry.savedMlOver25Prob,
                          savedMlBttsProb             = existingEntry.savedMlBttsProb,
                          savedMlMostLikelyScore      = existingEntry.savedMlMostLikelyScore,
                          mlPredictionSaved           = true,
                          savedHomeXg                 = existingEntry.savedHomeXg,
                          savedAwayXg                 = existingEntry.savedAwayXg,
                          savedPoissonHomeWinPct      = existingEntry.savedPoissonHomeWinPct,
                          savedPoissonDrawPct         = existingEntry.savedPoissonDrawPct,
                          savedPoissonAwayWinPct      = existingEntry.savedPoissonAwayWinPct,
                          savedPoissonMostLikelyScore = existingEntry.savedPoissonMostLikelyScore,
                          savedOver25Pct              = existingEntry.savedOver25Pct,
                          savedBttsPct                = existingEntry.savedBttsPct,
                          savedExpectedTotal          = existingEntry.savedExpectedTotal,
                          savedConfidenceScore        = existingEntry.savedConfidenceScore,
                          poissonSnapshotSaved        = existingEntry.poissonSnapshotSaved
                      )
                  } else {
                      entry
                  }
              }
          }
          historyStorage.saveEntry(finalEntry)
    }
}
