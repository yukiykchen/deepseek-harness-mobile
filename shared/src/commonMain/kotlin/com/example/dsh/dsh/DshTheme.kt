package com.example.dsh.dsh

import com.example.dsh.base.BasePager
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.PagerScope

/** Appearance the user picked; [SYSTEM] follows the OS night-mode flag. */
internal enum class DshThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
    SOLAR,
    HIGH_CONTRAST,
    ;

    val storageValue: String
        get() = when (this) {
            LIGHT -> "light"
            DARK -> "dark"
            SYSTEM -> "system"
            SOLAR -> "solar"
            HIGH_CONTRAST -> "high-contrast"
        }

    companion object {
        fun fromStorage(value: String): DshThemeMode = when (value) {
            "light" -> LIGHT
            "dark" -> DARK
            "solar" -> SOLAR
            "high-contrast" -> HIGH_CONTRAST
            else -> SYSTEM
        }
    }
}

/** Code blocks can keep their own light/dark setting independent of the app theme. */
internal enum class DshCodeTheme {
    AUTO,
    LIGHT,
    DARK,
    ;

    val storageValue: String
        get() = when (this) {
            AUTO -> "auto"
            LIGHT -> "light"
            DARK -> "dark"
        }

    companion object {
        fun fromStorage(value: String): DshCodeTheme = when (value) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> AUTO
        }
    }
}

/** Semantic colour tokens. Every DSH surface reads these instead of literals. */
internal data class DshPalette(
    val isDark: Boolean,
    val codeIsDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val rowSelected: Color,
    val border: Color,
    val divider: Color,
    val inputBackground: Color,
    val inputBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDisabled: Color,
    val placeholder: Color,
    val textOnAccent: Color,
    val accent: Color,
    val accentFill: Color,
    val accentFillDisabled: Color,
    val accentMuted: Color,
    val accentSoft: Color,
    val accentBorder: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val errorBubble: Color,
    val success: Color,
    val successFill: Color,
    val successFillDisabled: Color,
    val successSoft: Color,
    val warning: Color,
    val warningText: Color,
    val warningSoft: Color,
    val warningBorder: Color,
    val danger: Color,
    val dangerText: Color,
    val dangerFill: Color,
    val toolCardBackground: Color,
    val toolCardRunningBackground: Color,
    val toolCardErrorBackground: Color,
    val codeBackground: Color,
    val codeText: Color,
    val codeTextMuted: Color,
    val codeAccent: Color,
    val mask: Color,
    val maskLight: Color,
    val turnStatus: Color,
)

internal object DshTheme {
    /** Code tokens for a light listing, reused when "Code theme: Light" is forced. */
    private val lightCodeBackground = Color(0xFFF9FAFB)
    private val lightCodeText = Color(0xFF333B42)
    private val lightCodeTextMuted = Color(0xFF6E777E)
    private val lightCodeAccent = Color(0xFF4176E6)
    private val darkCodeBackground = Color(0xFF242528)
    private val darkCodeText = Color(0xFFD6DAE0)
    private val darkCodeTextMuted = Color(0xFF9AA1AA)
    private val darkCodeAccent = Color(0xFF7AA6FF)

