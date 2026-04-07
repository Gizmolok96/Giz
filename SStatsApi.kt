package com.footballanalyzer.app.data.api

import com.footballanalyzer.app.data.model.BookmakersResponse
import com.footballanalyzer.app.data.model.GameDetailResponse
import com.footballanalyzer.app.data.model.GameListResponse
import com.footballanalyzer.app.data.model.GlickoResponse
import com.footballanalyzer.app.data.model.LiveMarketsResponse
import com.footballanalyzer.app.data.model.LiveOddsResponse
import com.footballanalyzer.app.data.model.MarketsResponse
import com.footballanalyzer.app.data.model.OddsResponse
import com.footballanalyzer.app.data.model.TeamInfoResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface FootballApi {

    @GET("Games/list")
    suspend fun getMatchesByDate(
        @Query("date") date: String,
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0
    ): GameListResponse

    @GET("Games/list")
    suspend fun getLiveMatches(
        @Query("live") live: Boolean = true,
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0
    ): GameListResponse

    @GET("Games/list")
    suspend fun getTeamRecentGames(
        @Query("team") teamId: Int,
        @Query("ended") ended: Boolean = true,
        @Query("order") order: Int = -1,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0
    ): GameListResponse

    @GET("Games/list")
    suspend fun getH2HGames(
        @Query("bothTeams") bothTeams: String,
        @Query("ended") ended: Boolean = true,
        @Query("order") order: Int = -1,
        @Query("limit") limit: Int = 20
    ): GameListResponse

    @GET("Games/{id}")
    suspend fun getGameDetail(
        @Path("id") matchId: String
    ): GameDetailResponse

    @GET("Teams/{id}")
    suspend fun getTeamInfo(
        @Path("id") teamId: Int
    ): TeamInfoResponse

    /** Glicko-2 ratings and pre-computed xG for a specific match */
    @GET("Games/glicko/{id}")
    suspend fun getGlickoData(
        @Path("id") matchId: String
    ): GlickoResponse

    // ─── Odds endpoints ───────────────────────────────────────────────────

    /** List of all bookmakers supported by the API */
    @GET("Odds/bookmakers")
    suspend fun getBookmakers(): BookmakersResponse

    /** Pre-match (prematch) odds for a game. optionally filtered by bookmakerId */
    @GET("Odds/{gameId}")
    suspend fun getPrematchOdds(
        @Path("gameId") gameId: Int,
        @Query("bookmakerId") bookmakerId: String? = null,
        @Query("opening") opening: Boolean? = null
    ): OddsResponse

    /** Live odds for a game */
    @GET("Odds/live/{gameId}")
    suspend fun getLiveOdds(
        @Path("gameId") gameId: Int
    ): LiveOddsResponse

    /** Available pre-match market types (id → name map) */
    @GET("Odds/prematch-markets")
    suspend fun getPrematchMarkets(): MarketsResponse

    /** Available live market types (id → name map) */
    @GET("Odds/live-markets")
    suspend fun getLiveMarkets(): LiveMarketsResponse
}
