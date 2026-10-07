package org.fossify.gallery.helpers

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import org.apache.sanselan.common.byteSources.ByteSourceInputStream
import org.apache.sanselan.formats.jpeg.JpegImageParser
import java.io.File
import java.io.RandomAccessFile

data class MotionPhotoInfo(
    val videoOffsetFromStart: Long,
    val videoLength: Long
)

object MotionPhotoHelper {

    private const val SCAN_RANGE = 5L * 1024 * 1024
    private const val MIN_FILE_SIZE = 12L
    private const val MIN_BOX_SIZE = 8
    private const val MAX_BOX_SIZE = 64

    private val FTYP_MARKER = "ftyp".toByteArray(Charsets.US_ASCII)
    private val MICRO_VIDEO_OFFSET_REGEX = Regex("MicroVideoOffset(?:=\"|>)(\\d+)")
    private val ITEM_LENGTH_REGEX = Regex("Item:Length=\"(\\d+)\"")

    fun detectMotionPhoto(context: Context, path: String, name: String): MotionPhotoInfo? {
        if (!name.endsWith(".jpg", true) && !name.endsWith(".jpeg", true)) {
            return null
        }

        val xmpXml = try {
            val inputStream = if (path.startsWith("content:/")) {
                context.contentResolver.openInputStream(path.toUri())
            } else {
                File(path).inputStream()
            }
            inputStream?.use {
                JpegImageParser().getXmpXml(ByteSourceInputStream(it, name), HashMap<String, Any>())
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }

        if (xmpXml == null) return null

        // MotionPhoto is the current format, MicroVideo the legacy one used by older Pixels and some other vendors
        val isMotionPhoto = xmpXml.contains("GCamera:MotionPhoto=\"1\"", true) ||
            xmpXml.contains("<GCamera:MotionPhoto>1</GCamera:MotionPhoto>", true) ||
            xmpXml.contains("GCamera:MicroVideo=\"1\"", true) ||
            xmpXml.contains("<GCamera:MicroVideo>1</GCamera:MicroVideo>", true)

        if (!isMotionPhoto) return null

        return findVideoFromXmpLength(context, path, xmpXml) ?: findVideoOffset(context, path)
    }

    fun isMotionPhotoName(name: String): Boolean {
        return name.contains(".MP.", false) || name.startsWith("MVIMG_", true)
    }

    // the metadata says how long the appended video is, which avoids scanning and works for videos of any size
    private fun findVideoFromXmpLength(context: Context, path: String, xmpXml: String): MotionPhotoInfo? {
        val videoLength = getXmpVideoLength(xmpXml) ?: return null
        return try {
            val fileSize = getFileSize(context, path)
            val offset = fileSize - videoLength
            if (videoLength < MIN_FILE_SIZE || offset <= 0) {
                return null
            }

            val header = readBytes(context, path, offset, MIN_FILE_SIZE.toInt()) ?: return null
            if (matchesFtypMarker(header, FTYP_MARKER.size)) {
                MotionPhotoInfo(videoOffsetFromStart = offset, videoLength = videoLength)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getXmpVideoLength(xmpXml: String): Long? {
        MICRO_VIDEO_OFFSET_REGEX.find(xmpXml)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
            return it
        }

        val semanticIndex = xmpXml.indexOf("Semantic=\"MotionPhoto\"")
        if (semanticIndex == -1) {
            return null
        }

        val elementStart = xmpXml.lastIndexOf('<', semanticIndex)
        val elementEnd = xmpXml.indexOf('>', semanticIndex)
        if (elementStart == -1 || elementEnd == -1) {
            return null
        }

        val element = xmpXml.substring(elementStart, elementEnd)
        return ITEM_LENGTH_REGEX.find(element)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun getFileSize(context: Context, path: String): Long {
        return if (path.startsWith("content:/")) {
            context.contentResolver.openFileDescriptor(path.toUri(), "r")?.use { it.statSize } ?: 0L
        } else {
            File(path).length()
        }
    }

    private fun readBytes(context: Context, path: String, offset: Long, length: Int): ByteArray? {
        return if (path.startsWith("content:/")) {
            readBytesFromUri(context, path.toUri(), offset, length)
        } else {
            RandomAccessFile(File(path), "r").use { raf ->
                raf.seek(offset)
                ByteArray(length).also { raf.readFully(it) }
            }
        }
    }

    /** Copies the embedded video into its own file. Returns true on success. */
    fun exportVideo(context: Context, path: String, info: MotionPhotoInfo, destination: File): Boolean {
        return try {
            val input = if (path.startsWith("content:/")) {
                context.contentResolver.openInputStream(path.toUri())
            } else {
                File(path).inputStream()
            } ?: return false

            input.use { stream ->
                var toSkip = info.videoOffsetFromStart
                while (toSkip > 0) {
                    val skipped = stream.skip(toSkip)
                    if (skipped <= 0) {
                        return false
                    }
                    toSkip -= skipped
                }

                destination.outputStream().use { output ->
                    stream.copyTo(output)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun findVideoOffset(context: Context, path: String): MotionPhotoInfo? {
        return if (path.startsWith("content:/")) {
            findVideoOffsetFromContentUri(context, path)
        } else {
            findVideoOffsetFromFile(path)
        }
    }

    private fun findVideoOffsetFromFile(path: String): MotionPhotoInfo? {
        val file = File(path)
        val fileSize = file.length()
        if (fileSize < MIN_FILE_SIZE) return null

        val scanStart = maxOf(0L, fileSize - SCAN_RANGE)
        val scanLength = (fileSize - scanStart).toInt()

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(scanStart)
            val buffer = ByteArray(scanLength)
            raf.readFully(buffer)
            val relativeOffset = findFtypOffset(buffer) ?: return null
            val absoluteOffset = scanStart + relativeOffset
            return MotionPhotoInfo(
                videoOffsetFromStart = absoluteOffset,
                videoLength = fileSize - absoluteOffset
            )
        }
    }

    private fun findVideoOffsetFromContentUri(context: Context, path: String): MotionPhotoInfo? {
        val uri = path.toUri()
        val fileSize = context.contentResolver.openFileDescriptor(uri, "r")
            ?.use { it.statSize }
            ?.takeIf { it >= MIN_FILE_SIZE }
            ?: return null
        val scanStart = maxOf(0L, fileSize - SCAN_RANGE)
        val scanLength = (fileSize - scanStart).toInt()
        val buffer = readBytesFromUri(context, uri, scanStart, scanLength) ?: return null
        val relativeOffset = findFtypOffset(buffer) ?: return null
        val absoluteOffset = scanStart + relativeOffset
        return MotionPhotoInfo(
            videoOffsetFromStart = absoluteOffset,
            videoLength = fileSize - absoluteOffset
        )
    }

    private fun readBytesFromUri(context: Context, uri: Uri, offset: Long, length: Int): ByteArray? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        return inputStream.use { stream ->
            stream.skip(offset)
            val buffer = ByteArray(length)
            var totalRead = 0
            while (totalRead < length) {
                val read = stream.read(buffer, totalRead, length - totalRead)
                if (read == -1) break
                totalRead += read
            }
            buffer
        }
    }

    @Suppress("MagicNumber")
    private fun findFtypOffset(buffer: ByteArray): Int? {
        // Search for "ftyp" marker and validate it's an MP4 box header.
        // The box structure is: [4 bytes size][4 bytes "ftyp"][4+ bytes brand]
        // So we look for "ftyp" at position i, and the box starts at i-4.
        val markerSize = FTYP_MARKER.size
        for (i in markerSize until buffer.size - markerSize) {
            if (matchesFtypMarker(buffer, i)) {
                val boxStart = i - markerSize
                val boxSize = ((buffer[boxStart].toInt() and 0xFF) shl 24) or
                    ((buffer[boxStart + 1].toInt() and 0xFF) shl 16) or
                    ((buffer[boxStart + 2].toInt() and 0xFF) shl 8) or
                    (buffer[boxStart + 3].toInt() and 0xFF)
                if (boxSize in MIN_BOX_SIZE..MAX_BOX_SIZE) {
                    return boxStart
                }
            }
        }
        return null
    }

    @Suppress("MagicNumber")
    private fun matchesFtypMarker(buffer: ByteArray, i: Int): Boolean = buffer[i] == FTYP_MARKER[0] &&
        buffer[i + 1] == FTYP_MARKER[1] &&
        buffer[i + 2] == FTYP_MARKER[2] &&
        buffer[i + 3] == FTYP_MARKER[3]
}
