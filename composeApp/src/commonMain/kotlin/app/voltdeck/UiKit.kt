package app.voltdeck

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TABULAR = "tnum"

@Composable
fun Txt(
    text: String,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalPalette.current.ink,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    oneLine: Boolean = false,
    shortText: String? = null,
) {
    val style = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFeatureSettings = TABULAR, textAlign = align ?: TextAlign.Unspecified)
    if (oneLine) {
        var fit by remember(text, size) { mutableStateOf(OneLineFit()) }
        val widest = remember(text, size) { intArrayOf(0) }
        val abbreviated = fit.abbreviated && shortText != null
        BasicText(
            if (abbreviated) checkNotNull(shortText) else text,
            modifier = if (abbreviated) modifier.semantics { contentDescription = text } else modifier,
            style = style.copy(fontSize = size * fit.shrink),
            maxLines = 1,
            softWrap = false,
            onTextLayout = {
                val available = it.layoutInput.constraints.maxWidth
                if (widest[0] != 0 && available > widest[0]) {
                    widest[0] = available
                    fit = OneLineFit()
                } else {
                    widest[0] = maxOf(widest[0], available)
                    val overflow = it.didOverflowWidth || oneLineOverflows(it.multiParagraph.maxIntrinsicWidth, available)
                    fit = nextOneLineFit(fit, overflow, size.value, shortText != null)
                }
            },
        )
    } else {
        BasicText(text, modifier = modifier, style = style)
    }
}

/** État d'ajustement d'un libellé [Txt] `oneLine` : facteur de réduction, puis repli sur la forme abrégée. */
data class OneLineFit(val shrink: Float = 1f, val abbreviated: Boolean = false)

/**
 * Étape suivante quand un libellé ne tient pas : on le réduit jusqu'à [MIN_ONE_LINE_SP] ; en dessous, on passe à la
 * forme abrégée (si elle existe) à taille normale, qu'on réduit à son tour. Jamais de mot coupé.
 */
fun nextOneLineFit(fit: OneLineFit, overflow: Boolean, sizeSp: Float, hasShort: Boolean): OneLineFit {
    if (!overflow) return fit
    if (sizeSp * fit.shrink * ONE_LINE_STEP >= MIN_ONE_LINE_SP) return fit.copy(shrink = fit.shrink * ONE_LINE_STEP)
    if (hasShort && !fit.abbreviated) return OneLineFit(shrink = 1f, abbreviated = true)
    return fit
}

/**
 * Sans retour à la ligne, la mise en page du paragraphe prend sa propre largeur intrinsèque : c'est donc la largeur
 * *disponible* (contrainte du parent) qu'il faut comparer, jamais celle du paragraphe. Une contrainte infinie ne déborde pas.
 */
fun oneLineOverflows(intrinsicWidth: Float, availableWidth: Int): Boolean =
    availableWidth != Constraints.Infinity && intrinsicWidth > availableWidth * ONE_LINE_MARGIN

private const val ONE_LINE_STEP = 0.92f

/** Un libellé qui occupe plus de 97 % de la largeur disponible est réduit : le dernier glyphe est sinon rogné. */
private const val ONE_LINE_MARGIN = 0.97f

/** Plancher de réduction (sp, avant échelle de police) : en dessous, le texte est illisible, on abrège. */
const val MIN_ONE_LINE_SP = 8f

/** Un chiffre et son unité, l'unité en petit et en `ink2` (VOL-2 §3). */
@Composable
fun Value(number: String, unit: String?, size: TextUnit, modifier: Modifier = Modifier, color: Color = LocalPalette.current.ink) {
    val p = LocalPalette.current
    BasicText(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold)) { append(number) }
            if (unit != null && number != DASH) {
                withStyle(SpanStyle(color = p.ink2, fontSize = size * 0.45f, fontWeight = FontWeight.SemiBold)) { append(" $unit") }
            }
        },
        modifier = modifier,
        style = TextStyle(fontFeatureSettings = TABULAR),
    )
}

