package com.vipertecpro.plugins.image_cropper

// =============================================================================
// ImageCropper — Android native image editor
// =============================================================================
//
// A configurable, fully-native editor (Jetpack Compose) with three modes:
//   • Crop   — freehand drag / pinch-zoom / rotate behind a circle/rect mask,
//              a live preset selector, and draggable Zoom / Rotate rulers.
//   • Adjust — Brightness / Contrast / Saturation via draggable rulers (live).
//   • Filter — one-tap presets that set those three at once.
//
// Layout (top → bottom): image area · sub-tool tabs · ruler · [Cancel | Crop /
// Adjust / Filter | Done]. On "Done" the crop is rendered and the colour
// adjustments are baked in with a ColorMatrix, then the path is returned via the
// `ImageCropped` event. Mirrors the iOS implementation.
// =============================================================================

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object ImageCropperFunctions {

    private const val TAG = "ImageCropper"
    private const val EVENT_CROPPED = "Vipertecpro\\ImageCropper\\Events\\ImageCropped"
    private const val EVENT_CANCELLED = "Vipertecpro\\ImageCropper\\Events\\CropCancelled"

    data class CropPreset(val key: String, val label: String, val shape: String, val aspectRatio: Float)

    data class CropConfig(
        val path: String,
        val shape: String,
        val aspectRatio: Float,
        val tools: List<String>,
        val presets: List<CropPreset>,
        val outputSize: Int,
        val id: String?,
    )

    /** A colour filter preset — brightness / contrast / saturation in −100…100. */
    data class ColorFilterPreset(val name: String, val brightness: Float, val contrast: Float, val saturation: Float)

    private val FILTERS = listOf(
        ColorFilterPreset("Original", 0f, 0f, 0f),
        ColorFilterPreset("Vivid", 3f, 18f, 40f),
        ColorFilterPreset("Mono", 0f, 12f, -100f),
        ColorFilterPreset("Noir", -6f, 38f, -100f),
        ColorFilterPreset("Soft", 8f, -14f, -8f),
        ColorFilterPreset("Punch", -2f, 26f, 24f),
    )

    class Open(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val config = CropConfig(
                path = parameters["path"] as? String ?: "",
                shape = if ((parameters["shape"] as? String) == "circle") "circle" else "rect",
                aspectRatio = ((parameters["aspectRatio"] as? Number)?.toFloat() ?: 1f).let { if (it > 0f) it else 1f },
                tools = (parameters["tools"] as? List<*>)?.mapNotNull { it as? String }
                    ?.filter { it == "zoom" || it == "rotate" } ?: listOf("zoom", "rotate"),
                presets = (parameters["presets"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map {
                    CropPreset(it["key"] as? String ?: "", it["label"] as? String ?: "",
                        if ((it["shape"] as? String) == "circle") "circle" else "rect",
                        ((it["aspectRatio"] as? Number)?.toFloat() ?: 1f).coerceAtLeast(0.01f))
                } ?: emptyList(),
                outputSize = (parameters["outputSize"] as? Number)?.toInt() ?: 1024,
                id = parameters["id"] as? String,
            )
            Handler(Looper.getMainLooper()).post {
                try {
                    present(config)
                } catch (e: Exception) {
                    Log.e(TAG, "open failed: ${e.message}", e); dispatch(EVENT_CANCELLED, config.id)
                }
            }
            return emptyMap()
        }

        private fun present(config: CropConfig) {
            val bitmap = loadUprightBitmap(config.path) ?: run { dispatch(EVENT_CANCELLED, config.id); return }
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val view = ComposeView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            fun teardown() { (view.parent as? ViewGroup)?.removeView(view) }
            view.setContent {
                EditorScreen(bitmap, config,
                    onCancel = { edited ->
                        if (edited) {
                            android.app.AlertDialog.Builder(activity)
                                .setTitle("Discard Changes")
                                .setMessage("Are you sure you want to discard these changes?")
                                .setPositiveButton("Discard") { _, _ -> teardown(); dispatch(EVENT_CANCELLED, config.id) }
                                .setNegativeButton("Cancel", null).show()
                        } else { teardown(); dispatch(EVENT_CANCELLED, config.id) }
                    },
                    onDone = { state ->
                        Thread {
                            val out = CropRenderer.render(activity, bitmap, state, config)
                            activity.runOnUiThread {
                                teardown()
                                if (out != null) dispatch(EVENT_CROPPED, config.id, out) else dispatch(EVENT_CANCELLED, config.id)
                            }
                        }.start()
                    })
            }
            root.addView(view)
        }

        private fun dispatch(event: String, id: String?, path: String? = null) {
            val payload = JSONObject().apply { path?.let { put("path", it) }; id?.let { put("id", it) } }
            NativeActionCoordinator.dispatchEvent(activity, event, payload.toString())
        }

        private fun loadUprightBitmap(path: String): Bitmap? {
            val raw = BitmapFactory.decodeFile(path) ?: return null
            return try {
                val m = Matrix()
                when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                    else -> {}
                }
                if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } catch (e: Exception) { raw }
        }
    }

    val filters: List<ColorFilterPreset> get() = FILTERS
}

