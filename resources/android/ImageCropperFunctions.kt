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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.toArgb
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

    /** Remote images larger than this are rejected before decoding (mirrors iOS). */
    private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024

    data class CropPreset(val key: String, val label: String, val shape: String, val aspectRatio: Float)

    /**
     * Host-app theme overrides. Every color is optional: null falls back to the
     * editor's built-in system-adaptive default, so the editor blends into ANY
     * app — the host decides, not the plugin. Mirrors the iOS CropTheme.
     */
    data class CropTheme(
        val background: Color? = null,  // editor screen background
        val text: Color? = null,        // titles, labels, inactive icons
        val accent: Color? = null,      // the Done button
        val highlight: Color? = null,   // active states (selection, ruler value/fill)
    )

    data class CropConfig(
        val path: String,
        val shape: String,
        val aspectRatio: Float,
        val tools: List<String>,
        val modes: List<String>,
        val presets: List<CropPreset>,
        val outputSize: Int,
        val theme: CropTheme,
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
                // Clamp to a safe range: an absurd value (typo / bad config) would
                // otherwise request a giant Bitmap and OOM-crash the render.
                outputSize = ((parameters["outputSize"] as? Number)?.toInt() ?: 1024).coerceIn(16, 4096),
                theme = parseTheme(parameters["theme"]),
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
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val overlayTag = "image_cropper_overlay"
            // Re-entrancy guard: never stack two editors (e.g. a double invocation).
            if (root.findViewWithTag<android.view.View>(overlayTag) != null) {
                dispatch(EVENT_CANCELLED, config.id); return
            }

            val remote = config.path.startsWith("http://", ignoreCase = true) ||
                config.path.startsWith("https://", ignoreCase = true)

            // Local sources decode up-front (a failure never shows a screen);
            // remote sources show the loading screen first and decode after the
            // native download completes.
            val localBitmap = if (remote) null else loadUprightBitmap(config.path, config.outputSize)
                ?: run { dispatch(EVENT_CANCELLED, config.id); return }
            val night = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val editorBackground = config.theme.background?.toArgb()
                ?: if (night) 0xFF0B0B0C.toInt() else 0xFFF4F4F5.toInt()
            val view = ComposeView(activity).apply {
                tag = overlayTag
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // Opaque overlay — otherwise the picked image behind us shows
                // through. Host-app theme wins; else follow the system theme
                // (iOS: host.view.backgroundColor = theme ?? systemBackground).
                setBackgroundColor(editorBackground)
                isClickable = true // swallow touches so they don't reach the screen underneath
            }

            // Lock orientation while the editor is up. A config-change (rotation)
            // would destroy this programmatically-added overlay and its Compose
            // state, leaving the PHP side hanging with no event.
            val prevOrientation = activity.requestedOrientation
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED

            // The editor draws edge to edge over the host screen, so the system
            // bars have to suit ITS background, not the host's: dark icons on
            // the dark editor were unreadable. Put the host's choice back after.
            val systemBars = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            val prevLightStatusBars = systemBars.isAppearanceLightStatusBars
            val prevLightNavigationBars = systemBars.isAppearanceLightNavigationBars
            val lightEditor = androidx.core.graphics.ColorUtils.calculateLuminance(editorBackground) > 0.5
            systemBars.isAppearanceLightStatusBars = lightEditor
            systemBars.isAppearanceLightNavigationBars = lightEditor

            // Deliver EXACTLY ONE terminal event: a double-tap on Done, Done racing
            // Cancel, or a Back press can otherwise fire two events / none.
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var backCallback: androidx.activity.OnBackPressedCallback
            fun cleanup() {
                (view.parent as? ViewGroup)?.removeView(view)
                activity.requestedOrientation = prevOrientation
                systemBars.isAppearanceLightStatusBars = prevLightStatusBars
                systemBars.isAppearanceLightNavigationBars = prevLightNavigationBars
                backCallback.remove()
                // The source bitmap is intentionally NOT recycled here — a render
                // thread may still be reading it; let GC reclaim it.
            }
            fun finishCancelled() { if (finished.compareAndSet(false, true)) { cleanup(); dispatch(EVENT_CANCELLED, config.id) } }
            fun finishCropped(path: String) { if (finished.compareAndSet(false, true)) { cleanup(); dispatch(EVENT_CROPPED, config.id, path) } }

            // System BACK → treat as cancel, so it can never orphan the overlay.
            backCallback = object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { finishCancelled() }
            }
            activity.onBackPressedDispatcher.addCallback(backCallback)

            // null while a remote download is in flight → the loading screen shows.
            val bitmapState = androidx.compose.runtime.mutableStateOf(localBitmap)

            view.setContent {
                val bitmap = bitmapState.value
                if (bitmap == null) {
                    DownloadingScreen(config.theme) { finishCancelled() }
                    return@setContent
                }
                EditorScreen(bitmap, config,
                    onCancel = { edited ->
                        if (edited) {
                            android.app.AlertDialog.Builder(activity)
                                .setTitle("Discard Changes")
                                .setMessage("Are you sure you want to discard these changes?")
                                .setPositiveButton("Discard") { _, _ -> finishCancelled() }
                                .setNegativeButton("Cancel", null).show()
                        } else { finishCancelled() }
                    },
                    onDone = { state ->
                        Thread {
                            // Render off the UI thread; any failure (incl. OOM) must
                            // still resolve the PHP promise, never crash or hang.
                            val out = try {
                                CropRenderer.render(activity, bitmap, state, config)
                            } catch (t: Throwable) { Log.e(TAG, "render failed: ${t.message}", t); null }
                            activity.runOnUiThread {
                                if (out != null) finishCropped(out) else finishCancelled()
                            }
                        }.start()
                    })
            }
            root.addView(view)

            if (remote) {
                // Native download off the UI thread; decode through the same
                // downsampling/EXIF loader as local files, then swap in the
                // editor. Every failure resolves as CropCancelled.
                Thread {
                    val file = downloadToCache(config.path)
                    val decoded = try {
                        file?.let { loadUprightBitmap(it.absolutePath, config.outputSize) }
                    } catch (t: Throwable) { Log.e(TAG, "remote decode failed: ${t.message}", t); null }
                    file?.delete()
                    activity.runOnUiThread {
                        if (finished.get()) return@runOnUiThread   // user cancelled mid-download
                        if (decoded != null) bitmapState.value = decoded else finishCancelled()
                    }
                }.start()
            }
        }

        /**
         * Download an http(s) source to the app cache. Bounded (30s read
         * timeout, 64 MB cap) and quiet — any failure returns null, which the
         * caller resolves as CropCancelled. HTTPS is recommended; cleartext
         * http is subject to the app's network security policy.
         */
        private fun downloadToCache(urlString: String): File? {
            var connection: java.net.HttpURLConnection? = null
            return try {
                connection = (java.net.URL(urlString).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                if (connection.responseCode !in 200..299) return null
                if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) return null

                val file = File(activity.cacheDir, "cropper_download_${java.util.UUID.randomUUID()}")
                var total = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_DOWNLOAD_BYTES) { file.delete(); return null }
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (total == 0L) { file.delete(); null } else file
            } catch (t: Throwable) {
                Log.e(TAG, "download failed: ${t.message}", t); null
            } finally {
                connection?.disconnect()
            }
        }

        private fun dispatch(event: String, id: String?, path: String? = null) {
            val payload = JSONObject().apply { path?.let { put("path", it) }; id?.let { put("id", it) } }
            NativeActionCoordinator.dispatchEvent(activity, event, payload.toString())
        }

        /**
         * Decode the source DOWNSAMPLED (bounds memory — a full-res camera photo
         * otherwise allocates tens of MB and OOM-crashes on decode) and rotate it
         * upright per its EXIF orientation. The longest edge is capped relative to
         * `outputSize`, which is all the detail the crop can ever need.
         */
        private fun loadUprightBitmap(path: String, outputSize: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val maxDim = min(4096, max(2048, outputSize * 2))
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
            val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null

            return try {
                val m = Matrix()
                when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                    else -> {}
                }
                if (m.isIdentity) {
                    raw
                } else {
                    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    if (rotated != raw) raw.recycle()   // free the pre-rotation copy
                    rotated
                }
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
    // Host-app theme wins; each color falls back to the classic adaptive default.
    val surface = config.theme.background ?: surfaceColor()
    val onSurface = config.theme.text ?: onSurfaceColor()
    val accent = config.theme.accent ?: Color(0xFFEA7A3B)
    val highlight = config.theme.highlight ?: Color(0xFF34C759)
    // A small thumbnail for the 6 filter chips — never upload the full-res source
    // as a GPU texture (can exceed GL_MAX_TEXTURE_SIZE and render blank).
    val filterThumb = remember(bitmap) {
        val maxEdge = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val ts = (160f / maxEdge).coerceAtMost(1f)
        if (ts < 1f)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ts).toInt().coerceAtLeast(1),
                (bitmap.height * ts).toInt().coerceAtLeast(1), true).asImageBitmap()
        else bitmap.asImageBitmap()
    }

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
                    // Box centres the strip when it fits; it still scrolls when it doesn't.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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
                                    PresetIcon(p.shape == "circle", on, onSurface, highlight)
                                    BasicText(p.label,
                                        style = TextStyle(color = if (on) highlight else onSurface.copy(alpha = 0.6f),
                                            fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                                }
                            }
                        }
                    }
                }

                if (mode == "filter") {
                    // Box centres the strip when it fits; it still scrolls when it doesn't.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
                                                if (on) highlight else onSurface.copy(alpha = 0.25f),
                                                RoundedCornerShape(10.dp)
                                            )
                                    )
                                    BasicText(f.name,
                                        style = TextStyle(color = if (on) highlight else onSurface.copy(alpha = 0.7f), fontSize = 11.sp))
                                }
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
                        mode == "crop" && cropSub == "zoom" -> Ruler(scale, 1f..8f, String.format("%.1fx", scale), onSurface, highlight) { scale = it }
                        mode == "crop" -> Ruler(rotationDeg, -180f..180f, "${rotationDeg.toInt()}°", onSurface, highlight) { rotationDeg = it }
                        adjustSub == "brightness" -> Ruler(brightness, -100f..100f, sign(brightness), onSurface, highlight) { brightness = it }
                        adjustSub == "contrast" -> Ruler(contrast, -100f..100f, sign(contrast), onSurface, highlight) { contrast = it }
                        else -> Ruler(saturation, -100f..100f, sign(saturation), onSurface, highlight) { saturation = it }
                    }
                }

                // Row 3 — Cancel | modes | Done. A Box so the mode switcher is
                // centred on the SCREEN, not merely between two differently-sized
                // buttons (weight-centering drifts). Mirrors the iOS ZStack.
                Box(Modifier.fillMaxWidth()) {
                    BasicText("Cancel",
                        Modifier.align(Alignment.CenterStart).clickable { onCancel(edited) },
                        style = TextStyle(color = onSurface, fontSize = 15.sp))
                    // Only show the mode switcher when more than one mode is enabled.
                    if (config.modes.size > 1) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            if ("crop" in config.modes) ModeIcon("crop", mode == "crop", surface, onSurface) { mode = "crop" }
                            if ("adjust" in config.modes) ModeIcon("adjust", mode == "adjust", surface, onSurface) { mode = "adjust" }
                            if ("filter" in config.modes) ModeIcon("filter", mode == "filter", surface, onSurface) { mode = "filter" }
                        }
                    }
                    BasicText("Done",
                        Modifier.align(Alignment.CenterEnd).clickable {
                            onDone(CropState(scale, rotationDeg, offset, geomCover, geomVpW, geomVpH, shape, aspectRatio, brightness, contrast, saturation))
                        },
                        style = TextStyle(color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

/**
 * The SF-Symbols "crop" glyph, drawn with Canvas primitives: two overlapping
 * right-angle strokes — ⌐ entering from the left/bottom and ⌐ from top/right —
 * exactly the icon iOS shows. Shared by the mode button and rect presets so
 * both platforms read identically.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropGlyph(color: Color, sw: Float) {
    val w = size.width
    val h = size.height
    // Left vertical dropping to a bottom horizontal that exits right.
    drawLine(color, Offset(w * 0.30f, h * 0.08f), Offset(w * 0.30f, h * 0.70f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.30f, h * 0.70f), Offset(w * 0.92f, h * 0.70f), sw, StrokeCap.Round)
    // Top horizontal entering from the left, then a right vertical dropping out.
    drawLine(color, Offset(w * 0.08f, h * 0.30f), Offset(w * 0.70f, h * 0.30f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.70f, h * 0.30f), Offset(w * 0.70f, h * 0.92f), sw, StrokeCap.Round)
}

/**
 * Mode button matching iOS: an icon in a circle. Active = filled circle in the
 * app's text color with a background-colored icon; inactive = transparent with
 * a translucent icon. Icons are drawn with Canvas primitives (mirroring the
 * iOS SF Symbols: crop / slider.horizontal.3 / camera.filters) so the plugin
 * needs no icon library.
 */
@Composable
private fun ModeIcon(kind: String, active: Boolean, surface: Color, onSurface: Color, onClick: () -> Unit) {
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
                "crop" -> drawCropGlyph(fg, sw)
                "adjust" -> { // slider.horizontal.3
                    val ys = listOf(0.25f, 0.5f, 0.75f)
                    val knob = listOf(0.66f, 0.34f, 0.58f)
                    ys.forEachIndexed { i, yf ->
                        val y = h * yf
                        drawLine(fg, Offset(w * 0.12f, y), Offset(w * 0.88f, y), strokeWidth = sw, cap = StrokeCap.Round)
                        drawCircle(fg, radius = w * 0.09f, center = Offset(w * knob[i], y))
                    }
                }
                else -> { // filter — camera.filters, three overlapping circles
                    val r = w * 0.22f
                    drawCircle(fg, r, Offset(w * 0.38f, h * 0.42f), style = Stroke(sw))
                    drawCircle(fg, r, Offset(w * 0.62f, h * 0.42f), style = Stroke(sw))
                    drawCircle(fg, r, Offset(w * 0.5f, h * 0.63f), style = Stroke(sw))
                }
            }
        }
    }
}

