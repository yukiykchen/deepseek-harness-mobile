# 代码质量改进计划

> 最新复核：[2026-11-10 第二轮审查](code-quality-review-2026-11-10-round2.md)。当前代码已复现 SQLite 自等待、iOS 私钥校验错误、凭据 busy 卡住、OHOS WebSocket 泄漏，并确认 OHOS 生产 sharedLib 任务缺失。本文后面的“已完成”是上一轮实施自报，不能当当前验收结论；未解决项以第二轮 R01-R13 / D01-D04 为准。

审查日期：2026-11-10

审查范围：`shared`、`androidApp`、`iosApp`、`ohosApp`、构建脚本、测试脚本和发布配置。

明确不在本轮范围：Android 16 KB 原生库兼容。该问题已记录在 [code-quality-review.md](code-quality-review.md)，本文件不重复展开。

## 结论

当前代码已经完成了包名迁移、共享层分层和超长文件拆分。`verifySharedArchitecture`、共享单元测试、Android 单元测试和 Android Lint 均能通过，手写源码文件也都不超过 1000 行。

但“文件不超过 1000 行”只说明体积受控，不代表质量已经足够。现阶段最需要处理的是：

1. 发布包会输出用户提示词、模型回复和交互答案等敏感日志。
2. API Key 写入固定使用 `DEEPSEEK_API_KEY`，没有使用 Host 实际配置的凭据引用。
3. Host 事件在内存中无上限保留，并在每次插入后整体排序。
4. SQLite 同步接口会让调用线程用 `runBlocking` 等待数据库队列。
5. 预览签名私钥和密码公开，Release 也使用这套签名。

这些问题应先处理。页面职责、模板残留、平台一致性和测试覆盖属于第二阶段工作。

> 注意：以下 P1/P2/P3 清单是 2026-11-10 首次审查时的历史证据，行号和源码引用保留用于追溯。当前完成状态以本文件末尾的「实施状态」和「后续实施状态」为准。

## 优先级定义

| 级别 | 含义 |
| --- | --- |
| P1 | 涉及隐私、凭据、发布安全、明显功能错误或可能引发卡顿的资源问题，应优先处理 |
| P2 | 会持续降低可维护性、跨平台一致性或回归可信度，应在下一轮重构中处理 |
| P3 | 代码风格、工具链和设计系统问题，可随相关功能逐步清理 |

## P1：优先处理

### P1-1 敏感日志进入发布包

**证据**

