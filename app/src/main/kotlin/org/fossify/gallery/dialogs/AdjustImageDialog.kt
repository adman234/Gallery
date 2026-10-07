package org.fossify.gallery.dialogs

import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogAdjustImageBinding
import org.fossify.gallery.models.ImageAdjustments

/** Lets the user pick brightness, contrast and saturation, [onChanged] is called with every change for a live preview. */
class AdjustImageDialog(
    val activity: BaseSimpleActivity,
    val initial: ImageAdjustments,
    val onChanged: (adjustments: ImageAdjustments) -> Unit
) {
    private val binding = DialogAdjustImageBinding.inflate(activity.layoutInflater)
    private var wasConfirmed = false

    init {
        val textColor = activity.getProperTextColor()
        val primaryColor = activity.getProperPrimaryColor()
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                onChanged(getAdjustments())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        }

        binding.apply {
            arrayOf(adjustBrightness, adjustContrast, adjustSaturation).forEach {
                it.max = ImageAdjustments.MAX_VALUE * 2
                it.setColors(textColor, primaryColor, 0)
            }

            showValues(initial)
            arrayOf(adjustBrightness, adjustContrast, adjustSaturation).forEach {
                it.setOnSeekBarChangeListener(listener)
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> wasConfirmed = true }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .setNeutralButton(R.string.reset_adjustments, null)
            .setOnDismissListener {
                if (!wasConfirmed) {
                    onChanged(initial)
                }
            }
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.adjust) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        showValues(ImageAdjustments())
                    }
                }
            }
    }

    private fun showValues(adjustments: ImageAdjustments) {
        binding.apply {
            adjustBrightness.progress = adjustments.brightness + ImageAdjustments.MAX_VALUE
            adjustContrast.progress = adjustments.contrast + ImageAdjustments.MAX_VALUE
            adjustSaturation.progress = adjustments.saturation + ImageAdjustments.MAX_VALUE
        }
    }

    private fun getAdjustments() = ImageAdjustments(
        brightness = binding.adjustBrightness.progress - ImageAdjustments.MAX_VALUE,
        contrast = binding.adjustContrast.progress - ImageAdjustments.MAX_VALUE,
        saturation = binding.adjustSaturation.progress - ImageAdjustments.MAX_VALUE
    )
}
