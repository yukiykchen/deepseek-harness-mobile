package io.github.yukiykchen.dsh.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Native SQLite operations run in submission order, including reads and shutdown. */
internal class DshDatabaseQueue(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onAsyncFailure: (Throwable) -> Unit,
    private val onClosed: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val tasks = Channel<() -> Unit>(Channel.UNLIMITED)
    private val drained = CompletableDeferred<Unit>()
    private val worker = scope.launch {
        try {
            for (task in tasks) task()
        } finally {
            try {
                onClosed()
                drained.complete(Unit)
            } catch (error: Throwable) {
                drained.completeExceptionally(error)
            }
        }
    }

    fun <T> call(block: () -> T): T {
        val result = CompletableDeferred<T>()
        enqueue {
            try {
                result.complete(block())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return runBlocking { result.await() }
    }

    fun submit(block: () -> Unit) = submitReporting(block, null)

    fun submitReporting(block: () -> Unit, onFailure: ((Throwable) -> Unit)?) {
        enqueue {
            try {
                block()
            } catch (error: Throwable) {
                // A diagnostic callback must not terminate the database worker.
                if (onFailure == null) runCatching { onAsyncFailure(error) }
                else runCatching { onFailure.invoke(error) }
            }
        }
    }

    private fun enqueue(task: () -> Unit) {
        check(tasks.trySend(task).isSuccess) { "Database queue is closed" }
    }

    fun close() {
        tasks.close()
        try {
            runBlocking { worker.join(); drained.await() }
        } finally {
            scope.cancel()
        }
    }
}
