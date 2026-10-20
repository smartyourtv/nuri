package com.app.nuri.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake phrase is composed from the model's OWN vocabulary. */
class KwsKeywordsTest {

    private val tokensTxt = """
        <blank> 0
        ▁ 1
        ▁HE 2
        Y 3
        ▁I 4
        LO 5
        NA 6
        ▁THE 7
        LL 8
        O 9
    """.trimIndent()

    @Test fun composesHeyIlonaFromPieces() {
        val vocab = KwsKeywords.vocabulary(tokensTxt)
        assertTrue("blank token excluded", "<blank>" !in vocab)
        assertEquals("▁HE Y ▁I LO NA", KwsKeywords.compose("hey ilona", vocab))
    }

    @Test fun fallsBackToIlonaAlone() {
        val vocab = KwsKeywords.vocabulary(tokensTxt)
        assertEquals("▁I LO NA", KwsKeywords.compose("ilona", vocab))
    }

    @Test fun unspellablePhraseIsNullNeverGuessed() {
        val vocab = KwsKeywords.vocabulary(tokensTxt)
        assertNull(KwsKeywords.compose("hey banana", vocab))
    }

    @Test fun greedyLongestMatchWins() {
        // "hello" must prefer ▁HE + LL + O over single letters.
        val vocab = KwsKeywords.vocabulary(tokensTxt)
        assertEquals("▁HE LL O", KwsKeywords.compose("hello", vocab))
    }

    @Test fun verifiedLinesFitTheRealGigaspeechVocabulary() {
        // The real pack's pieces for the phrase (verified 2026-10-19
        // against its bpe.model): every token of both lines exists.
        val realVocab = setOf("▁HE", "Y", "▁I", "LO", "N", "A", "▁S", "RI")
        assertTrue(KwsKeywords.lineFits(KwsKeywords.HEY_ILONA_LINE, realVocab))
        assertTrue(KwsKeywords.lineFits(KwsKeywords.ILONA_LINE, realVocab))
        assertEquals("▁HE Y ▁I LO N A", KwsKeywords.HEY_ILONA_LINE)
    }
}
