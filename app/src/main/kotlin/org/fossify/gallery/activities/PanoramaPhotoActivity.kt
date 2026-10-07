package org.fossify.gallery.activities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.apache.sanselan.common.byteSources.ByteSourceInputStream
import org.apache.sanselan.formats.jpeg.JpegImageParser
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.views.PanoramaView
import java.io.File
import java.io.InputStream

/** Fullscreen viewer for 360° photos and wide panoramas. */
class PanoramaPhotoActivity : SimpleActivity() {
    companion object {
        // big enough to stay sharp when zoomed in, small enough for every GPU's texture limit
        private const val MAX_TEXTURE_SIZE = 4096
    }

    private var panoramaView: PanoramaView? = null
    private var isFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val holder = FrameLayout(this)
        holder.setBackgroundColor(android.graphics.Color.BLACK)
        setContentView(holder)

        val path = intent.getStringExtra(PATH)
        if (path.isNullOrEmpty()) {
            finish()
            return
        }

        ensureBackgroundThread {
            val bitmap = try {
                loadBitmap(path)
            } catch (ignored: Exception) {
                null
            } catch (ignored: OutOfMemoryError) {
                null
            }

            val area = getPanoramaArea(path)
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                if (bitmap == null) {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                    finish()
                    return@runOnUiThread
                }

                val view = PanoramaView(this, bitmap, area) {
                    toggleFullscreen()
                }

                panoramaView = view
                holder.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                toggleFullscreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        panoramaView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        panoramaView?.onPause()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView as View)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun openStream(path: String): InputStream? {
        return if (path.startsWith("content:/")) {
            contentResolver.openInputStream(Uri.parse(path))
        } else {
            File(path).inputStream()
        }
    }

    private fun loadBitmap(path: String): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        openStream(path)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        var sampleSize = 1
        while (options.outWidth / sampleSize > MAX_TEXTURE_SIZE || options.outHeight / sampleSize > MAX_TEXTURE_SIZE) {
            sampleSize *= 2
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        return openStream(path)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    // a panorama can cover just a part of the sphere, its metadata says which one
    private fun getPanoramaArea(path: String): PanoramaView.PanoramaArea {
        try {
            val xmp = openStream(path)?.use {
                JpegImageParser().getXmpXml(ByteSourceInputStream(it, path.getFilenameFromPath()), HashMap<String, Any>())
            } ?: return PanoramaView.PanoramaArea()

            val fullWidth = getXmpNumber(xmp, "FullPanoWidthPixels")
            val fullHeight = getXmpNumber(xmp, "FullPanoHeightPixels")
            val croppedWidth = getXmpNumber(xmp, "CroppedAreaImageWidthPixels")
            val croppedHeight = getXmpNumber(xmp, "CroppedAreaImageHeightPixels")
            val left = getXmpNumber(xmp, "CroppedAreaLeftPixels")
            val top = getXmpNumber(xmp, "CroppedAreaTopPixels")
            if (fullWidth == null || fullHeight == null || croppedWidth == null || croppedHeight == null || left == null || top == null) {
                return PanoramaView.PanoramaArea()
            }

            if (fullWidth <= 0f || fullHeight <= 0f || croppedWidth <= 0f || croppedHeight <= 0f) {
                return PanoramaView.PanoramaArea()
            }

            return PanoramaView.PanoramaArea(
                left = (left / fullWidth).coerceIn(0f, 1f),
                top = (top / fullHeight).coerceIn(0f, 1f),
                right = ((left + croppedWidth) / fullWidth).coerceIn(0f, 1f),
                bottom = ((top + croppedHeight) / fullHeight).coerceIn(0f, 1f)
            )
        } catch (ignored: Exception) {
            return PanoramaView.PanoramaArea()
        } catch (ignored: OutOfMemoryError) {
            return PanoramaView.PanoramaArea()
        }
    }

    // the values are written either as attributes or as elements
    private fun getXmpNumber(xmp: String, name: String): Float? {
        val match = Regex("GPano:$name(?:=\"|>)(\\d+)").find(xmp) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }
}
