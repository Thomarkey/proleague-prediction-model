package be.proleague.model.domain

/** Weights are the spreadsheet's: a win 2/5, a draw 1/3, a loss 4/15. */
enum class MatchOutcome(val code: Char, val weight: Double) {
    WIN('W', 2.0 / 5),
    DRAW('D', 1.0 / 3),
    LOSS('L', 4.0 / 15),
    ;

    companion object {
        fun from(code: Char): MatchOutcome = entries.firstOrNull { it.code == code.uppercaseChar() }
            ?: throw IllegalArgumentException("Unknown result code '$code', expected one of ${entries.map { it.code }}")
    }
}

/**
 * Recent results, oldest first.
 *
 * [KEPT_MATCHES] are held but only the last [REQUIRED_MATCHES] score: the spreadsheet weighs
 * three matches and changing that would move every prediction. The extra two are carried for
 * the league table's form strip, which reads better over five.
 *
 * Fewer than three matches scores 0.0, mirroring the spreadsheet's `LEN(...) = 3` guard.
 * A zero form collapses the goal calculation, so [Standings] callers reject a round where
 * any team is short of matches rather than predicting from it.
 */
data class Form(val results: List<MatchOutcome>) {

    /** The matches the value is built from: the most recent [REQUIRED_MATCHES]. */
    fun scoring(): List<MatchOutcome> = results.takeLast(REQUIRED_MATCHES)

    fun value(): Double = scoring().let { if (it.size == REQUIRED_MATCHES) it.sumOf { r -> r.weight } else 0.0 }

    fun code(): String = results.map { it.code }.joinToString("")

    companion object {
        const val REQUIRED_MATCHES = 3

        /** What the form strip shows. Never fewer than [REQUIRED_MATCHES]. */
        const val KEPT_MATCHES = 5

        fun none() = Form(emptyList())

        fun from(results: List<MatchOutcome>) = Form(results.takeLast(KEPT_MATCHES))

        /** Parses the spreadsheet notation, e.g. "WDW", oldest first. */
        fun parse(codes: String) = from(codes.map { MatchOutcome.from(it) })
    }
}
