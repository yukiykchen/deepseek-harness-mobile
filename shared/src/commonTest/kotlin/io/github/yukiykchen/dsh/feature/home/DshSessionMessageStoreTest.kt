package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.collection.ObservableList
import io.github.yukiykchen.dsh.data.DshLocalStore
import io.github.yukiykchen.dsh.data.DshStoreCommand
import io.github.yukiykchen.dsh.data.DshStoreCommandData
import io.github.yukiykchen.dsh.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshSessionMessageStoreTest {
    @Test
    fun readUsesScopeCapturedBeforeBackgroundDispatch() {
        val fixture = Fixture()
        try {
            val rows = fixture.messages.state("s1")
            fixture.scopeId = "ssh:second"
            fixture.runReadAndCallback()
            assertEquals(listOf("relay:first"), fixture.disk.readScopes)
            assertTrue(rows.isEmpty())
            assertFalse(fixture.messages.isReady("s1"))
            assertEquals(0, fixture.loaded)
        } finally {
            fixture.messages.close()
        }
    }

    @Test
    fun deletedAndRecreatedSessionRejectsOldReadAndDoesNotCancelNewRead() {
        val fixture = Fixture()
        try {
            fixture.messages.state("s1")
            fixture.messages.forget("s1")
            val newRows = fixture.messages.state("s1")
            fixture.worker.runNext()
            fixture.callbacks.removeAt(0)()
            assertTrue(newRows.isEmpty())
            assertFalse(fixture.messages.isReady("s1"))
            fixture.runReadAndCallback()
            assertEquals("cached", newRows.single().content)
            assertTrue(fixture.messages.isReady("s1"))
            assertEquals(1, fixture.loaded)
        } finally {
            fixture.messages.close()
        }
    }

    @Test
    fun callbackAlreadyPostedBeforeCloseCannotApplyOrNotify() {
        val fixture = Fixture()
        val rows = fixture.messages.state("s1")
        fixture.worker.runNext()
        fixture.messages.close()
        fixture.callbacks.removeAt(0)()
        assertTrue(rows.isEmpty())
        assertEquals(0, fixture.loaded)
    }

    @Test
    fun hostRowsArrivingBeforeDiskSnapshotWin() {
        val fixture = Fixture()
        try {
            val rows = fixture.messages.state("s1")
            rows.add(DshMessage("host", DshMessageRole.ASSISTANT, "fresh"))
            fixture.runReadAndCallback()
            assertEquals("fresh", rows.single().content)
            assertEquals(1, fixture.loaded)
        } finally {
            fixture.messages.close()
        }
    }

    @Test
    fun noDiskStoreStillMarksIndividualSessionReady() {
        val fixture = Fixture(withDisk = false)
        try {
            fixture.messages.state("s1")
            assertTrue(fixture.messages.isReady("s1"))
            assertEquals(1, fixture.ready)
        } finally {
            fixture.messages.close()
        }
    }

    private class Fixture(withDisk: Boolean = true) {
        var scopeId = "relay:first"
        var loaded = 0
        var ready = 0
        val worker = ManualDispatcher()
        val callbacks = mutableListOf<() -> Unit>()
        val disk = FakeStore()
        val messages = DshSessionMessageStore(
            scope = object : PagerScope { override val pagerId = "test-page" },
            localStore = { if (withDisk) disk else null },
            connectionId = { scopeId },
            isBlank = { false },
            listener = object : DshSessionMessageStore.Listener {
                override fun onDiskLoaded(sessionId: String, preload: Boolean, scrollToEndAfterLoad: Boolean) { loaded++ }
                override fun onReady(sessionId: String) { ready++ }
            },
            readDispatcher = worker,
            postToPage = { callbacks.add(it) },
        )

        fun runReadAndCallback() {
            worker.runNext()
            callbacks.removeAt(0)()
        }
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runNext() { tasks.removeAt(0).run() }
    }

    private class FakeStore : DshLocalStore {
        val readScopes = mutableListOf<String>()
        override fun loadMessages(scopeId: String, sessionId: String): List<DshMessage> {
            readScopes.add(scopeId)
            return listOf(DshMessage("disk", DshMessageRole.ASSISTANT, "cached"))
        }
        override fun loadLastConnectionMode() = DshConnectionMode.RELAY
        override fun saveLastConnectionMode(mode: DshConnectionMode) = Unit
        override fun loadRemoteProfile(): DshRemoteProfile? = null
        override fun saveRemoteProfile(profile: DshRemoteProfile) = Unit
        override fun loadRelayProfile(): DshRelayProfile? = null
        override fun saveRelayProfile(profile: DshRelayProfile) = Unit
        override fun clearRelayProfile() = Unit
        override fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile) = false
        override fun loadSessions(scopeId: String) = emptyList<DshSession>()
        override fun replaceSessions(scopeId: String, sessions: List<DshSession>) = Unit
        override fun replaceSessionsAsync(scopeId: String, sessions: List<DshSession>) = Unit
        override fun submitAsync(
            command: DshStoreCommand,
            data: DshStoreCommandData,
            post: (() -> Unit) -> Unit,
            onResult: (Throwable?) -> Unit,
        ) = post { onResult(null) }
        override fun replaceMessages(scopeId: String, sessionId: String, messages: List<DshMessage>) = Unit
        override fun clearScope(scopeId: String) = Unit
    }
}
