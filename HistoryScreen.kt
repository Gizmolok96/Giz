package com.footballanalyzer.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footballanalyzer.app.data.model.HistoryEntry
import com.footballanalyzer.app.ui.components.getInitials
import com.footballanalyzer.app.ui.theme.*
import com.footballanalyzer.app.viewmodel.HistoryViewModel

@Composable
fun HistoryScreen(
    onMatchClick: (String) -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadHistory() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .statusBarsPadding()
    ) {
        // Header — no title; Scan&Train on left, action buttons on right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Scan & Train button — LEFT side
            if (uiState.entries.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentDim)
                        .border(1.dp, AccentGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .clickable(enabled = !uiState.isSyncing) { viewModel.scanAndTrain() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (uiState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = AccentGreen,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = AccentGreen,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = if (uiState.isSyncing) "Scanning..." else "Scan & Train",
                            color = AccentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            // Action buttons — RIGHT side
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Delete finished matches button (green tint)
                val hasFinished = uiState.entries.any { it.homeResult != null && it.awayResult != null }
                if (hasFinished) {
                    IconButton(
                        onClick = { viewModel.clearFinishedMatches() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.DoneAll,
                            contentDescription = "Remove finished matches",
                            tint = AccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Delete all button (gray trash)
                if (uiState.entries.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.clearHistory() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear all",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // ML stats card
        uiState.mlStats?.let { stats ->
            if (stats.totalTrained > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PurpleDim)
                        .border(1.dp, PurpleLight.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("🤖 ML Model v${stats.modelVersion}", color = PurpleLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Trained on ${stats.totalTrained} matches", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        // Sync message
        uiState.syncMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentDim)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(text = msg, color = AccentGreen, fontSize = 12.sp)
            }
        }

        Divider(
            color = BorderColor,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (uiState.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📋", fontSize = 48.sp)
                    Text("No analysis history yet", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tap a match to analyze it", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Text(
                        text = "${uiState.entries.size} analyzed matches",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(items = uiState.entries, key = { it.id }) { entry ->
                    HistoryCard(
                        entry = entry,
                        onClick = { onMatchClick(entry.matchId) },
                        onDelete = { viewModel.deleteEntry(entry.matchId) }
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryCard(entry: HistoryEntry, onClick: () -> Unit, onDelete: () -> Unit = {}) {
    val hasResult = entry.homeResult != null && entry.awayResult != null
    // A match is live if its saved statusName looks like a live indicator
    val isEntryLive = entry.statusName?.let { s ->
        s.contains("'") ||
        s.lowercase().contains("live") ||
        s.lowercase().contains("half") ||
        s.lowercase() == "ht" ||
        s.lowercase().contains("break") ||
        s.lowercase().contains("extra time") ||
        s.lowercase().contains("penalties")
    } == true
    val showFinishedBadge = hasResult && !isEntryLive
    val showLiveBadge     = hasResult && isEntryLive
    val isFinished = showFinishedBadge

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(
                width = 1.dp,
                color = when {
                    showLiveBadge     -> LiveColor.copy(alpha = 0.45f)
                    showFinishedBadge -> AccentGreen.copy(alpha = 0.35f)
                    else              -> BorderColor
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        // Delete button in top-right corner
        Box(
            modifier = Modifier
                .size(22.dp)
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0x33FF4444))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete",
                tint = Color(0xFFFF6B6B),
                modifier = Modifier.size(12.dp)
            )
        }

        Column {
            // Status badge
            if (showFinishedBadge || showLiveBadge) {
                Row(
                    modifier = Modifier.padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (showFinishedBadge) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("✓ Finished", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(LiveColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "● LIVE ${entry.statusName ?: ""}".trim(),
                                color = LiveColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    entry.league?.let {
                        Text(it.take(20), color = TextMuted, fontSize = 10.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home team
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(BlueDim),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = getInitials(entry.homeTeam), color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(text = entry.homeTeam, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
                }

                // Score / probabilities
                Column(modifier = Modifier.width(90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hasResult) {
                        Text(
                            text = "${entry.homeResult} - ${entry.awayResult}",
                            color = if (showLiveBadge) LiveColor else TextPrimary,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold
                        )
                        if (showLiveBadge) {
                            Text(text = entry.statusName ?: "Live", color = LiveColor, fontSize = 10.sp)
                        } else {
                            Text(text = "Final", color = AccentGreen, fontSize = 10.sp)
                        }
                    } else {
                        Text(text = entry.mostLikelyScore, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Predicted", color = TextMuted, fontSize = 10.sp)
                    }
                }

                // Away team
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(PurpleDim),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = getInitials(entry.awayTeam), color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(text = entry.awayTeam, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Probability bar
            Row(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(BgCardDark)) {
                if (entry.homeWinPct > 0) Box(modifier = Modifier.fillMaxHeight().weight(entry.homeWinPct.toFloat()).background(HomeColor))
                if (entry.drawPct > 0) Box(modifier = Modifier.fillMaxHeight().weight(entry.drawPct.toFloat()).background(DrawColor))
                if (entry.awayWinPct > 0) Box(modifier = Modifier.fillMaxHeight().weight(entry.awayWinPct.toFloat()).background(AwayColor))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "${entry.homeWinPct.toInt()}%", color = HomeColor, fontSize = 10.sp)
                if (!hasResult) {
                    entry.league?.let { Text(text = it.take(18), color = TextMuted, fontSize = 10.sp) }
                }
                Text(text = "${entry.awayWinPct.toInt()}%", color = AwayColor, fontSize = 10.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = entry.viewedAt, color = TextMuted, fontSize = 10.sp)
                Text(text = "Confidence: ${entry.confidenceScore.toInt()}%", color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}
