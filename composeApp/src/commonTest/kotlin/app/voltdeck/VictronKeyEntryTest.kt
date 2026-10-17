// TEST ONLY — les clés ci-dessous sont celles, publiques, du simulateur (calculées par formule), pas des secrets.

package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import app.voltdeck.core.Sample
import app.voltdeck.drivers.victron.InMemoryVictronKeyStore
import app.voltdeck.drivers.victron.VictronKey
import app.voltdeck.drivers.victron.VictronKeyStore
import app.voltdeck.drivers.victron.VictronDecoder
import app.voltdeck.drivers.victron.VictronRecordType
import app.voltdeck.simulator.SimulatedVictron
import app.voltdeck.simulator.SimulationScenario
import app.voltdeck.simulator.VictronAdvertisementEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VictronKeyEntryTest {
    private val device = DeviceId("device-1")
    private val keys = InMemoryVictronKeyStore()
    private val goodKey = SimulatedVictron.TEST_KEY
    private val goodHex = SimulatedVictron.deviceKeyHex(SimulationScenario.SUNNY_DAY)
    private val otherHex = SimulatedVictron.deviceKeyHex(SimulationScenario.WRONG_VICTRON_KEY)

    private val advertisement = VictronAdvertisementEncoder.encode(
        VictronRecordType.SOLAR_CHARGER,
        modelId = 0xA042,
        nonce = 7,
        key = goodKey,
        samples = listOf(Sample(Metric.PV_POWER, value = 100.0)),
    )

    private fun rejected(text: String, adv: List<ByteArray> = listOf(advertisement)): String {
        val outcome = enterVictronKey(device, text, adv, keys)
        assertTrue(outcome is KeyEntryOutcome.Rejected, "attendu : refus, obtenu $outcome")
        return outcome.message
    }

    @Test
    fun theMatchingKeyIsSaved() {
        assertEquals(KeyEntryOutcome.Saved, enterVictronKey(device, goodHex, listOf(advertisement), keys))
        assertEquals(goodKey, keys.get(device))
    }

    @Test
    fun surroundingSpacesAndUpperCaseAreAccepted() {
        assertEquals(KeyEntryOutcome.Saved, enterVictronKey(device, "  ${goodHex.uppercase()}\n", listOf(advertisement), keys))
    }

    @Test
    fun aKeyWithTheWrongCheckByteIsRefusedWithTheStandardMessageAndNotSaved() {
        assertEquals(VictronDecoder.WRONG_KEY_MESSAGE, rejected(otherHex))
        assertNull(keys.get(device))
    }

    @Test
    fun aWrongKeyNeverReplacesTheStoredOne() {
        keys.put(device, goodKey)
        rejected(otherHex)
        assertEquals(goodKey, keys.get(device))
    }

    @Test
    fun badFormatIsRefusedBeforeLookingAtAnyAdvertisement() {
        assertEquals(KEY_FORMAT_MESSAGE, rejected(goodHex.dropLast(1), adv = emptyList()))
        assertEquals(KEY_FORMAT_MESSAGE, rejected("", adv = emptyList()))
        assertEquals(KEY_NOT_HEX_MESSAGE, rejected("zz" + goodHex.drop(2), adv = emptyList()))
        assertNull(keys.get(device))
    }

    @Test
    fun withoutAnAdvertisementTheKeyCannotBeVerifiedAndIsNotSaved() {
        assertEquals(KEY_UNVERIFIABLE_MESSAGE, rejected(goodHex, adv = emptyList()))
        assertNull(keys.get(device))
    }

    @Test
    fun anAdvertisementThatIsNotAProductAdvertisementCannotVerifyAKey() {
        assertEquals(KEY_UNVERIFIABLE_MESSAGE, rejected(goodHex, adv = listOf(byteArrayOf(0x07, 0x00))))
        assertNull(keys.get(device))
    }

    @Test
    fun messagesNeverRepeatTheTypedKey() {
        val messages = listOf(
            rejected(otherHex),
            rejected(goodHex.dropLast(1)),
            rejected("zz" + goodHex.drop(2)),
            rejected(goodHex, adv = emptyList()),
        )
        messages.forEach { message ->
            assertFalse(otherHex in message || goodHex in message || "zz" + goodHex.drop(2) in message)
        }
    }

    private val framesByType = mapOf(
        VictronRecordType.SOLAR_CHARGER to listOf(
            Sample(Metric.DEVICE_STATE, value = 3.0), Sample(Metric.ERROR_CODE, value = 0.0),
            Sample(Metric.BATTERY_VOLTAGE, value = 13.4), Sample(Metric.BATTERY_CURRENT, value = 6.5),
            Sample(Metric.PV_POWER, value = 120.0),
        ),
        VictronRecordType.BATTERY_MONITOR to listOf(
            Sample(Metric.BATTERY_VOLTAGE, value = 12.5), Sample(Metric.BATTERY_CURRENT, value = -5.0),
            Sample(Metric.SOC, value = 50.0), Sample(Metric.CONSUMED_AH, value = -50.0),
        ),
        VictronRecordType.DCDC_CONVERTER to listOf(
            Sample(Metric.DEVICE_STATE, value = 3.0), Sample(Metric.ERROR_CODE, value = 0.0),
            Sample(Metric.INPUT_VOLTAGE, value = 13.2), Sample(Metric.OUTPUT_VOLTAGE, value = 13.8),
        ),
    )

    /** Coquille sur le dernier chiffre de l'octet [byteIndex] : l'octet 0 (contrôle) reste juste. */
    private fun typo(hex: String, byteIndex: Int): String {
        val at = 2 * byteIndex + 1
        return hex.substring(0, at) + (if (hex[at] == '0') '1' else '0') + hex.substring(at + 1)
    }

    @Test
    fun aTypoOnByte5_10_or15IsRefusedForEveryRecordTypeAndNeverSaved() {
        for ((type, samples) in framesByType) {
            val adv = VictronAdvertisementEncoder.encode(type, modelId = 0xA042, nonce = 7, key = goodKey, samples = samples)
            assertEquals(KeyEntryOutcome.Saved, enterVictronKey(DeviceId("control-$type"), goodHex, listOf(adv), keys), "contrôle $type")
            for (byteIndex in listOf(5, 10, 15)) {
                val typed = typo(goodHex, byteIndex)
                assertEquals(goodHex.take(2), typed.take(2))
                assertEquals(VictronDecoder.WRONG_KEY_MESSAGE, rejected(typed, listOf(adv)), "$type octet $byteIndex")
                assertNull(keys.get(device))
            }
        }
    }

    @Test
    fun aFailingVaultGivesARefusalNotAnException() {
        val failing = object : VictronKeyStore {
            override fun get(device: DeviceId): VictronKey? = null
            override fun put(device: DeviceId, key: VictronKey) = error("Keystore indisponible")
            override fun remove(device: DeviceId) = Unit
        }
        val outcome = enterVictronKey(device, goodHex, listOf(advertisement), failing)
        assertEquals(KeyEntryOutcome.Rejected(KEY_SAVE_FAILED_MESSAGE), outcome)
        assertFalse(goodHex in KEY_SAVE_FAILED_MESSAGE)
    }
}
