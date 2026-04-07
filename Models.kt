package com.footballanalyzer.app.data.model

import com.google.gson.annotations.SerializedName

data class ApiCountry(val code: String? = null, val name: String? = null)
data class ApiTeam(val id: Int? = null, val name: String? = null, val flashId: String? = null, val country: ApiCountry? = null)
data class ApiLeague(val id: Int? = null, val name: String? = null, val country: ApiCountry? = null, val flashScoreId: String? = null)
data class ApiSeason(val uid: String? = null, val year: Int? = null, val league: ApiLeague? = null)

data class ApiGame(
    val id: Int? = null, val flashId: String? = null, val date: String? = null, val dateUtc: Long? = null,
    val status: Int? = null, val statusName: String? = null, val elapsed: Int? = null,
    val homeResult: Int? = null, val awayResult: Int? = null,
    val homeHTResult: Int? = null, val awayHTResult: Int? = null,
    val homeFTResult: Int? = null, val awayFTResult: Int? = null,
    val homeTeam: ApiTeam? = null, val awayTeam: ApiTeam? = null,
    val season: ApiSeason? = null, val roundName: String? = null, val periods: List<String>? = null
)

// ─── Full statistics model (~75 fields, home+away pairs) ──────────────────
data class ApiStatistics(
    // Shots
    val shotsOnGoalHome: Int? = null,        val shotsOnGoalAway: Int? = null,
    val shotsOffGoalHome: Int? = null,       val shotsOffGoalAway: Int? = null,
    val totalShotsHome: Int? = null,         val totalShotsAway: Int? = null,
    val blockedShotsHome: Int? = null,       val blockedShotsAway: Int? = null,
    val shotsInsideBoxHome: Int? = null,     val shotsInsideBoxAway: Int? = null,
    val shotsOutsideBoxHome: Int? = null,    val shotsOutsideBoxAway: Int? = null,
    val hitTheWoodworkHome: Int? = null,     val hitTheWoodworkAway: Int? = null,
    val bigChancesHome: Int? = null,         val bigChancesAway: Int? = null,
    val headedGoalsHome: Int? = null,        val headedGoalsAway: Int? = null,
    // xG metrics
    val expectedGoalsHome: Double? = null,   val expectedGoalsAway: Double? = null,
    val xgOnTargetHome: Double? = null,      val xgOnTargetAway: Double? = null,
    val xgotFacedHome: Double? = null,       val xgotFacedAway: Double? = null,
    val goalsPreventedHome: Double? = null,  val goalsPreventedAway: Double? = null,
    val expectedAssistsHome: Double? = null, val expectedAssistsAway: Double? = null,
    val calculatedXgHome: Double? = null,    val calculatedXgAway: Double? = null,
    // Possession & passes
    val ballPossessionHome: Int? = null,     val ballPossessionAway: Int? = null,
    val totalPassesHome: Int? = null,        val totalPassesAway: Int? = null,
    val passesAccurateHome: Int? = null,     val passesAccurateAway: Int? = null,
    val longPassesHome: Int? = null,         val longPassesAway: Int? = null,
    val passesInFinalThirdHome: Int? = null, val passesInFinalThirdAway: Int? = null,
    val accurateThroughPassesHome: Int? = null, val accurateThroughPassesAway: Int? = null,
    // Set pieces & fouls
    val cornerKicksHome: Int? = null,        val cornerKicksAway: Int? = null,
    val foulsHome: Int? = null,              val foulsAway: Int? = null,
    val freeKicksHome: Int? = null,          val freeKicksAway: Int? = null,
    val throwinsHome: Int? = null,           val throwinsAway: Int? = null,
    val offsidesHome: Int? = null,           val offsidesAway: Int? = null,
    // Cards
    val yellowCardsHome: Int? = null,        val yellowCardsAway: Int? = null,
    val redCardsHome: Int? = null,           val redCardsAway: Int? = null,
    // Attack
    val attacksHome: Int? = null,            val attacksAway: Int? = null,
    val dangerousAttacksHome: Int? = null,   val dangerousAttacksAway: Int? = null,
    val touchesInOppositionBoxHome: Int? = null, val touchesInOppositionBoxAway: Int? = null,
    val crossesHome: Int? = null,            val crossesAway: Int? = null,
    val crossesCompletedHome: Int? = null,   val crossesCompletedAway: Int? = null,
    // Defence
    val goalkeeperSavesHome: Int? = null,    val goalkeeperSavesAway: Int? = null,
    val totalTacklesHome: Int? = null,       val totalTacklesAway: Int? = null,
    val successTacklesHome: Int? = null,     val successTacklesAway: Int? = null,
    val duelsWonHome: Int? = null,           val duelsWonAway: Int? = null,
    val duelsTotalHome: Int? = null,         val duelsTotalAway: Int? = null,
    val clearancesHome: Int? = null,         val clearancesAway: Int? = null,
    val clearancesCompletedHome: Int? = null, val clearancesCompletedAway: Int? = null,
    val interceptionsHome: Int? = null,      val interceptionsAway: Int? = null,
    val errorsLeadingToGoalHome: Int? = null, val errorsLeadingToGoalAway: Int? = null,
    val errorsLeadingToShotHome: Int? = null, val errorsLeadingToShotAway: Int? = null
)

