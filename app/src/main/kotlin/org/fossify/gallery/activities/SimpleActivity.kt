package org.fossify.gallery.activities

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore.Images
import android.provider.MediaStore.Video
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isPiePlus
import org.fossify.gallery.R
import org.fossify.gallery.dialogs.StoragePermissionRequiredDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.MediaStoreDelta
import org.fossify.gallery.helpers.PendingMedia
import org.fossify.gallery.helpers.getPermissionsToRequest

open class SimpleActivity : BaseSimpleActivity() {
    companion object {
        private const val MEDIA_CHANGE_DEBOUNCE = 300L
        private const val PENDING_MEDIA_CHECK_INTERVAL = 1000L
    }

    private var dialog: AlertDialog? = null

    private val mediaChangeHandler = Handler(Looper.getMainLooper())
    private var lastPendingFolders: Set<String> = emptySet()
    private var isObservingMedia = false
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            // a single capture fires several change events, wait for them to settle
            mediaChangeHandler.removeCallbacksAndMessages(null)
            mediaChangeHandler.postDelayed({ checkNewMedia() }, MEDIA_CHANGE_DEBOUNCE)
        }
    }

    // caches media added since the last check and lets the activity show them right away
    protected fun checkNewMedia() {
        ensureBackgroundThread {
            val changedFolders = MediaStoreDelta.apply(applicationContext)
            val pendingFolders = PendingMedia.getPendingFolders(applicationContext)
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                if (changedFolders.isNotEmpty()) {
                    onNewMediaCached(changedFolders)
                }

                if (pendingFolders != lastPendingFolders) {
                    lastPendingFolders = pendingFolders
                    onPendingMediaChanged(pendingFolders)
                }

                // keep looking while something is in the making, so the indicator goes away even if no change event arrives
                if (pendingFolders.isNotEmpty() && isObservingMedia) {
                    mediaChangeHandler.removeCallbacksAndMessages(null)
                    mediaChangeHandler.postDelayed({ checkNewMedia() }, PENDING_MEDIA_CHECK_INTERVAL)
                }
            }
        }
    }

    protected open fun onNewMediaCached(changedFolders: Set<String>) {}

    // called with the paths of the folders that wait for a file another app is still writing
    protected open fun onPendingMediaChanged(pendingFolders: Set<String>) {}

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = "Gallery"

    protected fun checkNotchSupport() {
        if (isPiePlus()) {
            val cutoutMode = when {
                config.showNotch -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }

            window.attributes.layoutInDisplayCutoutMode = cutoutMode
            if (config.showNotch) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
        }
    }

    protected fun registerFileUpdateListener() {
        try {
            isObservingMedia = true
            contentResolver.registerContentObserver(Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (ignored: Exception) {
        }
    }

    protected fun unregisterFileUpdateListener() {
        try {
            isObservingMedia = false
            contentResolver.unregisterContentObserver(observer)
            mediaChangeHandler.removeCallbacksAndMessages(null)
        } catch (ignored: Exception) {
        }
    }

    protected fun showAddIncludedFolderDialog(callback: () -> Unit) {
        FilePickerDialog(this, config.lastFilepickerPath, false, config.shouldShowHidden, false, true) {
            config.lastFilepickerPath = it
            config.addIncludedFolder(it)
            callback()
            ensureBackgroundThread {
                scanPathRecursively(it)
            }
        }
    }

    protected fun requestMediaPermissions(enableRationale: Boolean = false, onGranted: () -> Unit) {
        when {
            hasAllPermissions(getPermissionsToRequest()) -> onGranted()
            config.showPermissionRationale -> {
                if (enableRationale) {
                    showPermissionRationale()
                } else {
                    onPermissionDenied()
                }
            }

            else -> {
                handlePartialMediaPermissions(getPermissionsToRequest(), force = true) { granted ->
                    if (granted) {
                        onGranted()
                    } else {
                        config.showPermissionRationale = true
                        showPermissionRationale()
                    }
                }
            }
        }
    }

    private fun showPermissionRationale() {
        dialog?.dismiss()
        StoragePermissionRequiredDialog(
            activity = this,
            onOkay = ::openDeviceSettings,
            onCancel = ::onPermissionDenied
        ) { dialog ->
            this.dialog = dialog
        }
    }

    private fun onPermissionDenied() {
        toast(org.fossify.commons.R.string.no_storage_permissions)
        finish()
    }
}
