package com.focusguard.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Copies one intruder photo into the shared gallery, on demand.
 *
 * Deliberately a copy, never a move. The originals stay in the app's private
 * storage because anyone who can open the gallery can also delete from it — and
 * the person most motivated to delete an intruder photo is the intruder. Export
 * exists so *you* can keep a copy somewhere else, not so the app stops holding
 * the evidence.
 */
object IntruderPhotoExporter {

    private const val TAG = "IntruderExport"
    private const val ALBUM = "HardBlock"

    sealed interface Result {
        data object Exported : Result
        data class Failed(val cause: Throwable?) : Result
    }

    fun export(context: Context, photo: File): Result {
        if (!photo.isFile) return Result.Failed(null)

        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Scoped storage: RELATIVE_PATH creates the album without
                    // needing WRITE_EXTERNAL_STORAGE, which the app does not hold.
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result.Failed(null)

            resolver.openOutputStream(uri)?.use { output ->
                photo.inputStream().use { input -> input.copyTo(output) }
            } ?: return Result.Failed(null)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Only visible to the gallery once the bytes are fully written.
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            Result.Exported
        } catch (error: Exception) {
            FocusGuardLogger.logError(TAG, "Falha ao exportar foto para a galeria", error)
            Result.Failed(error)
        }
    }
}
