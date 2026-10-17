// TEST ONLY — les clés ci-dessous sont celles, publiques, du simulateur (calculées par formule), pas des secrets.

package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import app.voltdeck.core.Sample
import app.voltdeck.drivers.victron.InMemoryVictronKeyStore
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

    private fun rejected(text: String, adv: ByteArray? = advertisement): String {
        val outcome = enterVictronKey(device, text, adv, keys)
        assertTrue(outcome is KeyEntryOutcome.Rejected, "attendu : refus, obtenu $outcome")
        return outcome.message
    }

    @Test
    fun theMatchingKeyIsSaved() {
        assertEquals(KeyEntryOutcome.Saved, enterVictronKey(device, goodHex, advertisement, keys))
        assertEquals(goodKey, keys.get(device))
    }

    @Test
    fun surroundingSpacesAndUpperCaseAreAccepted() {
        assertEquals(KeyEntryOutcome.Saved, enterVictronKey(device, "  ${goodHex.uppercase()}\n", advertisement, keys))
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
        assertEquals(KEY_FORMAT_MESSAGE, rejected(goodHex.dropLast(1), adv = null))
        assertEquals(KEY_FORMAT_MESSAGE, rejected("", adv = null))
        assertEquals(KEY_NOT_HEX_MESSAGE, rejected("zz" + goodHex.drop(2), adv = null))
        assertNull(keys.get(device))
    }

    @Test
    fun withoutAnAdvertisementTheKeyCannotBeVerifiedAndIsNotSaved() {
        assertEquals(KEY_UNVERIFIABLE_MESSAGE, rejected(goodHex, adv = null))
        assertNull(keys.get(device))
    }

    @Test
    fun anAdvertisementThatIsNotAProductAdvertisementCannotVerifyAKey() {
        assertEquals(KEY_UNVERIFIABLE_MESSAGE, rejected(goodHex, adv = byteArrayOf(0x07, 0x00)))
        assertNull(keys.get(device))
    }

    @Test
    fun messagesNeverRepeatTheTypedKey() {
        val messages = listOf(
            rejected(otherHex),
            rejected(goodHex.dropLast(1)),
            rejected("zz" + goodHex.drop(2)),
            rejected(goodHex, adv = null),
        )
        messages.forEach { message ->
            assertFalse(otherHex in message || goodHex in message || "zz" + goodHex.drop(2) in message)
        }
    }
}
