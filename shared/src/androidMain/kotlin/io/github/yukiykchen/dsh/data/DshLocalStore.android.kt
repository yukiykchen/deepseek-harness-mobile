package io.github.yukiykchen.dsh.data

import io.github.yukiykchen.dsh.model.DshLegacyRemoteProfile

internal actual fun sqliteNowMs(): Long = System.currentTimeMillis()

actual fun createDshLocalStore(path: String, legacyProfile: DshLegacyRemoteProfile?): DshLocalStore =
    DshSqliteLocalStore(path, legacyProfile)
