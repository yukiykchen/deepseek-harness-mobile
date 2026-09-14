package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshAttachmentPrevalidationTest {

    /** The mock Host's `fixtures/image-limits.json`, i.e. what a real Host publishes. */
    private val limits = DshImageLimits(
        maxImageBytes = 20 * 1024 * 1024,
        maxImagesPerMessage = 4,
        maxMessageImageBytes = 40 * 1024 * 1024,
        maxImagePixels = 33_554_432,
        maxImageDimension = 8192,
        mediaTypes = listOf("image/png", "image/jpeg", "image/webp", "image/gif"),
    )

    private fun image(
        mediaType: String = "image/png",
        bytes: Long = 4096,
        width: Int = 64,
        height: Int = 64,
    ) = DshOutgoingImage(
        mediaType = mediaType,
        base64 = "aGVsbG8=",
        name = "sample.png",
        bytes = bytes,
        width = width,
        height = height,
    )

    @Test
    fun acceptsAnImageInsideEveryLimit() {
        assertNull(DshAttachmentPrevalidation.validate(image(), emptyList(), limits))
    }

    @Test
    fun rejectsAnUnsupportedMediaType() {
        val rejection = DshAttachmentPrevalidation.validate(image(mediaType = "image/bmp"), emptyList(), limits)
        assertEquals(DshAttachmentReason.UNSUPPORTED_IMAGE_TYPE, rejection?.reason)
        assertTrue(rejection!!.message.contains("PNG"), rejection.message)
    }

    @Test
    fun rejectsEmptyBase64BeforeTheHostDoes() {
        val rejection = DshAttachmentPrevalidation.validate(
            image().copy(base64 = "", bytes = 0),
            emptyList(),
            limits,
        )
        assertEquals(DshAttachmentReason.INVALID_IMAGE_BASE64, rejection?.reason)
    }

    @Test
    fun rejectsAnImageOverThePerImageByteLimit() {
        val rejection = DshAttachmentPrevalidation.validate(
            image(bytes = 21 * 1024 * 1024),
            emptyList(),
            limits,
        )
        assertEquals(DshAttachmentReason.IMAGE_TOO_LARGE, rejection?.reason)
        assertEquals("This image is larger than the 20 MB the Host accepts.", rejection?.message)
    }

    @Test
    fun anOversizeFileWithheldByThePickerStillReportsItsSize() {
        // The native picker does not Base64-encode a file it knows is over the limit.
        val rejection = DshAttachmentPrevalidation.validate(
            image(bytes = 27 * 1024 * 1024).copy(base64 = ""),
            emptyList(),
            limits,
        )
        assertEquals(DshAttachmentReason.IMAGE_TOO_LARGE, rejection?.reason)
    }

    @Test
    fun rejectsAnEdgeLongerThanTheHostAllows() {
        val rejection = DshAttachmentPrevalidation.validate(
            image(width = 9000, height = 10),
            emptyList(),
            limits,
        )
        assertEquals(DshAttachmentReason.IMAGE_DIMENSION_TOO_LARGE, rejection?.reason)
    }

    @Test
    fun rejectsTooManyPixelsWhenBothEdgesFit() {
        val rejection = DshAttachmentPrevalidation.validate(
            image(width = 8000, height = 8000),
            emptyList(),
            limits,
        )
        assertEquals(DshAttachmentReason.IMAGE_TOO_MANY_PIXELS, rejection?.reason)
    }

    @Test
    fun rejectsOneImageMoreThanTheMessageAllows() {
        val staged = List(4) { image() }
        val rejection = DshAttachmentPrevalidation.validate(image(), staged, limits)
        assertEquals(DshAttachmentReason.TOO_MANY_IMAGES, rejection?.reason)
        assertEquals("At most 4 images per message.", rejection?.message)
    }

    @Test
    fun rejectsAMessageWhoseImagesExceedTheTotalBudget() {
        val staged = listOf(image(bytes = 19 * 1024 * 1024), image(bytes = 19 * 1024 * 1024))
        val rejection = DshAttachmentPrevalidation.validate(image(bytes = 5 * 1024 * 1024), staged, limits)
        assertEquals(DshAttachmentReason.IMAGES_TOO_LARGE, rejection?.reason)
    }

    @Test
    fun withoutLimitsOnlyTheProtocolsMediaTypesAreEnforced() {
        assertNull(DshAttachmentPrevalidation.validate(image(bytes = Long.MAX_VALUE), emptyList(), null))
        assertEquals(
            DshAttachmentReason.UNSUPPORTED_IMAGE_TYPE,
            DshAttachmentPrevalidation.validate(image(mediaType = "application/pdf"), emptyList(), null)?.reason,
        )
    }

    @Test
    fun hostAndLocalRejectionsShareTheirReasonCodes() {
        // A locally rejected image and the same rejection coming back from the Host must
        // be recognisable as the same failure (Task 3 bonus 3).
        DshAttachmentReason.entries.forEach { reason ->
            assertTrue(
                dshAttachmentReasonMessage(reason.name) != null,
                "no Host copy for ${reason.name}",
            )
        }
        assertEquals(
            "An image exceeds the Host's per-image size limit.",
            dshPromptErrorMessage(
                DshRpcError("session/attachment-invalid", "nope", """{"reason":"IMAGE_TOO_LARGE"}"""),
            ),
        )
    }
}
