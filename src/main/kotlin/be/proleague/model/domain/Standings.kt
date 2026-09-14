package be.proleague.model.domain

import java.time.Instant

/**
 * The league table a round's predictions were built from, ordered by rank.
 *
 * Rank drives the strength multiplier, so the table is stored alongside every round: a
 * later table would produce different numbers for the same fixtures.
 */
data class Standings(
    val season: Int,
    val records: List<TeamRecord>,
    val fetchedAt: Instant,
) {
    fun averages(): LeagueAverages = LeagueAverages.from(records)

    fun record(teamId: Int): TeamRecord = records.firstOrNull { it.team.id == teamId }
        ?: throw IllegalArgumentException("Team $teamId is not in the $season standings")

    fun teamsWithoutFullRecord(): List<Team> = records.filter { !it.hasPlayedBothWays() }.map { it.team }

    fun teamIds(): Set<Int> = records.map { it.team.id }.toSet()

    /** The provider serves form per team, so it is folded in once the table is built. */
    fun withForm(forms: Map<Int, Form>): Standings =
        copy(records = records.map { record -> record.copy(form = forms[record.team.id] ?: record.form) })
}
