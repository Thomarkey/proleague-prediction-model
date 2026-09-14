package be.proleague.model.port

import be.proleague.model.domain.Fixture
import be.proleague.model.domain.FixtureOdds
import be.proleague.model.domain.Form
import be.proleague.model.domain.Round
import be.proleague.model.domain.Standings

interface FootballDataPort {

    fun standings(season: Int): Standings

    fun fixtures(season: Int, round: Int): List<Fixture>

    /** The team's last [Form.REQUIRED_MATCHES] finished league matches, oldest first. */
    fun recentForm(season: Int, teamId: Int): Form

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
