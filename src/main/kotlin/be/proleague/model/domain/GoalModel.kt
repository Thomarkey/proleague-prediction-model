package be.proleague.model.domain

import kotlin.math.min

/**
 * The spreadsheet's goal calculation, ported exactly.
 *
 * Each leg takes a team's attacking rate relative to the league, scales it by the team's
 * own strength multiplier, and multiplies by the opponent's conceding rate relative to the
 * league divided by the opponent's multiplier. Form is then applied.
 *
 * The two legs apply form differently, and the asymmetry is the spreadsheet's, not a
 * transcription error:
 *
 *  - home: the home team's share of the pair's form, scaled by [formGoalScale] (bounded)
 *  - away: the product of both forms                                           (unbounded)
 *
 * Correcting it would change every number the model has ever produced, so it is preserved.
 * [formGoalScale] is the sheet's 2.1, previously tuned down "voor iets minder goals".
 */
class GoalModel(
    private val formGoalScale: Double,
    private val goalCap: Double,
) {

    fun predict(fixture: Fixture, standings: Standings, homeForm: Form, awayForm: Form): Prediction {
        val home = standings.record(fixture.home.id)
        val away = standings.record(fixture.away.id)
        val averages = standings.averages()
        val homeFormValue = homeForm.value()
        val awayFormValue = awayForm.value()
        require(homeFormValue + awayFormValue > 0.0) {
            "Cannot predict ${fixture.home.name} v ${fixture.away.name}: both teams have fewer than " +
                "${Form.REQUIRED_MATCHES} matches played, which leaves the form share undefined"
        }

        val homeGoals = (home.xgHomeFor() / averages.homeFor) * home.multiplier *
            ((away.xgAwayAgainst() / averages.awayAgainst) / away.multiplier) *
            (homeFormValue / (homeFormValue + awayFormValue) * formGoalScale)

        val awayGoals = (away.xgAwayFor() / averages.awayFor) * away.multiplier *
            ((home.xgHomeAgainst() / averages.homeAgainst) / home.multiplier) *
            awayFormValue * homeFormValue

        return Prediction(cap(homeGoals), cap(awayGoals))
    }

    /** The sheet's "teveel goals: cap op 5 per ploeg". */
    private fun cap(goals: Double) = min(goals, goalCap)
}
