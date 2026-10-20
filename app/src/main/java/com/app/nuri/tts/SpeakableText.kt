package com.app.nuri.tts

/**
 * SpeakableText — speech normalization for everything Ilona says.
 *
 * Provenance: a Kotlin port of the laptop Scout's `_speakable()` /
 * `_year_words()` (thinkpad/talk.py), which itself absorbed the
 * phone Scout's TtsTextNormalizer tables on 2026-10-18. The rules
 * exist because the owner heard the failures BY EAR: "911" read as
 * "nine hundred eleven", years read as thousands, the brain's
 * markdown **bold** spoken as "asterisk". Lessons file:
 * files/nuri-voice-lessons-applied.md (LL5).
 *
 * The speaker seam applies this centrally — TTS callers cannot
 * bypass it. Pure Kotlin, no Android types: unit-tested.
 *
 * Ilona-specific care on top of the ported rules:
 *  - Medicine UNITS expand (mcg → micrograms) but medicine NAMES
 *    are never touched — she reads the bottle as written.
 *  - Long digit runs (phone numbers) are spoken digit by digit,
 *    grouped, the way a person reads a number aloud.
 *  - Decimal doses are spelled ("0.5 mg" → "zero point five
 *    milligrams") so a dose is never mis-heard.
 */
object SpeakableText {

