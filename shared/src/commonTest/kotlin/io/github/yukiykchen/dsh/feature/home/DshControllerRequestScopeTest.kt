package io.github.yukiykchen.dsh.feature.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshControllerRequestScopeTest {
    @Test
    fun newerRequestAndOtherOwnerInvalidateOlderTickets() {
        val f = ControllerTestFixture()
        val scope = DshControllerRequestScope(f.ctx)
        val first = scope.begin("s1")!!
        assertTrue(scope.accepts(first))
        val other = DshControllerRequestScope(f.ctx)
        other.begin("s1")
        assertFalse(other.owns(first))
        scope.begin("s1")
        assertFalse(scope.owns(first))
    }

    @Test
    fun sessionScopeRejectsSwitchesButConnectionWideRequestsDoNot() {
        val f = ControllerTestFixture()
        val scoped = DshControllerRequestScope(f.ctx)
        val global = DshControllerRequestScope(f.ctx)
        val sessionRequest = scoped.begin("s1")!!
        val globalRequest = global.begin()!!
        f.sessionId = "s2"
        assertTrue(scoped.owns(sessionRequest))
        assertFalse(scoped.accepts(sessionRequest))
        assertTrue(global.accepts(globalRequest))
    }

    @Test
    fun reconnectChangesConnectionScopeAndClosePreventsNewWork() {
        val f = ControllerTestFixture()
        val scope = DshControllerRequestScope(f.ctx)
        val request = scope.begin()!!
        f.transport.sockets.first().disconnect()
        assertFalse(scope.accepts(request))
        scope.close()
        assertFalse(scope.owns(request))
        assertNull(scope.begin())
    }

    @Test
    fun startingWithoutAClientStillInvalidatesThePreviousRequest() {
        val f = ControllerTestFixture()
        val scope = DshControllerRequestScope(f.ctx)
        val request = scope.begin()!!
        f.client = null
        assertNull(scope.begin())
        assertFalse(scope.owns(request))
    }
}
