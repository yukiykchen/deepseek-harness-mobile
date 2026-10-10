# App 与 Host 协议

这份文档记录 **DSH App 实际调用的 Host 协议**，方便对照代码和官方 Harness API。权威实现在 `shared/src/commonMain/kotlin/com/example/dsh/dsh/` 下的 `DshHostProtocol.kt`（常量与时间线解析）和 `DshHostClient.kt`（RPC 调用）。方法名与官方 `packages/host/apiproxy` 对齐，App **不自定 JSON-RPC 方法**。

扫码 Relay 的配对、密封隧道不属于 Host 协议，见 [dsh-scan-remote](https://github.com/yukiykchen/dsh-scan-remote)。配对成功后，App 只对 **本机 loopback 上的 Host** 说话，信封与 SSH 相同。

手机内嵌 Harness（本地模式）已独立为 **DSH Local** 工程，协议信封仍与下表一致，但不在本仓库打包。

## 1. 连接方式与信封

| 模式 | `baseUrl` | 鉴权 | 下行事件 | 实现 |
| --- | --- | --- | --- | --- |
| 扫码（本 App） | 本机网关（Relay 转到电脑 `:3080`） | `Authorization: Bearer <token>` | WebSocket：`/api/events.mux` + `/api/events.host` | `DshHostClient` |
| SSH（本 App） | `http://127.0.0.1:<转发端口>` | 同扫码，token 可空 | 同扫码 WebSocket | `DshHostClient` |
| 手机本地（DSH Local） | `http://127.0.0.1:3080` | 内嵌 Host，通常无 Bearer | SSE：`GET /api/events.mux` | DSH Local 仓库（本仓库已移除） |

上行 RPC 三种模式都是：

```text
POST {baseUrl}/api/{method}
Content-Type: application/json
Authorization: Bearer <token>   // token 非空时
```

本地模式 SSE 失败或超时（约 3s）会退回轮询 `session.history`。远程模式 **mux 不断线重放漏掉的 `session/event`**，重连后必须再拉一次 `session.history`。

## 2. RPC 信封

请求：

```json
{
  "type": "client-request",
  "rpcId": "dsh-g1-12",
  "method": "session.prompt",
  "payload": { }
}
```

`rpcId` 由 App 生成：`dsh-g{connectionGeneration}-{seq}`。连接世代失效时，在途请求以 `generation-cancelled` 结束。

成功响应取 `result.ok == true` 的 `result.value`：

```json
{
  "result": {
    "ok": true,
    "value": { }
  }
}
```

失败：

```json
{
  "result": {
    "ok": false,
    "error": { "code": "...", "message": "...", "details": { } }
  }
}
```

超时 30 秒。传输错误码形如 `transport-{httpStatus}`。

审批 / 提问 **不是** unary RPC，见第 6 节 `POST /api/respond`。

## 3. 远程就绪顺序

远程 `DshHostConnectionRuntime` 在 `productReady` 之前会排队 RPC。就绪步骤：

1. 同时打开 WS `/api/events.mux` 与 `/api/events.host`
2. `host.describe`
3. 并行 `workspace.list`、`session.list` 作为基线
4. `READY` 后冲刷缓冲帧、发出排队中的 RPC

断开后 `generation++`，1 秒后重连。

## 4. App 已调用的方法

常量在 `DshHostProtocol`。下表是当前仓库 **真正发出去的** 调用。

### 握手与凭据

| method | 主要 payload | 用途 |
| --- | --- | --- |
| `host.describe` | `{}` | 远程握手 |
| `llm.providers` | `{}` | 是否存在 `deepseek-official` |
| `settings.describe` | `{}` | 读 `llm-deepseek` 的 `apiKeyEnv` |
| `credentials.describe` | `{ refs: ["DEEPSEEK_API_KEY"] }` | Key 是否已配置、是否可写 |
| `credentials.set` | `{ ref, value }` | 在凭据弹窗里修改电脑端 DSH 的 Key |

### 工作区与目录

| method | 主要 payload | 用途 |
| --- | --- | --- |
| `workspace.list` | `{}` | 基线：`items`、`archivedSessionIds` |
| `workspace.create` | `{ path }` | 新建工作区 |
| `workspace.rename` | `{ workspaceId, title }` | 改名 |
| `workspace.delete` | `{ workspaceId }` | 删除工作区 |
| `workspace.insertBefore` | `{ workspaceId, beforeWorkspaceId? }` | 排序 |
| `workspace.archiveSession` | `{ sessionId }` | 归档会话 |
| `host.listDirectory` | `{ path? }` | 选目录 |
| `host.createDirectory` | `{ path, name }` | 建子目录 |

### 会话

| method | 主要 payload | 用途 |
| --- | --- | --- |
| `session.list` | `{}` | `items[]`：`sessionId`、`running`、`blank`、`cwd`、`projections.values.title`、`agentPreset` |
| `session.create` | `{ workspaceId? }` | 返回 `sessionId` |
| `session.history` | `{ sessionId, maxMessages: 80 }` | 重放时间线；条目含 `event` 与可选 `view` |
| `session.models` | `{ sessionId }` | 当前模型与分组列表 |
| `session.selectModel` | `{ sessionId, provider, model, reasoningEffort? }` | 切换模型 |
| `session.prompt` | 见第 5 节 | 发用户消息 |
| `session.cancel` | `{ sessionId }` | 停止生成 |
| `session.rename` | `{ sessionId, title }` | 改会话标题 |
| `session.fork` | `{ sessionId, atSeq? }` | 分叉 |
| `session.updateQueue` | `{ sessionId, itemId, action }` | 队列 `edit` / `remove` / `steer` |
| `session.attachment` | `{ sessionId, attachmentId }` | 读历史图片：`attachment` + Base64 `data` |
| `skill.list` | `{ sessionId }` | `/` 补全用的 skill 列表 |
| `agentPreset.list` | （已声明常量，UI 目前只展示会话上的 preset 名） | 预留 |
| `goal.edit` / `pause` / `resume` / `clear` | `{ sessionId, ref: { id, revision }, objective? }` | Goal 条 |

导出不是 RPC：`GET /api/session.export?sessionId=&includeDescendants=`。

队列 `action` 示例：

```json
{ "kind": "edit", "content": [{ "type": "text", "text": "..." }] }
{ "kind": "remove" }
{ "kind": "steer" }
```

## 5. `session.prompt` 与流式

当前 App **只发文本**：

```json
{
  "sessionId": "...",
  "mode": "queue",
  "content": [{ "type": "text", "text": "用户输入" }]
}
```

`mode` 固定 `queue`。若 Host 立刻返回 `command.kind == success`，当作 slash 命令完成，不再等流。

官方图片通道（尚未从输入区发出）应是同一 `content` 数组里再加：

```json
{ "type": "image", "mediaType": "image/png", "data": "<canonical-base64>", "name": "photo.png" }
```

只支持 PNG / JPEG / WebP / GIF。限额看 Host 的 `imageLimits` projection。PDF 等通用文件 **不在** 该协议里。

流式结果不走 prompt 的 HTTP 响应体，而走 mux 上的 `session/event`。App 用 prompt 的 `rpcId` 对上事件 `source.rpcId`。`turn/end` 结束一轮。重连后若 Host 仍在跑，用 `adoptLiveStream` 挂上现有 turn，不重新 prompt。

## 6. 下行：mux / host / respond

信封常见形状：`{ "type", "payload" }`。远程连两条 WebSocket。

### `/api/events.mux`

| type | App 行为 |
| --- | --- |
| `session/subscribed` | 记下 `lastSeq` |
| `session/event` | 写入 store；驱动工具卡片与流式 delta |
| `session/queue` | 刷新队列码头 |
| `session/jobs` | 刷新 jobs |
| `session/projection` | 标题等投影 |
| `approval/requested` | 审批卡；用信封或 payload 的 `rpcId` |
| `question/requested` | 提问卡 |
| `approval/resolved` / `question/resolved` | 清 pending |

`session/event` 里 App 关心的 `event.type`：

| type | 用途 |
| --- | --- |
| `user/message` | 用户气泡或上下文注入（`source.kind != user`） |
| `assistant/chunk` | `text` / `text-delta` 正文；`reasoning-delta` 为 Think；`finish` 可带 error |
| `assistant/message` | 该步完整正文 |
| `tool/call` + `tool/result` | 折叠为一条工具卡片，优先用帧上的 `view` |
| `turn/end` | 结束流；`reason.error` 为失败 |

上下文、工具 `view.card`（terminal / read / diff / search / web）见 `DshWebTimelineParser`、`DshRemoteToolCallModels`。

### `/api/events.host`

| type | 用途 |
| --- | --- |
| `host/remote-event` | 设置变更等 |
| `host/session-added` | 新空白会话 |
| `host/session-status` | `running` |
| `host/session-removed` | 删会话并清缓存 |
| `host/workspace-order-changed` | 工作区顺序 |

### `POST /api/respond`

回答 Host 发起的审批 / 提问（body **不是** `client-request`）：

```json
{
  "type": "client-response",
  "rpcId": "<requested 帧上的 rpcId>",
  "result": { "ok": true, "value": { } }
}
```

审批 `value`：`{ sessionId, approvalId, outcome }`，`outcome` 仅 `allowed-once` | `rejected`。

提问 `value`：`{ sessionId, answer }`，`answer` 形如 `{ answers: [{ id, selected: [...], custom? }] }`。

## 7. 代码入口

| 文件 | 职责 |
| --- | --- |
| `DshHostProtocol.kt` | 路径常量、`session.history` 时间线解析 |
| `DshHostConnectionRuntime.kt` | 连接世代、mux/host WebSocket、RPC 排队与发送 |
| `DshHostClient.kt` | 扫码 / SSH 共用的 Host 客户端：所有 RPC、流式回复、事件分发 |
| `DshToolViewBodies.kt` | 工具 `view`（terminal/read/diff/search/web）解析 |
| `DshRemoteToolCallModel.kt` | `tool/call`+`result` → 卡片模型 |
| `DshHostStore.kt` | 会话、事件、队列、pending 内存投影 |
| `DshWebSocketModule.kt` | 传输 |

官方对照：

- [sessions.ts](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/host/apiproxy/src/api/sessions.ts)
- [api-proxy.ts](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/host/apiproxy/src/api-proxy.ts)
- [attachment README](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/attachment/attachment/README.md)

## 8. 明确未接或未发的

- 发图：`session.attachment` 已能读历史图；输入区尚未把 `type: image` 放进 `session.prompt`
- 通用文件 / PDF 上传：官方无此 RPC
- 插件启停：`pluginInventory/list` 只读，App 未接
- 永久删除会话：官方归档有，删除存储需扩 Host
- `session.prompt` 的 `mode: "steer"`：队列里的 steer 走 `session.updateQueue`，不是改 prompt mode
