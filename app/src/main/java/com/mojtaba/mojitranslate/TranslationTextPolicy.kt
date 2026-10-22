package com.mojtaba.mojitranslate

/**
 * Conservative protection for values that should remain literal in translated game/UI text.
 * Kept Android-independent so identifier rules can be regression-tested on the JVM.
 */
object TranslationTextPolicy {
    fun shouldPreserveIdentifier(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.length > 48) return false
        if (value.matches(Regex("""[\d\s.,:%/+()#_-]+"""))) return true
        if (value.matches(Regex("""(?i)(?:ID|UID|HP|MP|XP|FPS|LV|LVL)[\s:#_-]*[A-Z0-9._-]+"""))) return true
        val letters = value.count { it.isLetter() }
        val digits = value.count { it.isDigit() }
        val separators = value.count { it in "._-/#:" }
        return digits >= 3 && letters > 0 &&
            (digits + separators).toFloat() / value.length >= 0.55f
    }

    /** Rejoins translated chunks without inserting spaces before closing punctuation. */
    fun joinTranslatedChunks(chunks: List<String>): String {
        val result = StringBuilder()
        for (chunk in chunks) {
            val part = chunk.trim()
            if (part.isEmpty()) continue
            if (result.isNotEmpty() && part.first() !in ".,!?،؛:。！？；：)]}»") {
                result.append(' ')
            }
            result.append(part)
        }
        return result.toString()
    }
}
