package com.footballanalyzer.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footballanalyzer.app.data.api.ApiClient
import com.footballanalyzer.app.data.local.HistoryStorage
import com.footballanalyzer.app.data.model.HistoryEntry
import com.footballanalyzer.app.data.model.toMatch
import com.footballanalyzer.app.ml.MLModel
import com.footballanalyzer.app.ml.TrainEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val mlStats: MLStats? = null
)

data class MLStats(
    val totalTrained: Int,
    val modelVersion: Int,
    val accuracy7day: Double  = 0.0,
    val accuracy30day: Double = 0.0
) {
    /** Уровень доверия к ML-модели на основе количества обученных матчей */
    val confidenceLevel: ConfidenceLevel get() = when {
        totalTrained >= 120 -> ConfidenceLevel.HIGH
        totalTrained >= 80  -> ConfidenceLevel.GOOD
        totalTrained >= 40  -> ConfidenceLevel.MEDIUM
        else                -> ConfidenceLevel.LOW
    }
}

enum class ConfidenceLevel(
    val label: String,
    val description: String,
    val progressFraction: Float
) {
    LOW   ("Начальный",  "Модель только начала учиться. Больше доверяет математике.",   0.15f),
    MEDIUM("Средний",    "Модель накопила базовый опыт. Работает в смешанном режиме.",  0.45f),
    GOOD  ("Хороший",    "Модель достаточно обучена. Предсказания заметно точнее.",      0.72f),
    HIGH  ("Высокий",    "Модель хорошо обучена. Максимальное влияние на результат.",   1.00f)
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStorage = HistoryStorage(application)
    private val mlModel = MLModel(application)
    private val api = ApiClient.footballApi

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState

    fun loadHistory() {
        val entries = historyStorage.getAllEntries().sortedWith(
            compareByDescending<HistoryEntry> { it.homeResult != null && it.awayResult != null }
                .thenByDescending { it.viewedAt }
        )
        val stats = mlModel.getModelStats()
        _uiState.value = _uiState.value.copy(
            entries = entries,
            mlStats = MLStats(
                totalTrained  = stats.totalMatchesTrained,
                modelVersion  = stats.version,
                accuracy7day  = stats.accuracy7day,
                accuracy30day = stats.accuracy30day
            )
        )
    }

    fun deleteEntry(matchId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            historyStorage.deleteEntry(matchId)
            val updatedEntries = historyStorage.getAllEntries().sortedWith(
                compareByDescending<HistoryEntry> { it.homeResult != null && it.awayResult != null }
                    .thenByDescending { it.viewedAt }
            )
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(entries = updatedEntries)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyStorage.clearAll()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    entries = emptyList(),
                    syncMessage = "History cleared"
                )
            }
        }
    }

    /**
     * Removes all finished matches in a single bulk operation.
     * Previously this called deleteEntry() in a loop, which caused
     * TransactionTooLargeException and app crashes on large history sets.
     */
    fun clearFinishedMatches() {
        viewModelScope.launch(Dispatchers.IO) {
            val allEntries = historyStorage.getAllEntries()
            val finishedIds = allEntries
                .filter { it.homeResult != null && it.awayResult != null }
                .map { it.matchId }
                .toSet()

            if (finishedIds.isNotEmpty()) {
                historyStorage.deleteEntries(finishedIds)
            }

            val remaining = historyStorage.getAllEntries().sortedWith(
                compareByDescending<HistoryEntry> { it.homeResult != null && it.awayResult != null }
                    .thenByDescending { it.viewedAt }
            )

            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    entries = remaining,
                    syncMessage = if (finishedIds.isEmpty()) "No finished matches to remove"
                                  else "Removed ${finishedIds.size} finished match${if (finishedIds.size == 1) "" else "es"}"
                )
            }
        }
    }

    fun scanAndTrain() {
        _uiState.value = _uiState.value.copy(isSyncing = true, syncMessage = null)
        viewModelScope.launch {
            try {
                val entries = historyStorage.getAllEntries()

                val pending = entries.filter { it.homeResult == null || it.awayResult == null }
                val newlyFinished = mutableListOf<HistoryEntry>()

                for (entry in pending) {
                    try {
                        val detail = api.getGameDetail(entry.matchId)
                        val game = detail.data?.game
                        if (game != null) {
                            val match = game.toMatch()
                            if (match.isFinished() && match.homeResult != null && match.awayResult != null) {
                                val updated = entry.copy(
                                    homeResult = match.homeResult,
                                    awayResult = match.awayResult,
                                    statusName = match.statusName
                                )
                                historyStorage.updateEntry(updated)
                                newlyFinished.add(updated)
                            }
                        }
                        kotlinx.coroutines.delay(300)
                    } catch (_: Exception) {}
                }

                val allFinished = historyStorage.getAllEntries()
                    .filter { it.homeResult != null && it.awayResult != null }

                val trainEntries = allFinished.map { e ->
                    TrainEntry(
                        matchId                = e.matchId,
                        homeTeam               = e.homeTeam,
                        awayTeam               = e.awayTeam,
                        homeResult             = e.homeResult!!,
                        awayResult             = e.awayResult!!,
                        homeWinPct             = e.homeWinPct,
                        drawPct                = e.drawPct,
                        awayWinPct             = e.awayWinPct,
                        homeAvgGoalsFor        = e.homeAvgGoalsFor,
                        homeAvgGoalsAgainst    = e.homeAvgGoalsAgainst,
                        awayAvgGoalsFor        = e.awayAvgGoalsFor,
                        awayAvgGoalsAgainst    = e.awayAvgGoalsAgainst,
                        homePPG                = e.homePPG,
                        awayPPG                = e.awayPPG,
                        homeTrend              = e.homeTrend,
                        awayTrend              = e.awayTrend,
                        homeXgFor              = e.homeXgFor,
                        awayXgFor              = e.awayXgFor,
                        h2hHomeWinRate         = e.h2hHomeWinRate,
                        h2hAvgGoals            = e.h2hAvgGoals,
                        homeStrengthAdjWinRate = e.homeStrengthAdjWinRate,
                        awayStrengthAdjWinRate = e.awayStrengthAdjWinRate,
                        homeRecentPoints       = e.homeRecentPoints,
                        awayRecentPoints       = e.awayRecentPoints,
                        homeGoalDiff           = e.homeGoalDiff,
                        awayGoalDiff           = e.awayGoalDiff,
                        formDiff               = e.formDiff,
                        homeMotivation         = e.homeMotivation,
                        awayMotivation         = e.awayMotivation,
                        matchTimestamp         = e.matchTimestamp,
                        homeOdds               = e.homeOdds,
                        drawOdds               = e.drawOdds,
                        awayOdds               = e.awayOdds,
                        over25Odds             = e.over25Odds,
                        bttsYesOdds            = e.bttsYesOdds,
                        // Actual win rates (aligned with prediction feature vector)
                        homeActualWinRate      = e.homeActualWinRate,
                        awayActualWinRate      = e.awayActualWinRate,
                        homeHomeActualWinRate  = e.homeHomeActualWinRate,
                        awayAwayActualWinRate  = e.awayAwayActualWinRate,
                        // Glicko-2 data
                        glickoRatingDiff       = e.glickoRatingDiff,
                        homeGlickoRating       = e.homeGlickoRating,
                        awayGlickoRating       = e.awayGlickoRating,
                        homeGlickoRd           = e.homeGlickoRd,
                        awayGlickoRd           = e.awayGlickoRd,
                        // Rich stats: GK quality, defensive solidity, error rate
                        homeGkQuality          = e.homeGkQuality,
                        awayGkQuality          = e.awayGkQuality,
                        homeDefStrength        = e.homeDefStrength,
                        awayDefStrength        = e.awayDefStrength,
                        homeErrRate            = e.homeErrRate,
                        awayErrRate            = e.awayErrRate
                    )
                }

                val trained = if (trainEntries.isNotEmpty()) {
                    // First pass — main learning rate
                    val firstPass = mlModel.trainFromHistory(trainEntries)
                    // Second fine-tuning pass with reduced LR when enough data available.
                    // Helps convergence without overfitting on small datasets.
                    if (firstPass > 15) {
                        // Fine-tuning pass: retrain on ALL finished matches with reduced LR.
                        // retrainPass=true skips the "already seen" filter so the model
                        // can refine weights that were noisy during the first pass.
                        mlModel.trainFromHistory(trainEntries, extraLrFactor = 0.3, retrainPass = true)
                    }
                    firstPass
                } else 0

                val updatedEntries = historyStorage.getAllEntries().sortedWith(
                    compareByDescending<HistoryEntry> { it.homeResult != null && it.awayResult != null }
                        .thenByDescending { it.viewedAt }
                )
                val stats = mlModel.getModelStats()
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    entries = updatedEntries,
                    mlStats = MLStats(
                        totalTrained  = stats.totalMatchesTrained,
                        modelVersion  = stats.version,
                        accuracy7day  = stats.accuracy7day,
                        accuracy30day = stats.accuracy30day
                    ),
                    syncMessage = when {
                        pending.isEmpty() -> "No pending matches to check"
                        newlyFinished.isEmpty() -> "Checked ${pending.size} matches — no new results yet"
                        trained > 0 -> "Found ${newlyFinished.size} finished matches ✓ | trained: $trained | total: ${stats.totalMatchesTrained} | 7d: ${(stats.accuracy7day*100).toInt()}%"
                        else -> "Found ${newlyFinished.size} new finished matches (ML already up to date)"
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    syncMessage = "Scan failed: ${e.message}"
                )
            }
        }
    }
}
