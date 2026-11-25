package com.wudongsong.keyring

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import com.wudongsong.keyring.ui.entries.AvatarCropDialog
import com.wudongsong.keyring.ui.theme.MyPasswordsTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Source image: 200x100, left half red, right half blue, and a 20x20 green square in the middle.
 * The default (unzoomed) selection is the centred 100x100 square, so image x 50..150 is exported
 * and export x maps linearly onto it.
 */
class AvatarCropTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun exportsTheCenteredSelection() =
        withSavedCrop { result ->
            assertEquals(Color.RED, result.getPixel(1, 256))
            assertEquals(Color.RED, result.getPixel(150, 256))
            assertEquals(Color.BLUE, result.getPixel(510, 256))
        }

    @Test
    fun pinchingInNarrowsTheSelection() =
        withSavedCrop(gesture = {
            // Zoom to roughly 3x: the frame then covers image x ~83..117, so the green centre
            // square (image x 90..110) reaches much further left in the export.
            pinch(
                start0 = center + Offset(-50f, 0f),
                end0 = center + Offset(-150f, 0f),
                start1 = center + Offset(50f, 0f),
                end1 = center + Offset(150f, 0f),
            )
        }) { result ->
            assertEquals(Color.RED, result.getPixel(1, 256))
            assertEquals(Color.GREEN, result.getPixel(150, 256))
        }

    @Test
    fun draggingMovesTheSelection() =
        withSavedCrop(gesture = {
            // Moving the image right selects its left (red) side.
            swipe(start = Offset(width * 0.1f, center.y), end = Offset(width * 0.9f, center.y))
        }) { result ->
            assertEquals(Color.RED, result.getPixel(1, 256))
            assertEquals(Color.RED, result.getPixel(510, 256))
        }

    @Test
    fun previewsAfterThreeIdleSecondsAndReturnsToEditingOnTouch() =
        withCropDialog {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val canvas = compose.onNodeWithContentDescription(context.getString(R.string.avatar_crop_hint))
            val editing = SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.avatar_crop_editing),
            )
            val preview = SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.avatar_crop_preview),
            )

            compose.mainClock.autoAdvance = false
            try {
                canvas.assert(editing)
                compose.mainClock.advanceTimeBy(2_800)
                canvas.assert(editing)
                compose.mainClock.advanceTimeBy(350)
                canvas.assert(preview)

                canvas.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(3_300)
                canvas.assert(editing)
                canvas.performTouchInput { up() }
                compose.mainClock.advanceTimeBy(2_800)
                canvas.assert(editing)
                compose.mainClock.advanceTimeBy(350)
                canvas.assert(preview)
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }

    private fun withSavedCrop(
        gesture: (TouchInjectionScope.() -> Unit)? = null,
        assertions: (Bitmap) -> Unit,
    ) = withCropDialog { savedPath ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (gesture != null) {
            compose.onNodeWithContentDescription(context.getString(R.string.avatar_crop_hint))
                .performTouchInput(gesture)
        }
        compose.onNodeWithText(context.getString(R.string.avatar_crop_apply)).performClick()
        compose.waitUntil(5_000) { savedPath.get() != null }

        val result = checkNotNull(BitmapFactory.decodeFile(savedPath.get()))
        try {
            assertEquals(512, result.width)
            assertEquals(512, result.height)
            assertions(result)
        } finally {
            result.recycle()
        }
    }

    private fun withCropDialog(
        assertions: (AtomicReference<String>) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File.createTempFile("crop-test", ".png", context.cacheDir)
        val savedPath = AtomicReference<String>()
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        Canvas(source).apply {
            drawRect(0f, 0f, 100f, 100f, Paint().apply { color = Color.RED })
            drawRect(90f, 40f, 110f, 60f, Paint().apply { color = Color.GREEN })
        }
        photo.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()

        try {
            compose.setContent {
                MyPasswordsTheme {
                    AvatarCropDialog(Uri.fromFile(photo), onDismiss = {}, onCropped = savedPath::set)
                }
            }

            val apply = context.getString(R.string.avatar_crop_apply)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(apply) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            assertions(savedPath)
        } finally {
            photo.delete()
            savedPath.get()?.let { File(it).delete() }
        }
    }
}
