package com.example.dsh.theme

/** 用户可选的主题偏好。持久化时写 [storageValue]，与桌面端 `ui-theme.preference` 取值一致。 */
enum class DshThemePreference(val storageValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    fun resolvedIsDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** 空值、非法值一律回退 [SYSTEM]，避免旧版本或异常写入导致启动失败。 */
        fun fromStorage(raw: String?): DshThemePreference {
            val normalized = raw?.trim()?.lowercase() ?: return SYSTEM
            return entries.firstOrNull { it.storageValue == normalized } ?: SYSTEM
        }
    }
}
