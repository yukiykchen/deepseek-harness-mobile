package io.github.yukiykchen.dsh.ssh
internal enum class SshPhase {
    IDLE,
    CONNECTING,
    AUTHENTICATING,
    FINGERPRINT_REQUIRED,
    FORWARDING,
    READY,
    RECONNECTING,
    ERROR,
    STOPPED,
}

internal data class SshState(
    val phase: SshPhase,
    val message: String = "",
    val localPort: Int = 0,
    val generation: Long = 0,
)

internal data class DshSshConfig(
    val host: String,
    val port: Int,
    val username: String,
    val remoteDshPort: Int,
    val keyId: String,
    val hostFingerprint: String = "",
)
