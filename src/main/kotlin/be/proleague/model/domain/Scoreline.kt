package be.proleague.model.domain

enum class MatchResult {
    HOME_WIN,
    DRAW,
    AWAY_WIN,
}

enum class DoubleChance(val label: String) {
    HOME_OR_DRAW("1X"),
    DRAW_OR_AWAY("X2"),
    ;

    fun covers(result: MatchResult): Boolean = when (this) {
        HOME_OR_DRAW -> result != MatchResult.AWAY_WIN
        DRAW_OR_AWAY -> result != MatchResult.HOME_WIN
    }
}

data class Scoreline(val home: Int, val away: Int) {

    fun total(): Int = home + away

    fun result(): MatchResult = when {
        home > away -> MatchResult.HOME_WIN
        home < away -> MatchResult.AWAY_WIN
        else -> MatchResult.DRAW
    }

    fun bothScored(): Boolean = home >= 1 && away >= 1

    override fun toString(): String = "$home-$away"
}
