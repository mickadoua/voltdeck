package app.voltdeck

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rend un vrai `Txt(oneLine)` : le défaut de réduction à ×1,0 n'était visible qu'avec la mise en page réelle. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TxtOneLineRenderTest {
    @get:Rule val rule = createEmptyComposeRule()

    private var widthDp by mutableStateOf(120)
    private var fontScale = 1f

    private fun show(scale: Float, width: Int, text: String = "Statistiques", short: String? = "Stats") {
        fontScale = scale
        widthDp = width
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.width(widthDp.dp)) { Txt(text, 14.sp, oneLine = true, shortText = short) }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun layout(): TextLayoutResult {
        val node = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)).fetchSemanticsNodes().first()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun shown() = layout().layoutInput.text.text
    private fun sizeSp() = layout().layoutInput.style.fontSize.value

    @Test
    fun aLabelThatFitsKeepsItsFullSizeAtNormalScale() {
        show(scale = 1f, width = 120)
        assertEquals("Statistiques", shown())
        assertEquals(14f, sizeSp())
    }

    @Test
    fun aLabelNeverLosesLettersAtDoubleScale() {
        show(scale = 2f, width = 120)
        val result = layout()
        assertFalse(result.didOverflowWidth, "le libellé affiché (${shown()}) ne doit pas être coupé")
        assertTrue(shown() == "Statistiques" || shown() == "Stats")
        assertTrue(sizeSp() >= MIN_ONE_LINE_SP)
    }

    @Test
    fun aVeryNarrowSlotFallsBackToTheAbbreviation() {
        show(scale = 1f, width = 40)
        assertEquals("Stats", shown())
        assertFalse(layout().didOverflowWidth)
    }

    @Test
    fun aShrunkLabelGrowsBackWhenTheSlotWidens() {
        show(scale = 1f, width = 40)
        assertEquals("Stats", shown())
        widthDp = 200
        rule.waitForIdle()
        assertEquals("Statistiques", shown())
        assertEquals(14f, sizeSp())
    }
}
