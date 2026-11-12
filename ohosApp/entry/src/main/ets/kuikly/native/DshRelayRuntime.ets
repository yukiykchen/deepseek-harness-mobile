import { connection, webSocket } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import { callbackHasError, encodeBase64Url, webSocketText } from './DshBytes';
import { jsonObject, jsonString } from './DshJson';
import { DshRelayLoopbackServer } from './DshRelayLoopback';
import { DshRelayCredentials, DshRelaySecrets } from './DshRelaySecrets';
import { DshSealedPayload, DshSealedTunnelCrypto, DshSecureCipher } from './DshSealedTunnelCrypto';
import { HttpJsonResult } from './DshTcpHttp';
import { parsePairQr, prepareRelayNetwork, relayMessage, relayPost, toWs } from './DshRelayNetwork';
import { dshLog } from './DshOhosContext';

export class DshRelayNativeState {
  phase: string = 'IDLE';
  message: string = '';
  localPort: number = 0;
  localToken: string = '';
  hostId: string = '';
  hostName: string = '';
  relayOrigin: string = '';
  paired: boolean = false;
  generation: number = 0;
  toMap(): Record<string, Object> {
    const map: Record<string, Object> = {};
    map['phase'] = this.phase; map['message'] = this.message; map['localPort'] = this.localPort;
    map['localToken'] = this.localToken; map['hostId'] = this.hostId; map['hostName'] = this.hostName;
    map['relayOrigin'] = this.relayOrigin; map['paired'] = this.paired; map['generation'] = this.generation;
    return map;
  }
  copy(phase: string = this.phase, message: string = this.message): DshRelayNativeState {
    const next = new DshRelayNativeState();
    next.phase = phase; next.message = message; next.localPort = this.localPort; next.localToken = this.localToken;
    next.hostId = this.hostId; next.hostName = this.hostName; next.relayOrigin = this.relayOrigin;
    next.paired = this.paired; next.generation = this.generation;
    return next;
  }
}

interface RelayStore {
  load(): Promise<DshRelayCredentials | null>;
  save(master: string, token: string, host: string, name: string, origin: string, current?: () => boolean): Promise<void>;
  clear(): void;
}
interface RelayGateway {
  port: number;
  start(): Promise<number>;
  stop(): void;
  isListening(): boolean;
  onInner(type: string, payload: Record<string, Object>, channel: string): void;
}
type InnerSender = (type: string, payload: Record<string, Object>, channel: string) => void;

/** Narrow native boundaries allow deterministic tests of the production async state machine. */
export class DshRelayDependencies {
  createStore: () => RelayStore = () => new DshRelaySecrets();
  createSocket: () => webSocket.WebSocket = () => webSocket.createWebSocket();
  createGateway: (token: string, send: InnerSender) => RelayGateway = (token, send) => new DshRelayLoopbackServer(token, send);
  prepare: (origin: string, current: () => boolean) => Promise<void> = prepareRelayNetwork;
  request: (url: string, body: string, token: string, current: () => boolean) => Promise<HttpJsonResult> = relayPost;
  random: (count: number) => Promise<Uint8Array> = DshSealedTunnelCrypto.randomBytes;
  clientCipher: (master: string, session: string, client: string, server: string, proof: string) => Promise<DshSecureCipher> =
    DshSealedTunnelCrypto.createClientCipher;
}

class RelayAttempt {
  generation: number;
  credentials: DshRelayCredentials | null = null;
  socket: webSocket.WebSocket | null = null;
  cipher: DshSecureCipher | null = null;
  gateway: RelayGateway | null = null;
  sessionId: string = '';
  clientRandom: string = '';
  ticket: string = '';
  deadline: number = -1;
  sendChain: Promise<void> = Promise.resolve();
  recvChain: Promise<void> = Promise.resolve();
  pendingSends: number = 0;
  pendingReceives: number = 0;
  pendingSendBytes: number = 0;
  pendingReceiveBytes: number = 0;
  sendTimers: Map<number, () => void> = new Map();
  constructor(generation: number) { this.generation = generation; }
}

