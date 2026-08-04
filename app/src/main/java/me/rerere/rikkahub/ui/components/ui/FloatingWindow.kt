package me.rerere.rikkahub.ui.components.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.listener.control.IFxAppControl
import me.rerere.rikkahub.ui.theme.RikkahubTheme

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var window: IFxAppControl? by remember { mutableStateOf(null) }

    // [FIX] 悬浮窗权限缺失时 show() → WindowManager.addView SecurityException → 崩溃
    //（TTS 朗读场景：未授权 SYSTEM_ALERT_WINDOW 直接崩溃）。无权限则静默降级不显示。
    fun canShowOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    LaunchedEffect(visibility) {
        if (visibility) {
            if (canShowOverlay()) {
                window?.show()
            }
        } else {
            window?.hide()
        }
    }

    DisposableEffect(context) {
        window = FloatingX.install {
            setTag(tag)
            setContext(context)
            setGravity(FxGravity.LEFT_OR_BOTTOM)
            setOffsetXY(20f, -20f)
            setEnableAnimation(true)
            setLayoutView(ComposeView(context).apply {
                setContent {
                    RikkahubTheme {
                        content()
                    }
                }
            })
        }
        if (visibility && canShowOverlay()) window?.show() else window?.hide()
        onDispose {
            window?.cancel()
        }
    }
}
