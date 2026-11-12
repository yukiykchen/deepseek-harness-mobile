package io.github.yukiykchen.dsh.data

import io.github.yukiykchen.dsh.model.DshLegacyRemoteProfile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
internal actual fun sqliteNowMs(): Long = memScoped {
    val now = alloc<timeval>()
    check(gettimeofday(now.ptr, null) == 0) { "Cannot read SQLite wall clock" }
    now.tv_sec * 1000L + now.tv_usec / 1000L
}

actual fun createDshLocalStore(path: String, legacyProfile: DshLegacyRemoteProfile?): DshLocalStore =
    DshSqliteLocalStore(path, legacyProfile)
