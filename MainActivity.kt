package com.footballanalyzer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.footballanalyzer.app.ui.screens.AnalysisScreen
import com.footballanalyzer.app.ui.screens.HistoryScreen
import com.footballanalyzer.app.ui.screens.MatchesScreen
import com.footballanalyzer.app.ui.theme.*
import com.footballanalyzer.app.viewmodel.AnalysisViewModel
import com.footballanalyzer.app.viewmodel.HistoryViewModel
import com.footballanalyzer.app.viewmodel.MatchesViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FootballAnalyzerTheme {
                FootballApp()
            }
        }
    }
}

@Composable
fun FootballApp() {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route

    val matchesViewModel: MatchesViewModel = viewModel()
    val historyViewModel: HistoryViewModel = viewModel()

    Scaffold(
        containerColor = BgColor,
        bottomBar = {
            if (currentRoute == "matches" || currentRoute == "history") {
                NavigationBar(
                    containerColor = BgCard,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentRoute == "matches",
                        onClick = {
                            if (currentRoute != "matches") {
                                navController.navigate("matches") {
                                    popUpTo("matches") { inclusive = true }
                                }
                            }
                        },
                        icon = {
                            Text(text = "⚽", fontSize = 20.sp)
                        },
                        label = { Text("Matches", color = if (currentRoute == "matches") AccentGreen else TextMuted) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentGreen,
                            unselectedIconColor = TextMuted,
                            indicatorColor = AccentDim
                        )
                    )
                    NavigationBarItem(
                        selected = currentRoute == "history",
                        onClick = {
                            if (currentRoute != "history") {
                                navController.navigate("history") {
                                    popUpTo("matches")
                                }
                            }
                        },
                        icon = {
                            Icon(Icons.Default.History, contentDescription = null)
                        },
                        label = { Text("History", color = if (currentRoute == "history") AccentGreen else TextMuted) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentGreen,
                            unselectedIconColor = TextMuted,
                            indicatorColor = AccentDim
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "matches",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable("matches") {
                MatchesScreen(
                    onMatchClick = { matchId ->
                        navController.navigate("analysis/$matchId")
                    },
                    viewModel = matchesViewModel
                )
            }
            composable("history") {
                HistoryScreen(
                    onMatchClick = { matchId ->
                        navController.navigate("analysis/$matchId")
                    },
                    viewModel = historyViewModel
                )
            }
            composable(
                route = "analysis/{matchId}",
                arguments = listOf(navArgument("matchId") { type = NavType.StringType })
            ) { backStackEntry ->
                val matchId = backStackEntry.arguments?.getString("matchId") ?: return@composable
                val analysisViewModel: AnalysisViewModel = viewModel()
                AnalysisScreen(
                    matchId = matchId,
                    onBack = { navController.popBackStack() },
                    viewModel = analysisViewModel
                )
            }
        }
    }
}
