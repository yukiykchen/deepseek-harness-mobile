# spec.md · 夜间模式与系统主题适配

本规格是工程直接对照实施的依据。页面层在 `shared` 三端共用；原生壳（初值、系统监听、防白闪、状态栏）须在 Android / iOS / 鸿蒙同步实现并验收。

---

## 1. 关键技术约束（决定架构的三个事实）

### 1.1 Kuikly 的 `observable` 是 Pager 作用域的

`observable` 绑定创建它的 PageScope。放在单例里的 observable 不会触发任何页面重建。因此**禁止**让页面直接依赖单例状态驱动 UI；必须采用 Kuikly demo 的换肤模式：

- 单例只存普通数据；
- 每个 `Pager` 持有自己的 `var theme by observable(...)` 镜像；
- 切换时通过 `NotifyModule` 广播，各 Pager 收到后刷新自己的镜像。

### 1.2 主题偏好持久化到 `SharedPreferencesModule`，不用 `DshLocalStore`

防白闪要求原生容器在 `setContentView` 之前就知道深浅色。`DshLocalStore`（SQLite）依赖 `createPageData()` 传入的 `databaseDir`，时序上晚于首帧；而 Kuikly 的 `SharedPreferencesModule` 在 Android 上就是名为 `KRSharedPreferencesModule` 的原生 SharedPreferences 文件，`onCreate` 内可同步读取。

| 存储 | 首帧前原生可读 | 跨端 | 结论 |
| --- | --- | --- | --- |
| `DshLocalStore`（SQLite） | 否 | 是 | 不用于主题 |
| `SharedPreferencesModule` | 是 | 是 | 采用 |

### 1.3 原生下发系统主题变化的通道是 `sendEvent("themeDidChanged", ...)`

`Pager` 按 `PAGER_EVENT_THEME_DID_CHANGED = "themeDidChanged"` 分发到 `themeDidChanged(data)`；`data` 约定含 `isNightMode: Boolean`。

三端宿主都必须：

| 平台 | 注入初值 | 系统变化 | 首帧前上色 |
| --- | --- | --- | --- |
| Android | `createPageData()["isNightMode"]` | `onConfigurationChanged` → `delegator.sendEvent` | `DshThemeChrome` 读 SP 文件 `KRSharedPreferencesModule` |
| iOS | `pageData["isNightMode"]` | `traitCollectionDidChange` → `delegator sendWithEvent` | 读 `NSUserDefaults` 键 `theme_preference` |
| 鸿蒙 | `pagerData["isNightMode"]` | `onConfigurationUpdate` / colorMode → `delegate.sendEvent` | 读 Kuikly SP 文件 `{filesDir}/KRSharedPreferencesModule` |

`DshThemeModule.applyNativeChrome(isDark)` 三端都要实现并注册，不得空实现、不得只在 Android 落地。

---

## 2. 主题状态模型

```kotlin
enum class DshThemePreference { SYSTEM, LIGHT, DARK }

data class DshThemeState(
    val preference: DshThemePreference = DshThemePreference.SYSTEM,
    val systemDark: Boolean = false,
) {
    val isDark: Boolean
        get() = when (preference) {
            DshThemePreference.SYSTEM -> systemDark
            DshThemePreference.LIGHT -> false
            DshThemePreference.DARK -> true
        }
}
```

行为矩阵：

| 偏好 | 系统变化时 | 重启后 |
| --- | --- | --- |
| LIGHT | 始终浅色 | 保持浅色 |
| DARK | 始终深色 | 保持深色 |
| SYSTEM | 立即跟随系统 | 仍为跟随系统，按当时系统状态解析 |

规则：

- 设置 UI 展示并写入的是 `preference`，不是解析后的 `isDark`。
- `themeDidChanged` 只更新 `systemDark`，不得覆盖用户的 LIGHT / DARK 选择。
- 不得把偏好存为布尔值，否则无法区分 LIGHT 与 SYSTEM。

---

## 3. 模块与目录

```
shared/src/commonMain/kotlin/com/example/dsh/theme/
├── DshThemePreference.kt   // 枚举 + fromStorage(String?) 安全解析
├── DshThemeState.kt        // preference + systemDark → isDark
├── DshThemeTokens.kt       // 语义色 data class + LIGHT / DARK 两套常量
├── DshCodeColors.kt        // 语法高亮色，对齐 web shiki.css
└── DshTheme.kt             // 单例：bootstrap / setPreference / updateSystemDark / snapshot
```

