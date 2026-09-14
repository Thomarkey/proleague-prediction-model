package be.proleague.model.domain

enum class Market(val label: String) {
    MATCH_RESULT("1X2"),
    OVER("over"),
    UNDER("onder"),
    BTTS("btts"),
    DOUBLE_CHANCE("dubbele kans");

    companion object {
        /** The label is what the API speaks, being what a round already reports its markets as. */
        fun from(label: String): Market = entries.firstOrNull { it.label == label }
            ?: throw IllegalArgumentException("No market called '$label'")
    }
}

/** A bet on one market. Every pick knows how to settle itself against a final score. */
sealed interface Pick {
    val market: Market

    fun wins(actual: Scoreline): Boolean

    fun label(): String
}

data class MatchResultPick(val result: MatchResult) : Pick {
    override val market = Market.MATCH_RESULT

    override fun wins(actual: Scoreline) = actual.result() == result

    override fun label() = when (result) {
        MatchResult.HOME_WIN -> "1"
        MatchResult.DRAW -> "X"
        MatchResult.AWAY_WIN -> "2"
    }
}

data class OverPick(val line: Double) : Pick {
    override val market = Market.OVER

    override fun wins(actual: Scoreline) = actual.total() > line

    override fun label() = "over $line"
}

data class UnderPick(val line: Double) : Pick {
    override val market = Market.UNDER

    override fun wins(actual: Scoreline) = actual.total() < line

    override fun label() = "under $line"
}

data class BttsPick(val yes: Boolean) : Pick {
    override val market = Market.BTTS

    override fun wins(actual: Scoreline) = actual.bothScored() == yes

    override fun label() = if (yes) "yes" else "no"
}

data class DoubleChancePick(val chance: DoubleChance) : Pick {
    override val market = Market.DOUBLE_CHANCE

    override fun wins(actual: Scoreline) = chance.covers(actual.result())

    override fun label() = chance.label
}
