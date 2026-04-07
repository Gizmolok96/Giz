package com.footballanalyzer.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footballanalyzer.app.data.model.Match
import com.footballanalyzer.app.ui.components.MatchCard
import com.footballanalyzer.app.ui.theme.*
import com.footballanalyzer.app.viewmodel.MatchTab
import com.footballanalyzer.app.viewmodel.MatchesViewModel
import com.footballanalyzer.app.viewmodel.todayDate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun MatchesScreen(
    onMatchClick: (String) -> Unit,
    viewModel: MatchesViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .statusBarsPadding()
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Football Analyzer",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentGreen)
            }
        }

        // ── Match status tabs: All / Live / Upcoming / Finished ────────────
        val tabs = listOf(
            MatchTab.ALL      to "All",
            MatchTab.LIVE     to "Live",
            MatchTab.UPCOMING to "Upcoming",
            MatchTab.FINISHED to "Finished"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEach { (tab, label) ->
                val isSelected = uiState.selectedTab == tab
                val isLive = tab == MatchTab.LIVE
                val activeColor = if (isLive) LiveColor else AccentGreen
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) activeColor.copy(alpha = 0.18f) else BgCard
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) activeColor else BorderColor,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { viewModel.selectTab(tab) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isLive) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(LiveColor)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = label,
                            color = if (isSelected) activeColor else TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // ── Date selector (hidden on Live tab) ──────────────────────────────
        if (uiState.selectedTab != MatchTab.LIVE) {
            DateSelector(
                selectedDate = uiState.selectedDate,
                onDateSelected = { viewModel.selectDate(it) }
            )
        }

        // ── Search bar ──────────────────────────────────────────────────────
        SearchBar(
            query = uiState.searchQuery,
            onQueryChange = { viewModel.setSearchQuery(it) }
        )

        // ── Content ─────────────────────────────────────────────────────────

        // Error banner: shown non-destructively when matches already exist,
        // so the user can see the list even when a refresh fails (e.g. 429).
        val hasMatches = uiState.matches.isNotEmpty() || uiState.liveMatches.isNotEmpty()
        if (uiState.error != null && hasMatches) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF4A1A1A))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "⚠️  ${uiState.error}",
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.refresh() }) {
                    Text("Retry", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = AccentGreen)
                        Text(
                            text = when (uiState.selectedTab) {
                                MatchTab.LIVE     -> "Loading live matches..."
                                MatchTab.UPCOMING -> "Loading upcoming matches..."
                                MatchTab.FINISHED -> "Loading finished matches..."
                                else              -> "Loading matches..."
                            },
                            color = TextMuted, fontSize = 14.sp
                        )
                    }
                }
            }
            uiState.error != null && !hasMatches -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text("⚠️", fontSize = 40.sp)
                        Text(
                            uiState.error ?: "Error",
                            color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { viewModel.refresh() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            uiState.filteredMatches.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🏟️", fontSize = 40.sp)
                        Text(
                            text = when (uiState.selectedTab) {
                                MatchTab.LIVE     -> "No live matches right now"
                                MatchTab.UPCOMING -> "No upcoming matches found"
                                MatchTab.FINISHED -> "No finished matches found"
                                else              -> "No matches found"
                            },
                            color = TextMuted, fontSize = 14.sp
                        )
                    }
                }
            }
            else -> {
                val isLiveTab = uiState.selectedTab == MatchTab.LIVE
                val totalCount = if (isLiveTab) uiState.liveMatches.size else uiState.matches.size
                val filteredCount = uiState.filteredMatches.size
                val isToday = uiState.selectedDate == todayDate()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val countText = when {
                        uiState.searchQuery.isNotBlank() -> "$filteredCount of $totalCount matches"
                        isLiveTab -> "$totalCount live matches"
                        isToday -> "$totalCount matches today"
                        else -> "$totalCount matches"
                    }
                    Text(
                        text = countText,
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    if (!isLiveTab && isToday && uiState.searchQuery.isBlank()) {
                        Text(
                            text = "Today",
                            color = AccentGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                LazyColumn(
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.filteredMatches, key = { it.id }) { match ->
                        MatchCard(match = match, onClick = {
                            onMatchClick(match.id.toString())
                        })
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DateSelector(selectedDate: String, onDateSelected: (String) -> Unit) {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val displaySdf = SimpleDateFormat("MMM dd", Locale.US)
    val today = sdf.format(Date())

    val dates = (-2..4).map { offset ->
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offset)
        sdf.format(cal.time)
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        items(dates) { date ->
            val isSelected = date == selectedDate
            val isToday = date == today
            val displayDate = try {
                val d = sdf.parse(date)
                if (d != null) {
                    if (isToday) "Today" else displaySdf.format(d)
                } else date
            } catch (e: Exception) { date }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) AccentGreen.copy(alpha = 0.2f) else BgCard)
                    .border(1.dp, if (isSelected) AccentGreen else BorderColor, RoundedCornerShape(8.dp))
                    .clickable { onDateSelected(date) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayDate,
                    color = if (isSelected) AccentGreen else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(AccentGreen),
            decorationBox = { inner ->
                if (query.isEmpty()) Text("Search teams or leagues…", color = TextMuted, fontSize = 14.sp)
                inner()
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Clear",
                tint = TextMuted,
                modifier = Modifier.size(16.dp).clickable { onQueryChange("") }
            )
        }
    }
}
