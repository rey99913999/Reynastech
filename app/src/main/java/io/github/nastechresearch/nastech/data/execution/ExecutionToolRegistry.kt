package io.github.nastechresearch.nastech.data.execution

import me.rerere.ai.core.Tool

class ExecutionToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun find(name: String): Tool? = byName[name]

    fun names(): Set<String> = byName.keys

    fun compactIndex(maxChars: Int = 6_000): String {
        val lines = buildList {
            byName.values.sortedBy { it.name }.forEach { tool ->
                val description = tool.description
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(180)
                add("- ${tool.name}: ${description}")
            }
        }

        val result = StringBuilder("Available tools (compact planner index):\n")
        for (line in lines) {
            if (result.length + line.length + 1 > maxChars) break
            result.append(line).append('\n')
        }
        return result.toString().trim()
    }
}
