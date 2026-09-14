package be.proleague.model.application

import be.proleague.model.domain.GoalModel
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The model's tuning knobs. These are not constants: the goal scale and both odds floors
 * were arrived at by watching the model run, and will be adjusted again.
 */
@ConfigurationProperties(prefix = "model")
data class ModelProperties(
    val formGoalScale: Double,
    val goalCap: Double,
    val minOdds: Double,
    /** Not a floor: the bar above which a double chance is flagged as a value bet. */
    val doubleChanceValueOdds: Double,
    val rankMultipliers: List<Double>,
) {
    fun multiplierFor(rank: Int): Double = rankMultipliers.getOrNull(rank - 1)
        ?: throw IllegalArgumentException(
            "No multiplier for rank $rank, model.rank-multipliers holds ${rankMultipliers.size} entries"
        )
}

@Configuration
class ModelConfiguration {

    @Bean
    fun goalModel(properties: ModelProperties) = GoalModel(properties.formGoalScale, properties.goalCap)
}
