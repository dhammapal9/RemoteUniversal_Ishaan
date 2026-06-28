package com.idp.universalremote.presentation.cast

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.idp.universalremote.domain.model.MediaItem
import com.idp.universalremote.domain.model.MediaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MediaGridViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    fun load(type: MediaType) {
        viewModelScope.launch(Dispatchers.IO) {
            _items.value = query(type)
        }
    }

    private fun query(type: MediaType): List<MediaItem> {
        val contentUri = when (type) {
            MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val resolver = getApplication<Application>().contentResolver
        val sortDesc = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val limit = 200

        // API 26+ requires the bundle-arg query; API 30+ throws when LIMIT is embedded
        // in the legacy sort-order string.
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortDesc)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            resolver.query(contentUri, projection, args, null)
        } else {
            resolver.query(contentUri, projection, null, null, "$sortDesc LIMIT $limit")
        }

        val results = mutableListOf<MediaItem>()
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                results += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(contentUri, id),
                    title = c.getString(nameCol).orEmpty(),
                    mediaType = type,
                    sizeBytes = c.getLong(sizeCol),
                    mimeType = c.getString(mimeCol)
                )
            }
        }
        return results
    }
}