export class DshRelayRuntime {
  static readonly shared: DshRelayRuntime = new DshRelayRuntime();
  private readonly dependencies: DshRelayDependencies;
  private readonly listeners: ((state: DshRelayNativeState) => void)[] = [];
  private secrets: RelayStore | null = null;
  private active: RelayAttempt | null = null;
  private generation: number = 0;
  private stopped: boolean = true;
  private state: DshRelayNativeState = new DshRelayNativeState();
  private reconnectTimer: number = -1;
  private netConnection: connection.NetConnection | null = null;
  constructor(dependencies: DshRelayDependencies = new DshRelayDependencies()) { this.dependencies = dependencies; }

  addListener(listener: (state: DshRelayNativeState) => void): void {
    this.listeners.push(listener); listener(this.state);
  }
  removeListener(listener: (state: DshRelayNativeState) => void): void {
    const index = this.listeners.indexOf(listener); if (index >= 0) this.listeners.splice(index, 1);
  }
  current(): DshRelayNativeState { return this.state; }
  private store(): RelayStore {
    if (!this.secrets) this.secrets = this.dependencies.createStore();
    return this.secrets;
  }
  async attach(): Promise<void> {
    const generation = this.generation;
    try {
      const credentials = await this.store().load();
      if (generation !== this.generation || this.active || !credentials) return;
      this.applyCredentials(credentials);
      this.publish(this.state.copy());
    } catch (error) { dshLog('Relay pairing read unavailable'); }
  }

  async pairFromQr(qr: string): Promise<Record<string, Object>> {
    let generation = -1;
    let master = '', claim = '';
    try {
      const link = parsePairQr(qr);
      master = link.masterKey;
      this.stopResources(); generation = ++this.generation;
      this.publish(this.idle('PAIRING', '正在配对'));
      const current = (): boolean => generation === this.generation && this.state.phase === 'PAIRING';
      claim = await DshSealedTunnelCrypto.deriveClaimToken(master);
      if (!current()) return failResult('配对操作已取消');
      await this.dependencies.prepare(link.origin, current);
      if (!current()) return failResult('配对操作已取消');
      const payload: Record<string, Object> = {};
      payload['pairId'] = link.pairId; payload['claimToken'] = claim; payload['deviceLabel'] = 'HarmonyOS'; payload['platform'] = 'ohos';
      const response = await this.dependencies.request(`${link.origin}/pair/claim-device`, JSON.stringify(payload), '', current);
      if (!current()) return failResult('配对操作已取消');
      if (response.code < 200 || response.code >= 300) throw new Error('配对请求未被接受，请重新生成二维码');
      const host = jsonString(response.json, 'hostId'), token = jsonString(response.json, 'clientToken');
      const name = jsonString(response.json, 'hostName', '电脑');
      if (!host || !token) throw new Error('配对响应缺少凭据');
      await this.store().save(master, token, host, name, link.origin, current);
      if (!current()) return failResult('配对操作已取消');
      this.applyCredentials(new DshRelayCredentials(master, token, host, name, link.origin));
      this.publish(this.idle('IDLE', '已配对'));
      const result: Record<string, Object> = {};
      result['ok'] = true; result['hostId'] = host; result['hostName'] = name;
      result['relayOrigin'] = link.origin; result['pairedAt'] = Date.now();
      return result;
    } catch (error) {
      const message = relayMessage(error as Object, [master, claim]);
      if (generation >= 0 && generation === this.generation) this.publish(this.idle('ERROR', message));
      return failResult(message);
    }
  }

  async connect(): Promise<void> {
    this.stopped = false;
    const active = this.active;
    if (active && (this.state.phase !== 'READY' || active.gateway?.isListening())) return;
    await this.startAttempt();
  }
  disconnect(): void {
    this.stopResources(); this.publish(this.idle('STOPPED', '扫码连接已断开'));
  }
  async forget(): Promise<void> {
    this.stopResources(); this.store().clear();
    this.state = new DshRelayNativeState(); this.publish(this.idle('IDLE', '已移除配对'));
  }

