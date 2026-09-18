package io.github.nastechresearch.nastech.data.execution

object ToolOutputPreprocessor {
    private val uiSignal = Regex(
        """(?i)(node|className|resourceId|content-desc|contentDescription|clickable|editable|bounds|text=)"""
    )
    private val lowValueLine = Regex(
        """(?i)^(?:index|hash|timestamp|depth|parent|windowId)\s*[:=]"""
    )

    fun preprocess(text: String, maxChars: Int = 8_000): String {
        if (text.length <= maxChars) return text
        if (looksLikeUiTree(text)) return compactUiTree(text, maxChars)
        return compactGeneric(text, maxChars)
    }

    fun looksLikeUiTree(text: String): Boolean {
        val sample = text.take(16_000)
        return uiSignal.findAll(sample).count() >= 4
    }

    fun compactUiTree(text: String, maxChars: Int = 8_000): String {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { lowValueLine.matches(it) }
            .distinct()
            .filter { uiSignal.containsMatchIn(it) || it.length < 160 }
            .toList()

        val prioritized = lines.sortedByDescending { scoreUiLine(it) }
        val output = StringBuilder()
        for (line in prioritized) {
            if (output.length + line.length + 1 > maxChars) continue
            output.append(line).append('\n')
        }
        return output.toString().trim().ifBlank { text.take(maxChars) }
    }

    fun compactGeneric(text: String, maxChars: Int = 8_000): String {
        val output = StringBuilder()
        for (line in text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.distinct()) {
            if (output.length + line.length + 1 > maxChars) break
            output.append(line).append('\n')
        }
        return output.toString().trim().ifBlank { text.take(maxChars) }
    }

    private fun scoreUiLine(line: String): Int {
        val lower = line.lowercase()
        var score = 0
        if ("button" in lower || "clickable" in lower) score += 5
        if ("input" in lower || "edittext" in lower || "editable" in lower) score += 5
        if ("search" in lower) score += 4
        if ("text=" in lower || "content" in lower) score += 2
        if ("bounds" in lower) score += 1
        return score
    }
}
