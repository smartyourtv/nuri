package com.app.nuri.games

/** Big-target matching game logic — pure Kotlin. Enjoyment framing only:
 *  never therapy, never testing. NO timers, NO fail state, NO score pressure:
 *  a wrong tap just invites another try, and there is nothing to lose. */
data class ShapeOption(val id: String, val label: String, val symbol: String)
object MatchingGameLogic {
    val SHAPES: List<ShapeOption> = listOf(
        ShapeOption("circle","Circle","\u25CF"), ShapeOption("square","Square","\u25A0"),
        ShapeOption("triangle","Triangle","\u25B2"), ShapeOption("star","Star","\u2605"),
        ShapeOption("heart","Heart","\u2665"), ShapeOption("diamond","Diamond","\u25C6"))
    data class Round(val target: ShapeOption, val options: List<ShapeOption>)
    fun newRound(roundIndex: Int): Round {
        val target = SHAPES[roundIndex % SHAPES.size]
        val others = SHAPES.filter { it.id != target.id }.drop(roundIndex % 3).take(2)
        val options = (others + target).let { base ->
            // Deterministic gentle shuffle so rounds vary without randomness in tests.
            if (roundIndex % 2 == 0) base.reversed() else base
        }
        return Round(target, options)
    }
    fun feedbackFor(tapped: ShapeOption, round: Round): String =
        if (tapped.id == round.target.id) "That's the one. Lovely." else "Not quite — try another one. Take your time."
    fun isCorrect(tapped: ShapeOption, round: Round): Boolean = tapped.id == round.target.id
}
