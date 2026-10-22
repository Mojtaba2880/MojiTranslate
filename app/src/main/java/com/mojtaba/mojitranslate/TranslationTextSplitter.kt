package com.mojtaba.mojitranslate

/**
 * Splits long OCR strings into context-preserving chunks at punctuation and then word boundaries.
 * Short strings are returned unchanged; original order and all non-whitespace content are preserved.
 */
object TranslationTextSplitter {
    fun split(text: String, maxWords: Int = 18, maxChars: Int = 420): List<String> {
        val source = text.trim()
        if (source.isEmpty()) return emptyList()
        require(maxWords >= 1 && maxChars >= 1)
        if (source.length <= maxChars && source.split(Regex("\\s+")).size <= maxWords) {
            return listOf(source)
        }
        val sentences = source.split(Regex("(?<=[.!?;:])\\s+|(?<=[。！？；：，、])\\s*"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        for (sentence in sentences) {
            val words = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var current = mutableListOf<String>()
            var length = 0
            for (word in words) {
                if (current.isNotEmpty() && (current.size >= maxWords || length + 1 + word.length > maxChars)) {
                    chunks.add(current.joinToString(" "))
                    current = mutableListOf()
                    length = 0
                }
                if (word.length > maxChars) {
                    if (current.isNotEmpty()) {
                        chunks.add(current.joinToString(" "))
                        current = mutableListOf()
                        length = 0
                    }
                    word.chunked(maxChars).forEach { chunks.add(it) }
                } else {
                    current.add(word)
                    length += word.length + if (current.size > 1) 1 else 0
                }
            }
            if (current.isNotEmpty()) chunks.add(current.joinToString(" "))
        }
        return chunks.ifEmpty { listOf(source) }
    }
}