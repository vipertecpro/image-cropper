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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
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
        val modes: List<String>,
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
                tools = parseStringList(parameters["tools"])
                    .filter { it == "zoom" || it == "rotate" }.ifEmpty { listOf("zoom", "rotate") },
                modes = parseStringList(parameters["modes"])
                    .filter { it == "crop" || it == "adjust" || it == "filter" }
                    .ifEmpty { listOf("crop", "adjust", "filter") },
                presets = parsePresets(parameters["presets"]),
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
            val night = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val view = ComposeView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // Opaque, theme-aware overlay — otherwise the picked image behind us
                // shows through the editor (iOS: host.view.backgroundColor = systemBackground).
                setBackgroundColor(if (night) 0xFF0B0B0C.toInt() else 0xFFF4F4F5.toInt())
                isClickable = true // swallow touches so they don't reach the screen underneath
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
    var mode by remember { mutableStateOf(config.modes.firstOrNull() ?: "crop") }
    var cropSub by remember { mutableStateOf(config.tools.firstOrNull() ?: "zoom") }
    var adjustSub by remember { mutableStateOf("brightness") }

    val edited = scale != 1f || rotationDeg != 0f || offset != Offset.Zero ||
        brightness != 0f || contrast != 0f || saturation != 0f
    val circle = shape == "circle"
    // Crop can be turned off entirely (adjust/filter-only): then we show the WHOLE
    // image, no crop frame or gestures, and export the full photo with colour baked.
    val cropEnabled = "crop" in config.modes
    val surface = surfaceColor()
    val onSurface = onSurfaceColor()
    val filterThumb = remember(bitmap) { bitmap.asImageBitmap() }

    BoxWithConstraints(Modifier.fillMaxSize().background(surface)) {
        // Hoisted geometry so the Done button (below the stage) can build CropState.
        var geomCover by remember { mutableStateOf(1f) }
        var geomVpW by remember { mutableStateOf(1f) }
        var geomVpH by remember { mutableStateOf(1f) }

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // ---- Title bar: back button + dynamic mode title ----
            Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp)) {
                Box(
                    Modifier.align(Alignment.CenterStart).size(40.dp).clickable { onCancel(edited) },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(22.dp)) {
                        val sw = size.width * 0.11f
                        drawLine(onSurface, Offset(size.width * 0.6f, size.height * 0.22f),
                            Offset(size.width * 0.34f, size.height * 0.5f), sw, StrokeCap.Round)
                        drawLine(onSurface, Offset(size.width * 0.34f, size.height * 0.5f),
                            Offset(size.width * 0.6f, size.height * 0.78f), sw, StrokeCap.Round)
                    }
                }
                BasicText(
                    when (mode) { "adjust" -> "Adjust"; "filter" -> "Filter"; else -> "Crop" },
                    Modifier.align(Alignment.Center),
                    style = TextStyle(color = onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                )
            }

            // ---- Image stage: fills the space between the title bar and the controls.
            // clipToBounds is ESSENTIAL: Compose draws do NOT clip to layout bounds, so a
            // zoomed image would otherwise paint over the controls below it.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                val cw = constraints.maxWidth.toFloat()
                val stageH = constraints.maxHeight.toFloat()
                val side = min(cw, stageH) * 0.92f
                val vpW = if (aspectRatio >= 1f) side else side * aspectRatio
                val vpH = if (aspectRatio >= 1f) side / aspectRatio else side
                val left = (cw - vpW) / 2f
                val top = (stageH - vpH) / 2f
                // COVER the crop frame at user-scale 1 (never black inside).
                val coverScale = kotlin.math.max(vpW / bitmap.width, vpH / bitmap.height)
                val displayW = bitmap.width * coverScale
                val displayH = bitmap.height * coverScale
                SideEffect { geomCover = coverScale; geomVpW = vpW; geomVpH = vpH }
                // FIT the whole image when crop is off; COVER the frame when it's on.
                val fitScale = min((cw * 0.92f) / bitmap.width, (stageH * 0.92f) / bitmap.height)
                val gestureMod = if (cropEnabled) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rot ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            rotationDeg += rot
                            offset = clampOffset(offset + pan, displayW * scale, displayH * scale, rotationDeg, vpW, vpH)
                        }
                    }
                } else {
                    Modifier
                }
                Canvas(Modifier.fillMaxSize().then(gestureMod)) {
                    // Build the paint HERE so the draw reads brightness/contrast/saturation
                    // as snapshot state → the Canvas auto-invalidates on live colour changes.
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(colourMatrix(brightness, contrast, saturation))
                    }
                    val drawScale = if (cropEnabled) scale * coverScale else fitScale
                    val drawRot = if (cropEnabled) rotationDeg else 0f
                    val dx = if (cropEnabled) offset.x else 0f
                    val dy = if (cropEnabled) offset.y else 0f
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        bitmap,
                        buildMatrix(bitmap.width.toFloat(), bitmap.height.toFloat(),
                            drawScale, drawRot, cw / 2f + dx, stageH / 2f + dy),
                        paint
                    )
                }
                if (cropEnabled) {
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
                }
            }

            // ---- Bottom controls (wrap content; the image stage above takes the slack) ----
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (mode == "crop" && config.presets.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        config.presets.forEach { p ->
                            val on = p.shape == shape && abs(p.aspectRatio - aspectRatio) < 0.001f
                            Column(
                                Modifier.clickable {
                                    // Switch frame AND re-centre/re-cover the image.
                                    shape = p.shape; aspectRatio = p.aspectRatio
                                    scale = 1f; offset = Offset.Zero; rotationDeg = 0f
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                PresetIcon(p.shape == "circle", on)
                                BasicText(p.label,
                                    style = TextStyle(color = if (on) Color(0xFF34C759) else onSurface.copy(alpha = 0.6f),
                                        fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                            }
                        }
                    }
                }

                if (mode == "filter") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ImageCropperFunctions.filters.forEach { f ->
                            val on = brightness == f.brightness && contrast == f.contrast && saturation == f.saturation
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.clickable { brightness = f.brightness; contrast = f.contrast; saturation = f.saturation }
                            ) {
                                Image(
                                    bitmap = filterThumb,
                                    contentDescription = f.name,
                                    contentScale = ContentScale.Crop,
                                    colorFilter = ColorFilter.colorMatrix(
                                        androidx.compose.ui.graphics.ColorMatrix(
                                            colourMatrix(f.brightness, f.contrast, f.saturation).array)),
                                    modifier = Modifier.size(60.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            if (on) 2.dp else 1.dp,
                                            if (on) Color(0xFF34C759) else onSurface.copy(alpha = 0.25f),
                                            RoundedCornerShape(10.dp)
                                        )
                                )
                                BasicText(f.name,
                                    style = TextStyle(color = if (on) Color(0xFF34C759) else onSurface.copy(alpha = 0.7f), fontSize = 11.sp))
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
                                style = TextStyle(color = if (on) onSurface else onSurface.copy(alpha = 0.45f),
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
                    BasicText("Cancel", Modifier.clickable { onCancel(edited) }, style = TextStyle(color = onSurface, fontSize = 15.sp))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                        // Only show the mode switcher when more than one mode is enabled.
                        if (config.modes.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                if ("crop" in config.modes) ModeIcon("crop", mode == "crop") { mode = "crop" }
                                if ("adjust" in config.modes) ModeIcon("adjust", mode == "adjust") { mode = "adjust" }
                                if ("filter" in config.modes) ModeIcon("filter", mode == "filter") { mode = "filter" }
                            }
                        }
                    }
                    BasicText("Done", Modifier.clickable {
                        onDone(CropState(scale, rotationDeg, offset, geomCover, geomVpW, geomVpH, shape, aspectRatio, brightness, contrast, saturation))
                    }, style = TextStyle(color = Color(0xFFEA7A3B), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

/**
 * Mode button matching iOS: an icon in a circle. Active = filled white circle
 * with a black icon; inactive = transparent with a translucent-white icon.
 * Icons are drawn with Canvas primitives so the plugin needs no icon library.
 */
@Composable
private fun ModeIcon(kind: String, active: Boolean, onClick: () -> Unit) {
    val onSurface = onSurfaceColor()
    val surface = surfaceColor()
    val fg = if (active) surface else onSurface.copy(alpha = 0.75f)
    Box(
        Modifier.size(40.dp)
            .background(if (active) onSurface else Color.Transparent, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val sw = w * 0.09f
            when (kind) {
                "crop" -> drawRoundRect(fg, topLeft = Offset(w * 0.15f, h * 0.15f),
                    size = Size(w * 0.7f, h * 0.7f), cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
                    style = Stroke(sw))
                "adjust" -> {
                    val ys = listOf(0.25f, 0.5f, 0.75f)
                    val knob = listOf(0.66f, 0.34f, 0.58f)
                    ys.forEachIndexed { i, yf ->
                        val y = h * yf
                        drawLine(fg, Offset(w * 0.12f, y), Offset(w * 0.88f, y), strokeWidth = sw, cap = StrokeCap.Round)
                        drawCircle(fg, radius = w * 0.09f, center = Offset(w * knob[i], y))
                    }
                }
                else -> { // filter — three overlapping circles
                    val r = w * 0.22f
                    drawCircle(fg, r, Offset(w * 0.38f, h * 0.42f), style = Stroke(sw))
                    drawCircle(fg, r, Offset(w * 0.62f, h * 0.42f), style = Stroke(sw))
                    drawCircle(fg, r, Offset(w * 0.5f, h * 0.63f), style = Stroke(sw))
                }
            }
        }
    }
}

/** Small crop-shape glyph shown above each preset label (circle vs rounded rect). */
@Composable
private fun PresetIcon(circle: Boolean, on: Boolean) {
    val onSurface = onSurfaceColor()
    val c = if (on) Color(0xFF34C759) else onSurface.copy(alpha = 0.6f)
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val sw = w * 0.09f
        if (circle) {
            drawCircle(c, radius = w * 0.4f, style = Stroke(sw))
        } else {
            drawRoundRect(c, topLeft = Offset(w * 0.13f, w * 0.22f), size = Size(w * 0.74f, w * 0.56f),
                cornerRadius = CornerRadius(w * 0.1f, w * 0.1f), style = Stroke(sw))
        }
    }
}

private fun sign(v: Float): String = "${if (v > 0) "+" else ""}${v.toInt()}"

/**
 * The Android bridge leaves nested JSON arrays as [org.json.JSONArray] (not a Kotlin
 * List) — so a plain `as? List<*>` cast silently yields null. These helpers accept
 * BOTH shapes so the config's `tools` / `presets` parse on every bridge implementation.
 */
private fun parseStringList(any: Any?): List<String> = when (any) {
    is List<*> -> any.mapNotNull { it as? String }
    is JSONArray -> (0 until any.length()).mapNotNull { i -> any.optString(i).takeIf { it.isNotEmpty() } }
    else -> emptyList()
}

private fun parsePresets(any: Any?): List<ImageCropperFunctions.CropPreset> = when (any) {
    is List<*> -> any.mapNotNull { it as? Map<*, *> }.map { m ->
        ImageCropperFunctions.CropPreset(
            m["key"] as? String ?: "", m["label"] as? String ?: "",
            if ((m["shape"] as? String) == "circle") "circle" else "rect",
            ((m["aspectRatio"] as? Number)?.toFloat() ?: 1f).coerceAtLeast(0.01f))
    }
    is JSONArray -> (0 until any.length()).mapNotNull { any.optJSONObject(it) }.map { jo ->
        ImageCropperFunctions.CropPreset(
            jo.optString("key"), jo.optString("label"),
            if (jo.optString("shape") == "circle") "circle" else "rect",
            jo.optDouble("aspectRatio", 1.0).toFloat().coerceAtLeast(0.01f))
    }
    else -> emptyList()
}

/** Theme-adaptive colours that follow the system light/dark setting. */
@Composable
private fun surfaceColor(): Color = if (isSystemInDarkTheme()) Color(0xFF0B0B0C) else Color(0xFFF4F4F5)

@Composable
private fun onSurfaceColor(): Color = if (isSystemInDarkTheme()) Color.White else Color(0xFF17171A)

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
    val onSurface = onSurfaceColor()
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
                        drawLine(if (f <= fraction) Color(0xFF34C759) else onSurface.copy(alpha = 0.25f),
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
    val onSurface = onSurfaceColor()
    Box(Modifier.size(34.dp).background(onSurface.copy(alpha = 0.08f), CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        BasicText(label, style = TextStyle(color = onSurface.copy(alpha = 0.85f), fontSize = 20.sp))
    }
}

private fun buildMatrix(bmpW: Float, bmpH: Float, combinedScale: Float, rotationDeg: Float, targetCx: Float, targetCy: Float): Matrix =
    Matrix().apply {
        postTranslate(-bmpW / 2f, -bmpH / 2f); postScale(combinedScale, combinedScale)
        postRotate(rotationDeg); postTranslate(targetCx, targetCy)
    }

object CropRenderer {
    fun render(context: android.content.Context, bitmap: Bitmap, state: CropState, config: ImageCropperFunctions.CropConfig): String? {
        // No crop mode → export the WHOLE image (longest edge = outputSize) + colour.
        if ("crop" !in config.modes) {
            val s = config.outputSize.toFloat() / kotlin.math.max(bitmap.width, bitmap.height)
            val fw = (bitmap.width * s).toInt().coerceAtLeast(1)
            val fh = (bitmap.height * s).toInt().coerceAtLeast(1)
            val full = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(colourMatrix(state.brightness, state.contrast, state.saturation))
            }
            android.graphics.Canvas(full).drawBitmap(bitmap,
                buildMatrix(bitmap.width.toFloat(), bitmap.height.toFloat(), s, 0f, fw / 2f, fh / 2f), paint)
            return try {
                val file = File(context.cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out -> full.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                file.absolutePath
            } catch (e: Exception) { null } finally { full.recycle() }
        }

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
