package app.voltdeck

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voltdeck.core.DeviceId
import app.voltdeck.core.Reading
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val Background = Color(0xFF0B0F14)
private val Surface = Color(0xFF161C24)
private val TextPrimary = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B98A5)

@Composable
fun App() {
    val readings = remember { demoReadings(Clock.System) }
    DemoModeScreen(readings)
}

/** Écran brut du mode démo : affiche en direct le dernier [Reading] de chaque équipement simulé. */
@Composable
fun DemoModeScreen(readings: Flow<Reading>) {
    val latest = remember { mutableStateMapOf<DeviceId, Reading>() }
    LaunchedEffect(readings) { readings.collect { latest[it.deviceId] = it } }

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
            "Valeurs simulées, accélérées ×${DEMO_SPEED.toInt()}. Aucun équipement n'est lu.",
            style = TextStyle(color = TextSecondary, fontSize = 14.sp),
        )
        latest.values.sortedBy { it.deviceId.value }.forEach { reading -> DeviceCard(reading) }
    }
}

@Composable
private fun DeviceCard(reading: Reading) {
    Column(
        Modifier.fillMaxWidth().background(Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            deviceTitle(reading.deviceId),
            style = TextStyle(color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        )
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
}
