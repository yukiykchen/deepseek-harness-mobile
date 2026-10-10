package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals

class DshSessionScopeTest {
    @Test
    fun storageKeysIsolateRelayAndSshCaches() {
        assertEquals("relay:host-1", DshSessionScope(DshConnectionMode.RELAY, "host-1").storageKey)
        assertEquals("ssh:default", DshSessionScope(DshConnectionMode.SSH).storageKey)
        assertEquals(
            "ssh:office",
            DshSessionScope(DshConnectionMode.SSH, "office").storageKey,
        )
    }
}
