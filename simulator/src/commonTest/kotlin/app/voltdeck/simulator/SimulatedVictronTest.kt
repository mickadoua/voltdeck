// TEST ONLY — les clés du simulateur sont publiques, calculées par formule, et ne protègent rien.
//
// Sources du format : Victron Energy, « Extra manufacturer Data », version du 2022-12-14,
// https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
// Vecteurs de référence : keshavdv/victron-ble (Unlicense) et rajlaud/victron-ble-ha-parser (Apache-2.0), testés
// côté pilote (drivers/.../VictronReferenceVectorsTest). Ici : encodeur du simulateur contre le décodeur du pilote.

package app.voltdeck.simulator

import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.ble.ScanFilter
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.core.Sample
import app.voltdeck.drivers.KnownDevice
import app.voltdeck.drivers.victron.InMemoryVictronKeyStore
import app.voltdeck.drivers.victron.KeyParse
import app.voltdeck.drivers.victron.VictronDecodeResult
import app.voltdeck.drivers.victron.VictronDecoder
import app.voltdeck.drivers.victron.VictronInstantReadoutDriver
import app.voltdeck.drivers.victron.VictronKey
import app.voltdeck.drivers.victron.VictronLinkStatus
import app.voltdeck.drivers.victron.VictronRecordType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SimulatedVictronTest {
    private val key = SimulatedVictron.TEST_KEY
    private val companyId = ConnectionPolicy.VICTRON_COMPANY_ID
    private val noon = Instant.parse("2026-06-20T12:00:00Z")

    private fun decode(type: VictronRecordType, samples: List<Sample>, nonce: Int = 0x1234): Map<Metric, Double> {
        val record = VictronAdvertisementEncoder.encode(type, modelId = 0xA042, nonce, key, samples)
        val result = VictronDecoder.decode(record, key)
        assertIs<VictronDecodeResult.Decoded>(result)
        return result.samples.associate { it.metric to it.value }
    }

    @Test
    fun solarRecordRoundTrips() {
        val got = decode(
            VictronRecordType.SOLAR_CHARGER,
            listOf(
                Sample(Metric.DEVICE_STATE, value = 3.0),
                Sample(Metric.ERROR_CODE, value = 0.0),
                Sample(Metric.BATTERY_VOLTAGE, value = 13.31),
                Sample(Metric.BATTERY_CURRENT, value = -2.5),
                Sample(Metric.YIELD_TODAY, value = 1.27),
                Sample(Metric.PV_POWER, value = 245.0),
            ),
        )
        assertEquals(3.0, got[Metric.DEVICE_STATE])
        assertEquals(13.31, got[Metric.BATTERY_VOLTAGE])
        assertEquals(-2.5, got[Metric.BATTERY_CURRENT])
        assertEquals(1.27, got[Metric.YIELD_TODAY])
        assertEquals(245.0, got[Metric.PV_POWER])
        assertNull(got[Metric.LOAD_CURRENT], "courant de charge non fourni => NA => métrique absente")
    }

    @Test
    fun batteryMonitorRecordRoundTripsWithNegativeCurrentAndConsumedAh() {
        val got = decode(
            VictronRecordType.BATTERY_MONITOR,
            listOf(
                Sample(Metric.BATTERY_VOLTAGE, value = 12.84),
                Sample(Metric.BATTERY_CURRENT, value = -5.037),
                Sample(Metric.CONSUMED_AH, value = -80.4),
                Sample(Metric.SOC, value = 59.8),
                Sample(Metric.TIME_TO_GO, value = 1_000.0),
                Sample(Metric.TEMPERATURE, value = 21.5),
            ),
        )
        assertEquals(12.84, got[Metric.BATTERY_VOLTAGE])
        assertEquals(-5.037, got[Metric.BATTERY_CURRENT])
        assertEquals(-80.4, got[Metric.CONSUMED_AH])
        assertEquals(59.8, got[Metric.SOC])
        assertEquals(1_000.0, got[Metric.TIME_TO_GO])
        assertEquals(21.5, got.getValue(Metric.TEMPERATURE), 0.005)
    }

    @Test
    fun absentMetricsAreWrittenAsNaAndNeverAsZero() {
        val got = decode(
            VictronRecordType.BATTERY_MONITOR,
            listOf(Sample(Metric.BATTERY_VOLTAGE, value = 12.0), Sample(Metric.BATTERY_CURRENT, value = 0.0)),
        )
        assertEquals(setOf(Metric.BATTERY_VOLTAGE, Metric.BATTERY_CURRENT, Metric.ALARM_REASON), got.keys)
        assertEquals(0.0, got[Metric.BATTERY_CURRENT], "un zéro réel reste un zéro")
    }

    @Test
    fun dcdcRecordRoundTrips() {
        val got = decode(
            VictronRecordType.DCDC_CONVERTER,
            listOf(
                Sample(Metric.DEVICE_STATE, value = 3.0),
                Sample(Metric.INPUT_VOLTAGE, value = 13.8),
                Sample(Metric.OUTPUT_VOLTAGE, value = 14.4),
            ),
        )
        assertEquals(13.8, got[Metric.INPUT_VOLTAGE])
        assertEquals(14.4, got[Metric.OUTPUT_VOLTAGE])
        assertNull(got[Metric.ERROR_CODE])
    }

    @Test
    fun aValueEqualToTheNaPatternIsNeverEmittedAsNa() {
        val got = decode(VictronRecordType.BATTERY_MONITOR, listOf(Sample(Metric.BATTERY_CURRENT, value = -0.001)))
        assertEquals(-0.002, got[Metric.BATTERY_CURRENT])
    }

    @Test
    fun testKeysAreDistinctAndTheHexFormParsesBack() {
        assertNotEquals(SimulatedVictron.TEST_KEY.checkByte, SimulatedVictron.OTHER_KEY.checkByte)
        assertEquals(VictronKey.HEX_LENGTH, SimulatedVictron.testKeyHex().length)
        val parsed = VictronKey.parse(SimulatedVictron.testKeyHex())
        assertIs<KeyParse.Valid>(parsed)
        assertEquals(SimulatedVictron.TEST_KEY, parsed.key)
    }

    @Test
    fun scenarioIdsAreStable() {
        assertEquals(SimulationScenario.SUNNY_DAY, SimulationScenario.fromId("sunny_day"))
        assertEquals(SimulationScenario.WRONG_VICTRON_KEY, SimulationScenario.fromId("wrong_victron_key"))
        assertNull(SimulationScenario.fromId("inconnu"))
    }

    private val mpptDevice = KnownDevice(SimulatedPlant.MPPT_ID, SimulatedTransport.MPPT_ID, DeviceRole.SOLAR_CHARGER)
    private val shuntDevice = KnownDevice(SimulatedPlant.SHUNT_ID, SimulatedTransport.SHUNT_ID, DeviceRole.SHUNT)

    private fun fixedClock(at: Instant) = object : Clock {
        override fun now() = at
    }

    @Test
    fun sunnyDayChainShowsTheMpptAndTheShuntWithTheModelValues() = runTest {
        val truth = SimulatedPlant(seed = 1L, start = noon).readingsAt(noon)
        val transport = SimulatedTransport(seed = 1L, clock = fixedClock(noon), scenario = SimulationScenario.SUNNY_DAY)
        val keys = InMemoryVictronKeyStore().apply {
            put(mpptDevice.id, SimulatedVictron.TEST_KEY)
            put(shuntDevice.id, SimulatedVictron.TEST_KEY)
        }
        val driver = VictronInstantReadoutDriver(keys, fixedClock(noon))
        val mppt = driver.readings(mpptDevice, transport).first().samples
        val shunt = driver.readings(shuntDevice, transport).first().samples

        fun List<Sample>.value(m: Metric) = first { it.metric == m }.value
        val trueMppt = truth[1].samples
        val trueShunt = truth[2].samples
        assertTrue(trueMppt.value(Metric.PV_POWER) > 100.0, "le test suppose du soleil à midi")
        assertEquals(trueMppt.value(Metric.PV_POWER), mppt.value(Metric.PV_POWER))
        assertEquals(trueMppt.value(Metric.BATTERY_VOLTAGE), mppt.value(Metric.BATTERY_VOLTAGE), 0.0051)
        assertEquals(trueMppt.value(Metric.BATTERY_CURRENT), mppt.value(Metric.BATTERY_CURRENT), 0.051)
        assertEquals(trueMppt.value(Metric.YIELD_TODAY), mppt.value(Metric.YIELD_TODAY), 0.0051)
        assertEquals(trueMppt.value(Metric.DEVICE_STATE), mppt.value(Metric.DEVICE_STATE))
        assertEquals(trueShunt.value(Metric.SOC), shunt.value(Metric.SOC), 0.051)
        assertEquals(trueShunt.value(Metric.CONSUMED_AH), shunt.value(Metric.CONSUMED_AH), 0.051)
        assertEquals(trueShunt.value(Metric.BATTERY_CURRENT), shunt.value(Metric.BATTERY_CURRENT), 0.0011)
        assertTrue(shunt.value(Metric.CONSUMED_AH) < 0.0)
        assertEquals(trueShunt.value(Metric.TEMPERATURE), shunt.value(Metric.TEMPERATURE), 0.051)
        assertEquals(VictronLinkStatus.OK, driver.diagnostics.status.value[shuntDevice.id])
    }

    @Test
    fun wrongVictronKeyScenarioIsRefusedAndReportedAsWrongKey() = runTest {
        val transport = SimulatedTransport(clock = fixedClock(noon), scenario = SimulationScenario.WRONG_VICTRON_KEY)
        val keys = InMemoryVictronKeyStore().apply { put(mpptDevice.id, SimulatedVictron.TEST_KEY) }
        val driver = VictronInstantReadoutDriver(keys, fixedClock(noon))

        val readings = withTimeoutOrNull(5.seconds) { driver.readings(mpptDevice, transport).first() }

        assertNull(readings, "aucune mesure ne doit sortir avec une mauvaise clé")
        assertEquals(VictronLinkStatus.WRONG_KEY, driver.diagnostics.status.value[mpptDevice.id])
        val adv = transport.advertisements(ScanFilter(companyId = companyId)).first { it.id == SimulatedTransport.MPPT_ID }
        assertEquals(
            VictronDecoder.KeyCheck.Mismatch,
            VictronDecoder.checkKey(adv.manufacturerData.getValue(companyId), SimulatedVictron.TEST_KEY),
        )
    }

    @Test
    fun theSameDevicesAreDecodedWithTheirOwnKeyInTheWrongKeyScenario() = runTest {
        val transport = SimulatedTransport(clock = fixedClock(noon), scenario = SimulationScenario.WRONG_VICTRON_KEY)
        val keys = InMemoryVictronKeyStore().apply { put(mpptDevice.id, SimulatedVictron.OTHER_KEY) }
        val driver = VictronInstantReadoutDriver(keys, fixedClock(noon))
        assertTrue(driver.readings(mpptDevice, transport).first().samples.isNotEmpty())
    }
}
