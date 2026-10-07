package org.fossify.gallery.helpers

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.MediaColumns
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.helpers.isRPlus
import java.io.File
import java.util.Locale

/**
 * Finds media that another app (usually the camera) has announced but not finished writing yet.
 * Such files are hidden from everyone else until they are done, this only tells which folders are waiting for one.
 */
object PendingMedia {
    // anything older is a leftover of a failed write, not a photo which is still being processed
    private const val MAX_PENDING_AGE_MS = 90_000L
    private const val PENDING_FILE_PREFIX = ".pending-"

    /** Returns the lowercased paths of the folders which have a file in the making. */
    fun getPendingFolders(context: Context): Set<String> {
        val folders = HashSet<String>()
        if (!isRPlus()) {
            return folders
        }

        val oldestAllowedMs = System.currentTimeMillis() - MAX_PENDING_AGE_MS
        try {
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            }

            val projection = arrayOf(MediaColumns.DATA, MediaColumns.DATE_ADDED)
            context.contentResolver.query(Files.getContentUri("external"), projection, queryArgs, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0) ?: continue
                    val addedMs = cursor.getLong(1) * 1000
                    if (addedMs >= oldestAllowedMs && isMediaName(path)) {
                        folders.add(path.getParentPath().lowercase(Locale.getDefault()))
                    }
                }
            }
        } catch (ignored: Exception) {
        }

        // MediaStore may keep other apps' pending rows to itself, with all files access they are visible on the disk
        try {
            if (Environment.isExternalStorageManager()) {
                val cameraFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
                val hasPendingFile = cameraFolder.listFiles { _, name -> name.startsWith(PENDING_FILE_PREFIX) }?.any {
                    it.lastModified() >= oldestAllowedMs && isMediaName(it.name)
                } == true

                if (hasPendingFile) {
                    folders.add(cameraFolder.absolutePath.lowercase(Locale.getDefault()))
                }
            }
        } catch (ignored: Exception) {
        }

        return folders
    }

    private fun isMediaName(path: String) = path.isImageFast() || path.isVideoFast()
}
