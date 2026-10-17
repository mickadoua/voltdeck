package app.voltdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voltdeck.ble.BleTransport
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Reading
import app.voltdeck.drivers.KnownDevice
import app.voltdeck.drivers.victron.InMemoryVictronKeyStore
import app.voltdeck.drivers.victron.VictronDecoder
import app.voltdeck.drivers.victron.VictronKeyStore
import app.voltdeck.drivers.victron.VictronLinkStatus
import app.voltdeck.simulator.SimulationScenario
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private sealed interface Screen {
    data object Overview : Screen
    data object Devices : Screen
    data object AddDevice : Screen
    data class Shunt(val device: KnownDevice) : Screen
}

@Composable
fun App(
    keys: VictronKeyStore = remember { InMemoryVictronKeyStore() },
    shuntSetups: ShuntSetupStore = remember { InMemoryShuntSetupStore() },
    registry: DeviceRegistry = remember { InMemoryDeviceRegistry() },
    bluetooth: BluetoothAccess = AlwaysGrantedBluetooth,
    transport: (() -> BleTransport)? = null,
) {
    val scope = rememberCoroutineScope()
    val runtime = remember { VoltdeckRuntime(keys, shuntSetups, registry, transport, null, scope).also { it.start() } }
    App(runtime, bluetooth)
}

@Composable
fun App(runtime: VoltdeckRuntime, bluetooth: BluetoothAccess, background: BackgroundCollection = NoBackgroundCollection) {
    var darkOverride by remember { mutableStateOf<Boolean?>(null) }
    val dark = darkOverride ?: isSystemInDarkTheme()
    val live = runtime.live
    val session by runtime.session.collectAsState()
    val latest by runtime.latest.collectAsState()
    val changes by runtime.changes.collectAsState()
    val statuses by session.linkStatus.collectAsState()
    val devices = remember(session, changes) { session.devices }
    val setupOf = { id: DeviceId -> runtime.setupOf(id) }
    val overview = remember(latest, devices, changes) { buildOverview(devices, latest, setupOf) }
    var backgroundEnabled by remember { mutableStateOf(background.enabled) }
    val collectInBackground = session !is DemoSession && devices.isNotEmpty()
    LaunchedEffect(collectInBackground, backgroundEnabled, changes) { background.sync(collectInBackground) }

    var screen by remember { mutableStateOf<Screen>(Screen.Overview) }

    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        val p = LocalPalette.current
        Column(Modifier.fillMaxSize().background(p.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val current = screen) {
                is Screen.Shunt -> ShuntScreen(
                    title = session.title(current.device.deviceId),
                    status = statuses[current.device.deviceId] ?: VictronLinkStatus.WAITING,
                    initial = setupOf(current.device.deviceId),
                    onBack = { screen = Screen.Devices },
                    onSave = {
                        runtime.saveSetup(current.device.deviceId, it)
                        screen = Screen.Overview
                    },
                )
                Screen.AddDevice -> if (live != null) {
                    AddDeviceScreen(live, bluetooth, onBack = { screen = Screen.Devices }, onAdded = {
                        runtime.setDemo(false)
                        runtime.devicesChanged()
                        screen = Screen.Devices
                    })
                }
                else -> {
                    TopBar(
                        title = if (current == Screen.Overview) "Vue d'ensemble" else "Équipements",
                        subtitle = "${devices.size} équipement${if (devices.size > 1) "s" else ""} · lecture seule",
                        demo = session is DemoSession,
                        dark = dark,
                        onToggleTheme = { darkOverride = !dark },
                    )
                    Box(Modifier.weight(1f)) {
                        if (current == Screen.Overview) {
                            if (devices.isEmpty()) {
                                EmptyState { screen = Screen.AddDevice }
                            } else {
                                Column(Modifier.fillMaxSize()) {
                                    if (session is DemoSession && live != null) DemoBanner { screen = Screen.AddDevice }
                                    OverviewContent(overview, Modifier.weight(1f))
                                }
                            }
                        } else {
                            DevicesScreen(
                                session = session,
                                canGoLive = live != null,
                                latest = latest,
                                statuses = statuses,
                                setupOf = setupOf,
                                onMode = { runtime.setDemo(it) },
                                onScenario = { runtime.setScenario(it) },
                                onShunt = { screen = Screen.Shunt(it) },
                                onAdd = { screen = Screen.AddDevice },
                                onRemove = { id ->
                                    live?.remove(id)
                                    runtime.devicesChanged()
                                },
                                backgroundOn = if (background.supported) backgroundEnabled else null,
                                onBackground = {
                                    background.enabled = it
                                    backgroundEnabled = it
                                },
                            )
                        }
                    }
                    TabBar(current, onSelect = { screen = it })
                }
            }
        }
    }
}

