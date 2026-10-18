package app.voltdeck

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** Rend la vraie ligne « cycles » de la carte Batterie : un helper de libellé correct ne prouve pas que l'écran l'utilise. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class BatteryDetailLineRenderTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun tile(cycles: Double?, estimated: Boolean, cells: Boolean = false) = BatteryTile(
        soc = 60.0, voltage = 13.1, current = -4.0, temperature = null,
        cellMin = 3.30.takeIf { cells }, cellMax = 3.35.takeIf { cells },
        remainingAh = null, nominalAh = null, cycles = cycles, source = BatterySource.SHUNT, cyclesEstimated = estimated,
    )

    private fun show(tile: BatteryTile) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { it.setContent { BatteryDetailLine(tile) } }
        rule.waitForIdle()
    }

    private fun count(text: String) = rule.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    @Test
    fun anEstimatedCountShowsItsDecimalAndTheEstimatedTag() {
        show(tile(cycles = 0.4, estimated = true))
        rule.onNodeWithText("au moins 0,4 cycles").assertIsDisplayed()
        rule.onNodeWithText("estimé").assertIsDisplayed()
    }

    @Test
    fun aBmsCounterShowsNoEstimatedTagAndNoDecimal() {
        show(tile(cycles = 37.0, estimated = false, cells = true))
        rule.onNodeWithText("Cellules 3,30–3,35\u00a0V · 37 cycles", substring = false).assertIsDisplayed()
        assertEquals(0, count("estimé"))
    }

    @Test
    fun noCyclesShowNoTag() {
        show(tile(cycles = null, estimated = true))
        assertEquals(0, count("estimé"))
    }
}