data class CropState(
    val scale: Float, val rotationDeg: Float, val offset: Offset,
    val fitScale: Float, val viewportW: Float, val viewportH: Float,
    val shape: String, val aspectRatio: Float,
    val brightness: Float, val contrast: Float, val saturation: Float,
)

/** Build the ColorMatrix for brightness/contrast/saturation (−100…100 each). */
fun colourMatrix(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
    val m = ColorMatrix().apply { setSaturation((1 + saturation / 100f).coerceAtLeast(0f)) }
    val c = 1 + contrast / 100f
    val t = (1 - c) * 128f
    m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    val b = brightness / 100f * 0.5f * 255f
    m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)))
    return m
}

@Composable
private fun EditorScreen(
    bitmap: Bitmap,
    config: ImageCropperFunctions.CropConfig,
    onCancel: (edited: Boolean) -> Unit,
    onDone: (CropState) -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var rotationDeg by remember { mutableStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var shape by remember { mutableStateOf(config.shape) }
    var aspectRatio by remember { mutableStateOf(config.aspectRatio) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var mode by remember { mutableStateOf("crop") }
    var cropSub by remember { mutableStateOf(config.tools.firstOrNull() ?: "zoom") }
    var adjustSub by remember { mutableStateOf("brightness") }
    var showOriginal by remember { mutableStateOf(false) } // press-and-hold to compare

    val edited = scale != 1f || rotationDeg != 0f || offset != Offset.Zero ||
        brightness != 0f || contrast != 0f || saturation != 0f
    val circle = shape == "circle"

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val cw = constraints.maxWidth.toFloat()
        val stageH = constraints.maxHeight.toFloat() * 0.60f
        val side = min(cw, stageH) * 0.92f
        val vpW = if (aspectRatio >= 1f) side else side * aspectRatio
        val vpH = if (aspectRatio >= 1f) side / aspectRatio else side
        val left = (cw - vpW) / 2f
        val top = (stageH - vpH) / 2f
        // COVER the crop frame at user-scale 1 (never black inside).
        val coverScale = kotlin.math.max(vpW / bitmap.width, vpH / bitmap.height)
        val displayW = bitmap.width * coverScale
        val displayH = bitmap.height * coverScale
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colourMatrix(
                if (showOriginal) 0f else brightness,
                if (showOriginal) 0f else contrast,
                if (showOriginal) 0f else saturation))
        }

        Column(Modifier.fillMaxSize()) {
            // ---- Image stage ----
            Box(Modifier.fillMaxWidth().height(with(androidx.compose.ui.platform.LocalDensity.current) { stageH.toDp() })) {
                Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rot ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        rotationDeg += rot
                        offset = clampOffset(
                            offset + pan, displayW * scale, displayH * scale, rotationDeg, vpW, vpH
                        )
                    }
                }) {
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        bitmap,
                        buildMatrix(bitmap.width.toFloat(), bitmap.height.toFloat(),
                            scale * coverScale, rotationDeg, cw / 2f + offset.x, stageH / 2f + offset.y),
                        paint
                    )
                }
                Canvas(Modifier.fillMaxSize()) {
                    val rect = Rect(left, top, left + vpW, top + vpH)
                    val dim = Path().apply {
                        addRect(Rect(0f, 0f, cw, size.height))
                        if (circle) addOval(rect) else addRect(rect); fillType = PathFillType.EvenOdd
                    }
                    drawPath(dim, Color.Black.copy(alpha = 0.55f))
                    val clip = Path().apply { if (circle) addOval(rect) else addRect(rect) }
                    clipPath(clip) {
                        for (i in 1..2) {
                            val x = left + vpW * i / 3f
                            drawLine(Color.White.copy(alpha = 0.4f), Offset(x, top), Offset(x, top + vpH))
                            val y = top + vpH * i / 3f
                            drawLine(Color.White.copy(alpha = 0.4f), Offset(left, y), Offset(left + vpW, y))
                        }
                    }
                    if (circle) drawCircle(Color.White, vpW / 2f, Offset(rect.center.x, rect.center.y), style = Stroke(2f))
                    else drawRect(Color.White, Offset(left, top), Size(vpW, vpH), style = Stroke(2f))
                }

                // Press-and-hold to compare against the original colours.
                Box(
                    Modifier.align(Alignment.TopEnd).padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                showOriginal = true
                                waitForUpOrCancellation()
                                showOriginal = false
                            }
                        }
                ) {
                    BasicText(if (showOriginal) "Original" else "Compare",
                        style = TextStyle(color = Color.White, fontSize = 12.sp))
                }
            }

            // ---- Bottom controls ----
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (mode == "crop" && config.presets.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        config.presets.forEach { p ->
                            val on = p.shape == shape && abs(p.aspectRatio - aspectRatio) < 0.001f
                            BasicText(p.label, Modifier.clickable {
                                // Switch frame AND re-centre/re-cover the image.
                                shape = p.shape; aspectRatio = p.aspectRatio
                                scale = 1f; offset = Offset.Zero; rotationDeg = 0f
                            },
                                style = TextStyle(color = if (on) Color(0xFF34C759) else Color.White.copy(alpha = 0.6f),
                                    fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                        }
                    }
                }

                if (mode == "filter") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ImageCropperFunctions.filters.forEach { f ->
                            val on = brightness == f.brightness && contrast == f.contrast && saturation == f.saturation
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Canvas(Modifier.size(64.dp)) {
                                    val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colourMatrix(f.brightness, f.contrast, f.saturation)) }
                                    val s = min(size.width / bitmap.width, size.height / bitmap.height)
                                    drawContext.canvas.nativeCanvas.drawBitmap(bitmap,
                                        buildMatrix(bitmap.width.toFloat(), bitmap.height.toFloat(), s * 1.4f, 0f, size.width / 2f, size.height / 2f), p)
                                    drawRect(if (on) Color(0xFF34C759) else Color.White.copy(alpha = 0.2f), style = Stroke(if (on) 4f else 2f))
                                }
                                BasicText(f.name, Modifier.clickable { brightness = f.brightness; contrast = f.contrast; saturation = f.saturation },
                                    style = TextStyle(color = if (on) Color(0xFF34C759) else Color.White.copy(alpha = 0.7f), fontSize = 11.sp))
                            }
                        }
                    }
                } else {
                    // Row 1 — sub-tool tabs
                    val items = if (mode == "crop") config.tools.map { it to it.replaceFirstChar { c -> c.uppercase() } }
                    else listOf("brightness" to "Brightness", "contrast" to "Contrast", "saturation" to "Saturation")
                    val active = if (mode == "crop") cropSub else adjustSub
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        items.forEach { (key, label) ->
                            val on = active == key
                            BasicText(label, Modifier.clickable { if (mode == "crop") cropSub = key else adjustSub = key },
                                style = TextStyle(color = if (on) Color.White else Color.White.copy(alpha = 0.45f),
                                    fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                        }
                    }
                    // Row 2 — ruler
                    when {
                        mode == "crop" && cropSub == "zoom" -> Ruler(scale, 1f..8f, String.format("%.1fx", scale)) { scale = it }
                        mode == "crop" -> Ruler(rotationDeg, -180f..180f, "${rotationDeg.toInt()}°") { rotationDeg = it }
                        adjustSub == "brightness" -> Ruler(brightness, -100f..100f, sign(brightness)) { brightness = it }
                        adjustSub == "contrast" -> Ruler(contrast, -100f..100f, sign(contrast)) { contrast = it }
                        else -> Ruler(saturation, -100f..100f, sign(saturation)) { saturation = it }
                    }
                }

                // Row 3 — Cancel | modes | Done
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Cancel", Modifier.clickable { onCancel(edited) }, style = TextStyle(color = Color.White, fontSize = 15.sp))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModeTab("Crop", mode == "crop") { mode = "crop" }
                            ModeTab("Adjust", mode == "adjust") { mode = "adjust" }
                            ModeTab("Filter", mode == "filter") { mode = "filter" }
                        }
                    }
                    BasicText("Done", Modifier.clickable {
                        onDone(CropState(scale, rotationDeg, offset, coverScale, vpW, vpH, shape, aspectRatio, brightness, contrast, saturation))
                    }, style = TextStyle(color = Color(0xFFEA7A3B), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun ModeTab(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) Color.White else Color.Transparent, CircleShape).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = TextStyle(color = if (on) Color.Black else Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
    }
}

