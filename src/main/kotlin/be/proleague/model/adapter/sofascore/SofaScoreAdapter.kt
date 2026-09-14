package be.proleague.model.adapter.sofascore

import be.proleague.model.application.ModelProperties
import be.proleague.model.domain.Fixture
import be.proleague.model.domain.FixtureOdds
import be.proleague.model.domain.Form
import be.proleague.model.domain.MatchOutcome
import be.proleague.model.domain.Scoreline
import be.proleague.model.domain.Standings
import be.proleague.model.domain.Team
import be.proleague.model.domain.TeamRecord
import be.proleague.model.port.FootballDataPort
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant

@ConfigurationProperties(prefix = "sofascore")
data class SofaScoreProperties(
    val baseUrl: String,
    /** The `unique-tournament` id behind the site's league page. 38 is the Jupiler Pro League. */
    val tournament: Int,
    /**
     * SofaScore's own season id, not the calendar year: 96616 is 2026/27, 77040 is 2025/26.
     * `/unique-tournament/38/seasons` lists them.
     */
    val season: Int,
)

@Configuration
class SofaScoreConfiguration {

    @Bean
    fun sofaScoreRestClient(properties: SofaScoreProperties): RestClient = RestClient.builder()
        .baseUrl(properties.baseUrl)
        // An unknown tournament, season or round answers 4xx with the reason in the body.
        // Surface that reason instead of letting a raw HttpClientErrorException become a 500.
        .defaultStatusHandler(HttpStatusCode::isError) { request, response ->
            val body = runCatching { response.body.bufferedReader().readText() }.getOrDefault("")
            throw SofaScoreException("${request.uri.path} -> ${response.statusCode}: $body")
        }
        .build()
}

class SofaScoreException(message: String) : IllegalStateException(message)

/**
 * Reads the same public JSON the SofaScore site reads. No key and no quota, which is why it
 * replaced API-Football: the free plan there locked the current season out entirely.
 */
@Component
class SofaScoreAdapter(
    private val client: RestClient,
    private val properties: SofaScoreProperties,
    private val model: ModelProperties,
) : FootballDataPort {

    /**
     * SofaScore splits the table three ways and the model needs all three: rank comes from the
     * overall table, the goal columns from the home and away ones.
     */
    override fun standings(season: Int): Standings {
        val home = table(season, "home").associateBy { it.team.id }
        val away = table(season, "away").associateBy { it.team.id }

        return Standings(
            season = season,
            records = table(season, "total")
                .sortedBy { it.position }
                .map { it.toRecord(split(home, it.team, "home"), split(away, it.team, "away")) },
            fetchedAt = Instant.now(),
        )
    }

    private fun table(season: Int, type: String): List<StandingsRow> =
        get<StandingsResponse>("/unique-tournament/{t}/season/{s}/standings/{type}", properties.tournament, season, type)
            .standings
            // A Belgian season splits into play-offs and returns a table per group; only the
            // full-league one ranks every team against every other, which is what the strength
            // multiplier assumes. It is the longest, so prefer that over the first.
            .maxByOrNull { it.rows.size }
            ?.rows
            ?: throw SofaScoreException("No $type standings for tournament ${properties.tournament} season $season")

    private fun split(rows: Map<Int, StandingsRow>, team: SofaTeam, type: String): StandingsRow =
        rows[team.id] ?: throw SofaScoreException("${team.name} is missing from the $type table")

    private fun StandingsRow.toRecord(home: StandingsRow, away: StandingsRow) = TeamRecord(
        team = team.toTeam(),
        rank = position,
        multiplier = model.multiplierFor(position),
        homePlayed = home.matches,
        homeGoalsFor = home.scoresFor,
        homeGoalsAgainst = home.scoresAgainst,
        awayPlayed = away.matches,
        awayGoalsFor = away.scoresFor,
        awayGoalsAgainst = away.scoresAgainst,
        homeWins = home.wins,
        homeDraws = home.draws,
        homeLosses = home.losses,
        homePoints = home.points,
        awayWins = away.wins,
        awayDraws = away.draws,
        awayLosses = away.losses,
        awayPoints = away.points,
        points = points,
    )

    override fun fixtures(season: Int, round: Int): List<Fixture> =
        get<EventsResponse>("/unique-tournament/{t}/season/{s}/events/round/{r}", properties.tournament, season, round)
            .events
            .map { it.toFixture() }
            .sortedBy { it.kickoff }

    private fun SofaEvent.toFixture() = Fixture(
        id = id,
        home = homeTeam.toTeam(),
        away = awayTeam.toTeam(),
        kickoff = Instant.ofEpochSecond(startTimestamp),
        result = scoreline(),
    )

    private fun SofaEvent.scoreline(): Scoreline? {
        if (!status.isFinished()) return null
        val home = homeScore.current ?: return null
        val away = awayScore.current ?: return null
        return Scoreline(home, away)
    }

    /**
     * The per-team feed spans every competition the team plays, so it is filtered back to this
     * season's league matches: a cup tie must not count towards league form.
     */
    override fun recentForm(season: Int, teamId: Int): Form = Form.from(
        get<EventsResponse>("/team/{id}/events/last/0", teamId)
            .events
            .filter { it.season?.id == season && it.status.isFinished() }
            .sortedBy { it.startTimestamp }
            .mapNotNull { it.outcomeFor(teamId) }
    )

    private fun SofaEvent.outcomeFor(teamId: Int): MatchOutcome? {
        val score = scoreline() ?: return null
        val playedAtHome = homeTeam.id == teamId
        val scored = if (playedAtHome) score.home else score.away
        val conceded = if (playedAtHome) score.away else score.home
        return when {
            scored > conceded -> MatchOutcome.WIN
            scored < conceded -> MatchOutcome.LOSS
            else -> MatchOutcome.DRAW
        }
    }

    /**[]
     * A fixture nobody prices yet answers 404, which is an absence rather than a failure, so
     * it becomes no odds. Any other error still surfaces.
     */
    override fun odds(fixtureId: Long): FixtureOdds = client.get()
        .uri("/event/{id}/odds/1/all", fixtureId)
        .exchange { request, response ->
            when {
                response.statusCode == HttpStatus.NOT_FOUND -> OddsResponse()
                response.statusCode.isError ->
                    throw SofaScoreException("${request.uri.path} -> ${response.statusCode}")
                else -> response.bodyTo(OddsResponse::class.java) ?: OddsResponse()
            }
        }
        .let { SofaScoreOddsMapper.from(it) }

    private fun SofaTeam.toTeam() = Team(id, name)

    private inline fun <reified T : Any> get(path: String, vararg uriVariables: Any): T = client.get()
        .uri(path, *uriVariables)
        .retrieve()
        .body(T::class.java)
        ?: throw SofaScoreException("Empty body from $path ${uriVariables.toList()}")
}
