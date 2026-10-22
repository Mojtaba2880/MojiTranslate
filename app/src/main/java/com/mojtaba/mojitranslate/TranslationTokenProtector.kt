package com.mojtaba.mojitranslate

/** Protects numbers and compact alphanumeric IDs when a mixed-language line is translated online. */
object TranslationTokenProtector {
    private val tokenPattern = Regex(
        """(?i)(?:\b(?:UID|HP|MP|XP|FPS|LVL?|ID)\s*[:#_-]?\s*[A-Z0-9][A-Z0-9._/-]*\b|\b(?=[A-Z0-9._-]*[A-Z])(?=[A-Z0-9._-]*\d)[A-Z0-9]+(?:[._-][A-Z0-9]+)*\b|(?<![\p{L}\p{N}])[+-]?\d+(?:[.,]\d+)*(?:%|x)?(?![\p{L}\p{N}]))"""
    )

    data class ProtectedText internal constructor(
        val text: String,
        private val tokens: List<Pair<String, String>>
    ) {
        fun restore(translatedText: String): String {
            var restored = translatedText
            for ((placeholder, original) in tokens) {
                val pattern = Regex(Regex.escape(placeholder), RegexOption.IGNORE_CASE)
                val occurrences = pattern.findAll(restored).count()
                check(occurrences == 1) {
                    "Translation altered a protected identifier placeholder"
                }
                restored = restored.replace(placeholder, original, ignoreCase = true)
            }
            return restored
        }

        val protectedTokenCount: Int get() = tokens.size
    }

    fun protect(source: String): ProtectedText {
        val matches = tokenPattern.findAll(source).toList()
        if (matches.isEmpty()) return ProtectedText(source, emptyList())
        val result = StringBuilder(source.length + matches.size * 16)
        val tokens = mutableListOf<Pair<String, String>>()
        var cursor = 0
        for ((index, match) in matches.withIndex()) {
            result.append(source, cursor, match.range.first)
            val placeholder = "ZXQMOJITOKEN" + index + "XZQ"
            result.append(placeholder)
            tokens.add(placeholder to match.value)
            cursor = match.range.last + 1
        }
        result.append(source, cursor, source.length)
        return ProtectedText(result.toString(), tokens)
    }
}
