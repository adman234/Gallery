@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package org.fossify.gallery.helpers

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The video views are TextureViews, which can only show SDR. HDR video (HLG, HDR10) sent to them unchanged comes out
 * dark or washed out, so ask the decoder to tone-map it to SDR. Decoders that can't do that just ignore the request.
 */
class SdrToneMappingRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)

        out.add(SdrToneMappingVideoRenderer(builder))
    }

    private class SdrToneMappingVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
        override fun getMediaFormat(
            format: Format,
            codecMimeType: String,
            codecMaxValues: MediaCodecVideoRenderer.CodecMaxValues,
            codecOperatingRate: Float,
            deviceNeedsNoPostProcessWorkaround: Boolean,
            tunnelingAudioSessionId: Int
        ): MediaFormat {
            val mediaFormat = super.getMediaFormat(
                format,
                codecMimeType,
                codecMaxValues,
                codecOperatingRate,
                deviceNeedsNoPostProcessWorkaround,
                tunnelingAudioSessionId
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ColorInfo.isTransferHdr(format.colorInfo)) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }

            return mediaFormat
        }
    }
}
