import { webSocket } from '@kit.NetworkKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { callbackHasError, webSocketText } from '../native/DshBytes';
import { jsonString, mapOf, moduleParams } from '../native/DshJson';
import { DshOhosContext, dshError, dshLog } from '../native/DshOhosContext';

class WebSocketConnection {
  private readonly url: string;
  private readonly token: string;
  private readonly emit: (event: Record<string, Object>) => void;
  private readonly onFinished: () => void;
  private socket: webSocket.WebSocket | null = null;
  private slot: string = '';
  private closed: boolean = false;
  private finished: boolean = false;

  constructor(
    url: string,
    token: string,
    emit: (event: Record<string, Object>) => void,
    onFinished: () => void,
    slot: string,
  ) {
    this.url = url;
    this.token = token;
    this.emit = emit;
    this.onFinished = onFinished;
    this.slot = slot;
  }

  setSlot(slot: string): void {
    this.slot = slot;
    if (this.finished || this.closed) DshOhosContext.unbindSlot(slot);
  }

  start(): void {
    const ws = webSocket.createWebSocket();
    this.socket = ws;
    ws.on('open', (err: Object) => {
      if (this.closed) {
        return;
      }
      if (callbackHasError(err)) {
        dshError(`Host WebSocket failed url=${this.url}`, err);
        this.emit(mapOf(['kind', 'message'], ['ERROR', errorMessage(err)]));
        this.finish();
        return;
      }
      this.emit(mapOf(['kind'], ['OPEN']));
    });
    ws.on('message', (err: Object, value: Object) => {
      if (this.closed) {
        return;
      }
      const text = webSocketText(err, value);
      if (text.length > 0) {
        this.emit(mapOf(['kind', 'data'], ['FRAME', text]));
      }
    });
    ws.on('close', (_err: Object, value: Object) => {
      this.socket = null;
      if (!this.closed) {
        const rec = value as Record<string, Object>;
        const reason = rec && typeof rec['reason'] === 'string' ? rec['reason'] as string : '';
        this.emit(mapOf(['kind', 'message'], ['CLOSED', reason]));
      }
      this.finish();
    });
    ws.on('error', (error: Object) => {
      this.socket = null;
      if (!this.closed) {
        dshError(`Host WebSocket failed url=${this.url}`, error);
        this.emit(mapOf(['kind', 'message'], ['ERROR', errorMessage(error)]));
      }
      this.finish();
    });
    const options: webSocket.WebSocketRequestOptions = {};
    if (this.token.length > 0) {
      options.header = mapOf(['Authorization'], [`Bearer ${this.token}`]);
    }
    ws.connect(this.url, options).catch((error: Object) => {
      dshError('websocket connect failed', error);
      if (!this.closed) {
        this.emit(mapOf(['kind', 'message'], ['ERROR', errorMessage(error)]));
      }
      this.finish();
    });
  }

  close(): void {
    this.closed = true;
    try {
      this.socket?.close({ code: 1000, reason: 'client closed' });
    } catch (error) {
    }
    this.socket = null;
    this.finish();
  }

  private finish(): void {
    if (!this.finished) {
      this.finished = true;
      this.onFinished();
    }
    DshOhosContext.unbindSlot(this.slot);
  }
}

export class KRDshWebSocketModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = 'DshWebSocketModule';
  private readonly connections: Map<string, WebSocketConnection> = new Map();

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const value = moduleParams(params as Object);
    if (method === 'connect') {
      const connectionId = jsonString(value, 'connectionId');
      const url = jsonString(value, 'url');
      if (connectionId.length === 0 || url.length === 0 || !callback) {
        return null;
      }
      this.connections.get(connectionId)?.close();
      dshLog('connect requested');
      const connection = new WebSocketConnection(
        url,
        jsonString(value, 'token'),
        (event: Record<string, Object>) => {
          DshOhosContext.postToMain(connection.slot, event);
        },
        () => {
          if (this.connections.get(connectionId) === connection) {
            this.connections.delete(connectionId);
          }
        },
        '',
      );
      const slot = DshOhosContext.bindSlot((event: Record<string, Object>) => {
        callback(event);
      });
      connection.setSlot(slot);
      this.connections.set(connectionId, connection);
      connection.start();
      return null;
    }
    if (method === 'disconnect') {
      const connectionId = jsonString(value, 'connectionId');
      this.connections.get(connectionId)?.close();
      this.connections.delete(connectionId);
    }
    return null;
  }

  onDestroy(): void {
    for (const connection of this.connections.values()) {
      connection.close();
    }
    this.connections.clear();
    super.onDestroy();
  }
}

function errorMessage(error: Object): string {
  if (error instanceof Error && error.message.length > 0) {
    return error.message;
  }
  return 'WebSocket connection failed';
}
