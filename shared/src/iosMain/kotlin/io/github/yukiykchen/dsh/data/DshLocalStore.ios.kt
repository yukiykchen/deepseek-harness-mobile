package io.github.yukiykchen.dsh.data

import platform.posix.time
import kotlinx.cinterop.ExperimentalForeignApi
import io.github.yukiykchen.dsh.model.DshLegacyRemoteProfile

@OptIn(ExperimentalForeignApi::class)
internal actual fun sqliteNowMs(): Long = time(null) * 1000L

actual fun createDshLocalStore(path: String, legacyProfile: DshLegacyRemoteProfile?): DshLocalStore =
    DshSqliteLocalStore(path, legacyProfile)
