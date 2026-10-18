package com.app.nuri.config

/**
 * A pending conversational suggestion for the senior, in plain words.
 * Filled by the flavor hook; in the public flavor there is never one.
 * [kind] is one of FONT_STEP / EMOTION / GAMES; [value] is the suggested
 * value's stored name.
 */
data class PendingSuggestion(val kind: String, val value: String, val line: String)
