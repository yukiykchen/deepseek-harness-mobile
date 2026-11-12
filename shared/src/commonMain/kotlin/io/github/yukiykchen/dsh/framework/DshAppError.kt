package io.github.yukiykchen.dsh.framework

/** Stable categories for errors crossing a bridge, cache or network boundary. */
enum class DshAppErrorKind {
    IGNORABLE,
    RETRYABLE,
    USER_ACTIONABLE,
}

data class DshAppError(
    val kind: DshAppErrorKind,
    val code: String,
    val message: String,
) {
    companion object {
        fun ignorable(code: String, message: String = "") = DshAppError(DshAppErrorKind.IGNORABLE, code, message)
        fun retryable(code: String, message: String = "") = DshAppError(DshAppErrorKind.RETRYABLE, code, message)
        fun userActionable(code: String, message: String) = DshAppError(DshAppErrorKind.USER_ACTIONABLE, code, message)

        fun classify(transportCode: String, generationCancelled: Boolean = false): DshAppError = when {
            generationCancelled || transportCode == "generation-cancelled" || transportCode == "cancelled" ->
                ignorable(transportCode)
            transportCode.startsWith("transport-") -> retryable(transportCode)
            else -> userActionable(transportCode, "操作失败")
        }

        fun fromRpc(code: String, message: String): DshAppError {
            val kind = when {
                code == "cancelled" || code == "generation-cancelled" -> DshAppErrorKind.IGNORABLE
                code.startsWith("transport-") -> DshAppErrorKind.RETRYABLE
                else -> DshAppErrorKind.USER_ACTIONABLE
            }
            return DshAppError(kind, code, message)
        }
    }
}