### 3.1 `DshTheme` 单例（纯状态源）

```kotlin
internal object DshTheme {
    const val EVENT = "dshThemeChanged"
    const val PREF_KEY = "theme_preference"       // "system" | "light" | "dark"

    val snapshot: DshThemeSnapshot                 // state + tokens + codeColors + revision

    fun bootstrap(stored: String?, systemDark: Boolean)
    fun setPreference(p: DshThemePreference)      // 更新 → 广播；持久化由调用方（Pager）完成
    fun updateSystemDark(dark: Boolean)           // 仅 SYSTEM 且值变化时广播
    fun tokens(isDark: Boolean): DshThemeTokens   // 供子 ComposeView 按 attr.darkMode 取色
}
```

### 3.2 `BasePager` 镜像与广播

```kotlin
internal abstract class BasePager : Pager() {
    var theme by observable(DshTheme.snapshot)
    private lateinit var themeRef: CallbackRef

    override fun created() {
        super.created()
        DshTheme.bootstrap(
            stored = sp().getString(DshTheme.PREF_KEY),
            systemDark = isNightMode(),
        )
        theme = DshTheme.snapshot
        themeRef = notify().addNotify(DshTheme.EVENT) { theme = DshTheme.snapshot }
    }

    override fun themeDidChanged(data: JSONObject) {
        super.themeDidChanged(data)
        DshTheme.updateSystemDark(data.optBoolean(IS_NIGHT_MODE_KEY))
    }

    override fun pageWillDestroy() {
        notify().removeNotify(DshTheme.EVENT, themeRef)
        super.pageWillDestroy()
    }

    fun setThemePreference(p: DshThemePreference) {
        DshTheme.setPreference(p)
        sp().setString(DshTheme.PREF_KEY, p.storageValue)   // 失败不阻断，记录 Warn
        notify().postNotify(DshTheme.EVENT, JSONObject())
        acquireModule<DshThemeModule>(...).applyNativeChrome(DshTheme.snapshot.isDark)
    }
}
```

### 3.3 子 ComposeView 传色

`DshDisclosureRow`、`DshApprovalPanel`、`DshMarkdown` 等不继承 Pager，统一通过 attr 传入：父页面写 `darkMode = ctx.theme.isDark`（`DshMarkdown.darkMode` 已是先例），组件内部 `DshTheme.tokens(attr.darkMode)` 取色。attr 为 observable，父页面 `theme` 变化即触发子组件重建。

### 3.4 页面使用约定

允许：

```kotlin
backgroundColor(ctx.theme.tokens.background)
color(ctx.theme.tokens.primaryText)
darkMode = ctx.theme.isDark
```

禁止：

```kotlin
if (isNightMode()) ...          // 页面不得直接读系统状态
backgroundColor(Color.WHITE)    // 生产路径不得再出现硬编码色
color(Color(0xFF1F2933))
```

`vfor` 列表项内直接读 `ctx.theme`，不缓存色值。

---

## 4. 语义色 token（`DshThemeTokens`）

命名采用语义角色；色值以 web `ui-theme` 已验证的 `--dsw-alias-*` 深色板为准，缺口处补充并通过对比度检查。

### 4.1 基础色

| token | 浅色 | 深色 | 用途 | 对应 web alias |
| --- | --- | --- | --- | --- |
| `background` | `#F5F6F7` | `#151517` | 页面主背景、原生窗口底 | `bg-module-platform` / `bg-base` |
| `surface` | `#FFFFFF` | `#232324` | 卡片、弹窗、顶栏、composer | `bg-layer-1` |
| `surfaceVariant` | `#F1F3F5` | `#2C2C2E` | 输入区、次级容器、分段选择底 | `bg-layer-2` |
| `surfaceElevated` | `#FFFFFF` | `#353638` | 菜单、浮层 | `bg-layer-3` |
| `selectedSurface` | `#E4EDFD` | `#1C2D49` | 当前会话选中底 | — |
| `primaryText` | `#0F1115` | `#F9FAFB` | 正文、标题 | `label-primary` |
| `secondaryText` | `#61666B` | `#CFD3D6` | 辅助文本 | `label-secondary` |
| `tertiaryText` | `#81858C` | `#ADB2B8` | 时间、说明、placeholder | `label-tertiary` |
| `captionText` | `#ADB2B8` | `#81858C` | 分组标题 | `label-caption` |
| `divider` | `rgba(0,0,0,0.10)` | `rgba(255,255,255,0.12)` | 分割线、边框 | `border-l2` |
| `dividerStrong` | `rgba(0,0,0,0.16)` | `rgba(255,255,255,0.20)` | 输入框边框 | `border-l4` |
| `primary` | `#4176E6` | `#5686FE` | 主操作、链接、发送键 | `state-business-primary` |
| `primaryPressed` | `#315FC7` | `#679EFE` | 按压 | `button-info-fill` |
| `primaryDisabled` | `#B7C8FE` | `#3A4A6E` | 禁用主按钮（深色不得接近背景） | — |
| `onPrimary` | `#FFFFFF` | `#FFFFFF` | 主色按钮上的文字与图标 | — |
| `icon` | `#555D64` | `#D1D7DC` | 单色图标 tint | — |
| `scrim` | `rgba(0,0,0,0.40)` | `rgba(0,0,0,0.60)` | Modal 遮罩 | — |
| `userBubble` | `#EDF3FE` | `#1C2D49` | 用户消息气泡底 | — |
| `userBubbleText` | `#34415B` | `#DCE6FF` | 用户消息文字 | — |

