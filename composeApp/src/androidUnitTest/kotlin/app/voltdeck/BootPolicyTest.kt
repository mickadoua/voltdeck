package app.voltdeck

import android.content.Intent
import java.io.File
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun whenSeveralConditionsFailTheFirstInTheDocumentedOrderIsReported() {
        val nothing = BootConditions(restartAfterBoot = false, collectionEnabled = false, hasDevices = false, bluetoothGranted = false)
        assertEquals(BootOutcome.SETTING_OFF, BootPolicy.decide(nothing))
        assertEquals(BootOutcome.STOPPED_BY_USER, BootPolicy.decide(nothing.copy(restartAfterBoot = true)))
        assertEquals(BootOutcome.NO_DEVICE, BootPolicy.decide(nothing.copy(restartAfterBoot = true, collectionEnabled = true)))
        assertEquals(
            BootOutcome.NO_DEVICE,
            BootPolicy.decide(ready.copy(hasDevices = false, bluetoothGranted = false)),
            "sans équipement, la permission manquante n'est pas le problème à montrer",
        )
    }

    private class Starts(val failure: Exception? = null) {
        val failures = mutableListOf<BootAttempt>()
        val starts = mutableListOf<BootAttempt>()
        fun run() = ServiceStart.run(
            { failure?.let { throw it } }, { Instant.parse("2026-06-21T10:00:00Z") }, { 90.seconds }, { 7 }, { failures += it }, { starts += it },
        )
    }

    @Test
    fun aServiceStartAndroidForbidsInTheBackgroundIsRecordedInsteadOfCrashing() {
        val starts = Starts(IllegalStateException("message"))
        assertEquals(false, starts.run())
        assertEquals(listOf(BootAttempt(t0, BootOutcome.REFUSED, "IllegalStateException", 7, 90.seconds)), starts.failures)
        assertEquals(emptyList(), starts.starts)
    }

    @Test
    fun aMissingRightIsRecordedTheSameWay() {
        val starts = Starts(SecurityException("message"))
        assertEquals(false, starts.run())
        assertEquals(listOf(BootAttempt(t0, BootOutcome.REFUSED, "SecurityException", 7, 90.seconds)), starts.failures)
    }

    @Test
    fun aSuccessfulStartIsNotedWithItsPhoneBootAndRecordsNoFailure() {
        val starts = Starts()
        assertEquals(true, starts.run())
        assertEquals(listOf(BootAttempt(t0, BootOutcome.STARTED, null, 7, 90.seconds)), starts.starts)
        assertEquals(emptyList(), starts.failures)
    }

    @Test
    fun anUnrelatedBugLeavesItsTypeBehindThenPropagates() {
        val starts = Starts(UnsupportedOperationException())
        assertFailsWith<UnsupportedOperationException> { starts.run() }
        assertEquals(listOf(BootAttempt(t0, BootOutcome.FAILED, "UnsupportedOperationException", 7, 90.seconds)), starts.failures)
        assertEquals(emptyList(), starts.starts)
    }

    private class Run(val conditions: BootConditions, val failure: Exception? = null) {
        var started = 0
        val recorded = mutableListOf<BootAttempt>()
        fun on(action: String?) = BootRestart.run(
            action, { conditions }, { started++; failure?.let { throw it } }, { Instant.parse("2026-06-21T10:00:00Z") }, { 7 }, { recorded += it },
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
        assertEquals(listOf(BootAttempt(t0, BootOutcome.STARTED, bootCount = 7)), run.recorded)
    }

    @Test
    fun aRefusalByAndroidIsRecordedWithItsTypeAndNeverTheMessage() {
        val run = Run(ready, failure = IllegalStateException("secret-looking message"))
        run.on(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(listOf(BootAttempt(t0, BootOutcome.REFUSED, "IllegalStateException", 7)), run.recorded)
    }

    @Test
    fun aSkippedStartIsRecordedWithItsReasonAndDoesNotStartTheService() {
        val run = Run(ready.copy(restartAfterBoot = false))
        run.on(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(0, run.started)
        assertEquals(listOf(BootAttempt(t0, BootOutcome.SETTING_OFF, bootCount = 7)), run.recorded)
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
