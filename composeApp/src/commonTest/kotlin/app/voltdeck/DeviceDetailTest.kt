package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.core.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class DeviceDetailTest {
    private fun reading(vararg samples: Sample) = Reading(DeviceId("d"), Instant.fromEpochSeconds(1_800_000_000), samples.toList())
    private fun s(metric: Metric, value: Double, channel: Int = 0) = Sample(metric, channel, value)

    private fun DeviceDetail.row(label: String) = sections.flatMap { it.rows }.firstOrNull { it.label == label }

    @Test
    fun batteryShowsCellsSpreadAndLabelsTheCalculatedPower() {
        val d = deviceDetail(
            DeviceRole.BATTERY,
            reading(
                s(Metric.SOC, 78.0), s(Metric.BATTERY_VOLTAGE, 13.2), s(Metric.BATTERY_CURRENT, -10.0),
                s(Metric.CELL_VOLTAGE, 3.300, 0), s(Metric.CELL_VOLTAGE, 3.310, 1), s(Metric.CELL_VOLTAGE, 3.296, 2),
                s(Metric.CYCLES, 42.0),
            ),
        )
        assertEquals("78", d.hero?.number)
        assertEquals("%", d.hero?.unit)
        assertEquals("-132 W", d.row("Puissance (+ = charge)")?.value)
        assertEquals("calculée", d.row("Puissance (+ = charge)")?.tag)
        assertEquals("3,296 V · cellule 3", d.row("Plus basse")?.value)
        assertEquals("3,310 V · cellule 2", d.row("Plus haute")?.value)
        assertEquals("14 mV", d.row("Écart")?.value)
        assertEquals("compteur du BMS", d.row("Cycles")?.tag)
        assertEquals("3,310 V", d.row("Cellule 2")?.value)
    }

    @Test
    fun anAbsentFieldIsHiddenButAMeasuredZeroIsShown() {
        val d = deviceDetail(DeviceRole.SOLAR_CHARGER, reading(s(Metric.PV_POWER, 0.0), s(Metric.BATTERY_VOLTAGE, 13.1)))
        assertEquals("0", d.hero?.number)
        assertNull(d.row("Courant de charge"))
        assertNull(d.row("Courant vers la batterie"))
        assertNull(d.row("Puissance vers la batterie"))
        assertNotNull(d.row("Tension batterie"))
        assertNull(d.sections.firstOrNull { it.title == "État" })
    }

    @Test
    fun solarPowerToBatteryIsCalculatedAndTagged() {
        val d = deviceDetail(
            DeviceRole.SOLAR_CHARGER,
            reading(s(Metric.PV_POWER, 210.0), s(Metric.BATTERY_VOLTAGE, 13.5), s(Metric.BATTERY_CURRENT, 14.0), s(Metric.DEVICE_STATE, 3.0), s(Metric.ERROR_CODE, 0.0)),
        )
        assertEquals("189 W", d.row("Puissance vers la batterie")?.value)
        assertEquals("calculée", d.row("Puissance vers la batterie")?.tag)
        assertEquals("Bulk", d.row("Étape de charge")?.value)
        assertEquals("Aucune", d.row("Erreur")?.value)
    }

    @Test
    fun dcdcWithoutCurrentShowsNeitherCurrentNorPower() {
        val d = deviceDetail(DeviceRole.DCDC, reading(s(Metric.INPUT_VOLTAGE, 13.8), s(Metric.OUTPUT_VOLTAGE, 14.4), s(Metric.DEVICE_STATE, 4.0)))
        assertEquals("14,40", d.hero?.number)
        val labels = d.sections.flatMap { it.rows }.map { it.label }
        assertEquals(listOf("Tension d'entrée", "Tension de sortie", "Étape de charge"), labels)
    }

    @Test
    fun shuntTimeToGoComesFromTheShuntAndTemperatureIsHiddenWhenAbsent() {
        val d = deviceDetail(
            DeviceRole.SHUNT,
            reading(s(Metric.SOC, 64.0), s(Metric.BATTERY_VOLTAGE, 12.8), s(Metric.BATTERY_CURRENT, 5.0), s(Metric.TIME_TO_GO, 135.0), s(Metric.CONSUMED_AH, -20.0)),
        )
        assertEquals("annoncée par le shunt", d.row("Autonomie restante")?.tag)
        assertEquals("2 h 15 min", d.row("Autonomie restante")?.value)
        assertEquals("64", d.hero?.number)
        assertNull(d.row("Température"))
        assertNull(d.row("Tension point milieu"))
        assertEquals("64 W", d.row("Puissance (+ = charge)")?.value)
    }

    @Test
    fun batteryProtectionsAreReadable() {
        val d = deviceDetail(DeviceRole.BATTERY, reading(s(Metric.PROTECTION_FLAGS, 0.0), s(Metric.FET_STATE, 3.0)))
        assertEquals("Aucune", d.row("Alarmes")?.value)
        assertEquals("Charge et décharge actives", d.row("Relais")?.value)
        assertNull(d.hero)
    }
}