### 4.2 状态色（前景与背景成对，禁止只换背景）

| 状态 | 浅色 背景 / 前景 | 深色 背景 / 前景 | 用途 | 对应 web alias |
| --- | --- | --- | --- | --- |
| `success` | `#E6FAED` / `#1F8A4C` | `#233C2C` / `#4ED17E` | 已连接、允许、成功 | `state-success-*` |
| `warning` | `#FEF5E7` / `#DD8629` | `#27241F` / `#F7AD31` | 审批 badge、指纹确认 | `state-warn-*` |
| `error` | `#FDEBEC` / `#EC1313` | `#451D22` / `#F25A5A` | 错误气泡、拒绝、停止键 | `state-error-*` |
| `info` | `#EEF3FA` / `#4176E6` | `#1C2D49` / `#8CB2FF` | 提问面板、提示 | — |
| `running` | `#EEF3FA` / `#4D6BFE` | `#1C2D49` / `#78A4F8` | 工具运行中、turn status | — |
| `disabled` | `#F1F3F5` / `#ADB2B8` | `#2C2C2E` / `#61666B` | 禁用态（仍须可辨识） | — |

### 4.3 Markdown 与代码（`DshCodeColors`）

| token | 浅色 | 深色 | 对应 web |
| --- | --- | --- | --- |
| `codeBlockBackground` | `#F9FAFB` | `#1B1B1C` | `markdown-code-block` |
| `inlineCodeBackground` | `#EBEEF2` | `#2C2C2E` | `markdown-inline-code` |
| `codeText` | `primaryText` | `primaryText` | `shiki-foreground` |
| `quoteBar` | `#A2A4A8` | `#858990` | — |
| `quoteBackground` | `#F5F6F7` | `#242528` | — |
| `quoteText` | `#61666D` | `#B7BBC2` | — |
| `tableBackground` | `#FAFAFA` | `#202124` | — |
| `link` | `#4176E6` | `#78A4F8` | — |
| 语法 constant | `#1C7ED6` | `#4DABF7` | shiki |
| 语法 string | `#2F9E44` | `#69DB7C` | shiki |
| 语法 comment | `#868E96` | `#ADB5BD` | shiki |
| 语法 keyword | `#D6336C` | `#FAA2C1` | shiki |
| 语法 parameter | `#E8590C` | `#FFA94D` | shiki |
| 语法 function | `#6741D9` | `#B197FC` | shiki |
| 语法 punctuation | `#495057` | `#CED4DA` | shiki |

公式文本跟随 `primaryText`，公式容器跟随 `codeBlockBackground`。

`DshMarkdown.markdownConfig()` 改为从 `DshTheme.tokens(attr.darkMode)` 与 `DshCodeColors` 取值；`codeHighlightDarkTheme = attr.darkMode`。

预留 `enum class CodeTheme { FOLLOW_APP, LIGHT, DARK }`，本期固定 `FOLLOW_APP`，独立代码主题 UI 留到加分项。

### 4.4 对比度要求

- 正文与重要操作文字对背景不低于 4.5:1；大字号不低于 3:1。
- 状态不能只靠颜色，工具卡片保留图标与文案语义。
- 禁用态不得与背景融为一体。

---

## 5. 组件适配规格

