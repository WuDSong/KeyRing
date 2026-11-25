package com.wudongsong.keyring.ui.entries

import android.graphics.Bitmap
import android.graphics.Canvas as BitmapCanvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.wudongsong.keyring.R
import com.wudongsong.keyring.util.ImageStorage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val FRAME_FRACTION = 0.8f
private const val MAX_ZOOM = 5f
private const val MAX_DECODE_EDGE = 2048
private const val ICON_EDGE = 512
private const val PREVIEW_DELAY_MS = 3_000L
private val CropBackground = Color(0xFF17181C)

@Composable
internal fun AvatarCropDialog(uri: Uri, onDismiss: () -> Unit, onCropped: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(uri) { mutableStateOf(false) }
    var saveFailed by remember(uri) { mutableStateOf(false) }
    var saving by remember(uri) { mutableStateOf(false) }
    var crop by remember(uri) { mutableStateOf<IconCrop?>(null) }

    LaunchedEffect(uri) {
        try {
            val decoded = withContext(Dispatchers.IO) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) {
                    decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val (width, height) = info.size.let { it.width to it.height }
                    val longest = maxOf(width, height)
                    if (longest > MAX_DECODE_EDGE) {
                        val scale = MAX_DECODE_EDGE.toFloat() / longest
                        decoder.setTargetSize(
                            (width * scale).roundToInt().coerceAtLeast(1),
                            (height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            }
            crop = IconCrop(Offset(decoded.width / 2f, decoded.height / 2f))
            bitmap = decoded
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    fun saveCrop(image: Bitmap, selection: IconCrop) {
        if (saving) return
        saving = true
        saveFailed = false
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { selection.encode(image) }
                val request = coroutineContext
                // Finish transferring or deleting the file if the editor locks during the write.
                withContext(NonCancellable) {
                    val path = withContext(Dispatchers.IO) {
                        ImageStorage.saveBytes(context, bytes, "png")
                    }
                    if (!request.isActive) {
                        withContext(Dispatchers.IO) { ImageStorage.deleteIfExists(path) }
                    } else if (path != null) {
                        onCropped(path)
                    } else {
                        saveFailed = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }

    FullScreenCropDialog(cancelable = !saving, onDismiss = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize(), color = CropBackground, contentColor = Color.White) {
            // Hoisted so the bottom bar and the content share the same snapshot values.
            val image = bitmap
            val selection = crop
            Scaffold(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                // Measured before the content, so the actions stay on screen even when the preview
                // and the controls need more room than the display offers (landscape, split screen,
                // large font scale).
                bottomBar = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(
                            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                enabled = !saving,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            ) {
                                Text(stringResource(android.R.string.cancel))
                            }
                            Button(
                                enabled = image != null && selection != null && !saving,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (image != null && selection != null) saveCrop(image, selection)
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (saving) R.string.avatar_crop_saving
                                        else R.string.avatar_crop_apply
                                    )
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.avatar_crop_title),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                    )
                    // Keep the image area square in landscape, where height limits the crop size.
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val stageModifier =
                            (if (maxWidth > maxHeight) Modifier.size(maxHeight)
                             else Modifier.fillMaxSize())
                                .clip(RoundedCornerShape(16.dp))
                        Box(modifier = stageModifier, contentAlignment = Alignment.Center) {
                            when {
                                loadFailed ->
                                    Text(
                                        stringResource(R.string.avatar_crop_open_failed),
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(24.dp),
                                    )
                                image == null || selection == null ->
                                    CircularProgressIndicator(color = Color.White)
                                else -> CropPreview(
                                    image = image,
                                    selection = { checkNotNull(crop) },
                                    enabled = !saving,
                                    onSelectionChange = { crop = it },
                                )
                            }
                        }
                    }
                    if (saveFailed) {
                        Text(
                            stringResource(R.string.avatar_crop_save_failed),
                            color = Color(0xFFFFB4AB),
                            style = MaterialTheme.typography.bodySmall,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenCropDialog(
    cancelable: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val parent = rememberCompositionContext()
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestContent by rememberUpdatedState(content)
    val hostWindow = LocalView.current.rootView.layoutParams as? WindowManager.LayoutParams
    val secure = hostWindow != null && hostWindow.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    // Use a non-floating window so older Android versions also lay out behind the system bars.
    val dialog = remember(context) {
        ComponentDialog(context, android.R.style.Theme_Material_NoActionBar)
    }
    SideEffect {
        dialog.setCancelable(cancelable)
        val window = checkNotNull(dialog.window)
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    DisposableEffect(dialog, parent) {
        val window = checkNotNull(dialog.window)
        window.setBackgroundDrawable(ColorDrawable(CropBackground.toArgb()))
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { latestDismiss() }
        val view = ComposeView(context).apply {
            setParentCompositionContext(parent)
            setContent { latestContent() }
        }
        dialog.setContentView(view)
        dialog.show()
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        onDispose {
            dialog.dismiss()
            view.disposeComposition()
        }
    }
}

@Composable
private fun CropPreview(
    image: Bitmap,
    selection: () -> IconCrop,
    enabled: Boolean,
    onSelectionChange: (IconCrop) -> Unit,
) {
    var touching by remember(image) { mutableStateOf(false) }
    var interaction by remember(image) { mutableStateOf(0) }
    var previewOnly by remember(image) { mutableStateOf(false) }
    val currentSelection by rememberUpdatedState(selection)
    val updateSelection by rememberUpdatedState(onSelectionChange)
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }

    LaunchedEffect(image, touching, interaction, enabled) {
        if (enabled && !touching) {
            delay(PREVIEW_DELAY_MS)
            previewOnly = true
        }
    }

    val maskOpacity by animateFloatAsState(
        targetValue = if (previewOnly) 1f else 0.6f,
        animationSpec = tween(180),
        label = "crop mask",
    )
    val hint = stringResource(R.string.avatar_crop_hint)
    val mode = stringResource(
        if (previewOnly) R.string.avatar_crop_preview else R.string.avatar_crop_editing,
    )
    Canvas(
        modifier = Modifier.fillMaxSize()
            .clipToBounds()
            .semantics {
                contentDescription = hint
                stateDescription = mode
            }
            .pointerInput(image, enabled) {
                if (!enabled) return@pointerInput
                // Observe touches without consuming them so the pan and pinch detector still runs.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    interaction++ // Restart the timer even if down and up arrive in one frame.
                    previewOnly = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        touching = false
                    }
                }
            }
            .pointerInput(image, enabled) {
                if (!enabled) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val frame = minOf(size.width, size.height) * FRAME_FRACTION
                    if (frame > 0f) {
                        val frameCenter = Offset(size.width / 2f, size.height / 2f)
                        val current = currentSelection()
                        val next = current.zoomed(image, current.zoom * zoom)
                        val newSide = next.side(image)
                        val center = current.center +
                            (centroid - frameCenter) * ((current.side(image) - newSide) / frame) -
                            pan * (newSide / frame)
                        updateSelection(next.copy(center = center).clamped(image))
                    }
                }
            },
    ) {
        val frame = size.minDimension * FRAME_FRACTION
        val radius = frame / 2f
        drawIntoCanvas {
            currentSelection().draw(image, it.nativeCanvas, paint, center, frame)
        }
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addOval(Rect(center, radius))
        }
        drawPath(outside, CropBackground.copy(alpha = maskOpacity))
        drawCircle(
            color = Color.White.copy(alpha = (1f - maskOpacity) * 1.5f),
            radius = radius,
            style = Stroke(1.dp.toPx()),
        )
    }
}

/** Crop center and side are in decoded image pixels. Preview and export share the same transform. */
private data class IconCrop(val center: Offset, val zoom: Float = 1f) {
    fun side(image: Bitmap): Float = minOf(image.width, image.height) / zoom

    fun clamped(image: Bitmap): IconCrop {
        val half = side(image) / 2f
        return copy(
            center = Offset(
                center.x.coerceIn(half, image.width - half),
                center.y.coerceIn(half, image.height - half),
            ),
        )
    }

    fun zoomed(image: Bitmap, value: Float): IconCrop =
        copy(zoom = value.coerceIn(1f, MAX_ZOOM)).clamped(image)

    fun draw(image: Bitmap, canvas: BitmapCanvas, paint: Paint, targetCenter: Offset, targetSide: Float) {
        val scale = targetSide / side(image)
        val left = targetCenter.x - center.x * scale
        val top = targetCenter.y - center.y * scale
        canvas.drawBitmap(image, null, RectF(left, top, left + image.width * scale, top + image.height * scale), paint)
    }

    fun encode(image: Bitmap): ByteArray {
        val output = Bitmap.createBitmap(ICON_EDGE, ICON_EDGE, Bitmap.Config.ARGB_8888)
        try {
            val center = Offset(ICON_EDGE / 2f, ICON_EDGE / 2f)
            draw(image, BitmapCanvas(output), Paint(Paint.FILTER_BITMAP_FLAG), center, ICON_EDGE.toFloat())
            return ByteArrayOutputStream().use {
                check(output.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            output.recycle()
        }
    }
}
