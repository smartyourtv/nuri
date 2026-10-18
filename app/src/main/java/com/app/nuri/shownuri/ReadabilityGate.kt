package com.app.nuri.shownuri

/**
 * The gibberish gate (v0.3.2 — Shawn's on-device test, 2026-10-18).
 *
 * On v0.3.1, Show Nuri presented raw OCR fragments from a photo of a
 * computer screen — "610 bytor) 24149239 bytes) (27768 byten)… Shitt" —
 * as "everything it says, as written." OCR of a photographed screen
 * (or any glare/angle shot) produces exactly this debris: number runs,
 * bracket-laden fragments, vowel-less shards. A senior cannot tell
 * debris from content, so Nuri must not present it as a reading.
 *
 * The gate scores OCR text for readability with three signals:
 *  1. DICTIONARY RATIO — the share of word tokens found in a core
 *     English vocabulary (letters, medicine labels, food packages —
 *     the texts Show Nuri exists to read), with common suffixes
 *     stripped (bills→bill, charged→charge).
 *  2. JUNK DENSITY — the share of tokens carrying digits, stray
 *     brackets/symbols, or no letters at all. Real documents use
 *     numbers sparingly and punctuation attached to words; OCR debris
 *     is dominated by them.
 *  3. LINE STRUCTURE — text is scored per line so clean lines can be
 *     kept (partial credit) while garbage lines are dropped, never
 *     dumped raw.
 *
 * Verdicts: READABLE (present it), PARTIAL (present only the clean
 * lines and say the rest wasn't clear), NOT_READABLE (present
 * nothing; coach a retake instead). Thresholds are conservative on
 * purpose: doubt goes to the senior's side — a retake offer, never a
 * confident reading of noise.
 */
object ReadabilityGate {

    enum class Verdict { READABLE, PARTIAL, NOT_READABLE }

    enum class TokenKind { WORD, UNKNOWN_ALPHA, NUMERIC, JUNK }

    data class LineScore(
        val line: String,
        val tokens: Int,
        val words: Int,
        val junk: Int,
        val wordRatio: Double,
        val junkRatio: Double,
        val clean: Boolean,
    )

    /**
     * A numeric token is DEBRIS only when it is a long pure digit run
     * (5+ digits, no separators) — file sizes and serial fragments,
     * the signature of screen-photo OCR. Short numbers and formatted
     * ones ("10", "3", "2027", "$250.00") are content: real labels
     * and letters are full of them.
     */
    fun isDebrisNumeric(raw: String): Boolean {
        val core = raw.trim().trim { it in EDGE_PUNCT }
        return core.matches(Regex("\\d{5,}"))
    }

    fun isJunkToken(raw: String): Boolean = when (classifyToken(raw)) {
        TokenKind.JUNK -> true
        TokenKind.NUMERIC -> isDebrisNumeric(raw)
        else -> false
    }

    data class Assessment(
        val verdict: Verdict,
        val wordRatio: Double,
        val junkRatio: Double,
        val lineScores: List<LineScore>,
        val cleanLines: List<String>,
        val droppedLineCount: Int,
    )

    // A token is "clean" only if, after trimming sentence punctuation
    // from its edges, it is purely letters (plus inner ' or -). A token
    // like "bytor)" keeps its bracket in the core and is JUNK — that
    // attached-bracket pattern is the fingerprint of screen-photo OCR.
    private val EDGE_PUNCT = ".,;:!?\"'()[]{}<>".toSet()

    fun classifyToken(raw: String): TokenKind {
        val t = raw.trim()
        if (t.isEmpty()) return TokenKind.JUNK
        if (t.any { it.isDigit() }) return TokenKind.NUMERIC
        val core = t.trim { it in EDGE_PUNCT }
        if (core.isEmpty()) return TokenKind.JUNK
        if (core.any { !it.isLetter() && it != '\'' && it != '-' }) return TokenKind.JUNK
        if (core != t && t.any { it in "()[]{}<>" }) return TokenKind.JUNK
        if (core.length == 1) {
            return if (core.lowercase() in setOf("a", "i")) TokenKind.WORD else TokenKind.UNKNOWN_ALPHA
        }
        return if (isDictionaryWord(core)) TokenKind.WORD else TokenKind.UNKNOWN_ALPHA
    }

