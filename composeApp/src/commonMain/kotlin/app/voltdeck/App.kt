package app.voltdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voltdeck.core.DeviceId
import app.voltdeck.core.Reading
import app.voltdeck.drivers.KnownDevice
import app.voltdeck.drivers.victron.InMemoryVictronKeyStore
import app.voltdeck.drivers.victron.VictronDecoder
import app.voltdeck.drivers.victron.VictronKeyStore
import app.voltdeck.drivers.victron.VictronLinkStatus
import app.voltdeck.simulator.SimulatedPlant
import app.voltdeck.simulator.SimulationScenario
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val Background = Color(0xFF0B0F14)
private val Surface = Color(0xFF161C24)
private val TextPrimary = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B98A5)
private val Accent = Color(0xFF1F6FEB)

@Composable
fun App(keys: VictronKeyStore = remember { InMemoryVictronKeyStore() }) {
    var scenario by remember { mutableStateOf(SimulationScenario.SUNNY_DAY) }
    val session = remember(scenario, keys) { DemoSession(scenario, keys) }
    DemoModeScreen(session, onScenario = { scenario = it })
}

/** Écran brut du mode démo : le dernier [Reading] de chaque équipement simulé, lu par les pilotes, et la saisie des clés. */
@Composable
fun DemoModeScreen(session: DemoSession, onScenario: (SimulationScenario) -> Unit) {
    val latest = remember(session) { mutableStateMapOf<DeviceId, Reading>() }
    LaunchedEffect(session) { session.readings().collect { latest[it.deviceId] = it } }
    val statuses by session.driver.diagnostics.status.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("Mode démo", style = TextStyle(color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold))
        BasicText(
            "Valeurs simulées, accélérées ×${DEMO_SPEED.toInt()}. Aucun équipement n'est lu. Les annonces Victron simulées " +
                "sont de vraies trames chiffrées, décodées par le pilote.",
            style = TextStyle(color = TextSecondary, fontSize = 14.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SimulationScenario.entries.forEach { option ->
                Button(option.id, selected = option == session.scenario) { onScenario(option) }
            }
        }
        BasicText(
            "Clé des appareils simulés (publique, TEST ONLY) : ${session.deviceKeyHex}",
            style = TextStyle(color = TextSecondary, fontSize = 12.sp),
        )
        latest.values.filter { it.deviceId == SimulatedPlant.BATTERY_ID }.forEach { DeviceCard(it) }
        session.victronDevices.forEach { device ->
            DeviceCard(
                reading = latest[device.id],
                title = deviceTitle(device.id),
                status = statuses[device.id] ?: VictronLinkStatus.WAITING,
                keyEntry = { KeyEntry(session, device) },
            )
        }
    }
}

private fun statusText(status: VictronLinkStatus): String = when (status) {
    VictronLinkStatus.WAITING -> "En attente d'une annonce…"
    VictronLinkStatus.OK -> "Déchiffrement réussi"
    VictronLinkStatus.NO_KEY -> "Clé absente : saisissez-la ci-dessous."
    VictronLinkStatus.WRONG_KEY -> VictronDecoder.WRONG_KEY_MESSAGE
}

@Composable
private fun Button(label: String, selected: Boolean = false, onClick: () -> Unit) {
    BasicText(
        label,
        modifier = Modifier
            .background(if (selected) Accent else Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(color = TextPrimary, fontSize = 14.sp),
    )
}

/** Saisie masquée de la clé : le texte n'est conservé que le temps de la saisie, puis effacé. */
@Composable
private fun KeyEntry(session: DemoSession, device: KnownDevice) {
    var text by remember(session) { mutableStateOf("") }
    var message by remember(session) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(KEY_INPUT_LIMIT) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            cursorBrush = SolidColor(TextPrimary),
            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().background(Background).padding(10.dp),
        )
        Button("Vérifier et enregistrer la clé") {
            when (val outcome = session.submitKey(device, text)) {
                KeyEntryOutcome.Saved -> {
                    text = ""
                    message = "Clé enregistrée."
                }
                is KeyEntryOutcome.Rejected -> message = outcome.message
            }
        }
        message?.let { BasicText(it, style = TextStyle(color = TextSecondary, fontSize = 13.sp)) }
    }
}

@Composable
private fun DeviceCard(
    reading: Reading?,
    title: String? = null,
    status: VictronLinkStatus? = null,
    keyEntry: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().background(Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            title ?: reading?.let { deviceTitle(it.deviceId) } ?: "",
            style = TextStyle(color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        )
        if (status != null) {
            BasicText(statusText(status), style = TextStyle(color = TextSecondary, fontSize = 13.sp))
        }
        if (reading != null) {
            val local = reading.at.toLocalDateTime(TimeZone.currentSystemDefault())
            BasicText(
                "Heure simulée ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}",
                style = TextStyle(color = TextSecondary, fontSize = 13.sp),
            )
            reading.samples.forEach { sample ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BasicText(sampleLabel(sample), style = TextStyle(color = TextSecondary, fontSize = 16.sp))
                    BasicText(
                        sampleText(sample),
                        style = TextStyle(color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
        keyEntry?.invoke()
    }
}

private const val KEY_INPUT_LIMIT = 64