@Composable
private fun DemoBanner(onAdd: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .background(p.warnBg, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onAdd)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt("Valeurs simulées", 15.sp, FontWeight.Bold, p.warnText)
            Txt("Ajoutez vos équipements pour voir votre installation.", 13.sp, color = p.warnText)
        }
        Txt("Ajouter", 14.sp, FontWeight.Bold, p.onAccent, Modifier.background(p.accent, CircleShape).padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Txt("Aucun équipement", 17.sp, FontWeight.Bold)
            Txt("Ajoutez votre batterie, votre régulateur solaire ou votre shunt pour voir l'installation ici.", 14.sp, color = p.ink2)
            PrimaryButton("Ajouter un équipement", Modifier.fillMaxWidth(), onAdd)
        }
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, demo: Boolean, dark: Boolean, onToggleTheme: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(title, 24.sp, FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (demo) Pill("Mode démo", Tone.WARN)
                Txt(subtitle, 13.sp, FontWeight.SemiBold, p.ink2)
            }
        }
        RoundButton(if (dark) "Passer au thème clair" else "Passer au thème sombre", onToggleTheme, if (dark) SunIcon else MoonIcon)
    }
}

@Composable
private fun TabBar(current: Screen, onSelect: (Screen) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(Screen.Overview to "Accueil", Screen.Devices to "Équipements").forEach { (target, label) ->
            val active = current == target
            Box(
                Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 56.dp)
                    .clickable(role = Role.Tab) { onSelect(target) }
                    .semantics { selected = active },
                contentAlignment = Alignment.Center,
            ) {
                Txt(
                    label, 14.sp, if (active) FontWeight.Bold else FontWeight.Medium, if (active) p.onAccent else p.ink2,
                    if (active) Modifier.background(p.accent, CircleShape).padding(horizontal = 18.dp, vertical = 8.dp) else Modifier,
                )
            }
        }
    }
}

