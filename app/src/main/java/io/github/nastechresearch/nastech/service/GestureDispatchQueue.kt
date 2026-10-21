package io.github.nastechresearch.nastech.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cancellation-safe serialization for AccessibilityService gesture dispatches.
 *
 * A Mutex removes cancelled waiters from its wait queue and releases ownership when the
 * active caller is cancelled. This avoids ticket-counter races where a cancelled waiter
 * can advance shared state, let a later gesture overtake the active one, and strand
 * subsequent callers permanently.
 */
internal class GestureDispatchQueue {
    private val mutex = Mutex()

    suspend fun <T> withNextGesture(block: suspend () -> T): T =
        mutex.withLock { block() }
}
