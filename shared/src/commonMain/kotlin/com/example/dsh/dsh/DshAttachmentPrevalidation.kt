package com.example.dsh.dsh

/**
 * The `session/attachment-invalid` reasons the App can predict before sending.
 *
 * The names are the Host's `details.reason` values verbatim, so an image rejected
 * on the phone and the same image rejected by the Host report the same code.
 */
internal enum class DshAttachmentReason {
    TOO_MANY_IMAGES,
    IMAGES_TOO_LARGE,
    IMAGE_TOO_LARGE,
    IMAGE_TOO_MANY_PIXELS,
    IMAGE_DIMENSION_TOO_LARGE,
    UNSUPPORTED_IMAGE_TYPE,
    INVALID_IMAGE_BASE64,
}

/** A rejected image: the Host's reason code plus the copy shown next to the thumbnail. */
internal data class DshAttachmentRejection(
    val reason: DshAttachmentReason,
    val message: String,
)

/**
 * Readable copy for a `session/attachment-invalid` reason, or null when the reason
 * is not one of the image ones. [limits] is the Host's `imageLimits` projection when
 * the App is the one rejecting, and null when the Host already rejected — the Host
 * message then carries its own numbers.
 */
internal fun dshAttachmentReasonMessage(reason: String, limits: DshImageLimits? = null): String? = when (reason) {
    "TOO_MANY_IMAGES" -> limits
        ?.let { "At most ${it.maxImagesPerMessage} images per message." }
        ?: "Too many images in one message."
    "IMAGES_TOO_LARGE" -> limits
        ?.let { "The images in this message exceed ${dshFormatBytes(it.maxMessageImageBytes)} in total." }
        ?: "The images in this message exceed the Host's total size limit."
    "IMAGE_TOO_LARGE" -> limits
        ?.let { "This image is larger than the ${dshFormatBytes(it.maxImageBytes)} the Host accepts." }
        ?: "An image exceeds the Host's per-image size limit."
    "IMAGE_TOO_MANY_PIXELS" -> limits
        ?.let { "This image has more than ${it.maxImagePixels} pixels." }
        ?: "An image has too many pixels for the Host."
    "IMAGE_DIMENSION_TOO_LARGE" -> limits
        ?.let { "This image is wider or taller than ${it.maxImageDimension} px." }
        ?: "An image is wider or taller than the Host allows."
    "UNSUPPORTED_IMAGE_TYPE" -> limits
        ?.let { "Unsupported image type; the Host accepts ${it.mediaTypes.joinToString(", ") { type -> type.removePrefix("image/").uppercase() }}." }
        ?: "Unsupported image type; use PNG, JPEG, WebP or GIF."
    "INVALID_IMAGE_BASE64", "INVALID_IMAGE" -> "The image data could not be decoded."
    "IMAGE_TYPE_MISMATCH" -> "The image bytes do not match their declared type."
    "MODEL_DOES_NOT_SUPPORT_IMAGES" -> "The selected model does not accept image input."
    else -> null
}

/**
 * Enforces the Host's `imageLimits` on the phone so a violation is explained before
 * `session/prompt` instead of after it (Task 3 criterion 7).
 *
 * With no `imageLimits` projection yet, only the media types the protocol itself
 * fixes are enforced; everything else is left to the Host.
 */
internal object DshAttachmentPrevalidation {
    /** Media types the first version of the official image pipeline accepts. */
    val DEFAULT_MEDIA_TYPES = listOf("image/png", "image/jpeg", "image/webp", "image/gif")

    /**
     * Checks [candidate] on its own and against the images [staged] before it in the
     * same message. Returns null when the image may be sent.
     */
    fun validate(
        candidate: DshOutgoingImage,
        staged: List<DshOutgoingImage>,
        limits: DshImageLimits?,
    ): DshAttachmentRejection? {
        val mediaTypes = limits?.mediaTypes?.takeIf { it.isNotEmpty() } ?: DEFAULT_MEDIA_TYPES
        if (candidate.mediaType.lowercase() !in mediaTypes) {
            return reject(DshAttachmentReason.UNSUPPORTED_IMAGE_TYPE, limits)
        }
        // Checked before the data: the picker withholds Base64 for a file already over
        // the limit, and "too large" is the reason that image should report anyway.
        if (limits != null && limits.maxImageBytes > 0 && candidate.bytes > limits.maxImageBytes) {
            return reject(DshAttachmentReason.IMAGE_TOO_LARGE, limits)
        }
        if (candidate.base64.isEmpty() || candidate.bytes <= 0L) {
            return reject(DshAttachmentReason.INVALID_IMAGE_BASE64, limits)
        }
        if (limits == null) return null
        if (limits.maxImageDimension > 0 &&
            maxOf(candidate.width, candidate.height) > limits.maxImageDimension
        ) {
            return reject(DshAttachmentReason.IMAGE_DIMENSION_TOO_LARGE, limits)
        }
        if (limits.maxImagePixels > 0 && candidate.width > 0 && candidate.height > 0 &&
            candidate.width.toLong() * candidate.height.toLong() > limits.maxImagePixels
        ) {
            return reject(DshAttachmentReason.IMAGE_TOO_MANY_PIXELS, limits)
        }
        if (limits.maxImagesPerMessage > 0 && staged.size + 1 > limits.maxImagesPerMessage) {
            return reject(DshAttachmentReason.TOO_MANY_IMAGES, limits)
        }
        if (limits.maxMessageImageBytes > 0 &&
            staged.sumOf { it.bytes } + candidate.bytes > limits.maxMessageImageBytes
        ) {
            return reject(DshAttachmentReason.IMAGES_TOO_LARGE, limits)
        }
        return null
    }

    private fun reject(reason: DshAttachmentReason, limits: DshImageLimits?): DshAttachmentRejection =
        DshAttachmentRejection(reason, dshAttachmentReasonMessage(reason.name, limits) ?: reason.name)
}

/** Byte counts as they appear in attachment copy and in exported transcripts. */
internal fun dshFormatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
