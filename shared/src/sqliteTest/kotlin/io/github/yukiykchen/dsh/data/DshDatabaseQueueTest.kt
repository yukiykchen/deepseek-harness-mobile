package io.github.yukiykchen.dsh.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DshDatabaseQueueTest {
    @Test
    fun readsObserveEveryEarlierWriteInSubmissionOrder() {
        val queue = DshDatabaseQueue(onAsyncFailure = { throw it })
        try {
            val writes = mutableListOf<Int>()
            repeat(200) { value ->
                queue.submit { writes.add(value) }
                assertEquals(value + 1, queue.call { writes.size })
            }
            assertEquals((0 until 200).toList(), queue.call { writes.toList() })
        } finally {
            queue.close()
        }
    }

    @Test
    fun closeDrainsWritesBeforeReleasingResourcesAndRejectsNewTasks() {
        val events = mutableListOf<String>()
        val queue = DshDatabaseQueue(onAsyncFailure = { throw it }, onClosed = { events.add("closed") })
        repeat(100) { value -> queue.submit { events.add("write-$value") } }
        queue.close()
        queue.close()
        assertEquals((0 until 100).map { "write-$it" } + "closed", events)
        assertFailsWith<IllegalStateException> { queue.submit {} }
        assertFailsWith<IllegalStateException> { queue.call {} }
    }

    @Test
    fun failedOperationsDoNotPoisonTheNextTask() {
        val failures = mutableListOf<String>()
        val queue = DshDatabaseQueue(onAsyncFailure = { failures.add(it.message.orEmpty()) })
        try {
            queue.submit { error("async failure") }
            assertFailsWith<IllegalArgumentException> { queue.call { throw IllegalArgumentException("sync failure") } }
            assertEquals("ok", queue.call { "ok" })
            assertEquals(listOf("async failure"), failures)
        } finally {
            queue.close()
        }
    }

    @Test
    fun closeReportsResourceReleaseFailure() {
        val queue = DshDatabaseQueue(onAsyncFailure = {}, onClosed = { error("close failed") })
        assertFailsWith<IllegalStateException> { queue.close() }
    }
}