/** Équipements : mesures lues par les pilotes, clés Victron, position du shunt ; en démo, scénarios du simulateur. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicesScreen(
    session: Session,
    canGoLive: Boolean,
    latest: Map<DeviceId, Reading>,
    statuses: Map<DeviceId, VictronLinkStatus>,
    setupOf: (DeviceId) -> ShuntSetup,
    onMode: (Boolean) -> Unit,
    onScenario: (SimulationScenario) -> Unit,
    onShunt: (KnownDevice) -> Unit,
    onAdd: () -> Unit,
    onRemove: (DeviceId) -> Unit,
    backgroundOn: Boolean?,
    onBackground: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val isDemo = session is DemoSession
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canGoLive) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Mes équipements", !isDemo) { onMode(false) }
                Choice("Démo", isDemo) { onMode(true) }
            }
        }
        if (session is DemoSession) {
            Txt(
                "Valeurs simulées, accélérées ×${DEMO_SPEED.toInt()}. Aucun équipement n'est lu. Les annonces Victron simulées " +
                    "sont de vraies trames chiffrées, décodées par le pilote.",
                14.sp, color = p.ink2,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SimulationScenario.entries.forEach { option -> Choice(option.id, option == session.scenario) { onScenario(option) } }
            }
            Txt("Clé des appareils simulés (publique, TEST ONLY) : ${session.deviceKeyHex}", 12.sp, color = p.muted)
        } else {
            PrimaryButton("Ajouter un équipement", Modifier.fillMaxWidth(), onAdd)
            if (backgroundOn != null) {
                Card(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Txt("Collecte écran éteint", 16.sp, FontWeight.Bold)
                            Txt("Voltdeck continue de lire et d'enregistrer avec une notification. Sinon, la collecte s'arrête avec l'application.", 13.sp, color = p.ink2)
                        }
                        Choice(if (backgroundOn) "Activée" else "Désactivée", backgroundOn) { onBackground(!backgroundOn) }
                    }
                }
            }
            if (session is LiveSession) {
                val error by session.scanError.collectAsState()
                error?.let { Pill(it, Tone.WARN) }
            }
        }
        session.devices.forEach { device ->
            val victron = device.role != DeviceRole.BATTERY
            DeviceCard(latest[device.deviceId], session.title(device.deviceId), if (victron) statuses[device.deviceId] ?: VictronLinkStatus.WAITING else null, simulated = isDemo) {
                if (device.role == DeviceRole.SHUNT) {
                    SecondaryButton("Position : ${placementSummary(setupOf(device.deviceId))}", Modifier.fillMaxWidth()) { onShunt(device) }
                }
                if (victron) KeyEntry(session, device)
                if (!isDemo) SecondaryButton("Retirer cet équipement", Modifier.fillMaxWidth()) { onRemove(device.deviceId) }
            }
        }
        if (!isDemo && session.devices.isNotEmpty()) {
            Txt("Batterie JBD : connexion automatique, et reconnexion après une coupure. Victron : écoute des annonces, sans connexion.", 13.sp, color = p.muted)
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Txt(
        label, 14.sp, FontWeight.SemiBold, if (selected) p.onAccent else p.ink,
        Modifier
            .background(if (selected) p.accent else p.surface, CircleShape)
            .border(1.5.dp, if (selected) p.accent else p.line, CircleShape)
            .clickable(role = Role.RadioButton) { onClick() }
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

private fun statusText(status: VictronLinkStatus): String = when (status) {
    VictronLinkStatus.WAITING -> "En attente d'une annonce…"
    VictronLinkStatus.OK -> "Déchiffrement réussi"
    VictronLinkStatus.NO_KEY -> "Clé absente : saisissez-la ci-dessous."
    VictronLinkStatus.WRONG_KEY -> VictronDecoder.WRONG_KEY_MESSAGE
}

/** Saisie masquée de la clé : le texte n'est conservé que le temps de la saisie, puis effacé. */
@Composable
private fun KeyEntry(session: Session, device: KnownDevice) {
    val p = LocalPalette.current
    var text by remember(session) { mutableStateOf("") }
    var message by remember(session) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt("Clé d'annonce", 14.sp, FontWeight.SemiBold)
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(KEY_INPUT_LIMIT) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            cursorBrush = SolidColor(p.ink),
            textStyle = TextStyle(color = p.ink, fontSize = 16.sp),
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 52.dp)
                .border(1.5.dp, p.ink2, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 15.dp),
        )
        SecondaryButton("Vérifier et enregistrer la clé", Modifier.fillMaxWidth()) {
            when (val outcome = session.submitKey(device, text)) {
                KeyEntryOutcome.Saved -> {
                    text = ""
                    message = "Clé enregistrée."
                }
                is KeyEntryOutcome.Rejected -> message = outcome.message
            }
        }
        message?.let { Txt(it, 13.sp, color = p.ink2) }
    }
}

@Composable
private fun DeviceCard(
    reading: Reading?,
    title: String,
    status: VictronLinkStatus? = null,
    simulated: Boolean,
    extra: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    Card(Modifier.fillMaxWidth()) {
        Txt(title, 18.sp, FontWeight.Bold)
        if (status != null) Txt(statusText(status), 13.sp, color = p.ink2)
        if (reading != null) {
            val local = reading.at.toLocalDateTime(TimeZone.currentSystemDefault())
            Txt("${if (simulated) "Heure simulée" else "Dernière mesure"} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}", 13.sp, color = p.muted)
            reading.samples.forEach { sample ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Txt(sampleLabel(sample), 15.sp, color = p.ink2, modifier = Modifier.weight(1f))
                    Txt(sampleText(sample), 16.sp, FontWeight.SemiBold)
                }
            }
        }
        extra?.invoke()
    }
}

private const val KEY_INPUT_LIMIT = 64
