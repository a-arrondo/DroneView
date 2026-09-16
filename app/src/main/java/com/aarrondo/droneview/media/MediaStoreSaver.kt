package com.aarrondo.droneview.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaStoreSaver {

    const val RELATIVE_PATH = "DCIM/DroneView"

    private const val PHOTO_MIME = "image/jpeg"
    private const val VIDEO_MIME = "video/mp4"
    private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"

    fun photoDisplayName(): String = photoDisplayName(Date())

    fun videoDisplayName(): String = videoDisplayName(Date())

    fun photoDisplayName(now: Date): String =
        "DroneView_" + formatTimestamp(now) + ".jpg"

    fun videoDisplayName(now: Date): String =
        "DroneView_" + formatTimestamp(now) + ".mp4"

    fun formatTimestamp(now: Date): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(now)

    suspend fun savePhoto(
        context: Context,
        bitmap: Bitmap,
        quality: Int = 95,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = photoDisplayName()
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, PHOTO_MIME)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            pending,
        ) ?: throw IOException("MediaStore.Images insert failed ($displayName)")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    throw IOException("Bitmap.compress(JPEG) failed ($displayName)")
                }
            } ?: throw IOException("openOutputStream failed ($displayName)")

            val completed = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, completed, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    suspend fun createPendingVideo(
        context: Context,
    ): Pair<Uri, ParcelFileDescriptor> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = videoDisplayName()
        val pending = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            pending,
        ) ?: throw IOException("MediaStore.Video insert failed ($displayName)")

        try {
            val pfd: ParcelFileDescriptor =
                resolver.openFileDescriptor(uri, "rw")
                    ?: throw IOException("openFileDescriptor(rw) failed ($displayName)")
            uri to pfd
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    suspend fun finalizeVideo(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            val completed = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, completed, null, null)
        }
    }

    suspend fun abortVideo(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
}

