package app.voltdeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val ACTIVE_WATTS = 1.0

@Composable
fun OverviewContent(overview: Overview, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        if (maxWidth >= 600.dp) Landscape(overview) else Portrait(overview)
    }
}

@Composable
private fun Portrait(o: Overview) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        val top = sourceTiles(o)
        if (top.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                top.forEach { tile -> Column(Modifier.weight(1f).fillMaxHeight()) { tile.content(Modifier.weight(1f).fillMaxWidth()) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                top.forEach { tile -> FlowLink(tile.color(p), tile.active, vertical = true, modifier = Modifier.weight(1f).height(28.dp)) }
            }
        }
        BatteryCard(o, Modifier.fillMaxWidth())
        if (o.consumption != null || o.loads.isNotEmpty()) {
            FlowLink(p.load, (o.consumption?.watts ?: 0.0) > ACTIVE_WATTS, vertical = true, modifier = Modifier.fillMaxWidth().height(28.dp))
            ConsumptionCard(o, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Landscape(o: Overview) {
    val p = LocalPalette.current
    val top = sourceTiles(o)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(horizontal = 20.dp, vertical = 4.dp)) {
        if (top.isNotEmpty()) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                top.forEach { it.content(Modifier.weight(1f).fillMaxWidth()) }
            }
            Column(Modifier.width(48.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                top.forEach { FlowLink(it.color(p), it.active, vertical = false, modifier = Modifier.weight(1f).fillMaxWidth()) }
            }
        }
        BatteryCard(o, Modifier.weight(1.4f).fillMaxHeight())
        if (o.consumption != null || o.loads.isNotEmpty()) {
            FlowLink(p.load, (o.consumption?.watts ?: 0.0) > ACTIVE_WATTS, vertical = false, modifier = Modifier.width(48.dp).fillMaxHeight())
            ConsumptionCard(o, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

private class SourceTile(val color: (Palette) -> androidx.compose.ui.graphics.Color, val active: Boolean, val content: @Composable (Modifier) -> Unit)

private fun sourceTiles(o: Overview): List<SourceTile> = buildList {
    o.solar?.let { s -> add(SourceTile({ it.solar }, (s.powerW ?: 0.0) > ACTIVE_WATTS) { m -> SolarCard(s, m) }) }
    o.dcdc?.let { d -> add(SourceTile({ it.solar }, false) { m -> DcdcCard(d, m) }) }
    o.sources.forEach { c -> add(SourceTile({ it.solar }, (c.powerW ?: 0.0) > ACTIVE_WATTS) { m -> SourceCircuitCard(c, m) }) }
}

private fun watts(value: Double?) = value?.let { frDecimal(it, 0) } ?: DASH

@Composable
private fun SolarCard(s: SolarTile, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier, radius = 18.dp, padding = 14.dp) {
        TileHeader("Solaire", p.solar, SunIcon)
        Value(watts(s.powerW), "W", 34.sp)
        val side = listOfNotNull(s.batteryVoltage?.let { "${frDecimal(it, 2)} V" }, s.batteryCurrent?.let { "${frDecimal(it, 1)} A" })
        if (side.isNotEmpty()) Txt(side.joinToString(" · ") + "\ncôté batterie", 13.sp, color = p.ink2)
        s.yieldTodayKwh?.let { Txt("Aujourd'hui ${frDecimal(it, 2)} kWh", 13.sp, color = p.ink2) }
        s.state?.let { Pill(victronStateText(it), if (it == 0) Tone.NEUTRAL else Tone.GOOD) }
    }
}

@Composable
private fun DcdcCard(d: DcdcTile, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier, radius = 18.dp, padding = 14.dp) {
        TileHeader("DC-DC", if (d.running) p.solar else p.grid, SwapIcon)
        Value(d.outputVoltage?.let { frDecimal(it, 2) } ?: DASH, "V", 34.sp, color = if (d.running) p.ink else p.muted)
        d.inputVoltage?.let { Txt("Entrée ${frDecimal(it, 2)} V", 13.sp, color = p.ink2) }
        Txt("Courant non fourni par l'appareil", 12.sp, color = p.muted)
        d.state?.let { Pill(victronStateText(it), if (d.running) Tone.GOOD else Tone.NEUTRAL) }
    }
}

@Composable
private fun SourceCircuitCard(c: CircuitTile, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier, radius = 18.dp, padding = 14.dp) {
        TileHeader(c.name, p.solar, SunIcon)
        Value(watts(c.powerW), "W", 34.sp)
        c.current?.let { Txt("${frDecimal(it, 1)} A · shunt", 13.sp, color = p.ink2) }
    }
}

@Composable
private fun BatteryCard(o: Overview, modifier: Modifier) {
    val p = LocalPalette.current
    val b = o.battery
    Card(modifier) {
        TileHeader("Batterie", p.battery, BatteryIcon) {
            when (b?.trend) {
                BatteryTrend.CHARGING -> Pill("En charge", Tone.GOOD)
                BatteryTrend.DISCHARGING -> Pill("En décharge", Tone.NEUTRAL)
                BatteryTrend.IDLE -> Pill("Au repos", Tone.NEUTRAL)
                null -> Pill("Aucune mesure", Tone.WARN)
            }
        }
        if (b == null) {
            Txt("Ajoutez une batterie à BMS ou réglez un shunt sur le négatif de la batterie.", 14.sp, color = p.ink2)
            return@Card
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Value(b.soc?.let { frDecimal(it, 0) } ?: DASH, "%", 68.sp)
            if (b.remainingAh != null && b.nominalAh != null) {
                Txt("${frDecimal(b.remainingAh, 0)} / ${frDecimal(b.nominalAh, 0)} Ah", 14.sp, FontWeight.SemiBold, p.ink2, Modifier.padding(bottom = 12.dp))
            }
        }
        b.soc?.let { Gauge((it / 100).toFloat(), p.battery, Modifier.fillMaxWidth().height(16.dp)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("Tension", b.voltage?.let { frDecimal(it, 2) }, "V", Modifier.weight(1f))
            MetricCell("Courant", b.current?.let { signedFr(it, 1) }, "A", Modifier.weight(1f))
            MetricCell("Puissance", b.powerW?.let { signedFr(it, 0) }, "W", Modifier.weight(1f))
            MetricCell("Temp.", b.temperature?.let { frDecimal(it, 0) }, "°C", Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val autonomy = o.autonomy
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    autonomy.minutes != null -> {
                        val m = autonomy.minutes.toLong()
                        Txt("Autonomie ${m / 60} h ${(m % 60).toString().padStart(2, '0')}", 13.sp, color = p.ink2)
                        Tag(if (autonomy.estimated) "estimée" else "shunt")
                    }
                    autonomy.gap == AutonomyGap.CHARGING -> Txt("Autonomie : $DASH (en charge)", 13.sp, color = p.ink2)
                    else -> Txt("Autonomie : $DASH", 13.sp, color = p.ink2)
                }
            }
            BatteryDetailLine(b)
        }
        Txt(if (b.source == BatterySource.SHUNT) "Mesure : shunt de batterie" else "Mesure : BMS de la batterie", 12.sp, color = p.muted)
    }
}

/** Cellules et cycles ; des cycles estimés sur le shunt portent l'étiquette « estimé » pour ne pas passer pour un compteur de BMS. */
@Composable
internal fun BatteryDetailLine(b: BatteryTile) {
    val p = LocalPalette.current
    val cells = if (b.cellMin != null && b.cellMax != null) "Cellules ${frDecimal(b.cellMin, 2)}–${frDecimal(b.cellMax, 2)} V" else null
    val cycles = b.cycles?.let { cyclesLabel(it, b.cyclesEstimated) }
    if (cells == null && cycles == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(listOfNotNull(cells, cycles).joinToString(" · "), 13.sp, color = p.ink2)
        if (cycles != null && b.cyclesEstimated) Tag("estimé")
    }
}

@Composable
private fun MetricCell(label: String, value: String?, unit: String, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(label, 12.sp, FontWeight.SemiBold, p.muted)
        Value(value ?: DASH, unit, 18.sp)
    }
}

@Composable
private fun ConsumptionCard(o: Overview, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier, radius = 18.dp, padding = 16.dp) {
        TileHeader("Consommation DC", p.load, PlugIcon)
        val c = o.consumption
        if (c != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("calculée")
                if (c.incomplete) Tag("incomplète", p.warnText)
            }
            Value(frDecimal(c.watts, 0), "W", 34.sp)
            val batteryVolts = o.battery?.voltage
            if (batteryVolts != null && batteryVolts > 0) Txt("≈ ${frDecimal(c.watts / batteryVolts, 1)} A à ${frDecimal(batteryVolts, 2)} V", 13.sp, color = p.ink2)
            Txt(
                if (c.incomplete) "Une source sans mesure de courant charge la batterie : valeur sous-estimée." else "Sources mesurées − flux de la batterie.",
                12.sp, color = p.muted,
            )
        } else {
            Txt("Masquée : aucune mesure de la batterie.", 14.sp, color = p.ink2)
        }
        o.loads.forEach { load ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(load.name, 15.sp, FontWeight.SemiBold)
                    Tag("shunt")
                }
                Value(watts(load.powerW), "W", 20.sp)
            }
        }
    }
}
