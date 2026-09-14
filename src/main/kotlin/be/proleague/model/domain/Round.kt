package be.proleague.model.domain

import java.time.Instant
import kotlin.math.roundToInt

data class Fixture(
    val id: Long,
    val home: Team,
    val away: Team,
    val kickoff: Instant,
    val result: Scoreline?,
)

data class Prediction(val homeGoals: Double, val awayGoals: Double) {
    /**
     * Excel's ROUND and [roundToInt] both round a half away from zero, and predicted goals
     * are never negative, so the two agree on every value this model produces.
     */
    fun scoreline(): Scoreline = Scoreline(homeGoals.roundToInt(), awayGoals.roundToInt())

    /**
     * The double chance backing the favoured side.
     *
     * Read off the goals rather than off [scoreline], because rounding is what loses the
     * favourite: 0.62 - 0.48 is a home lean that shows up as 0-0, and a double chance taken
     * from that draw would have no side to back at all. The goals still do. Only a dead heat
     * to the last decimal has none, and that falls to the home side, which is the side the
     * model already hands the ground advantage to.
     */
    fun doubleChance(): DoubleChance =
        if (homeGoals >= awayGoals) DoubleChance.HOME_OR_DRAW else DoubleChance.DRAW_OR_AWAY

    /** Whether the 1X2 bet backs a side: read off [scoreline], which is what that bet is taken from. */
    fun hasWinner(): Boolean = scoreline().result() != MatchResult.DRAW
}

/**
 * Pre-match odds, keyed by the pick they pay out on. [edited] holds the picks whose odds were
 * typed in by hand rather than read off the provider.
 */
data class FixtureOdds(val byPick: Map<Pick, Double>, val edited: Set<Pick> = emptySet()) {

    fun of(pick: Pick): Double? = byPick[pick]

    fun wasEdited(pick: Pick): Boolean = pick in edited

    /**
     * The provider's book with hand-typed odds laid over it. The provider is a proxy for what
     * is actually on offer, so where the two disagree the typed one is the real price.
     */
    fun withEdits(edits: Map<Pick, Double>) = FixtureOdds(byPick + edits, edits.keys)

    companion object {
        fun none() = FixtureOdds(emptyMap())
    }
}

/**
 * A bet the model produced. [placed] is false when the odds were missing or below the
 * market's floor; the pick is kept either way so a round can show why it staked nothing.
 * [edited] marks odds that were corrected by hand, which is what keeps them through a
 * re-prediction. [valueBet] marks a price the model rates as generous; it changes nothing
 * about the stake, the view just points at it. Defaulted so a round stored before it existed
 * still loads.
 */
data class Selection(
    val pick: Pick,
    val odds: Double?,
    val placed: Boolean,
    val edited: Boolean = false,
    val valueBet: Boolean = false,
) {
    /** Return on a one unit stake: the odds when the pick wins, 0.0 when it loses. */
    fun returnOn(actual: Scoreline?): Double? {
        val stakeOdds = odds ?: return null
        if (!placed || actual == null) return null
        return if (pick.wins(actual)) stakeOdds else 0.0
    }
}

data class MatchPrediction(
    val fixture: Fixture,
    val prediction: Prediction,
    val selections: List<Selection>,
) {
    fun selection(market: Market): Selection? = selections.firstOrNull { it.pick.market == market }

    /**
     * The hand-typed odds, keyed by the pick they were typed against. A re-prediction that
     * moves the score moves the pick with it, and the edit is then simply not found again:
     * a price typed for over 2.5 says nothing about over 3.5.
     */
    fun editedOdds(): Map<Pick, Double> = selections
        .filter { it.edited }
        .mapNotNull { selection -> selection.odds?.let { selection.pick to it } }
        .toMap()

    /** The same match with one market's bet replaced. The pick itself does not move. */
    fun withSelection(selection: Selection) = copy(
        selections = selections.map { if (it.pick.market == selection.pick.market) selection else it }
    )
}

data class Round(
    val season: Int,
    val number: Int,
    val matches: List<MatchPrediction>,
) {
    fun match(fixtureId: Long): MatchPrediction? = matches.firstOrNull { it.fixture.id == fixtureId }

    fun withMatch(match: MatchPrediction) =
        copy(matches = matches.map { if (it.fixture.id == match.fixture.id) match else it })

    /**
     * Profit for one market at one unit per match: winning odds less the stakes actually
     * placed. Null until at least one match in that market has settled.
     *
     * The spreadsheet subtracts a flat 8 per round, charging a unit even for bets its odds
     * floor skipped. This counts only what was staked.
     */
    fun netto(market: Market): Double? =
        netto(matches.mapNotNull { it.selection(market)?.returnOn(it.fixture.result) })

    fun netto(): Map<Market, Double?> = Market.entries.associateWith { netto(it) }

    /**
     * The value bets read as a book of their own, so the flag can be judged on what it made
     * rather than taken on faith. A cut across [netto], not a sixth market beside it: each of
     * these stakes is counted in its own market's netto as well.
     */
    fun nettoValueBets(): Double? = netto(
        matches.flatMap { match -> match.selections.filter { it.valueBet }.mapNotNull { it.returnOn(match.fixture.result) } }
    )

    private fun netto(returns: List<Double>): Double? =
        if (returns.isEmpty()) null else returns.sum() - returns.size

    fun isSettled(): Boolean = matches.all { it.fixture.result != null }
}
