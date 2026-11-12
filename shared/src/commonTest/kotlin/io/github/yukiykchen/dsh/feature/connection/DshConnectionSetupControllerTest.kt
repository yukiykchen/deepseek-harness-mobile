package io.github.yukiykchen.dsh.feature.connection

import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshHostRuntimePhase
import io.github.yukiykchen.dsh.platform.DshSshPhase
import io.github.yukiykchen.dsh.platform.DshSshState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DshConnectionSetupControllerTest {
    @Test fun missingLocalStorageCannotPretendToSaveAProfileAndNavigateToAnUnconfiguredHome() {
        val f = SetupTestFixture()
        f.ready()
        f.storeAvailable = false
        f.probes.single().success()
        f.flush()
        assertTrue(f.navigations.isEmpty())
        assertTrue(f.form.error.contains("保存"))
        assertFalse(f.form.busy)
    }
    @Test fun returningFromHomeAllowsANewConnectionWithoutRetainingTheOldPassphrase() {
        val f = SetupTestFixture()
        f.ready()
        f.probes.single().success()
        f.flush()
        f.controller.resume()
        assertEquals("", f.form.passphrase)
        f.controller.continueToHost()
        assertEquals(2, f.validations.size)
        f.controller.dispose()
        f.controller.resume()
        assertTrue(f.form.closed)
    }
    @Test fun duplicateConnectAndValidationCallbacksStartExactlyOneNativeAttempt() {
        val f = SetupTestFixture()
        f.configureSsh()
        f.controller.continueToHost()
        f.controller.continueToHost()
        assertEquals(1, f.validations.size)
        f.validations.single().callback(true)
        f.validations.single().callback(true)
        f.flush()
        assertEquals(1, f.starts.size)
        assertEquals("test-passphrase", f.validations.single().passphrase)
        assertEquals("test-passphrase", f.starts.single().config.keyPassphrase)
        assertNull(f.store.remote)
    }

    @Test fun fieldEditsAndModeChangesInvalidatePendingValidation() {
        for (edit in listOf<(SetupTestFixture) -> Unit>({ it.form.host = "other" }, { it.form.passphrase = "next-secret" },
            { it.controller.connectionMode = DshConnectionMode.RELAY })) {
            val f = SetupTestFixture()
            f.configureSsh()
            f.controller.continueToHost()
            edit(f)
            f.validations.single().callback(true)
            f.flush()
            assertTrue(f.starts.isEmpty())
            assertNull(f.store.remote)
            assertFalse(f.form.busy)
        }
    }

    @Test fun duplicateReadyCannotCreateMultipleProbesOrNavigateTwice() {
        val f = SetupTestFixture()
        f.ready()
        f.state(DshSshPhase.READY)
        assertEquals(1, f.probes.size)
        f.probes.single().success()
        f.probes.single().success()
        f.flush()
        f.state(DshSshPhase.READY)
        assertEquals(1, f.navigations.size)
        assertEquals(1, f.store.remoteWrites)
        assertEquals("test-passphrase", f.navigations.single().passphrase)
        assertFalse(f.store.remote.toString().contains("test-passphrase"))
        assertEquals(DshHostRuntimePhase.STOPPED, f.probes.single().client.currentConnectionState().phase)
    }

    @Test fun reconnectDropsTheOldProbeAndItsLateSuccessCannotStopTheNewAttempt() {
        val f = SetupTestFixture()
        f.ready()
        val old = f.probes.single()
        f.state(DshSshPhase.RECONNECTING, generation = 2)
        assertEquals(DshHostRuntimePhase.STOPPED, old.client.currentConnectionState().phase)
        f.state(DshSshPhase.READY, port = 9911, generation = 3)
        old.success()
        f.flush()
        assertTrue(f.navigations.isEmpty())
        assertEquals(0, f.stops)
        assertTrue(f.form.busy)
        f.probes.last().success()
        f.flush()
        assertEquals(1, f.navigations.size)
    }

    @Test fun newerNativeGenerationReprobesEvenWhenThePortIsReused() {
        val f = SetupTestFixture()
        f.ready()
        f.state(DshSshPhase.READY, generation = 2)
        assertEquals(2, f.probes.size)
        assertEquals(DshHostRuntimePhase.STOPPED, f.probes.first().client.currentConnectionState().phase)
        f.state(DshSshPhase.READY, port = 1234, generation = 1)
        assertEquals(2, f.probes.size)
    }

    @Test fun disposalReleasesProbeAndNativeResourcesAndRejectsQueuedSuccess() {
        val f = SetupTestFixture()
        f.ready()
        f.probes.single().success()
        f.controller.dispose()
        f.store.close()
        f.flush()
        f.controller.continueToHost()
        f.controller.scanRelayQr()
        assertEquals(1, f.stops)
        assertTrue(f.navigations.isEmpty())
        assertNull(f.store.remote)
        assertTrue(f.form.closed)
        assertEquals("", f.form.passphrase)
    }

    @Test fun probeReturnedAfterReentrantDisposalIsReleased() {
        val f = SetupTestFixture()
        f.startSsh()
        f.onCreateProbe = { f.controller.dispose() }
        f.state(DshSshPhase.READY)
        assertEquals(DshHostRuntimePhase.STOPPED, f.probes.single().client.currentConnectionState().phase)
        assertTrue(f.navigations.isEmpty())
    }

    @Test fun lateFailureFromCancelledAttemptCannotClearTheNextBusyState() {
        val f = SetupTestFixture()
        f.startSsh()
        val old = f.starts.single().callback
        f.form.host = "next-host"
        f.controller.continueToHost()
        old(DshSshState(DshSshPhase.ERROR, "old error", generation = 9))
        f.flush()
        assertTrue(f.form.busy)
        assertEquals("", f.form.error)
        assertEquals(2, f.validations.size)
    }

    @Test fun fingerprintConfirmationUsesTheCurrentNativeAttemptWithoutStartingAnotherOne() {
        val f = SetupTestFixture()
        f.startSsh()
        f.state(DshSshPhase.FINGERPRINT_REQUIRED, message = "SHA256:offered")
        assertNull(f.store.remote)
        f.controller.continueToHost()
        assertEquals(1, f.validations.size)
        f.controller.trustFingerprint()
        f.controller.trustFingerprint()
        assertEquals(listOf("SHA256:offered"), f.trusts)
        assertEquals(1, f.starts.size)
        f.state(DshSshPhase.READY, generation = 2)
        f.probes.single().success()
        f.flush()
        assertEquals("SHA256:offered", f.store.remote!!.hostFingerprint)
        assertEquals(1, f.navigations.size)
    }

    @Test fun failedProbeOrPersistenceCannotReportSuccess() {
        for (persistenceFailure in listOf(false, true)) {
            val f = SetupTestFixture()
            f.ready()
            f.store.failWrites = persistenceFailure
            if (persistenceFailure) f.probes.single().success() else f.probes.single().failure()
            f.flush()
            assertTrue(f.navigations.isEmpty())
            assertFalse(f.form.busy)
            assertTrue(f.form.error.isNotEmpty())
            assertEquals(1, f.stops)
            assertNull(f.store.remote)
        }
    }

    @Test fun asyncCommitDelayKeepsTheRequestBusyAndBlocksNavigation() {
        val f = SetupTestFixture()
        f.store.deferWrites = true
        f.ready()
        f.probes.single().success()
        f.flush()
        assertTrue(f.form.busy)
        assertTrue(f.navigations.isEmpty())
        assertNull(f.store.remote)
        f.store.completeWrites { callback -> f.posts.add(callback) }
        f.flush()
        assertFalse(f.form.busy)
        assertEquals(1, f.navigations.size)
        assertNotNull(f.store.remote)
    }

    @Test fun asyncCommitFailureCannotMarkTheKeySavedOrNavigate() {
        val f = SetupTestFixture()
        f.store.deferWrites = true
        f.ready()
        f.probes.single().success()
        f.flush()
        f.store.failWrites = true
        f.store.completeWrites { callback -> f.posts.add(callback) }
        f.flush()
        assertTrue(f.navigations.isEmpty())
        assertFalse(f.form.busy)
        assertTrue(f.form.error.contains("保存"))
        assertNull(f.store.remote)
    }

    @Test fun malformedReadyAndValidationFailureDoNotCreateProbes() {
        val invalidKey = SetupTestFixture()
        invalidKey.configureSsh()
        invalidKey.controller.continueToHost()
        invalidKey.validations.single().callback(false)
        invalidKey.flush()
        assertTrue(invalidKey.starts.isEmpty())
        assertFalse(invalidKey.form.busy)
        val f = SetupTestFixture()
        f.startSsh()
        f.state(DshSshPhase.READY, port = 0)
        assertTrue(f.probes.isEmpty())
        assertFalse(f.form.busy)
        assertEquals(1, f.stops)
    }

    @Test fun cancelledPairingCannotUpdateTheNewModesUiOrPersistMetadata() {
        val f = SetupTestFixture()
        f.controller.scanRelayQr()
        f.controller.connectionMode = DshConnectionMode.SSH
        f.scans.single()(f.pairing())
        f.flush()
        assertEquals(1, f.cancelledPairings)
        assertFalse(f.controller.relayPaired)
        assertNull(f.store.relay)
        assertFalse(f.form.busy)
    }

    @Test fun pairingAndForgettingHaveOneCompletionAndRespectNativeFailure() {
        val f = SetupTestFixture()
        f.controller.scanRelayQr()
        f.controller.scanRelayQr()
        f.scans.single()(f.pairing())
        f.scans.single()(f.pairing())
        f.flush()
        assertEquals(1, f.scans.size)
        assertTrue(f.controller.relayPaired)
        f.controller.forgetRelay()
        f.forgets.last()(false)
        f.flush()
        assertTrue(f.controller.relayPaired)
        assertTrue(f.form.error.isNotEmpty())
        f.controller.forgetRelay()
        f.forgets.last()(true)
        f.flush()
        assertFalse(f.controller.relayPaired)
        assertNull(f.store.relay)
    }

    @Test fun disposedPairingCallbacksCannotWriteToAClosedStore() {
        val f = SetupTestFixture()
        f.controller.scanRelayQr()
        f.controller.dispose()
        f.store.close()
        f.scans.single()(f.pairing())
        f.flush()
        assertNull(f.store.relay)
        assertEquals(1, f.cancelledPairings)
    }

    @Test fun successfulRelayNavigationUsesThePairedHostAndOccursOnce() {
        val f = SetupTestFixture()
        f.controller.scanRelayQr()
        f.scans.single()(f.pairing())
        f.flush()
        f.controller.continueToHost()
        f.controller.continueToHost()
        f.flush()
        assertEquals(1, f.navigations.size)
        assertEquals("host", f.navigations.single().profile)
        assertEquals(DshConnectionMode.RELAY, f.store.mode)
        assertEquals("", f.navigations.single().passphrase)
    }
}