const val DASH = "—"

@Composable
fun Card(modifier: Modifier = Modifier, radius: Dp = 20.dp, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.background(p.surface, shape).border(1.5.dp, p.line, shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

enum class Tone { GOOD, WARN, CRIT, NEUTRAL }

@Composable
fun Pill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val (fg, bg) = when (tone) {
        Tone.GOOD -> p.goodText to p.goodBg
        Tone.WARN -> p.warnText to p.warnBg
        Tone.CRIT -> p.critText to p.critBg
        Tone.NEUTRAL -> p.ink2 to p.grid
    }
    Txt(text, 12.sp, FontWeight.SemiBold, fg, modifier.background(bg, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp))
}

/** Étiquette de nature d'une valeur : « calculée », « shunt », « incomplète »… */
@Composable
fun Tag(text: String, color: Color = LocalPalette.current.ink2) {
    Txt(
        text, 11.sp, FontWeight.SemiBold, color,
        Modifier.border(1.5.dp, color.copy(alpha = 0.7f), CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
fun RoundButton(description: String, onClick: () -> Unit, icon: DrawScope.(Color) -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(44.dp)
            .background(p.surface, CircleShape)
            .border(1.5.dp, p.line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) { icon(p.ink) }
    }
}

@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .sizeIn(minHeight = 52.dp)
            .background(p.accent, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, 16.sp, FontWeight.Bold, p.onAccent)
    }
}

@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .sizeIn(minHeight = 44.dp)
            .border(1.5.dp, p.ink, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, 14.sp, FontWeight.SemiBold)
    }
}

/** Pastille colorée de 22 dp portant l'icône d'une grandeur. */
@Composable
fun Badge(color: Color, icon: DrawScope.(Color) -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(22.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) { icon(p.onAccent) }
    }
}

@Composable
fun TileHeader(label: String, color: Color, icon: DrawScope.(Color) -> Unit, shortLabel: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge(color, icon)
        Txt(label, 15.sp, FontWeight.Bold, modifier = Modifier.weight(1f), oneLine = true, shortText = shortLabel)
        trailing()
    }
}

/**
 * Liaison entre deux nœuds de la vue d'ensemble : tirets qui avancent dans le sens du flux quand [active],
 * filet neutre sinon.
 */
@Composable
fun FlowLink(color: Color, active: Boolean, vertical: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    val phase by rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier.semantics { contentDescription = if (active) "Flux d'énergie actif" else "Aucun flux" }) {
        val thickness = 5.dp.toPx()
        val start = if (vertical) Offset(size.width / 2, 0f) else Offset(0f, size.height / 2)
        val end = if (vertical) Offset(size.width / 2, size.height) else Offset(size.width, size.height / 2)
        drawLine(p.grid, start, end, thickness, StrokeCap.Round)
        if (active) {
            val dash = 6.dp.toPx()
            val gap = 8.dp.toPx()
            drawLine(
                color, start, end, thickness, StrokeCap.Butt,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), -(dash + gap) * phase),
            )
        }
    }
}

/** Jauge de charge : piste à 22 % de la couleur batterie, 16 dp (VOL-2 §5). */
@Composable
fun Gauge(fraction: Float, color: Color, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Charge ${(fraction * 100).toInt()} %" }) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(color.copy(alpha = 0.22f), cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
    }
}

private fun DrawScope.stroke(c: Color) = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round)

val SunIcon: DrawScope.(Color) -> Unit = { c ->
    val center = Offset(size.width / 2, size.height / 2)
    drawCircle(c, size.minDimension * 0.2f, center, style = stroke(c))
    for (i in 0 until 8) {
        val a = i * kotlin.math.PI / 4
        val dx = kotlin.math.cos(a).toFloat()
        val dy = kotlin.math.sin(a).toFloat()
        drawLine(c, center + Offset(dx, dy) * (size.minDimension * 0.34f), center + Offset(dx, dy) * (size.minDimension * 0.47f), size.minDimension * 0.1f, StrokeCap.Round)
    }
}

