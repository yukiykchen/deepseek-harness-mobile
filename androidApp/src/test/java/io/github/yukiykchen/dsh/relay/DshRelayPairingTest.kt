package io.github.yukiykchen.dsh.relay

import org.junit.Assert.*
import org.junit.Test

class DshRelayPairingTest {
    @Test
    fun pairingIntentCapturedBeforeWorkerDispatchIsInvalidatedByStop() {
        val f = RelayRuntimeFixture()
        val token = f.runtime.beginPairing()
        assertTrue(f.runtime.isPairing(token))
        f.runtime.disconnect()
        assertFalse(f.runtime.isPairing(token))
    }
    @Test
    fun routerFragmentAndMainQueryUseTheSamePairParameters() {
        val fragment = parseDshRelayPairQr("https://relay.example/pair#/pair?id=one&key=secret")
        val query = parseDshRelayPairQr("https://relay.example/pair?id=one&key=secret")
        assertEquals(query, fragment)
        assertEquals("https://relay.example", query.origin)
        assertEquals(query, parseDshRelayPairQr("https://relay.example/pair#id=one&key=secret"))
    }

    @Test
    fun encodedDelimitersStayInsideParameterValues() {
        assertEquals("one&two", parseDshRelayPairQr("http://localhost:8787/#/pair?id=one%26two&key=secret").pairId)
    }

    @Test
    fun duplicateOrMissingPairParametersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parseDshRelayPairQr("https://relay.example/?id=one&id=two&key=secret") }
        assertThrows(IllegalArgumentException::class.java) { parseDshRelayPairQr("https://relay.example/?id=one") }
        assertThrows(IllegalArgumentException::class.java) { parseDshRelayPairQr("https://relay.example/?id=one&key=") }
    }

    @Test
    fun credentialsAndNonHttpSchemesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parseDshRelayPairQr("https://user:password@relay.example/?id=one&key=secret") }
        assertThrows(IllegalArgumentException::class.java) { parseDshRelayPairQr("file:///pair?id=one&key=secret") }
    }
}
