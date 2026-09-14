package be.proleague.model.application

import be.proleague.model.domain.BttsPick
import be.proleague.model.domain.DoubleChancePick
import be.proleague.model.domain.FixtureOdds
import be.proleague.model.domain.Market
import be.proleague.model.domain.MatchResultPick
import be.proleague.model.domain.OverPick
import be.proleague.model.domain.Pick
import be.proleague.model.domain.Prediction
import be.proleague.model.domain.Round
import be.proleague.model.domain.Selection
import be.proleague.model.domain.UnderPick
import org.springframework.stereotype.Service

/**
 * Turns a predicted scoreline into bets.
 *
 * Every pick follows from the rounded score: a 3-1 gives `1`, over 3.5, under 4.5, btts yes
 * and 1X. The over and under lines bracket the predicted total, so both win when the total
 * lands exactly on it. The double chance is the one pick taken from the goals before they
 * are rounded, so a predicted draw still backs the side the model leans to.
 *
 * A bet is placed only when its odds clear the odds floor: below that it is not worth the
 * stake. Every market answers to the same floor; the double chance's own, higher bar is now
 * only a value marker, not a gate.
 */
@Service
class BetSelectionService(private val properties: ModelProperties) {

    fun select(prediction: Prediction, odds: FixtureOdds): List<Selection> {
        val score = prediction.scoreline()
        return listOf(
            selection(prediction, MatchResultPick(score.result()), odds),
            selection(prediction, OverPick(score.total() - LINE_OFFSET), odds),
            selection(prediction, UnderPick(score.total() + LINE_OFFSET), odds),
            selection(prediction, BttsPick(score.bothScored()), odds),
            selection(prediction, DoubleChancePick(prediction.doubleChance()), odds),
        )
    }

    /** The same pick at a hand-typed price, restaked against the same floor as any other. */
    fun restake(prediction: Prediction, pick: Pick, odds: Double): Selection =
        selection(prediction, pick, odds, edited = true)

    /**
     * A stored round re-judged against today's floor.
     *
     * Whether a bet was placed does not belong in the store the way the pick and the price do:
     * it is this calculation, and it was only ever frozen because the answer got written down
     * next to its inputs. A round that has kicked off is never predicted again - [keepBook]
     * hands back exactly what was stored - so without this, the floor of the day a round was
     * written under is the floor it keeps for good, and lowering one cannot reach the rounds
     * it already skipped. Re-running it on read is what makes a floor a setting.
     */
    fun restate(round: Round): Round = round.copy(
        matches = round.matches.map { match ->
            match.copy(
                selections = match.selections.map {
                    selection(match.prediction, it.pick, it.odds, it.edited)
                }
            )
        }
    )

    private fun selection(prediction: Prediction, pick: Pick, odds: FixtureOdds): Selection =
        selection(prediction, pick, odds.of(pick), edited = odds.wasEdited(pick))

    private fun selection(prediction: Prediction, pick: Pick, odds: Double?, edited: Boolean) = Selection(
        pick = pick,
        odds = odds,
        placed = odds != null && odds >= properties.minOdds,
        edited = edited,
        valueBet = odds != null && isValue(prediction, pick, odds),
    )

    /**
     * A double chance is priced short by construction - it covers two of the three results -
     * so one still paying [ModelProperties.doubleChanceValueOdds] is the book rating the
     * favoured side well below what the model makes it. That is worth pointing at, but it is
     * a marker only: the bet is placed on the same floor as every other market.
     *
     * Only when the model itself backs a side. A predicted draw gives the double chance no
     * favourite to be underrating: the pick then falls out of the goals before rounding and
     * covers the draw the model actually expects, so a generous price on it says nothing.
     */
    private fun isValue(prediction: Prediction, pick: Pick, odds: Double): Boolean =
        pick.market == Market.DOUBLE_CHANCE &&
            odds >= properties.doubleChanceValueOdds &&
            prediction.hasWinner()

    private companion object {
        /** Half a goal either side of the predicted total. */
        const val LINE_OFFSET = 0.5
    }
}
