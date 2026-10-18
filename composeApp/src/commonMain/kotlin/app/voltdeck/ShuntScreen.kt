package app.voltdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voltdeck.drivers.victron.VictronLinkStatus

private class PlacementOption(val placement: ShuntPlacement, val title: String, val description: String)

private val OPTIONS = listOf(
    PlacementOption(
        ShuntPlacement.BATTERY, "Sur le négatif de la batterie",
        "Moniteur de batterie : il voit tout ce qui entre et sort du parc. Donne la charge, le courant et l'autonomie.",
    ),
    PlacementOption(ShuntPlacement.LOAD, "Sur un circuit de consommation", "Il mesure un seul départ : frigo, onduleur, éclairage…"),
    PlacementOption(ShuntPlacement.SOURCE, "Sur une source de charge", "Alternateur, chargeur secteur, éolienne, ou solaire sans régulateur Bluetooth."),
    PlacementOption(ShuntPlacement.IGNORED, "Ne pas l'utiliser", "Il reste listé mais n'alimente ni la vue d'ensemble ni les calculs."),
)

fun placementSummary(setup: ShuntSetup): String = when (setup.placement) {
    ShuntPlacement.BATTERY -> "Négatif de la batterie"
    ShuntPlacement.LOAD -> "Consommation · ${setup.displayName}"
    ShuntPlacement.SOURCE -> "Source · ${setup.displayName}"
    ShuntPlacement.IGNORED -> "Non utilisé"
}

