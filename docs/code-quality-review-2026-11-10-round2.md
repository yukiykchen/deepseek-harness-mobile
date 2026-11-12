# DSH 第二轮代码质量审查

审查时间：2026-11-10。基线：`main`，HEAD `30a92c0`，包含当前全部未提交改动，而非只审查 HEAD 已提交代码。

范围：共享业务与 UI、SQLite、Android/iOS/OHOS 原生桥接、Relay/SSH、缓存、测试及发布链路。16 KB 原生库兼容继续排除。

审查阶段只新增文档、可运行证据探针和旧文档状态提示；随后按本文件逐项修改代码与测试。没有提交、推送，也没有安装或覆盖设备上的 App。

> 修复状态（2026-11-10 后续）：R01-R13 和 D01-D04 已修改并补回归测试，包括会话策略协调器抽取和 `DshHostClient` 显式 `start()`。下方问题描述与初始反例输出保留为历史证据，不再代表当前缺陷。

## 结论

**当前代码不宜视为完成收尾或可以发布。上一轮“代码内任务全部完成”的结论过早。**

最需要修复的不是再拆几个文件，而是生产 SQLite 自等待、OHOS 生产库产物缺失、异步持久化虚报成功、iOS 加密私钥校验和原生资源生命周期。现有测试虽全绿，但若干测试夹具与生产契约不一致。

优先级：P1 为功能、构建或隐私风险，必须优先处理；P2 为条件触发的回归、缓存正确性和工程可靠性问题。静态 UI 检查器的 P1/P2 是线索，不能直接等同于下列已核实缺陷。

## 核实问题

### R01 [P1] SQLite 消费者等待自己，所有后续任务堵塞

