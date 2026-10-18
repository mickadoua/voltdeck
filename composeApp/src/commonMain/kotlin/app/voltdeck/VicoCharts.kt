package app.voltdeck

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.ColumnCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.VicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * 60

private fun whOrDash(v: Double?) = v?.let { "${frDecimal(it, 0)} Wh" } ?: DASH

/** Zoom maximal des colonnes d'énergie : assez pour lire un créneau dans un mois, sans aller jusqu'à une colonne plein écran. */
private val ColumnsMaxZoom: Zoom = Zoom.max(Zoom.fixed(8f), Zoom.Content)

/**
 * État de zoom d'une courbe : de tout le contenu à une fenêtre de [closestWindowMinutes] minutes, jamais au-delà.
 * Les bornes sont mémorisées : Vico recrée l'état de zoom dès que l'une d'elles change d'instance, ce qui rendait la courbe
 * à son zoom initial à chaque nouvelle mesure.
 */
@Composable
internal fun rememberLineZoomState(closestWindowMinutes: Double): VicoZoomState {
    val maxZoom = remember(closestWindowMinutes) { Zoom.max(Zoom.x(closestWindowMinutes), Zoom.Content) }
    return rememberVicoZoomState(initialZoom = Zoom.Content, minZoom = Zoom.Content, maxZoom = maxZoom)
}

@Composable
private fun VoltdeckVicoTheme(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val theme = remember(p) {
        VicoTheme(
            candlestickCartesianLayerColors = VicoTheme.CandlestickCartesianLayerColors(p.goodText, p.muted, p.critText),
            columnCartesianLayerColors = listOf(p.solar, p.load),
            lineColor = p.line,
            textColor = p.ink2,
        )
    }
    ProvideVicoTheme(theme, content)
}

@Composable
private fun axisLabel() = rememberAxisLabelComponent(
    style = TextStyle(color = LocalPalette.current.ink2, fontSize = 12.sp),
)

@Composable
private fun markerLabel() = rememberTextComponent(
    style = TextStyle(color = LocalPalette.current.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    padding = Insets(10.dp, 6.dp),
    background = rememberShapeComponent(
        fill = Fill(LocalPalette.current.surface),
        shape = RoundedCornerShape(10.dp),
        strokeFill = Fill(LocalPalette.current.line),
        strokeThickness = 1.dp,
    ),
)

/**
 * Énergie produite et consommée par créneau (heure ou jour). Pincer pour zoomer, glisser pour défiler,
 * toucher une colonne pour lire ses valeurs.
 */
@Composable
fun EnergyChart(columns: EnergyColumns, slotLabel: (Int) -> String, description: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(columns) {
        producer.runTransaction { columnSeries { series(columns.producedDrawn); series(columns.consumedDrawn) } }
    }
    val thickness: Dp = if (columns.produced.size > 24) 5.dp else 8.dp
    VoltdeckVicoTheme {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                        rememberLineComponent(Fill(p.solar), thickness, RoundedCornerShape(2.dp)),
                        rememberLineComponent(Fill(p.load), thickness, RoundedCornerShape(2.dp)),
                    ),
                    columnCollectionSpacing = 4.dp,
                    rangeProvider = CartesianLayerRangeProvider.fixed(minY = 0.0),
                ),
                startAxis = VerticalAxis.rememberStart(
                    label = axisLabel(),
                    line = rememberAxisLineComponent(Fill.Transparent),
                    guideline = rememberAxisGuidelineComponent(Fill(p.grid)),
                    valueFormatter = CartesianValueFormatter { _, v, _ -> frDecimal(v, 0) },
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    label = axisLabel(),
                    guideline = null,
                    valueFormatter = CartesianValueFormatter { _, v, _ -> slotLabel(v.toInt().coerceIn(0, columns.produced.size - 1)) },
                ),
                marker = rememberDefaultCartesianMarker(
                    label = markerLabel(),
                    valueFormatter = DefaultCartesianMarker.ValueFormatter { _, targets ->
                        val target = targets.filterIsInstance<ColumnCartesianLayerMarkerTarget>().firstOrNull()
                        val at = target?.x?.toInt()?.coerceIn(0, columns.produced.size - 1)
                        if (target == null || at == null) {
                            DASH
                        } else {
                            "${slotLabel(at)} · produite ${whOrDash(columns.produced[at])} · consommée ${whOrDash(columns.consumed[at])}"
                        }
                    },
                ),
            ),
            modelProducer = producer,
            modifier = modifier.semantics { contentDescription = description },
            zoomState = rememberVicoZoomState(initialZoom = Zoom.Content, minZoom = Zoom.Content, maxZoom = ColumnsMaxZoom),
        )
    }
}