val BatteryIcon: DrawScope.(Color) -> Unit = { c ->
    val w = size.width
    val h = size.height
    drawRoundRect(c, Offset(w * 0.08f, h * 0.28f), Size(w * 0.74f, h * 0.44f), CornerRadius(w * 0.08f), style = stroke(c))
    drawLine(c, Offset(w * 0.92f, h * 0.43f), Offset(w * 0.92f, h * 0.57f), w * 0.1f, StrokeCap.Round)
}

val PlugIcon: DrawScope.(Color) -> Unit = { c ->
    val w = size.width
    val h = size.height
    val s = stroke(c)
    drawLine(c, Offset(w * 0.36f, h * 0.08f), Offset(w * 0.36f, h * 0.32f), s.width, StrokeCap.Round)
    drawLine(c, Offset(w * 0.64f, h * 0.08f), Offset(w * 0.64f, h * 0.32f), s.width, StrokeCap.Round)
    drawRoundRect(c, Offset(w * 0.22f, h * 0.34f), Size(w * 0.56f, h * 0.34f), CornerRadius(w * 0.14f), style = s)
    drawLine(c, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.94f), s.width, StrokeCap.Round)
}

val SwapIcon: DrawScope.(Color) -> Unit = { c ->
    val w = size.width
    val h = size.height
    val s = stroke(c).width
    drawLine(c, Offset(w * 0.12f, h * 0.32f), Offset(w * 0.84f, h * 0.32f), s, StrokeCap.Round)
    drawLine(c, Offset(w * 0.84f, h * 0.32f), Offset(w * 0.66f, h * 0.16f), s, StrokeCap.Round)
    drawLine(c, Offset(w * 0.88f, h * 0.68f), Offset(w * 0.16f, h * 0.68f), s, StrokeCap.Round)
    drawLine(c, Offset(w * 0.16f, h * 0.68f), Offset(w * 0.34f, h * 0.84f), s, StrokeCap.Round)
}

val MoonIcon: DrawScope.(Color) -> Unit = { c ->
    drawArc(c, 60f, 250f, false, Offset(size.width * 0.12f, size.height * 0.12f), Size(size.width * 0.76f, size.height * 0.76f), style = stroke(c))
    drawArc(c, 110f, 140f, false, Offset(size.width * 0.38f, size.height * 0.02f), Size(size.width * 0.6f, size.height * 0.6f), style = stroke(c))
}

val BackIcon: DrawScope.(Color) -> Unit = { c ->
    val s = stroke(c).width
    drawLine(c, Offset(size.width * 0.62f, size.height * 0.2f), Offset(size.width * 0.32f, size.height * 0.5f), s, StrokeCap.Round)
    drawLine(c, Offset(size.width * 0.32f, size.height * 0.5f), Offset(size.width * 0.62f, size.height * 0.8f), s, StrokeCap.Round)
}

val ForwardIcon: DrawScope.(Color) -> Unit = { c ->
    val s = stroke(c).width
    drawLine(c, Offset(size.width * 0.38f, size.height * 0.2f), Offset(size.width * 0.68f, size.height * 0.5f), s, StrokeCap.Round)
    drawLine(c, Offset(size.width * 0.68f, size.height * 0.5f), Offset(size.width * 0.38f, size.height * 0.8f), s, StrokeCap.Round)
}

val LockIcon: DrawScope.(Color) -> Unit = { c ->
    val s = stroke(c)
    drawRoundRect(c, Offset(size.width * 0.2f, size.height * 0.45f), Size(size.width * 0.6f, size.height * 0.45f), CornerRadius(size.width * 0.08f), style = s)
    drawArc(c, 180f, 180f, false, Offset(size.width * 0.32f, size.height * 0.12f), Size(size.width * 0.36f, size.height * 0.5f), style = s)
}
