package be.proleague.model.adapter.sofascore

import be.proleague.model.domain.BttsPick
import be.proleague.model.domain.DoubleChance
import be.proleague.model.domain.DoubleChancePick
import be.proleague.model.domain.FixtureOdds
import be.proleague.model.domain.MatchResult
import be.proleague.model.domain.MatchResultPick
import be.proleague.model.domain.OverPick
import be.proleague.model.domain.Pick
import be.proleague.model.domain.UnderPick

/**
 * Maps SofaScore's markets and choices onto the model's picks.
 *
 * Anything the model does not bet on is ignored, and an unrecognised choice is skipped
 * rather than guessed at: a missing odd shows up as an unplaced selection, which is
 * visible, where a wrong one would silently corrupt a round's profit.
 */
object SofaScoreOddsMapper {

    private const val FULL_TIME = 1
    private const val DOUBLE_CHANCE = 2
    private const val BOTH_TEAMS_TO_SCORE = 5
    private const val MATCH_GOALS = 9

    fun from(response: OddsResponse): FixtureOdds = FixtureOdds(
        response.markets
            .flatMap { market -> market.choices.mapNotNull { choice -> pick(market, choice)?.to(choice) } }
            .mapNotNull { (pick, choice) -> choice.decimalOdd()?.let { pick to it } }
            .toMap()
    )

    private fun pick(market: OddsMarket, choice: OddsChoice): Pick? = when (market.marketId) {
        FULL_TIME -> matchResult(choice.name)?.let { MatchResultPick(it) }
        DOUBLE_CHANCE -> doubleChance(choice.name)?.let { DoubleChancePick(it) }
        BOTH_TEAMS_TO_SCORE -> bothTeamsScore(choice.name)?.let { BttsPick(it) }
        MATCH_GOALS -> matchGoals(market.choiceGroup, choice.name)
        else -> null
    }

    private fun matchResult(name: String) = when (name) {
        "1" -> MatchResult.HOME_WIN
        "X" -> MatchResult.DRAW
        "2" -> MatchResult.AWAY_WIN
        else -> null
    }

    /** "12" is a draw-no-bet in disguise; the model only ever picks the two that cover a draw. */
    private fun doubleChance(name: String) = when (name) {
        "1X" -> DoubleChance.HOME_OR_DRAW
        "X2" -> DoubleChance.DRAW_OR_AWAY
        else -> null
    }

    private fun bothTeamsScore(name: String) = when (name) {
        "Yes" -> true
        "No" -> false
        else -> null
    }

    /** The line lives on the market as "2.5", the side on the choice as "Over" or "Under". */
    private fun matchGoals(choiceGroup: String?, name: String): Pick? {
        val line = choiceGroup?.toDoubleOrNull() ?: return null
        return when (name) {
            "Over" -> OverPick(line)
            "Under" -> UnderPick(line)
            else -> null
        }
    }
}