fun placementEffect(setup: ShuntSetup): String = when (setup.placement) {
    ShuntPlacement.BATTERY ->
        "La tuile Batterie prend la charge, le courant et l'autonomie du shunt. La consommation reste calculée : sources mesurées − flux de la batterie."
    ShuntPlacement.LOAD ->
        "Une ligne « ${setup.displayName} » mesurée apparaît dans Consommation. Le shunt ne sert plus pour la charge ni l'autonomie."
    ShuntPlacement.SOURCE ->
        "Une tuile « ${setup.displayName} » rejoint les sources, reliée à la batterie. Sa production entre dans le calcul de la consommation."
    ShuntPlacement.IGNORED ->
        "Le shunt n'apparaît pas sur la vue d'ensemble. Sans BMS ni shunt sur la batterie, la consommation est masquée."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShuntScreen(
    title: String,
    status: VictronLinkStatus,
    announced: ShuntSetup?,
    initial: ShuntSetup,
    onBack: () -> Unit,
    onSave: (ShuntSetup) -> Unit,
) {
    val p = LocalPalette.current
    var placement by remember { mutableStateOf(initial.placement) }
    var circuit by remember { mutableStateOf(initial.circuit) }
    var name by remember { mutableStateOf(initial.name.orEmpty()) }
    var capacity by remember { mutableStateOf(initial.capacityAh?.let { frDecimal(it, if (it % 1.0 == 0.0) 0 else 1) }.orEmpty()) }

    fun choose(newPlacement: ShuntPlacement) {
        placement = newPlacement
        val circuits = Circuit.forPlacement(newPlacement)
        if (circuit !in circuits) {
            circuit = circuits.firstOrNull()
            name = ""
        }
    }

    val capacityAh = capacityOf(capacity).takeIf { placement == ShuntPlacement.BATTERY }
    val capacityInvalid = placement == ShuntPlacement.BATTERY && capacity.isNotBlank() && capacityAh == null
    val draft = ShuntSetup(placement, circuit, name.takeIf { it.isNotBlank() && circuit != null }, capacityAh)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton("Retour", onBack, BackIcon)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(title, 24.sp, FontWeight.Bold)
                when (status) {
                    VictronLinkStatus.OK -> Pill("Détecté · annonces déchiffrées", Tone.GOOD)
                    VictronLinkStatus.WAITING -> Pill("En attente d'une annonce", Tone.NEUTRAL)
                    VictronLinkStatus.NO_KEY -> Pill("Détecté · clé absente", Tone.WARN)
                    VictronLinkStatus.WRONG_KEY -> Pill("Détecté · clé incorrecte", Tone.CRIT)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(p.grid, RoundedCornerShape(14.dp)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(18.dp)) { LockIcon(p.ink2) }
            Txt("Ce réglage reste dans Voltdeck. Rien n'est écrit dans le shunt.", 14.sp, color = p.ink2)
        }

        Card(Modifier.fillMaxWidth()) {
            Txt("Mode annoncé par le shunt", 13.sp, FontWeight.SemiBold, p.muted)
            if (announced == null) {
                Txt("Moniteur de batterie", 17.sp, FontWeight.Bold)
                Txt("Lu dans son annonce Bluetooth (enregistrement Victron 0x02). Il sert de choix par défaut.", 13.sp, color = p.ink2)
            } else {
                Txt("Compteur DC · ${announced.displayName}", 17.sp, FontWeight.Bold)
                Txt("Lu dans son annonce Bluetooth (enregistrement Victron 0x0D, réglé dans VictronConnect). Il sert de choix par défaut.", 13.sp, color = p.ink2)
            }
        }

        Txt("Où est-il branché ?", 17.sp, FontWeight.Bold)
        OPTIONS.forEach { option -> PlacementRow(option, option.placement == placement) { choose(option.placement) } }

        val circuits = Circuit.forPlacement(placement)
        if (circuits.isNotEmpty()) {
            Txt("Quel circuit mesure-t-il ?", 17.sp, FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                circuits.forEach { c -> Chip(c.label, c == circuit) { circuit = c } }
            }
            Txt("Nom affiché (facultatif)", 14.sp, FontWeight.SemiBold)
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(NAME_LIMIT) },
                singleLine = true,
                cursorBrush = SolidColor(p.ink),
                textStyle = TextStyle(color = p.ink, fontSize = 16.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 52.dp)
                    .border(1.5.dp, p.ink2, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 15.dp),
                decorationBox = { inner ->
                    if (name.isEmpty()) Txt(circuit?.label.orEmpty(), 16.sp, color = p.muted)
                    inner()
                },
            )
        }

        if (placement == ShuntPlacement.BATTERY) {
            Txt("Capacité de la batterie (facultatif)", 17.sp, FontWeight.Bold)
            BasicTextField(
                value = capacity,
                onValueChange = { capacity = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(CAPACITY_LIMIT) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = SolidColor(p.ink),
                textStyle = TextStyle(color = p.ink, fontSize = 16.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 52.dp)
                    .border(1.5.dp, if (capacityInvalid) p.critText else p.ink2, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 15.dp),
                decorationBox = { inner ->
                    if (capacity.isEmpty()) Txt("Ah, par exemple 200", 16.sp, color = p.muted)
                    inner()
                },
            )
            Txt(
                if (capacityInvalid) "Capacité entre 1 et ${ShuntSetup.MAX_CAPACITY_AH.toInt()} Ah."
                else "Sans BMS, les cycles sont estimés : Ah déchargés ÷ cette capacité. Sans capacité, ils restent masqués.",
                13.sp, color = if (capacityInvalid) p.critText else p.ink2,
            )
        }

        Card(Modifier.fillMaxWidth()) {
            Txt("Sur la vue d'ensemble", 13.sp, FontWeight.SemiBold, p.muted)
            Txt(placementEffect(draft), 14.sp, color = p.ink2)
        }

        PrimaryButton("Enregistrer", Modifier.fillMaxWidth()) { if (!capacityInvalid) onSave(draft) }
    }
}

@Composable
private fun PlacementRow(option: PlacementOption, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) p.accent.copy(alpha = 0.10f) else p.surface, shape)
            .border(1.5.dp, if (selected) p.accent else p.line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 1.dp).size(22.dp).border(2.dp, if (selected) p.accent else p.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).background(p.accent, CircleShape))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(option.title, 16.sp, FontWeight.Bold)
            Txt(option.description, 13.sp, color = p.ink2)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .sizeIn(minHeight = 44.dp)
            .background(if (selected) p.accent else p.surface, CircleShape)
            .border(1.5.dp, if (selected) p.accent else p.line, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, 14.sp, FontWeight.SemiBold, if (selected) p.onAccent else p.ink)
    }
}

private const val NAME_LIMIT = 30
private const val CAPACITY_LIMIT = 7

/** Capacité saisie en Ah (virgule ou point), `null` si vide ou hors limites. */
internal fun capacityOf(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf(ShuntSetup::isValidCapacity)
