package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.GesturePoint

internal data class ShortcutMenuBounds(val left: Float, val top: Float, val width: Float, val rowHeight: Float, val count: Int) {
    val right get() = left + width
    val bottom get() = top + rowHeight * count

    fun rowAt(x: Float, y: Float): Int? =
        if (x >= left && x <= right && y >= top && y < bottom) ((y - top) / rowHeight).toInt() else null
}

internal object ShortcutMenuGeometry {
    fun place(
        icon: GesturePoint,
        side: CornerSide,
        screenWidth: Float,
        screenHeight: Float,
        density: Float,
        count: Int,
        iconDiameter: Float,
        safeInsets: OverlaySafeInsets = OverlaySafeInsets(),
    ): ShortcutMenuBounds {
        require(count > 0)
        val margin = 12f * density
        val minX = safeInsets.left + margin
        val maxX = screenWidth - safeInsets.right - margin
        val minY = safeInsets.top + margin
        val maxY = screenHeight - safeInsets.bottom - margin
        val width = minOf(184f * density, (maxX - minX).coerceAtLeast(1f))
        val rowHeight = minOf(48f * density, (maxY - minY) / count).coerceAtLeast(1f)
        val gap = 10f * density
        val leftOfIcon = icon.x - iconDiameter / 2f - gap - width
        val rightOfIcon = icon.x + iconDiameter / 2f + gap
        val leftFits = leftOfIcon >= minX
        val rightFits = rightOfIcon + width <= maxX
        val preferredLeft = when {
            side == CornerSide.Left && rightFits -> rightOfIcon
            side == CornerSide.Right && leftFits -> leftOfIcon
            leftFits -> leftOfIcon
            rightFits -> rightOfIcon
            else -> if (icon.x < screenWidth / 2f) rightOfIcon else leftOfIcon
        }
        val left = preferredLeft.coerceIn(minX, (maxX - width).coerceAtLeast(minX))
        val height = rowHeight * count
        val top = (icon.y - height / 2f).coerceIn(minY, (maxY - height).coerceAtLeast(minY))
        return ShortcutMenuBounds(left, top, width, rowHeight, count)
    }
}
