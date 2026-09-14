package com.example.dsh.module

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Phone-side image sources for the official DSH image-prompt pipeline.
 *
 * Returns canonical Base64 plus `mediaType`, byte count and pixel size, which is
 * everything `imageLimits` is checked against. Nothing else about the file — no
 * path, no `content://` URI — crosses into the shared layer.
 */
class KRDshMediaModule : KuiklyRenderBaseModule() {
    private var callback: KuiklyRenderCallback? = null
    private var maxDimension = 0
    private var maxBytes = 0L
    private var maxCount = 1
    private var cameraFile: File? = null

    init {
        activeInstance = this
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "pickImages" -> pickImages(params, callback)
            "captureImage" -> captureImage(params, callback)
            else -> callback?.invoke(mapOf("error" to "no-picker", "message" to "unknown method $method"))
        }
    }

    private fun pickImages(params: String?, callback: KuiklyRenderCallback?) {
        readParams(params, callback)
        val act = activity ?: return finish("no-picker", "The app window is gone.")
        // The Android 13+ photo picker needs no storage permission and only exposes
        // what the user selects. Older releases fall back to the SAF document picker.
        val picker = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                if (maxCount > 1) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxCount)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                if (maxCount > 1) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        try {
            act.startActivityForResult(picker, REQUEST_PICK)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no photo picker available", e)
            finish("no-picker", "This device has no photo picker.")
        }
    }

    private fun captureImage(params: String?, callback: KuiklyRenderCallback?) {
        readParams(params, callback)
        val act = activity ?: return finish("no-picker", "The app window is gone.")
        // ACTION_IMAGE_CAPTURE requires the CAMERA grant from any app that declares
        // the permission, which this one does for the pairing QR scanner.
        if (act.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }
        launchCamera()
    }

    private fun launchCamera() {
        val act = activity ?: return finish("no-picker", "The app window is gone.")
        val target = runCatching {
            File(act.cacheDir, "camera").apply { mkdirs() }
                .let { File(it, "dsh-capture-${System.currentTimeMillis()}.jpg") }
        }.getOrNull() ?: return finish("decode-failed", "Could not create a file for the photo.")
        cameraFile = target
        val uri = runCatching {
            FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", target)
        }.getOrNull() ?: return finish("decode-failed", "Could not create a file for the photo.")
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            act.startActivityForResult(capture, REQUEST_CAMERA)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no camera app available", e)
            finish("no-picker", "This device has no camera app.")
        }
    }

    private fun readParams(params: String?, callback: KuiklyRenderCallback?) {
        val json = JSONObject(params ?: "{}")
        this.callback = callback
        maxCount = json.optInt("maxCount", 1).coerceAtLeast(1)
        maxDimension = json.optInt("maxDimension", 0)
        maxBytes = json.optLong("maxBytes", 0L)
    }

    private fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            finish("permission-denied", "Camera access is off. Allow it in Settings to take a photo.")
        }
    }

    private fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK && requestCode != REQUEST_CAMERA) return
        if (resultCode != android.app.Activity.RESULT_OK) {
            cameraFile?.delete()
            cameraFile = null
            return finish(null, "")
        }
        val uris = if (requestCode == REQUEST_CAMERA) {
            listOfNotNull(cameraFile?.takeIf { it.length() > 0 }?.let { Uri.fromFile(it) })
        } else {
            collectUris(data)
        }
        if (uris.isEmpty()) return finish("decode-failed", "The picker returned no image.")
        val images = uris.take(maxCount).mapNotNull { readImage(it) }
        cameraFile?.delete()
        cameraFile = null
        if (images.isEmpty()) return finish("decode-failed", "That file could not be read as an image.")
        respond(mapOf("images" to images))
    }

    private fun collectUris(data: Intent?): List<Uri> {
        val clip = data?.clipData
        if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(data?.data)
    }

    /**
     * Decodes [uri] to canonical Base64. The bytes are sent untouched unless the
     * image is longer than [maxDimension] on an edge, in which case it is downscaled
     * and re-encoded so the Host's pixel and dimension limits cannot be tripped by a
     * raw camera-resolution photo.
     */
    private fun readImage(uri: Uri): Map<String, Any>? {
        val resolver = context?.contentResolver ?: return null
        val raw = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (raw.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val mediaType = bounds.outMimeType?.lowercase() ?: resolver.getType(uri)?.lowercase() ?: return null
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val name = displayName(uri, mediaType)
        if (maxDimension <= 0 || maxOf(width, height) <= maxDimension) {
            // An image the Host will reject on size is reported with its metadata but
            // without its data: Base64 of a 27 MB file is 36 MB of string to carry across
            // the bridge for a prevalidation that already has everything it needs.
            val data = if (maxBytes > 0 && raw.size > maxBytes) {
                ""
            } else {
                Base64.encodeToString(raw, Base64.NO_WRAP)
            }
            return entry(mediaType, data, name, raw.size.toLong(), width, height)
        }
        return downscale(raw, width, height, name)
    }

    /** Re-encodes as JPEG (PNG for images that may carry alpha) inside [maxDimension]. */
    private fun downscale(raw: ByteArray, width: Int, height: Int, name: String): Map<String, Any>? {
        val scale = maxDimension.toFloat() / maxOf(width, height).toFloat()
        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }
                .takeWhile { maxOf(width, height) / it >= maxDimension }
                .last()
        }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
        val target = Bitmap.createScaledBitmap(
            decoded,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
        val stream = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val bytes = stream.toByteArray()
        val encodedName = name.substringBeforeLast('.', name) + ".jpg"
        if (target !== decoded) target.recycle()
        decoded.recycle()
        return entry(
            "image/jpeg",
            Base64.encodeToString(bytes, Base64.NO_WRAP),
            encodedName,
            bytes.size.toLong(),
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
        )
    }

    private fun entry(
        mediaType: String,
        data: String,
        name: String,
        bytes: Long,
        width: Int,
        height: Int,
    ): Map<String, Any> = mapOf(
        "mediaType" to mediaType,
        "data" to data,
        "name" to name,
        "bytes" to bytes,
        "width" to width,
        "height" to height,
    )

    private fun displayName(uri: Uri, mediaType: String): String {
        val fromCursor = runCatching {
            context?.contentResolver?.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val fallback = uri.lastPathSegment?.substringAfterLast('/')
        return (fromCursor ?: fallback)?.takeIf { it.isNotEmpty() }
            ?: "image.${mediaType.substringAfterLast('/')}"
    }

    private fun finish(error: String?, message: String) {
        respond(buildMap {
            put("images", emptyList<Map<String, Any>>())
            if (error != null) put("error", error)
            if (message.isNotEmpty()) put("message", message)
        })
    }

    private fun respond(payload: Map<String, Any>) {
        val pending = callback ?: return
        callback = null
        activity?.runOnUiThread { pending.invoke(payload) } ?: pending.invoke(payload)
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    companion object {
        const val MODULE_NAME = "HRDshMediaModule"
        const val REQUEST_PICK = 4093
        const val REQUEST_CAMERA = 4094
        const val REQUEST_CAMERA_PERMISSION = 4095
        private const val TAG = "DshMedia"
        private var activeInstance: KRDshMediaModule? = null

        fun dispatchActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            activeInstance?.onActivityResult(requestCode, resultCode, data)
        }

        fun dispatchRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
            activeInstance?.onRequestPermissionsResult(requestCode, grantResults)
        }
    }
}
