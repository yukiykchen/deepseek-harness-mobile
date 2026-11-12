package io.github.yukiykchen.dsh.feature.home

import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.data.createTestHostClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DshAttachmentCacheTest {
    @Test
    fun disconnectedLoadCanBeRetriedAfterAClientAppears() {
        val f = Fixture()
        f.cache.load("s1", "a1")
        f.client = createTestHostClient()
        f.cache.load("s1", "a1")
        assertEquals(1, f.requests.size)
        f.requests.single()("data:first", null)
        assertEquals("data:first", f.cache.dataUrl("s1", "a1"))
    }

    @Test fun forgettingASessionDropsItsCachedAttachments() {
        val f = Fixture(createTestHostClient())
        f.cache.load("s1", "a1")
        f.requests.single()("data:image/png;base64,abc", null)
        assertNotNull(f.cache.dataUrl("s1", "a1"))
        f.cache.forget("s1")
        assertNull(f.cache.dataUrl("s1", "a1"))
    }

    @Test
    fun pendingReadsAreDeduplicatedAndFailuresCanBeRetried() {
        val f = Fixture(createTestHostClient())
        f.cache.load("s1", "a1")
        f.cache.load("s1", "a1")
        assertEquals(1, f.requests.size)
        f.requests.first()(null, "failed")
        f.cache.load("s1", "a1")
        assertEquals(2, f.requests.size)
    }

    @Test
    fun sameAttachmentIdsAreIsolatedBySession() {
        val f = Fixture(createTestHostClient())
        f.cache.load("s1", "same")
        f.cache.load("s2", "same")
        f.requests[0]("data:s1", null)
        f.requests[1]("data:s2", null)
        assertEquals("data:s1", f.cache.dataUrl("s1", "same"))
        assertEquals("data:s2", f.cache.dataUrl("s2", "same"))
        assertEquals(listOf("s1", "s2"), f.loaded)
    }

    @Test
    fun replacingTheClientRejectsOldCallbacksAndClearsCachedImages() {
        val f = Fixture(createTestHostClient())
        f.cache.load("s1", "cached")
        f.requests[0]("data:old", null)
        f.cache.load("s1", "pending")
        val oldCallback = f.requests[1]
        f.client = createTestHostClient()
        assertNull(f.cache.dataUrl("s1", "cached"))
        f.cache.load("s1", "pending")
        oldCallback("data:stale", null)
        assertNull(f.cache.dataUrl("s1", "pending"))
        f.requests.last()("data:new", null)
        assertEquals("data:new", f.cache.dataUrl("s1", "pending"))
        assertEquals(listOf("s1", "s1"), f.loaded)
    }

    @Test
    fun closingDiscardsCachedDataAndLateCallbacks() {
        val f = Fixture(createTestHostClient())
        f.cache.load("s1", "a1")
        f.cache.close()
        f.requests.single()("data:late", null)
        f.cache.load("s1", "a1")
        assertNull(f.cache.dataUrl("s1", "a1"))
        assertEquals(1, f.requests.size)
        assertEquals(emptyList(), f.loaded)
    }

    private class Fixture(var client: DshHostClient? = null) {
        val requests = mutableListOf<(String?, String?) -> Unit>()
        val loaded = mutableListOf<String>()
        val cache = DshAttachmentCache(
            ctx = object : DshHomeContext {
                override val pagerId = "test-page"
                override val activeSessionId = "s1"
                override val hostClient get() = client
            },
            onLoaded = { loaded.add(it) },
            fetch = { _, _, _, callback -> requests.add(callback) },
        )
    }
}