| 表面 | 必须替换的项 |
| --- | --- |
| 会话抽屉 | 抽屉底 `surface`、遮罩 `scrim`、选中行 `selectedSurface`、运行状态点 `running`、新会话 / 外观 / 连接设置入口文字 `primaryText` / `secondaryText` |
| 顶栏 | 底 `surface`、标题 `primaryText`、菜单 / 加号图标 tint `icon` |
| 消息区 | 页面底 `background`、用户气泡 `userBubble`、错误气泡 `error`、时间戳 `tertiaryText` |
| 输入区 | 容器 `surface`、输入文字 `primaryText`、placeholder `tertiaryText`、模型选择边框 `dividerStrong`、发送键 `primary` / 停止键 `error.fg` / 语音 `primaryPressed`、附件菜单 `surfaceVariant`、Skill 联想 `surfaceVariant` + `success.fg` |
| Markdown | 见 4.3，全部经 `darkMode` attr |
| 工具卡片 `DshDisclosureRow` | 六种状态各自的背景 / 边框 / 图标 tint / 文案色；`DshLongText`、`DshJsonTree` 底用 `codeBlockBackground` |
| 审批 `DshApprovalPanel` | 卡片 `surface`、风险 badge `warning`、命令预览 `codeBlockBackground`、允许 `success.fg`、拒绝 `error.fg`、忙碌 `disabled` |
| 提问 `DshQuestionFlow` / `DshQuestionPanel` | 卡片 `surface`、选中 `primary`、单选边框 `divider`、提示 `info` |
| Queue / Jobs / Goal | 底 `surfaceVariant`，状态点用状态色对 |
| 连接设置 / 凭证 / 模型 / 工作区 Modal | 遮罩 `scrim`、卡片 `surface`、分段选择 `surfaceVariant`、输入框 `surfaceVariant` + `dividerStrong`、指纹提示 `warning`、错误 `error.fg`、保存键 `primary` / `primaryDisabled` |
| 连接向导 `DshConnectionSetupPage` | 页面底 `background`、头部 `surface`、按钮 `primary`、错误 `error.fg` |
| WebView 页 | 页面底 `background`、头部 `surface`、进度条 `primary`、WebView 容器底跟随 `background` |

图标策略（Kuikly `Image.tintColor()` 已确认支持）：

| 资源 | 处理 |
| --- | --- |
| `menu` `plus` `x` `sliders` `chevron-down` `tool-*` `goal` | `tintColor(icon)` |
| `send` `mic` `square` | 保持白色，始终画在 `primary` / `error` 实色按钮上 |
| `wordmark`（`currentColor`） | `tintColor(primaryText)` |

根 View 设置 `autoDarkEnable(false)`，避免 Android force-dark 与自定义深色板叠加。

---

## 6. 系统主题与原生层（Android / iOS / 鸿蒙同期）

三端行为必须一致：跟随系统实时变；锁定浅/深不受系统影响；冷启动无白闪；页面内切换后状态栏/窗口底立刻跟上。

### 6.1 页面初值 `isNightMode`

宿主在创建 Pager 前写入当前**系统**深浅（不是用户偏好解析结果）。`BasePager.isNightMode()` 只把这个值当作 `systemDark`。

| 平台 | 系统深浅来源 |
| --- | --- |
| Android | `uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES` |
| iOS | `traitCollection.userInterfaceStyle == UIUserInterfaceStyleDark` |
| 鸿蒙 | `Configuration.colorMode == COLOR_MODE_DARK` |

### 6.2 系统切换监听

只更新 `systemDark`，不得覆盖用户 LIGHT / DARK。去重要避免重复事件。

| 平台 | 监听 | 下发 |
| --- | --- | --- |
| Android | `configChanges="uiMode"` + `onConfigurationChanged` | `delegator.sendEvent("themeDidChanged", isNightMode)` |
| iOS | `traitCollectionDidChange`（userInterfaceStyle 变化） | `delegator sendWithEvent:@"themeDidChanged"` |
| 鸿蒙 | Ability `onConfigurationUpdate` / 窗口 colorMode | `KuiklyViewDelegate.sendEvent("themeDidChanged", …)` |

### 6.3 防白闪

在首帧 Kuikly 内容上屏前，按「偏好 + 系统」解析 `isDark`，把窗口/容器刷成 `#F5F6F7` / `#151517`，状态栏图标深浅与之相反。

偏好存储与 Kuikly `SharedPreferencesModule` 同源：

| 平台 | 读取位置 |
| --- | --- |
| Android | `getSharedPreferences("KRSharedPreferencesModule").getString("theme_preference")` |
| iOS | `[NSUserDefaults standardUserDefaults] objectForKey:@"theme_preference"` |
| 鸿蒙 | `{filesDir}/KRSharedPreferencesModule` XML 中 `theme_preference` |

