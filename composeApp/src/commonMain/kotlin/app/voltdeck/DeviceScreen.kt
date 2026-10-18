package app.voltdeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voltdeck.core.DeviceRole
import app.voltdeck.drivers.KnownDevice
import app.voltdeck.drivers.victron.VictronLinkStatus
import app.voltdeck.storage.AggRow
import app.voltdeck.storage.AggregateReader
import app.voltdeck.storage.SeriesKey
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Fenêtre de la courbe d'historique d'un équipement. */
private val HISTORY_WINDOW = 24.hours

/** Un équipement en détail : mesure principale, courbe des 24 dernières heures, puis toutes ses mesures par rubrique. */
@Composable
fun DeviceScreen(
    runtime: VoltdeckRuntime,
    device: KnownDevice,
    onBack: () -> Unit,
    onShunt: () -> Unit,
) {
    val p = LocalPalette.current
    val session by runtime.session.collectAsState()
    val latest by runtime.latest.collectAsState()
    val statuses by session.linkStatus.collectAsState()
    val database by runtime.database.collectAsState()
    val changes by runtime.changes.collectAsState()
    val reading = latest[device.deviceId]
    val detail = remember(reading, device.role) { reading?.let { deviceDetail(device.role, it) } }
    val victron = device.role != DeviceRole.BATTERY
    val status = if (victron) statuses[device.deviceId] ?: VictronLinkStatus.WAITING else null

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton("Retour", onBack, BackIcon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(session.title(device.deviceId), 22.sp, FontWeight.Bold)
                if (reading != null) {
                    val t = reading.at.toLocalDateTime(runtime.zone)
                    val label = if (session is DemoSession) "Heure simulée" else "Dernière mesure"
                    Txt("$label ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}", 13.sp, color = p.muted)
                }
            }
        }
        if (status != null && status != VictronLinkStatus.OK) Txt(deviceStatusText(status), 14.sp, color = p.ink2)

        if (detail == null) {
            Card(Modifier.fillMaxWidth()) { Txt("Aucune mesure reçue pour l'instant.", 14.sp, color = p.ink2) }
        } else {
            detail.hero?.let { hero ->
                Card(Modifier.fillMaxWidth()) {
                    Txt(hero.label, 15.sp, FontWeight.Bold, p.ink2)
                    Value(hero.number, hero.unit, 56.sp)
                    hero.tag?.let { Tag(it) }
                }
            }
            val history = detail.history
            if (history != null && database != null) {
                HistoryCard(runtime, device, history, changes)
            }
            detail.sections.forEach { section ->
                Card(Modifier.fillMaxWidth()) {
                    Txt(section.title, 17.sp, FontWeight.Bold)
                    section.rows.forEach { row ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Txt(row.label, 15.sp, color = p.ink2)
                                row.tag?.let { Tag(it) }
                            }
                            Txt(row.value, 16.sp, FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (device.role == DeviceRole.SHUNT) {
            SecondaryButton("Position : ${placementSummary(runtime.setupOf(device.deviceId))}", Modifier.fillMaxWidth(), onShunt)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun HistoryCard(runtime: VoltdeckRuntime, device: KnownDevice, spec: HistorySpec, changes: Any?) {
    val p = LocalPalette.current
    val session by runtime.session.collectAsState()
    val database by runtime.database.collectAsState()
    var rows by remember { mutableStateOf<List<AggRow>?>(null) }
    val to = remember(session) { session.clock.now() }
    LaunchedEffect(database, spec, changes, device.deviceId) {
        val db = database ?: return@LaunchedEffect
        val now = session.clock.now()
        rows = AggregateReader(db).series(SeriesKey(device.deviceId, spec.metric), now - HISTORY_WINDOW, now, now, runtime.zone).rows
    }
    val from = to - HISTORY_WINDOW
    Card(Modifier.fillMaxWidth()) {
        Txt(spec.title, 17.sp, FontWeight.Bold)
        val loaded = rows
        if (loaded == null) {
            Txt("Chargement…", 14.sp, color = p.ink2)
        } else if (loaded.isEmpty()) {
            Txt("Aucune mesure enregistrée sur les dernières 24 heures.", 14.sp, color = p.ink2)
        } else {
            val segments = remember(loaded, from) { segmentsOf(loaded, from) }
            SegmentedLineChart(
                segments = segments,
                color = if (spec.metric == app.voltdeck.core.Metric.PV_POWER) p.solar else p.battery,
                unit = spec.unit,
                decimals = spec.decimals,
                xLabel = { hours -> "${(from + (hours * 3600).toLong().seconds).toLocalDateTime(runtime.zone).hour} h" },
                description = spec.title,
                modifier = Modifier.fillMaxWidth().height(180.dp),
                fixedY = if (spec.fixedPercent) 0.0..100.0 else null,
            )
        }
    }
}

fun deviceStatusText(status: VictronLinkStatus): String = when (status) {
    VictronLinkStatus.WAITING -> "En attente d'une annonce…"
    VictronLinkStatus.OK -> "Déchiffrement réussi"
    VictronLinkStatus.NO_KEY -> "Clé absente : saisissez-la dans la liste des équipements."
    VictronLinkStatus.WRONG_KEY -> app.voltdeck.drivers.victron.VictronDecoder.WRONG_KEY_MESSAGE
}