/** Axe des x en minutes : début ramené sur un multiple de [alignMinutes] pour que les repères tombent sur des heures ou des jours pleins. */
private class MinuteAlignedRange(
    private val alignMinutes: Double,
    private val base: CartesianLayerRangeProvider,
) : CartesianLayerRangeProvider by base {
    override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore): Double = floor(minX / alignMinutes) * alignMinutes
}

/**
 * Courbe en tronçons : chaque trou de collecte sépare deux tronçons, jamais relié. [xLabel] reçoit des minutes
 * depuis l'origine de l'axe (voir [Segment]), qui doit tomber sur une heure pleine ; un repère est posé toutes
 * les [labelEveryMinutes] minutes (Vico en saute si les libellés se touchent) et libellé par [tickLabel] ; [xLabel] sert au repère au toucher. [fixedY] fige l'axe vertical
 * (0 à 100 % pour la charge).
 */
@Composable
fun SegmentedLineChart(
    segments: List<Segment>,
    color: Color,
    unit: String,
    decimals: Int,
    xLabel: (Double) -> String,
    labelEveryMinutes: Int,
    tickLabel: (Double) -> String = xLabel,
    description: String,
    modifier: Modifier = Modifier,
    fixedY: ClosedFloatingPointRange<Double>? = null,
    zoomState: VicoZoomState = rememberLineZoomState(closestWindowMinutes(xStepOf(segments))),
) {
    val p = LocalPalette.current
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(segments) {
        producer.runTransaction { lineSeries { segments.forEach { series(x = it.x, y = it.y) } } }
    }
    val dot = rememberShapeComponent(Fill(color), RoundedCornerShape(50), Insets.Zero)
    val lines = segments.map { segment ->
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            pointProvider = if (segment.x.size == 1) LineCartesianLayer.PointProvider.single(LineCartesianLayer.Point(dot, 8.dp)) else null,
        )
    }
    val xStep = remember(segments) { xStepOf(segments) }
    val getXStep = remember(xStep) { { _: CartesianChartModel -> xStep } }
    val range = remember(fixedY, labelEveryMinutes) {
        val y = if (fixedY != null) CartesianLayerRangeProvider.fixed(minY = fixedY.start, maxY = fixedY.endInclusive) else CartesianLayerRangeProvider.auto()
        MinuteAlignedRange(if (labelEveryMinutes >= MINUTES_PER_DAY) MINUTES_PER_DAY.toDouble() else MINUTES_PER_HOUR.toDouble(), y)
    }
    VoltdeckVicoTheme {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(lines.ifEmpty { listOf(LineCartesianLayer.rememberLine()) }),
                    rangeProvider = range,
                ),
                startAxis = VerticalAxis.rememberStart(
                    label = axisLabel(),
                    line = rememberAxisLineComponent(Fill.Transparent),
                    guideline = rememberAxisGuidelineComponent(Fill(p.grid)),
                    valueFormatter = CartesianValueFormatter { _, v, _ -> frDecimal(v, decimals) },
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    label = axisLabel(),
                    guideline = null,
                    valueFormatter = CartesianValueFormatter { _, v, _ -> tickLabel(v) },
                    itemPlacer = remember(labelEveryMinutes, xStep) {
                        HorizontalAxis.ItemPlacer.aligned(spacing = { (labelEveryMinutes / xStep).roundToInt().coerceAtLeast(1) })
                    },
                ),
                marker = rememberDefaultCartesianMarker(
                    label = markerLabel(),
                    valueFormatter = DefaultCartesianMarker.ValueFormatter { _, targets ->
                        val point = targets.filterIsInstance<LineCartesianLayerMarkerTarget>().firstOrNull()?.points?.firstOrNull()
                        if (point == null) DASH else "${xLabel(point.entry.x)} · ${frDecimal(point.entry.y, decimals)} $unit"
                    },
                ),
                getXStep = getXStep,
            ),
            modelProducer = producer,
            modifier = modifier.semantics { contentDescription = description },
            zoomState = zoomState,
        )
    }
}