  private async startAttempt(): Promise<void> {
    if (this.stopped) return;
    this.clearReconnect(); this.releaseAttempt();
    const attempt = new RelayAttempt(++this.generation);
    this.active = attempt;
    this.publish(this.idle('CONNECTING', '正在申请访问票'));
    if (!this.accepts(attempt)) return;
    this.registerNetwork();
    attempt.deadline = setTimeout(() => {
      if (this.accepts(attempt) && this.state.phase !== 'READY') this.fail(attempt, '扫码隧道握手超时');
    }, 30000);
    try {
      const credentials = await this.store().load();
      if (!this.accepts(attempt)) return;
      if (!credentials) { this.fail(attempt, '请重新扫码配对电脑', true); return; }
      attempt.credentials = credentials;
      this.applyCredentials(credentials);
      await this.dependencies.prepare(credentials.origin, () => this.accepts(attempt));
      if (!this.accepts(attempt)) return;
      const response = await this.dependencies.request(`${credentials.origin}/access-ticket`, '{}', credentials.clientToken, () => this.accepts(attempt));
      if (!this.accepts(attempt)) return;
      if (response.code < 200 || response.code >= 300) {
        this.fail(attempt, '访问票申请失败，请检查网络或重新扫码', response.code === 401 || response.code === 403); return;
      }
      attempt.sessionId = jsonString(response.json, 'accessSessionId'); attempt.ticket = jsonString(response.json, 'ticket');
      const hostId = jsonString(response.json, 'hostId');
      if (hostId) { const next = this.state.copy(); next.hostId = hostId; this.state = next; }
      const tunnel = jsonString(response.json, 'tunnelUrl', `${toWs(credentials.origin)}/client-tunnel`);
      if (!attempt.sessionId || !attempt.ticket) throw new Error('访问票内容无效');
      const random = await this.dependencies.random(32);
      if (!this.accepts(attempt)) return;
      attempt.clientRandom = encodeBase64Url(random);
      const proof = await DshSealedTunnelCrypto.clientProof(credentials.masterKey, attempt.sessionId, attempt.clientRandom);
      if (!this.accepts(attempt)) return;
      const hello: Record<string, Object> = {};
      hello['accessSessionId'] = attempt.sessionId; hello['clientRandomB64'] = attempt.clientRandom; hello['clientProofB64'] = proof;
      this.publish(this.state.copy('HANDSHAKING', '正在建立加密隧道'));
      if (this.accepts(attempt)) this.openTunnel(attempt, tunnel, envelope('client_hello', hello));
    } catch (error) { if (this.accepts(attempt)) this.fail(attempt, relayMessage(error as Object)); }
  }

  private openTunnel(attempt: RelayAttempt, tunnel: string, hello: Record<string, Object>): void {
    const ws = this.dependencies.createSocket(); attempt.socket = ws;
    let opened = false;
    ws.on('open', (error: Object) => {
      if (!this.accepts(attempt) || attempt.socket !== ws) return;
      if (callbackHasError(error)) { this.fail(attempt, '扫码事件流连接失败'); return; }
      if (opened) return; opened = true;
      this.sendSocket(attempt, ws, JSON.stringify(hello)).catch(() => this.fail(attempt, '扫码隧道发送失败'));
    });
    ws.on('message', (first: Object, second: Object) => {
      if (!this.accepts(attempt) || attempt.socket !== ws) return;
      const text = webSocketText(first, second);
      if (!text) return;
      if (text.length > 16 * 1024 * 1024 - attempt.pendingReceiveBytes || attempt.pendingReceives >= 64) {
        this.fail(attempt, '隧道消息超过上限'); return;
      }
      attempt.pendingReceives++;
      attempt.pendingReceiveBytes += text.length;
      const run = async (): Promise<void> => {
        try { if (this.accepts(attempt)) await this.handleOuter(attempt, text); }
        catch (error) { this.fail(attempt, '扫码隧道消息验证失败'); }
        finally { attempt.pendingReceives--; attempt.pendingReceiveBytes -= text.length; }
      };
      attempt.recvChain = attempt.recvChain.then(run, run);
    });
    ws.on('close', () => this.fail(attempt, '电脑连接已断开'));
    ws.on('error', () => this.fail(attempt, '扫码事件流连接失败'));
    const options: webSocket.WebSocketRequestOptions = {};
    options.header = { 'Authorization': `Bearer ${attempt.ticket}` };
    ws.connect(tunnel, options).catch(() => this.fail(attempt, '扫码事件流连接失败'));
  }

