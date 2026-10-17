package app.voltdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voltdeck.core.DeviceRole
import kotlinx.coroutines.launch

@Composable
fun AddDeviceScreen(session: LiveSession, bluetooth: BluetoothAccess, onBack: () -> Unit, onAdded: () -> Unit) {
    val p = LocalPalette.current
    var granted by remember { mutableStateOf(bluetooth.granted()) }
    var selected by remember { mutableStateOf<Found?>(null) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton("Retour", { if (selected != null) selected = null else onBack() }, BackIcon)
            Txt(if (selected == null) "Ajouter un équipement" else "Étape 2 sur 2", 24.sp, FontWeight.Bold)
        }
        Row(
            Modifier.fillMaxWidth().background(p.grid, RoundedCornerShape(14.dp)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Canvas(Modifier.sizeIn(minWidth = 18.dp, minHeight = 18.dp)) { LockIcon(p.ink2) }
            Txt("Voltdeck lit les équipements sans jamais rien y écrire.", 14.sp, color = p.ink2)
        }

        val current = selected
        when {
            !granted -> Card(Modifier.fillMaxWidth()) {
                Txt("Autorisation Bluetooth", 17.sp, FontWeight.Bold)
                Txt("Pour trouver vos équipements, Voltdeck a besoin de l'autorisation « Appareils à proximité ». Elle ne sert pas à vous localiser.", 14.sp, color = p.ink2)
                PrimaryButton("Autoriser le Bluetooth", Modifier.fillMaxWidth()) { bluetooth.request { granted = it } }
            }
            current == null -> DiscoveryList(session) { selected = it }
            else -> Confirm(session, current, onAdded)
        }
    }
}

@Composable
private fun DiscoveryList(session: LiveSession, onPick: (Found) -> Unit) {
    val p = LocalPalette.current
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    LaunchedEffect(session) { session.discover().collect { found = it } }
    val error by session.scanError.collectAsState()

    Txt("Étape 1 sur 2 · Équipements à portée", 17.sp, FontWeight.Bold)
    error?.let { Pill(it, Tone.WARN) }
    if (found.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Txt("Recherche en cours…", 16.sp, FontWeight.SemiBold)
            Txt(
                "Pris en charge : batteries à BMS JBD (Ultimatron…) et appareils Victron dont l'option « Instant readout » est activée dans VictronConnect.",
                14.sp, color = p.ink2,
            )
        }
    }
    found.forEach { item ->
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 72.dp)
                .background(p.surface, shape)
                .border(1.5.dp, p.line, shape)
                .clickable(role = Role.Button) { onPick(item) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Badge(if (item.candidate.role == DeviceRole.SOLAR_CHARGER) p.solar else p.battery, if (item.candidate.role == DeviceRole.SOLAR_CHARGER) SunIcon else BatteryIcon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(item.name ?: roleLabel(item.candidate.role), 16.sp, FontWeight.Bold)
                Txt(listOfNotNull(roleLabel(item.candidate.role), brand(item), item.candidate.model).joinToString(" · "), 13.sp, color = p.ink2)
            }
            Txt(signal(item.rssi), 13.sp, FontWeight.SemiBold, p.ink2)
        }
    }
}

private fun brand(item: Found) = if (item.candidate.driverId == "victron.ir") "Victron" else "JBD"

private fun signal(rssi: Int) = when {
    rssi >= -65 -> "Signal fort"
    rssi >= -80 -> "Signal moyen"
    else -> "Signal faible"
}

@Composable
private fun Confirm(session: LiveSession, found: Found, onAdded: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val victron = found.candidate.driverId == "victron.ir"
    var name by remember(found) { mutableStateOf(found.name ?: roleLabel(found.candidate.role)) }
    var key by remember(found) { mutableStateOf("") }
    var busy by remember(found) { mutableStateOf(false) }
    var message by remember(found) { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Txt(roleLabel(found.candidate.role), 17.sp, FontWeight.Bold)
        Txt(listOfNotNull(brand(found), found.candidate.model, signal(found.rssi)).joinToString(" · "), 13.sp, color = p.ink2)
    }
    Field("Nom affiché", name, onChange = { name = it.take(40) })
    if (victron) {
        Field("Clé d'annonce", key, secret = true, onChange = { key = it.take(64) })
        Txt("Dans VictronConnect, ouvrez l'appareil puis ses infos produit : activez « Instant readout » et affichez la clé de chiffrement (32 caractères).", 13.sp, color = p.muted)
    } else {
        Txt("Voltdeck va se connecter à la batterie et lire sa version pour confirmer que c'est bien un BMS JBD.", 13.sp, color = p.muted)
    }
    message?.let { Pill(it, Tone.CRIT) }
    PrimaryButton(if (busy) "Vérification…" else if (victron) "Vérifier la clé et ajouter" else "Vérifier et ajouter", Modifier.fillMaxWidth()) {
        if (busy) return@PrimaryButton
        val label = name.ifBlank { roleLabel(found.candidate.role) }
        if (victron) {
            when (val outcome = session.addVictron(found, label, key)) {
                AddOutcome.Added -> onAdded()
                is AddOutcome.Rejected -> message = outcome.message
            }
        } else {
            busy = true
            scope.launch {
                when (val outcome = session.addJbd(found, label)) {
                    AddOutcome.Added -> onAdded()
                    is AddOutcome.Rejected -> message = outcome.message
                }
                busy = false
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, 14.sp, FontWeight.SemiBold)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
            cursorBrush = SolidColor(p.ink),
            textStyle = TextStyle(color = p.ink, fontSize = 16.sp),
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 52.dp)
                .border(1.5.dp, p.ink2, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 15.dp),
        )
    }
}
