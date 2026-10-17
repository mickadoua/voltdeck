package app.voltdeck

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Jetons du design system v1 (VOL-2, §2) : deux thèmes choisis, pas inversés automatiquement. */
@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val line: Color,
    val grid: Color,
    val accent: Color,
    val onAccent: Color,
    val solar: Color,
    val load: Color,
    val battery: Color,
    val goodText: Color,
    val goodBg: Color,
    val warnText: Color,
    val warnBg: Color,
    val critText: Color,
    val critBg: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF0D1113),
    surface = Color(0xFF161C1F),
    ink = Color(0xFFF4F6F7),
    ink2 = Color(0xFFAAB3B8),
    muted = Color(0xFF8D979D),
    line = Color(0xFF2C353A),
    grid = Color(0xFF273036),
    accent = Color(0xFFD4F03A),
    onAccent = Color(0xFF0B0D0F),
    solar = Color(0xFFC98500),
    load = Color(0xFF4A96F5),
    battery = Color(0xFF1FA87D),
    goodText = Color(0xFF46C79B),
    goodBg = Color(0xFF123428),
    warnText = Color(0xFFE8A317),
    warnBg = Color(0xFF3A2C08),
    critText = Color(0xFFFF7B72),
    critBg = Color(0xFF3C1613),
)

val LightPalette = Palette(
    bg = Color(0xFFF2F0EA),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF0B0D0F),
    ink2 = Color(0xFF4A5057),
    muted = Color(0xFF5C636A),
    line = Color(0xFFBDB8AA),
    grid = Color(0xFFE4E1D8),
    accent = Color(0xFFD4F03A),
    onAccent = Color(0xFF0B0D0F),
    solar = Color(0xFFC27A00),
    load = Color(0xFF1F6FD1),
    battery = Color(0xFF0A8F6A),
    goodText = Color(0xFF0A6E50),
    goodBg = Color(0xFFDFF3EA),
    warnText = Color(0xFF7A4E00),
    warnBg = Color(0xFFFFEFC6),
    critText = Color(0xFFB3261E),
    critBg = Color(0xFFFDE6E3),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Format français : virgule décimale. */
fun frDecimal(value: Double, decimals: Int): String = formatDecimal(value, decimals).replace('.', ',')

fun signedFr(value: Double, decimals: Int): String {
    val text = frDecimal(value, decimals)
    return if (value > 0 && text.any { it in '1'..'9' }) "+$text" else text.replace("-", "−")
}
