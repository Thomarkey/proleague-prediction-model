package be.proleague.model.application

import be.proleague.model.domain.Standings
import be.proleague.model.port.FootballDataPort
import be.proleague.model.port.StandingsRepositoryPort
import org.springframework.stereotype.Service

/**
 * The league table the view shows. A plain fetch from the provider, with nothing of the model
 * in it: the predictions read their own table, so this one can be as stale or as current as
 * the reader likes without moving a single bet.
 *
 * It is stored rather than fetched per page load because the form strip costs a call per team,
 * which is a button press and not a render.
 */
@Service
class StandingsService(
    private val footballData: FootballDataPort,
    private val repository: StandingsRepositoryPort,
) {

    fun refresh(season: Int): Standings {
        val table = footballData.standings(season)
        val forms = table.teamIds().associateWith { footballData.recentForm(season, it) }
        return table.withForm(forms).also { repository.save(it) }
    }

    fun latest(season: Int): Standings? = repository.latest(season)
}
