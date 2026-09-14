package be.proleague.model.presentation

import be.proleague.model.domain.Fixture
import be.proleague.model.domain.Market
import be.proleague.model.domain.MatchPrediction
import be.proleague.model.domain.Round
import be.proleague.model.domain.Selection
import be.proleague.model.domain.Standings
import be.proleague.model.domain.TeamRecord
import java.time.Instant
import kotlin.math.round

/**
 * Every figure the API emits is read, not recomputed, so two decimals is all the precision it
 * needs -- and it keeps a netto off the `0.30000000000000004` that summing doubles produces.
 */
private fun Double.round2(): Double = round(this * 100) / 100

/** The value bets' own column, alongside the five markets a netto is otherwise keyed by. */
private const val VALUE_BETS = "value bet"

/** A round's profit per column: the five markets, then the value bets counted a second time. */
private fun Round.nettoByLabel(): Map<String, Double?> =
    netto().entries.associate { it.key.label to it.value?.round2() } + (VALUE_BETS to nettoValueBets()?.round2())

/** The same columns for a round that was never predicted: present, but with nothing in them. */
private fun emptyNetto(): Map<String, Double?> =
    Market.entries.associate { it.label to null } + (VALUE_BETS to null)

data class SelectionDto(
    val market: String,
    val pick: String,
    val odds: Double?,
    val placed: Boolean,
    /** True once the odds were typed in by hand, so the view can say so. */
    val edited: Boolean,
    /** A double chance still paying the old floor: flagged, but staked like any other bet. */
    val valueBet: Boolean,
    val won: Boolean?,
) {
    companion object {
        fun from(selection: Selection, match: MatchPrediction) = SelectionDto(
            market = selection.pick.market.label,
            pick = selection.pick.label(),
            odds = selection.odds?.round2(),
            placed = selection.placed,
            edited = selection.edited,
            valueBet = selection.valueBet,
            won = match.fixture.result?.let { selection.pick.wins(it) },
        )
    }
}

/** One hand-typed odd. The market is named by its label, the same one a round reports. */
data class OddsEditDto(val fixtureId: Long, val market: String, val odds: Double) {
    fun market(): Market = Market.from(market)
}

data class MatchDto(
    val fixtureId: Long,
    val home: String,
    val away: String,
    val homeId: Int,
    val awayId: Int,
    val kickoff: Instant,
    /** Null for a round that was never predicted: the fixture and its result still stand. */
    val predictedHomeGoals: Double?,
    val predictedAwayGoals: Double?,
    val predictedScore: String?,
    val actualScore: String?,
    val selections: List<SelectionDto>,
) {
    companion object {
        fun from(fixture: Fixture) = MatchDto(
            fixtureId = fixture.id,
            home = fixture.home.name,
            away = fixture.away.name,
            homeId = fixture.home.id,
            awayId = fixture.away.id,
            kickoff = fixture.kickoff,
            predictedHomeGoals = null,
            predictedAwayGoals = null,
            predictedScore = null,
            actualScore = fixture.result?.toString(),
            selections = emptyList(),
        )

        fun from(match: MatchPrediction) = from(match.fixture).copy(
            predictedHomeGoals = match.prediction.homeGoals.round2(),
            predictedAwayGoals = match.prediction.awayGoals.round2(),
            predictedScore = match.prediction.scoreline().toString(),
            selections = match.selections.map { SelectionDto.from(it, match) },
        )
    }
}

data class RoundDto(
    val season: Int,
    val number: Int,
    /** False for a round the model never got to: it shows as fixtures and results only. */
    val predicted: Boolean,
    val settled: Boolean,
    val netto: Map<String, Double?>,
    val matches: List<MatchDto>,
) {
    companion object {
        fun from(round: Round) = RoundDto(
            season = round.season,
            number = round.number,
            predicted = true,
            settled = round.isSettled(),
            netto = round.nettoByLabel(),
            matches = round.matches.map { MatchDto.from(it) },
        )

        /**
         * A round with nothing in the book. Rounds 1 to 4 of a season are always this: the
         * model needs a table before it can predict, and by the time it has one they are gone.
         */
        fun from(season: Int, number: Int, fixtures: List<Fixture>) = RoundDto(
            season = season,
            number = number,
            predicted = false,
            settled = fixtures.all { it.result != null },
            netto = emptyNetto(),
            matches = fixtures.map { MatchDto.from(it) },
        )
    }
}

