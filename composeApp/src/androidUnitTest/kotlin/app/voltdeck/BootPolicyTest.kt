package app.voltdeck

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootPolicyTest {
    @Test
    fun theCollectionRestartsOnlyWhenEverythingIsInPlace() {
        assertTrue(BootPolicy.shouldStart(restartAfterBoot = true, collectionEnabled = true, hasDevices = true, bluetoothGranted = true))
    }

    @Test
    fun withoutTheExplicitSettingNothingRestarts() {
        assertFalse(BootPolicy.shouldStart(restartAfterBoot = false, collectionEnabled = true, hasDevices = true, bluetoothGranted = true))
    }

    @Test
    fun aCollectionStoppedByTheUserStaysStopped() {
        assertFalse(BootPolicy.shouldStart(restartAfterBoot = true, collectionEnabled = false, hasDevices = true, bluetoothGranted = true))
    }

    @Test
    fun noDeviceOrNoBluetoothPermissionMeansNoService() {
        assertFalse(BootPolicy.shouldStart(restartAfterBoot = true, collectionEnabled = true, hasDevices = false, bluetoothGranted = true))
        assertFalse(BootPolicy.shouldStart(restartAfterBoot = true, collectionEnabled = true, hasDevices = true, bluetoothGranted = false))
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
