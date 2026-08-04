package me.rerere.rikkahub.ui.components.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private val MAX_HEIGHT = 10000.dp
private val MAX_WIDTH = 10000.dp

val LocalExportContext = staticCompositionLocalOf { false }

/**
 * Draws an arbitrary composable into a bitmap
 * mainScope has to be Dispatcher.Main because it has to perform
 * layout and measurement calculations on the UI thread.
 */
class BitmapComposer(private val mainScope: CoroutineScope) {
    /**
     * Renders an arbitrary Composable View into a Bitmap.
     *
     * @param activity The host activity that is needed to attach the composable content to the view hierarchy.
     * @param width Optional width of the bitmap in device-independent pixels. Try to provide
     * a width or height value for better results.
     * @param height Optional height of the bitmap in device-independent pixels. Try to provide
     * a width or height value for better results.
     * @param screenDensity screen density to interpret the width and height.
     * @param content An arbitrary composable content to render.
     * @return A Bitmap representing the rendered Composable content.
     */
    suspend fun composableToBitmap(
        activity: Activity,
        width: Dp? = null,
        height: Dp? = null,
        screenDensity: Density,
        content: @Composable () -> Unit
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        // [FIX] 取消清理：导出协程被取消（用户离开页面/新导出）时，原实现既不移除
        // 挂在 decorView 上的 ComposeView（视图泄漏，Activity 重建后残留）也不 resume，
        // 调用方永久挂起。invokeOnCancellation 里在主线程移除容器并 resume 异常。
        val contentWidthInPixels = (screenDensity.density * (width ?: MAX_WIDTH).value).roundToInt()
        val contentHeightInPixels = (screenDensity.density * (height ?: MAX_HEIGHT).value).roundToInt()

        // Step 2: Create a container to hold the ComposeView temporarily
        val composeViewContainer = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(contentWidthInPixels, contentHeightInPixels)
            visibility = View.INVISIBLE // Keep it invisible
        }

        // Step 3: Create and configure the ComposeView using the activity
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalExportContext provides true) {
                    content()
                }
            }
        }

        // add the composable view to the container
        composeViewContainer.addView(composeView)

        // Step 4: Attach container to the root decor view
        val decorView = activity.window.decorView as ViewGroup
        decorView.addView(composeViewContainer) // since the container is invisible, we are OK.

        continuation.invokeOnCancellation {
            mainScope.launch {
                runCatching { decorView.removeView(composeViewContainer) }
                if (continuation.isActive) continuation.resumeWithException(CancellationException("Export cancelled"))
            }
        }

        mainScope.launch {
            // Step 5: Create measure specifications for the ComposeView
            // If width or height is not provided, use AT_MOST to let the content decide the height
            val widthMeasureSpecs = if (width == null) {
                View.MeasureSpec.AT_MOST // or View.MeasureSpec.UNSPECIFIED
                // UNSPECIFIED width may not work with horizontally scrollable content - use caution.
            } else {
                View.MeasureSpec.EXACTLY
            }

            val heightMeasureSpecs = if (height == null) {
                View.MeasureSpec.AT_MOST // or View.MeasureSpec.UNSPECIFIED
                // UNSPECIFIED height may not work with vertically scrollable content - use caution.
            } else {
                View.MeasureSpec.EXACTLY
            }

            // Step 6: Wait for the ComposeView to be drawn and capture the bitmap
            Handler(Looper.getMainLooper()).post {
                // ask for the container view to measure itself
                composeViewContainer.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        contentWidthInPixels,
                        widthMeasureSpecs
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                        contentHeightInPixels,
                        heightMeasureSpecs
                    )
                )

                // now request a layout at origin
                composeViewContainer.layout(0, 0, contentWidthInPixels, contentHeightInPixels)

                // Wait for async components to complete rendering before capturing bitmap
                Handler(Looper.getMainLooper()).postDelayed({
                    // Re-measure after async components have loaded to get proper height
                    composeViewContainer.measure(
                        View.MeasureSpec.makeMeasureSpec(
                            contentWidthInPixels,
                            widthMeasureSpecs
                        ),
                        View.MeasureSpec.makeMeasureSpec(
                            contentHeightInPixels,
                            heightMeasureSpecs
                        )
                    )

                    // Re-layout with the actual measured dimensions
                    val actualWidth = composeViewContainer.measuredWidth
                    val actualHeight = composeViewContainer.measuredHeight
                    composeViewContainer.layout(0, 0, actualWidth, actualHeight)

                    val bitmap = composeView.drawToBitmap() // layout finished, draw to bitmap
                    if (continuation.isActive) continuation.resume(bitmap) // notify the caller with the bitmap

                    // Step 7: Clean up - remove the container
                    runCatching { decorView.removeView(composeViewContainer) }
                }, 100) // delay to allow ComposeView to finish rendering
            }
        }
    }
}