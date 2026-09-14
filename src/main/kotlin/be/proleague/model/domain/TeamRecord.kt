package be.proleague.model.domain

/**
 * A team's split home and away record, plus the strength multiplier its league position
 * earns it. The rates are goals per match: the spreadsheet's XGF and XGA columns.
 */
data class TeamRecord(
    val team: Team,
    val rank: Int,
    val multiplier: Double,
    val homePlayed: Int,
    val homeGoalsFor: Int,
    val homeGoalsAgainst: Int,
    val awayPlayed: Int,
    val awayGoalsFor: Int,
    val awayGoalsAgainst: Int,
    /**
     * The win/draw/loss columns and points of the home and away tables. The model never reads
     * them - the league table view does, both split and added up. Default to zero so a
     * standings document written before they existed still loads; the next refresh fills
     * them in.
     */
    val homeWins: Int = 0,
    val homeDraws: Int = 0,
    val homeLosses: Int = 0,
    val homePoints: Int = 0,
    val awayWins: Int = 0,
    val awayDraws: Int = 0,
    val awayLosses: Int = 0,
    val awayPoints: Int = 0,
    /**
     * The overall table's points, kept separately because it is the only figure a league
     * deduction lands on: adding the two split tables up would hand the points back.
     */
    val points: Int = 0,
    /** Recent results for the table's form strip. Same default reason as the columns above. */
    val form: Form = Form.none(),
) {
    val played: Int get() = homePlayed + awayPlayed

    val wins: Int get() = homeWins + awayWins

    val draws: Int get() = homeDraws + awayDraws

    val losses: Int get() = homeLosses + awayLosses

    val goalsFor: Int get() = homeGoalsFor + awayGoalsFor

    val goalsAgainst: Int get() = homeGoalsAgainst + awayGoalsAgainst

    val goalDifference: Int get() = goalsFor - goalsAgainst

    val homeGoalDifference: Int get() = homeGoalsFor - homeGoalsAgainst

    val awayGoalDifference: Int get() = awayGoalsFor - awayGoalsAgainst

    fun xgHomeFor(): Double = rate(homeGoalsFor, homePlayed)

    fun xgHomeAgainst(): Double = rate(homeGoalsAgainst, homePlayed)

    fun xgAwayFor(): Double = rate(awayGoalsFor, awayPlayed)

    fun xgAwayAgainst(): Double = rate(awayGoalsAgainst, awayPlayed)

    /**
     * Goals per match, floored just off zero.
     *
     * The model multiplies the four rates together, so a single true zero - a side that has
     * not conceded at home after three games - drives the whole prediction to 0 and takes
     * every market on that fixture with it. Three clean sheets is thin evidence that a team
     * will never concede, so the floor treats it as "almost none" rather than "none".
     *
     * It is a blunt instrument: [FLOOR] is small enough to stay invisible once a table fills
     * in, but it is still a made-up number standing in for a missing prior. The honest fix is
     * to shrink each rate toward the league average by matches played, which would also stop
     * a 1-in-1 record reading as strongly as a 30-in-30 one. Worth doing if early rounds keep
     * producing odd numbers.
     */
    private fun rate(goals: Int, played: Int): Double =
        (goals / played.toDouble()).coerceAtLeast(FLOOR)

    /** Every rate divides by matches played, so a team must have played on both sides. */
    fun hasPlayedBothWays(): Boolean = homePlayed > 0 && awayPlayed > 0

    private companion object {
        /** Below any real goals-per-match figure, so it only ever replaces an exact zero. */
        const val FLOOR = 0.01
    }
}

data class LeagueAverages(
    val homeFor: Double,
    val homeAgainst: Double,
    val awayFor: Double,
    val awayAgainst: Double,
) {
    companion object {
        fun from(records: List<TeamRecord>) = LeagueAverages(
            homeFor = records.map { it.xgHomeFor() }.average(),
            homeAgainst = records.map { it.xgHomeAgainst() }.average(),
            awayFor = records.map { it.xgAwayFor() }.average(),
            awayAgainst = records.map { it.xgAwayAgainst() }.average(),
        )
    }
}