  private async handleOuter(attempt: RelayAttempt, text: string): Promise<void> {
    if (!this.accepts(attempt)) return;
    const message = JSON.parse(text) as Record<string, Object>;
    const type = jsonString(message, 'type'), payload = jsonObject(message, 'payload');
    if (type === 'server_hello') {
      if (attempt.cipher) return;
      const credentials = attempt.credentials;
      if (!credentials) return;
      let cipher: DshSecureCipher;
      try {
        cipher = await this.dependencies.clientCipher(credentials.masterKey, attempt.sessionId, attempt.clientRandom,
          jsonString(payload, 'serverRandomB64'), jsonString(payload, 'serverProofB64'));
      } catch (error) { this.fail(attempt, '加密验证失败，请重新扫码', true); return; }
      if (!this.accepts(attempt)) return;
      const token = encodeBase64Url(await this.dependencies.random(24));
      if (!this.accepts(attempt)) return;
      attempt.cipher = cipher;
      const server = this.dependencies.createGateway(token, (type, payload, channel) => this.enqueueSend(attempt, type, payload, channel));
      attempt.gateway = server;
      const port = await server.start();
      if (!this.accepts(attempt)) { server.stop(); return; }
      if (port < 1 || port > 65535) throw new Error('网关端口无效');
      if (attempt.deadline >= 0) clearTimeout(attempt.deadline);
      attempt.deadline = -1;
      const ready = this.state.copy('READY', '扫码隧道已连接'); ready.localToken = token; ready.localPort = port;
      this.publish(ready);
    } else if (type === 'sealed') {
      const cipher = attempt.cipher, server = attempt.gateway;
      if (!cipher || !server) throw new Error('隧道未完成握手');
      const opened = await cipher.open(new DshSealedPayload(jsonString(payload, 'seq'), jsonString(payload, 'ciphertextB64')));
      if (!this.accepts(attempt) || attempt.gateway !== server) return;
      server.onInner(jsonString(opened, 'type'), jsonObject(opened, 'payload'), jsonString(opened, 'channel'));
    } else if (type === 'device_close' || type === 'close') this.fail(attempt, '电脑连接已断开');
  }

