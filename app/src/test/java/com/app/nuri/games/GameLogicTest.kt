package com.app.nuri.games
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class GameLogicTest {
    @Test fun roundContainsTargetAndThreeOptions() {
        val r = MatchingGameLogic.newRound(0); assertEquals(3, r.options.size); assertTrue(r.options.contains(r.target))
    }
    @Test fun wrongTapIsGentleNotFailure() {
        val r = MatchingGameLogic.newRound(0); val wrong = r.options.first { it.id != r.target.id }
        assertTrue(MatchingGameLogic.feedbackFor(wrong, r).contains("try another"))
    }
}