    val light = DshPalette(
        isDark = false,
        codeIsDark = false,
        background = Color(0xFFF7F9FA),
        surface = Color(0xFFFFFFFF),
        surfaceRaised = Color(0xFFF7F9FB),
        surfaceSunken = Color(0xFFF1F3F5),
        rowSelected = Color(0xFFE3E6EA),
        border = Color(0xFFE4E8EC),
        divider = Color(0xFFEBEEF2),
        inputBackground = Color(0xFFF9FAFB),
        inputBorder = Color(0xFFD9DEE3),
        textPrimary = Color(0xFF1F2933),
        textSecondary = Color(0xFF4F565C),
        textMuted = Color(0xFF7A838A),
        textDisabled = Color(0xFFADB2B8),
        // Was 0xFF98A1A9, which measured 2.51 against the input fill while the dark
        // palette's own placeholder measured 3.88. DshThemeContrastTest holds the floor.
        placeholder = Color(0xFF828B93),
        textOnAccent = Color(0xFFFFFFFF),
        accent = Color(0xFF4176E6),
        // The brand blue behind white label text measures 4.23, just under AA. The dark
        // palette already deepens it to 0xFF3A6FD8 (4.72); light now matches.
        accentFill = Color(0xFF3A6FD8),
        accentFillDisabled = Color(0xFFB7C8FE),
        accentMuted = Color(0xFF679EFE),
        accentSoft = Color(0xFFEDF3FE),
        accentBorder = Color(0xFFC7D9F2),
        userBubble = Color(0xFFEDF3FE),
        userBubbleText = Color(0xFF34415B),
        errorBubble = Color(0xFFFFEEEE),
        success = Color(0xFF2F7D4F),
        successFill = Color(0xFF2F7D4F),
        successFillDisabled = Color(0xFFC8D7A8),
        successSoft = Color(0xFFE8F7EE),
        warning = Color(0xFFD99A20),
        warningText = Color(0xFF8A6A16),
        warningSoft = Color(0xFFFFF4D6),
        warningBorder = Color(0xFFE8E1C8),
        danger = Color(0xFFC23B3B),
        dangerText = Color(0xFFB53232),
        dangerFill = Color(0xFFE05252),
        toolCardBackground = Color(0xFFFCFDFE),
        toolCardRunningBackground = Color(0xFFF7FCFA),
        toolCardErrorBackground = Color(0xFFFFF6F6),
        codeBackground = lightCodeBackground,
        codeText = lightCodeText,
        codeTextMuted = lightCodeTextMuted,
        codeAccent = lightCodeAccent,
        mask = Color(0x66000000),
        maskLight = Color(0x55000000),
        turnStatus = Color(0xFF4D6BFE),
    )

    val dark = DshPalette(
        isDark = true,
        codeIsDark = true,
        background = Color(0xFF101215),
        surface = Color(0xFF17191D),
        surfaceRaised = Color(0xFF1E2126),
        surfaceSunken = Color(0xFF23262C),
        rowSelected = Color(0xFF2B2F36),
        border = Color(0xFF2E323A),
        divider = Color(0xFF24272D),
        inputBackground = Color(0xFF1E2126),
        inputBorder = Color(0xFF3A3F48),
        textPrimary = Color(0xFFE8EAED),
        textSecondary = Color(0xFFBFC5CD),
        textMuted = Color(0xFF8C939C),
        textDisabled = Color(0xFF5A6069),
        placeholder = Color(0xFF767D86),
        textOnAccent = Color(0xFFFFFFFF),
        accent = Color(0xFF7AA6FF),
        accentFill = Color(0xFF3A6FD8),
        accentFillDisabled = Color(0xFF32415C),
        accentMuted = Color(0xFF4E7BC8),
        accentSoft = Color(0xFF1E2A3F),
        accentBorder = Color(0xFF33496E),
        userBubble = Color(0xFF23324A),
        userBubbleText = Color(0xFFD9E3F5),
        errorBubble = Color(0xFF3A1F22),
        success = Color(0xFF5FD095),
        successFill = Color(0xFF2E7D52),
        successFillDisabled = Color(0xFF33452F),
        successSoft = Color(0xFF152B1F),
        warning = Color(0xFFE0A63A),
        warningText = Color(0xFFE8C271),
        warningSoft = Color(0xFF33290F),
        warningBorder = Color(0xFF4A3C18),
        danger = Color(0xFFE87B7B),
        dangerText = Color(0xFFF09595),
        dangerFill = Color(0xFFC24A4A),
        toolCardBackground = Color(0xFF1B1E23),
        toolCardRunningBackground = Color(0xFF15241F),
        toolCardErrorBackground = Color(0xFF2A1A1C),
        codeBackground = darkCodeBackground,
        codeText = darkCodeText,
        codeTextMuted = darkCodeTextMuted,
        codeAccent = darkCodeAccent,
        mask = Color(0x99000000),
        maskLight = Color(0x80000000),
        turnStatus = Color(0xFF8DA6FF),
    )

