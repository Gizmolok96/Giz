package com.footballanalyzer.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.footballanalyzer.app.data.model.Analysis
import com.footballanalyzer.app.data.model.AnalysisResponse
import com.footballanalyzer.app.data.model.BettingTip
import com.footballanalyzer.app.data.model.BookmakerOdds
import com.footballanalyzer.app.data.model.GlickoData
import com.footballanalyzer.app.data.model.GoalLinePrediction
import com.footballanalyzer.app.data.model.H2HData
import com.footballanalyzer.app.data.model.MatchOdds
import com.footballanalyzer.app.data.model.MLPrediction
import com.footballanalyzer.app.data.model.Match
import com.footballanalyzer.app.data.model.TeamFeatures
import com.footballanalyzer.app.data.model.TeamFormStat
import com.footballanalyzer.app.ui.components.getInitials
import com.footballanalyzer.app.ui.theme.*
import com.footballanalyzer.app.viewmodel.ConfidenceLevel
import com.footballanalyzer.app.viewmodel.MLStats
import com.footballanalyzer.app.viewmodel.AnalysisViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class AnalysisTab { OVERVIEW, FORM, ML_PREDICTION, BETTING_TIPS, BOOKMAKER_ODDS }

@Composable
fun AnalysisScreen(
    matchId: String,
    onBack: () -> Unit,
    viewModel: AnalysisViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activeTab by remember { mutableStateOf(AnalysisTab.OVERVIEW) }

    LaunchedEffect(matchId) { viewModel.analyze(matchId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                text = "Match Analysis",
                color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (uiState.result != null) {
                IconButton(onClick = { viewModel.retry(matchId) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentGreen)
                }
            }
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = AccentGreen)
                        Text("Analyzing match...", color = TextMuted, fontSize = 14.sp)
                        Text("Fetching form, H2H & strength data", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
            uiState.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text("Analysis Failed", color = TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(uiState.error ?: "", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Button(
                            onClick = { viewModel.retry(matchId) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) { Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            uiState.result != null -> {
                val result   = uiState.result!!
                val match    = result.match
                val analysis = result.analysis

                MatchHeaderCard(match)

                val tabLabels = listOf(
                    AnalysisTab.OVERVIEW        to "Overview",
                    AnalysisTab.FORM            to "Team Form",
                    AnalysisTab.ML_PREDICTION   to "ML Forecast",
                    AnalysisTab.BETTING_TIPS    to "Betting Tips",
                    AnalysisTab.BOOKMAKER_ODDS  to "Odds"
                )
                ScrollableTabRow(
                    selectedTabIndex = tabLabels.indexOfFirst { it.first == activeTab },
                    containerColor = BgCard, contentColor = AccentGreen, edgePadding = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabLabels.forEach { (tab, label) ->
                        Tab(
                            selected = activeTab == tab, onClick = { activeTab = tab },
                            text = {
                                Text(
                                    text = label, fontSize = 13.sp,
                                    fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal,
                                    color = if (activeTab == tab) AccentGreen else TextMuted
                                )
                            }
                        )
                    }
                }

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (activeTab) {
                        AnalysisTab.OVERVIEW -> {
                            item { WinProbCard(analysis, match) }
                            item { GoalsCard(analysis, match) }
                            item { StrengthComparisonCard(analysis, match) }
                            analysis.glickoData?.let { g ->
                                if (g.available) item { Glicko2Card(g, match) }
                            }
                            item { H2HCard(analysis.h2h, match.homeTeamName, match.awayTeamName) }
                            if (analysis.keyFactors.isNotEmpty()) {
                                item { KeyFactorsCard(analysis.keyFactors) }
                            }
                            if (analysis.leagueCalibration != "Default") {
                                item { LeagueCalibrationBadge(analysis.leagueCalibration) }
                            }
                        }
                        AnalysisTab.FORM -> {
                            item {
                                TeamFormCard(match.homeTeamName, analysis.homeFormMatches,
                                    analysis.homeFeatures, isHome = true)
                            }
                            item {
                                TeamFormCard(match.awayTeamName, analysis.awayFormMatches,
                                    analysis.awayFeatures, isHome = false)
                            }
                        }
                        AnalysisTab.ML_PREDICTION -> {
                            item { MLPredictionCard(analysis, match, uiState.mlStats) }
                        }
                        AnalysisTab.BETTING_TIPS -> {
                            if (analysis.bettingTips.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) { Text("No betting tips available", color = TextMuted, fontSize = 14.sp) }
                                }
                            } else {
                                items(analysis.bettingTips) { tip -> BettingTipItem(tip) }
                            }
                        }
                        AnalysisTab.BOOKMAKER_ODDS -> {
                            item { BookmakerOddsSection(analysis.matchOdds, match.homeTeamName, match.awayTeamName) }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(40.dp)) }
                }
            }
        }
    }
}

// ─── Strength Comparison Card ──────────────────────────────────────────────

/**
 * Shows strength-adjusted (Elo-like) win rates AND home/away splits side-by-side.
 */
@Composable
private fun StrengthComparisonCard(analysis: Analysis, match: Match) {
    val home = analysis.homeFeatures
    val away = analysis.awayFeatures

    SectionCard(title = "Strength Analysis") {
        // Elo-like adjusted win rate
        Text("Opponent-Weighted Win Rate", color = TextMuted, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val adj = (home.strengthAdjustedWinRate * 100).roundToInt()
                val raw = (home.winRate * 100).roundToInt()
                Text("${adj}%", color = AccentGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(match.homeTeamName.take(10), color = TextSecondary, fontSize = 11.sp)
                Text("raw: ${raw}%", color = TextMuted, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("vs", color = TextMuted, fontSize = 16.sp)
                Text("Adj. Win%", color = TextMuted, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val adj = (away.strengthAdjustedWinRate * 100).roundToInt()
                val raw = (away.winRate * 100).roundToInt()
                Text("${adj}%", color = Color(0xFF4FC3F7), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(match.awayTeamName.take(10), color = TextSecondary, fontSize = 11.sp)
                Text("raw: ${raw}%", color = TextMuted, fontSize = 10.sp)
            }
        }

        Divider(color = BgCardDark, modifier = Modifier.padding(vertical = 10.dp))

        // Home/Away split
        Text("Home / Away Split", color = TextMuted, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(match.homeTeamName.take(12), color = TextSecondary, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                if (home.homeMatches > 0) {
                    SplitStatRow("🏠 Home", home.homeWinRate, home.homeAvgGoalsFor,
                        home.homeAvgGoalsAgainst, home.homeMatches)
                }
                if (home.awayMatches > 0) {
                    SplitStatRow("✈ Away", home.awayWinRate, home.awayAvgGoalsFor,
                        home.awayAvgGoalsAgainst, home.awayMatches)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(match.awayTeamName.take(12), color = TextSecondary, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                if (away.homeMatches > 0) {
                    SplitStatRow("🏠 Home", away.homeWinRate, away.homeAvgGoalsFor,
                        away.homeAvgGoalsAgainst, away.homeMatches)
                }
                if (away.awayMatches > 0) {
                    SplitStatRow("✈ Away", away.awayWinRate, away.awayAvgGoalsFor,
                        away.awayAvgGoalsAgainst, away.awayMatches)
                }
            }
        }

        // Motivation factor
        val homeMotiv = home.motivationFactor
        val awayMotiv = away.motivationFactor
        if (homeMotiv != 1.0 || awayMotiv != 1.0) {
            Divider(color = BgCardDark, modifier = Modifier.padding(vertical = 10.dp))
            Text("Motivation", color = TextMuted, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MotivationChip(match.homeTeamName.take(10), homeMotiv)
                MotivationChip(match.awayTeamName.take(10), awayMotiv)
            }
        }
    }
}

@Composable
private fun SplitStatRow(label: String, winRate: Double, gf: Double, ga: Double, n: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1.2f))
        Text("${(winRate*100).roundToInt()}%W", color = AccentGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(" %.1f:%.1f".format(gf, ga), color = TextSecondary, fontSize = 10.sp)
        Text(" (${n})", color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun MotivationChip(name: String, factor: Double) {
    val (label, color) = when {
        factor >= 1.08 -> "HIGH 🔥" to Color(0xFF4CAF50)
        factor >= 1.03 -> "GOOD ↑"  to Color(0xFF8BC34A)
        factor <= 0.92 -> "LOW ❄"   to Color(0xFFE57373)
        factor <= 0.97 -> "REDUCED" to Color(0xFFFF9800)
        else           -> "NORMAL"  to TextMuted
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Text(name, color = TextSecondary, fontSize = 11.sp)
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ─── League calibration badge ─────────────────────────────────────────────

@Composable
private fun LeagueCalibrationBadge(leagueName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D1F2D))
            .border(1.dp, Color(0xFF1A5276), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚙ League calibration: ", color = TextMuted, fontSize = 11.sp)
        Text(leagueName, color = Color(0xFF4FC3F7), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.weight(1f))
        Text("Dixon-Coles active", color = TextMuted, fontSize = 10.sp)
    }
}

// ─── MLPredictionCard ─────────────────────────────────────────────────────

@Composable
private fun MLPredictionCard(analysis: Analysis, match: Match, mlStats: MLStats? = null) {
    val ml = analysis.mlPrediction

    if (ml == null || !ml.available) {
        SectionCard(title = "ML Forecast") {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("ML prediction not available for this match.", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        return
    }

    // ── Goal probability indicator (always visible, no training required) ──────
    GoalProbabilityIndicator(ml = ml, analysis = analysis)

    // ── Model confidence indicator ────────────────────────────────────────────
    mlStats?.let { stats ->
        val level = stats.confidenceLevel
        val levelColor = when (level) {
            ConfidenceLevel.LOW    -> Color(0xFFEF5350)
            ConfidenceLevel.MEDIUM -> Color(0xFFFFB300)
            ConfidenceLevel.GOOD   -> Color(0xFF42A5F5)
            ConfidenceLevel.HIGH   -> Color(0xFF66BB6A)
        }
        val confidencePct = (level.progressFraction * 100).toInt()
        val nextThreshold = when (level) {
            ConfidenceLevel.LOW    -> 40
            ConfidenceLevel.MEDIUM -> 80
            ConfidenceLevel.GOOD   -> 120
            ConfidenceLevel.HIGH   -> 120
        }
        val toNext = (nextThreshold - stats.totalTrained).coerceAtLeast(0)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF12122A))
                .border(1.dp, levelColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🤖 Уровень обучения модели",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(levelColor.copy(alpha = 0.18f))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = level.label,
                        color = levelColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Доверие к модели",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "$confidencePct%",
                        color = levelColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(levelColor.copy(alpha = 0.12f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(level.progressFraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(levelColor)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = level.description,
                    color = TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${stats.totalTrained} матчей обучено",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (level != ConfidenceLevel.HIGH) {
                        Text(
                            text = "ещё $toNext до следующего",
                            color = TextMuted.copy(alpha = 0.55f),
                            fontSize = 9.sp
                        )
                    } else if (stats.accuracy7day > 0.0) {
                        Text(
                            text = "точность: ${"%.0f".format(stats.accuracy7day * 100)}%",
                            color = levelColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    if (ml.isDefaultWeights) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A1F00))
                    .border(1.dp, Color(0xFFB8860B), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Model Not Yet Trained", color = Color(0xFFFFD700), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Running on factory-default weights (v1.8). The model now includes Elo-like " +
                    "opponent weighting, home/away splits, motivation factors, and goal market " +
                    "predictions (Over 2.5, BTTS, Score). Accuracy improves after training.",
                    color = Color(0xFFCCBB77), fontSize = 12.sp, lineHeight = 18.sp
                )
                Divider(color = Color(0xFF4A3A00))
                Text("How to train:", color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "1. Analyse finished matches (with known scores)\n" +
                    "2. Go to History tab\n" +
                    "3. Tap Scan & Train — results are used automatically\n" +
                    "4. After 10+ matches accuracy improves noticeably",
                    color = Color(0xFFCCBB77), fontSize = 12.sp, lineHeight = 18.sp
                )
            }
            SectionCard(title = "Initial Weights Prediction  (v1.8 — not personalised)") {
                ModelComparisonRow(
                    homeTeam = match.homeTeamName, awayTeam = match.awayTeamName,
                    poissonHome = analysis.homeWinPct, poissonDraw = analysis.drawPct, poissonAway = analysis.awayWinPct,
                    mlHome = ml.homeWinProb * 100, mlDraw = ml.drawProb * 100, mlAway = ml.awayWinProb * 100,
                    mlLabel = "Neural Net\n(initial)"
                )
            }
            GoalMarketsCard(ml = ml, analysis = analysis, match = match)
        }
    } else {
        val stats = ml.trainingStats
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0D2A1A))
                    .border(1.dp, Color(0xFF2D6A4F), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(ml.modelUsed, color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (stats != null && stats.totalMatchesTrained > 0)
                        Text("Trained on ${stats.totalMatchesTrained} matches", color = TextMuted, fontSize = 11.sp)
                    Text("Elo + Home/Away + Motivation + Goal Markets", color = TextMuted, fontSize = 10.sp)
                }
                if (stats != null && stats.accuracy7day > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${(stats.accuracy7day * 100).roundToInt()}%", color = AccentGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("7-day acc.", color = TextMuted, fontSize = 10.sp)
                    }
                }
            }
            SectionCard(title = "Poisson+DC  vs  Neural Net (trained)") {
                ModelComparisonRow(
                    homeTeam = match.homeTeamName, awayTeam = match.awayTeamName,
                    poissonHome = analysis.homeWinPct, poissonDraw = analysis.drawPct, poissonAway = analysis.awayWinPct,
                    mlHome = ml.homeWinProb * 100, mlDraw = ml.drawProb * 100, mlAway = ml.awayWinProb * 100,
                    mlLabel = "Neural Net\n(trained)"
                )
            }
            GoalMarketsCard(ml = ml, analysis = analysis, match = match)
            if (stats != null) {
                SectionCard(title = "Training Statistics") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        if (stats.accuracy7day > 0)  StatItem("7-day acc",  "${(stats.accuracy7day  * 100).roundToInt()}%", AccentGreen)
                        if (stats.accuracy30day > 0) StatItem("30-day acc", "${(stats.accuracy30day * 100).roundToInt()}%", AccentGreen)
                        StatItem("Trained on", "${stats.totalMatchesTrained}", TextSecondary)
                        StatItem("Version", "v${stats.modelVersion}", TextMuted)
                    }
                }
            }
        }
    }
}

/**
 * Card showing Over/Under 2.5, BTTS, and Score predictions.
 * Compares Poisson+DC (repository) vs ML model (neural net + trained goal weights).
 */
@Composable
private fun GoalMarketsCard(ml: MLPrediction, analysis: Analysis, match: Match) {
    val isLive = match.isLive()
    val liveGoalLines = ml.goalLines

    SectionCard(title = "Goal Markets — ML Prediction") {

        // ── Live: show 3 adaptive Over/Under lines based on current score ──
        if (isLive && liveGoalLines.isNotEmpty()) {
            Text(
                text = "Live lines based on current score ${match.homeResult ?: 0}-${match.awayResult ?: 0}",
                color = TextMuted, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Header
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("Line",  color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(0.8f))
                Text("Over %",  color = AccentGreen, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Under %", color = Color(0xFFE57373), fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
            Divider(color = BgCardDark)
            Spacer(modifier = Modifier.height(4.dp))

            liveGoalLines.forEach { gl ->
                LiveGoalLineRow(goalLine = gl)
                Divider(color = BgCardDark.copy(alpha = 0.4f))
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = BgCardDark)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Standard header for pre-match or as fallback ──────────────────
        if (!isLive || liveGoalLines.isEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text("Market",     color = TextMuted,           fontSize = 11.sp, modifier = Modifier.weight(1.4f))
                Text("Poisson+DC", color = Color(0xFF4FC3F7),   fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Neural Net", color = Color(0xFFA5D6A7),   fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
            Divider(color = BgCardDark)
            Spacer(modifier = Modifier.height(8.dp))

            GoalMarketRow(
                label      = "Over 2.5 ⚽",
                poissonPct = analysis.over25Pct,
                mlPct      = ml.over25Prob * 100
            )
            Divider(color = BgCardDark.copy(alpha = 0.5f))

            GoalMarketRow(
                label      = "Under 2.5 🔒",
                poissonPct = analysis.under25Pct,
                mlPct      = (1.0 - ml.over25Prob) * 100
            )
            Divider(color = BgCardDark.copy(alpha = 0.5f))

            GoalMarketRow(
                label      = "BTTS Yes 🥅",
                poissonPct = analysis.bttsPct,
                mlPct      = ml.bttsProb * 100
            )
            Divider(color = BgCardDark.copy(alpha = 0.5f))
        } else {
            // For live: show BTTS only if at least one team hasn't scored yet
            val homeGoalsML = match.homeResult ?: 0
            val awayGoalsML = match.awayResult ?: 0
            if (!(homeGoalsML > 0 && awayGoalsML > 0)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("BTTS Yes 🥅", color = TextSecondary, fontSize = 13.sp)
                    val bttsPct = (ml.bttsProb * 100).roundToInt()
                    val bttsColor = when {
                        bttsPct >= 65 -> AccentGreen
                        bttsPct >= 45 -> Color(0xFFFFB74D)
                        else          -> Color(0xFFE57373)
                    }
                    Text("${bttsPct}%", color = bttsColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Divider(color = BgCardDark.copy(alpha = 0.5f))
            }
        }

        // ── Most Likely Score ──────────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Most Likely Score", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1.4f))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(analysis.mostLikelyScore, color = Color(0xFF4FC3F7), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Poisson+DC", color = TextMuted, fontSize = 10.sp)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val mlScore = ml.mlMostLikelyScore.ifEmpty { analysis.mostLikelyScore }
                Text(mlScore, color = Color(0xFFA5D6A7), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Neural Net", color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun LiveGoalLineRow(goalLine: GoalLinePrediction) {
    val overPct   = (goalLine.overProb  * 100).roundToInt()
    val underPct  = (goalLine.underProb * 100).roundToInt()
    val lineLabel = "Over ${goalLine.line}"

    val overColor = when {
        goalLine.alreadyGuaranteed -> Color(0xFF81C784)
        overPct >= 70 -> AccentGreen
        overPct >= 50 -> Color(0xFFC8E6C9)
        else          -> TextMuted
    }
    val underColor = when {
        underPct >= 70 -> Color(0xFFEF9A9A)
        underPct >= 50 -> Color(0xFFE57373)
        else           -> TextMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Line label
        Text(
            text = lineLabel,
            color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(0.8f)
        )
        // Over column
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (goalLine.alreadyGuaranteed) {
                Text("✓ 100%", color = Color(0xFF81C784), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("guaranteed", color = Color(0xFF81C784), fontSize = 9.sp)
            } else {
                Text("${overPct}%", color = overColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                LinearProgressBar(goalLine.overProb.coerceIn(0.0, 1.0), overColor)
            }
        }
        // Under column
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (goalLine.alreadyGuaranteed) {
                Text("✗ 0%", color = Color(0xFFBDBDBD), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("impossible", color = Color(0xFFBDBDBD), fontSize = 9.sp)
            } else {
                Text("${underPct}%", color = underColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                LinearProgressBar(goalLine.underProb.coerceIn(0.0, 1.0), underColor)
            }
        }
    }
}

@Composable
private fun GoalMarketRow(label: String, poissonPct: Double, mlPct: Double) {
    val delta = mlPct - poissonPct
    val deltaColor = when { delta > 3.0 -> Color(0xFF81C784); delta < -3.0 -> Color(0xFFE57373); else -> TextMuted }
    val deltaStr = if (delta >= 0) "+${delta.roundToInt()}%" else "${delta.roundToInt()}%"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1.4f))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${poissonPct.roundToInt()}%", color = Color(0xFF4FC3F7), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                LinearProgressBar(poissonPct / 100.0, Color(0xFF4FC3F7))
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${mlPct.roundToInt()}%", color = Color(0xFFA5D6A7), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                LinearProgressBar(mlPct / 100.0, Color(0xFFA5D6A7))
            }
        }
    }
}

@Composable
private fun ModelComparisonRow(
    homeTeam: String, awayTeam: String,
    poissonHome: Double, poissonDraw: Double, poissonAway: Double,
    mlHome: Double, mlDraw: Double, mlAway: Double, mlLabel: String
) {
    val rows = listOf(
        Triple("${homeTeam.take(9)} Win", poissonHome, mlHome),
        Triple("Draw",                    poissonDraw, mlDraw),
        Triple("${awayTeam.take(9)} Win", poissonAway, mlAway)
    )
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Outcome",  color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1.4f))
        Text("Poisson+DC", color = Color(0xFF4FC3F7), fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(mlLabel, color = Color(0xFFA5D6A7), fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text("Delta", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
    }
    Divider(color = BgCardDark)
    Spacer(modifier = Modifier.height(8.dp))
    rows.forEach { (label, poisson, ml) ->
        val delta = ml - poisson
        val deltaColor = when { delta > 2.0 -> Color(0xFF81C784); delta < -2.0 -> Color(0xFFE57373); else -> TextMuted }
        val deltaStr = if (delta >= 0) "+${delta.roundToInt()}%" else "${delta.roundToInt()}%"
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1.4f))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${poisson.roundToInt()}%", color = Color(0xFF4FC3F7), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    LinearProgressBar(poisson / 100.0, Color(0xFF4FC3F7))
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${ml.roundToInt()}%", color = Color(0xFFA5D6A7), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    LinearProgressBar(ml / 100.0, Color(0xFFA5D6A7))
                }
            }
            Text(deltaStr, color = deltaColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
        }
        Divider(color = BgCardDark.copy(alpha = 0.5f))
    }
}

@Composable
private fun LinearProgressBar(fraction: Double, color: Color) {
    Box(
        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.85f).height(4.dp)
            .clip(RoundedCornerShape(2.dp)).background(BgCardDark)
    ) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).background(color))
    }
}