    /** Dictionary lookup with light suffix stripping (plurals, -ed, -ing, -ly…). */
    fun isDictionaryWord(raw: String): Boolean {
        val w = raw.lowercase().trim('\'')
        if (w in CoreWords.WORDS) return true
        val candidates = mutableListOf<String>()
        if (w.endsWith("'s")) candidates += w.dropLast(2)
        if (w.endsWith("ies") && w.length > 4) candidates += w.dropLast(3) + "y"
        if (w.endsWith("es") && w.length > 3) candidates += w.dropLast(2)
        if (w.endsWith("s") && w.length > 3) candidates += w.dropLast(1)
        if (w.endsWith("ed") && w.length > 4) { candidates += w.dropLast(2); candidates += w.dropLast(1) }
        if (w.endsWith("ing") && w.length > 5) { candidates += w.dropLast(3); candidates += w.dropLast(3) + "e" }
        if (w.endsWith("ly") && w.length > 4) candidates += w.dropLast(2)
        if (w.endsWith("er") && w.length > 4) candidates += w.dropLast(2)
        if (w.endsWith("est") && w.length > 5) candidates += w.dropLast(3)
        return candidates.any { it in CoreWords.WORDS }
    }

    fun scoreLine(line: String): LineScore {
        val tokens = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return LineScore(line, 0, 0, 0, 0.0, 0.0, false)
        var words = 0; var junk = 0
        tokens.forEach { tok ->
            if (classifyToken(tok) == TokenKind.WORD) words++
            if (isJunkToken(tok)) junk++
        }
        val wordRatio = words.toDouble() / tokens.size
        val junkRatio = junk.toDouble() / tokens.size
        // A line reads clean when most of it is recognizable words and
        // little of it is debris. A line with NO debris and at least
        // half recognizable words also counts — label lines carry
        // content numbers and product names ("Lisinopril 10 mg
        // tablets", "Refills: 3"). A lone recognizable word (a
        // heading) counts too.
        val clean = (tokens.size >= 2 && wordRatio >= 0.6 && junkRatio <= 0.34) ||
            (junk == 0 && words >= 1 && wordRatio >= 0.5) ||
            (tokens.size == 1 && words == 1)
        return LineScore(line, tokens.size, words, junk, wordRatio, junkRatio, clean)
    }

    fun assess(text: String): Assessment {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Assessment(Verdict.NOT_READABLE, 0.0, 0.0, emptyList(), emptyList(), 0)
        val scores = lines.map { scoreLine(it) }
        val totalTokens = scores.sumOf { it.tokens }
        val totalWords = scores.sumOf { it.words }
        val junkCount = scores.sumOf { it.junk }
        val wordRatio = if (totalTokens > 0) totalWords.toDouble() / totalTokens else 0.0
        val junkRatio = if (totalTokens > 0) junkCount.toDouble() / totalTokens else 0.0
        val cleanLines = scores.filter { it.clean }.map { it.line }
        val cleanShare = if (totalTokens > 0) scores.filter { it.clean }.sumOf { it.tokens }.toDouble() / totalTokens else 0.0
        val verdict = when {
            // READABLE means: present it ALL. That additionally requires
            // that essentially every line earned its place — a mostly
            // clean text with one garbage line is PARTIAL, so the
            // garbage line is dropped instead of read aloud as content.
            totalTokens >= 4 && wordRatio >= 0.55 && junkRatio <= 0.35 &&
                (cleanLines.size == scores.size || cleanShare >= 0.85) -> Verdict.READABLE
            cleanLines.size >= 2 && cleanShare >= 0.30 -> Verdict.PARTIAL
            cleanLines.size == 1 && scores.size == 1 && wordRatio >= 0.6 -> Verdict.PARTIAL
            else -> Verdict.NOT_READABLE
        }
        return Assessment(verdict, wordRatio, junkRatio, scores, cleanLines, scores.size - cleanLines.size)
    }

    /** Coaching when nothing (or not enough) could be read. Plain, kind, one fix at a time. */
    fun notReadableLine(screenLike: Boolean): String =
        "I couldn't read that clearly — the words came out broken, and I won't guess at them. " +
            "Let's try again: move closer, fill the whole picture with the page, and hold steady." +
            if (screenLike) " Words on a TV or computer screen are hard to read from a photo — getting closer helps a lot." else ""

