package com.footballanalyzer.app.data.repository

import com.footballanalyzer.app.data.api.ApiClient
import com.footballanalyzer.app.data.model.Analysis
import com.footballanalyzer.app.data.model.AnalysisResponse
import com.footballanalyzer.app.data.model.ApiGame
import com.footballanalyzer.app.data.model.GlickoData
import com.footballanalyzer.app.data.model.ApiSaBookmakerOdds
import com.footballanalyzer.app.data.model.ApiSaLiveOdds
import com.footballanalyzer.app.data.model.ApiStatistics
import com.footballanalyzer.app.data.model.BettingTip
import com.footballanalyzer.app.data.model.BookmakerOdds
import com.footballanalyzer.app.data.model.GoalLinePrediction
import com.footballanalyzer.app.data.model.H2HData
import com.footballanalyzer.app.data.model.H2HMatch
import com.footballanalyzer.app.data.model.Match
import com.footballanalyzer.app.data.model.MatchOdds
import com.footballanalyzer.app.data.model.TeamFeatures
import com.footballanalyzer.app.data.model.TeamFormStat
import com.footballanalyzer.app.data.model.TeamRichStats
import com.footballanalyzer.app.data.model.extractFor
import com.footballanalyzer.app.data.model.toMatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FootballRepository {

    private val api = ApiClient.footballApi

    private val FINISHED_STATUSES = setOf(8, 9, 10, 17, 18)

    // ─── League calibration table ──────────────────────────────────────────
    private val LEAGUE_CALIBRATION = mapOf(
        // ── England ───────────────────────────────────────────────────────
        39   to LeagueParams("Premier League",        avgGoals = 2.72, homeAdv = 1.08, dcRho = -0.130),
        40   to LeagueParams("Championship",          avgGoals = 2.38, homeAdv = 1.11, dcRho = -0.125),
        41   to LeagueParams("League One",            avgGoals = 2.45, homeAdv = 1.10, dcRho = -0.122),
        42   to LeagueParams("League Two",            avgGoals = 2.38, homeAdv = 1.09, dcRho = -0.118),
        // ── Spain ─────────────────────────────────────────────────────────
        140  to LeagueParams("La Liga",               avgGoals = 2.52, homeAdv = 1.10, dcRho = -0.125),
        141  to LeagueParams("La Liga 2",             avgGoals = 2.42, homeAdv = 1.10, dcRho = -0.120),
        // ── Germany ───────────────────────────────────────────────────────
        78   to LeagueParams("Bundesliga",            avgGoals = 2.85, homeAdv = 1.05, dcRho = -0.130),
        79   to LeagueParams("2. Bundesliga",         avgGoals = 2.82, homeAdv = 1.05, dcRho = -0.128),
        80   to LeagueParams("3. Liga",               avgGoals = 2.68, homeAdv = 1.06, dcRho = -0.122),
        // ── Italy ─────────────────────────────────────────────────────────
        135  to LeagueParams("Serie A",               avgGoals = 2.50, homeAdv = 1.09, dcRho = -0.120),
        136  to LeagueParams("Serie B",               avgGoals = 2.38, homeAdv = 1.10, dcRho = -0.118),
        // ── France ────────────────────────────────────────────────────────
        61   to LeagueParams("Ligue 1",               avgGoals = 2.55, homeAdv = 1.12, dcRho = -0.128),
        62   to LeagueParams("Ligue 2",               avgGoals = 2.38, homeAdv = 1.10, dcRho = -0.120),
        // ── UEFA ──────────────────────────────────────────────────────────
        2    to LeagueParams("Champions League",      avgGoals = 2.80, homeAdv = 1.06, dcRho = -0.130),
        3    to LeagueParams("Europa League",         avgGoals = 2.65, homeAdv = 1.04, dcRho = -0.125),
        848  to LeagueParams("Conference League",     avgGoals = 2.58, homeAdv = 1.03, dcRho = -0.128),
        // ── Netherlands ───────────────────────────────────────────────────
        88   to LeagueParams("Eredivisie",            avgGoals = 3.10, homeAdv = 1.10, dcRho = -0.133),
        89   to LeagueParams("Eerste Divisie",        avgGoals = 3.05, homeAdv = 1.10, dcRho = -0.130),
        // ── Portugal ──────────────────────────────────────────────────────
        94   to LeagueParams("Primeira Liga",         avgGoals = 2.60, homeAdv = 1.13, dcRho = -0.125),
        95   to LeagueParams("Liga Portugal 2",       avgGoals = 2.45, homeAdv = 1.12, dcRho = -0.120),
        // ── Russia ────────────────────────────────────────────────────────
        235  to LeagueParams("Russian PL",            avgGoals = 2.45, homeAdv = 1.14, dcRho = -0.120),
        // ── Belgium ───────────────────────────────────────────────────────
        144  to LeagueParams("Pro League",            avgGoals = 2.88, homeAdv = 1.09, dcRho = -0.130),
        // ── Turkey ────────────────────────────────────────────────────────
        203  to LeagueParams("Süper Lig",             avgGoals = 2.68, homeAdv = 1.13, dcRho = -0.125),
        // ── Scotland ──────────────────────────────────────────────────────
        179  to LeagueParams("Premiership",           avgGoals = 2.62, homeAdv = 1.09, dcRho = -0.128),
        // ── Switzerland ───────────────────────────────────────────────────
        197  to LeagueParams("Super League",          avgGoals = 2.72, homeAdv = 1.08, dcRho = -0.122),
        // ── Austria ───────────────────────────────────────────────────────
        218  to LeagueParams("Bundesliga Austria",    avgGoals = 2.88, homeAdv = 1.07, dcRho = -0.128),
        // ── Scandinavia ───────────────────────────────────────────────────
        103  to LeagueParams("Eliteserien",           avgGoals = 2.82, homeAdv = 1.08, dcRho = -0.125),
        104  to LeagueParams("Allsvenskan",           avgGoals = 2.68, homeAdv = 1.07, dcRho = -0.120),
        119  to LeagueParams("Superliga Denmark",     avgGoals = 2.78, homeAdv = 1.09, dcRho = -0.125),
        107  to LeagueParams("Veikkausliiga",         avgGoals = 2.75, homeAdv = 1.08, dcRho = -0.122),
        // ── Poland ────────────────────────────────────────────────────────
        106  to LeagueParams("Ekstraklasa",           avgGoals = 2.48, homeAdv = 1.12, dcRho = -0.118),
        // ── Czech Republic ────────────────────────────────────────────────
        271  to LeagueParams("Fortuna Liga",          avgGoals = 2.58, homeAdv = 1.11, dcRho = -0.120),
        // ── Greece ────────────────────────────────────────────────────────
        286  to LeagueParams("Super League Greece",   avgGoals = 2.45, homeAdv = 1.12, dcRho = -0.118),
        // ── Romania ───────────────────────────────────────────────────────
        283  to LeagueParams("Liga I Romania",        avgGoals = 2.42, homeAdv = 1.13, dcRho = -0.115),
        // ── Ukraine ───────────────────────────────────────────────────────
        244  to LeagueParams("Premier League UKR",    avgGoals = 2.38, homeAdv = 1.13, dcRho = -0.115),
        // ── Hungary ───────────────────────────────────────────────────────
        318  to LeagueParams("OTP Bank Liga",         avgGoals = 2.58, homeAdv = 1.13, dcRho = -0.115),
        // ── Croatia ───────────────────────────────────────────────────────
        345  to LeagueParams("HNL Croatia",           avgGoals = 2.52, homeAdv = 1.12, dcRho = -0.118),
        // ── Brazil ────────────────────────────────────────────────────────
        71   to LeagueParams("Brasileirao",           avgGoals = 2.48, homeAdv = 1.18, dcRho = -0.115),
        72   to LeagueParams("Série B Brazil",        avgGoals = 2.42, homeAdv = 1.18, dcRho = -0.112),
        // ── Argentina ─────────────────────────────────────────────────────
        128  to LeagueParams("Liga Profesional",      avgGoals = 2.48, homeAdv = 1.20, dcRho = -0.110),
        // ── Mexico ────────────────────────────────────────────────────────
        262  to LeagueParams("Liga MX",               avgGoals = 2.70, homeAdv = 1.15, dcRho = -0.130),
        // ── USA ───────────────────────────────────────────────────────────
        253  to LeagueParams("MLS",                   avgGoals = 2.92, homeAdv = 1.08, dcRho = -0.128),
        // ── Chile ─────────────────────────────────────────────────────────
        242  to LeagueParams("Primera División CHL",  avgGoals = 2.38, homeAdv = 1.18, dcRho = -0.108),
        // ── Japan ─────────────────────────────────────────────────────────
        98   to LeagueParams("J1 League",             avgGoals = 2.65, homeAdv = 1.10, dcRho = -0.120),
        // ── South Korea ───────────────────────────────────────────────────
        292  to LeagueParams("K League 1",            avgGoals = 2.52, homeAdv = 1.10, dcRho = -0.115),
        // ── China ─────────────────────────────────────────────────────────
        169  to LeagueParams("Chinese Super League",  avgGoals = 2.62, homeAdv = 1.15, dcRho = -0.118),
        // ── Australia ─────────────────────────────────────────────────────
        188  to LeagueParams("A-League",              avgGoals = 2.88, homeAdv = 1.07, dcRho = -0.122),
        // ── Saudi Arabia ──────────────────────────────────────────────────
        307  to LeagueParams("Saudi Pro League",      avgGoals = 2.78, homeAdv = 1.13, dcRho = -0.120)
    )
    private data class LeagueParams(
        val name: String, val avgGoals: Double,
        val homeAdv: Double, val dcRho: Double
    )
    private val DEFAULT_LEAGUE = LeagueParams("Default", avgGoals = 2.60, homeAdv = 1.12, dcRho = -0.130)

    // ─── Priority bookmaker IDs shown first (Pinnacle=4, Bet365=8, 1xBet=11) ──
    private val PRIORITY_BOOKMAKER_IDS = setOf(4, 8, 11, 6, 16)

    // ─── Weights for 7 recent matches (most recent = highest weight) ──────
    private val WEIGHTS_7 = listOf(0.25, 0.20, 0.17, 0.14, 0.11, 0.08, 0.05)

    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getMatchesByDate(date: String): List<Match> {
        val pageSize = 500
        val allGames = mutableListOf<ApiGame>()
        var offset = 0
        while (true) {
            val response = api.getMatchesByDate(date, pageSize, offset)
            val page = response.data ?: break
            allGames.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        return allGames.map { it.toMatch() }
    }

    suspend fun getLiveMatches(): List<Match> {
        val pageSize = 500
        val allGames = mutableListOf<ApiGame>()
        var offset = 0
        while (true) {
            val response = api.getLiveMatches(true, pageSize, offset)
            val page = response.data ?: break
            allGames.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        return allGames.map { it.toMatch() }
    }

    // ─── Fetch bookmaker odds (prematch + live) in parallel ───────────────

    suspend fun fetchMatchOdds(matchId: Int, isLive: Boolean): MatchOdds {
        return try {
            coroutineScope {
                val prematchDeferred = async {
                    runCatching { api.getPrematchOdds(matchId) }.getOrNull()
                }
                val liveDeferred = if (isLive) async {
                    runCatching { api.getLiveOdds(matchId) }.getOrNull()
                } else null

                val prematchResp = prematchDeferred.await()
                val liveResp = liveDeferred?.await()

                val prematchBookmakers = prematchResp?.data
                    ?.map { bkOdds -> mapBookmakerOdds(bkOdds) }
                    ?.sortedWith(compareByDescending<BookmakerOdds> {
                        it.bookmakerId in PRIORITY_BOOKMAKER_IDS
                    }.thenBy { it.bookmakerName })
                    ?: emptyList()

                val liveOdds = liveResp?.data?.let { mapLiveOdds(it) }
                val liveElapsed = liveResp?.data?.elapsed

                MatchOdds(
                    prematch = prematchBookmakers,
                    live = liveOdds,
                    liveElapsed = liveElapsed
                )
            }
        } catch (e: Exception) {
            MatchOdds(error = e.message)
        }
    }

    /** Convert raw ApiSaBookmakerOdds → condensed BookmakerOdds (1x2, O/U 2.5, BTTS) */
    private fun mapBookmakerOdds(raw: ApiSaBookmakerOdds): BookmakerOdds {
        var homeWin: Double? = null
        var draw: Double? = null
        var awayWin: Double? = null
        var over25: Double? = null
        var under25: Double? = null
        var bttsYes: Double? = null
        var bttsNo: Double? = null

        raw.odds?.forEach { market ->
            val marketId = market.marketId ?: return@forEach
            val prices = market.odds ?: return@forEach
            when (marketId) {
                1 -> {
                    prices.forEach { price ->
                        when (price.name?.lowercase()) {
                            "home", "1" -> homeWin = price.value?.toDouble()
                            "draw", "x" -> draw = price.value?.toDouble()
                            "away", "2" -> awayWin = price.value?.toDouble()
                        }
                    }
                }
                5 -> {
                    prices.forEach { price ->
                        val name = price.name?.lowercase() ?: return@forEach
                        when {
                            name.contains("over") && name.contains("2.5") ->
                                over25 = price.value?.toDouble()
                            name.contains("under") && name.contains("2.5") ->
                                under25 = price.value?.toDouble()
                        }
                    }
                    if (over25 == null && under25 == null && prices.size == 2) {
                        over25 = prices[0].value?.toDouble()
                        under25 = prices[1].value?.toDouble()
                    }
                }
                8 -> {
                    prices.forEach { price ->
                        val name = price.name?.lowercase() ?: return@forEach
                        when {
                            name == "yes" || name.contains("btts yes") -> bttsYes = price.value?.toDouble()
                            name == "no"  || name.contains("btts no")  -> bttsNo  = price.value?.toDouble()
                        }
                    }
                    if (bttsYes == null && bttsNo == null && prices.size == 2) {
                        bttsYes = prices[0].value?.toDouble()
                        bttsNo  = prices[1].value?.toDouble()
                    }
                }
            }
        }

        return BookmakerOdds(
            bookmakerId = raw.bookmakerId ?: 0,
            bookmakerName = raw.bookmakerName ?: "Unknown",
            homeWin = homeWin, draw = draw, awayWin = awayWin,
            over25 = over25, under25 = under25, bttsYes = bttsYes, bttsNo = bttsNo
        )
    }

    private fun mapLiveOdds(raw: ApiSaLiveOdds): BookmakerOdds {
        var homeWin: Double? = null
        var draw: Double? = null
        var awayWin: Double? = null
        var over25: Double? = null
        var under25: Double? = null
        var bttsYes: Double? = null
        var bttsNo: Double? = null

        raw.odds?.forEach { market ->
            val marketId = market.marketId ?: return@forEach
            val prices = market.odds ?: return@forEach
            when (marketId) {
                59 -> {
                    prices.forEach { price ->
                        when (price.name?.lowercase()) {
                            "home", "1" -> homeWin = price.value?.toDouble()
                            "draw", "x" -> draw = price.value?.toDouble()
                            "away", "2" -> awayWin = price.value?.toDouble()
                        }
                    }
                }
                25 -> {
                    prices.forEach { price ->
                        val name = price.name?.lowercase() ?: return@forEach
                        when {
                            name.contains("over") && name.contains("2.5") -> over25 = price.value?.toDouble()
                            name.contains("under") && name.contains("2.5") -> under25 = price.value?.toDouble()
                        }
                    }
                }
                30 -> {
                    prices.forEach { price ->
                        val name = price.name?.lowercase() ?: return@forEach
                        when {
                            name == "yes" -> bttsYes = price.value?.toDouble()
                            name == "no"  -> bttsNo  = price.value?.toDouble()
                        }
                    }
                }
                48 -> {
                    if (homeWin == null) {
                        prices.forEach { price ->
                            when (price.name?.lowercase()) {
                                "home", "1" -> homeWin = price.value?.toDouble()
                                "away", "2" -> awayWin = price.value?.toDouble()
                            }
                        }
                    }
                }
            }
        }

        return BookmakerOdds(
            bookmakerId = 0, bookmakerName = "Live",
            homeWin = homeWin, draw = draw, awayWin = awayWin,
            over25 = over25, under25 = under25, bttsYes = bttsYes, bttsNo = bttsNo
        )
    }

    // ─────────────────────────────────────────────────────────────────────────

    suspend fun analyzeMatch(matchId: String): AnalysisResponse {
        val detailResp = api.getGameDetail(matchId)
        val gameDetail = detailResp.data ?: throw Exception("Match not found")
        val game = gameDetail.game ?: throw Exception("Match data unavailable")

        val match = game.toMatch()
        val homeTeamId = match.homeTeamId
        val awayTeamId = match.awayTeamId
        val excludeId = game.id ?: 0

        val leagueParams = LEAGUE_CALIBRATION[match.leagueId] ?: DEFAULT_LEAGUE

        // Phase 1: fetch game lists, H2H, odds, Glicko-2 — all in parallel
        val (homeGames, awayGames, h2hGames, matchOdds, rawGlicko) = coroutineScope {
            val homeDeferred = async { fetchTeamRecentGames(homeTeamId, excludeId, match.leagueId) }
            val awayDeferred = async { fetchTeamRecentGames(awayTeamId, excludeId, match.leagueId) }
            val h2hDeferred  = async { fetchH2HGames(homeTeamId, awayTeamId) }
            val oddsDeferred = async {
                runCatching { fetchMatchOdds(match.id, match.isLive()) }.getOrElse { MatchOdds(error = it.message) }
            }
            val glickoDeferred = async {
                runCatching { api.getGlickoData(matchId).data?.glicko }.getOrNull()
            }
            val home   = homeDeferred.await()
            val away   = awayDeferred.await()
            val h2h    = h2hDeferred.await()
            val odds   = oddsDeferred.await()
            val glicko = glickoDeferred.await()
            DataQuint(home, away, h2h, odds, glicko)
        }

        // Build processed GlickoData from raw API response
        val glicko: GlickoData? = if (
            rawGlicko?.homeRating != null && rawGlicko.awayRating != null
        ) {
            GlickoData(
                homeRating         = rawGlicko.homeRating,
                awayRating         = rawGlicko.awayRating,
                homeRd             = rawGlicko.homeRd ?: 350.0,
                awayRd             = rawGlicko.awayRd ?: 350.0,
                homeXg             = rawGlicko.homeXg?.takeIf { it > 0 },
                awayXg             = rawGlicko.awayXg?.takeIf { it > 0 },
                homeWinProbability = rawGlicko.homeWinProbability,
                awayWinProbability = rawGlicko.awayWinProbability,
                homeVolatility     = rawGlicko.homeVolatility,
                awayVolatility     = rawGlicko.awayVolatility,
                ratingDiff         = rawGlicko.homeRating - rawGlicko.awayRating,
                available          = true
            )
        } else null

        // Phase 2: fetch detailed per-match statistics for both teams in parallel
        // We request up to 10 games and filter for those with actual stats (target: 7)
        val (homeRichStats, awayRichStats) = coroutineScope {
            val homeRichDeferred = async { fetchRichStatsForGames(homeGames.take(10), homeTeamId) }
            val awayRichDeferred = async { fetchRichStatsForGames(awayGames.take(10), awayTeamId) }
            Pair(homeRichDeferred.await(), awayRichDeferred.await())
        }

        val homeForm = buildFormStats(homeGames, homeTeamId, leagueParams.avgGoals, homeRichStats)
        val awayForm = buildFormStats(awayGames, awayTeamId, leagueParams.avgGoals, awayRichStats)

        val homeFeatures = computeTeamFeatures(homeForm, isHome = true, leagueParams)
        val awayFeatures = computeTeamFeatures(awayForm, isHome = false, leagueParams)

        val h2h = computeH2H(h2hGames, homeTeamId, awayTeamId, match.homeTeamName, match.awayTeamName)

        val homeXg = computeExpectedGoals(homeFeatures, awayFeatures, isHome = true, leagueParams, glicko)
        val awayXg = computeExpectedGoals(awayFeatures, homeFeatures, isHome = false, leagueParams, glicko)

        val liveStat = gameDetail.statistics
        val probs = if (match.isLive() && match.elapsed != null) {
            liveAdjustedProbabilities(
                historicalHomeXg = homeXg,
                historicalAwayXg = awayXg,
                elapsed          = match.elapsed,
                currentHomeScore = match.homeResult ?: 0,
                currentAwayScore = match.awayResult ?: 0,
                liveMatchHomeXg  = liveStat?.expectedGoalsHome,
                liveMatchAwayXg  = liveStat?.expectedGoalsAway,
                h2h              = h2h,
                league           = leagueParams,
                liveStats        = liveStat
            )
        } else {
            poissonProbabilities(homeXg, awayXg, h2h, leagueParams)
        }

        val finalProbs = blendWithGlicko(blendWithPinnacle(probs, matchOdds), glicko)

        val bettingTips = buildBettingTips(finalProbs, homeFeatures, awayFeatures, h2h)
        val keyFactors  = buildKeyFactors(homeFeatures, awayFeatures, h2h, match.homeTeamName, match.awayTeamName)
        val confidence  = computeConfidence(homeFeatures, awayFeatures, h2h, homeForm.size,
            pinnacleUsed = finalProbs !== probs, glicko = glicko)

        val liveGoalLines: List<GoalLinePrediction> = if (match.isLive() && finalProbs.overLines.isNotEmpty()) {
            val currentTotal = (match.homeResult ?: 0) + (match.awayResult ?: 0)
            listOf(0.5, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5, 9.5, 10.5)
                .filter { line -> currentTotal < line }
                .take(3)
                .map { line ->
                    val overProb = finalProbs.overLines[line] ?: 0.0
                    GoalLinePrediction(line = line, overProb = overProb, underProb = 1.0 - overProb, alreadyGuaranteed = false)
                }
        } else emptyList()

        val analysis = Analysis(
            homeXg = homeXg, awayXg = awayXg,
            homeWinPct = finalProbs.homeWin * 100,
            drawPct    = finalProbs.draw    * 100,
            awayWinPct = finalProbs.awayWin * 100,
            mostLikelyScore = finalProbs.mostLikelyScore,
            mostLikelyProb  = finalProbs.mostLikelyProb * 100,
            over25Pct   = finalProbs.over25 * 100,
            under25Pct  = finalProbs.under25 * 100,
            bttsPct     = finalProbs.btts * 100,
            expectedTotal = homeXg + awayXg,
            homeFeatures  = homeFeatures,
            awayFeatures  = awayFeatures,
            h2h           = h2h,
            bettingTips   = bettingTips,
            keyFactors    = keyFactors,
            confidenceScore = confidence,
            homeFormMatches = homeForm,
            awayFormMatches = awayForm,
            leagueCalibration = leagueParams.name,
            liveGoalLines = liveGoalLines,
            liveApiHomeXg = liveStat?.expectedGoalsHome,
            liveApiAwayXg = liveStat?.expectedGoalsAway,
            matchOdds = matchOdds,
            glickoData = glicko
        )

        return AnalysisResponse(match = match, analysis = analysis)
    }

    // ─── Internal helper ──────────────────────────────────────────────────

    private data class DataQuad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    private data class DataQuint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

    // ─── Fetching helpers ─────────────────────────────────────────────────

    private suspend fun fetchTeamRecentGames(
        teamId: Int,
        excludeMatchId: Int,
        leagueId: Int? = null
    ): List<ApiGame> {
        val games = runCatching {
            api.getTeamRecentGames(teamId = teamId, limit = 50).data ?: emptyList()
        }.getOrElse { emptyList() }

        val finished = games.filter { g ->
            val id = g.id ?: return@filter false
            g.status in FINISHED_STATUSES && id != excludeMatchId
        }

        if (leagueId == null) return finished.take(20)

        val sameLeague = finished.filter { g -> g.season?.league?.id == leagueId }.take(15)
        val otherComps = finished.filter { g -> g.season?.league?.id != leagueId }.take(5)

        return (sameLeague + otherComps)
    }

    /**
     * Fetches detailed statistics for up to [maxGames] recent games in parallel.
     * Games with empty statistics are still included but marked hasStats=false.
     * Target: collect 7 games with real stats; fallback gracefully if fewer available.
     */
    private suspend fun fetchRichStatsForGames(
        games: List<ApiGame>,
        teamId: Int,
        maxGames: Int = 10
    ): List<TeamRichStats> = coroutineScope {
        val candidates = games.take(maxGames)
        val deferreds = candidates.map { game ->
            async {
                val matchId = game.id ?: return@async null
                val isHome = game.homeTeam?.id == teamId
                runCatching {
                    val detail = api.getGameDetail(matchId.toString()).data
                    val stats = detail?.statistics
                    stats?.extractFor(isHome, matchId) ?: emptyStatsFor(isHome, matchId)
                }.getOrElse { emptyStatsFor(isHome, matchId) }
            }
        }
        deferreds.awaitAll()
            .filterNotNull()
            // Prefer matches with real stats; keep up to 7 with stats + fill remaining from no-stats
            .let { all ->
                val withStats    = all.filter { it.hasStats }.take(7)
                val withoutStats = all.filter { !it.hasStats }
                (withStats + withoutStats).take(7)
            }
    }

    /** Returns an empty (neutral-default) TeamRichStats when no data is available. */
    private fun emptyStatsFor(isHome: Boolean, matchId: Int) = TeamRichStats(
        matchId = matchId, isHome = isHome,
        pos = 50.0, totalShots = 0, shotsOnTarget = 0,
        shotsInBox = 0, shotsOutBox = 0, corners = 0,
        bigChances = 0, dangerousAttacks = 0, touchesInBox = 0,
        crosses = 0, interceptions = 0, clearances = 0,
        totalTackles = 0, successTackles = 0, duelsWon = 0, duelsTotal = 0,
        xgApi = null, xgOnTarget = null, xgotFaced = null, goalsPreventedGk = null,
        goalkeeperSaves = 0, errorsLeadingToGoal = 0, hasStats = false
    )

    private suspend fun fetchH2HGames(homeTeamId: Int, awayTeamId: Int): List<ApiGame> {
        return runCatching {
            api.getH2HGames(bothTeams = "$homeTeamId,$awayTeamId", limit = 20).data ?: emptyList()
        }.getOrElse { emptyList() }
            .filter { g -> g.status in FINISHED_STATUSES }
    }

    // ─── Form stats builder ───────────────────────────────────────────────

    private fun buildFormStats(
        games: List<ApiGame>,
        teamId: Int,
        leagueAvgGoals: Double,
        richStats: List<TeamRichStats> = emptyList()
    ): List<TeamFormStat> {
        val richByMatchId = richStats.associateBy { it.matchId }

        return games.mapNotNull { g ->
            val isHome = g.homeTeam?.id == teamId
            val goalsFor     = if (isHome) g.homeResult ?: g.homeFTResult else g.awayResult ?: g.awayFTResult
            val goalsAgainst = if (isHome) g.awayResult ?: g.awayFTResult else g.homeResult ?: g.homeFTResult
            if (goalsFor == null || goalsAgainst == null) return@mapNotNull null

            val opponentName = if (isHome) g.awayTeam?.name ?: "?" else g.homeTeam?.name ?: "?"
            val competition  = g.season?.league?.name
            val result = when {
                goalsFor > goalsAgainst -> "W"
                goalsFor == goalsAgainst -> "D"
                else -> "L"
            }

            val totalGoals = (goalsFor + goalsAgainst).toDouble()
            val rawStrength = totalGoals / leagueAvgGoals.coerceAtLeast(1.0)
            val opponentStrength = rawStrength.coerceIn(0.50, 1.50)

            val htGF = if (isHome) g.homeHTResult ?: 0 else g.awayHTResult ?: 0
            val htGA = if (isHome) g.awayHTResult ?: 0 else g.homeHTResult ?: 0

            val rich = richByMatchId[g.id ?: -1]

            TeamFormStat(
                matchId = g.id ?: 0, date = g.date, isHome = isHome,
                opponent = opponentName,
                goalsFor = goalsFor, goalsAgainst = goalsAgainst,
                result = result,
                possession = rich?.pos?.toInt() ?: 50,
                shots = rich?.totalShots ?: 0,
                shotsOnTarget = rich?.shotsOnTarget ?: 0,
                competition = competition,
                opponentStrength = opponentStrength,
                htGoalsFor = htGF,
                htGoalsAgainst = htGA,
                richStats = rich
            )
        }.take(20)
    }

    // ─── Team features ────────────────────────────────────────────────────

    private fun computeTeamFeatures(
        form: List<TeamFormStat>,
        isHome: Boolean,
        league: LeagueParams
    ): TeamFeatures {
        if (form.isEmpty()) {
            return TeamFeatures(
                homeAdvantage = if (isHome) league.homeAdv else (2.0 - league.homeAdv),
                motivationFactor = 1.0
            )
        }

        val n = form.size.toDouble()
        val wins  = form.count { it.result == "W" }
        val draws = form.count { it.result == "D" }
        val winRate  = wins  / n
        val drawRate = draws / n
        val ppg  = (wins * 3 + draws) / n
        val avgGF = form.map { it.goalsFor }.average()
        val avgGA = form.map { it.goalsAgainst }.average()
        val avgShots = form.map { it.shots.toDouble() }.average()
        val avgShotsOnTarget = form.map { it.shotsOnTarget.toDouble() }.average()
        val avgPossession = form.map { it.possession.toDouble() }.average()

        val recent5 = form.take(5)
        val recentPoints = recent5.map { when (it.result) { "W" -> 3; "D" -> 1; else -> 0 } }.sum()
        val formString   = recent5.joinToString("") { it.result }

        val last5Pts = recent5.sumOf { when (it.result) { "W" -> 3.0; "D" -> 1.0; else -> 0.0 } }
        val prev5    = form.drop(5).take(5)
        val prev5Pts = if (prev5.isEmpty()) last5Pts
                       else prev5.sumOf { when (it.result) { "W" -> 3.0; "D" -> 1.0; else -> 0.0 } }
        val trend = (last5Pts - prev5Pts) / 15.0

        val totalWeight = form.sumOf { it.opponentStrength }
        val weightedWins = form.sumOf { stat ->
            val points = when (stat.result) { "W" -> 1.0; "D" -> 0.5; else -> 0.0 }
            points * stat.opponentStrength
        }
        val strengthAdjWinRate = if (totalWeight > 0) weightedWins / totalWeight else winRate

        val homeGames = form.filter { it.isHome }
        val awayGames = form.filter { !it.isHome }

        fun splitStats(games: List<TeamFormStat>): Triple<Double, Double, Double> {
            if (games.isEmpty()) return Triple(winRate, avgGF, avgGA)
            val w = games.count { it.result == "W" }.toDouble() / games.size
            val gf = games.map { it.goalsFor }.average()
            val ga = games.map { it.goalsAgainst }.average()
            return Triple(w, gf, ga)
        }
        val (hwR, hGF, hGA) = splitStats(homeGames)
        val (awR, aGF, aGA) = splitStats(awayGames)

        // ── Improved motivation factor: multi-signal continuous calculation ────
        //
        // Four signals combined:
        //
        //  1. ppgSignal       — how strong the overall season form is (nonlinear)
        //  2. trendSignal     — momentum: recent 5 vs previous 5 games
        //  3. consistencySignal — teams that avoid defeats regularly play with
        //                         more confidence (not-lose rate in last 5)
        //  4. scoringMomentum — teams scoring more than their season average
        //                       in recent matches have elevated attacking intent
        //
        // The final factor is clamped to [0.82, 1.15] — enough to meaningfully
        // influence predictions without overpowering statistical signals.
        //
        // Opponent-strength awareness: strengthAdjWinRate is already computed
        // above and accounts for the quality of opponents faced. We fold a small
        // piece of that into ppgSignal so a team with easy fixtures doesn't get
        // an undeserved boost.

        val ppgNorm = (ppg / 3.0).coerceIn(0.0, 1.0)
        val strengthPenalty = ((0.5 - strengthAdjWinRate) * 0.04).coerceIn(-0.04, 0.04)
        val ppgSignal = when {
            ppgNorm >= 0.78 -> 0.11 + strengthPenalty  // title-contending form (≥2.34 PPG)
            ppgNorm >= 0.63 -> 0.06 + strengthPenalty  // strong form (≥1.89 PPG)
            ppgNorm >= 0.47 -> 0.01 + strengthPenalty  // average form
            ppgNorm >= 0.33 -> -0.05                   // poor form (≤0.99 PPG)
            else            -> -0.10                   // crisis / relegation zone form
        }

        val trendSignal = (trend * 0.55).coerceIn(-0.06, 0.06)

        val recentNonLoss = recent5.count { it.result != "L" }.toDouble() / recent5.size.coerceAtLeast(1)
        val consistencySignal = ((recentNonLoss - 0.40) * 0.05).coerceIn(-0.04, 0.04)

        val recentAvgGF = if (recent5.isNotEmpty()) recent5.map { it.goalsFor }.average() else avgGF
        val scoringMomentum = ((recentAvgGF - avgGF) * 0.025).coerceIn(-0.03, 0.03)

        val motivationFactor = (1.0 + ppgSignal + trendSignal + consistencySignal + scoringMomentum)
            .coerceIn(0.82, 1.15)

        val locationFactor = if (isHome) league.homeAdv else (2.0 - league.homeAdv)

        val firstHalfAvgGF  = form.map { it.htGoalsFor.toDouble() }.average().takeIf { it.isFinite() } ?: 0.0
        val firstHalfAvgGA  = form.map { it.htGoalsAgainst.toDouble() }.average().takeIf { it.isFinite() } ?: 0.0
        val secondHalfAvgGF = form.map { (it.goalsFor - it.htGoalsFor).coerceAtLeast(0).toDouble() }.average().takeIf { it.isFinite() } ?: 0.0
        val secondHalfAvgGA = form.map { (it.goalsAgainst - it.htGoalsAgainst).coerceAtLeast(0).toDouble() }.average().takeIf { it.isFinite() } ?: 0.0

        val htWins             = form.count { it.htGoalsFor > it.htGoalsAgainst }
        val htWinRate          = htWins / n
        val comeFromBehind     = form.count { it.htGoalsFor < it.htGoalsAgainst && it.result == "W" }
        val comeFromBehindRate = comeFromBehind / n
        val holdLead           = form.count { it.htGoalsFor > it.htGoalsAgainst && it.result == "W" }
        val holdLeadRate       = holdLead / n

        // ── Rich stats — computed from detailed per-game statistics ──────────
        val richList = form.mapNotNull { it.richStats }.filter { it.hasStats }
        val statsMatchCount = richList.size

        val avgBigChances      = if (statsMatchCount > 0) richWeightedAvg(richList.map { it.bigChances.toDouble() }) else 0.0
        val avgDangerousAttacks = if (statsMatchCount > 0) richWeightedAvg(richList.map { it.dangerousAttacks.toDouble() }) else 0.0
        val avgShotsInBox      = if (statsMatchCount > 0) richWeightedAvg(richList.map { it.shotsInBox.toDouble() }) else 0.0
        val avgTotalShots      = if (statsMatchCount > 0) richWeightedAvg(richList.map { it.totalShots.toDouble() }) else avgShots
        val avgCorners         = if (statsMatchCount > 0) richWeightedAvg(richList.map { it.corners.toDouble() }) else 0.0
        val avgTacklePct       = if (statsMatchCount > 0) richWeightedAvg(richList.map { it.tacklePct }) else 50.0
        val avgDuelsPct        = if (statsMatchCount > 0) richWeightedAvg(richList.map { it.duelsPct }) else 50.0
        val avgXgApiFor        = if (statsMatchCount > 0) {
            val xgValues = richList.mapNotNull { it.xgApi }
            if (xgValues.isNotEmpty()) xgValues.average() else 0.0
        } else 0.0

        // Rich xG estimate using XG-project algorithm
        val richXgEstimate = if (statsMatchCount >= 3) computeRichXg(richList, isHome) else 0.0

        // Rich xGA: how much xG the opponent generated against this team
        val richXgAgainstEstimate = if (statsMatchCount >= 3) computeRichXgAgainst(richList) else 0.0

        // ── New: goalkeeper quality, errors, defensive solidity ──────────────
        // avgGoalsPrevented: average goals prevented by GK above expected.
        // Positive = elite GK, negative = GK below average. Clamp to avoid outliers.
        val avgGoalsPrevented = if (statsMatchCount > 0) {
            val gpValues = richList.mapNotNull { it.goalsPreventedGk }
            if (gpValues.isNotEmpty()) richWeightedAvg(gpValues, WEIGHTS_7.take(gpValues.size)) else 0.0
        } else 0.0

        // avgXgOnTarget: average xG on target per game (shot quality signal).
        // Used to adjust the attacking xG when API provides this field.
        val avgXgOnTarget = if (statsMatchCount > 0) {
            val xgotValues = richList.mapNotNull { it.xgOnTarget }
            if (xgotValues.isNotEmpty()) richWeightedAvg(xgotValues, WEIGHTS_7.take(xgotValues.size)) else 0.0
        } else 0.0

        // avgErrorsLeadingToGoal: average defensive errors per game.
        // Higher value → team is error-prone → opponent xG gets a risk premium.
        val avgErrorsLeadingToGoal = if (statsMatchCount > 0)
            richWeightedAvg(richList.map { it.errorsLeadingToGoal.toDouble() }) else 0.0

        // defSolidityFactor: based on interceptions + clearances per game.
        // Typical top-league average: ~50 combined defensive actions per game.
        // Factor > 1.0 → more defensive actions than average → stronger defence.
        val avgDefActions = if (statsMatchCount > 0)
            richWeightedAvg(richList.map { (it.interceptions + it.clearances).toDouble() }) else 0.0
        val defSolidityFactor = if (avgDefActions > 0.0)
            (avgDefActions / 50.0).coerceIn(0.60, 1.60) else 0.0

        // gkQualityFactor: multiplicative adjustment for opponent xG calculation.
        // A GK preventing +0.4 goals/game above expected → their team concedes less.
        // Factor range: [0.75, 1.25]. Neutral = 1.0 (no data or GK at expectation level).
        val gkQualityFactor = if (statsMatchCount >= 3 && richList.any { it.goalsPreventedGk != null }) {
            // goalsPreventedGk > 0 = GK saves more than expected → opponents score less
            // Typical range: [-0.5, +0.6] per game. Scale to ±25% xGA multiplier.
            val clampedGp = avgGoalsPrevented.coerceIn(-0.6, 0.6)
            (1.0 - clampedGp * 0.40).coerceIn(0.75, 1.25)
        } else 1.0

        // Blend xGAvg: prefer rich xG when available.
        // 0.20 is the minimum floor in computeRichXg — treating it as valid data
        // would pull xG down for teams where stats are missing/zero.
        // Only use richXgEstimate when it is meaningfully above the floor value.
        val effectiveXgFor = when {
            richXgEstimate > 0.25 -> richXgEstimate
            avgXgApiFor > 0.0     -> avgXgApiFor
            else                  -> avgGF
        }


        // ── Attack and defense strength indices (normalised to league average) ─────
        // These are used by PoissonEngine.calculateFromFeatures() for the Hybrid predictor.
        // attackStrength = effectiveXgFor / (league.avgGoals / 2)
        //   → > 1.0 means this team scores more than the league average per team
        // defenseStrength = (league.avgGoals / 2) / avgGA
        //   → > 1.0 means this team concedes fewer goals than the league average per team
        val leagueHalf      = league.avgGoals / 2.0
        val attackStrength  = ((effectiveXgFor.coerceAtLeast(0.30) / leagueHalf) * 1.05).coerceIn(0.75, 2.2)
        val defenseStrength = (leagueHalf / avgGA.coerceAtLeast(0.70)).coerceIn(0.75, 2.2)

        return TeamFeatures(
            pointsPerGame = ppg, winRate = winRate, drawRate = drawRate,
            lossRate = 1.0 - winRate - drawRate,
            avgGoalsFor = avgGF, avgGoalsAgainst = avgGA,
            xgForAvg = effectiveXgFor, xgAgainstAvg = avgGA,
            avgPossession = avgPossession,
            avgShots = avgTotalShots,
            avgShotsOnTarget = avgShotsOnTarget,
            recentPoints = recentPoints, trend = trend,
            homeAdvantage = locationFactor,
            totalMatches = form.size,
            formString = formString.padEnd(5, '-').take(5),
            strengthAdjustedWinRate = strengthAdjWinRate,
            homeWinRate = hwR, homeAvgGoalsFor = hGF, homeAvgGoalsAgainst = hGA,
            homeMatches = homeGames.size,
            awayWinRate = awR, awayAvgGoalsFor = aGF, awayAvgGoalsAgainst = aGA,
            awayMatches = awayGames.size,
            motivationFactor = motivationFactor,
            firstHalfAvgGoalsFor      = firstHalfAvgGF,
            firstHalfAvgGoalsAgainst  = firstHalfAvgGA,
            secondHalfAvgGoalsFor     = secondHalfAvgGF,
            secondHalfAvgGoalsAgainst = secondHalfAvgGA,
            htWinRate          = htWinRate,
            comeFromBehindRate = comeFromBehindRate,
            holdLeadRate       = holdLeadRate,
            avgBigChances      = avgBigChances,
            avgDangerousAttacks = avgDangerousAttacks,
            avgShotsInBox      = avgShotsInBox,
            avgTotalShots      = avgTotalShots,
            avgCorners         = avgCorners,
            avgTacklePct       = avgTacklePct,
            avgDuelsPct        = avgDuelsPct,
            avgXgApiFor           = avgXgApiFor,
            statsMatchCount       = statsMatchCount,
            richXgEstimate        = richXgEstimate,
            richXgAgainstEstimate = richXgAgainstEstimate,
            avgGoalsPrevented     = avgGoalsPrevented,
            avgXgOnTarget         = avgXgOnTarget,
            avgErrorsLeadingToGoal = avgErrorsLeadingToGoal,
            defSolidityFactor     = defSolidityFactor,
            gkQualityFactor       = gkQualityFactor,
            attackStrength        = attackStrength,
            defenseStrength       = defenseStrength
        )
    }

    // ─── Rich xG computation (calibrated for realistic football xG ranges) ──

    /**
     * Computes expected goals from detailed match statistics.
     *
     * Primary path: uses API-provided xG (sstats.net calculates it server-side).
     * Fallback: shot-based formula when API xG is absent.
     *
     * Calibrated targets:
     *   Weak team  → ~0.8–1.0 xG
     *   Average    → ~1.1–1.4 xG
     *   Strong     → ~1.5–2.1 xG
     *
     * Returns 0.0 when fewer than 3 matches with stats are available.
     */
    private fun computeRichXg(richList: List<TeamRichStats>, isHome: Boolean): Double {
        if (richList.size < 3) return 0.0

        val w = WEIGHTS_7.take(richList.size).let { wl ->
            val sum = wl.sum(); wl.map { it / sum }
        }

        // ── PRIMARY: API xG (already calculated by sstats.net) ──────────────
        val apiPairs = richList.zip(w).mapNotNull { (r, wt) -> r.xgApi?.let { Pair(it, wt) } }
        val baseXg: Double = if (apiPairs.size >= 2) {
            val wTotal = apiPairs.sumOf { it.second }
            apiPairs.sumOf { (xg, wt) -> xg * wt } / wTotal
        } else {
            // ── FALLBACK: shot-based formula when API xG is missing ──────────
            // Coefficients calibrated to typical football xG distributions:
            //   SOT conversion ~12%, big chance ~35%, non-SOT box shot ~6%
            val avgSot    = richWeightedAvg(richList.map { it.shotsOnTarget.toDouble() }, w)
            val avgBig    = richWeightedAvg(richList.map { it.bigChances.toDouble() }, w)
            val avgInBox  = richWeightedAvg(richList.map { it.shotsInBox.toDouble() }, w)
            val avgPos    = richWeightedAvg(richList.map { it.pos }, w)

            // If bigChances is zero across all matches, the API likely doesn't provide this field.
            // When avgBig is unavailable, raise the SOT coefficient to 0.155 to absorb the missing
            // big-chance contribution — avoids double-counting that would occur if we set
            // effectiveBig = avgSot * 0.12 (already in the avgSot * 0.12 term) × 0.35 = overlap.
            val (sotCoeff, effectiveBig) = if (avgBig < 0.05 && avgSot > 0)
                Pair(0.155, 0.0)    // consolidated coefficient, no double-count
            else
                Pair(0.12, avgBig)  // standard decomposed approach when big chances are known

            val shotXg = avgSot * sotCoeff +
                         effectiveBig * 0.35 +
                         (avgInBox - avgSot).coerceAtLeast(0.0) * 0.06

            // Possession: ±15% max effect
            val posFactor = (avgPos / 50.0).coerceIn(0.85, 1.15)
            shotXg * posFactor
        }

        // ── No location adjustment here — venue effect applied once ────────────
        // in computeExpectedGoals() via league.homeAdv. Applying locFactor here
        // would double-count location (here + computeExpectedGoals), overstating
        // home xG by ~8–16% when rich stats are available.
        return baseXg.coerceIn(0.20, 2.80)
    }

    /**
     * Computes xG conceded (xGA) from opponent shot data stored in TeamRichStats.
     *
     * Uses the opponent's API xG when available (≥3 matches); falls back to a
     * shot-based formula identical to the attacking xG calculation.
     * No location adjustment — venue is already handled in computeExpectedGoals().
     */
    private fun computeRichXgAgainst(richList: List<TeamRichStats>): Double {
        if (richList.size < 3) return 0.0

        val w = WEIGHTS_7.take(richList.size).let { wl ->
            val sum = wl.sum(); wl.map { it / sum }
        }

        // PRIMARY: opponent's API xG (most accurate signal)
        val apiPairs = richList.zip(w).mapNotNull { (r, wt) -> r.oppXgApi?.let { Pair(it, wt) } }
        val baseXgA: Double = if (apiPairs.size >= 2) {
            val wTotal = apiPairs.sumOf { it.second }
            apiPairs.sumOf { (xg, wt) -> xg * wt } / wTotal
        } else {
            // FALLBACK: shot-based formula using opponent's shots against this team
            val avgOppSot = richWeightedAvg(richList.map { it.oppShotsOnTarget.toDouble() }, w)
            val avgOppBig = richWeightedAvg(richList.map { it.oppBigChances.toDouble() }, w)
            val avgOppBox = richWeightedAvg(richList.map { it.oppShotsInBox.toDouble() }, w)
            // When oppBigChances is unavailable (avgOppBig ≈ 0), raise SOT coefficient to 0.155
            // to absorb the missing big-chance contribution — mirrors the same fix in computeRichXg.
            val (oppSotCoeff, oppEffectiveBig) = if (avgOppBig < 0.05 && avgOppSot > 0)
                Pair(0.155, 0.0)
            else
                Pair(0.12, avgOppBig)
            oppSotCoeff * avgOppSot + oppEffectiveBig * 0.35 + (avgOppBox - avgOppSot).coerceAtLeast(0.0) * 0.06
        }

        return baseXgA.coerceIn(0.20, 3.0)
    }

    /** Weighted average with optional custom weights (defaults to WEIGHTS_7). */
    private fun richWeightedAvg(values: List<Double>, weights: List<Double> = WEIGHTS_7): Double {
        if (values.isEmpty()) return 0.0
        val w = if (weights.size >= values.size) weights.take(values.size) else List(values.size) { 1.0 / values.size }
        val total = w.sum()
        return if (total == 0.0) 0.0 else values.zip(w).sumOf { (v, wt) -> v * wt } / total
    }

    // ─── H2H ─────────────────────────────────────────────────────────────

    private fun computeH2H(
        games: List<ApiGame>, homeTeamId: Int, awayTeamId: Int,
        homeName: String, awayName: String
    ): H2HData {
        if (games.isEmpty()) return H2HData()
        var homeWins = 0; var draws = 0; var awayWins = 0; var totalGoals = 0.0
        val matchList = mutableListOf<H2HMatch>()
        for (g in games) {
            val homeGoals = (g.homeResult ?: g.homeFTResult) ?: continue
            val awayGoals = (g.awayResult ?: g.awayFTResult) ?: continue
            val gHomeId   = g.homeTeam?.id ?: continue
            val gHomeName = g.homeTeam?.name ?: ""
            val gAwayName = g.awayTeam?.name ?: ""
            if (gHomeId == homeTeamId) {
                when { homeGoals > awayGoals -> homeWins++; homeGoals == awayGoals -> draws++; else -> awayWins++ }
            } else {
                when { awayGoals > homeGoals -> homeWins++; awayGoals == homeGoals -> draws++; else -> awayWins++ }
            }
            totalGoals += homeGoals + awayGoals
            matchList.add(H2HMatch(g.date, gHomeName, gAwayName, homeGoals, awayGoals))
        }
        val total = homeWins + draws + awayWins
        return H2HData(homeWins, draws, awayWins, total,
            if (total > 0) totalGoals / total else 2.5, matchList)
    }

    // ─── Expected Goals ───────────────────────────────────────────────────

    /**
     * Glicko-2 expected score using the correct logistic formula.
     * Returns a value in (0, 1):
     *   0.50 = equally rated teams
     *   0.75 = team A is clearly stronger
     *   0.25 = team A is clearly weaker
     */
    private fun glickoExpectedScore(ratingA: Double, ratingB: Double): Double =
        1.0 / (1.0 + Math.pow(10.0, -(ratingA - ratingB) / 400.0))

    /**
     * Dynamic blend weight for Glicko-2 data based on Rating Deviation and volatility.
     *
     * RD < 75  → rating is very well-established → 35% weight
     * RD < 150 → established rating              → 22% weight
     * RD ≥ 250 → rating barely set               → 3% weight
     *
     * High volatility (> 0.08) means the team is unpredictable → −5% penalty.
     */
    private fun glickoBlendWeight(rd: Double, volatility: Double?): Double {
        val rdWeight = when {
            rd < 75  -> 0.35
            rd < 100 -> 0.30
            rd < 150 -> 0.22
            rd < 200 -> 0.14
            rd < 250 -> 0.08
            else     -> 0.03
        }
        val volPenalty = if (volatility != null && volatility > 0.08) 0.05 else 0.0
        return (rdWeight - volPenalty).coerceAtLeast(0.0)
    }

    private fun computeExpectedGoals(
        team: TeamFeatures, opponent: TeamFeatures,
        isHome: Boolean, league: LeagueParams,
        glicko: GlickoData? = null
    ): Double {
        val leagueAvg = league.avgGoals / 2.0

        val teamAvgGF = if (isHome && team.homeMatches >= 3) team.homeAvgGoalsFor
                        else if (!isHome && team.awayMatches >= 3) team.awayAvgGoalsFor
                        else team.avgGoalsFor

        val oppAvgGA  = if (!isHome && opponent.homeMatches >= 3) opponent.homeAvgGoalsAgainst
                        else if (isHome && opponent.awayMatches >= 3) opponent.awayAvgGoalsAgainst
                        else opponent.avgGoalsAgainst

        val attackStrength = (teamAvgGF.coerceAtLeast(0.30) / leagueAvg).coerceIn(0.25, 3.0)

        // Defensive weakness: blend goal-based GA with xGA from opponent shot data
        // xGA is a truer measure of defensive quality than actual goals conceded
        val goalBasedDefWeakness = (oppAvgGA.coerceAtLeast(0.30) / leagueAvg).coerceIn(0.25, 3.0)
        val defWeakness = when {
            opponent.statsMatchCount >= 5 && opponent.richXgAgainstEstimate > 0.0 -> {
                val xgaRatio = (opponent.richXgAgainstEstimate / leagueAvg).coerceIn(0.25, 3.0)
                goalBasedDefWeakness * 0.55 + xgaRatio * 0.45
            }
            opponent.statsMatchCount >= 3 && opponent.richXgAgainstEstimate > 0.0 -> {
                val xgaRatio = (opponent.richXgAgainstEstimate / leagueAvg).coerceIn(0.25, 3.0)
                goalBasedDefWeakness * 0.70 + xgaRatio * 0.30
            }
            else -> goalBasedDefWeakness
        }

        val adjWinRate  = team.strengthAdjustedWinRate.takeIf { it > 0 } ?: team.winRate
        val oppAdjWin   = opponent.strengthAdjustedWinRate.takeIf { it > 0 } ?: opponent.winRate

        val ppgFactor    = (1.0 + (team.pointsPerGame.coerceIn(0.0, 3.0) - 1.5) * 0.20).coerceIn(0.50, 2.0)
        val oppPPGFactor = (1.0 - (opponent.pointsPerGame.coerceIn(0.0, 3.0) - 1.5) * 0.12).coerceIn(0.60, 1.60)

        // Relative strength: use Glicko-2 expected score (logistic formula) when available.
        // E = 1 / (1 + 10^(-(Ra - Rb) / 400)) — the canonical Glicko-2 expected score.
        //   E = 0.50 → equal teams → multiplier 1.00 (neutral)
        //   E = 0.75 → clearly stronger → multiplier ~1.35
        //   E = 0.25 → clearly weaker  → multiplier ~0.65
        // Fallback: crude win-rate diff when Glicko is unavailable.
        val relStrength = if (glicko != null && glicko.available) {
            val expectedScore = if (isHome)
                glickoExpectedScore(glicko.homeRating, glicko.awayRating)
            else
                glickoExpectedScore(glicko.awayRating, glicko.homeRating)
            (1.0 + (expectedScore - 0.5) * 1.40).coerceIn(0.50, 1.80)
        } else {
            (1.0 + (adjWinRate - oppAdjWin) * 0.40).coerceIn(0.50, 1.80)
        }
        val trendFactor  = (1.0 + team.trend * 0.30).coerceIn(0.70, 1.40)
        val locationFactor = if (isHome) league.homeAdv else (2.0 - league.homeAdv)

        val goalBasedXg = (leagueAvg * attackStrength * defWeakness *
                  ppgFactor * oppPPGFactor * relStrength * trendFactor * locationFactor)
            .coerceIn(0.25, 4.5)

        val htXg = if (team.secondHalfAvgGoalsFor > 0.0) {
            val htAttack = (team.secondHalfAvgGoalsFor / (leagueAvg * 0.55)).coerceIn(0.4, 2.5)
            val htDefWeak = ((opponent.secondHalfAvgGoalsAgainst).coerceAtLeast(0.1) /
                            (leagueAvg * 0.55)).coerceIn(0.4, 2.5)
            (leagueAvg * htAttack * htDefWeak * locationFactor).coerceIn(0.20, 4.5)
        } else goalBasedXg
        val blendedGoalXg = if (team.secondHalfAvgGoalsFor > 0.0) (goalBasedXg * 0.80 + htXg * 0.20) else goalBasedXg

        // ── xgOnTarget refinement: adjust richXgEstimate toward xGoT signal ───
        // xG on target is a more precise quality indicator than raw xG.
        // When xGoT is available we blend it with API xG to reduce overestimation.
        val refinedRichXg = if (team.statsMatchCount >= 3
                                && team.richXgEstimate > 0.0
                                && team.avgXgOnTarget > 0.0) {
            // xGoT is typically 60–80% of xG; cap influence to avoid collapse
            val xgotRatio = (team.avgXgOnTarget / team.richXgEstimate).coerceIn(0.50, 1.30)
            val blendW    = if (team.statsMatchCount >= 5) 0.25 else 0.15
            team.richXgEstimate * (1.0 - blendW) + team.richXgEstimate * xgotRatio * blendW
        } else {
            team.richXgEstimate
        }

        // ── Blend goal-based xG with rich-stats xG (when stats available) ──
          // Rich xG carries 40% weight when we have 5+ stat matches, 25% for 3-4.
          // IMPORTANT: apply locationFactor to richXgEstimate here, since computeRichXg()
          // no longer applies it internally (Fix 2). This ensures the venue effect is applied
          // uniformly across both the goal-based and rich-stats components of the blend.
          val richXgWithLocation = if (refinedRichXg > 0.0) refinedRichXg * locationFactor else 0.0
          val xgBeforeDefAdj = when {
              team.statsMatchCount >= 5 && richXgWithLocation > 0.0 ->
                  blendedGoalXg * 0.60 + richXgWithLocation * 0.40
              team.statsMatchCount >= 3 && richXgWithLocation > 0.0 ->
                  blendedGoalXg * 0.75 + richXgWithLocation * 0.25
              else -> blendedGoalXg
          }
  
        // ── Opponent goalkeeper quality adjustment ────────────────────────────
        // A strong opponent GK (gkQualityFactor < 1.0) reduces our expected goals.
        // The factor is derived from goalsPreventedGk: range [0.75, 1.25].
        // Apply only when opponent has enough stat matches (≥3).
        val oppGkAdj = if (opponent.statsMatchCount >= 3 && opponent.gkQualityFactor != 1.0)
            opponent.gkQualityFactor else 1.0

        // ── Opponent defensive solidity adjustment ────────────────────────────
        // defSolidityFactor > 1.0 = opponent makes many interceptions+clearances → harder to score.
        // 0.0 means no data → use neutral 1.0.
        val oppDefAdj = if (opponent.statsMatchCount >= 3 && opponent.defSolidityFactor > 0.0) {
            // Map [0.60, 1.60] → xG multiplier: more defensive actions = harder to score.
            // Effect capped at ±15% to avoid extreme swings.
            (1.0 / opponent.defSolidityFactor.coerceIn(0.70, 1.50)).coerceIn(0.85, 1.15)
        } else 1.0

        // ── Team's own error-risk premium (this team's defensive errors) ──────
        // errorsLeadingToGoal: when used in the caller for the opponent's calculation,
        // this adds xGA risk. Here, team = attacker, so we look at own error rate
        // as a signal for how often we lose the ball dangerously (indirect attack proxy).
        // We apply it as a mild attacking uplift for teams that force errors.
        // Weight: ≤5% — conservative because errors are a noisy metric.
        val ownErrUplift = if (team.statsMatchCount >= 3 && team.avgErrorsLeadingToGoal > 0.0) {
            // If team concedes errors it doesn't directly boost attack.
            // But opponent errors in front of them do — and we use opponent.avgErrorsLeadingToGoal
            // for the opponent's xG calculation (called symmetrically from AnalysisViewModel).
            // Here we apply defensive error vulnerability as direct xGA signal:
            // The more errors the opponent makes in their own half, the more we score.
            // We receive opponent features as `opponent`, so read opponent.avgErrorsLeadingToGoal.
            val oppErrRate = opponent.avgErrorsLeadingToGoal
            if (opponent.statsMatchCount >= 3 && oppErrRate > 0.0) {
                // Typical range: 0.0–0.6 errors/game leading to goals.
                // Add up to +8% xG uplift when opponent is very error-prone.
                (1.0 + (oppErrRate / 0.5).coerceIn(0.0, 0.08))
            } else 1.0
        } else {
            // Also apply when team stats unavailable but opponent errors are known
            val oppErrRate = opponent.avgErrorsLeadingToGoal
            if (opponent.statsMatchCount >= 3 && oppErrRate > 0.0)
                (1.0 + (oppErrRate / 0.5).coerceIn(0.0, 0.08))
            else 1.0
        }

        val finalXg = (xgBeforeDefAdj * oppGkAdj * oppDefAdj * ownErrUplift).coerceIn(0.20, 4.8)

        // ── Glicko-2 xG blend ────────────────────────────────────────────────
        // Glicko API provides a pre-computed xG calibrated across the full database.
        // We blend it using a dynamic weight derived from glickoBlendWeight():
        //   well-established rating (RD < 100)  → up to 21% weight on Glicko xG
        //   uncertain rating (RD ≥ 250)          → only 2% weight
        //   high volatility (> 0.08) team        → weight reduced by 3.5%
        //
        // Weight is capped at 0.60 × rdWeight so that relStrength (which already
        // incorporates Glicko ratings) doesn't compound with a heavy xG blend
        // and over-represent Glicko in the final xG.
        val glickoXg  = if (isHome) glicko?.homeXg  else glicko?.awayXg
        val glickoRd  = if (isHome) glicko?.homeRd  else glicko?.awayRd
        val glickoVol = if (isHome) glicko?.homeVolatility else glicko?.awayVolatility
        val finalXgWithGlicko = if (glickoXg != null && glickoRd != null) {
            val xgBlendW = glickoBlendWeight(glickoRd, glickoVol) * 0.60
            finalXg * (1.0 - xgBlendW) + glickoXg * xgBlendW
        } else {
            finalXg
        }

        return (finalXgWithGlicko * team.motivationFactor).coerceIn(0.20, 4.8)
    }

    // ─── Poisson + Dixon-Coles ────────────────────────────────────────────

    private data class PoissonResult(
        val homeWin: Double, val draw: Double, val awayWin: Double,
        val over25: Double, val under25: Double, val btts: Double,
        val mostLikelyScore: String, val mostLikelyProb: Double,
        val overLines: Map<Double, Double> = emptyMap()
    )

    private fun poissonProbabilities(
        homeXg: Double, awayXg: Double,
        h2h: H2HData, league: LeagueParams
    ): PoissonResult {
        val rho = league.dcRho

        var homeWin = 0.0; var draw = 0.0; var awayWin = 0.0
        var over25  = 0.0; var btts = 0.0
        var bestProb = 0.0; var bestScore = "1-1"
        var totalP  = 0.0

        for (h in 0..7) {
            for (a in 0..7) {
                var p = poissonPmf(homeXg, h) * poissonPmf(awayXg, a)
                val tau = when {
                    h == 0 && a == 0 -> 1.0 - homeXg * awayXg * rho
                    h == 1 && a == 0 -> 1.0 + awayXg * rho
                    h == 0 && a == 1 -> 1.0 + homeXg * rho
                    h == 1 && a == 1 -> 1.0 - rho
                    else             -> 1.0
                }
                p *= tau.coerceAtLeast(0.0)
                totalP += p
                when {
                    h > a  -> homeWin += p
                    h == a -> draw    += p
                    else   -> awayWin += p
                }
                if (h + a > 2) over25 += p
                if (h > 0 && a > 0) btts += p
                if (p > bestProb) { bestProb = p; bestScore = "$h-$a" }
            }
        }

        val total = homeWin + draw + awayWin
        if (total > 0) { homeWin /= total; draw /= total; awayWin /= total }
        val over25Norm = if (totalP > 0) over25 / totalP else over25
        val bttsNorm   = if (totalP > 0) btts   / totalP else btts

        if (h2h.total >= 3) {
            val w = 0.15
            homeWin = homeWin * (1 - w) + (h2h.homeWins.toDouble() / h2h.total) * w
            draw    = draw    * (1 - w) + (h2h.draws.toDouble()    / h2h.total) * w
            awayWin = awayWin * (1 - w) + (h2h.awayWins.toDouble() / h2h.total) * w
            val t2 = homeWin + draw + awayWin
            homeWin /= t2; draw /= t2; awayWin /= t2
        }

        return PoissonResult(homeWin, draw, awayWin, over25Norm, 1.0 - over25Norm, bttsNorm, bestScore, bestProb)
    }

    private fun liveAdjustedProbabilities(
        historicalHomeXg: Double,
        historicalAwayXg: Double,
        elapsed: Int,
        currentHomeScore: Int,
        currentAwayScore: Int,
        liveMatchHomeXg: Double?,
        liveMatchAwayXg: Double?,
        h2h: H2HData,
        league: LeagueParams,
        liveStats: ApiStatistics? = null
    ): PoissonResult {
        val minutesPlayed = elapsed.coerceIn(1, 120)
        val totalExpected = if (minutesPlayed >= 90) 95 else 90
        val minutesLeft   = (totalExpected - minutesPlayed).coerceAtLeast(0)
        val remainFrac    = minutesLeft / 90.0

        val histHomeRem = historicalHomeXg * remainFrac
        val histAwayRem = historicalAwayXg * remainFrac

        val liveHomeRem = if (liveMatchHomeXg != null && liveMatchHomeXg > 0)
            (liveMatchHomeXg / minutesPlayed) * minutesLeft else null
        val liveAwayRem = if (liveMatchAwayXg != null && liveMatchAwayXg > 0)
            (liveMatchAwayXg / minutesPlayed) * minutesLeft else null

        val sotHomeXg = if (liveMatchHomeXg == null || liveMatchHomeXg <= 0) {
            val sot = liveStats?.shotsOnGoalHome ?: 0
            if (sot > 0) ((sot.toDouble() / minutesPlayed) * minutesLeft * 0.11).coerceIn(0.0, 3.0) else null
        } else null
        val sotAwayXg = if (liveMatchAwayXg == null || liveMatchAwayXg <= 0) {
            val sot = liveStats?.shotsOnGoalAway ?: 0
            if (sot > 0) ((sot.toDouble() / minutesPlayed) * minutesLeft * 0.11).coerceIn(0.0, 3.0) else null
        } else null

        val bestLiveHome = liveHomeRem ?: sotHomeXg
        val bestLiveAway = liveAwayRem ?: sotAwayXg

        val liveWeight = (minutesPlayed / 90.0).coerceIn(0.0, 1.0)
        val histWeight = 1.0 - liveWeight
        var remHomeXg = if (bestLiveHome != null) bestLiveHome * liveWeight + histHomeRem * histWeight else histHomeRem
        var remAwayXg = if (bestLiveAway != null) bestLiveAway * liveWeight + histAwayRem * histWeight else histAwayRem

        if (liveStats != null) {
            val posHome = liveStats.ballPossessionHome ?: 50
            if (posHome in 1..99) {
                // Possession advantage: team with more than 50% possession gets xG boost.
                // 12% max effect (at 100% or 0% possession) — calibrated to empirical data.
                val posAdv = ((posHome - 50) / 100.0 * 0.12).coerceIn(-0.06, 0.06)
                remHomeXg = (remHomeXg * (1.0 + posAdv)).coerceAtLeast(0.0)
                remAwayXg = (remAwayXg * (1.0 - posAdv)).coerceAtLeast(0.0)
            }
            val cornerHome = liveStats.cornerKicksHome ?: 0
            val cornerAway = liveStats.cornerKicksAway ?: 0
            val cornerAdv  = ((cornerHome - cornerAway).toDouble() / 5.0 * 0.02).coerceIn(-0.10, 0.10)
            remHomeXg = (remHomeXg * (1.0 + cornerAdv)).coerceAtLeast(0.0)
            remAwayXg = (remAwayXg * (1.0 - cornerAdv)).coerceAtLeast(0.0)
            val redHome = liveStats.redCardsHome ?: 0
            val redAway = liveStats.redCardsAway ?: 0
            repeat(redHome) { remHomeXg *= 0.75 }
            repeat(redAway) { remAwayXg *= 0.75 }
            val yellowHome = liveStats.yellowCardsHome ?: 0
            val yellowAway = liveStats.yellowCardsAway ?: 0
            if (yellowHome >= 5) remHomeXg *= 0.95
            if (yellowAway >= 5) remAwayXg *= 0.95

            // New: dangerous attacks live signal
            val dangHome = liveStats.dangerousAttacksHome ?: 0
            val dangAway = liveStats.dangerousAttacksAway ?: 0
            if (dangHome + dangAway > 0) {
                val dangAdv = ((dangHome - dangAway).toDouble() / (dangHome + dangAway)) * 0.05
                remHomeXg = (remHomeXg * (1.0 + dangAdv)).coerceAtLeast(0.0)
                remAwayXg = (remAwayXg * (1.0 - dangAdv)).coerceAtLeast(0.0)
            }
        }

        remHomeXg = remHomeXg.coerceIn(0.0, 5.0)
        remAwayXg = remAwayXg.coerceIn(0.0, 5.0)

        val rho = league.dcRho
        var homeWin = 0.0; var draw = 0.0; var awayWin = 0.0
        var over25  = 0.0; var btts  = 0.0
        var bestProb = 0.0
        var bestScore = "$currentHomeScore-$currentAwayScore"
        var totalP = 0.0

        val currentTotal   = currentHomeScore + currentAwayScore
        val alreadyOver05  = currentTotal >= 1
        val alreadyOver15  = currentTotal >= 2
        val alreadyOver25  = currentTotal >= 3
        val alreadyOver35  = currentTotal >= 4
        val alreadyOver45  = currentTotal >= 5
        val alreadyOver55  = currentTotal >= 6
        val alreadyOver65  = currentTotal >= 7
        val alreadyOver75  = currentTotal >= 8
        val alreadyOver85  = currentTotal >= 9
        val alreadyOver95  = currentTotal >= 10
        val alreadyOver105 = currentTotal >= 11
        val alreadyBtts    = currentHomeScore > 0 && currentAwayScore > 0

        var over05 = 0.0; var over15 = 0.0; var over35 = 0.0; var over45 = 0.0
        var over55 = 0.0; var over65 = 0.0; var over75 = 0.0; var over85 = 0.0
        var over95 = 0.0; var over105 = 0.0

        for (addH in 0..12) {
            for (addA in 0..12) {
                var p = poissonPmf(remHomeXg, addH) * poissonPmf(remAwayXg, addA)
                val tau = when {
                    addH == 0 && addA == 0 -> 1.0 - remHomeXg * remAwayXg * rho
                    addH == 1 && addA == 0 -> 1.0 + remAwayXg * rho
                    addH == 0 && addA == 1 -> 1.0 + remHomeXg * rho
                    addH == 1 && addA == 1 -> 1.0 - rho
                    else                   -> 1.0
                }
                p *= tau.coerceAtLeast(0.0)
                totalP += p

                val finalHome  = currentHomeScore + addH
                val finalAway  = currentAwayScore + addA
                val finalTotal = finalHome + finalAway
                when {
                    finalHome > finalAway  -> homeWin += p
                    finalHome == finalAway -> draw    += p
                    else                   -> awayWin += p
                }
                if (alreadyOver05  || finalTotal > 0)  over05  += p
                if (alreadyOver15  || finalTotal > 1)  over15  += p
                if (alreadyOver25  || finalTotal > 2)  over25  += p
                if (alreadyOver35  || finalTotal > 3)  over35  += p
                if (alreadyOver45  || finalTotal > 4)  over45  += p
                if (alreadyOver55  || finalTotal > 5)  over55  += p
                if (alreadyOver65  || finalTotal > 6)  over65  += p
                if (alreadyOver75  || finalTotal > 7)  over75  += p
                if (alreadyOver85  || finalTotal > 8)  over85  += p
                if (alreadyOver95  || finalTotal > 9)  over95  += p
                if (alreadyOver105 || finalTotal > 10) over105 += p
                if (alreadyBtts    || (finalHome > 0 && finalAway > 0)) btts += p
                if (p > bestProb) { bestProb = p; bestScore = "$finalHome-$finalAway" }
            }
        }

        val total = homeWin + draw + awayWin
        if (total > 0) { homeWin /= total; draw /= total; awayWin /= total }

        val norm = { v: Double -> if (totalP > 0) v / totalP else v }
        val overLines = mapOf(
            0.5  to norm(over05),  1.5 to norm(over15),  2.5 to norm(over25),
            3.5  to norm(over35),  4.5 to norm(over45),  5.5 to norm(over55),
            6.5  to norm(over65),  7.5 to norm(over75),  8.5 to norm(over85),
            9.5  to norm(over95),  10.5 to norm(over105)
        )
        val over25Norm = norm(over25)
        val bttsNorm   = norm(btts)
        return PoissonResult(homeWin, draw, awayWin, over25Norm, 1.0 - over25Norm, bttsNorm, bestScore, bestProb, overLines)
    }

    private fun poissonPmf(lambda: Double, k: Int): Double {
        if (lambda <= 0) return if (k == 0) 1.0 else 0.0
        var logP = -lambda + k * ln(lambda)
        for (i in 1..k) logP -= ln(i.toDouble())
        return exp(logP)
    }

    // ─── Pinnacle blending ─────────────────────────────────────────────────

    private fun blendWithPinnacle(probs: PoissonResult, odds: MatchOdds): PoissonResult {
        val pinnacle = odds.prematch.firstOrNull { it.bookmakerId == 4 }
            ?: odds.prematch.firstOrNull { it.bookmakerId in PRIORITY_BOOKMAKER_IDS }
            ?: return probs

        val h = pinnacle.homeWin ?: return probs
        val d = pinnacle.draw    ?: return probs
        val a = pinnacle.awayWin ?: return probs
        if (h <= 1.0 || d <= 1.0 || a <= 1.0) return probs

        val rawH = 1.0 / h; val rawD = 1.0 / d; val rawA = 1.0 / a
        val margin = rawH + rawD + rawA
        if (margin <= 0.0) return probs

        val fairH = rawH / margin; val fairD = rawD / margin; val fairA = rawA / margin

        val w = 0.25
        var bH = probs.homeWin * (1 - w) + fairH * w
        var bD = probs.draw    * (1 - w) + fairD * w
        var bA = probs.awayWin * (1 - w) + fairA * w
        val bt = bH + bD + bA
        if (bt > 0) { bH /= bt; bD /= bt; bA /= bt }

        return probs.copy(homeWin = bH, draw = bD, awayWin = bA)
    }

    // ─── Glicko-2 win-probability blend ───────────────────────────────────

    /**
     * Blends Poisson/Dixon-Coles win probabilities with Glicko-2 pre-computed
     * probabilities (when the API provides them).
     *
     * Unlike the old fixed 20% weight, the blend weight now adapts to the
     * reliability of the Glicko-2 ratings:
     *   - Low RD (< 75)  → rating is very well calibrated → up to 35% weight
     *   - High RD (≥ 250) → rating barely established      → only 3% weight
     *   - High volatility (> 0.08) → unpredictable team   → −5% penalty
     *
     * Average RD/volatility across both teams is used to set the blend weight.
     *
     * Glicko [homeWinProbability] + [awayWinProbability] don't always sum to 1,
     * so draw is computed as the remainder and all three are re-normalised.
     */
    private fun blendWithGlicko(probs: PoissonResult, glicko: GlickoData?): PoissonResult {
        if (glicko == null || !glicko.available) return probs
        val gH = glicko.homeWinProbability ?: return probs
        val gA = glicko.awayWinProbability ?: return probs
        if (gH <= 0.0 || gA <= 0.0 || gH + gA >= 1.0) return probs

        val gD = (1.0 - gH - gA).coerceAtLeast(0.05)
        val gTotal = gH + gD + gA
        val fairH = gH / gTotal; val fairD = gD / gTotal; val fairA = gA / gTotal

        val avgRd  = (glicko.homeRd + glicko.awayRd) / 2.0
        val avgVol = listOfNotNull(glicko.homeVolatility, glicko.awayVolatility)
            .average().takeIf { !it.isNaN() }
        val w = glickoBlendWeight(avgRd, avgVol)

        var bH = probs.homeWin * (1 - w) + fairH * w
        var bD = probs.draw    * (1 - w) + fairD * w
        var bA = probs.awayWin * (1 - w) + fairA * w
        val bt = bH + bD + bA
        if (bt > 0) { bH /= bt; bD /= bt; bA /= bt }

        return probs.copy(homeWin = bH, draw = bD, awayWin = bA)
    }

    // ─── Betting tips ─────────────────────────────────────────────────────

    private fun buildBettingTips(
        probs: PoissonResult, home: TeamFeatures, away: TeamFeatures, h2h: H2HData
    ): List<BettingTip> {
        val tips = mutableListOf<BettingTip>()
        when {
            probs.homeWin >= 0.55 -> tips.add(BettingTip("Home Win", "High",
                "Strong home advantage & form", (probs.homeWin * 100).roundToInt()))
            probs.homeWin >= 0.45 -> tips.add(BettingTip("Home Win / Draw", "Medium",
                "Home team slight favourite", ((probs.homeWin + probs.draw) * 100).roundToInt()))
            probs.awayWin >= 0.50 -> tips.add(BettingTip("Away Win",
                if (probs.awayWin >= 0.55) "High" else "Medium",
                "Away team in better form", (probs.awayWin * 100).roundToInt()))
            else -> tips.add(BettingTip("Draw", "Low",
                "Closely matched teams", (probs.draw * 100).roundToInt()))
        }
        if (probs.over25 >= 0.60) {
            tips.add(BettingTip("Over 2.5 Goals",
                if (probs.over25 >= 0.70) "High" else "Medium",
                "Both teams avg %.1f combined".format(home.avgGoalsFor + away.avgGoalsFor),
                (probs.over25 * 100).roundToInt()))
        } else if (probs.over25 < 0.40) {
            tips.add(BettingTip("Under 2.5 Goals",
                if (probs.over25 < 0.30) "High" else "Medium",
                "Low-scoring form expected",
                (probs.under25 * 100).roundToInt()))
        }
        val bttsAlreadyAdded = probs.btts >= 0.55
        if (bttsAlreadyAdded) {
            tips.add(BettingTip("Both Teams To Score",
                if (probs.btts >= 0.65) "High" else "Medium",
                "Both teams score regularly", (probs.btts * 100).roundToInt()))
        }
        // Rich-stats tips: corners
        val totalCorners = home.avgCorners + away.avgCorners
        if (totalCorners >= 10.0) {
            tips.add(BettingTip("Over 9.5 Corners", "Medium",
                "Avg %.1f corners combined".format(totalCorners),
                (minOf((totalCorners / 12.0) * 100, 85.0)).toInt()))
        }
        // Big chances tip — add BTTS only if not already added, otherwise show chance volume tip
        val totalBigChances = home.avgBigChances + away.avgBigChances
        if (totalBigChances >= 4.0) {
            if (!bttsAlreadyAdded) {
                tips.add(BettingTip("Both Teams To Score", "Medium",
                    "%.1f big chances per game combined".format(totalBigChances),
                    (minOf((totalBigChances / 6.0) * 100, 80.0)).toInt()))
            } else {
                tips.add(BettingTip("High Chance Volume", "Medium",
                    "%.1f big chances per game combined — open game expected".format(totalBigChances),
                    (minOf((totalBigChances / 6.0) * 100, 80.0)).toInt()))
            }
        }
        tips.add(BettingTip("Correct Score: ${probs.mostLikelyScore}", "Low",
            "%.0f%% probability (Dixon-Coles model)".format(probs.mostLikelyProb * 100),
            (probs.mostLikelyProb * 100).roundToInt()))
        return tips
    }

    // ─── Key factors ──────────────────────────────────────────────────────

    private fun buildKeyFactors(
        home: TeamFeatures, away: TeamFeatures, h2h: H2HData,
        homeName: String, awayName: String
    ): List<String> {
        val factors = mutableListOf<String>()

        // ── Stats availability indicator (shown first so user knows data quality) ─
        val totalStats = home.statsMatchCount + away.statsMatchCount
        when {
            home.statsMatchCount >= 5 && away.statsMatchCount >= 5 ->
                factors.add("Stats enriched: ${home.statsMatchCount}/7 games (home) + ${away.statsMatchCount}/7 (away) — xG uses real shot data")
            home.statsMatchCount >= 3 || away.statsMatchCount >= 3 ->
                factors.add("Partial stats: ${home.statsMatchCount}/7 (home) + ${away.statsMatchCount}/7 (away) — limited xG enrichment")
            totalStats == 0 ->
                factors.add("No detailed stats — xG based on goals only (standard mode)")
        }
        if (home.strengthAdjustedWinRate >= 0.60)
            factors.add("$homeName excels vs quality opponents (adj. win rate %.0f%%)".format(home.strengthAdjustedWinRate * 100))
        if (away.strengthAdjustedWinRate >= 0.60)
            factors.add("$awayName excels vs quality opponents (adj. win rate %.0f%%)".format(away.strengthAdjustedWinRate * 100))
        if (home.motivationFactor >= 1.08) factors.add("$homeName highly motivated (title race / cup stakes)")
        if (home.motivationFactor <= 0.92) factors.add("$homeName low motivation — no stakes in this match")
        if (away.motivationFactor >= 1.08) factors.add("$awayName highly motivated (title race / cup stakes)")
        if (away.motivationFactor <= 0.92) factors.add("$awayName low motivation — no stakes in this match")
        if (home.homeMatches >= 3 && home.homeWinRate >= 0.65)
            factors.add("$homeName dominant at home (%.0f%% home win rate)".format(home.homeWinRate * 100))
        if (away.awayMatches >= 3 && away.awayWinRate >= 0.55)
            factors.add("$awayName strong on the road (%.0f%% away win rate)".format(away.awayWinRate * 100))
        if (home.winRate >= 0.55) factors.add("$homeName strong recent form (%.0f%% win rate)".format(home.winRate * 100))
        if (away.winRate >= 0.55) factors.add("$awayName strong recent form (%.0f%% win rate)".format(away.winRate * 100))
        if (home.pointsPerGame >= 2.0) factors.add("$homeName elite form — %.1f PPG".format(home.pointsPerGame))
        if (away.pointsPerGame >= 2.0) factors.add("$awayName elite form — %.1f PPG".format(away.pointsPerGame))
        if (home.pointsPerGame <= 0.8) factors.add("$homeName poor form — only %.1f PPG".format(home.pointsPerGame))
        if (away.pointsPerGame <= 0.8) factors.add("$awayName poor form — only %.1f PPG".format(away.pointsPerGame))
        if (home.trend >  0.05) factors.add("$homeName improving trend in last 5 games")
        if (away.trend >  0.05) factors.add("$awayName improving trend in last 5 games")
        if (home.trend < -0.05) factors.add("$homeName declining form recently")
        if (away.trend < -0.05) factors.add("$awayName declining form recently")
        if (h2h.total >= 3) {
            val dom = h2h.homeWins - h2h.awayWins
            if (dom >= 2)       factors.add("$homeName dominates H2H (${h2h.homeWins}W-${h2h.draws}D-${h2h.awayWins}L)")
            else if (dom <= -2) factors.add("$awayName dominates H2H (${h2h.awayWins}W-${h2h.draws}D-${h2h.homeWins}L)")
        }
        // ── Rich-stats key factors ──────────────────────────────────────────
        if (home.statsMatchCount >= 3) {
            if (home.avgBigChances >= 2.5)
                factors.add("$homeName creates many big chances (avg %.1f per game)".format(home.avgBigChances))
            if (home.avgBigChances <= 0.8)
                factors.add("$homeName rarely creates big chances — low attacking threat")
            if (home.avgDangerousAttacks >= 50)
                factors.add("$homeName high-pressure attack (%.0f dangerous attacks/game)".format(home.avgDangerousAttacks))
            if (home.avgTacklePct >= 70)
                factors.add("$homeName elite defensive duels (%.0f%% tackle success)".format(home.avgTacklePct))
            if (home.avgShotsInBox >= 8)
                factors.add("$homeName generates volume in the box (%.1f shots/game)".format(home.avgShotsInBox))
            if (home.avgXgApiFor >= 1.8)
                factors.add("$homeName exceeds league xG average (%.2f xG/game)".format(home.avgXgApiFor))
        }
        if (away.statsMatchCount >= 3) {
            if (away.avgBigChances >= 2.5)
                factors.add("$awayName creates many big chances (avg %.1f per game)".format(away.avgBigChances))
            if (away.avgBigChances <= 0.8)
                factors.add("$awayName rarely creates big chances — low attacking threat")
            if (away.avgTacklePct >= 70)
                factors.add("$awayName elite defensive duels (%.0f%% tackle success)".format(away.avgTacklePct))
            if (away.avgXgApiFor >= 1.8)
                factors.add("$awayName exceeds league xG average (%.2f xG/game)".format(away.avgXgApiFor))
        }
        return factors.take(10)
    }

    // ─── Confidence ───────────────────────────────────────────────────────

    private fun computeConfidence(
        home: TeamFeatures, away: TeamFeatures, h2h: H2HData,
        formSize: Int, pinnacleUsed: Boolean, glicko: GlickoData? = null
    ): Double {
        var score = 0.50
        if (formSize >= 5) score += 0.08
        if (formSize >= 10) score += 0.04
        if (h2h.total >= 5) score += 0.05
        if (home.totalMatches >= 7 && away.totalMatches >= 7) score += 0.05
        if (pinnacleUsed) score += 0.08
        if (home.statsMatchCount >= 5 && away.statsMatchCount >= 5) score += 0.10
        else if (home.statsMatchCount >= 3 && away.statsMatchCount >= 3) score += 0.05
        val probDiff = abs(home.winRate - away.winRate)
        if (probDiff >= 0.20) score += 0.05
        else if (probDiff <= 0.05) score -= 0.03
        // Glicko-2 boost: established ratings (low RD) add predictive value.
        // High volatility subtracts confidence — team is currently unpredictable.
        if (glicko != null && glicko.available) {
            val avgRd = (glicko.homeRd + glicko.awayRd) / 2.0
            when {
                avgRd < 75  -> score += 0.10  // extremely well-calibrated
                avgRd < 100 -> score += 0.08
                avgRd < 150 -> score += 0.06
                avgRd < 200 -> score += 0.03
                else        -> score += 0.01  // rating available but uncertain
            }
            val avgVol = listOfNotNull(glicko.homeVolatility, glicko.awayVolatility)
                .average().takeIf { !it.isNaN() } ?: 0.06
            when {
                avgVol > 0.12 -> score -= 0.06  // highly unpredictable teams
                avgVol > 0.09 -> score -= 0.03  // moderately unpredictable
                avgVol > 0.06 -> score -= 0.01  // slight unpredictability
            }
        }
        return score.coerceIn(0.0, 1.0)
    }
}
