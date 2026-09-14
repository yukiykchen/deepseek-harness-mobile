# DeepSeek Harness Mobile

将 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 接到 Android / iOS 手机的实验性移动端宿主项目。

**本仓库只连接电脑上的 DSH**，不再内嵌 Node.js / Harness，APK 不再携带约 115 MB 的 `payload.zip`。

App 启动后先选连接方式，再进入聊天。**扫码连电脑的完整启动命令见 [怎么启动（扫码连电脑）](#怎么启动扫码连电脑)。**

装好之后怎么用（连接、主题、复制导出、图片、会话管理、插件、日志）见 **[docs/user-guide.md](docs/user-guide.md)**。

文档索引：[用户指南](docs/user-guide.md) · [App ↔ Host 协议](docs/app-host-protocol.md) · [设计说明](docs/design.md) · [需求对照清单](docs/requirements-checklist.md) · [演示录制脚本](docs/demo-runsheet.md)

- **扫码连接**：扫描电脑 DSH Settings 里的二维码，经 Relay 访问电脑上的 Harness。
- **SSH**：用本机端口转发连到电脑上的 DSH。

若要在手机上跑内嵌 Agent，请使用独立仓库 **[DSH Local](https://github.com/yukiykchen/deepseek-harness-local)**（`com.example.dsh.local`，需 Shizuku 与 `payload.zip`）。两个 App 可以同时安装。

本项目的目标不是把 DeepSeek Harness 翻译成 Kotlin，而是用 Kotlin/Kuikly 做宿主界面，连到电脑上的 Host。

> 当前项目处于开发和验证阶段。请不要把它当作生产版本使用。

## 项目定位

DeepSeek Harness 本身是一个插件化 Agent 运行时。本仓库提供 Android / iOS 宿主：

- 使用 Kuikly/Kotlin Multiplatform 实现主要 UI 和跨平台协议层；
- 扫码 / SSH / 直连模式用 HTTP RPC + WebSocket（`/api/remote.mux`），对接 DSH 0.1.5 的浏览器会话登录（cookie）；
- 按连接模式隔离会话列表和消息缓存；
- 通过扫码 Relay 或 SSH 隧道连接电脑上的 DSH Host。

## 连接模式

启动后首页是「连接 DSH」。

| 模式 | App 文案 | Agent 跑在哪 | 传输 | API Key 配在哪 | 会话缓存 |
| --- | --- | --- | --- | --- | --- |
| 远程 Relay | 扫码连接 | 电脑 DSH | Relay sealed tunnel + 本机 loopback WebSocket | 电脑 | `relay:<hostId>` |
| 远程 SSH | SSH | 电脑 DSH | SSH 本地转发 + WebSocket | 电脑 | `ssh:default` |
| 手机本地 | （独立 App） | 见 DSH Local | 本机 HTTP + SSE | 手机 | `local` |

App 连上 Host 之后的 JSON-RPC、事件流和会话时间线见 **[docs/app-host-protocol.md](docs/app-host-protocol.md)**；
消息模型、流式状态机、附件协议和导出格式见 **[docs/design.md](docs/design.md)**。

两种远程模式互不影响。从扫码切到 SSH 后，看不到另一套缓存，这是预期行为。

- 电脑已经在跑 DSH，手机和电脑同一 Wi-Fi / 热点：选 **扫码连接**。启动命令见上方「怎么启动（扫码连电脑）」。
- 已有 SSH 私钥，或不想在电脑上跑 Relay：选 **SSH**。

扫码连接和 SSH 支持 Android 与 iOS。iOS 首次扫码会申请相机权限，首次 SSH 会要求确认主机指纹。

## 怎么启动（扫码连电脑）

最终要**同时开着三样**：电脑上的 Relay、电脑上的 DSH、手机上的 App。建议开两个电脑终端，都不要关。

**终端 1 — Relay（默认 `127.0.0.1:8787`）**

```bash
git clone https://github.com/yukiykchen/dsh-scan-remote.git
cd dsh-scan-remote/relay
cp .env.example .env
npm ci
npm run build
HOST=127.0.0.1 PORT=8787 npm start
```

另开窗口确认还活着：

```bash
curl http://127.0.0.1:8787/health
```

**终端 2 — 安装插件并启动 DSH（默认 `127.0.0.1:3080`）**

```bash
npx @deepseek-ai/dsh plugin --profile web add "github:yukiykchen/dsh-scan-remote#v0.0.1"

ipconfig getifaddr en0    # macOS Wi-Fi；Linux 用 ip addr / hostname -I
export PUBLIC_RELAY_URL=http://192.168.1.10:8787   # 换成上一步的电脑 LAN IP，手机必须能打开
npx @deepseek-ai/dsh web
```

浏览器打开本机 DSH，进入 **Settings > Remote Access**，应看到二维码。

**手机 — 安装并打开 App**

Android 试用包从 [GitHub Releases](https://github.com/yukiykchen/deepseek-harness-mobile/releases) 下载 `dsh-mobile-*.apk`，在系统设置里允许未知来源后安装。给仓库打 `v*` tag（例如 `v0.0.1`）会由 GitHub Actions 自动打 Release APK。

也可以自己编：

```bash
git clone https://github.com/yukiykchen/deepseek-harness-mobile.git
cd deepseek-harness-mobile
./gradlew :androidApp:installDebug
```

或用 Android Studio 打开本仓库，运行 `androidApp`。打开 App → **扫码连接** → **扫描电脑二维码** → **连接已配对电脑**。

DeepSeek API Key 配在电脑端 DSH，不要配在手机里。更细的说明、换网络、SSH 见下方各节。

## 工作原理

### 扫码连接

```text
电脑 DSH :3080 (127.0.0.1)
        ^
        | 本机回环
dsh-scan-remote 插件 --WSS--> Relay :8787 <--WSS-- 手机 App
                                     ^
                                     |
                          二维码里的 publicRelayUrl
                          必须是手机能访问的电脑 IP
```

手机配对成功后，在本机拉起一个 loopback WebSocket 网关，聊天协议与远程 Host 对齐。

### SSH

```text
Android App -- HTTP/WS --> 127.0.0.1:<本地转发端口>
                              |
                         SSH tunnel
                              |
                         电脑 127.0.0.1:3080
```

## 使用前置条件

### 1. DeepSeek API Key

**扫码连接 / SSH**：Key 配在电脑端 DSH Host。

请注意：

- 不要把真实 API Key 写进 Git、截图或公开 Issue；
- API Key 仍然用于访问 DeepSeek 在线模型，会产生网络流量和模型调用费用。

### 2. 扫码连接需要电脑上的 Relay 和插件

扫码模式不把 DSH 的 `3080` 端口暴露到公网。电脑上要额外跑一个 Relay（默认 `127.0.0.1:8787`），并给 DSH `web` profile 安装 `dsh-scan-remote`。下载、安装和启动步骤见下方「通过扫码连接」。

电脑侧需要：

- Node.js 18+（Relay 目录声明 `22.x`，用当前 LTS 即可）；
- 已能在本机启动 `dsh web`（`npx @deepseek-ai/dsh` 或 Harness 仓库里的 `pnpm dsh web`）；
- 手机和电脑在同一可互通网段（同一 Wi-Fi 或同一热点）。

### 3. Android 开发环境

建议使用：

- Android Studio；
- Android SDK、Platform 34 和对应的 Build Tools；
- JDK 17 或 Android Studio 自带的 JBR；
- 一台 Android 7.0（API 24）或更高版本的设备；
- 已开启开发者选项和 USB 调试，或已配置无线调试。

项目当前 Android 配置为：

```text
compileSdk = 34
minSdk     = 24
targetSdk  = 28
```

### 4. iOS 开发环境

建议使用：

- Xcode 15+（部署目标 iOS 14.1）；
- CocoaPods（`iosApp` 目录执行 `pod install`）；
- 一台 iOS 14.1 或更高版本的真机（扫码需要相机，SSH / 局域网 Relay 需要本机网络权限）。

用 Xcode 打开 `iosApp/iosApp.xcworkspace`（不要只开 `xcodeproj`），选择 `iosApp` target 在真机上运行。NMSSH 自带的 OpenSSL 静态库是 iOS 真机切片，模拟器目前编不过 SSH 依赖；扫码也需要真机相机。

## 获取源码

```bash
git clone git@github.com:yukiykchen/deepseek-harness-mobile.git
cd deepseek-harness-mobile
```

如果使用 HTTPS：

```bash
git clone https://github.com/yukiykchen/deepseek-harness-mobile.git
cd deepseek-harness-mobile
```

## 使用 Android Studio 启动

1. 用 Android Studio 打开仓库根目录。
2. 等待 Gradle Sync 完成，并确认 Android SDK、JDK 和依赖下载没有错误。
3. 连接 Android 手机，确认 `adb devices` 能看到设备。
4. 在 Android Studio 的运行配置中选择 `androidApp` 模块和目标手机。
5. 点击 Run，等待 APK 安装并启动。
6. 启动后先出现「连接 DSH」，选择扫码或 SSH。
7. API Key 使用电脑上已配置的 Key。
8. 创建会话并发送第一条消息。

## 通过扫码连接电脑上的 DSH Host

扫码模式走 [dsh-scan-remote](https://github.com/yukiykchen/dsh-scan-remote) 插件和本机 Relay。电脑上的 Harness 仍只监听 `127.0.0.1:3080`；手机和插件都出站连 Relay。更细的协议说明见[插件中文 README](https://github.com/yukiykchen/dsh-scan-remote/blob/master/README.zh-CN.md)。

两件不要混：

- `relay` / `DSH_RELAY`：插件连 Relay 用的地址，电脑本机一般是 `http://127.0.0.1:8787`。
- `publicRelayUrl` / `PUBLIC_RELAY_URL`：写进二维码、给手机用的地址，必须是手机能打开的电脑 IP，例如 `http://192.168.1.10:8787`。

先把手机和电脑连到**同一个 Wi-Fi 或同一个热点**。

### 1. 下载并启动 Relay

Relay 在插件仓库的 `relay/` 目录，没有单独的安装包。先克隆仓库：

```bash
git clone https://github.com/yukiykchen/dsh-scan-remote.git
cd dsh-scan-remote/relay
```

用 npm 启动（需要 Node.js）：

```bash
cp .env.example .env
npm ci
npm run build
HOST=127.0.0.1 PORT=8787 npm start
```

或用 Docker（仍在 `relay/` 目录）：

```bash
docker compose up --build
```

确认本机健康检查通过：

```bash
curl http://127.0.0.1:8787/health
```

Relay 要一直开着。关掉后手机扫码和隧道都会断。

### 2. 给 DSH 安装扫码插件

另开一个终端（Relay 那个窗口不要停）：

```bash
npx @deepseek-ai/dsh plugin --profile web add "github:yukiykchen/dsh-scan-remote#v0.0.1"
```

如果已经装过，可以跳过这一步。Settings 里没有 **Remote Access** 时，多半是插件没装到 `web` profile，或 Host 不是用这个 profile 启动的。

### 3. 写入手机能访问的电脑地址并启动 DSH

看电脑当前局域网 IP（手机必须能 ping 通这一台）：

```bash
ipconfig getifaddr en0    # macOS Wi-Fi
# 或 ifconfig / ip addr
```

把这个地址写进二维码 origin 后启动 Host：

```bash
export PUBLIC_RELAY_URL=http://192.168.1.10:8787   # 换成上一步看到的电脑 LAN IP
npx @deepseek-ai/dsh web
```

如果本机是从 DeepSeek Harness 仓库开发，也可以用仓库里的 `pnpm dsh web`。已经用过插件时，可以直接改 `~/.dsh-scan-remote/config.json` 的 `publicRelayUrl`，再重启 DSH。

打开浏览器里的 DSH：**Settings > Remote Access**，应看到二维码、倒计时和电脑名。确认码里的地址是当前网段，而不是上一张网的 IP。

### 4. 手机扫码

1. 打开 Android 或 iOS App，选 **扫码连接**。
2. 点 **扫描电脑二维码**，对准 Settings 页的码。
3. 配对成功后点 **连接已配对电脑**。

首版只保存一台电脑。换电脑先点「移除这台电脑」，再扫新码。

二维码里的 origin 在生成时写死。电脑换 Wi-Fi、改连热点、或从公司网切到另一网段后，必须改 `publicRelayUrl`、重启 DSH、再扫**新码**。只把手机和电脑连到同一个 Wi-Fi 还不够，如果码里仍是旧 IP，手机照样超时。

## 通过 SSH 连接电脑上的 DSH Host

SSH 模式只建立本地端口转发，不通过 SSH 执行远程命令，也不需要把 DSH 端口暴露到公网。

电脑端先启动 DSH，并保持回环监听（SSH 模式不需要 Relay）：

```bash
npx @deepseek-ai/dsh web --port 3080
```

推荐为手机使用的 SSH 用户配置 Ed25519 公钥：

```bash
ssh-keygen -t ed25519
ssh-copy-id your-user@your-computer
```

Windows OpenSSH 用户可以把 `.pub` 文件内容追加到 `%USERPROFILE%\\.ssh\\authorized_keys`。手机和电脑不在同一局域网时，可以让两台设备加入同一个 Tailscale 或 ZeroTier 网络，然后在 App 中填写虚拟地址，例如 `100.86.12.34`。

在 App 中填写 SSH 主机、SSH 端口、用户名和远程 DSH 端口，然后导入私钥。首次连接会展示 SSH 主机指纹；确认后会用于后续校验，指纹变化时需要重新确认。

SSH 模式下 API Key 应配置在电脑端 DSH Host 中。扫码和 SSH 的会话缓存彼此隔离，远程 Host 是远程会话的最终数据来源。

App 支持用户主动开启 Android 前台服务来保持后台 SSH 隧道，但 Android 可能限制长时间后台服务。网络切换或系统回收后，App 会尝试重连，并通过会话历史恢复已被远程 Host 接受的任务。

## 使用 Gradle 命令行构建和安装

在仓库根目录执行：

```bash
./gradlew :androidApp:assembleDebug
```

生成的 APK 位于：

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

连接手机后可以直接安装：

```bash
./gradlew :androidApp:installDebug
```

或者使用 ADB：

```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

## 首次启动流程

App 先打开「连接 DSH」，不会启动内嵌内核。

**扫码连接**进入首页后：恢复 Relay 配对、经 Relay 建隧道、通过插件获取 `dsh web` 登录 token 换取 cookie，再在本机 loopback 上连远程 `/api/remote.mux`（WebSocket）。

**SSH** 进入首页后：建立本地端口转发，再按远程 Host 协议拉会话。

## 当前支持的能力

当前首页主要提供一个移动端 Harness Chat 界面。逐项使用说明见 **[docs/user-guide.md](docs/user-guide.md)**。

对话本身：

- 创建和切换会话；
- 恢复会话历史；
- 查询可用模型并切换模型；
- Markdown 消息渲染（标题、列表、引用、表格、链接、行内代码、代码块高亮）；
- LaTeX 公式渲染：行内 `$…$` 就地转成 Unicode，独立成段的 `$$…$$` 单独成行（分数、根号、上下标、希腊字母、求和、矩阵）；分块到达的公式在闭合前保持纯文本，无法渲染的公式回退为源码，可单独复制公式源码；
- 流式回答，逐块渲染，已完成的块不再重排；
- 取消当前请求；
- 展示工具调用事件，可展开输出、终端/JSON 列表和文件 diff；
- 工具审批和 Agent 提问的应答界面；
- 排队消息（编辑 / 删除 / 转向）；
- 本地 SQLite 会话和消息缓存。

界面与功能扩展：

- **主题**：浅色 / 深色 / 跟随系统 / 日出日落 / 高对比度，工具输出可用独立的代码主题；即时生效并持久化。日出日落按设备时钟和时区估算，不申请定位权限；
- **选择、复制与导出**：长按消息可局部选择、整条复制、单独复制某个代码块，多选若干条一起导出，或把整个会话导出为 Markdown、HTML，或经系统打印面板「另存为 PDF」；
- **图片附件**：相册多选与拍照，发送前预览和移除，按 Host 的 `imageLimits` 预校验并给出可读原因，气泡内缩略图与全屏查看，重开会话按 `attachmentId` 还原；
- **会话管理**：重命名（`session.rename`）、归档（`workspace.archiveSession`，需确认且说明只是隐藏）、独立的归档列表；
- **插件列表**：读取 Host 的 `pluginInventory/list`，支持按名称搜索、按状态筛选、查看失败原因，只读因此不提供启停按钮；
- **日志中心**：连接、RPC、mux/host 帧和会话事件的结构化记录，支持等级 / 会话 / 时间 / 类型 / 关键词筛选、详情查看、复制、脱敏导出和本地清除。

## 目录结构

```text
androidApp/
  src/main/java/.../relay/          # 扫码、sealed tunnel、本机 loopback 网关
  src/main/java/.../ssh/            # SSH 转发
  src/main/java/.../module/         # Android/Kuikly 原生模块

shared/
  src/commonMain/.../dsh/            # 跨平台 UI、协议、模型和本地存储接口
  src/androidMain/.../dsh/           # Android SQLite 存储实现
  src/iosMain/.../dsh/               # iOS 存储实现
  src/ohosMain/.../dsh/              # OpenHarmony 存储实现

iosApp/                              # iOS 宿主工程
ohosApp/                             # OpenHarmony 宿主工程
```

比较重要的文件：

- [`DshConnectionSetupPage.kt`](shared/src/commonMain/kotlin/com/example/dsh/dsh/DshConnectionSetupPage.kt)：启动时选择扫码 / SSH；
- [`DshRelayManager.kt`](androidApp/src/main/java/com/example/dsh/relay/DshRelayManager.kt)：扫码配对、sealed tunnel 和本机 loopback 网关；
- [`DshHostProtocol.kt`](shared/src/commonMain/kotlin/com/example/dsh/dsh/DshHostProtocol.kt)：App 与 Host 的 RPC 和事件协议；
- [`DshHomePage.kt`](shared/src/commonMain/kotlin/com/example/dsh/dsh/DshHomePage.kt)：聊天、会话、输入框和模型配置；
- [`DshLocalStore.android.kt`](shared/src/androidMain/kotlin/com/example/dsh/dsh/DshLocalStore.android.kt)：按连接 scope 隔离的本地数据库。

## 网络和通信说明

远程模式最终都把 Host 看成「本机可访问的 DSH HTTP API」。方法表、信封和 mux/host 帧见 **[docs/app-host-protocol.md](docs/app-host-protocol.md)**。

**扫码连接**：手机经 Relay 与电脑插件建 sealed tunnel，再在手机 `127.0.0.1:<loopback>` 上露出 Host。事件流是 WebSocket，不是 SSE。二维码 origin 必须是手机能打开的电脑地址；插件连 Relay 仍用电脑本机 `127.0.0.1:8787`。

**SSH**：`127.0.0.1` 是手机上的 SSH 本地转发端点，实际流量到电脑 `127.0.0.1:3080`。远程模式使用 HTTP RPC + WebSocket，DSH API 路径不变。

如果事件流断开，客户端仍会尝试用会话历史恢复结果。

## 故障排查

### 扫码后连不上电脑

1. 电脑和手机是否在同一可互通网段，而不是只「看起来连了 Wi-Fi」；
2. 二维码 / `publicRelayUrl` 是否仍是上一张网的 IP；
3. 改完配置后是否重启了 `dsh web`，以及是否重新扫了新码；
4. 电脑上 Relay 是否还在 `8787` 监听；
5. 电脑防火墙是否拦截了来自手机的 `8787`。

### API Key 配置成功但无法回答

请检查：

- API Key 是否有效，且已配在电脑端 DSH；
- 手机是否可以访问 Relay / SSH 主机；
- 当前选择的模型是否可用；
- 手机时间是否正确，避免 TLS 或鉴权异常。

## 不接电脑 DSH 的开发与验收：mock Host

`tools/mock-host` 是一个用 Node 写的回放式 Host，说的是和真 Host 完全一样的 0.1.5 协议：cookie 登录、
`/api/remote.mux` 的逻辑流、一元 RPC 的 `client-request` 信封。不需要 API Key，也不需要 Relay。

```bash
export PATH="/opt/homebrew/opt/node@22/bin:$PATH"   # 需要 Node 22
cd tools/mock-host && npm install
node src/server.js --host 0.0.0.0 --port 3080 --token dev-token
npm test                                            # 5 个端到端子测试
```

App 侧选 **直连**，地址填 `http://10.0.2.2:3080`（模拟器）或电脑的局域网 IP（真机）。

发什么话触发什么剧本由关键字决定：默认 markdown，另有 `latex`、`tool`、`image`、`fail`、`approve`、
`ask`、`long`。剧本在 `fixtures/scenarios/`，会话种子在 `fixtures/seed-sessions.json`（含一个已归档会话），
插件清单、模型目录和 `imageLimits` 也都在 `fixtures/` 下，直接改就能造场景。

录演示视频的分镜和踩坑见 **[docs/demo-runsheet.md](docs/demo-runsheet.md)**。

## 开发说明

修改 Kotlin 或 Kuikly 代码后，重新执行：

```bash
./gradlew :androidApp:assembleDebug
```

iOS 在 `iosApp` 执行 `pod install` 后，用 Xcode 打开 `iosApp.xcworkspace` 编译。

## 当前限制

- 当前主要验证 Android / iOS 移动端宿主流程；
- 模型推理仍然依赖 DeepSeek 在线 API，不是完全离线模型；
- 扫码二维码绑定电脑当前局域网 IP，换网络后必须改 `publicRelayUrl`、重启 DSH 并重新扫码；
- Android 后台进程可能被系统或厂商策略回收；iOS 在后台时系统可能暂停本机 loopback / SSH 转发。

## 许可证和上游项目

本项目是移动端集成和实验性宿主代码。DeepSeek Harness 的许可证、第三方依赖和使用限制请以其上游仓库为准：

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
- [DeepSeek Harness 中文说明](https://github.com/deepseek-ai/deepseek-harness/blob/main/README.zh.md)