data class ApiGameDetail(val game: ApiGame? = null, val statistics: ApiStatistics? = null)
data class GameListResponse(val status: String? = null, val count: Int? = null, val data: List<ApiGame>? = null, val message: String? = null)
data class GameDetailResponse(val status: String? = null, val data: ApiGameDetail? = null, val message: String? = null)

// ─── Glicko-2 rating API models ────────────────────────────────────────────

data class GlickoMetrics(
    val homeRating: Double? = null, val homeRd: Double? = null,
    val awayRating: Double? = null, val awayRd: Double? = null,
    val homeXg: Double? = null,     val awayXg: Double? = null,
    val homeWinProbability: Double? = null, val awayWinProbability: Double? = null,
    val homeVolatility: Double? = null, val awayVolatility: Double? = null
)

data class GlickoApiData(val fixture: ApiGame? = null, val glicko: GlickoMetrics? = null)
data class GlickoResponse(
    val status: String? = null,
    val data: GlickoApiData? = null,
    val message: String? = null
)

/**
 * Processed Glicko-2 snapshot used in analysis calculations and UI.
 *
 * [available] is true only when the API returned valid rating data for the match.
 * When [homeRd] / [awayRd] < 150, the rating is well-established and Glicko xG
 * is reliable enough to blend into the final xG estimate.
 */
data class GlickoData(
    val homeRating: Double = 1500.0,
    val awayRating: Double = 1500.0,
    val homeRd: Double = 350.0,
    val awayRd: Double = 350.0,
    val homeXg: Double? = null,
    val awayXg: Double? = null,
    val homeWinProbability: Double? = null,
    val awayWinProbability: Double? = null,
    val homeVolatility: Double? = null,
    val awayVolatility: Double? = null,
    val ratingDiff: Double = 0.0,
    val available: Boolean = false
)

data class ApiVenue(
    val name: String? = null, val address: String? = null,
    val city: String? = null, val capacity: Int? = null
)
data class ApiCoach(val id: Int? = null, val name: String? = null)
data class ApiPlayer(val id: Int? = null, val name: String? = null)

data class ApiTeamInfo(
    val id: Int? = null, val name: String? = null, val flashId: String? = null,
    val logoUrl: String? = null, val code: String? = null, val founded: Int? = null,
    val country: ApiCountry? = null, val seasons: List<ApiSeason>? = null,
    val venue: ApiVenue? = null, val coach: ApiCoach? = null,
    val players: List<ApiPlayer>? = null
)

data class TeamInfoResponse(
    val status: String? = null,
    val data: ApiTeamInfo? = null,
    val message: String? = null,
    val count: Int? = null,
    val requestQuery: String? = null,
    val offset: Int? = null,
    @SerializedName("TotalCount") val totalCount: Int? = null,
    val traceId: String? = null
)

// ─── Rich per-match stats for a single team (extracted from ApiStatistics) ──
data class TeamRichStats(
    val matchId: Int,
    val isHome: Boolean,
    val pos: Double,
    val totalShots: Int,
    val shotsOnTarget: Int,
    val shotsInBox: Int,
    val shotsOutBox: Int,
    val corners: Int,
    val bigChances: Int,
    val dangerousAttacks: Int,
    val touchesInBox: Int,
    val crosses: Int,
    val interceptions: Int,
    val clearances: Int,
    val totalTackles: Int,
    val successTackles: Int,
    val duelsWon: Int,
    val duelsTotal: Int,
    val xgApi: Double?,
    val xgOnTarget: Double?,
    val xgotFaced: Double?,
    val goalsPreventedGk: Double?,
    val goalkeeperSaves: Int,
    val errorsLeadingToGoal: Int,
    val hasStats: Boolean,
    // ── Opponent's attacking stats for this match (used to compute xGA) ──
    val oppShotsOnTarget: Int = 0,
    val oppBigChances: Int = 0,
    val oppShotsInBox: Int = 0,
    val oppXgApi: Double? = null
) {
    val tacklePct: Double get() = if (totalTackles > 0) successTackles.toDouble() / totalTackles * 100.0 else 50.0
    val duelsPct: Double  get() = if (duelsTotal > 0) duelsWon.toDouble() / duelsTotal * 100.0 else 50.0
}

