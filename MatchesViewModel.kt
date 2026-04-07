package com.footballanalyzer.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footballanalyzer.app.data.model.Match
import com.footballanalyzer.app.data.repository.FootballRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MatchTab { ALL, LIVE, UPCOMING, FINISHED }

data class MatchesUiState(
    val isLoading: Boolean = false,
    val matches: List<Match> = emptyList(),
    val liveMatches: List<Match> = emptyList(),
    val error: String? = null,
    val selectedDate: String = todayDate(),
    val searchQuery: String = "",
    val showLive: Boolean = false,
    val selectedTab: MatchTab = MatchTab.ALL
) {
    val filteredMatches: List<Match>
        get() {
            val tabFiltered = when (selectedTab) {
                MatchTab.ALL      -> matches
                MatchTab.LIVE     -> liveMatches
                MatchTab.UPCOMING -> matches.filter { it.isUpcoming() }
                MatchTab.FINISHED -> matches.filter { it.isFinished() }
            }
            return if (searchQuery.isBlank()) tabFiltered else {
                tabFiltered.filter { m ->
                    val q = searchQuery.lowercase()
                    m.homeTeamName.lowercase().contains(q) ||
                    m.awayTeamName.lowercase().contains(q) ||
                    m.leagueName.lowercase().contains(q)
                }
            }
        }
}

fun todayDate(): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

class MatchesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FootballRepository()
    private val _uiState = MutableStateFlow(MatchesUiState())
    val uiState: StateFlow<MatchesUiState> = _uiState

    init {
        loadMatches()
    }

    fun selectTab(tab: MatchTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab, showLive = tab == MatchTab.LIVE)
        if (tab == MatchTab.LIVE) {
            loadLiveMatches()
        } else if (_uiState.value.matches.isEmpty()) {
            loadMatches(_uiState.value.selectedDate)
        }
    }

    fun loadMatches(date: String? = null) {
        val targetDate = date ?: _uiState.value.selectedDate
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            selectedDate = targetDate,
            showLive = _uiState.value.selectedTab == MatchTab.LIVE
        )
        viewModelScope.launch {
            try {
                val matches = repository.getMatchesByDate(targetDate)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    matches = matches
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load matches"
                )
            }
        }
    }

    fun loadLiveMatches() {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            showLive = true
        )
        viewModelScope.launch {
            try {
                val live = repository.getLiveMatches()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    liveMatches = live
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load live matches"
                )
            }
        }
    }

    fun selectDate(date: String) {
        loadMatches(date)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun refresh() {
        if (_uiState.value.selectedTab == MatchTab.LIVE) {
            loadLiveMatches()
        } else {
            loadMatches(_uiState.value.selectedDate)
        }
    }
}
