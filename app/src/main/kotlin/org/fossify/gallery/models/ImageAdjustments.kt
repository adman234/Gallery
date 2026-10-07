package org.fossify.gallery.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/** Editor adjustments, every value goes from -[MAX_VALUE] to [MAX_VALUE] with 0 meaning unchanged. */
data class ImageAdjustments(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0
) {
    companion object {
        const val MAX_VALUE = 100
        private const val MAX_BRIGHTNESS_SHIFT = 100f
        private const val MAX_CONTRAST_CHANGE = 0.8f
        private const val MIDDLE_GRAY = 127.5f
    }

    fun isNeutral() = brightness == 0 && contrast == 0 && saturation == 0

    fun getColorFilter(): ColorMatrixColorFilter? {
        if (isNeutral()) {
            return null
        }

        val matrix = ColorMatrix()
        matrix.setSaturation(1f + saturation / MAX_VALUE.toFloat())

        val scale = 1f + contrast / MAX_VALUE.toFloat() * MAX_CONTRAST_CHANGE
        val shift = (1f - scale) * MIDDLE_GRAY + brightness / MAX_VALUE.toFloat() * MAX_BRIGHTNESS_SHIFT
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, shift,
                    0f, scale, 0f, 0f, shift,
                    0f, 0f, scale, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        return ColorMatrixColorFilter(matrix)
    }

    /** Returns a new bitmap with the adjustments applied, or [bitmap] itself when there is nothing to do. */
    fun applyTo(bitmap: Bitmap): Bitmap {
        val colorFilter = getColorFilter() ?: return bitmap
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = colorFilter
        Canvas(result).drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }
}