// ─── Header & Standard Cards ─────────────────────────────────────────────

@Composable
private fun MatchHeaderCard(match: Match) {
    SectionCard(title = "") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(BlueDim),
                    contentAlignment = Alignment.Center) {
                    Text(getInitials(match.homeTeamName), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(match.homeTeamName, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (match.homeResult != null && match.awayResult != null) {
                    Text("${match.homeResult} – ${match.awayResult}",
                        color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    val statusColor = if (match.isLive()) LiveColor else TextMuted
                    val statusLabel = when {
                        match.isLive()     -> match.statusName.ifEmpty { "LIVE" }
                        match.isFinished() -> match.statusName.ifEmpty { "FT" }
                        else               -> match.statusName
                    }
                    Text(statusLabel, color = statusColor, fontSize = 12.sp)
                } else {
                    Text("vs", color = TextMuted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (match.statusName.isEmpty()) "Upcoming" else match.statusName,
                        color = AccentGreen, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(match.leagueName, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(PurpleDim),
                    contentAlignment = Alignment.Center) {
                    Text(getInitials(match.awayTeamName), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(match.awayTeamName, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WinProbCard(analysis: Analysis, match: Match) {
    SectionCard(title = "Win Probability") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            ProbItem(match.homeTeamName.take(10), analysis.homeWinPct, AccentGreen)
            ProbItem("Draw", analysis.drawPct, TextSecondary)
            ProbItem(match.awayTeamName.take(10), analysis.awayWinPct, Color(0xFF4FC3F7))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            Box(modifier = Modifier.weight(analysis.homeWinPct.toFloat().coerceAtLeast(1f)).background(AccentGreen))
            Box(modifier = Modifier.weight(analysis.drawPct.toFloat().coerceAtLeast(1f)).background(TextSecondary))
            Box(modifier = Modifier.weight(analysis.awayWinPct.toFloat().coerceAtLeast(1f)).background(Color(0xFF4FC3F7)))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Most likely: ${analysis.mostLikelyScore}  (${analysis.mostLikelyProb.roundToInt()}% · Dixon-Coles)",
            color = TextMuted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun GoalsCard(analysis: Analysis, match: Match) {
    val isLive       = match.isLive()
    val liveLines    = analysis.liveGoalLines          // Poisson+DC lines from repository
    val homeGoals    = match.homeResult ?: 0
    val awayGoals    = match.awayResult ?: 0
    val bothScored   = homeGoals > 0 && awayGoals > 0

    SectionCard(title = "Expected Goals") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("Home xG",   "%.2f".format(analysis.homeXg),       AccentGreen)
            StatItem("Total",     "%.2f".format(analysis.expectedTotal), TextSecondary)
            StatItem("Away xG",   "%.2f".format(analysis.awayXg),       Color(0xFF4FC3F7))
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (isLive && liveLines.isNotEmpty()) {
            // ── Live: adaptive lines based on current score ──────────────
            Text(
                text = "Poisson+DC · счёт ${homeGoals}-${awayGoals}",
                color = TextMuted, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("Line",    color = TextMuted,         fontSize = 11.sp, modifier = Modifier.weight(0.8f))
                Text("Over %",  color = AccentGreen,       fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Under %", color = Color(0xFFE57373), fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
            Divider(color = BgCardDark)
            Spacer(modifier = Modifier.height(4.dp))
            liveLines.forEach { gl ->
                LiveGoalLineRow(goalLine = gl)
                Divider(color = BgCardDark.copy(alpha = 0.4f))
            }
            // BTTS only if at least one team hasn't scored yet
            if (!bothScored) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("BTTS Yes 🥅", color = TextSecondary, fontSize = 13.sp)
                    val bttsPct = analysis.bttsPct.roundToInt()
                    val bttsColor = when {
                        bttsPct >= 65 -> AccentGreen
                        bttsPct >= 45 -> Color(0xFFFFB74D)
                        else          -> Color(0xFFE57373)
                    }
                    Text("${bttsPct}%", color = bttsColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // ── Pre-match: static Over/Under 2.5 + BTTS ─────────────────
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                StatItem("Over 2.5",  "${analysis.over25Pct.roundToInt()}%",  AccentGreen)
                StatItem("Under 2.5", "${analysis.under25Pct.roundToInt()}%", Color(0xFFE57373))
                StatItem("BTTS",      "${analysis.bttsPct.roundToInt()}%",    Color(0xFFFFB74D))
            }
        }
    }
}

@Composable
private fun H2HCard(h2h: H2HData, homeName: String, awayName: String) {
    SectionCard(title = "Head-to-Head") {
        if (h2h.total == 0) {
            Text("No H2H data available", color = TextMuted, fontSize = 13.sp)
            return@SectionCard
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("${homeName.take(8)} W", "${h2h.homeWins}", AccentGreen)
            StatItem("Draws", "${h2h.draws}", TextSecondary)
            StatItem("${awayName.take(8)} W", "${h2h.awayWins}", Color(0xFF4FC3F7))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Avg goals: %.1f per game (${h2h.total} matches)".format(h2h.avgGoals),
            color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        if (h2h.matches.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = BgCardDark)
            h2h.matches.take(4).forEach { m ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(m.date?.take(10) ?: "", color = TextMuted, fontSize = 10.sp)
                    Text("${m.homeTeamName.take(8)} ${m.homeResult}-${m.awayResult} ${m.awayTeamName.take(8)}",
                        color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun KeyFactorsCard(factors: List<String>) {
    SectionCard(title = "Key Factors") {
        factors.forEach { factor ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top) {
                Text("•", color = AccentGreen, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp, top = 1.dp))
                Text(factor, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun TeamFormCard(
    teamName: String, form: List<TeamFormStat>,
    features: TeamFeatures, isHome: Boolean
) {
    SectionCard(title = "$teamName — Form") {
        if (form.isEmpty()) {
            Text("No recent form data available", color = TextMuted, fontSize = 13.sp)
            return@SectionCard
        }

        // Summary stats row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("PPG",      "%.1f".format(features.pointsPerGame), AccentGreen)
            StatItem("Win %",    "${(features.winRate * 100).roundToInt()}%", AccentGreen)
            StatItem("Adj W%",   "${(features.strengthAdjustedWinRate * 100).roundToInt()}%", Color(0xFFFFB74D))
            StatItem("Form",     features.formString, TextSecondary)
        }

        // Home/Away breakdown
        if (features.homeMatches > 0 || features.awayMatches > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = BgCardDark)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                if (features.homeMatches > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🏠 At Home", color = TextMuted, fontSize = 10.sp)
                        Text("${(features.homeWinRate*100).roundToInt()}% W", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("%.1f : %.1f".format(features.homeAvgGoalsFor, features.homeAvgGoalsAgainst),
                            color = TextSecondary, fontSize = 11.sp)
                        Text("(${features.homeMatches} games)", color = TextMuted, fontSize = 10.sp)
                    }
                }
                if (features.awayMatches > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✈ Away", color = TextMuted, fontSize = 10.sp)
                        Text("${(features.awayWinRate*100).roundToInt()}% W", color = Color(0xFF4FC3F7), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("%.1f : %.1f".format(features.awayAvgGoalsFor, features.awayAvgGoalsAgainst),
                            color = TextSecondary, fontSize = 11.sp)
                        Text("(${features.awayMatches} games)", color = TextMuted, fontSize = 10.sp)
                    }
                }
                // Motivation
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Motivation", color = TextMuted, fontSize = 10.sp)
                    val (label, color) = when {
                        features.motivationFactor >= 1.08 -> "HIGH 🔥" to Color(0xFF4CAF50)
                        features.motivationFactor >= 1.03 -> "GOOD ↑"  to Color(0xFF8BC34A)
                        features.motivationFactor <= 0.92 -> "LOW ❄"   to Color(0xFFE57373)
                        features.motivationFactor <= 0.97 -> "REDUCED" to Color(0xFFFF9800)
                        else                              -> "NORMAL"  to TextMuted
                    }
                    Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Divider(color = BgCardDark)
        Spacer(modifier = Modifier.height(6.dp))

        // Individual match rows
        form.forEach { stat ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                // Result badge
                val resultColor = when (stat.result) { "W" -> Color(0xFF4CAF50); "D" -> Color(0xFFFF9800); else -> Color(0xFFE57373) }
                Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(resultColor),
                    contentAlignment = Alignment.Center) {
                    Text(stat.result, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(stat.opponent, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val locLabel = if (stat.isHome) "Home" else "Away"
                    val compLabel = stat.competition?.let { " · $it" } ?: ""
                    Text("$locLabel$compLabel", color = TextMuted, fontSize = 10.sp)
                }
                // Goals
                Text("${stat.goalsFor}–${stat.goalsAgainst}", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                // Opponent strength indicator
                val strColor = when {
                    stat.opponentStrength >= 1.30 -> Color(0xFFE57373)   // strong opp
                    stat.opponentStrength >= 1.10 -> Color(0xFFFF9800)
                    stat.opponentStrength <= 0.70 -> Color(0xFF4CAF50)   // weak opp
                    else -> TextMuted
                }
                val strLabel = when {
                    stat.opponentStrength >= 1.30 -> "★★★"
                    stat.opponentStrength >= 1.10 -> "★★"
                    stat.opponentStrength <= 0.70 -> "★"
                    else -> "★★"
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 6.dp)) {
                    Text(strLabel, color = strColor, fontSize = 10.sp)
                    Text("opp.", color = TextMuted, fontSize = 9.sp)
                }
            }
            Divider(color = BgCardDark.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun BettingTipItem(tip: BettingTip) {
    val confidenceColor = when (tip.confidence) {
        "High"   -> Color(0xFF4CAF50)
        "Medium" -> Color(0xFFFF9800)
        else     -> Color(0xFFE57373)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(BgCard).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tip.market, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(tip.reason, color = TextMuted, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${tip.passRate}%", color = AccentGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(tip.confidence, color = confidenceColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─── Shared composables ───────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(BgCard).padding(16.dp)
    ) {
        if (title.isNotEmpty()) {
            Text(title, color = TextSecondary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        }
        content()
    }
}

@Composable
private fun ProbItem(label: String, pct: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${pct.roundToInt()}%", color = color, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

// ─── Bookmaker Odds Section ───────────────────────────────────────────────

@Composable
private fun BookmakerOddsSection(
    matchOdds: MatchOdds,
    homeTeamName: String,
    awayTeamName: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Live odds block (shown only when available)
        if (matchOdds.live != null) {
            val elapsed = matchOdds.liveElapsed
            val elapsedLabel = if (elapsed != null) "Live Odds  •  $elapsed'" else "Live Odds"
            SectionCard(title = elapsedLabel) {
                BookmakerOddsRow(
                    odds = matchOdds.live,
                    homeTeamName = homeTeamName,
                    awayTeamName = awayTeamName,
                    showHeader = true
                )
            }
        }

        // Prematch odds header (1x2 / O-U / BTTS column labels)
        if (matchOdds.prematch.isNotEmpty()) {
            SectionCard(title = "Pre-Match Odds") {

                // Column header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Bookmaker",
                        color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(2.2f)
                    )
                    OddsHeaderCell(homeTeamName.take(6), Modifier.weight(1f))
                    OddsHeaderCell("X", Modifier.weight(1f))
                    OddsHeaderCell(awayTeamName.take(6), Modifier.weight(1f))
                    OddsHeaderCell("O2.5", Modifier.weight(1f))
                    OddsHeaderCell("U2.5", Modifier.weight(1f))
                    OddsHeaderCell("BTTS", Modifier.weight(1f))
                }

                Divider(color = BgCardDark, thickness = 1.dp, modifier = Modifier.padding(bottom = 8.dp))

                matchOdds.prematch.forEachIndexed { index, bk ->
                    if (index > 0) Divider(
                        color = BgCardDark,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    BookmakerOddsRow(
                        odds = bk,
                        homeTeamName = homeTeamName,
                        awayTeamName = awayTeamName,
                        showHeader = false
                    )
                }
            }
        }

        // Neither live nor prematch available
        if (matchOdds.prematch.isEmpty() && matchOdds.live == null) {
            SectionCard(title = "Bookmaker Odds") {
                if (matchOdds.error != null) {
                    Text(
                        "Could not load odds: ${matchOdds.error}",
                        color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    )
                } else {
                    Text(
                        "No odds available for this match",
                        color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OddsHeaderCell(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

@Composable
private fun BookmakerOddsRow(
    odds: BookmakerOdds,
    homeTeamName: String,
    awayTeamName: String,
    showHeader: Boolean
) {
    Column {
        if (showHeader) {
            // When showing as a standalone card (live odds), show column headers inline
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(2.2f))
                OddsHeaderCell(homeTeamName.take(6), Modifier.weight(1f))
                OddsHeaderCell("X", Modifier.weight(1f))
                OddsHeaderCell(awayTeamName.take(6), Modifier.weight(1f))
                OddsHeaderCell("O2.5", Modifier.weight(1f))
                OddsHeaderCell("U2.5", Modifier.weight(1f))
                OddsHeaderCell("BTTS", Modifier.weight(1f))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                odds.bookmakerName,
                color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2.2f)
            )
            OddsCell(odds.homeWin, AccentGreen, Modifier.weight(1f))
            OddsCell(odds.draw, TextSecondary, Modifier.weight(1f))
            OddsCell(odds.awayWin, Color(0xFF4FC3F7), Modifier.weight(1f))
            OddsCell(odds.over25, Color(0xFFFFB74D), Modifier.weight(1f))
            OddsCell(odds.under25, Color(0xFFFFB74D), Modifier.weight(1f))
            OddsCell(odds.bttsYes, Color(0xFFCE93D8), Modifier.weight(1f))
        }
    }
}

@Composable
private fun OddsCell(value: Double?, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = if (value != null) "%.2f".format(value) else "–",
        color = if (value != null) color else TextMuted,
        fontSize = 12.sp,
        fontWeight = if (value != null) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

// ─── Glicko-2 Rating Card ─────────────────────────────────────────────────

@Composable
private fun Glicko2Card(glicko: GlickoData, match: Match) {
    val glickoBlue   = Color(0xFF4FC3F7)
    val glickoOrange = Color(0xFFFFB74D)
    val glickoGreen  = Color(0xFF81C784)

    SectionCard(title = "⚡ Glicko-2 Ratings") {
        // ── Rating comparison row ────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(match.homeTeamName.take(14), color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(glicko.homeRating.roundToInt().toString(),
                    color = glickoBlue, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("RD ±${glicko.homeRd.roundToInt()}", color = TextMuted, fontSize = 11.sp)
            }
            Column(modifier = Modifier.weight(0.8f), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(14.dp))
                val diff = glicko.ratingDiff
                val diffColor = when {
                    diff > 50  -> glickoBlue
                    diff < -50 -> glickoOrange
                    else       -> TextMuted
                }
                val diffStr = when {
                    diff > 0  -> "+${diff.roundToInt()}"
                    diff < 0  -> diff.roundToInt().toString()
                    else      -> "±0"
                }
                Text(diffStr, color = diffColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("rating diff", color = TextMuted, fontSize = 10.sp)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(match.awayTeamName.take(14), color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(glicko.awayRating.roundToInt().toString(),
                    color = glickoOrange, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("RD ±${glicko.awayRd.roundToInt()}", color = TextMuted, fontSize = 11.sp)
            }
        }

        // ── Glicko xG row ────────────────────────────────────────────────
        if (glicko.homeXg != null && glicko.awayXg != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = BgCardDark)
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.2f".format(glicko.homeXg), color = glickoBlue,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Glicko xG", color = TextMuted, fontSize = 10.sp)
                }
                Text("xG", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(0.5f),
                    textAlign = TextAlign.Center)
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.2f".format(glicko.awayXg), color = glickoOrange,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Glicko xG", color = TextMuted, fontSize = 10.sp)
                }
            }
        }

        // ── Win probability row ──────────────────────────────────────────
        if (glicko.homeWinProbability != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = BgCardDark)
            Spacer(modifier = Modifier.height(10.dp))
            val homeWin = glicko.homeWinProbability
            val awayWin = glicko.awayWinProbability ?: (1.0 - homeWin)
            val draw    = (1.0 - homeWin - awayWin).coerceAtLeast(0.0)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GlickoWinCell("${(homeWin * 100).roundToInt()}%", "Home Win", glickoBlue,   Modifier.weight(1f))
                GlickoWinCell("${(draw    * 100).roundToInt()}%", "Draw",     TextSecondary, Modifier.weight(1f))
                GlickoWinCell("${(awayWin * 100).roundToInt()}%", "Away Win", glickoOrange,  Modifier.weight(1f))
            }
        }

        // ── Volatility badge ─────────────────────────────────────────────
        if (glicko.homeVolatility != null) {
            Spacer(modifier = Modifier.height(8.dp))
            val vol = glicko.homeVolatility
            val volColor = when {
                vol < 0.06  -> glickoGreen
                vol < 0.08  -> TextSecondary
                else        -> glickoOrange
            }
            val volLabel = when {
                vol < 0.06  -> "Low volatility (stable)"
                vol < 0.08  -> "Medium volatility"
                else        -> "High volatility (erratic)"
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(BgCardDark).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("σ ${"%.4f".format(vol)} · $volLabel", color = volColor, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun GoalProbabilityIndicator(ml: MLPrediction, analysis: Analysis) {
    val over25Blend = (analysis.over25Pct + ml.over25Prob * 100) / 2.0
    val bttsBlend   = (analysis.bttsPct   + ml.bttsProb   * 100) / 2.0
    val under25Blend = 100.0 - over25Blend

    fun goalColor(pct: Double) = when {
        pct >= 65 -> Color(0xFF66BB6A)
        pct >= 50 -> Color(0xFFFFB74D)
        else      -> Color(0xFFEF5350)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0A1628))
            .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚽ Вероятность голов",
                color = Color(0xFF90CAF9),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Poisson + ML",
                color = TextMuted,
                fontSize = 10.sp
            )
        }

        GoalProbBar(
            label      = "Тотал Больше 2.5",
            pct        = over25Blend,
            color      = goalColor(over25Blend),
            mlPct      = ml.over25Prob * 100,
            poissonPct = analysis.over25Pct
        )
        GoalProbBar(
            label      = "Тотал Меньше 2.5",
            pct        = under25Blend,
            color      = goalColor(under25Blend),
            mlPct      = (1.0 - ml.over25Prob) * 100,
            poissonPct = 100.0 - analysis.over25Pct
        )
        GoalProbBar(
            label      = "Обе забьют (BTTS)",
            pct        = bttsBlend,
            color      = goalColor(bttsBlend),
            mlPct      = ml.bttsProb * 100,
            poissonPct = analysis.bttsPct
        )
    }
}

@Composable
private fun GoalProbBar(
    label: String,
    pct: Double,
    color: Color,
    mlPct: Double,
    poissonPct: Double
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "P:${poissonPct.toInt()}%",
                    color = Color(0xFF4FC3F7),
                    fontSize = 10.sp
                )
                Text(
                    text = "ML:${mlPct.toInt()}%",
                    color = Color(0xFFA5D6A7),
                    fontSize = 10.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(color.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${pct.toInt()}%",
                        color = color,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((pct / 100.0).toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}


@Composable
private fun GlickoWinCell(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}
