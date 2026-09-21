package be.proleague.model.application

import be.proleague.model.domain.*
import be.proleague.model.port.FootballDataPort
import be.proleague.model.port.RoundRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

class IncompleteStandingsException(teams: List<Team>) : IllegalStateException(
    "Cannot predict yet: ${teams.joinToString { it.name }} " +
        "${if (teams.size == 1) "has" else "have"} not played both home and away"
)

class RoundAlreadyStartedException(roundNumber: Int) : IllegalStateException(
    "Speeldag $roundNumber has already kicked off, so it can no longer be predicted: the live " +
        "table now holds its own results"
)

/**
 * Predicts a round, or takes its results once it can no longer be predicted.
 *
 * A round is predicted from the live table, which counts every finished match and nothing
 * else. That is the cumulative table of everything before the round only while the round
 * itself is still to be played, so the round's first kickoff is the line it all turns on:
 *
 *  - before it, every call re-predicts the whole round from scratch. A stored prediction
 *    carries no weight, because a later table is strictly better informed than the one it was
 *    built on. Predicting a round weeks early is therefore harmless: it will be redone, at
 *    the latest odds, until the round starts.
 *  - from it, nothing is predicted again. The predictions and the odds they were struck at
 *    are the book, and only the results are updated. A round that was never predicted before
 *    kickoff has missed its chance for good, because the table now holds its own results.
 *
 * The table is read here but never stored: it is this service's input, not its output. What
 * the league view shows is [StandingsService]'s business.
 */
@Service
class PredictionService(
    private val footballData: FootballDataPort,
    private val roundRepository: RoundRepositoryPort,
    private val goalModel: GoalModel,
    private val betSelection: BetSelectionService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * A stored round, judged against the odds bounds as they stand today rather than as they
     * stood the day the round was written. Every read goes through here, because a round that has
     * kicked off is never predicted again and this is its only chance to hear that a bound moved.
     */
    fun round(season: Int, roundNumber: Int): Round? =
        roundRepository.find(season, roundNumber)?.let { betSelection.restate(it) }

    fun rounds(season: Int): List<Round> = roundRepository.findAll(season).map { betSelection.restate(it) }

    fun predict(season: Int, roundNumber: Int): Round {
        val fixtures = footballData.fixtures(season, roundNumber)
        val existing = round(season, roundNumber)

        val matches = if (hasStarted(fixtures)) {
            keepBook(existing, roundNumber, fixtures)
        } else {
            predictAll(season, fixtures, existing)
        }

        return Round(season, roundNumber, matches).also {
            roundRepository.save(it)
            log.info("Predicted season {} round {}: {} matches", season, roundNumber, matches.size)
        }
    }

    /**
     * Corrects one bet's odds by hand. The provider's price is only a proxy for what is
     * actually on offer, so a typed one overrules it and, being marked as typed, survives
     * every later re-prediction that still lands on the same pick.
     */
    fun editOdds(season: Int, roundNumber: Int, fixtureId: Long, market: Market, odds: Double): Round {
        require(odds >= 1.0) { "An odd of $odds pays out less than the stake" }
        val round = round(season, roundNumber)
            ?: throw IllegalArgumentException("Speeldag $roundNumber holds no predictions to correct")
        val match = round.match(fixtureId)
            ?: throw IllegalArgumentException("Speeldag $roundNumber holds no fixture $fixtureId")
        val selection = match.selection(market)
            ?: throw IllegalArgumentException("Fixture $fixtureId carries no ${market.label} bet")

        return round.withMatch(match.withSelection(betSelection.restake(match.prediction, selection.pick, odds))).also {
            roundRepository.save(it)
            log.info("Set {} odds on fixture {} to {}", market.label, fixtureId, odds)
        }
    }

    /**
     * The round as the provider has it. What a round with nothing in the book falls back to,
     * so its fixtures and scores can still be read even though it will never carry a bet.
     */
    fun fixtures(season: Int, roundNumber: Int): List<Fixture> = footballData.fixtures(season, roundNumber)

    /**
     * The whole round is judged by its earliest kickoff: once any match in it has started the
     * table can no longer be trusted to predate the round.
     */
    private fun hasStarted(fixtures: List<Fixture>): Boolean =
        fixtures.any { !it.kickoff.isAfter(clock.instant()) }

    /**
     * A started round is the book as it was written: the predictions and the odds they were
     * struck at stand, and only the results still move. A fixture the provider has since added
     * to the round missed its chance to be predicted, so it is left out rather than back-dated.
     */
    private fun keepBook(existing: Round?, roundNumber: Int, fixtures: List<Fixture>): List<MatchPrediction> {
        val kept = fixtures.mapNotNull { fixture -> existing?.match(fixture.id)?.copy(fixture = fixture) }
        if (kept.isEmpty()) throw RoundAlreadyStartedException(roundNumber)
        if (kept.size < fixtures.size) {
            log.warn(
                "Round {} holds {} fixtures but only {} were predicted before kickoff; leaving the rest out",
                roundNumber, fixtures.size, kept.size,
            )
        }
        return kept
    }

    /**
     * Form costs a call per team, so only the teams actually playing are fetched. The whole
     * table still has to be complete: every rate divides by matches played, and a side missing
     * one of its two halves drags the league averages the model divides by.
     */
    private fun predictAll(season: Int, fixtures: List<Fixture>, existing: Round?): List<MatchPrediction> {
        val standings = footballData.standings(season)
        standings.teamsWithoutFullRecord()
            .takeIf { it.isNotEmpty() }
            ?.let { throw IncompleteStandingsException(it) }

        val forms = fixtures.flatMapTo(mutableSetOf()) { setOf(it.home.id, it.away.id) }
            .associateWith { footballData.recentForm(season, it) }
        return fixtures.map { predict(it, standings, forms, existing?.match(it.id)) }
    }

    /**
     * The odds are re-read from the provider, but hand-typed ones are laid back over them:
     * they are corrections of that same provider, so a fresh fetch would only undo them.
     */
    private fun predict(
        fixture: Fixture,
        standings: Standings,
        forms: Map<Int, Form>,
        previous: MatchPrediction?,
    ): MatchPrediction {
        val prediction = goalModel.predict(fixture, standings, form(forms, fixture.home), form(forms, fixture.away))
        val odds = footballData.odds(fixture.id).withEdits(previous?.editedOdds().orEmpty())
        return MatchPrediction(
            fixture = fixture,
            prediction = prediction,
            selections = betSelection.select(prediction, odds),
        )
    }

    private fun form(forms: Map<Int, Form>, team: Team): Form = forms[team.id]
        ?: throw IllegalStateException("No recent form fetched for ${team.name}")
}
