package app.voltdeck

import android.content.Intent
import java.io.File
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootPolicyTest {
    private val ready = BootConditions(restartAfterBoot = true, collectionEnabled = true, hasDevices = true, bluetoothGranted = true)
    private val t0 = Instant.parse("2026-06-21T10:00:00Z")

    @Test
    fun theCollectionRestartsOnlyWhenEverythingIsInPlace() {
        assertEquals(BootOutcome.STARTED, BootPolicy.decide(ready))
    }

    @Test
    fun withoutTheExplicitSettingNothingRestarts() {
        assertEquals(BootOutcome.SETTING_OFF, BootPolicy.decide(ready.copy(restartAfterBoot = false)))
    }

    @Test
    fun aCollectionStoppedByTheUserStaysStopped() {
        assertEquals(BootOutcome.STOPPED_BY_USER, BootPolicy.decide(ready.copy(collectionEnabled = false)))
    }

    @Test
    fun noDeviceOrNoBluetoothPermissionMeansNoService() {
        assertEquals(BootOutcome.NO_DEVICE, BootPolicy.decide(ready.copy(hasDevices = false)))
        assertEquals(BootOutcome.NO_PERMISSION, BootPolicy.decide(ready.copy(bluetoothGranted = false)))
    }

    private class Run(val conditions: BootConditions, val failure: Exception? = null) {
        var started = 0
        val recorded = mutableListOf<BootAttempt>()
        fun on(action: String?) = BootRestart.run(
            action, { conditions }, { started++; failure?.let { throw it } }, { Instant.parse("2026-06-21T10:00:00Z") }, { recorded += it },
        )
    }

    @Test
    fun onlyTheBootBroadcastTriggersAStartAndNothingElseIsRecorded() {
        val run = Run(ready)
        run.on("android.intent.action.PACKAGE_REPLACED")
        run.on(null)
        assertEquals(0, run.started)
        assertEquals(emptyList(), run.recorded)
    }

    @Test
    fun aStartIsRecordedAsStarted() {
        val run = Run(ready)
        run.on(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(1, run.started)
        assertEquals(listOf(BootAttempt(t0, BootOutcome.STARTED)), run.recorded)
    }

    @Test
    fun aRefusalByAndroidIsRecordedWithItsTypeAndNeverTheMessage() {
        val run = Run(ready, failure = IllegalStateException("secret-looking message"))
        run.on(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(listOf(BootAttempt(t0, BootOutcome.REFUSED, "IllegalStateException")), run.recorded)
    }

    @Test
    fun aSkippedStartIsRecordedWithItsReasonAndDoesNotStartTheService() {
        val run = Run(ready.copy(restartAfterBoot = false))
        run.on(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(0, run.started)
        assertEquals(listOf(BootAttempt(t0, BootOutcome.SETTING_OFF)), run.recorded)
    }

    @Test
    fun theManifestWiresTheReceiverToBootCompletedAndDeclaresItsPermission() {
        val manifest = File("src/androidMain/AndroidManifest.xml").readText()
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in manifest)
        val receiver = manifest.substringAfter("<receiver").substringBefore("</receiver>")
        assertTrue(".BootReceiver" in receiver)
        assertTrue("android.intent.action.BOOT_COMPLETED" in receiver)
        assertEquals(1, Regex("<receiver").findAll(manifest).count(), "un seul récepteur déclaré")
    }
}
