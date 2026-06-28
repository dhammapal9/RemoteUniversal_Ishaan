package com.idp.universalremote.domain.model

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val mediaType: MediaType,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val thumbnail: Uri? = null,
    val mimeType: String? = null
)

enum class MediaType { IMAGE, VIDEO, AUDIO }
