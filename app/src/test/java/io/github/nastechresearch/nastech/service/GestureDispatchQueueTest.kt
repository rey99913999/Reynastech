package io.github.nastechresearch.nastech.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDispatchQueueTest {

    @Test
    fun `cancelled waiter cannot let later gesture overtake active gesture`() = runBlocking {
        val queue = GestureDispatchQueue()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async {
            queue.withNextGesture {
                order += "first-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstStarted.await()

        val cancelledWaiter = launch {
            queue.withNextGesture {
                order += "cancelled-waiter-ran"
            }
        }
        // Yield until the waiting coroutine has had an opportunity to suspend on the Mutex.
        yield()
        val third = async {
            queue.withNextGesture {
                order += "third"
                thirdStarted.complete(Unit)
            }
        }

        cancelledWaiter.cancelAndJoin()
        assertFalse("third gesture must wait for the active gesture", thirdStarted.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        third.await()

        assertEquals(listOf("first-start", "first-end", "third"), order)
        assertTrue(thirdStarted.isCompleted)
    }

    @Test
    fun `cancelling active gesture releases queue for next caller`() = runBlocking {
        val queue = GestureDispatchQueue()
        val firstStarted = CompletableDeferred<Unit>()
        val nextStarted = CompletableDeferred<Unit>()

        val first = launch {
            queue.withNextGesture {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
        }
        firstStarted.await()

        val next = async {
            queue.withNextGesture {
                nextStarted.complete(Unit)
                "completed"
            }
        }

        yield()
        assertFalse(nextStarted.isCompleted)
        first.cancelAndJoin()
        assertEquals("completed", next.await())
        assertTrue(nextStarted.isCompleted)
    }
}