    fun partialNote(droppedLineCount: Int): String =
        "That's everything I could read clearly — the rest of the photo wasn't clear enough, and I won't guess at it. A closer photo may help with the rest."

    /**
     * Does this failed read look like a photograph OF a screen? Signals:
     * a screen-ish vision label, or screen vocabulary in the debris
     * (byte counts and installer words are what screens show).
     */
    fun looksLikeScreen(ocrText: String, labelTexts: List<String>): Boolean {
        val screenLabels = setOf("screenshot", "display", "monitor", "television", "computer", "electronics", "technology", "screen")
        if (labelTexts.any { it.lowercase() in screenLabels }) return true
        val t = ocrText.lowercase()
        return listOf("bytes", "installing", "download", ".apk", "setup", "loading").any { it in t }
    }
}

/**
 * Core English vocabulary for the gate — the words of letters, bills,
 * medicine labels, food packages, and everyday speech (the texts Show
 * Nuri reads). Deliberately small and auditable: a word NOT being here
 * never blocks a reading by itself (ratios decide), it only lowers the
 * dictionary signal.
 */
object CoreWords {
    val WORDS: Set<String> = """
    a about above across act add address adult after again against age ago ahead aid air all allow almost alone along already also always am an and another any anyone anything around arrive as ask asked at away
    baby back bad bag balance ball bank be because become bed been before began begin behind believe below best better between bill bills bit blood body book box boy bring brother business but buy by
    call called calories can cannot car card care careful carry case center certain change charged charges check child children city claim class close cold come company complete computer contain copy cost could country course cover cup current cut
    daily date day days dear death did die different directions do doctor does dog dollar dollars done door dose down drink drive during
    each early eat eight either emergency end enough even evening every exactly expires eye eyes
    face fact fall family far fast father fat feel feet few fill final find fine finger first fish five floor follow food for form found four free from front full
    get girl give go goes going good got great group grow
    had hair half hand happen happy hard has have he head health hear heart help her here high him his home hope hospital hour house how however husband
    i if important in information inside instead into is it its
    just keep kind king know
    label large last later learn leave left leg less let letter life light like line list little live long look lot love low lower
    made mail main make man many may me means medical medicare medicine member milk money month more morning most mother mouth move much must my
    name near need never new next night no normal north not note notice now number
    of off office often old on once one only open or oral order other our out over own
    package page pain paper parents part patient pay people per perhaps person phone photo place plan please point pounds power prescribed print problem put
    questions quickly quite
    reach read ready really reason refill refills remember report rest result right room run
    said same save saw say school second see seem send sentence serve service set seven several she short should show side sign simple since sister six sleep small so some something sometimes son song soon sound south space speak special stand start state still stop store story street such summary sure surface system
    table take talk teach team tell ten than thank that the their them then there these they thing think third this those though thought three through time to today together too took total toward town tree try turn twice two
    under unit until up us use used usually
    very visit voice
    walk want war warm was watch water way we week well went were west what when where which while white who why wife will window with without word work world would write written
    year you your yours yourself
    take tablet tablets capsule capsules twice bedtime warning discard temperature directed needed fever contains ingredients nutrition serving refrigerated freeze best sell keep directed chew swallow dissolve tongue skin cream apply area times hour hours week weeks month months morning evening night daily dose dosage strength born died alive married single window
    danger poison control center emergency ambulance fire police help rescue
    television remote control channel volume button screen battery batteries light lamp clock watch glasses keys wallet purse bag coat hat door window chair table bed kitchen bathroom garden yard street store shop market church school park library post office bank pharmacy grocery
    january february march april may june july august september october november december monday tuesday wednesday thursday friday saturday sunday
    one two three four five six seven eight nine ten eleven twelve twenty thirty forty fifty hundred thousand first second third
    is are was were be been being have has had do does did will would shall should can could may might must
    thank thanks please sorry yes no okay well hello goodbye
    appointment insurance account amount owe owed premium deductible clinic nurse symptom symptoms cough flu vaccine results pressure kidney bone mg mcg ml
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
}
