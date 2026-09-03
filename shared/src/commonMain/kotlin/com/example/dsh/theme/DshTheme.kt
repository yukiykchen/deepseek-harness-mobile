package com.example.dsh.theme

/** 某一时刻的完整主题快照。`revision` 单调递增，用于判断是否需要刷新。 */
internal data class DshThemeSnapshot(
    val state: DshThemeState,
    val tokens: DshThemeTokens,
    val codeColors: DshCodeColors,
    val revision: Int,
) {
    val isDark: Boolean get() = state.isDark
    val preference: DshThemePreference get() = state.preference
}

/**
 * 主题的单一状态源。只存普通数据，不含 Kuikly observable。
 *
 * Kuikly 的 observable 绑定在创建它的 Pager 作用域上，因此这里的变化不会直接驱动 UI：
 * 每个 Pager 在 [com.example.dsh.base.BasePager] 中持有 `theme by observable(...)` 镜像，
 * 变化通过 NotifyModule 的 [EVENT] 广播后各自刷新。
 */
internal object DshTheme {
    const val EVENT = "dshThemeChanged"
    const val PREF_KEY = "theme_preference"

    private var state = DshThemeState()
    private var revision = 0

    var snapshot: DshThemeSnapshot = buildSnapshot()
        private set

    /**
     * 页面创建时调用。`stored` 为持久化的偏好字符串（可为空 / 非法），`systemDark` 为系统当前状态。
     * 多个 Pager 重复调用是安全的：只有值变化时才会推进 revision。
     */
    fun bootstrap(stored: String?, systemDark: Boolean): Boolean {
        val next = DshThemeState(
            preference = DshThemePreference.fromStorage(stored),
            systemDark = systemDark,
        )
        return commit(next)
    }

    /** 用户切换偏好。返回 true 表示快照已变化，调用方应广播。 */
    fun setPreference(preference: DshThemePreference): Boolean =
        commit(state.copy(preference = preference))

    /** 系统深浅色变化。只更新 systemDark；仅当偏好为 SYSTEM 且解析结果变化时快照才会变。 */
    fun updateSystemDark(systemDark: Boolean): Boolean =
        commit(state.copy(systemDark = systemDark))

    /** 仅供测试重置全局状态。 */
    fun resetForTest() {
        state = DshThemeState()
        revision = 0
        snapshot = buildSnapshot()
    }

    private fun commit(next: DshThemeState): Boolean {
        val visibleChange = next.preference != state.preference || next.isDark != state.isDark
        state = next
        if (!visibleChange) return false
        revision++
        snapshot = buildSnapshot()
        return true
    }

    private fun buildSnapshot(): DshThemeSnapshot = DshThemeSnapshot(
        state = state,
        tokens = DshThemeTokens.of(state.isDark),
        codeColors = DshCodeColors.of(state.isDark),
        revision = revision,
    )
}