fun ApiStatistics.extractFor(isHome: Boolean, matchId: Int): TeamRichStats {
    val s = this
    val totalTackles   = (if (isHome) s.totalTacklesHome   else s.totalTacklesAway)   ?: 0
    val successTackles = (if (isHome) s.successTacklesHome else s.successTacklesAway) ?: 0
    val duelsWon       = (if (isHome) s.duelsWonHome       else s.duelsWonAway)       ?: 0
    val duelsTotal     = (if (isHome) s.duelsTotalHome      else s.duelsTotalAway)     ?: 0
    val totalShots     = (if (isHome) s.totalShotsHome     else s.totalShotsAway)     ?: 0
    val shotsOnTarget  = (if (isHome) s.shotsOnGoalHome    else s.shotsOnGoalAway)    ?: 0
    val shotsInBox     = (if (isHome) s.shotsInsideBoxHome else s.shotsInsideBoxAway) ?: 0
    val shotsOutBox    = (if (isHome) s.shotsOutsideBoxHome else s.shotsOutsideBoxAway) ?: 0
    val possession     = (if (isHome) s.ballPossessionHome  else s.ballPossessionAway)  ?: 0
    val corners        = (if (isHome) s.cornerKicksHome    else s.cornerKicksAway)    ?: 0
    val bigChances     = (if (isHome) s.bigChancesHome     else s.bigChancesAway)     ?: 0
    val dangAtk        = (if (isHome) s.dangerousAttacksHome else s.dangerousAttacksAway) ?: 0
    val touches        = (if (isHome) s.touchesInOppositionBoxHome else s.touchesInOppositionBoxAway) ?: 0
    val crosses        = (if (isHome) s.crossesHome        else s.crossesAway)        ?: 0
    val intercept      = (if (isHome) s.interceptionsHome  else s.interceptionsAway)  ?: 0
    val clearances     = (if (isHome) s.clearancesHome     else s.clearancesAway)     ?: 0
    val gkSaves        = (if (isHome) s.goalkeeperSavesHome else s.goalkeeperSavesAway) ?: 0
    val errGoal        = (if (isHome) s.errorsLeadingToGoalHome else s.errorsLeadingToGoalAway) ?: 0
    val xgApi          = if (isHome) s.expectedGoalsHome   else s.expectedGoalsAway
    val xgOnTarget     = if (isHome) s.xgOnTargetHome      else s.xgOnTargetAway
    val xgotFaced      = if (isHome) s.xgotFacedHome       else s.xgotFacedAway
    val goalsPrevented = if (isHome) s.goalsPreventedHome  else s.goalsPreventedAway

    // Opponent's attacking stats (flipped side) — used to compute xGA
    val oppSoT = (if (isHome) s.shotsOnGoalAway    else s.shotsOnGoalHome)    ?: 0
    val oppBig = (if (isHome) s.bigChancesAway     else s.bigChancesHome)     ?: 0
    val oppBox = (if (isHome) s.shotsInsideBoxAway else s.shotsInsideBoxHome) ?: 0
    val oppXg  = if (isHome) s.expectedGoalsAway   else s.expectedGoalsHome

    // Consider stats "present" if at least shots data is non-zero.
    // NOTE: bigChances is Int (not Int?) due to ?: 0 above, so bigChances != null is always true.
    // Correct check uses numeric comparison only.
    val hasStats = totalShots > 0 || shotsOnTarget > 0

    return TeamRichStats(
        matchId = matchId,
        isHome = isHome,
        pos = if (possession in 1..99) possession.toDouble() else 50.0,
        totalShots = totalShots,
        shotsOnTarget = shotsOnTarget,
        shotsInBox = shotsInBox,
        shotsOutBox = shotsOutBox,
        corners = corners,
        bigChances = bigChances,
        dangerousAttacks = dangAtk,
        touchesInBox = touches,
        crosses = crosses,
        interceptions = intercept,
        clearances = clearances,
        totalTackles = totalTackles,
        successTackles = successTackles,
        duelsWon = duelsWon,
        duelsTotal = duelsTotal,
        xgApi = xgApi,
        xgOnTarget = xgOnTarget,
        xgotFaced = xgotFaced,
        goalsPreventedGk = goalsPrevented,
        goalkeeperSaves = gkSaves,
        errorsLeadingToGoal = errGoal,
        hasStats = hasStats,
        oppShotsOnTarget = oppSoT,
        oppBigChances = oppBig,
        oppShotsInBox = oppBox,
        oppXgApi = oppXg
    )
}

// ─── Odds API models ───────────────────────────────────────────────────────

/** Single bookmaker entry from /Odds/bookmakers */
data class ApiBookmaker(
    val id: Int? = null,
    val bookmakerName: String? = null
)

data class BookmakersResponse(
    val status: String? = null,
    val count: Int? = null,
    val data: List<ApiBookmaker>? = null
)

