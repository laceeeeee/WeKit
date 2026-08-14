package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Shared visual treatment for floating chat cards.
 *
 * By default light mode keeps WeChat's own backgrounds intact; dark mode needs an explicit
 * surface and a hairline border because Android elevation is barely visible on near-black chat
 * backgrounds. A custom surface color overrides both modes and may carry its own alpha (e.g. a
 * translucent #AARRGGBB for a frosted look); the hairline stroke color can be customized too.
 */
internal object FloatingChatCardVisuals {

    private const val DEFAULT_DARK_SURFACE_COLOR = 0xFF242424.toInt()
    private const val DEFAULT_STROKE_COLOR = 0x24FFFFFF
    private const val STROKE_WIDTH_DP = 1

    private data class AppliedStyle(
        val cornerRadiusDp: Int,
        val strokeWidthPx: Int,
        val surfaceColor: Int,
        val strokeColor: Int,
    )

    private val originalBackgrounds = WeakHashMap<View, Drawable?>()
    private val appliedBackgrounds = WeakHashMap<View, Drawable>()
    private val appliedStyles = WeakHashMap<View, AppliedStyle>()

    /**
     * Applies the floating card surface to [view].
     *
     * @param customSurfaceColor when null the default behavior applies (dark mode gets the
     *   built-in surface, light mode keeps WeChat's own background); when set, it is used in
     *   both modes and its alpha is honored.
     * @param customStrokeColor hairline border color; null uses the built-in one.
     * @param applyBuiltInDarkSurface when true (default) a null [customSurfaceColor] paints the
     *   built-in surface in dark mode, like the floating title bar; when false, a null
     *   [customSurfaceColor] leaves the view's own background untouched in both modes, like the
     *   floating input bar.
     */
    fun applyCardSurface(
        view: View,
        cornerRadiusDp: Int,
        customSurfaceColor: Int? = null,
        customStrokeColor: Int? = null,
        applyBuiltInDarkSurface: Boolean = true,
    ) {
        val surfaceColor = customSurfaceColor
            ?: if (applyBuiltInDarkSurface) DEFAULT_DARK_SURFACE_COLOR else null
        if (surfaceColor == null) {
            restoreOriginalBackground(view)
            return
        }

        if (!originalBackgrounds.containsKey(view)) {
            originalBackgrounds[view] = view.background
        }

        val density = view.resources.displayMetrics.density
        val strokeWidthPx = (STROKE_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
        val strokeColor = customStrokeColor ?: DEFAULT_STROKE_COLOR
        val style = AppliedStyle(cornerRadiusDp, strokeWidthPx, surfaceColor, strokeColor)
        val appliedBackground = appliedBackgrounds[view]
        if (appliedStyles[view] == style && view.background === appliedBackground) return

        val radiusPx = cornerRadiusDp * density
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(surfaceColor)
            setStroke(strokeWidthPx, strokeColor)
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