  private enqueueSend(attempt: RelayAttempt, type: string, payload: Record<string, Object>, channel: string): void {
    if (!this.accepts(attempt) || !attempt.cipher || !attempt.socket) return;
    if (attempt.pendingSends >= 64) { this.fail(attempt, '隧道发送队列超过上限'); return; }
    const snapshot = JSON.stringify(envelope(type, payload, channel));
    if (snapshot.length > 16 * 1024 * 1024 - attempt.pendingSendBytes) { this.fail(attempt, '隧道发送队列超过上限'); return; }
    attempt.pendingSends++;
    attempt.pendingSendBytes += snapshot.length;
    const run = async (): Promise<void> => {
      try {
        const cipher = attempt.cipher, ws = attempt.socket;
        if (!this.accepts(attempt) || !cipher || !ws) return;
        const sealed = await cipher.seal(JSON.parse(snapshot) as Record<string, Object>);
        if (!this.accepts(attempt) || attempt.socket !== ws) return;
        const payload: Record<string, Object> = {};
        payload['accessSessionId'] = attempt.sessionId; payload['seq'] = sealed.seq; payload['ciphertextB64'] = sealed.ciphertextB64;
        await this.sendSocket(attempt, ws, JSON.stringify(envelope('sealed', payload)));
      } catch (error) { this.fail(attempt, '扫码隧道发送失败'); }
      finally { attempt.pendingSends--; attempt.pendingSendBytes -= snapshot.length; }
    };
    attempt.sendChain = attempt.sendChain.then(run, run);
  }
  private sendSocket(attempt: RelayAttempt, ws: webSocket.WebSocket, text: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      if (!this.accepts(attempt)) { reject(new Error('连接操作已取消')); return; }
      const timer = setTimeout(() => {
        attempt.sendTimers.delete(timer); reject(new Error('隧道发送超时'));
      }, 20000);
      attempt.sendTimers.set(timer, () => { clearTimeout(timer); reject(new Error('连接操作已取消')); });
      try {
        ws.send(text, (error: BusinessError, ok: boolean) => {
          clearTimeout(timer);
          attempt.sendTimers.delete(timer);
          if (callbackHasError(error) || !ok) reject(new Error('隧道发送失败')); else resolve();
        });
      } catch (error) { clearTimeout(timer); attempt.sendTimers.delete(timer); reject(new Error('隧道发送失败')); }
    });
  }
  private accepts(attempt: RelayAttempt): boolean { return !this.stopped && this.active === attempt && this.generation === attempt.generation; }
  private fail(attempt: RelayAttempt, message: string, permanent: boolean = false): void {
    if (!this.accepts(attempt)) return;
    const credentials = attempt.credentials;
    const safe = relayMessage(new Error(message), [credentials?.masterKey ?? '', credentials?.clientToken ?? '', attempt.ticket, this.state.localToken]);
    this.releaseAttempt(); this.generation++;
    if (permanent) { this.stopped = true; this.unregisterNetwork(); }
    this.publish(this.idle(permanent ? 'ERROR' : 'RECONNECTING', safe));
    if (this.stopped) return;
    const generation = this.generation;
    this.clearReconnect();
    this.reconnectTimer = setTimeout(() => {
      if (!this.stopped && generation === this.generation && !this.active) { this.reconnectTimer = -1; this.startAttempt(); }
    }, 2000);
  }
  private stopResources(): void {
    this.stopped = true; this.generation++; this.clearReconnect(); this.unregisterNetwork(); this.releaseAttempt();
  }
  private releaseAttempt(): void {
    const attempt = this.active; this.active = null;
    if (!attempt) return;
    if (attempt.deadline >= 0) clearTimeout(attempt.deadline);
    attempt.sendTimers.forEach((cancel: () => void) => cancel()); attempt.sendTimers.clear();
    const ws = attempt.socket; attempt.socket = null; attempt.cipher = null;
    if (ws) { try { ws.close().catch(() => {}); } catch (error) {} }
    attempt.gateway?.stop(); attempt.gateway = null;
  }
  private clearReconnect(): void { if (this.reconnectTimer >= 0) clearTimeout(this.reconnectTimer); this.reconnectTimer = -1; }
  private registerNetwork(): void {
    if (this.netConnection) return;
    try {
      const net = connection.createNetConnection(); this.netConnection = net;
      net.on('netAvailable', () => {
        if (this.netConnection === net && !this.stopped && !this.active) this.startAttempt();
      });
      net.register((error: BusinessError) => {
        if (callbackHasError(error) && this.netConnection === net) { this.netConnection = null; dshLog('Relay network registration failed'); }
      });
    } catch (error) { this.netConnection = null; }
  }
  private unregisterNetwork(): void {
    const net = this.netConnection; this.netConnection = null;
    if (net) { try { net.unregister(() => {}); } catch (error) {} }
  }
  private applyCredentials(value: DshRelayCredentials): void {
    const next = this.state.copy(); next.hostId = value.hostId; next.hostName = value.hostName;
    next.relayOrigin = value.origin; next.paired = true; this.state = next;
  }
  private idle(phase: string, message: string): DshRelayNativeState {
    const next = this.state.copy(phase, message); next.localPort = 0; next.localToken = ''; next.generation = this.generation; return next;
  }
  private publish(next: DshRelayNativeState): void {
    this.state = next;
    const listeners = this.listeners.slice();
    for (let i = 0; i < listeners.length; i++) {
      if (this.state !== next) break;
      if (!this.listeners.includes(listeners[i])) continue;
      try { listeners[i](next); } catch (error) { dshLog('Relay state listener failed'); }
    }
  }
}

function envelope(type: string, payload: Record<string, Object>, channel: string = ''): Record<string, Object> {
  const value: Record<string, Object> = {};
  value['v'] = 1; value['type'] = type; value['id'] = util.generateRandomUUID(); value['ts'] = Date.now(); value['payload'] = payload;
  if (channel) value['channel'] = channel;
  return value;
}
function failResult(message: string): Record<string, Object> {
  const value: Record<string, Object> = {}; value['ok'] = false; value['message'] = message; return value;
}