    val highContrast = DshPalette(
        isDark = true,
        codeIsDark = true,
        background = Color(0xFF000000),
        surface = Color(0xFF000000),
        surfaceRaised = Color(0xFF101010),
        surfaceSunken = Color(0xFF1A1A1A),
        rowSelected = Color(0xFF2E2E2E),
        border = Color(0xFF8A8A8A),
        divider = Color(0xFF6E6E6E),
        inputBackground = Color(0xFF101010),
        inputBorder = Color(0xFFAFAFAF),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFF0F0F0),
        textMuted = Color(0xFFD2D2D2),
        textDisabled = Color(0xFF9A9A9A),
        placeholder = Color(0xFFBFBFBF),
        textOnAccent = Color(0xFFFFFFFF),
        accent = Color(0xFF9FC5FF),
        accentFill = Color(0xFF1A5FD0),
        accentFillDisabled = Color(0xFF3B4A61),
        accentMuted = Color(0xFF3B7AD6),
        accentSoft = Color(0xFF10243F),
        accentBorder = Color(0xFF9FC5FF),
        userBubble = Color(0xFF10243F),
        userBubbleText = Color(0xFFFFFFFF),
        errorBubble = Color(0xFF3A0F12),
        success = Color(0xFF7BEFA9),
        successFill = Color(0xFF11663C),
        successFillDisabled = Color(0xFF3A5A46),
        successSoft = Color(0xFF072414),
        warning = Color(0xFFFFC64D),
        warningText = Color(0xFFFFD98A),
        warningSoft = Color(0xFF2E2205),
        warningBorder = Color(0xFFFFC64D),
        danger = Color(0xFFFF9E9E),
        dangerText = Color(0xFFFFB5B5),
        dangerFill = Color(0xFFB02222),
        toolCardBackground = Color(0xFF0A0A0A),
        toolCardRunningBackground = Color(0xFF06170F),
        toolCardErrorBackground = Color(0xFF2A0C0E),
        codeBackground = Color(0xFF0A0A0A),
        codeText = Color(0xFFFFFFFF),
        codeTextMuted = Color(0xFFC9C9C9),
        codeAccent = Color(0xFF9FC5FF),
        mask = Color(0xCC000000),
        maskLight = Color(0xB3000000),
        turnStatus = Color(0xFF9FC5FF),
    )

    // Six fixed instances so reading `theme` inside every attr {} never allocates.
    private val lightWithDarkCode = light.copy(
        codeIsDark = true,
        codeBackground = darkCodeBackground,
        codeText = darkCodeText,
        codeTextMuted = darkCodeTextMuted,
        codeAccent = darkCodeAccent,
    )
    private val darkWithLightCode = dark.copy(
        codeIsDark = false,
        codeBackground = lightCodeBackground,
        codeText = lightCodeText,
        codeTextMuted = lightCodeTextMuted,
        codeAccent = lightCodeAccent,
    )
    private val highContrastWithLightCode = highContrast.copy(
        codeIsDark = false,
        codeBackground = lightCodeBackground,
        codeText = lightCodeText,
        codeTextMuted = lightCodeTextMuted,
        codeAccent = lightCodeAccent,
    )

    fun palette(
        mode: DshThemeMode,
        systemDark: Boolean,
        solarNight: Boolean,
        codeTheme: DshCodeTheme,
    ): DshPalette {
        val base = when (mode) {
            DshThemeMode.LIGHT -> light
            DshThemeMode.DARK -> dark
            DshThemeMode.HIGH_CONTRAST -> highContrast
            DshThemeMode.SYSTEM -> if (systemDark) dark else light
            DshThemeMode.SOLAR -> if (solarNight) dark else light
        }
        val codeDark = when (codeTheme) {
            DshCodeTheme.AUTO -> base.isDark
            DshCodeTheme.LIGHT -> false
            DshCodeTheme.DARK -> true
        }
        if (codeDark == base.codeIsDark) return base
        return when {
            base === light -> lightWithDarkCode
            base === dark -> darkWithLightCode
            else -> highContrastWithLightCode
        }
    }

    const val THEME_MODE_KEY = "theme_mode"
    const val CODE_THEME_KEY = "code_theme"
}

/**
 * Reactive palette access. `Props` is a [PagerScope], so this resolves inside every
 * `attr { }` block and re-runs it when the pager's theme state changes. Reading it in
 * `body()` is not reactive — `body()` runs once.
 */
internal val PagerScope.theme: DshPalette
    get() = (getPager() as? BasePager)?.palette ?: DshTheme.light
