package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.drawable.GradientDrawable
import android.view.View
import java.util.WeakHashMap

/**
 * 悬浮卡公共绘制: 圆角 + 阴影 + 暗色浮层, 悬浮标题栏 / 悬浮输入框共用。
 *
 * 暗色 (深色外观) 下浮层仍是深色, 卡片会融进深色背景里看不清边界, 这里补一层
 * 半透明白色描边 + 半透明白色填充, 让卡片在深色背景下也能显出轮廓。
 * 深浅色切换时恢复原背景重画, 只影响当前已应用过的 View。
 */
internal object FloatingChatCardVisuals {

    private const val DARK_SURFACE_COLOR = 0x33FFFFFF
    private const val DARK_STROKE_COLOR = 0x4DFFFFFF

    private val originalBackgrounds = WeakHashMap<View, Any?>()
    private val appliedBackgrounds = WeakHashMap<View, GradientDrawable>()
    private val appliedStyles = WeakHashMap<View, Pair<Int, Boolean>>()

    /**
     * 深浅色主题切换时按目标色深补色: 深色补白描边/填充, 浅色恢复原背景, 保持新旧主题一致。
     */
    fun applyDarkSurface(view: View, cornerRadiusDp: Int) {
        val dark = isDarkMode(view)
        val style = cornerRadiusDp to dark
        if (appliedStyles[view] == style) return
        if (!dark) {
            restoreOriginalBackground(view)
            return
        }
        originalBackgrounds.putIfAbsent(view, view.background)
        val radiusPx = cornerRadiusDp * view.resources.displayMetrics.density
        val strokeWidthPx = 1 * view.resources.displayMetrics.density
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(DARK_SURFACE_COLOR)
            setStroke(strokeWidthPx, DARK_STROKE_COLOR)
        }
        view.background = background
        appliedBackgrounds[view] = background
        appliedStyles[view] = style
    }

    private fun restoreOriginalBackground(view: View) {
        if (!originalBackgrounds.containsKey(view)) return
        val appliedBackground = appliedBackgrounds[view]
        if (view.background === appliedBackground) {
            view.background = originalBackgrounds[view]
        }
        originalBackgrounds.remove(view)
        appliedBackgrounds.remove(view)
        appliedStyles.remove(view)
    }
}
