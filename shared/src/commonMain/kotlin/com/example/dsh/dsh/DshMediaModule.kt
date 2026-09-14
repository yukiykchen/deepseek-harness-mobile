package com.example.dsh.dsh

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Why a pick returned nothing, so the composer can explain it instead of going quiet. */
internal enum class DshMediaError { NONE, PERMISSION_DENIED, NO_PICKER, DECODE_FAILED }

internal data class DshMediaResult(
    val images: List<DshOutgoingImage>,
    val error: DshMediaError = DshMediaError.NONE,
    val message: String = "",
)

/**
 * Phone-side image sources for Task 3: the system photo library and the camera.
 *
 * Both return canonical Base64 plus the metadata `imageLimits` is checked against, so
 * the shared layer never touches a file path or a `content://` URI. The camera is only
 * a source — captured images go through the same official image-prompt pipeline.
 */
internal class DshMediaModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    /**
     * Opens the system photo picker for at most [maxCount] images. [maxDimension]
     * caps the longest edge; anything larger is downscaled natively so the Host's
     * pixel limits cannot be tripped by a raw camera-resolution photo. A file over
     * [maxBytes] comes back with its metadata but no data, because prevalidation is
     * going to reject it and the Base64 would only be carried across the bridge to be
     * thrown away.
     */
    fun pickImages(maxCount: Int, maxDimension: Int, maxBytes: Long, callback: (DshMediaResult) -> Unit) {
        request("pickImages", maxCount, maxDimension, maxBytes, callback)
    }

    /** Takes a photo with the system camera app. */
    fun captureImage(maxDimension: Int, maxBytes: Long, callback: (DshMediaResult) -> Unit) {
        request("captureImage", 1, maxDimension, maxBytes, callback)
    }

    private fun request(
        method: String,
        maxCount: Int,
        maxDimension: Int,
        maxBytes: Long,
        callback: (DshMediaResult) -> Unit,
    ) {
        toNative(
            false,
            method,
            JSONObject().apply {
                put("maxCount", maxCount)
                put("maxDimension", maxDimension)
                put("maxBytes", maxBytes)
            }.toString(),
            { value -> callback(parseResult(value)) },
            false,
        )
    }

    private fun parseResult(value: JSONObject?): DshMediaResult {
        if (value == null) return DshMediaResult(emptyList(), DshMediaError.DECODE_FAILED, "The picker returned nothing.")
        val error = when (value.optString("error")) {
            "permission-denied" -> DshMediaError.PERMISSION_DENIED
            "no-picker" -> DshMediaError.NO_PICKER
            "decode-failed" -> DshMediaError.DECODE_FAILED
            else -> DshMediaError.NONE
        }
        val array = value.optJSONArray("images")
        val images = buildList {
            for (index in 0 until (array?.length() ?: 0)) {
                val entry = array?.optJSONObject(index) ?: continue
                val data = entry.optString("data")
                val mediaType = entry.optString("mediaType")
                val bytes = entry.optLong("bytes")
                // Data may be withheld for an oversize file; the metadata is what
                // prevalidation rejects it on.
                if (mediaType.isEmpty() || (data.isEmpty() && bytes <= 0L)) continue
                add(
                    DshOutgoingImage(
                        mediaType = mediaType,
                        base64 = data,
                        name = entry.optString("name").takeIf { it.isNotEmpty() },
                        bytes = bytes,
                        width = entry.optInt("width"),
                        height = entry.optInt("height"),
                    ),
                )
            }
        }
        return DshMediaResult(images, error, value.optString("message"))
    }

    companion object {
        const val MODULE_NAME = "HRDshMediaModule"
    }
}