    private val DIGIT_WORDS = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )
    private val TENS_WORDS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )

    /** 0..99 as words ("forty-two"). */
    fun twoDigitWords(n: Int): String {
        require(n in 0..99)
        if (n < 20) return DIGIT_WORDS[n]
        val tens = TENS_WORDS[n / 10]
        val ones = n % 10
        return if (ones == 0) tens else "$tens-${DIGIT_WORDS[ones]}"
    }

    /** 1992 → "nineteen ninety-two"; 2026 → "twenty twenty-six" (laptop _year_words). */
    fun yearWords(n: Int): String {
        if (n == 2000) return "two thousand"
        if (n in 2001..2009) return "two thousand ${DIGIT_WORDS[n - 2000]}"
        if (n >= 2010) return "twenty ${twoDigitWords(n - 2000)}"
        val hi = n / 100
        val lo = n % 100
        return when {
            lo == 0 -> "${twoDigitWords(hi)} hundred"
            lo < 10 -> "${twoDigitWords(hi)} oh ${DIGIT_WORDS[lo]}"
            else -> "${twoDigitWords(hi)} ${twoDigitWords(lo)}"
        }
    }

    private val ORDINALS = mapOf(
        1 to "first", 2 to "second", 3 to "third", 4 to "fourth", 5 to "fifth",
        6 to "sixth", 7 to "seventh", 8 to "eighth", 9 to "ninth", 10 to "tenth",
        11 to "eleventh", 12 to "twelfth", 13 to "thirteenth", 14 to "fourteenth",
        15 to "fifteenth", 16 to "sixteenth", 17 to "seventeenth", 18 to "eighteenth",
        19 to "nineteenth", 20 to "twentieth", 21 to "twenty-first", 22 to "twenty-second",
        23 to "twenty-third", 24 to "twenty-fourth", 25 to "twenty-fifth",
        26 to "twenty-sixth", 27 to "twenty-seventh", 28 to "twenty-eighth",
        29 to "twenty-ninth", 30 to "thirtieth", 31 to "thirty-first"
    )

    // Abbreviation tables (phone TtsTextNormalizer, via the laptop port).
    private val ABBREVIATIONS: List<Pair<Regex, String>> = listOf(
        "mr." to "mister", "mrs." to "missus", "ms." to "miss",
        "prof." to "professor", "sr." to "senior", "jr." to "junior", "st." to "saint",
        "ave." to "avenue", "blvd." to "boulevard", "rd." to "road",
        "ln." to "lane", "ct." to "court", "pl." to "place",
        "a.m." to "ay em", "p.m." to "pee em", "am." to "ay em", "pm." to "pee em",
        "etc." to "etcetera", "i.e." to "that is", "e.g." to "for example",
        "vs." to "versus", "approx." to "approximately", "no." to "number", "tel." to "telephone",
        "mon." to "Monday", "tue." to "Tuesday", "tues." to "Tuesday",
        "wed." to "Wednesday", "thu." to "Thursday", "thur." to "Thursday",
        "thurs." to "Thursday", "fri." to "Friday", "sat." to "Saturday", "sun." to "Sunday",
        "jan." to "January", "feb." to "February", "mar." to "March",
        "apr." to "April", "jun." to "June", "jul." to "July",
        "aug." to "August", "sep." to "September", "sept." to "September",
        "oct." to "October", "nov." to "November", "dec." to "December"
    ).map { (abbr, expansion) ->
        Regex("(?<![A-Za-z0-9])" + Regex.escape(abbr) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE) to expansion
    }

    // Small, reviewed respelling table (phone → laptop → here). Names and
    // medicine names are NEVER added here — those stay verbatim (V18).
    private val RESPELLINGS: List<Pair<Regex, String>> = listOf(
        "sql" to "sequel", "api" to "ay pee eye", "url" to "you are ell", "gui" to "gooey"
    ).map { (word, spoken) -> Regex("\\b" + Regex.escape(word) + "\\b", RegexOption.IGNORE_CASE) to spoken }

    /** Medicine units that expand after a number; the NAME never changes. */
    private val UNIT_EXPANSIONS = listOf(
        Regex("(\\d+(?:\\.\\d+)?)\\s*mcg\\b", RegexOption.IGNORE_CASE) to "$1 micrograms",
        Regex("(\\d+(?:\\.\\d+)?)\\s*mg\\b", RegexOption.IGNORE_CASE) to "$1 milligrams",
        Regex("(\\d+(?:\\.\\d+)?)\\s*ml\\b", RegexOption.IGNORE_CASE) to "$1 milliliters",
        Regex("(\\d+(?:\\.\\d+)?)\\s*g\\b", RegexOption.IGNORE_CASE) to "$1 grams"
    )

    fun normalize(raw: String): String {
        var text = raw
        // Markdown is for eyes, not ears — strip BEFORE anything else so
        // no rule below can resurrect it; digit*digit is multiplication.
        text = text.replace(Regex("(\\d)\\s*\\*\\s*(\\d)"), "$1 times $2")
        text = text.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        text = text.replace(Regex("__([^_]+)__"), "$1")
        text = text.replace(Regex("\\*([^*]+)\\*"), "$1")
        text = text.replace(Regex("`([^`]+)`"), "$1")
        text = text.replace(Regex("(?m)^#{1,6}\\s+"), "")
        text = text.replace("*", "").replace("`", "")
        // The emergency number, digit by digit — never "nine hundred eleven".
        text = text.replace(Regex("\\b911\\b"), "nine one one")
        // "dr." by context (phone rule): doctor before a name, drive otherwise.
        text = text.replace(Regex("\\b[Dd][Rr]\\.\\s+(?=[A-Z])"), "doctor ")
        text = text.replace(Regex("\\b[Dd][Rr]\\."), "drive")
        for ((pattern, expansion) in ABBREVIATIONS) {
            // An abbreviation at the very END of the text carries the
            // sentence's final period — keep it, or the sentence
            // splitter (and the listener) loses the sentence boundary.
            text = pattern.replace(text) { m ->
                if (m.range.last == text.lastIndex) "$expansion." else expansion
            }
        }
        // Symbols.
        text = text.replace(Regex("\\s&\\s"), " and ")
        text = text.replace(Regex("\\s@\\s"), " at ")
        text = text.replace("°C", " degrees Celsius ")
        text = text.replace("°F", " degrees Fahrenheit ")
        text = text.replace("%", " percent ")
        text = text.replace("°", " degrees ")
        for ((pattern, spoken) in RESPELLINGS) text = pattern.replace(text, spoken)
        text = numberPolish(text)
        // Years LAST of the number rules but BEFORE digit-runs would be
        // wrong order — years are 4 digits, digit-runs need ≥7, so they
        // cannot collide; years run here exactly as the laptop orders them.
        text = text.replace(Regex("\\b(19\\d\\d|20\\d\\d)\\b")) { m -> yearWords(m.groupValues[1].toInt()) }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** Dose decimals, medicine units, ordinals, and long digit runs. */
    private fun numberPolish(input: String): String {
        var text = input
        // Units FIRST, while the number is still numeric (including
        // decimals): "0.5 mg" → "0.5 milligrams"; the decimal rule
        // below then spells the number: "zero point five milligrams".
        for ((pattern, replacement) in UNIT_EXPANSIONS) text = pattern.replace(text, replacement)
        text = text.replace(Regex("\\b(\\d+)\\.(\\d+)\\b")) { m ->
            val whole = m.groupValues[1].toInt()
            val frac = m.groupValues[2].map { c -> DIGIT_WORDS[c - '0'] }.joinToString(" ")
            "${twoDigitWords(whole)} point $frac"
        }
        // Ordinals for dates and sequences ("October 3rd" → "October third").
        text = text.replace(Regex("\\b(\\d{1,2})(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)) { m ->
            ORDINALS[m.groupValues[1].toInt()] ?: m.value
        }
        // Long digit runs (phone numbers, account numbers): digit by
        // digit, in the groups as written — "555-234-5678" is spoken
        // "five five five, two three four, five six seven eight".
        text = text.replace(Regex("\\b\\d[\\d\\s().-]{5,}\\d\\b")) { m ->
            val groups = m.value.split(Regex("[\\s().-]+")).filter { it.isNotEmpty() }
            val digitsOnly = groups.joinToString("")
            if (digitsOnly.length >= 7 && groups.all { g -> g.all { it.isDigit() } }) {
                groups.joinToString(", ") { g -> g.map { c -> DIGIT_WORDS[c - '0'] }.joinToString(" ") }
            } else m.value
        }
        return text
    }

    /**
     * Split into sentences for pipelined synthesis (LL3): synthesize
     * sentence N+1 while N plays. Splits on sentence-final punctuation,
     * keeping it attached; a trailing fragment is its own sentence.
     */
    fun sentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            if ((c == '.' || c == '!' || c == '?') && (i + 1 >= text.length || text[i + 1] == ' ')) {
                val s = current.toString().trim()
                if (s.isNotEmpty()) out += s
                current.clear()
                if (i + 1 < text.length && text[i + 1] == ' ') i++
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }
}