/** A round in the list view, plus the running profit up to and including it. */
data class RoundSummaryDto(
    val season: Int,
    val number: Int,
    val settled: Boolean,
    val netto: Map<String, Double?>,
    val runningTotal: Map<String, Double>,
) {
    companion object {
        fun from(rounds: List<Round>): List<RoundSummaryDto> {
            val running = mutableMapOf<String, Double>()
            return rounds.map { round ->
                val netto = round.nettoByLabel()
                netto.forEach { (column, profit) -> running[column] = (running[column] ?: 0.0) + (profit ?: 0.0) }
                RoundSummaryDto(
                    season = round.season,
                    number = round.number,
                    settled = round.isSettled(),
                    netto = netto,
                    runningTotal = running.mapValues { it.value.round2() },
                )
            }
        }
    }
}

/** One set of table columns. The same shape for the total, home and away tables. */
data class TableColumnsDto(
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDifference: Int,
    val points: Int,
)

data class TeamRecordDto(
    /** Position in the overall table. The home and away tables are ranked by the view. */
    val rank: Int,
    val team: String,
    /** The provider's team id, which is also what its crest is served under. */
    val teamId: Int,
    val total: TableColumnsDto,
    val home: TableColumnsDto,
    val away: TableColumnsDto,
    /** Recent results oldest first, e.g. "LWDWW". Empty until the next refresh fills it in. */
    val form: String,
    val multiplier: Double,
    val xgHomeFor: Double,
    val xgHomeAgainst: Double,
    val xgAwayFor: Double,
    val xgAwayAgainst: Double,
) {
    companion object {
        fun from(record: TeamRecord) = TeamRecordDto(
            rank = record.rank,
            team = record.team.name,
            teamId = record.team.id,
            total = TableColumnsDto(
                played = record.played,
                wins = record.wins,
                draws = record.draws,
                losses = record.losses,
                goalsFor = record.goalsFor,
                goalsAgainst = record.goalsAgainst,
                goalDifference = record.goalDifference,
                points = record.points,
            ),
            home = TableColumnsDto(
                played = record.homePlayed,
                wins = record.homeWins,
                draws = record.homeDraws,
                losses = record.homeLosses,
                goalsFor = record.homeGoalsFor,
                goalsAgainst = record.homeGoalsAgainst,
                goalDifference = record.homeGoalDifference,
                points = record.homePoints,
            ),
            away = TableColumnsDto(
                played = record.awayPlayed,
                wins = record.awayWins,
                draws = record.awayDraws,
                losses = record.awayLosses,
                goalsFor = record.awayGoalsFor,
                goalsAgainst = record.awayGoalsAgainst,
                goalDifference = record.awayGoalDifference,
                points = record.awayPoints,
            ),
            form = record.form.code(),
            multiplier = record.multiplier.round2(),
            xgHomeFor = record.xgHomeFor().round2(),
            xgHomeAgainst = record.xgHomeAgainst().round2(),
            xgAwayFor = record.xgAwayFor().round2(),
            xgAwayAgainst = record.xgAwayAgainst().round2(),
        )
    }
}

data class StandingsDto(
    val season: Int,
    val fetchedAt: Instant,
    val averageHomeFor: Double,
    val averageHomeAgainst: Double,
    val averageAwayFor: Double,
    val averageAwayAgainst: Double,
    val records: List<TeamRecordDto>,
) {
    companion object {
        fun from(standings: Standings): StandingsDto {
            val averages = standings.averages()
            return StandingsDto(
                season = standings.season,
                fetchedAt = standings.fetchedAt,
                averageHomeFor = averages.homeFor.round2(),
                averageHomeAgainst = averages.homeAgainst.round2(),
                averageAwayFor = averages.awayFor.round2(),
                averageAwayAgainst = averages.awayAgainst.round2(),
                records = standings.records.map { TeamRecordDto.from(it) },
            )
        }
    }
}
