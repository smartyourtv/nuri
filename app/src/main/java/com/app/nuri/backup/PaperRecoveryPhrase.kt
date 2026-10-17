package com.app.nuri.backup

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Layer 4: paper recovery phrase (drawer fallback, brief SS8).
 * 12 words drawn from a LOCAL wordlist subset (60 common, easy-to-write
 * words below — a subset, not the full BIP-39 list) plus a checksum derived
 * from SHA-256 of the phrase, so a copied-wrong word is caught on entry.
 * The phrase is shown ONCE to the caregiver to write on paper; Nuri never
 * stores, speaks, or displays it again (only its hash check is kept).
 */
object PaperRecoveryPhrase {
    val WORDS: List<String> = listOf(
        "apple","bird","blue","book","bread","chair","cloud","cup","day","dog","door","egg",
        "fish","flower","garden","green","hand","hat","home","house","key","lamp","leaf","milk",
        "moon","morning","music","night","paper","path","rain","red","river","road","rock","rose",
        "salt","sand","sea","shoe","sky","smile","snow","song","star","stone","sun","table",
        "tea","tree","warm","water","white","wind","window","winter","wood","yellow","friend","light")
    data class Generated(val words: List<String>, val checksum: String) {
        fun phraseText(): String = words.joinToString(" ")
    }
    fun checksumFor(words: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(words.joinToString(" ").toByteArray(Charsets.UTF_8))
        return digest.take(3).joinToString("") { "%02x".format(it) }
    }
    fun generate(random: SecureRandom = SecureRandom()): Generated {
        val words = List(12) { WORDS[random.nextInt(WORDS.size)] }
        return Generated(words, checksumFor(words))
    }
    fun validate(words: List<String>, expectedChecksum: String): Boolean =
        words.size == 12 && words.all { it in WORDS } && checksumFor(words) == expectedChecksum.lowercase()
    fun statusText(): String = "Ready to generate. The caregiver writes the 12 words on paper and keeps them in a drawer. Nuri keeps only a check code, never the words."
}