Android 额外：`activity_hr.xml` 不得写死白底；`hr_loading` 保持透明以免盖住 Kuikly。

### 6.4 `DshThemeModule`

- Kuikly：`applyNativeChrome(isDark: Boolean)`，`moduleName = "DshThemeModule"`。
- Android：`KRDshThemeModule`，`moduleExport` 注册；刷窗口、容器、状态栏/导航栏图标。
- iOS：类名必须是 `DshThemeModule`（运行时按类名创建）；刷 `view` / `window` 背景与 `preferredStatusBarStyle`。
- 鸿蒙：`getCustomRenderModuleCreatorRegisterMap` 注册同名 Module；刷 `window` 背景与 `setWindowSystemBarProperties`。

### 6.5 验收（三端都要跑）

连接向导 + 首页外观弹窗至少覆盖：三模式立即变色、杀进程保持、跟随系统随系统切换、锁定浅色后系统变深 App 仍浅、冷启动无白闪。会话内 Markdown / 工具卡等在已配对设备上目视。

---

## 7. 设置入口 UI

### 7.1 位置

侧边抽屉 `DshSessionDrawer`，在"新会话"之下新增"外观"行，原"设置"改名为"连接设置"：

```
[Logo]                    [×]
┌ 新会话 ─────────────────┐
┌ 外观 ───────────────────┐   ← 新增，打开 DshAppearanceModal
┌ 连接设置 ───────────────┐   ← 原"设置"，行为不变
会话
  · ...
```

不放在：连接设置 Modal 内（职责混淆）、连接向导页（首屏聚焦配对）、顶栏（拥挤）、输入区 `sliders` 图标（当前无事件、语义不符）。

### 7.2 `DshAppearanceModal`

沿用现有 `Modal(inWindow = true)` + 居中卡片 + 遮罩模式：

```
┌─────────────────────────────────┐
│  外观                        ×  │
│                                 │
│  主题模式                        │
│  ┌───────────────────────────┐  │
│  │ ● 跟随系统                 │  │
│  │   当前：深色               │  │  ← 仅 SYSTEM 时显示解析结果
│  │ ○ 浅色                     │  │
│  │ ○ 深色                     │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
```

交互：

- 单选即生效，无保存按钮；点选后 Modal 不关闭，允许连续试三种。
- 选中项：`surfaceVariant` 底 + `primary` 圆点；未选中：透明底 + `divider` 圆环。
- 跟随系统时副文案显示"当前：浅色 / 深色"。
- 点遮罩或 × 关闭。
- 持久化失败时不阻断，Toast 提示"设置未能保存，下次启动可能恢复默认"。

挂载位置：`DshHomePage` 中与 `DshConnectionSettingsModal` 同级：

```kotlin
vif({ ctx.appearanceVisible }) {
    DshAppearanceModal(
        preference = { ctx.theme.state.preference },
        resolvedDark = { ctx.theme.isDark },
        onSelect = { ctx.setThemePreference(it) },
        onClose = { ctx.appearanceVisible = false },
    )
}
```

---

## 8. 持久化与容错

| 情形 | 行为 |
| --- | --- |
| 无值 | `SYSTEM` |
| 非法值 | `SYSTEM`，记录 Warn |
| 读取异常 | `SYSTEM`，记录 Warn |
| 写入失败 | 本次切换仍立即生效，记录 Error，UI 提示 |

存储键 `theme_preference`，值 `"system" | "light" | "dark"`。

---

## 9. 非目标（本期明确不做）

- 第三方主题 / token override 插件。
- 与电脑端 `settings.yaml` 同步。
- `configColor.ini` / Android 换肤文件。
- `RouterPage`、`ImageAdapterBenchmarks`。
- 视觉回归截图基建（以手动核对清单替代）。
- H5 / 小程序宿主（本期无对应 App 工程）。

## 10. 加分项设计预留

| 加分项 | 预留点 |
| --- | --- |
| 独立代码主题 | `CodeTheme` 枚举、`DshMarkdown.darkMode` 改为独立解析、外观 Modal 增加"代码主题"行 |
| 日出日落 | `DshThemePreference` 增加 `AUTO`，`systemDark` 来源改为时间 / 位置计算 |
| 高对比度 | `DshThemeTokens` 增加 `highContrast` 变体，在 LIGHT / DARK 上叠加 |
