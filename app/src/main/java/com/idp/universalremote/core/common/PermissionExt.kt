package com.idp.universalremote.core.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.idp.universalremote.domain.model.MediaType

/**
 * Runtime permission helpers. Pre-Android-13 (API <33) the storage-read grant is a
 * single `READ_EXTERNAL_STORAGE`; on 13+ Google split it per media type. On 14+ a
 * fourth grant — `READ_MEDIA_VISUAL_USER_SELECTED` — covers the "Selected photos"
 * picker mode and is granted alongside images/video.
 */
object PermissionExt {

    /** Permissions required to enumerate media of [type] from MediaStore. */
    fun mediaReadPermissions(type: MediaType): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when (type) {
            MediaType.IMAGE -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            MediaType.VIDEO -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            MediaType.AUDIO -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        }
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * Android 14+ exposes a third option in the system dialog ("Allow access to
     * selected photos only"). We treat that as "granted" for our purposes since
     * `MediaStore` will return only the chosen items.
     */
    fun hasMediaReadAccess(context: Context, type: MediaType): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && type != MediaType.AUDIO) {
            val partial = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
            if (partial) return true
        }
        return mediaReadPermissions(type).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * POST_NOTIFICATIONS is only a runtime permission on API 33+. Below that it's
     * granted automatically at install time.
     */
    fun notificationPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.POST_NOTIFICATIONS
        else null

    fun hasNotificationPermission(context: Context): Boolean {
        val perm = notificationPermission() ?: return true
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}
