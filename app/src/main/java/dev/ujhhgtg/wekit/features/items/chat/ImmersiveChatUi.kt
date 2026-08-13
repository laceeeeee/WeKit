package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ListView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import kotlin.math.max

@Feature(
    name = "聊天界面沉浸",
    categories = ["聊天"],
    description = "聊天界面启用 edge-to-edge: 内容延伸到状态栏背后, 消息可滚动到状态栏下方",
)
object ImmersiveChatUi {

    private const val TAG = "ImmersiveChatUi"

    /** 每个聊天页布局最近一次观察到的状态栏 inset。 */
    private val statusBarOffsets = WeakHashMap<View, Int>()

    /** 已应用 edge-to-edge 的窗口, 防止重复应用。 */
    private val edgeToEdgeApplied = WeakHashMap<android.view.Window, Boolean>()

    /** 已注册的 pre-draw 监听, 避免重复注册。 */
    private val offsetPreDraws = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    /** 已中和的 EdgeToEdgeWrapperLayout (四边 padding 归零 + 色块压透明)。 */
    private val wrapperStripsNeutralized = WeakHashMap<View, Boolean>()

    /** 已应用过 ConvBox 列表布局修复的窗口。 */
    private val convBoxWindows = WeakHashMap<android.view.Window, Boolean>()

    /** 设置 ConvBox 状态栏颜色时的递归保护。 */
    private var settingConvBoxColor = false

    /** 每个 ConvBox 页面根的收敛状态。 */
    private val convBoxFixStates = WeakHashMap<View, ConvBoxFixState>()

    private class ConvBoxFixState {
        var titleBar: View? = null
        var list: View? = null
        var toolbar: View? = null
        var wrapper: View? = null
        var color: Int = Color.WHITE
        var inset: Int = 0
        var applied: Boolean = false
        var finished: Boolean = false
        var lastTitleBottom: Int = -1
        var lastListTop: Int = -1
        var stableFrames: Int = 0
        var titleBarMissingWarned: Boolean = false
    }

    /** 悬浮标题栏等特性读取当前状态栏偏移的入口。 */
    fun statusBarOffset(layout: View): Int {
        return statusBarOffsets[layout] ?: currentStatusBarOffset(layout)
    }

    private fun currentStatusBarOffset(layout: View): Int {
        val activity = layout.context.activityOrNull() ?: return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        } else {
            val view = activity.window.decorView
            WindowInsetsCompat.toWindowInsetsCompat(view.rootWindowInsets).getInsets(
                WindowInsetsCompat.Type.statusBars()
            ).top
        }
    }

    override fun onEnable() {
        // 每个聊天页布局挂 pre-draw: 每帧刷新状态栏偏移, 供悬浮标题栏/列表 padding 使用。
        ChattingUILayout::class.reflekt().firstConstructorOrNull {
            parameters(Context::class, AttributeSet::class)
        }?.hookAfter {
            val layout = thisObject as? ChattingUILayout ?: return@hookAfter
            trackStatusBarOffset(layout)
            neutralizeChatWrapper(layout)
            layout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    // 打开任意聊天页时对整个聊天页应用 edge-to-edge
                    val activity = layout.context.activityOrNull() ?: return
                    applyEdgeToEdge(activity)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            })
        } ?: WeLogger.w(TAG, "ChattingUILayout constructor hook target not found")

        // 消息列表滚到顶时 ChattingContent 的滚动容器会收到 focus 事件 (触摸列表或
        // ChatFooter 上移消息), 此时才把窗口切到全屏。半屏通知进入时不会 focus, 保持半屏。
        "com.tencent.mm.pluginsdk.ui.chat.ChattingContent".toClass().reflekt().firstMethodOrNull {
            name = "requestFocus"
        }?.hookAfter {
            val content = thisObject as? View ?: return@hookAfter
            content.findAncestorChattingUILayout()?.let { layout ->
                val activity = layout.context.activityOrNull() ?: return@hookAfter
                applyEdgeToEdge(activity)
            }
        } ?: WeLogger.w(TAG, "ChattingContent.requestFocus hook target not found")

        // 聊天页从通知进入时是半屏窗口, 状态栏 inset 不完整, 需要全屏后重新布局。
        "com.tencent.mm.ui.chatting.ChattingUI".toClass().reflekt().firstMethodOrNull {
            name = "onWindowFocusChanged"
        }?.hookAfter {
            val activity = thisObject as? Activity ?: return@hookAfter
            if (activity.isFinishing || activity.isDestroyed) return@hookAfter
            applyEdgeToEdge(activity)
        } ?: WeLogger.w(TAG, "ChattingUI.onWindowFocusChanged hook target not found")
    }

    private fun applyEdgeToEdge(activity: Activity) {
        val window = activity.window ?: return
        if (edgeToEdgeApplied[window] == true) {
            // 已应用过: 只需重新压下状态栏透明 (微信可能又改了颜色)
            runCatching {
                if (window.statusBarColor != Color.TRANSPARENT) {
                    window.statusBarColor = Color.TRANSPARENT
                }
            }
            return
        }
        edgeToEdgeApplied[window] = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        runCatching { window.statusBarColor = Color.TRANSPARENT }
        WeLogger.d(TAG, "edge-to-edge applied to ${activity.javaClass.simpleName}")
    }

    private fun neutralizeChatWrapper(layout: View) {
        val wrapper = layout.findEdgeToEdgeWrapper() ?: return
        if (wrapper.paddingTop != 0 || wrapper.paddingBottom != 0) {
            wrapper.setPadding(wrapper.paddingLeft, 0, wrapper.paddingRight, 0)
        }
        if (wrapperStripsNeutralized[wrapper] != null) return
        runCatching {
            wrapper.javaClass.getMethod(
                "setNavigationBarBackgroundColor",
                Int::class.javaPrimitiveType
            ).invoke(wrapper, Color.TRANSPARENT)
            wrapper.javaClass.getMethod("setStatusBarColor", Int::class.javaPrimitiveType)
                .invoke(wrapper, Color.TRANSPARENT)
        }
        wrapperStripsNeutralized[wrapper] = true
    }

    private fun View.findEdgeToEdgeWrapper(): View? {
        var current: View? = this
        while (current != null) {
            if (current.javaClass.name == "com.tencent.mm.ui.widget.EdgeToEdgeWrapperLayout") {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun View.findAncestorChattingUILayout(): ChattingUILayout? {
        var parent = parent
        while (parent != null) {
            if (parent is ChattingUILayout) return parent
            parent = parent.parent
        }
        return null
    }

    private fun trackStatusBarOffset(layout: View) {
        val old = offsetPreDraws.remove(layout)
        old?.let { listener ->
            runCatching { layout.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            statusBarOffsets[layout] = currentStatusBarOffset(layout)
            reassertEdgeToEdgeStatusBar(layout)
            neutralizeChatWrapper(layout)
            true
        }
        offsetPreDraws[layout] = listener
        layout.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun reassertEdgeToEdgeStatusBar(layout: View) {
        val activity = layout.context.activityOrNull() ?: return
        val window = activity.window
        if (edgeToEdgeApplied[window] != true) return
        runCatching {
            if (window.statusBarColor != Color.TRANSPARENT) {
                window.statusBarColor = Color.TRANSPARENT
            }
        }
    }

    private tailrec fun Context.activityOrNull(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }
}
