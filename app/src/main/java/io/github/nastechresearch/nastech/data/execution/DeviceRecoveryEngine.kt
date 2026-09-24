package io.github.nastechresearch.nastech.data.execution

data class DeviceRecoveryAttempt<T>(
    val result: T?,
    val attempts: Int,
    val recovered: Boolean,
)

class DeviceRecoveryEngine(
    private val maxAttempts: Int = 3,
) {
    suspend fun <T> run(
        primary: suspend () -> T,
        fallbacks: List<suspend () -> T>,
        isSuccess: (T) -> Boolean,
    ): DeviceRecoveryAttempt<T> {
        val branches = ArrayList<suspend () -> T>(1 + fallbacks.size)
        branches += primary
        branches += fallbacks

        var attempts = 0
        var last: T? = null
        for (branch in branches.take(maxAttempts.coerceAtLeast(1))) {
            attempts++
            val value = runCatching { branch() }.getOrNull()
            last = value
            if (value != null && isSuccess(value)) {
                return DeviceRecoveryAttempt(value, attempts, recovered = attempts > 1)
            }
        }

        return DeviceRecoveryAttempt(last, attempts, recovered = false)
    }
}
