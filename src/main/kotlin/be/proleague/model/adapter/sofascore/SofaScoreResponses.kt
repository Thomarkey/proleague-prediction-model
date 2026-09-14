package be.proleague.model.adapter.sofascore

/**
 * SofaScore's wire format, isolated here so the rest of the application never sees it.
 *
 * These are the shapes behind the public site's own JSON calls, verified against live
 * responses for tournament 38 season 96616. Only the handful of fields the model reads are
 * declared; the provider sends far more and Boot's mapper ignores the rest.
 */
data class StandingsResponse(val standings: List<StandingsTable> = emptyList())

data class StandingsTable(val rows: List<StandingsRow> = emptyList())

/**
 * One row of a league table. The same shape serves the total, home and away tables, so
 * [matches] and the goal columns mean "at home" in the home table and overall in the total.
 */
data class StandingsRow(
    val position: Int,
    val team: SofaTeam,
    val matches: Int,
    val scoresFor: Int,
    val scoresAgainst: Int,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    /** The provider's own total, so any deduction the league applied is already in it. */
    val points: Int = 0,
)

data class SofaTeam(val id: Int, val name: String)

data class EventsResponse(val events: List<SofaEvent> = emptyList())

data class SofaEvent(
    val id: Long,
    val homeTeam: SofaTeam,
    val awayTeam: SofaTeam,
    val homeScore: EventScore = EventScore(),
    val awayScore: EventScore = EventScore(),
    /** Kickoff, epoch seconds. */
    val startTimestamp: Long,
    val status: EventStatus,
    /** Absent on some feeds; present on the per-team feed, which spans several seasons. */
    val season: EventSeason? = null,
)

data class EventScore(val current: Int? = null)

data class EventSeason(val id: Int)

data class EventStatus(val type: String) {
    /** Anything else is scheduled, live, postponed or abandoned, and carries no final score. */
    fun isFinished(): Boolean = type == FINISHED

    private companion object {
        const val FINISHED = "finished"
    }
}

data class OddsResponse(val markets: List<OddsMarket> = emptyList())

data class OddsMarket(
    val marketId: Int,
    val marketName: String,
    /** The line for a market quoted per line, e.g. "2.5" on match goals. Null elsewhere. */
    val choiceGroup: String? = null,
    val choices: List<OddsChoice> = emptyList(),
)

data class OddsChoice(
    val name: String,
    /** Fractional, e.g. "17/2" or "1/4". SofaScore never quotes decimal. */
    val fractionalValue: String,
) {
    /**
     * Fractional odds are the profit per unit staked, so the decimal price adds the stake
     * back: 17/2 pays 9.5. A malformed or zero-denominator price yields null rather than a
     * guess, which surfaces as an unplaced selection instead of corrupting a round's profit.
     */
    fun decimalOdd(): Double? {
        val numerator = fractionalValue.substringBefore('/').trim().toDoubleOrNull() ?: return null
        val denominator = fractionalValue.substringAfter('/', "").trim().toDoubleOrNull() ?: return null
        return if (denominator == 0.0) null else numerator / denominator + 1
    }
}
