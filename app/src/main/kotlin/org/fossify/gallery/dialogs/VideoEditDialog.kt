@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package org.fossify.gallery.dialogs

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.models.RadioItem
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogVideoEditBinding
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Trims a video, changes its speed and optionally removes its audio. The result is always saved as a new file. */
class VideoEditDialog(val activity: BaseSimpleActivity, val path: String, val callback: (newPath: String) -> Unit) {
    companion object {
        private const val SEEK_STEPS = 1000
        private const val MIN_LENGTH_STEPS = 5
        private const val PREVIEW_SIZE = 640
        private const val PROGRESS_INTERVAL = 300L
        private val SPEEDS = floatArrayOf(0.125f, 0.25f, 0.5f, 1f, 2f, 4f)
        private const val DEFAULT_SPEED_INDEX = 3
    }

    private val binding = DialogVideoEditBinding.inflate(activity.layoutInflater)
    private val retriever = MediaMetadataRetriever()
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private val previewRequest = AtomicInteger()
    private val progressHandler = Handler(Looper.getMainLooper())
    private var durationMs = 0L
    private var speedIndex = DEFAULT_SPEED_INDEX
    private var transformer: Transformer? = null
    private var outputFile: File? = null
    private var dialog: AlertDialog? = null

    init {
        try {
            retriever.setDataSource(path)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (ignored: Exception) {
        }

        if (durationMs <= 0L) {
            activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
        } else {
            setupViews()
            activity.getAlertDialogBuilder()
                .setPositiveButton(org.fossify.commons.R.string.save, null)
                .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                .setOnDismissListener { cleanup() }
                .apply {
                    activity.setupDialogStuff(binding.root, this, R.string.edit_video, cancelOnTouchOutside = false) { alertDialog ->
                        dialog = alertDialog
                        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            export()
                        }

                        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                            cancelExport()
                            alertDialog.dismiss()
                        }
                    }
                }
        }
    }

    private fun setupViews() {
        val textColor = activity.getProperTextColor()
        val primaryColor = activity.getProperPrimaryColor()
        binding.apply {
            arrayOf(videoEditStart, videoEditEnd).forEach {
                it.max = SEEK_STEPS
                it.setColors(textColor, primaryColor, 0)
            }

            videoEditStart.progress = 0
            videoEditEnd.progress = SEEK_STEPS
            videoEditStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (progress > videoEditEnd.progress - MIN_LENGTH_STEPS) {
                        videoEditEnd.progress = (progress + MIN_LENGTH_STEPS).coerceAtMost(SEEK_STEPS)
                        if (progress > SEEK_STEPS - MIN_LENGTH_STEPS) {
                            seekBar.progress = SEEK_STEPS - MIN_LENGTH_STEPS
                        }
                    }

                    updateLabels()
                    if (fromUser) {
                        showPreview(getStartMs())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })

            videoEditEnd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (progress < videoEditStart.progress + MIN_LENGTH_STEPS) {
                        videoEditStart.progress = (progress - MIN_LENGTH_STEPS).coerceAtLeast(0)
                        if (progress < MIN_LENGTH_STEPS) {
                            seekBar.progress = MIN_LENGTH_STEPS
                        }
                    }

                    updateLabels()
                    if (fromUser) {
                        showPreview(getEndMs())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })

            videoEditSpeedHolder.setOnClickListener {
                val items = ArrayList<RadioItem>()
                SPEEDS.forEachIndexed { index, speed ->
                    items.add(RadioItem(index, getSpeedText(speed)))
                }

                RadioGroupDialog(activity, items, speedIndex) {
                    speedIndex = it as Int
                    updateLabels()
                }
            }
        }

        updateLabels()
        showPreview(0L)
    }

    private fun getStartMs() = durationMs * binding.videoEditStart.progress / SEEK_STEPS

    private fun getEndMs() = durationMs * binding.videoEditEnd.progress / SEEK_STEPS

    private fun getSpeedText(speed: Float): String {
        return if (speed < 1f) {
            "1/${(1 / speed).roundToInt()}x"
        } else {
            "${speed.roundToInt()}x"
        }
    }

    private fun formatMs(ms: Long) = (ms / 1000.0).roundToInt().getFormattedDuration()

    private fun updateLabels() {
        val speed = SPEEDS[speedIndex]
        val resultMs = ((getEndMs() - getStartMs()) / speed).toLong()
        binding.apply {
            videoEditStartLabel.text = "${activity.getString(R.string.video_start)}: ${formatMs(getStartMs())}"
            videoEditEndLabel.text = "${activity.getString(R.string.video_end)}: ${formatMs(getEndMs())}"
            videoEditSpeedValue.text = getSpeedText(speed)
            videoEditResultLabel.text = "${activity.getString(R.string.resulting_length)}: ${formatMs(resultMs)}"
        }
    }

    // only the newest request is decoded, dragging a slider creates a lot of them
    private fun showPreview(positionMs: Long) {
        val request = previewRequest.incrementAndGet()
        try {
            previewExecutor.execute {
                if (request != previewRequest.get()) {
                    return@execute
                }

                val frame: Bitmap? = try {
                    retriever.getScaledFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW_SIZE, PREVIEW_SIZE)
                } catch (ignored: Throwable) {
                    null
                }

                if (frame != null) {
                    activity.runOnUiThread {
                        if (request == previewRequest.get()) {
                            binding.videoEditPreview.setImageBitmap(frame)
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun getOutputFile(): File {
        val parent = path.getParentPath()
        val baseName = path.getFilenameFromPath().substringBeforeLast('.')
        var file = File(parent, "${baseName}_edited.mp4")
        var index = 1
        while (file.exists()) {
            file = File(parent, "${baseName}_edited_$index.mp4")
            index++
        }
        return file
    }

    private fun export() {
        if (transformer != null) {
            return
        }

        val startMs = getStartMs()
        val endMs = getEndMs()
        val speed = SPEEDS[speedIndex]
        val isTrimmed = binding.videoEditStart.progress > 0 || binding.videoEditEnd.progress < SEEK_STEPS
        val removeAudio = binding.videoEditMute.isChecked
        if (!isTrimmed && speed == 1f && !removeAudio) {
            dialog?.dismiss()
            return
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.fromFile(File(path)))
        if (isTrimmed) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(if (binding.videoEditEnd.progress < SEEK_STEPS) endMs else C.TIME_END_OF_SOURCE)
                    .build()
            )
        }

        val editedItemBuilder = EditedMediaItem.Builder(mediaItemBuilder.build()).setRemoveAudio(removeAudio)
        if (speed != 1f) {
            editedItemBuilder.setSpeed(object : SpeedProvider {
                override fun getSpeed(timeUs: Long) = speed

                override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
            })
        }

        val destination = getOutputFile()
        outputFile = destination
        try {
            val newTransformer = Transformer.Builder(activity.applicationContext)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        exportFinished(destination)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        exportFailed(exportException)
                    }
                })
                .build()

            transformer = newTransformer
            setEditingEnabled(false)
            binding.videoEditProgress.progress = 0
            binding.videoEditProgress.beVisible()
            newTransformer.start(editedItemBuilder.build(), destination.absolutePath)
            pollProgress()
        } catch (e: Exception) {
            exportFailed(e)
        }
    }

    private fun pollProgress() {
        val currentTransformer = transformer ?: return
        val holder = ProgressHolder()
        if (currentTransformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            binding.videoEditProgress.progress = holder.progress
        }

        progressHandler.postDelayed({ pollProgress() }, PROGRESS_INTERVAL)
    }

    private fun setEditingEnabled(enabled: Boolean) {
        binding.apply {
            arrayOf(videoEditStart, videoEditEnd, videoEditSpeedHolder, videoEditMute).forEach {
                it.isEnabled = enabled
            }
        }

        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled
    }

    private fun exportFinished(destination: File) {
        progressHandler.removeCallbacksAndMessages(null)
        transformer = null
        outputFile = null
        activity.rescanPaths(arrayListOf(destination.absolutePath)) {
            activity.runOnUiThread {
                activity.toast(org.fossify.commons.R.string.file_saved)
                callback(destination.absolutePath)
            }
        }

        dialog?.dismiss()
    }

    private fun exportFailed(exception: Exception) {
        progressHandler.removeCallbacksAndMessages(null)
        transformer = null
        outputFile?.delete()
        outputFile = null
        binding.videoEditProgress.beGone()
        setEditingEnabled(true)
        activity.showErrorToast(exception)
    }

    private fun cancelExport() {
        progressHandler.removeCallbacksAndMessages(null)
        val currentTransformer = transformer ?: return
        transformer = null
        try {
            currentTransformer.cancel()
        } catch (ignored: Exception) {
        }

        outputFile?.delete()
        outputFile = null
    }

    private fun cleanup() {
        cancelExport()
        previewRequest.incrementAndGet()
        previewExecutor.execute {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
        previewExecutor.shutdown()
    }
}
