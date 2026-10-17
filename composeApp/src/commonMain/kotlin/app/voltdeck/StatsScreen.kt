package app.voltdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.storage.AggRow
import app.voltdeck.storage.AggregateReader
import app.voltdeck.storage.EnergyBucket
import app.voltdeck.storage.Level
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Composable
fun StatsScreen(runtime: VoltdeckRuntime) {
    val p = LocalPalette.current
    val session by runtime.session.collectAsState()
    val database by runtime.database.collectAsState()
    val changes by runtime.changes.collectAsState()
    val today = remember(session) { session.clock.now().toLocalDateTime(runtime.zone).date }
    var period by remember(session) { mutableStateOf(StatsPeriod(PeriodKind.DAY, today)) }
    var data by remember { mutableStateOf<StatsData?>(null) }
    var table by remember { mutableStateOf(false) }

    val battery = remember(session, changes) { batteryOf(session, runtime) }
    LaunchedEffect(database, period, changes) {
        val stats = runtime.stats()
        val history = runtime.energyHistory()
        val db = database
        data = if (stats == null || history == null || db == null) {
            null
        } else {
            loadStats(stats, history, AggregateReader(db), battery, period, runtime.zone, session.clock.now())
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().background(p.surface, CircleShape).border(1.5.dp, p.line, CircleShape).padding(4.dp)) {
            PeriodKind.entries.forEach { kind ->
                val active = period.kind == kind
                Box(
                    Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 44.dp)
                        .background(if (active) p.accent else p.surface, CircleShape)
                        .clickable(role = Role.Tab) { period = StatsPeriod(kind, period.anchor) }
                        .semantics { selected = active },
                    contentAlignment = Alignment.Center,
                ) { Txt(kind.label, 14.sp, if (active) FontWeight.Bold else FontWeight.Medium, if (active) p.onAccent else p.ink2) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton("Période précédente", { period = period.previous() }, BackIcon)
            Txt(period.label, 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
            if (period.contains(today)) {
                Box(Modifier.size(44.dp))
            } else {
                RoundButton("Période suivante", { period = period.next() }, ForwardIcon)
            }
        }

        val d = data
        if (database == null) {
            Card(Modifier.fillMaxWidth()) { Txt("Pas de statistiques : la base de données n'est pas disponible sur cet appareil.", 14.sp, color = p.ink2) }
            return@Column
        }
        if (d == null) {
            Txt("Chargement…", 14.sp, color = p.ink2)
            return@Column
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TotalCard("Production", d.produced, d.producedComparison, p.solar, SunIcon, Modifier.weight(1f))
            TotalCard("Consommation", d.consumed, d.consumedComparison, p.load, PlugIcon, Modifier.weight(1f))
        }

        Card(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(if (d.level == Level.HOUR) "Énergie par heure (Wh)" else "Énergie par jour (Wh)", 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                Txt(
                    if (table) "Graphique" else "Tableau", 14.sp, FontWeight.SemiBold, p.ink,
                    Modifier.border(1.5.dp, p.ink, RoundedCornerShape(12.dp)).clickable(role = Role.Button) { table = !table }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Legend(listOf(p.solar to "Produite", p.load to "Consommée"))
            val slots = remember(d) { slotsOf(d, runtime.zone) }
            if (table) EnergyTable(slots, d, runtime.zone) else EnergyBars(slots, d, runtime.zone)
            Txt("Consommation calculée : production côté batterie − flux de la batterie. Une période sans mesure reste vide.", 12.sp, color = p.muted)
        }

        Card(Modifier.fillMaxWidth()) {
            Txt("Charge de la batterie (%)", 17.sp, FontWeight.Bold)
            if (d.soc.isEmpty()) {
                Txt("Aucune mesure de charge sur cette période.", 14.sp, color = p.ink2)
            } else {
                SocLine(d.soc, d.period.first.atStartOfDayIn(runtime.zone), d.period.kind, runtime.zone)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Txt("Minimum et maximum", 17.sp, FontWeight.Bold)
            ExtremumRow("Charge la plus basse", d.socMin, "%", 0, runtime.zone, d.period.kind)
            ExtremumRow("Charge la plus haute", d.socMax, "%", 0, runtime.zone, d.period.kind)
            ExtremumRow("Tension la plus basse", d.voltageMin, "V", 2, runtime.zone, d.period.kind)
            ExtremumRow("Tension la plus haute", d.voltageMax, "V", 2, runtime.zone, d.period.kind)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
    }
}

private fun batteryOf(session: Session, runtime: VoltdeckRuntime): DeviceId? =
    session.devices.firstOrNull { it.role == DeviceRole.SHUNT && runtime.setupOf(it.deviceId).placement == ShuntPlacement.BATTERY }?.deviceId
        ?: session.devices.firstOrNull { it.role == DeviceRole.BATTERY }?.deviceId

/** Créneaux de la période : 24 heures ou un par jour, présents même sans mesure (un trou se voit). */
private fun slotsOf(d: StatsData, zone: TimeZone): List<Instant> = if (d.level == Level.HOUR) {
    val start = d.period.first.atStartOfDayIn(zone)
    val end = d.period.endExclusive.atStartOfDayIn(zone)
    generateSequence(start) { it + 1.hours }.takeWhile { it < end }.toList()
} else {
    d.period.days.map { it.atStartOfDayIn(zone) }
}

private fun slotLabel(at: Instant, level: Level, zone: TimeZone): String {
    val t = at.toLocalDateTime(zone)
    return if (level == Level.HOUR) "${t.hour} h" else "${t.date.day}/${t.date.month.ordinal + 1}"
}

@Composable
private fun TotalCard(title: String, total: EnergyTotal?, comparison: Comparison?, color: Color, icon: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier, radius = 18.dp, padding = 14.dp) {
        TileHeader(title, color, icon)
        Value(total?.let { frDecimal(it.kwh, 2) } ?: DASH, "kWh", 26.sp)
        if (total != null && total.calculated) Tag("calculée")
        total?.coverage?.takeIf { it < 0.99 }?.let { Txt("Couverture ${frDecimal(it * 100, 0)} %", 12.sp, color = p.muted) }
        comparison?.let {
            val against = if (it.partial) "les ${it.days} premiers jours de la période précédente" else "la période précédente"
            Txt("${signedFr(it.percent, 0)} % vs $against", 12.sp, FontWeight.SemiBold, p.ink2)
        }
    }
}

@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Txt(label, 13.sp, FontWeight.SemiBold, p.ink2)
            }
        }
    }
}

/** Graduation « ronde » au-dessus de [max] : 1, 2, 2,5 ou 5 × 10ⁿ. */
fun niceCeiling(max: Double): Double {
    if (max <= 0.0) return 1.0
    val magnitude = 10.0.pow(kotlin.math.floor(log10(max)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= max }
    return step * magnitude
}

@Composable
private fun EnergyBars(slots: List<Instant>, d: StatsData, zone: TimeZone) {
    val p = LocalPalette.current
    val columns = remember(d, slots) { energyColumns(slots, d.buckets) }
    val peak = columns.produced.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
    Txt("Touchez une colonne pour lire ses valeurs. Pincez pour zoomer, glissez pour défiler.", 13.sp, FontWeight.SemiBold, p.muted)
    EnergyChart(
        columns = columns,
        slotLabel = { slotLabel(slots[it], d.level, zone) },
        description = "Énergie produite et consommée, ${slots.size} périodes",
        modifier = Modifier.fillMaxWidth().height(200.dp),
    )
    peak?.let { Txt("Pic de production : ${wh(columns.produced[it])} (${slotLabel(slots[it], d.level, zone)})", 12.sp, FontWeight.Bold, p.ink2) }
}

private fun wh(v: Double?) = v?.let { "${frDecimal(it, 0)} Wh" } ?: DASH

@Composable
private fun EnergyTable(slots: List<Instant>, d: StatsData, zone: TimeZone) {
    val p = LocalPalette.current
    val byStart = d.buckets.associateBy { it.start }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Txt("Période", 12.sp, FontWeight.Bold, p.muted, Modifier.weight(1f))
            Txt("Produite", 12.sp, FontWeight.Bold, p.muted, Modifier.weight(1f))
            Txt("Consommée", 12.sp, FontWeight.Bold, p.muted, Modifier.weight(1f))
        }
        slots.forEach { s ->
            val b: EnergyBucket? = byStart[s]
            Row(Modifier.fillMaxWidth()) {
                Txt(slotLabel(s, d.level, zone), 13.sp, color = p.ink2, modifier = Modifier.weight(1f))
                Txt(wh(b?.producedWh), 13.sp, modifier = Modifier.weight(1f))
                Txt(wh(b?.consumedWh), 13.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SocLine(rows: List<AggRow>, from: Instant, kind: PeriodKind, zone: TimeZone) {
    val p = LocalPalette.current
    val segments = remember(rows, from) { segmentsOf(rows, from) }
    SegmentedLineChart(
        segments = segments,
        color = p.battery,
        unit = "%",
        decimals = 0,
        xLabel = { hours ->
            val t = (from + (hours * 3600).toLong().seconds).toLocalDateTime(zone)
            if (kind == PeriodKind.DAY) "${t.hour} h" else "${t.date.day}/${t.date.month.ordinal + 1}"
        },
        description = "Charge de la batterie, de 0 à 100 %",
        modifier = Modifier.fillMaxWidth().height(180.dp),
        fixedY = 0.0..100.0,
    )
}

@Composable
private fun ExtremumRow(label: String, e: Extremum?, unit: String, decimals: Int, zone: TimeZone, kind: PeriodKind) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 14.sp, color = p.ink2, modifier = Modifier.weight(1f))
        if (e == null) {
            Txt(DASH, 14.sp, FontWeight.SemiBold)
        } else {
            val t = e.at.toLocalDateTime(zone)
            val whenText = if (kind == PeriodKind.DAY) "${t.hour} h ${t.minute.toString().padStart(2, '0')}" else "le ${t.date.day}/${t.date.month.ordinal + 1}"
            Txt("${frDecimal(e.value, decimals)} $unit · $whenText", 14.sp, FontWeight.SemiBold)
        }
    }
}