private fun sign(v: Float): String = "${if (v > 0) "+" else ""}${v.toInt()}"

/**
 * Keep the image covering the (axis-aligned) crop viewport at ANY rotation.
 * The offset is un-rotated into the image's own axes, clamped against the
 * rotated image's coverage, then rotated back. w/h are the on-screen image
 * size (display * scale); vpW/vpH the viewport. At 0° this reduces to the
 * plain `±(w - vpW)/2` clamp.
 */
private fun clampOffset(o: Offset, w: Float, h: Float, rotDeg: Float, vpW: Float, vpH: Float): Offset {
    val rad = Math.toRadians(rotDeg.toDouble())
    val cosT = cos(rad).toFloat()
    val sinT = sin(rad).toFloat()
    val ac = abs(cosT)
    val asn = abs(sinT)
    // Half-extent of the (rotated) viewport projected onto the image axes.
    val ax = ac * vpW / 2f + asn * vpH / 2f
    val ay = asn * vpW / 2f + ac * vpH / 2f
    val limX = max(0f, w / 2f - ax)
    val limY = max(0f, h / 2f - ay)
    // u = R(-θ) · o, clamp, then o = R(θ) · u.
    val ux = (cosT * o.x + sinT * o.y).coerceIn(-limX, limX)
    val uy = (-sinT * o.x + cosT * o.y).coerceIn(-limY, limY)
    return Offset(cosT * ux - sinT * uy, sinT * ux + cosT * uy)
}