- [DshHostClient.kt:544](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt#L544) 记录提示词预览。
- [DshHostClient.kt:610](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt#L610) 记录每个 Host 帧的 payload 预览。
- [DshHostClient.kt:697](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt#L697) 记录模型增量文本。
- [DshHostConnection.kt:192](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostConnection.kt#L192) 记录问答回复请求体，[DshHostConnection.kt:211](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostConnection.kt#L211) 记录响应体。
- [DshMarkdownStreamLog.kt:11](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/framework/DshMarkdownStreamLog.kt#L11) 直接调用 `KLog.i`。
- Android 的 [KRLogAdapter.kt:11](../androidApp/src/main/java/io/github/yukiykchen/dsh/adapter/KRLogAdapter.kt#L11) 无条件转发到 Logcat；OHOS 的 [napi_init.cpp:24](../ohosApp/entry/src/main/cpp/napi_init.cpp#L24) 以 `%{public}s` 输出。

**影响**

用户输入、模型输出、审批命令、问答答案和工具结果可能出现在设备日志、Bug Report 或厂商诊断包中。当前只对少量凭据做了脱敏，无法覆盖对话内容。

**改进**

1. 建立统一的 `DshDiagnostics` 或日志策略接口，按 `debug`、`release` 和用户显式开启的诊断模式控制输出。
2. Release 默认只记录事件类型、长度、序号、耗时和稳定错误码，不记录 prompt、delta、payload、body、URL 查询参数或文件路径。
3. 将 `DshStreamLog.preview()` 改名并限制为调试专用，避免业务代码把正文当普通日志使用。
4. Android、iOS、OHOS 的日志适配层统一过滤或降级；OHOS 默认不要使用 `%{public}s` 输出业务正文。

**验收**

- Release 构建运行一轮发送、流式回复、工具调用、审批和问答后，系统日志中找不到测试 prompt、回复片段和问答答案。
- 增加日志策略单元测试，覆盖 prompt、delta、payload、token 和长文本截断。

### P1-2 API Key 写入目标与 Host 配置不一致

**证据**

- [DshHostClient.kt:152](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt#L152) 能从 `settings.describe` 读取 `apiKeyEnv`，并判断凭据是否可写。
- 但 `loadCredentialSetup` 当前没有调用方。
- [DshHostClient.kt:203](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt#L203) 保存时固定写入 `DEEPSEEK_CREDENTIAL_REF`。
- 协议文档 [app-host-protocol.md:93](app-host-protocol.md#L93) 也说明凭据引用来自 `apiKeyEnv`。

**影响**

Host 将 `apiKeyEnv` 配置为其他名称时，App 会把 Key 写到错误位置，界面却显示保存成功。不可写的凭据也不会在打开弹窗时提前提示。

**改进**

1. 打开凭据弹窗时调用 `loadCredentialSetup`，保存返回的 `credentialRef`、`configured` 和 `writable`。
2. `saveApiKey(ref, value)` 显式接收凭据引用，不再隐式使用默认常量。
3. Provider 未启用或凭据不可写时禁用保存，并给出明确原因。
4. 打开、加载、保存过程继续使用 `DshControllerRequestScope`，防止旧请求覆盖新状态。

**验收**

- `apiKeyEnv = CUSTOM_DEEPSEEK_KEY` 时，`credentials.set` 的 `ref` 为 `CUSTOM_DEEPSEEK_KEY`。
- Provider 未启用、namespace 缺失、凭据不可写、加载失败和保存失败均有测试。

### P1-3 Host 事件无上限保留，插入成本随会话增长

**证据**

- [DshModels.kt:132](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/model/DshModels.kt#L132) 为每个会话保存全部原始事件字符串。
- [DshModels.kt:201](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/model/DshModels.kt#L201) 每次插入都先线性查重，再对整个列表排序。
- [DshHostStoreQueries.kt:164](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostStoreQueries.kt#L164) 实际只在审批卡片中反查 `tool/call` 命令。
- 删除会话时，[DshHostClient.kt:779](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt#L779) 没有清理 `sessionLastSeq` 和该会话的 pending interaction。

**影响**

长会话和大量工具输出会持续占用内存，流式事件越多，单次插入越慢。删除会话后仍可能残留状态。

**改进**

1. 用 `callId -> approvalCommand` 的小索引替代全量原始事件列表。
2. 若确实需要事件窗口，按会话设置条数和字节上限，并使用按 `seq` 有序的结构。
3. 删除会话时统一调用 `store.removeSession(id)`，清理会话、序号、投影、队列、任务和待处理交互。
4. 只在需要渲染时解析 JSON，避免同一个 payload 反复 `toString()` 和重新解析。

**验收**

- 构造 10,000 个事件的会话，内存中保留的事件数量和字节数符合上限。
- 审批命令仍能从对应 `callId` 正确显示。
- 会话删除后，Store 中不存在该会话的任何状态。

### P1-4 SQLite 同步接口阻塞调用线程

**证据**

- [DshDatabaseQueue.kt:35](../shared/src/sqliteMain/kotlin/io/github/yukiykchen/dsh/data/DshDatabaseQueue.kt#L35) 的 `call()` 使用 `runBlocking` 等待队列结果。
- 首页在连接和创建会话时同步写会话索引，例如 [DshHomePage.kt:429](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L429) 和 [DshHomePage.kt:576](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L576)。
- 首页启动时同步读取缓存，见 [DshHomePage.kt:665](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L665)。

**影响**

当队列中已有较大的消息快照写入时，页面线程会等待前序任务完成。用户可能看到启动、切换会话或新建会话卡顿。许多失败又被 `runCatching` 吞掉，导致缓存与界面状态不一致时难以诊断。

**改进**

1. 将 `DshLocalStore` 拆成异步读写接口，或提供明确的 `suspend` / callback API。
2. 页面线程只发起请求，结果再切回 Kuikly 页面线程应用。
3. 会话索引写入失败要进入统一的缓存错误状态，不能完全静默。
4. 保留 FIFO 语义，但区分必须同步完成的关闭流程与普通读写。

**验收**

- 在数据库队列中注入慢写任务时，首页启动、切换会话和新建会话不阻塞页面线程。
- 并发读写仍保持提交顺序，关闭时仍会排空已提交写入。

### P1-5 Release 签名和发布安全配置过宽

**证据**

- [androidApp/build.gradle.kts:35](../androidApp/build.gradle.kts#L35) 读取仓库中的 `keystore/preview.jks`，并在构建脚本中写死密码。
- [androidApp/build.gradle.kts:47](../androidApp/build.gradle.kts#L47) 的 Release 构建使用这套签名，且 `isMinifyEnabled = false`。
- [keystore/README.md](../keystore/README.md) 明确说明密码公开。
- [network_security_config.xml:3](../androidApp/src/main/res/xml/network_security_config.xml#L3) 全局允许明文 HTTP。

**影响**

任何人都可以使用同一私钥签出相同包名的 APK。对侧载用户来说，这会降低更新来源的可信度。全局明文流量也会扩大非预期 HTTP 请求的暴露面。

**改进**

1. 预览包改用 CI Secret 中的签名，或明确改成每次构建都不可升级的测试包身份。
2. 公开私钥不得用于任何会分发给真实用户的 Release。
3. 评估 Release 混淆和资源压缩，至少开启 R8 并补齐必要的 keep 规则。
4. 明文 HTTP 只服务于 Relay 局域网配对和本机 loopback；其他 HTTP 请求在代码层拒绝，并保留安全测试。

**验收**

- 仓库中不再包含可用于正式分发的私钥和密码。
- Release 构建、安装、扫码、SSH 和对话流程在开启 R8 后通过回归测试。

## P2：下一轮重构

### P2-1 首页和 Host 客户端仍承担过多职责

**证据**

- [DshHomePage.kt](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt) 912 行，从第 [70 行](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L70) 开始持有会话、消息、键盘、抽屉、语音、请求世代和十余个 controller。
- `body()`、`homeConversation()`、`createHostClient()` 主要在搬运大量 lambda，见 [DshHomePage.kt:274](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L274)、[DshHomePage.kt:337](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L337) 和 [DshHomePage.kt:476](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L476)。
- [DshHostClient.kt](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt) 800 行，同时负责 RPC 封装、响应解析、流式回合、事件分发和 Host Store 更新。
- [DshStreamingTurnController.kt:66](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshStreamingTurnController.kt#L66) 将多个内部状态公开为可变属性。

**改进**

1. 首页拆出 `HomeSessionCoordinator`，统一管理会话列表、当前会话、缓存恢复、会话选择和创建。
2. Host 客户端拆成 `HostRpcApi`、`HostEventReducer` 和 `PromptStreamRegistry`，构造函数中不直接启动连接。
3. 用不可变 `HomeUiState` 和明确事件替代大批 getter/setter lambda。
4. 流式回合内部状态改为私有，只暴露渲染所需的只读状态和命令。

**验收**

- 首页类只负责生命周期、视图组装和事件转发，目标控制在 400 行以内。
- Host RPC 解析、事件归约和流式状态机均有独立单元测试。

### P2-2 本地消息 ID 可能重复

**证据**

- [DshHomePage.kt:849](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L849) 使用 `user-${messages.size}` 和 `assistant-${messages.size}`。
- [DshStreamingTurnController.kt:162](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshStreamingTurnController.kt#L162) 会删除空的流式行，列表长度可能回退。
- 滚动 ref、Markdown 实例和 SQLite 主键都依赖消息 ID。

**影响**

删除空行或历史重排后，新消息可能复用仍在列表中的 ID，造成行绑定、滚动目标或缓存写入错误。

**改进**

使用会话内单调序号或 UUID 生成本地临时 ID；Host 返回真实 ID 后再建立映射。错误行也使用同一生成器。

**验收**

- 删除空 assistant 行后连续发送多条消息，列表 ID 保持唯一。
- 本地 ID 被 Host 历史替换后，滚动和展开状态仍指向正确行。

### P2-3 跨平台 SSH 私钥校验语义不一致

**证据**

- Android 使用私钥和口令真实解析，见 [KRBridgeModule.kt:326](../androidApp/src/main/java/io/github/yukiykchen/dsh/module/KRBridgeModule.kt#L326)。
- iOS 桥接忽略口令，见 [HRBridgeModule.m:79](../iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m#L79)；[DshSshKeyStore.swift:80](../iosApp/iosApp/KuiklyExpand/Native/DshSshKeyStore.swift#L80) 只检查 PEM 标记。
- OHOS 同样只检查文本标记，见 [DshSshKeyStore.ets:115](../ohosApp/entry/src/main/ets/kuikly/native/DshSshKeyStore.ets#L115)，而 OHOS SSH 本身又明确不支持，见 [KRDshEngineModule.ets:13](../ohosApp/entry/src/main/ets/kuikly/modules/KRDshEngineModule.ets#L13)。

**影响**

iOS 上错误口令可能通过连接页校验，直到真正建立 SSH 时才失败。OHOS 也会给出“校验通过”的错误印象。

**改进**

1. iOS 使用 libssh2 或现有 SSH 会话代码真实验证私钥和口令。
2. OHOS 在 SSH 不支持时隐藏或禁用 SSH 配置，而不是保留一个只检查文本的校验入口。
3. 为三端定义同一份桥接契约：输入、错误码、口令行为和不支持状态。

**验收**

- iOS 覆盖普通私钥、加密私钥、缺少口令和错误口令。
- OHOS SSH 入口的可见性与 `supportsSshBridge` 一致。

### P2-4 原生桥接仍有模板残留和空实现

**证据**

- 共享桥接 [DshBridgeModule.kt](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/framework/DshBridgeModule.kt) 仍暴露电话、地址、样品订单、离线包、人机验证、缓存、预下载等业务无关方法。
- Android 对 `openPage`、`showAlert`、`ssoRequest` 等方法只解析参数或直接为空，见 [KRBridgeModule.kt:154](../androidApp/src/main/java/io/github/yukiykchen/dsh/module/KRBridgeModule.kt#L154)。
- OHOS 的 `openPage` 和 `dateFormatter` 仍是 TODO，见 [KRBridgeModule.ets:112](../ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets#L112)。
- OHOS 仍注册模板 `KRMyModule` 和 `KRMyView`，见 [KuiklyViewDelegate.ets:16](../ohosApp/entry/src/main/ets/kuikly/KuiklyViewDelegate.ets#L16)。
- OHOS 文件读取失败时不会回调，见 [KRBridgeModule.ets:182](../ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets#L182)。

**影响**

调用者看到的方法不等于平台实际能力。空实现会让失败表现为无响应，后续开发者也难判断哪些能力仍在使用。

**改进**

1. 将桥接接口收敛为当前 App 实际调用的方法，并按领域拆成剪贴板、键盘、SSH Key、导航和系统栏等小模块。
2. 删除模板 module、view、页面路由和未使用的工具函数。
3. 不支持的方法统一返回 `unsupported` 错误，回调必须总是完成。
4. 给桥接方法建立三端能力矩阵，并用契约测试检查方法名和返回结构。

**验收**

- 共享层不再暴露没有调用方的业务无关桥接方法。
- 每个保留方法都有 Android、iOS、OHOS 的实现或明确的 `unsupported` 结果。

### P2-5 OHOS 网络路径重复，部分代码不可达

**证据**

- Kotlin/Native 暴露 `dsh_http_post_json`，见 [DshOhosNetworkTransport.kt:108](../shared/src/ohosMain/kotlin/io/github/yukiykchen/dsh/data/DshOhosNetworkTransport.kt#L108)。
- C++ 层导出 `dshHttpPostJson`，见 [napi_init.cpp:171](../ohosApp/entry/src/main/cpp/napi_init.cpp#L171)，但 ArkTS 没有调用方。
- Relay 实际走系统 HTTP，失败后再走手写 TCP HTTP，见 [DshRelayNetwork.ets:46](../ohosApp/entry/src/main/ets/kuikly/native/DshRelayNetwork.ets#L46)。
- [DshTcpHttp.ets](../ohosApp/entry/src/main/ets/kuikly/native/DshTcpHttp.ets) 640 行，包含 HTTP 解析、分块编码、网卡选择、超时和大量日志。

**影响**

网络行为分散在 Kotlin、C++ 和 ArkTS 三层，维护者很难判断真实请求路径。手写 HTTP 解析器也需要长期承担协议兼容和安全边界。

**改进**

1. 删除不可达的 NetworkKMM/NAPI HTTP 入口，或明确只保留一种正式路径。
2. 将手写 TCP HTTP 限定为可开关的兼容回退，并记录触发条件。
3. 为回退路径补充分块响应、截断响应、大响应、超时和取消测试。
4. 网络日志不输出完整 URL、请求体和响应体。

**验收**

- OHOS 只有一个文档化的正式 HTTP 实现入口。
- 删除未使用入口后，HAP 构建和 54 项 Relay 主机测试仍通过。

### P2-6 语音按钮只有状态切换，没有实际能力

**证据**

[DshHomePage.kt:898](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L898) 只切换 `voiceActive` 和顶部文字；[DshConversationPanels.kt:284](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshConversationPanels.kt#L284) 会把输入框改为“正在聆听”，并禁用编辑。

**影响**

用户看到聆听状态，但没有录音、权限申请、识别结果或失败提示。

**改进**

接入真实语音能力并定义权限和错误状态；当前不实现时，应隐藏语音入口，空输入时禁用发送按钮。

**验收**

- 用户不能进入没有实际行为的“正在聆听”状态。

### P2-7 高风险流程缺少页面级回归测试

**证据**

- 当前共享测试主要覆盖 controller、Store、协议解析和 SQLite。
- `DshHomePage`、`DshStreamingTurnController`、`DshConversationScroller`、`DshMessageRowView`、`DshMarkdownView` 没有直接测试。
- OHOS 的 [Ability.test.ets](../ohosApp/entry/src/ohosTest/ets/test/Ability.test.ets) 仍是模板断言。
- iOS/OHOS 的相机、Keychain/HUKS、本地网络权限仍缺真机证据。

**改进**

1. 为首页建立可注入时钟、调度器、Host 客户端和本地存储的状态机测试。
2. 覆盖发送、流式输出、工具分段、停止、断线恢复、切换会话和历史覆盖。
3. 对消息列表和 Markdown 做 Android 截图或组件测试，覆盖空态、长文本、工具卡、键盘弹出和宽屏。
4. 删除 OHOS 模板测试，补一条真实启动、Relay 配对状态和 HUKS 读写的设备测试。

**验收**

- 首页核心交互至少有一组端到端状态测试。
- 每个平台都有一条不依赖人工观察的真实启动验证。

## P3：持续清理

### P3-1 构建配置和废弃 API

**证据**

- [settings.gradle.kts:34](../settings.gradle.kts#L34) 包含不存在的 `:h5App` 和 `:miniApp`，Gradle 已提示 9.0 后会失败。
- `:shared:compileDebugKotlinAndroid` 仍有 `TextAreaView.setText`、`IPagerId`、`pagerId` 委托和 `currentPageId` 废弃警告，见 [DshPagerIdKtx.kt:17](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/framework/DshPagerIdKtx.kt#L17) 和 [DshPagerUtils.kt:16](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/framework/DshPagerUtils.kt#L16)。
- Kotlin 默认 hierarchy template 未正确应用，构建时持续输出警告。
- [build.gradle.kts](../build.gradle.kts) 和 [build.ohos.gradle.kts](../build.ohos.gradle.kts) 重复定义 `verifySharedArchitecture`。
- Android 依赖中仍声明 Picasso、RecyclerView、DynamicAnimation、Kotlin serialization 等当前源码未直接使用的库，见 [androidApp/build.gradle.kts:74](../androidApp/build.gradle.kts#L74)。

**改进**

1. 删除不存在的 Gradle 子项目，迁移废弃 Kuikly API。
2. 按官方 hierarchy template 重新组织 source set，或显式关闭默认模板并说明原因。
3. 用 `buildSrc` 插件或共享脚本注册架构检查，避免两份任务配置漂移。
4. 通过依赖分析删除未使用的直接依赖，依赖升级另起任务验证。

### P3-2 UI 颜色和间距缺少统一 token

**证据**

共享 UI 中有 201 处 `Color(0x...)` 字面量，其中 96 个不同颜色值。[DshConnectionDialogs.kt:100](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshConnectionDialogs.kt#L100) 将布局、颜色、状态和事件压缩在一行。

**改进**

建立 `DshTheme`，集中定义文字、背景、边框、强调色、错误色、圆角和间距。先替换连接页、首页输入区、会话抽屉和弹窗，再逐步覆盖其他组件。

### P3-3 格式和错误处理不一致

**证据**

- 多处缩进异常，例如 [DshModels.kt:219](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/model/DshModels.kt#L219) 和 [DshStreamingTurnController.kt:157](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshStreamingTurnController.kt#L157)。
- 共享层生产代码有大量 `runCatching` 或宽泛 `catch (_: Exception)`，部分失败没有日志、错误码或用户状态。
- 仓库没有 ktlint、detekt、SwiftLint 或 ArkTS lint 配置。

**改进**

1. 增加格式化和静态检查，先只检查本次改动文件，避免一次性大规模格式化。
2. 为 JSON 解析、存储、网络、桥接调用定义错误类型，区分可忽略、可重试和需提示用户的失败。
3. 禁止吞掉存储写入失败，至少记录脱敏错误码并更新缓存状态。

## 建议执行顺序

| 阶段 | 内容 | 目标 |
| --- | --- | --- |
| 1 | P1-1、P1-2、P1-5 | 先消除隐私、凭据和发布身份风险 |
| 2 | P1-3、P1-4 | 控制长会话内存和页面线程阻塞 |
| 3 | P2-1、P2-2、P2-7 | 重组首页状态，并补足核心交互回归 |
| 4 | P2-3、P2-4、P2-5、P2-6 | 统一三端能力和桥接行为 |
| 5 | P3 | 清理工具链、设计 token 和代码风格 |

每个阶段都应保持现有检查继续通过：

```bash
./gradlew verifySharedArchitecture :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug
```

涉及 iOS 或 OHOS 原生代码时，还需分别运行：

```bash
python3 scripts/test-ios-relay.py
SSH_ADDRESS_SANITIZER=1 python scripts/test-ios-ssh.py
/Applications/DevEco-Studio.app/Contents/tools/node/bin/node scripts/test-ohos-relay.cjs
```

## 本轮审查验证

- `./gradlew verifySharedArchitecture :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug` 通过。
- 架构检查结果：172 个 Kotlin 源文件、102 个原生或脚本源文件，手写文件均不超过 1000 行。
- `./gradlew :shared:compileDebugKotlinAndroid --rerun --warning-mode all` 编译通过，但输出了本文 P3-1 记录的废弃 API 和 Gradle 配置警告。
- 本轮为文档审查，没有修改业务代码，也没有重新执行 iOS、OHOS 和真机测试。

## 2026-11-10 实施状态

### 已完成

- **P1-1 敏感日志**：新增 `DshDiagnostics`。生产模式只输出长度和结构信息，业务正文预览仅在宿主显式传入 `verboseDiagnostics=true` 时启用；Android/iOS Debug 开启，OHOS/Release 关闭。Android 日志适配器、Android/iOS/OHOS 桥接日志和 OHOS N-API 日志适配层均已收口。新增 `DshDiagnosticsTest`。
- **P1-2 API Key 写入目标**：凭据弹窗打开时调用 `loadCredentialSetup`，保存 Host 返回的 `credentialRef`，并在不可写、Provider 未启用或加载失败时阻止写入。`credentials.set` 不再固定写入默认引用。新增自定义引用、只读配置和加载失败回归测试。
- **P1-3 Host 事件缓存**：`sessionEvents` 改为每个会话最多 256 条的有序队列，插入不再整表排序；新增 `removeSession()` 统一清理会话、序号、队列、任务、投影和待处理交互；连线里的 projection 帧也写入 Store。新增 300 条事件上限及删除会话回归测试。
- **P1-4 SQLite 页面线程阻塞**：`DshLocalStore.submit` 把连接配置、配对资料和会话索引写入 FIFO 数据库队列，首页和连接页不再为这些写操作做同步阻塞调用。消息快照写入和关闭排空仍沿用原有队列语义。新增默认 `submit` 契约测试并调整连接设置测试夹具。
- **P1-5 Release 签名**：公开 `preview.jks` 只用于 Debug；Release APK 改成未签名产物，GitHub Release 工作流用 `apksigner` 校验签名，阻止未签名 APK 发布。预览签名路径和默认口令改为 `DSH_PREVIEW_*` 环境变量可覆盖，并更新 `keystore/README.md`。
- **P2-2 消息 ID**：首页本地消息改用页面内单调递增序号，空流式行被删除后不会复用旧 ID。
- **P2-6 语音按钮**：移除了未实现的“正在聆听”状态和开关参数；空输入点击麦克风不执行操作，输入框始终保持可编辑。
- **P3-1 构建配置**：删除不存在的 `:h5App`、`:miniApp` 子项目，显式关闭默认 Kotlin hierarchy template，消除相关 Gradle 警告。

### 本轮验证

```text
./gradlew verifySharedArchitecture :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug
BUILD SUCCESSFUL

./gradlew :androidApp:assembleDebug :androidApp:assembleRelease
BUILD SUCCESSFUL
```

`apksigner verify` 确认 Release 产物为 `androidApp-release-unsigned.apk`，不携带公开预览签名。架构检查通过：174 个 Kotlin 源文件、102 个原生或脚本源文件，手写文件均不超过 1000 行。仍有 Kuikly 废弃 API 编译警告；iOS、OHOS 原生回归与真机流程本轮未执行。

### 仍未完成

以下事项已在本轮后续修改中完成，详见下一节；这里保留的是需要外部设备、私有凭证或人工视觉复核的验证边界：

- iOS 真机 Keychain、相机、本地网络权限。
- OHOS 真机 HUKS、相机、本地网络权限和签名 profile。
- Android 真机相机扫码、Relay/SSH 连接和 Android 16 KB 设备（按用户要求暂不处理 16 KB 兼容）。
- Release 签名所需 GitHub Secrets 的实际配置，以及开启 R8 后的真机安装与端到端流程。

## 2026-11-10 后续实施状态

### 已完成

- **P2-1 职责拆分**：`DshHostClient` 的连线基线解析拆到 `DshHostBaselineParser`，流式回合状态拆到 `DshPromptStreamRegistry`；构造函数保持原有 API，避免页面调用点扩散。新增注册表单测覆盖增量累积、完成、错误、会话隔离和旧回调。
- **P2-3 iOS/OHOS SSH 一致性**：iOS `validateSshKey` 接收口令，私钥验证区分普通私钥和加密私钥；新增 Swift 回归。OHOS 在 `supportsSshBridge=false` 时隐藏 SSH Tab，并把缓存的 SSH 模式回退为扫码。
- **P2-4 桥接清理**：共享 `BridgeModule` 只保留实际使用的 9 个能力；Android、iOS、OHOS 同步删除模板方法/空实现；OHOS 删除 `KRMyModule`、`KRMyView` 和模板 View 注册；移除旧 `IPagerId` / `currentPageId` 工具。新增 `test_bridge_contract.py` 校验三端方法名和不支持方法的返回约定。
- **P2-5 OHOS 网络路径**：删除未使用的 NetworkKMM Kotlin/C++/NAPI HTTP 入口及其下载和链接配置；正式路径只剩系统 HTTP，失败时走手写 TCP HTTP 回退。新增 TCP HTTP 回归测试覆盖 content-length、分块、超大响应、截断、EOF、超时和取消；OHOS 主机测试共 59 项通过。
- **P2-7 自动化回归**：新增流式回合注册表测试、凭据引用测试、有界事件测试、日志策略测试、桥接契约测试、主题静态检查和 TCP HTTP 边界测试。真机权限和 UI 截图仍属于外部验证边界。
- **P3-2 设计 token**：新增 `DshTheme`，连接页 Palette、连接设置弹窗、首页输入区与抽屉遮罩改用统一颜色 token；静态检查禁止受治理文件再出现裸颜色字面量。
- **P3-3 静态检查与错误策略**：架构检查新增主题约束、废弃工具约束和桥接契约；不支持的桥接方法返回 `unsupported bridge method`，不再静默无响应；OHOS 文件读取的旧模板入口已删除；新增 `DshAppError` 统一区分可忽略、可重试和需提示用户的错误，并覆盖分类测试。
- **P3-1 废弃 API**：删除旧 `Utils` / `IPagerId` 扩展；所有 `setText()` 调用改为 Kuikly 的输入属性 `text(...)`，`currentPageId`、`pagerId` 委托和 `TextAreaView.setText` 编译警告全部消失。
- **P1-5 发布链路**：Release 启用 R8 与资源压缩，新增 keep 规则并由 Release 构建验证；正式签名改为 `DSH_RELEASE_*` 环境变量和 GitHub Secret 注入，未配置时仍生成未签名 APK，发布流程用 `apksigner` 阻止上传。

### 本轮验证

```text
./gradlew verifySharedArchitecture :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug
BUILD SUCCESSFUL

./gradlew :shared:compileDebugKotlinAndroid
BUILD SUCCESSFUL

./gradlew :androidApp:assembleDebug :androidApp:assembleRelease
BUILD SUCCESSFUL

python3 -m unittest discover -s scripts -p 'test_*.py'
14 tests, OK

python3 scripts/test-ios-relay.py
Swift Relay tests: 34 passed, 0 failed

/Applications/DevEco-Studio.app/Contents/tools/node/bin/node scripts/test-ohos-relay.cjs
OHOS Relay host tests: 59 passed, 0 failed
```

R8 的 `missing_rules.txt` 已按 Android 不使用的 SSHD 桌面可选依赖补齐 `-dontwarn`。`git diff --check` 干净。

### 外部验证边界

- GitHub Release Secrets：`DSH_RELEASE_KEYSTORE_BASE64`、`DSH_RELEASE_KEYSTORE_PASSWORD`、`DSH_RELEASE_KEY_ALIAS`、`DSH_RELEASE_KEY_PASSWORD` 需要仓库管理员配置；未配置时发布流程会失败，避免未签名包进入 Release。
- 真机或签名设备上的扫码、SSH、相机、Keychain/HUKS、本地网络权限，以及开启 R8 后的安装和端到端对话，仍需在实际设备上执行。
- iOS 完整 App Debug/Release 构建、OHOS HAP 构建未在本次本地验证中重跑；相关源码改动已由各自原生回归和静态契约覆盖。
