package io.github.yukiykchen.dsh.feature.connection

import com.tencent.kuikly.core.base.PagerScope
import io.github.yukiykchen.dsh.data.createTestHostClient
import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshHostRuntimePhase
import io.github.yukiykchen.dsh.model.DshHostRuntimeState
import io.github.yukiykchen.dsh.model.DshHostConnection
import io.github.yukiykchen.dsh.platform.DshRelayPhase
import io.github.yukiykchen.dsh.platform.DshRelayState
import io.github.yukiykchen.dsh.platform.DshSshPhase
import io.github.yukiykchen.dsh.platform.DshSshState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DshConnectionControllerTest {
    @Test
    fun reconnectResyncUsesReadyGenerationRatherThanTransientStatusText() {
        val f = Fixture()
        f.state(DshHostRuntimePhase.READY, 1)
        f.state(DshHostRuntimePhase.RECONNECTING, 2)
        f.state(DshHostRuntimePhase.CONNECTING, 3)
        f.state(DshHostRuntimePhase.HOST_HANDSHAKE, 3)
        f.state(DshHostRuntimePhase.SYNCING, 3)
        f.state(DshHostRuntimePhase.READY, 3)
        f.state(DshHostRuntimePhase.READY, 3)
        assertEquals(1, f.reconnected)
    }

    @Test
    fun stoppedCoordinatorIgnoresLateReadyNotifications() {
        val f = Fixture()
        f.state(DshHostRuntimePhase.READY, 1)
        f.controller.coordinator.stop()
        f.state(DshHostRuntimePhase.READY, 3)
        assertEquals(0, f.reconnected)
    }

    @Test
    fun relayFailureStopsAndReleasesTheExistingHostClient() {
        val f = Fixture()
        f.relay(DshRelayState(DshRelayPhase.READY, localPort = 8811, localToken = "first", generation = 1))
        val old = f.controller.client!!
        f.relay(DshRelayState(DshRelayPhase.ERROR, message = "revoked", generation = 2))
        assertNull(f.controller.client)
        assertEquals(DshHostRuntimePhase.STOPPED, old.currentConnectionState().phase)
        assertEquals("revoked", f.controller.statusLabel)
    }

    @Test
    fun tokenReplacementOnTheSamePortCreatesANewClientAndOldNativeStatesAreIgnored() {
        val f = Fixture()
        f.relay(DshRelayState(DshRelayPhase.READY, localPort = 8811, localToken = "first", hostId = "host-new", generation = 2))
        val old = f.controller.client!!
        f.relay(DshRelayState(DshRelayPhase.READY, localPort = 8812, localToken = "old", hostId = "host-old", generation = 1))
        assertSame(old, f.controller.client)
        assertEquals("host-new", f.controller.remoteProfileId)
        f.relay(DshRelayState(DshRelayPhase.READY, localPort = 8811, localToken = "next", generation = 3))
        assertNotSame(old, f.controller.client)
        assertEquals("next", f.endpoints.last().token)
        assertEquals(DshHostRuntimePhase.STOPPED, old.currentConnectionState().phase)
    }

    @Test
    fun settingsModeIsADraftAndCancellationDoesNotChangeTransportOrStorageScope() {
        val f = Fixture()
        f.controller.configure(DshConnectionMode.SSH, "profile")
        f.controller.coordinator.begin(DshConnectionMode.SSH)
        val scope = f.controller.sessionScope
        f.controller.openSettings()
        f.controller.selectMode(false)
        assertEquals(DshConnectionMode.SSH, f.controller.mode)
        assertEquals(scope, f.controller.sessionScope)
        assertTrue(f.controller.isActive())
        f.controller.closeSettings()
        assertTrue(f.controller.sshMode)
        assertEquals(scope, f.controller.sessionScope)
    }

    @Test
    fun sshDisconnectReconnectErrorAndFingerprintChallengeReleaseTheHostClient() {
        for (phase in listOf(DshSshPhase.STOPPED, DshSshPhase.RECONNECTING, DshSshPhase.ERROR, DshSshPhase.FINGERPRINT_REQUIRED)) {
            val f = Fixture()
            f.controller.configure(DshConnectionMode.SSH, "profile")
            val generation = f.controller.coordinator.begin(DshConnectionMode.SSH)
            f.controller.handleSshState(generation, DshSshState(DshSshPhase.READY, localPort = 8811, generation = 1))
            val old = f.controller.client!!
            f.controller.handleSshState(generation, DshSshState(phase, message = "state changed", generation = 2))
            assertNull(f.controller.client)
            assertEquals(DshHostRuntimePhase.STOPPED, old.currentConnectionState().phase)
        }
    }

    @Test
    fun legacySshStopWithoutGenerationStillReleasesTheClient() {
        val f = Fixture()
        f.controller.configure(DshConnectionMode.SSH, "profile")
        val generation = f.controller.coordinator.begin(DshConnectionMode.SSH)
        f.controller.handleSshState(generation, DshSshState(DshSshPhase.READY, localPort = 8811, generation = 7))
        val old = f.controller.client!!
        f.controller.handleSshState(generation, DshSshState(DshSshPhase.STOPPED))
        assertNull(f.controller.client)
        assertEquals(DshHostRuntimePhase.STOPPED, old.currentConnectionState().phase)
    }

    private class Fixture {
        var reconnected = 0
        val endpoints = mutableListOf<DshHostConnection>()
        val controller = DshConnectionController(
            scope = object : PagerScope { override val pagerId = "test-page" },
            localStore = { null },
            supportsRelay = { false },
            createClient = { endpoints.add(it); createTestHostClient() },
            listener = object : DshConnectionController.Listener {
                override fun onClientConnected() = Unit
                override fun onClientReconnected() { reconnected++ }
                override fun onTransportStateChanged() = Unit
                override fun beforeSettingsShown() = Unit
                override fun onSettingsVisibilityChanged(visible: Boolean) = Unit
                override fun onSettingsSaved(passphrase: String) = Unit
                override fun onSetupRequired() = Unit
            },
        )
        init { controller.coordinator.begin(DshConnectionMode.RELAY) }
        fun state(phase: DshHostRuntimePhase, generation: Long) {
            controller.handleRuntimeState(DshHostRuntimeState(phase, generation))
        }
        fun relay(state: DshRelayState) = controller.handleRelayState(controller.coordinator.generation, state)
    }
}