@Composable
private fun Ruler(value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    val span = range.endInclusive - range.start
    val fraction = if (span == 0f) 0.5f else ((value - range.start) / span).coerceIn(0f, 1f)
    fun clamp(v: Float) = v.coerceIn(range.start, range.endInclusive)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(display, style = TextStyle(color = Color(0xFF34C759), fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepButton("−") { onChange(clamp(value - span / 60f)) }
            BoxWithConstraints(Modifier.weight(1f)) {
                val wPx = constraints.maxWidth.toFloat()
                fun at(x: Float) = range.start + (x / wPx).coerceIn(0f, 1f) * span
                Canvas(Modifier.fillMaxWidth().height(44.dp)
                    .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)) } }
                    .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> onChange(at(ch.position.x)) } }) {
                    val n = 40; val gap = size.width / (n - 1)
                    for (i in 0 until n) {
                        val f = i / (n - 1f); val h = if (i % 5 == 0) 20.dp.toPx() else 11.dp.toPx()
                        drawLine(if (f <= fraction) Color(0xFF34C759) else Color.White.copy(alpha = 0.25f),
                            Offset(i * gap, center.y - h / 2), Offset(i * gap, center.y + h / 2), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            StepButton("+") { onChange(clamp(value + span / 60f)) }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).background(Color.White.copy(alpha = 0.08f), CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        BasicText(label, style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp))
    }
}

private fun buildMatrix(bmpW: Float, bmpH: Float, combinedScale: Float, rotationDeg: Float, targetCx: Float, targetCy: Float): Matrix =
    Matrix().apply {
        postTranslate(-bmpW / 2f, -bmpH / 2f); postScale(combinedScale, combinedScale)
        postRotate(rotationDeg); postTranslate(targetCx, targetCy)
    }

object CropRenderer {
    fun render(context: android.content.Context, bitmap: Bitmap, state: CropState, config: ImageCropperFunctions.CropConfig): String? {
        val ratio = state.aspectRatio
        val outW: Int; val outH: Int
        if (ratio >= 1f) { outW = config.outputSize; outH = (config.outputSize / ratio).toInt() }
        else { outH = config.outputSize; outW = (config.outputSize * ratio).toInt() }

        val k = outW / state.viewportW.coerceAtLeast(1f)
        val output = Bitmap.createBitmap(outW.coerceAtLeast(1), outH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val circle = state.shape == "circle"
        if (circle) canvas.clipPath(android.graphics.Path().apply { addOval(RectF(0f, 0f, outW.toFloat(), outH.toFloat()), android.graphics.Path.Direction.CW) })

        // Bake crop + colour in one draw.
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colourMatrix(state.brightness, state.contrast, state.saturation))
        }
        canvas.drawBitmap(bitmap, buildMatrix(bitmap.width.toFloat(), bitmap.height.toFloat(),
            k * state.scale * state.fitScale, state.rotationDeg, outW / 2f + k * state.offset.x, outH / 2f + k * state.offset.y), paint)

        return try {
            val ext = if (circle) "png" else "jpg"
            val file = File(context.cacheDir, "cropped_${System.currentTimeMillis()}.$ext")
            FileOutputStream(file).use { out ->
                if (circle) output.compress(Bitmap.CompressFormat.PNG, 100, out) else output.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            file.absolutePath
        } catch (e: Exception) { null } finally { output.recycle() }
    }
}