/** A single price (outcome) inside a market */
data class ApiOddsPrice(
    val name: String? = null,
    val value: Double? = null,
    val openingValue: Double? = null
)

/** One market with its outcomes/prices */
data class ApiSaBet(
    val marketId: Int? = null,
    val marketName: String? = null,
    val odds: List<ApiOddsPrice>? = null
)

/** Prematch odds for one bookmaker */
data class ApiSaBookmakerOdds(
    val bookmakerId: Int? = null,
    val bookmakerName: String? = null,
    val odds: List<ApiSaBet>? = null
)

data class OddsResponse(
    val status: String? = null,
    val count: Int? = null,
    val data: List<ApiSaBookmakerOdds>? = null,
    val message: String? = null
)

/** Live odds structure from /Odds/live/{gameId} */
data class ApiSaLiveOdds(
    val elapsed: String? = null,
    val stopped: Boolean? = null,
    val finished: Boolean? = null,
    val lastUpdate: String? = null,
    val gameStatus: Int? = null,
    val odds: List<ApiSaBet>? = null
)

data class LiveOddsResponse(
    val status: String? = null,
    val count: Int? = null,
    val data: ApiSaLiveOdds? = null,
    val message: String? = null
)

/** Markets response — data is a map of id (String) → market name */
data class MarketsResponse(
    val status: String? = null,
    val count: Int? = null,
    val data: Map<String, String>? = null
)

data class LiveMarketsResponse(
    val status: String? = null,
    val count: Int? = null,
    val data: Map<String, String>? = null
)

// ─── App-level odds models ─────────────────────────────────────────────────

/** Condensed 1x2 odds from a bookmaker — what the UI actually shows */
data class BookmakerOdds(
    val bookmakerId: Int = 0,
    val bookmakerName: String = "",
    val homeWin: Double? = null,
    val draw: Double? = null,
    val awayWin: Double? = null,
    val over25: Double? = null,
    val under25: Double? = null,
    val bttsYes: Double? = null,
    val bttsNo: Double? = null
)