证据：[DshSqliteLocalStore.kt:39](../shared/src/sqliteMain/kotlin/io/github/yukiykchen/dsh/data/DshSqliteLocalStore.kt#L39)、[DshDatabaseQueue.kt:24](../shared/src/sqliteMain/kotlin/io/github/yukiykchen/dsh/data/DshDatabaseQueue.kt#L24)、[DshDatabaseQueue.kt:44](../shared/src/sqliteMain/kotlin/io/github/yukiykchen/dsh/data/DshDatabaseQueue.kt#L44)。

`store.submit { saveLastConnectionMode(...) }` 在唯一队列消费者中执行；同步写接口经 `onDatabase` 再往该队列提交任务，然后 `runBlocking` 等待。消费者不返回就不能消费新任务，形成确定的自等待。线程数量再多也不能解决同一队列只有一个消费者的问题。

触发点包括首页会话索引写入和连接页配置保存。返回页面时同步读或 `close()` 排空队列会继续等住。使用真实 `DshSqliteLocalStore` 的隔离进程探针已经复现，未进入任何 SQL 执行，也不依赖慢盘或真机。

改进：队列只包围一次 driver 操作；使用专用异步 store API 调用未排队的内部操作，不对同步公共方法再套 `submit`。读、写、关闭使用同一序列化契约，但不能相互自等待。

验收：真实 SQLite 覆盖 `submit` 后读回、第二次写入、关闭、异常后恢复；每个测试设进程级超时，禁止让测试本身无限挂住。

### R02 [P1] OHOS 清理依赖时删掉了 sharedLib 产物定义

证据：[shared/build.ohos.gradle.kts:41](../shared/build.ohos.gradle.kts#L41)、[CMakeLists.txt:17](../ohosApp/entry/src/main/cpp/CMakeLists.txt#L17)、[napi_init.cpp:99](../ohosApp/entry/src/main/cpp/napi_init.cpp#L99)。

`ohosArm64` 只配置 compilation，不再声明 `binaries.sharedLib`。任务列表只有 klibrary 编译和 debug test 链接，没有生产 `linkSharedDebugSharedOhosArm64`。显式请求旧生产任务时 Gradle 返回 `task ... not found`。

CMake/N-API 仍依赖 `libshared.so` 和导出头；当前 `entry/libs/arm64-v8a/libshared.so` 是 14:40 的旧产物。因此保留产物的工作区可能掩盖缺陷，Node 主机测试无法证明当前代码可以形成 HAP。

改进：恢复 shared library 二进制声明，仅去掉 NetworkKMM linker 参数；检查 Hvigor 插件的任务和复制契约，清理旧产物后验证生产链接。

验收：Debug/Release 生产链接任务存在；从干净产物目录生成新的 `.so`、导出头和 HAP，而不是复用旧文件。

### R03 [P1] “排队成功”被当成“配置保存成功”

证据：[DshConnectionSetupController.kt:100](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/connection/DshConnectionSetupController.kt#L100)、[DshConnectionSetupController.kt:198](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/connection/DshConnectionSetupController.kt#L198)、[DshDatabaseQueue.kt:53](../shared/src/sqliteMain/kotlin/io/github/yukiykchen/dsh/data/DshDatabaseQueue.kt#L53)。

配对、SSH 设置在 `submit` 返回后立刻改变 UI、`markKeySaved` 或跳转；外层 `runCatching` 只能接住入队错误。后台实际写入失败只打印日志，不能反馈给调用方。即便修复 R01，磁盘满、schema 或 driver 错误仍会表现为保存成功。`profile + last mode` 也不在一个原子事务内。

[SetupTestFixture.kt:90](../shared/src/commonTest/kotlin/io/github/yukiykchen/dsh/feature/connection/SetupTestFixture.kt#L90) 立即执行写入，`completeWrites()` 无实际行为，导致生产异步失败没有被测试。

改进：异步 API 返回 `Result` 或 suspend 结果，事务完成后再跳转/移交密钥所有权；失败保留草稿和旧配置。每个入队任务捕获提交时的 scope、profile、sessions 快照，禁止后台闭包读取页面 observable。

验收：延迟写、入队后失败、切模式、页面关闭、部分事务失败均不能报告成功或写错 scope；假存储提供可控提交完成，不模拟立即成功。

### R04 [P1] iOS 私钥校验拒绝有效加密 OpenSSH，接受伪 PEM

证据：[DshSshKeyValidator.swift:13](../iosApp/iosApp/KuiklyExpand/Native/DshSshKeyValidator.swift#L13)、[SshKeyRecordTests.swift](../scripts/swift/SshKeyRecordTests.swift)。

实现只检查标记、`ENCRYPTED` 文本和口令非空。OpenSSH 加密 Ed25519 的头仍是 `BEGIN OPENSSH PRIVATE KEY`，cipher 信息位于编码体内，不含该明文标记。用系统 `ssh-keygen` 生成的真实私钥得到：正确口令也返回 false；无效 `ENCRYPTED PRIVATE KEY` 文本加任意口令却返回 true。未加密私钥带多余口令也被拒绝。

改进：使用和真实 SSH 认证一致的解析/解密实现；不手写字符串猜测，不把“口令存在”称为“口令正确”。把纯语法检查和密码解密验证分别定义清楚。

验收：真实 RSA/Ed25519 普通、加密 OpenSSH/PEM，正确/错误/缺失口令及截断输入；样本由真实工具生成，不用 `body` 字符串假装密钥。

### R05 [P1] 凭据弹窗初始加载断线后永远 busy

证据：[DshCredentialController.kt:45](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshCredentialController.kt#L45)、[DshConnectionDialogs.kt:181](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshConnectionDialogs.kt#L181)。

加载成功/失败回调先 `accepts(request)` 再释放 busy。连接 generation 变化时回调被丢弃，busy 永不复位；输入和关闭按钮均被 busy 禁用。现有“重连可重试”测试只覆盖保存阶段，保存阶段使用 `owns` 后释放 busy，加载阶段未采用同样规则。

可控传输复现：`open -> socket disconnect -> flush callbacks` 后 `busy=true, visible=true, errorLength=0`。

改进：先验证请求所有权并释放该请求的 busy，再检查上下文；失效时提示重新加载或自动重试，保持关闭可用。

验收：Provider 查询、settings 查询、credentials 查询的各阶段断线，以及客户端替换、关闭后重开，均不会遗留 busy 或清掉新请求的 busy。

### R06 [P1] OHOS Host WebSocket 没有销毁清理，主动关闭泄漏 slot

证据：[KRDshWebSocketModule.ets:118](../ohosApp/entry/src/main/ets/kuikly/modules/KRDshWebSocketModule.ets#L118)、[KRDshWebSocketModule.ets:123](../ohosApp/entry/src/main/ets/kuikly/modules/KRDshWebSocketModule.ets#L123)、[KRDshWebSocketModule.ets:140](../ohosApp/entry/src/main/ets/kuikly/modules/KRDshWebSocketModule.ets#L140)。

slot 只在收到 CLOSED/ERROR 时 unbind；主动 close 设 `closed=true` 后恰好不再 emit CLOSED，因此 disconnect 不回收 slot。模块无 `onDestroy`，页面销毁也不关闭 connections 或注销全局回调。生产 ArkTS 经边界 mock 的探针复现：断开后 slots=1；再次连接后销毁 slots=2，新 socket 仍未关闭。

改进：connection 独立持有 slot；finish/close/onDestroy 统一幂等释放。映射删除使用当前实例身份，避免旧 socket 的终止回调误删同 ID 的新 connection。Android 对应映射也应做身份校验。

验收：多次连接/断开/销毁后 slot 和 socket 数归零；回调晚到不能访问已销毁页面或移除替代连接。

### R07 [P1] iOS 图片适配器绕过了诊断脱敏

证据：[KuiklyRenderComponentExpandHandler.m:41](../iosApp/iosApp/KuiklyExpand/Handler/KuiklyRenderComponentExpandHandler.m#L41)、同文件第 57、109、120 行。

无 DEBUG 宏的 `NSLog` 输出原始 src、resolved URL 和完整本地路径。图像附件以 data URL 传入时会打印 Base64 正文；远程 URL 可包含查询凭据。新增 `DshDiagnostics` 只影响共享日志，无法阻止这些原生直出。

改进：原生图片日志只留类型、长度、成功/失败码；禁止 data URL、查询参数、完整路径。对 Debug 预览也应明确密钥脱敏规则，不能以注释“never credentials”当保证。

验收：Release 附件及带测试 token 的 URL 加载后日志中找不到 Base64、token 或路径；测试覆盖原生适配层，不只调用 preview 函数。

### R08 [P2] Store 去重没有阻止重复流式正文

证据：[DshModels.kt:205](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/model/DshModels.kt#L205)、[DshHostClient.kt:656](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt#L656)、[DshPromptStreamRegistry.kt:50](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshPromptStreamRegistry.kt#L50)。

Store 发现重复 seq 后 return，但不返回“是否接纳”；客户端仍通知 UI、调用 registry。相同 seq 的 chunk 发送两次，生产客户端探针输出 `[hello, hello]`。事件窗口裁剪后仅扫描窗口还会遗失旧 seq 的去重信息。

改进：定义事件接纳结果，把去重/乱序策略置于所有副作用之前；checkpoint 与历史窗口分离，明确 gap/backfill 规则。

验收：重复 chunk、工具结果、projection 和重连回放不重复触发 UI 或拼接正文；不要假定传输永不重复。

### R09 [P2] 审批命令查询使用了与生产不同的事件结构

证据：[DshHostClient.kt:652](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt#L652)、[DshHostStoreQueries.kt:170](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostStoreQueries.kt#L170)、[DshHostStoreQueriesTest.kt](../shared/src/commonTest/kotlin/io/github/yukiykchen/dsh/data/DshHostStoreQueriesTest.kt)。

客户端存 `{event:{data:...},view:...}`，查询却直接读根 `data`。生产形状返回 `command=null`，测试夹具形状 `{data:...}` 返回 `pwd`；探针已确认。用户可能无法在审批面板看清具体命令。

改进：通过统一 `dshWireEvent` 解包并建立按 callId 的命令索引。未解决审批关联信息不能随着任意 256 条窗口被删除。

验收：从完整 mux 信封经过客户端到审批面板，显示正确命令；再注入 300 个其他事件，待审批命令仍保留。

### R10 [P2] 普通切换会话隐式取消 Host 生成

证据：[DshHomePage.kt:713](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomePage.kt#L713)、[DshStreamingTurnController.kt:124](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshStreamingTurnController.kt#L124)、[DshHostClient.kt:530](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/data/DshHostClient.kt#L530)。

`selectMountedSession -> cancelForSessionSwitch -> handle.cancel -> session.cancel` 把取消本地观察与停止远程生成混在一起。不是 UI 晚回调问题：真实 RPC 会被发送。多会话工具中查看另一个会话应与点击停止分开；若产品有意规定切换即停止，需明确交互并重新定义接口。

改进：拆成 `detachObservation()` 和 `cancelRemoteTurn()`；只有显式停止发送 session.cancel，切换保留后台回合。

验收：切会话不新增取消 RPC，切回能从历史/实时事件恢复；显式停止仅发一次取消 RPC。

### R11 [P2] SQLite 缓存不能往返当前消息模型

证据：[DshSqliteLocalStore.kt:146](../shared/src/sqliteMain/kotlin/io/github/yukiykchen/dsh/data/DshSqliteLocalStore.kt#L146)、[DshSqliteLocalStore.kt:169](../shared/src/sqliteMain/kotlin/io/github/yukiykchen/dsh/data/DshSqliteLocalStore.kt#L169)、[DshModels.kt:287](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/model/DshModels.kt#L287)。

数据库仅存六个消息字段，遗漏 reasoning、附件 ID、tool 状态/类型、context 元数据和 remoteTool。会话表也未存 blank/cwd 等字段。读回后思考变正文、图片失去附件引用、工具卡失去语义，离线或 Host 失败时缓存不等价于当前内容。页面内单调 ID 还会在重启归零，未解决与磁盘已有 local ID 的冲突。

改进：定义版本化缓存 DTO/迁移，保留渲染所需字段；本地 ID 带持久化序号或启动级唯一前缀，不能只依赖页面计数。

验收：完整消息/会话模型 roundtrip、旧 schema 迁移、同 scope 冷启动后再次发送、Host 不可用时图片/思考/工具卡保真。

### R12 [P2] 缓存限额和 ViewRef 回收没有闭环

证据：[DshConversationScroller.kt:41](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshConversationScroller.kt#L41)、[DshConversationScroller.kt:51](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshConversationScroller.kt#L51)、[DshSessionMessageStore.kt:135](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshSessionMessageStore.kt#L135)、[DshAttachmentCache.kt:26](../shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshAttachmentCache.kt#L26)。

panel 淘汰仅删 scroller，不删 rows 对应 ViewRef；全部会话消息预加载并长期保留，附件 Base64 无字节/LRU 限额。256 条事件限制也未限制单条/总字节。于是“小文件”“最多若干 panel”都不代表长期内存受控。

改进：按会话管理所有 refs，在淘汰/删除/销毁中共同回收；消息和附件按字节预算做 LRU，只预载最近会话，保护当前流式会话和未处理审批。

验收：100 会话往返后 refs 不随访问次数增加；大附件和大工具结果触及预算时可预测淘汰，不丢关键交互。

### R13 [P2] 发布签名校验在多版本 SDK 下执行错误命令

证据：[release.yml:62](../.github/workflows/release.yml#L62)。

`"$ANDROID_HOME/build-tools"/*/apksigner verify ...` 匹配多版本时，第一个文件是可执行命令，第二个 apksigner 路径变成首个参数，而不是依次选择工具。等价参数调用真实 apksigner 返回 `Unsupported command: /another/build-tools/apksigner`。签名正确也可能被判未签名。

改进：固定/解析单一 build-tools 版本，显式选择一个 APK；不把 Secret 文本直接拼进 shell 源码；清理私钥放在 trap/finally，构建失败也执行。

验收：零个、一个、两个 build-tools 目录及有效/无效签名都得到明确结果；本地签名构建通过不等于 GitHub Secrets 已配置。

## 工程质量债务

| 项目 | 当前证据 | 下一步及验收 |
| --- | --- | --- |
| D01 职责拆分未完成 | Home 900 行、HostClient 710 行；页面仍负责会话协调、渲染缓存、连接回调和导航，HostClient 构造中直接启动 transport | 先提取可测 HomeSessionCoordinator；分离 RPC、reducer、stream registry。验收职责、依赖和行为，不以减行数或搬 lambda 当完成 |
| D02 错误抽象未接入 | `DshAppError` 仅声明和自身测试，生产仍用 DshRpcError/String/吞异常 | 接入网络、缓存、UI 的真实失败路径或删除无用抽象；用实际错误观察到重试/提示/忽略策略 |
| D03 测试契约偏差 | 假存储立即完成；假 PEM 是 body 字符串；OHOS loader 对纯 DshBytes/DshJson 做源码替换；桥接检查只 assertIn 方法名 | mock 只放在 OS/I/O 边界；纯 codec 原样执行；行为测试覆盖返回、生命周期和异常；用干净 native 链接与生产最小流程补齐矩阵 |
| D04 空控件与主题不完整 | 设置的模型/权限/语言/日志/断开等行传空回调；mic 图标对应 Unit；Theme 只覆盖少数文件且只检测 Color(0x...) | 未实现功能隐藏或显式禁用，尤其断开必须执行；主题纳入条件色、圆角/间距与真实页面截图，不以正则零命中当视觉完成 |

UI 输入 `text(state)` 与 `textDidChange` 双向绑定还需要单独验证光标和中文 IME；无废弃 API 警告并不证明不会覆盖编辑状态。当前只列为待核实框架风险，不把静态 K03 命中宣称为已复现缺陷。

## UI 静态复核

```text
Static Gate: FAIL
Visual Audit: NOT RUN
Design Score: N/A
Overall UI Status: VISUAL_UNVERIFIED
```

checker 扫描 128 文件，原始结果 P1=24、P2=33、Slop Risk=56，**均是机器命中，不是 57 个已确认 UI bug**。本轮未安装当前工作区构建，也未做截图，所以没有视觉分数。

P1 Triage：K20 的 16 个命中实际传了明确 pagerId，不能按 import 判全局漂移；K03 的 7 个命中待 IME/光标验证；K06 的 1 个命中是 workspace 内普通 forEach 构建 row，active/running 快照是否正确更新需组件回归。

P2 Triage：

- K04（17）：16 处是普通集合类型/函数而非 UI List；剩余 ConversationPanels List 使用 absolutePositionAllZero 受父容器约束。保留实现，不据此机械加 height。
- X01（2）：保留现有独立 Activity/ViewController 页面导航；其生命周期和“切换会话取消生成”分开验证，不为过静态门禁改掉路由架构。
- V01（1）：选中态 `✓` 是有意的勾选符号，不是随机 emoji 图标，保留。
- V07（13）：消息行、会话抽屉、导航、设置、工作区弹窗、审批、展开区、目标、任务、Markdown、问答流程/面板、队列仍有内联颜色，均未修复；列入 D04，截图和语义 token 一起验收。

## 验证与边界

| 当前验证 | 结果 | 能证明什么 |
| --- | --- | --- |
| verifySharedArchitecture | PASS；178 Kotlin、103 native/script；手写 <=1000 行 | 包名/依赖及长度，不证明运行契约 |
| 重新执行 shared/Android 单测 | 259/81 项，0 失败/跳过 | 现有样本通过；不覆盖 R01 等新增失败样本 |
| Android lintDebug | 0 error，68 warning | 静态 API/资源诊断，不证明相机、签名或长期运行 |
| Swift Relay/KeyRecord 回归 | 34 项 PASS | 当前测试定义；不证明伪 PEM 用例是正确的密钥校验 |
| OHOS 主机回归 | 59 项 PASS | 转译后状态逻辑和 I/O mocks；不生成 libshared.so/HAP |
| 生产队列/Store 限时探针 | 已复现 R01 | 真实实现自等待，非仅源码推测 |
| 真实 ssh-keygen 样本探针 | 已复现 R04 | 有效加密 OpenSSH 被拒绝、伪 PEM 被接受 |
| 生产客户端/可控传输探针 | 已复现 R05/R08/R09 | busy 卡住、重复增量、审批查询结构错配 |
| 生产 ArkTS 模块/系统边界 mocks 探针 | 已复现 R06 | disconnect/destroy 遗留 slots/socket |
| OHOS 生产链接任务 dry-run | FAIL，任务不存在 | R02 不能归为单纯未上真机 |

本轮未重跑完整 iOS App、OHOS HAP、Android R8 Release 安装、真实服务端/设备端端到端和截图。未读取/配置正式私钥或 GitHub Secrets。性能/内存影响除可见的数据结构限额外不虚报测量值。

### 可运行证据

探针保存在 [review-evidence](review-evidence/StoreAndStreamProbe.java)，只使用测试临时数据；数据库探针在独立 Java 进程中故意复现自等待后退出，不挂住真实 App。

```bash
./gradlew -I docs/review-evidence/probe.init.gradle :shared:reviewProductionProbe --console=plain
/Applications/DevEco-Studio.app/Contents/tools/node/bin/node docs/review-evidence/ohos-websocket-probe.cjs
./gradlew -c settings.ohos.gradle.kts :shared:linkSharedDebugSharedOhosArm64 --dry-run
```

观测值：

```text
actualStoreSubmitEntered=true actualStoreWriteCompleted=false
worker stack: DshDatabaseQueue.call -> DshSqliteLocalStore.onDatabase
  -> saveLastConnectionMode -> DshSqliteLocalStore.submit -> queue worker
credentialLoadAfterDisconnect busy=true visible=true errorLength=0
duplicateSeqDeltas=[hello, hello]
productionEnvelopeApprovalCommand=null
testFixtureEnvelopeApprovalCommand=pwd
afterDisconnect slots=1 socketClosed=true
afterDestroy slots=2 newestSocketClosed=false
```

SSH 探针：

```bash
KEYS=$(mktemp -d)
ssh-keygen -q -t ed25519 -N review-test -f "$KEYS/encrypted-ed25519"
ssh-keygen -q -t ed25519 -N '' -f "$KEYS/plain-ed25519"
SSH_ADDRESS_SANITIZER=1 python3 scripts/test-ios-ssh.py
```

输出依次为：真实加密私钥正确口令 false、真实普通私钥空口令 true、普通私钥多余口令 false、无效加密 PEM 任意口令 true。

## 改进顺序

1. R01/R02：先恢复数据库和 OHOS 生产构建，加入不复用旧产物的 gate。
2. R03/R05/R06：异步提交结果、busy 所有权和原生资源回收；用可控完成/乱序/失败测试验证。
3. R04/R07/R09：真实私钥解析、原生日志脱敏和审批目标显示。
4. R08/R10/R11/R12：事件幂等、观察与取消分离、版本化缓存及内存预算。
5. R13/D01-D04：签名 CI、业务状态协调、接入错误策略、测试契约和真实 UI 交互。

完成标准：每个条目以反例变绿、实际调用链与验收产物为依据；设备、签名或权限缺证据时明确保留未完成，不再次统称“代码内全部完成”。

## 修复复核

R01/R03 把事务入口改为 `submitAsync(command, data, post, onResult)`，队列只执行不调用同步公共 API；配置落盘后才跳转或移交密钥。R02 恢复 `binaries.sharedLib`，任务列表出现生产 `linkDebugSharedOhosArm64` / `linkReleaseSharedOhosArm64`。R04 用 libssh2 parser 替换伪 PEM 字符串判断，并加入真实 `ssh-keygen` 样本。R05 增加凭据弹窗加载超时和 generation；R06 让 OHOS WebSocket connection 持有 slot，`finish/close/onDestroy` 统一释放。R07 只在 DEBUG 输出图片结构信息，Release 不打印 URL、路径或 Base64。

R08/R09 让 Store 返回事件是否被接纳，重复 seq 不再触发 UI；审批查询与流式 registry 统一使用 `dshWireEvent`。R10 将本地观察 detach 与显式 `session.cancel` 分离。R11 新增版本化消息缓存 codec、schema v6 和会话完整字段，本地 ID 改为启动级唯一前缀。R12 在会话淘汰/删除时同步回收 scroller rows、message states、附件缓存和 epochs。R13 改为按版本选择单一 `apksigner`。

D02 把 `DshAppError` 接入 HostClient 的流式 RPC 错误分类；D03 移除 OHOS loader 对纯 codec 的源码替换、改为 OS 边界 mocks；D04 删除设置页未实现行、让断开连接执行真实停止、把空输入 mic 改为附件入口。D01 新增并接入 `DshHomeSessionCoordinator`，并把 `DshHostClient` 的隐式连接启动改为显式 `start()`。

当前自动验证：

```text
./gradlew verifySharedArchitecture :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug
BUILD SUCCESSFUL

./gradlew -c settings.ohos.gradle.kts :shared:tasks --all
linkDebugSharedOhosArm64 / linkReleaseSharedOhosArm64 present

SSH_ADDRESS_SANITIZER=1 python scripts/test-ios-ssh.py
Objective-C SSH tests: 20 passed, 0 failed

/Applications/DevEco-Studio.app/Contents/tools/node/bin/node scripts/test-ohos-relay.cjs
OHOS Relay host tests: 60 passed, 0 failed

./gradlew -I docs/review-evidence/probe.init.gradle :shared:reviewProductionProbe
productionEnvelopeApprovalCommand=pwd
duplicateSeqDeltas=[hello]
actualStoreCommandCommitted=true

bash scripts/verify-release-apk.sh <signed Debug APK>
signature verified; unsigned Release APK correctly rejected
```

仍需外部条件：正式 GitHub Secrets、OHOS 从干净产物目录生成 HAP、iOS/OHOS/Android 真机权限与相机/扫码/SSH 端到端、Android R8 Release 真机安装、完整截图视觉审查。输入 `text(state)` 与 `textDidChange` 的 IME/光标行为也需设备复核；静态检查通过不等于视觉验收。
