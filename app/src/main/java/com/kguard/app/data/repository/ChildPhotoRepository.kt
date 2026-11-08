package com.kguard.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Child profile photos. A picked photo is turned upright, cropped square, scaled to 512 px and saved as a
 * ~50 KB JPEG, then uploaded to `users/{parent uid}/avatars/{childId}.jpg`. The download URL (which carries
 * its own access token) is what goes into the child's profile, so it loads on every phone.
 */
object ChildPhotoRepository {
    private const val SIZE = 512
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** Reads and shrinks a picked image. Throws IllegalStateException with a readable message if it can't. */
    suspend fun prepare(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; only a missing stream means the photo can't be opened.
        val stream = resolver.openInputStream(uri) ?: error("That photo couldn't be opened.")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("That file isn't a photo K-Guard can use.")

        // Decode at roughly twice the target size to save memory on large camera photos.
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE * 2) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("That photo couldn't be opened.")

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)

        val side = min(decoded.width, decoded.height)
        val scale = SIZE.toFloat() / side
        val matrix = Matrix().apply { postScale(scale, scale); if (rotation != 0) postRotate(rotation.toFloat()) }
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side, matrix, true)
        if (square !== decoded) decoded.recycle()

        val out = ByteArrayOutputStream()
        var quality = 85
        do {
            out.reset()
            square.compress(Bitmap.CompressFormat.JPEG, quality, out)
            quality -= 15
        } while (out.size() > MAX_BYTES && quality > 20)
        square.recycle()
        out.toByteArray()
    }

    /** Uploads [jpeg] for [childId] and returns the URL to store in the child's profile. */
    suspend fun upload(childId: String, jpeg: ByteArray): String {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in again to save the photo.")
        val ref = FirebaseStorage.getInstance().reference.child("users/$uid/avatars/$childId.jpg")
        val meta = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCacheControl("public, max-age=86400")
            .build()
        ref.putBytes(jpeg, meta).await()
        // A new token each upload, so phones that cached the old photo fetch the new one.
        return ref.downloadUrl.await().toString()
    }
}
