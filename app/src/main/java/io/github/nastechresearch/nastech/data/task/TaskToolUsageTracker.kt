package io.github.nastechresearch.nastech.data.task

import java.util.concurrent.ConcurrentHashMap

data class TaskToolUsage(
    val toolName: String,
    val status: Status,
    val decision: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Status {
        PENDING_APPROVAL,
        ALLOWED_FOR_TASK,
        COMPLETED,
        DENIED,
        FAILED,
    }
}

/**
 * Lightweight in-task transparency state. This is not an audit store; durable execution
 * telemetry remains the source of historical truth. The map exists only to let the active
 * Task UI show the latest tool state without introducing a second audit framework.
 */
object TaskToolUsageTracker {
    private val byTask = ConcurrentHashMap<String, MutableMap<String, TaskToolUsage>>()

    fun record(taskId: String?, usage: TaskToolUsage) {
        if (taskId.isNullOrBlank()) return
        byTask.computeIfAbsent(taskId) { ConcurrentHashMap() }[usage.toolName] = usage
    }

    fun snapshot(taskId: String?): List<TaskToolUsage> =
        if (taskId.isNullOrBlank()) emptyList()
        else byTask[taskId]?.values?.sortedByDescending { it.timestamp }.orEmpty()

    fun clearTask(taskId: String) {
        byTask.remove(taskId)
    }
}