/** All odds data available for a match (prematch + optionally live) */
data class MatchOdds(
    val prematch: List<BookmakerOdds> = emptyList(),
    val live: BookmakerOdds? = null,
    val liveElapsed: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

// ─── App domain models ─────────────────────────────────────────────────────

data class Match(
    val id: Int = 0, val flashId: String? = null,
    val homeTeamId: Int = 0, val homeTeamName: String = "Home",
    val awayTeamId: Int = 0, val awayTeamName: String = "Away",
    val homeResult: Int? = null, val awayResult: Int? = null,
    val homeHTResult: Int? = null, val awayHTResult: Int? = null,
    val status: Int = 0, val statusName: String = "",
    val elapsed: Int? = null, val date: String? = null,
    val leagueId: Int? = null, val leagueName: String = "Unknown",
    val seasonUid: String? = null, val roundName: String? = null
) {
    fun getMatchId(): String = id.toString()
    fun isLive() = status in setOf(3, 4, 5, 6, 7, 11, 19)
    fun isFinished() = status in setOf(8, 9, 10, 17, 18)
    fun isUpcoming() = status == 2 || status == 0
    fun getElapsedDisplay(): String = when {
        isLive() && elapsed != null -> "${elapsed}'"
        isLive() -> statusName
        isFinished() -> "FT"
        else -> ""
    }
}

fun ApiGame.toMatch(): Match = Match(
    id = id ?: 0, flashId = flashId,
    homeTeamId = homeTeam?.id ?: 0, homeTeamName = homeTeam?.name ?: "Home",
    awayTeamId = awayTeam?.id ?: 0, awayTeamName = awayTeam?.name ?: "Away",
    homeResult = homeResult ?: homeFTResult, awayResult = awayResult ?: awayFTResult,
    homeHTResult = homeHTResult, awayHTResult = awayHTResult,
    status = status ?: 0, statusName = statusName ?: "",
    elapsed = elapsed, date = date,
    leagueId = season?.league?.id, leagueName = season?.league?.name ?: "Unknown",
    seasonUid = season?.uid, roundName = roundName
)

data class TeamFormStat(
    val matchId: Int = 0, val date: String? = null, val isHome: Boolean = true,
    val opponent: String = "", val goalsFor: Int = 0, val goalsAgainst: Int = 0,
    val result: String = "", val possession: Int = 50,
    val shots: Int = 0, val shotsOnTarget: Int = 0, val competition: String? = null,
    val opponentStrength: Double = 1.0,
    val htGoalsFor: Int = 0,
    val htGoalsAgainst: Int = 0,
    // Rich stats — null when API didn't return stats for this game
    val richStats: TeamRichStats? = null
)

data class TeamFeatures(
    val pointsPerGame: Double = 0.0, val winRate: Double = 0.0,
    val drawRate: Double = 0.0, val lossRate: Double = 0.0,
    val avgGoalsFor: Double = 0.0, val avgGoalsAgainst: Double = 0.0,
    val xgForAvg: Double = 0.0, val xgAgainstAvg: Double = 0.0,
    val avgPossession: Double = 50.0, val avgShots: Double = 0.0,
    val avgShotsOnTarget: Double = 0.0, val recentPoints: Int = 0,
    val trend: Double = 0.0, val homeAdvantage: Double = 1.0,
    val totalMatches: Int = 0, val formString: String = "",
    val strengthAdjustedWinRate: Double = 0.0,
    val homeWinRate: Double = 0.0,
    val homeAvgGoalsFor: Double = 0.0,
    val homeAvgGoalsAgainst: Double = 0.0,
    val homeMatches: Int = 0,
    val awayWinRate: Double = 0.0,
    val awayAvgGoalsFor: Double = 0.0,
    val awayAvgGoalsAgainst: Double = 0.0,
    val awayMatches: Int = 0,
    val motivationFactor: Double = 1.0,
    val firstHalfAvgGoalsFor: Double = 0.0,
    val firstHalfAvgGoalsAgainst: Double = 0.0,
    val secondHalfAvgGoalsFor: Double = 0.0,
    val secondHalfAvgGoalsAgainst: Double = 0.0,
    val htWinRate: Double = 0.0,
    val comeFromBehindRate: Double = 0.0,
    val holdLeadRate: Double = 0.0,
    // ── Rich stats fields (populated when API returns detailed statistics) ──
    val avgBigChances: Double = 0.0,
    val avgDangerousAttacks: Double = 0.0,
    val avgShotsInBox: Double = 0.0,
    val avgTotalShots: Double = 0.0,
    val avgCorners: Double = 0.0,
    val avgTacklePct: Double = 50.0,
    val avgDuelsPct: Double = 50.0,
    val avgXgApiFor: Double = 0.0,
    val statsMatchCount: Int = 0,
    /** xG computed from rich match statistics (XG-algorithm). 0.0 = no stats available. */
    val richXgEstimate: Double = 0.0,
    /** xG conceded (against) computed from opponent shot data. 0.0 = no stats available. */
    val richXgAgainstEstimate: Double = 0.0,
    /**
     * Average goals prevented by goalkeeper above expected (goalsPreventedGk).
     * Positive = goalkeeper saves more than expected → reduces effective xGA.
     * 0.0 when data unavailable.
     */
    val avgGoalsPrevented: Double = 0.0,
    /**
     * Average xG on target (xgOnTarget) per game — quality of shots, not just volume.
     * Used to refine the attacking xG estimate.
     * 0.0 when data unavailable.
     */
    val avgXgOnTarget: Double = 0.0,
    /**
     * Average errors leading to goal per game. Higher → more vulnerable defense.
     * Used to add an error-risk premium to the opponent's xGA.
     * 0.0 when data unavailable.
     */
    val avgErrorsLeadingToGoal: Double = 0.0,
    /**
     * Defensive solidity factor derived from interceptions + clearances per game.
     * Normalised around 1.0: >1.0 = above-average defence activity.
     * 0.0 when data unavailable (treated as neutral 1.0 in calculations).
     */
    val defSolidityFactor: Double = 0.0,
    /**
     * Goalkeeper quality factor: adjustment multiplier derived from goalsPreventedGk.
     * Range: [0.75, 1.25]. Values < 1.0 = strong GK (saves more than expected → opponents score less).
     * Values > 1.0 = weak GK (concedes more than expected → opponents score more).
     * 1.0 when data unavailable (neutral).
     */
    val gkQualityFactor: Double = 1.0,
    /**
     * Normalised attack strength relative to league average.
     * attackStrength = effectiveXgFor / (leagueAvgGoals / 2). Range [0.25, 3.0].
     * 1.0 = league average. Used by PoissonEngine for accurate lambda calculation.
     * 0.0 when not yet computed (PoissonEngine falls back to xG estimates).
     */
    val attackStrength: Double = 1.0,
    /**
     * Normalised defense strength relative to league average.
     * defenseStrength = (leagueAvgGoals / 2) / avgGoalsAgainst. Range [0.25, 3.0].
     * 1.0 = league average. Higher = fewer goals conceded (stronger defense).
     * 0.0 when not yet computed (PoissonEngine falls back to xG estimates).
     */
    val defenseStrength: Double = 1.0
)

data class H2HMatch(val date: String?, val homeTeamName: String, val awayTeamName: String, val homeResult: Int?, val awayResult: Int?)

data class H2HData(
    val homeWins: Int = 0, val draws: Int = 0, val awayWins: Int = 0,
    val total: Int = 0, val avgGoals: Double = 2.5, val matches: List<H2HMatch> = emptyList()
)

data class BettingTip(val market: String = "", val confidence: String = "", val reason: String = "", val passRate: Int = 0)

data class GoalLinePrediction(
    val line: Double = 2.5,
    val overProb: Double = 0.0,
    val underProb: Double = 0.0,
    val alreadyGuaranteed: Boolean = false
)

data class MLPrediction(
    val homeWinProb: Double = 0.0,
    val drawProb: Double = 0.0,
    val awayWinProb: Double = 0.0,
    val confidence: Double = 0.0,
    val modelUsed: String = "",
    val available: Boolean = false,
    val isDefaultWeights: Boolean = false,
    val trainingStats: TrainingStats? = null,
    val over25Prob: Double = 0.0,
    val bttsProb: Double = 0.0,
    val mlMostLikelyScore: String = "",
    val goalLines: List<GoalLinePrediction> = emptyList()
)

data class TrainingStats(
    val totalMatchesTrained: Int = 0, val accuracy7day: Double = 0.0,
    val accuracy30day: Double = 0.0, val pendingPredictions: Int = 0, val modelVersion: Int = 1
)

data class Analysis(
    val homeXg: Double = 0.0, val awayXg: Double = 0.0,
    val homeWinPct: Double = 0.0, val drawPct: Double = 0.0, val awayWinPct: Double = 0.0,
    val mostLikelyScore: String = "1-1", val mostLikelyProb: Double = 0.0,
    val over25Pct: Double = 0.0, val under25Pct: Double = 0.0,
    val bttsPct: Double = 0.0, val expectedTotal: Double = 0.0,
    val homeFeatures: TeamFeatures = TeamFeatures(), val awayFeatures: TeamFeatures = TeamFeatures(),
    val h2h: H2HData = H2HData(), val bettingTips: List<BettingTip> = emptyList(),
    val keyFactors: List<String> = emptyList(), val confidenceScore: Double = 0.0,
    val mlPrediction: MLPrediction? = null,
    val homeFormMatches: List<TeamFormStat> = emptyList(),
    val awayFormMatches: List<TeamFormStat> = emptyList(),
    val leagueCalibration: String = "Default",
    val liveGoalLines: List<GoalLinePrediction> = emptyList(),
    val liveApiHomeXg: Double? = null,
    val liveApiAwayXg: Double? = null,
    /** Bookmaker odds (prematch and live) */
    val matchOdds: MatchOdds = MatchOdds(),
    /** Glicko-2 rating data — null when API did not return analytics for this match */
    val glickoData: GlickoData? = null
)

data class AnalysisResponse(val match: Match = Match(), val analysis: Analysis = Analysis())

data class HistoryEntry(
    val id: String, val matchId: String, val homeTeam: String, val awayTeam: String,
    val homeResult: Int? = null, val awayResult: Int? = null,
    val statusName: String? = null, val league: String? = null,
    val homeWinPct: Double, val drawPct: Double, val awayWinPct: Double,
    val confidenceScore: Double, val mostLikelyScore: String,
    val viewedAt: String, val mlModelVersion: Int? = null,
    val homeAvgGoalsFor: Double = 0.0,
    val homeAvgGoalsAgainst: Double = 0.0,
    val awayAvgGoalsFor: Double = 0.0,
    val awayAvgGoalsAgainst: Double = 0.0,
    val homePPG: Double = 0.0,
    val awayPPG: Double = 0.0,
    val homeTrend: Double = 0.5,
    val awayTrend: Double = 0.5,
    val homeXgFor: Double = 0.0,
    val awayXgFor: Double = 0.0,
    val h2hHomeWinRate: Double = 0.35,
    val h2hAvgGoals: Double = 2.5,
    val homeStrengthAdjWinRate: Double = 0.5,
    val awayStrengthAdjWinRate: Double = 0.5,
    val homeRecentPoints: Double = 0.0,
    val awayRecentPoints: Double = 0.0,
    val homeGoalDiff: Double = 0.0,
    val awayGoalDiff: Double = 0.0,
    val formDiff: Double = 0.0,
    val homeMotivation: Double = 1.0,
    val awayMotivation: Double = 1.0,
    val matchTimestamp: Long = 0L,
    /** Prematch 1X2 bookmaker odds — used for ML W/D/L and score prediction.
     *  0.0 means not available (old entries or no odds data). */
    val homeOdds: Double = 0.0,
    val drawOdds: Double = 0.0,
    val awayOdds: Double = 0.0,
    /** Over 2.5 bookmaker odds — used to improve Over/Under ML prediction. 0.0 = not available. */
    val over25Odds: Double = 0.0,
    /** Under 2.5 bookmaker odds — paired with over25Odds for margin-correct normalization. 0.0 = not available. */
    val under25Odds: Double = 0.0,
    /** BTTS Yes bookmaker odds — used to improve BTTS ML prediction. 0.0 = not available. */
    val bttsYesOdds: Double = 0.0,
    /** BTTS No bookmaker odds — paired with bttsYesOdds for margin-correct normalization. 0.0 = not available. */
    val bttsNoOdds: Double = 0.0,

    // ─── Actual team win rates (for ML training alignment) ─────────────────────────────────
    // These store the pre-match win rates as used in enrichWithMlPrediction() (home.winRate,
    // home.homeWinRate, away.awayWinRate) so that training and prediction use the same feature
    // scale. Previously homeWinPct/100 (Poisson probability) was used in training, causing drift.
    val homeActualWinRate: Double = 0.0,
    val awayActualWinRate: Double = 0.0,
    val homeHomeActualWinRate: Double = 0.0,
    val awayAwayActualWinRate: Double = 0.0,

    // ─── Glicko-2 data (for ML training and feature vector) ─────────────────────────────────
    val glickoRatingDiff: Double = 0.0,
    val homeGlickoRating: Double = 1500.0,
    val awayGlickoRating: Double = 1500.0,
    val homeGlickoRd: Double = 350.0,
    val awayGlickoRd: Double = 350.0,

    // ─── Rich stats: goalkeeper quality, defensive solidity, error rate ───────────────────
    /** GK quality factor for home/away team (from goalsPreventedGk). Range [0.75,1.25], neutral=1.0. */
    val homeGkQuality: Double = 1.0,
    val awayGkQuality: Double = 1.0,
    /** Defensive solidity factor (interceptions+clearances normalised to 1.0). 0.0 = no data. */
    val homeDefStrength: Double = 1.0,
    val awayDefStrength: Double = 1.0,
    /** Avg errors leading to goal per game. Higher = more vulnerable defence. 0.0 = no data. */
    val homeErrRate: Double = 0.0,
    val awayErrRate: Double = 0.0,

    // ─── Snapshot of ML (Neural Net) predictions at the moment of first pre-match analysis ───
    // These values are written ONCE when the match is first analyzed (status = upcoming/not started).
    // They MUST NOT be overwritten after the match ends or after neural network retraining.
    // When displaying a finished match from history, always use these saved values
    // instead of asking the neural network to re-predict (which would reflect post-match learning).

    /** ML probability for home win — saved at pre-match analysis time. 0.0 = not yet saved. */
    val savedMlHomeWinProb: Double = 0.0,

    /** ML probability for draw — saved at pre-match analysis time. 0.0 = not yet saved. */
    val savedMlDrawProb: Double = 0.0,

    /** ML probability for away win — saved at pre-match analysis time. 0.0 = not yet saved. */
    val savedMlAwayWinProb: Double = 0.0,

    /** ML probability for Over 2.5 goals — saved at pre-match analysis time. 0.0 = not yet saved. */
    val savedMlOver25Prob: Double = 0.0,

    /** ML probability for BTTS Yes — saved at pre-match analysis time. 0.0 = not yet saved. */
    val savedMlBttsProb: Double = 0.0,

    /** ML most likely score (e.g. "2-1") — saved at pre-match analysis time. Empty = not yet saved. */
    val savedMlMostLikelyScore: String = "",

    /**
     * True when ML predictions have been captured for this entry before the match started.
     * Use this flag in the UI/ViewModel:
     *   - if (mlPredictionSaved) → display savedMl* fields in the Neural Net section
     *   - if (!mlPredictionSaved) → run live ML prediction as usual (match not yet analyzed pre-match)
     *
     * HOW TO SET THIS FLAG:
     * In HistoryManager / ViewModel, when saving a HistoryEntry for an upcoming or live match,
     * populate all savedMl* fields from analysis.mlPrediction and set mlPredictionSaved = true.
     * Never set it to true for entries where the match was already finished at analysis time.
     */
    val mlPredictionSaved: Boolean = false,

    // ─── Pre-match Poisson / Glicko analysis snapshot ───────────────────────────────────────
    // Locked once for an upcoming match. Displayed unchanged when the match is re-opened after
    // it finishes, so the "pre-match analysis" shown in history never changes retroactively.
    // Prevents post-match form recalculation from altering displayed predictions.
    val savedHomeXg: Double = 0.0,
    val savedAwayXg: Double = 0.0,
    val savedPoissonHomeWinPct: Double = 0.0,
    val savedPoissonDrawPct: Double = 0.0,
    val savedPoissonAwayWinPct: Double = 0.0,
    val savedPoissonMostLikelyScore: String = "",
    val savedOver25Pct: Double = 0.0,
    val savedBttsPct: Double = 0.0,
    val savedExpectedTotal: Double = 0.0,
    val savedConfidenceScore: Double = 0.0,
    /** True when the Poisson snapshot has been captured for this entry (upcoming / pre-match). */
    val poissonSnapshotSaved: Boolean = false
)

data class MLTrainEntry(
    val matchId: String, val homeTeam: String, val awayTeam: String,
    val homeResult: Int, val awayResult: Int,
    val homeWinPct: Double, val drawPct: Double, val awayWinPct: Double
)

// ─── Extension: build a HistoryEntry with locked ML predictions ────────────

/**
 * Returns a copy of this HistoryEntry with ML prediction fields populated from
 * the given [mlPrediction]. Call this when first saving an upcoming match to history.
 *
 * Usage in ViewModel / HistoryManager:
 *
 *   val entry = buildHistoryEntry(match, analysis, ...)
 *   val lockedEntry = if (!match.isFinished() && analysis.mlPrediction?.available == true) {
 *       entry.withLockedMlPrediction(analysis.mlPrediction!!)
 *   } else {
 *       entry
 *   }
 *   historyManager.save(lockedEntry)
 */
fun HistoryEntry.withLockedMlPrediction(ml: MLPrediction): HistoryEntry = copy(
    savedMlHomeWinProb    = ml.homeWinProb,
    savedMlDrawProb       = ml.drawProb,
    savedMlAwayWinProb    = ml.awayWinProb,
    savedMlOver25Prob     = ml.over25Prob,
    savedMlBttsProb       = ml.bttsProb,
    savedMlMostLikelyScore = ml.mlMostLikelyScore,
    mlPredictionSaved     = true
)

/**
 * Returns an [MLPrediction] reconstructed from the saved snapshot in this HistoryEntry.
 * Use this in the UI instead of re-running the neural network for finished matches.
 *
 * Usage in MatchAnalysisViewModel / UI:
 *
 *   val historyEntry = historyManager.findByMatchId(matchId)
 *   val mlToShow = if (historyEntry?.mlPredictionSaved == true && match.isFinished()) {
 *       historyEntry.toSavedMlPrediction()
 *   } else {
 *       analysis.mlPrediction  // live neural net result for ongoing/upcoming matches
 *   }
 */
fun HistoryEntry.toSavedMlPrediction(): MLPrediction = MLPrediction(
    homeWinProb       = savedMlHomeWinProb,
    drawProb          = savedMlDrawProb,
    awayWinProb       = savedMlAwayWinProb,
    over25Prob        = savedMlOver25Prob,
    bttsProb          = savedMlBttsProb,
    mlMostLikelyScore = savedMlMostLikelyScore,
    available         = mlPredictionSaved,
    isDefaultWeights  = false,
    modelUsed         = "Neural Net (pre-match snapshot)"
)

/**
 * Returns a copy of this HistoryEntry with the Poisson/Glicko analysis snapshot locked.
 * Call this when first saving an upcoming match to history.
 * The snapshot is restored when the match is reopened after it finishes so that the
 * displayed pre-match analysis never changes retroactively due to form recalculation.
 */
fun HistoryEntry.withLockedPoissonSnapshot(analysis: Analysis): HistoryEntry = copy(
    savedHomeXg                = analysis.homeXg,
    savedAwayXg                = analysis.awayXg,
    savedPoissonHomeWinPct     = analysis.homeWinPct,
    savedPoissonDrawPct        = analysis.drawPct,
    savedPoissonAwayWinPct     = analysis.awayWinPct,
    savedPoissonMostLikelyScore = analysis.mostLikelyScore,
    savedOver25Pct             = analysis.over25Pct,
    savedBttsPct               = analysis.bttsPct,
    savedExpectedTotal         = analysis.expectedTotal,
    savedConfidenceScore       = analysis.confidenceScore,
    poissonSnapshotSaved       = true
)

/**
 * Returns a copy of [freshAnalysis] with Poisson/score fields replaced by the saved
 * pre-match snapshot stored in this HistoryEntry.
 * Use when displaying a finished match so the user sees the original prediction.
 */
fun HistoryEntry.restoreAnalysisSnapshot(freshAnalysis: Analysis): Analysis = freshAnalysis.copy(
    homeXg          = savedHomeXg,
    awayXg          = savedAwayXg,
    homeWinPct      = savedPoissonHomeWinPct,
    drawPct         = savedPoissonDrawPct,
    awayWinPct      = savedPoissonAwayWinPct,
    mostLikelyScore = savedPoissonMostLikelyScore.ifEmpty { freshAnalysis.mostLikelyScore },
    over25Pct       = savedOver25Pct,
    under25Pct      = (100.0 - savedOver25Pct).coerceIn(0.0, 100.0),
    bttsPct         = savedBttsPct,
    expectedTotal   = savedExpectedTotal,
    confidenceScore = savedConfidenceScore
)
