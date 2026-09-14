package be.proleague.model.adapter.mongo

import be.proleague.model.domain.Round
import be.proleague.model.domain.Standings
import be.proleague.model.port.RoundRepositoryPort
import be.proleague.model.port.StandingsRepositoryPort
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndReplaceOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

/**
 * ponytail: domain objects are stored as-is rather than through separate documents, so a
 * rename in the domain breaks reads of existing data. Acceptable while the database starts
 * empty each season; introduce mapped documents if the model outlives a refactor.
 */
@Repository
class MongoRoundRepository(private val mongo: MongoTemplate) : RoundRepositoryPort {

    override fun save(round: Round) {
        mongo.findAndReplace(query(round.season, round.number), round, UPSERT)
    }

    override fun find(season: Int, number: Int): Round? = mongo.findOne(query(season, number), Round::class.java)

    override fun findAll(season: Int): List<Round> = mongo.find(
        Query(Criteria.where("season").`is`(season)).with(Sort.by("number")),
        Round::class.java,
    )

    private fun query(season: Int, number: Int) = Query(
        Criteria.where("season").`is`(season).and("number").`is`(number)
    )

    private companion object {
        val UPSERT: FindAndReplaceOptions = FindAndReplaceOptions.options().upsert()
    }
}

@Repository
class MongoStandingsRepository(private val mongo: MongoTemplate) : StandingsRepositoryPort {

    override fun save(standings: Standings) {
        mongo.save(standings)
    }

    override fun latest(season: Int): Standings? = mongo.findOne(
        Query(Criteria.where("season").`is`(season)).with(Sort.by(Sort.Direction.DESC, "fetchedAt")).limit(1),
        Standings::class.java,
    )
}