/**
 * Preset glyph matching iOS: `person.crop.circle` (head + shoulders inside a
 * circle) for circle presets, the crop glyph for rect presets.
 */
@Composable
private fun PresetIcon(circle: Boolean, on: Boolean, onSurface: Color, highlight: Color) {
    val c = if (on) highlight else onSurface.copy(alpha = 0.6f)
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        if (circle) {
            val radius = w * 0.42f
            val center = Offset(w / 2f, h / 2f)
            drawCircle(c, radius = radius, center = center, style = Stroke(sw))
            clipPath(Path().apply { addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)) }) {
                drawCircle(c, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.40f))                 // head
                drawOval(c, topLeft = Offset(w * 0.24f, h * 0.60f), size = Size(w * 0.52f, h * 0.42f))  // shoulders
            }
        } else {
            drawCropGlyph(c, sw)
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

/** #RGB / #RRGGBB / #RRGGBBAA (leading '#' optional) → Compose Color, or null on junk. */
private fun parseHexColor(value: Any?): Color? {
    var s = (value as? String)?.trim()?.removePrefix("#") ?: return null
    if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
    if (s.length != 6 && s.length != 8) return null
    val v = s.toLongOrNull(16) ?: return null
    return if (s.length == 6) {
        Color(((0xFFL shl 24) or v).toInt())
    } else {
        // Incoming is RRGGBBAA (CSS order); Compose wants AARRGGBB.
        val a = v and 0xFF
        Color(((a shl 24) or (v ushr 8)).toInt())
    }
}

private fun parseTheme(any: Any?): ImageCropperFunctions.CropTheme = when (any) {
    is Map<*, *> -> ImageCropperFunctions.CropTheme(
        background = parseHexColor(any["background"]),
        text = parseHexColor(any["text"]),
        accent = parseHexColor(any["accent"]),
        highlight = parseHexColor(any["highlight"]),
    )
    is JSONObject -> ImageCropperFunctions.CropTheme(
        background = parseHexColor(any.optString("background").takeIf { it.isNotEmpty() }),
        text = parseHexColor(any.optString("text").takeIf { it.isNotEmpty() }),
        accent = parseHexColor(any.optString("accent").takeIf { it.isNotEmpty() }),
        highlight = parseHexColor(any.optString("highlight").takeIf { it.isNotEmpty() }),
    )
    else -> ImageCropperFunctions.CropTheme()
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

/**
 * Shown while a remote (http/https) source downloads — themed like the editor,
 * with a Cancel that fires CropCancelled. The spinner is hand-drawn (an
 * animated arc) so the plugin needs no Material dependency. Mirrors iOS.
 */
@Composable
private fun DownloadingScreen(theme: ImageCropperFunctions.CropTheme, onCancel: () -> Unit) {
    val surface = theme.background ?: surfaceColor()
    val onSurface = theme.text ?: onSurfaceColor()
    val highlight = theme.highlight ?: Color(0xFF34C759)
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin"
    )

    Column(
        Modifier.fillMaxSize().background(surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(44.dp)) {
            drawArc(
                highlight, startAngle = angle, sweepAngle = 270f, useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        BasicText("Loading image…", Modifier.padding(top = 18.dp),
            style = TextStyle(color = onSurface.copy(alpha = 0.8f), fontSize = 15.sp))
        BasicText("Cancel", Modifier.padding(top = 14.dp).clickable { onCancel() },
            style = TextStyle(color = onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    }
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
private fun Ruler(value: Float, range: ClosedFloatingPointRange<Float>, display: String, onSurface: Color, highlight: Color, onChange: (Float) -> Unit) {
    val span = range.endInclusive - range.start
    val fraction = if (span == 0f) 0.5f else ((value - range.start) / span).coerceIn(0f, 1f)
    fun clamp(v: Float) = v.coerceIn(range.start, range.endInclusive)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(display, style = TextStyle(color = highlight, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepButton("−", onSurface) { onChange(clamp(value - span / 60f)) }
            BoxWithConstraints(Modifier.weight(1f)) {
                val wPx = constraints.maxWidth.toFloat()
                fun at(x: Float) = range.start + (x / wPx).coerceIn(0f, 1f) * span
                Canvas(Modifier.fillMaxWidth().height(44.dp)
                    .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)) } }
                    .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> onChange(at(ch.position.x)) } }) {
                    val n = 40; val gap = size.width / (n - 1)
                    for (i in 0 until n) {
                        val f = i / (n - 1f); val h = if (i % 5 == 0) 20.dp.toPx() else 11.dp.toPx()
                        drawLine(if (f <= fraction) highlight else onSurface.copy(alpha = 0.25f),
                            Offset(i * gap, center.y - h / 2), Offset(i * gap, center.y + h / 2), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            StepButton("+", onSurface) { onChange(clamp(value + span / 60f)) }
        }
    }
}

@Composable
private fun StepButton(label: String, onSurface: Color, onClick: () -> Unit) {
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
            // Catch Throwable (incl. OutOfMemoryError): rendering must NEVER crash
            // the app or the render thread — a null return resolves PHP as cancelled.
            return try {
                val s = config.outputSize.toFloat() / max(bitmap.width, bitmap.height)
                val fw = (bitmap.width * s).toInt().coerceAtLeast(1)
                val fh = (bitmap.height * s).toInt().coerceAtLeast(1)
                val full = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
                try {
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(colourMatrix(state.brightness, state.contrast, state.saturation))
                    }
                    android.graphics.Canvas(full).drawBitmap(bitmap,
                        buildMatrix(bitmap.width.toFloat(), bitmap.height.toFloat(), s, 0f, fw / 2f, fh / 2f), paint)
                    val file = File(context.cacheDir, "cropped_${java.util.UUID.randomUUID()}.jpg")
                    FileOutputStream(file).use { out -> full.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                    file.absolutePath
                } finally { full.recycle() }
            } catch (t: Throwable) { Log.e("ImageCropper", "render failed: ${t.message}", t); null }
        }

        return try {
            val ratio = state.aspectRatio
            val outW: Int; val outH: Int
            if (ratio >= 1f) { outW = config.outputSize; outH = (config.outputSize / ratio).toInt().coerceAtLeast(1) }
            else { outH = config.outputSize; outW = (config.outputSize * ratio).toInt().coerceAtLeast(1) }

            val k = outW / state.viewportW.coerceAtLeast(1f)
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(output)
                val circle = state.shape == "circle"
                if (circle) canvas.clipPath(android.graphics.Path().apply { addOval(RectF(0f, 0f, outW.toFloat(), outH.toFloat()), android.graphics.Path.Direction.CW) })

                // Bake crop + colour in one draw.
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(colourMatrix(state.brightness, state.contrast, state.saturation))
                }
                canvas.drawBitmap(bitmap, buildMatrix(bitmap.width.toFloat(), bitmap.height.toFloat(),
                    k * state.scale * state.fitScale, state.rotationDeg, outW / 2f + k * state.offset.x, outH / 2f + k * state.offset.y), paint)

                val ext = if (circle) "png" else "jpg"
                val file = File(context.cacheDir, "cropped_${java.util.UUID.randomUUID()}.$ext")
                FileOutputStream(file).use { out ->
                    if (circle) output.compress(Bitmap.CompressFormat.PNG, 100, out) else output.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                file.absolutePath
            } finally { output.recycle() }
        } catch (t: Throwable) { Log.e("ImageCropper", "render failed: ${t.message}", t); null }
    }
}
