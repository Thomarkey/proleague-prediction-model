package be.proleague.model.port

import be.proleague.model.domain.*

interface FootballDataPort {

    fun standings(season: Int): Standings

    fun fixtures(season: Int, round: Int): List<Fixture>

    /** The team's last [Form.KEPT_MATCHES] finished league matches, oldest first, overall and per venue. */
    fun recentForm(season: Int, teamId: Int): TeamForm

    /** Pre-match odds. The provider only serves the last seven days; older fixtures come back empty. */
    fun odds(fixtureId: Long): FixtureOdds
}

interface RoundRepositoryPort {

    fun save(round: Round)

    fun find(season: Int, number: Int): Round?

    fun findAll(season: Int): List<Round>
}

interface StandingsRepositoryPort {

    fun save(standings: Standings)

    fun latest(season: Int): Standings?
}
