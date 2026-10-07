package org.fossify.gallery.helpers

import android.content.Context
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.MediaColumns
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.helpers.NOMEDIA
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.gallery.R
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.createDirectoryFromMedia
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.getNoMediaFoldersSync
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.shouldFolderBeVisible
import org.fossify.gallery.extensions.updateDBDirectory
import org.fossify.gallery.models.Medium
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Asks MediaStore only for the rows added or changed since the previous check and writes them into the local cache,
 * so a fresh photo or screenshot shows up without waiting for a full rescan of every folder.
 */
object MediaStoreDelta {
    private const val MAX_ROWS = 500
    private const val DATE_CHECK_OVERLAP_SECS = 2L
    private val lock = Any()

    /** Returns the paths of the folders whose cached content changed. */
    fun apply(context: Context): Set<String> = synchronized(lock) {
        try {
            val newMedia = getChangedMedia(context)
            if (newMedia.isEmpty()) {
                emptySet()
            } else {
                cacheMedia(context, newMedia)
            }
        } catch (ignored: Exception) {
            emptySet()
        }
    }

    private fun getChangedMedia(context: Context): ArrayList<Medium> {
        val config = context.config
        val nowSecs = System.currentTimeMillis() / 1000
        val lastCheckSecs = config.lastMediaStoreCheck
        config.lastMediaStoreCheck = nowSecs

        var selection: String? = null
        var selectionArgs: Array<String>? = null
        if (isRPlus()) {
            try {
                val generation = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val lastGeneration = config.lastMediaStoreGeneration
                config.lastMediaStoreGeneration = generation
                if (lastGeneration < 0 || generation <= lastGeneration) {
                    // first check, nothing new, or MediaStore got rebuilt. The full rescan handles the last case
                    return ArrayList()
                }

                selection = "${MediaColumns.GENERATION_MODIFIED} > ?"
                selectionArgs = arrayOf(lastGeneration.toString())
            } catch (ignored: Exception) {
            }
        }

        if (selection == null) {
            if (lastCheckSecs == 0L) {
                return ArrayList()
            }

            val since = (lastCheckSecs - DATE_CHECK_OVERLAP_SECS).toString()
            selection = "${MediaColumns.DATE_MODIFIED} > ? OR ${MediaColumns.DATE_ADDED} > ?"
            selectionArgs = arrayOf(since, since)
        }

        val projection = arrayListOf(
            MediaColumns._ID,
            MediaColumns.DISPLAY_NAME,
            MediaColumns.DATA,
            MediaColumns.DATE_MODIFIED,
            MediaColumns.DATE_TAKEN,
            MediaColumns.SIZE
        )

        if (isQPlus()) {
            projection.add(MediaColumns.DURATION)
        }

        val showHidden = config.shouldShowHidden
        val media = ArrayList<Medium>()
        val uri = Files.getContentUri("external")
        context.contentResolver.query(uri, projection.toTypedArray(), selection, selectionArgs, null)?.use { cursor ->
            if (cursor.count > MAX_ROWS) {
                // something like a restore or a big copy, leave it to the full rescan
                return ArrayList()
            }

            val idIndex = cursor.getColumnIndexOrThrow(MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaColumns.DATA)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
            val takenIndex = cursor.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaColumns.SIZE)
            val durationIndex = cursor.getColumnIndex(MediaColumns.DURATION)

            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIndex) ?: continue
                val filename = cursor.getString(nameIndex) ?: continue
                val size = cursor.getLong(sizeIndex)
                if (size <= 0L || (!showHidden && path.contains("/."))) {
                    continue
                }

                val type = when {
                    path.isImageFast() -> TYPE_IMAGES
                    path.isVideoFast() -> TYPE_VIDEOS
                    path.isGif() -> TYPE_GIFS
                    path.isRawFast() -> TYPE_RAWS
                    path.isSvg() -> TYPE_SVGS
                    else -> continue
                }

                val lastModified = cursor.getLong(modifiedIndex) * 1000
                var dateTaken = cursor.getLong(takenIndex)
                if (dateTaken == 0L) {
                    dateTaken = lastModified
                }

                val videoDuration = if (durationIndex != -1) (cursor.getLong(durationIndex) / 1000.0).roundToInt() else 0
                val isFavorite = try {
                    context.favoritesDB.isFavorite(path)
                } catch (ignored: Exception) {
                    false
                }

                media.add(
                    Medium(
                        id = null,
                        name = filename,
                        path = path,
                        parentPath = path.getParentPath(),
                        modified = lastModified,
                        taken = dateTaken,
                        size = size,
                        type = type,
                        videoDuration = videoDuration,
                        isFavorite = isFavorite,
                        deletedTS = 0L,
                        mediaStoreId = cursor.getLong(idIndex)
                    )
                )
            }
        }

        return media
    }

    private fun cacheMedia(context: Context, newMedia: ArrayList<Medium>): Set<String> {
        val config = context.config
        val excludedPaths: MutableSet<String> = if (config.temporarilyShowExcluded) HashSet() else config.excludedFolders
        val includedPaths = config.includedFolders
        val noMediaFolders = context.getNoMediaFoldersSync()
        val folderNoMediaStatuses = HashMap<String, Boolean>()
        noMediaFolders.forEach { folder ->
            folderNoMediaStatuses["$folder/$NOMEDIA"] = true
        }

        val albumCovers = config.parseAlbumCovers()
        val hiddenString = context.getString(R.string.hidden)
        val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0
        val cachedDirPaths = context.directoryDB.getAll().map { it.path.lowercase(Locale.getDefault()) }.toHashSet()
        val mediaFetcher = MediaFetcher(context)
        val changedFolders = HashSet<String>()

        newMedia.groupBy { it.parentPath }.forEach { (folder, media) ->
            val isVisible = folder.shouldFolderBeVisible(excludedPaths, includedPaths, config.shouldShowHidden, folderNoMediaStatuses) { path, hasNoMedia ->
                folderNoMediaStatuses[path] = hasNoMedia
            }

            if (!isVisible) {
                return@forEach
            }

            context.mediaDB.insertAll(media)
            val folderMedia = ArrayList(context.mediaDB.getMediaFromPath(folder))
            mediaFetcher.sortMedia(folderMedia, config.getFolderSorting(folder))
            val directory = context.createDirectoryFromMedia(
                path = folder,
                curMedia = folderMedia,
                albumCovers = albumCovers,
                hiddenString = hiddenString,
                includedFolders = includedPaths,
                getProperFileSize = getProperFileSize,
                noMediaFolders = noMediaFolders
            )

            if (cachedDirPaths.contains(folder.lowercase(Locale.getDefault()))) {
                context.updateDBDirectory(directory)
            } else {
                context.directoryDB.insert(directory)
            }

            changedFolders.add(folder)
        }

        return changedFolders
    }
}
