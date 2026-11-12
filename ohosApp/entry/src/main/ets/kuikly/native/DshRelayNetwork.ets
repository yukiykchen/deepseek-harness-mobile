import { connection } from '@kit.NetworkKit';
import url from '@ohos.url';
import { HttpJsonResult, postJsonOverSystemHttp, postJsonOverTcp } from './DshTcpHttp';
import { dshLog } from './DshOhosContext';

export class DshPairLink {
  origin: string;
  pairId: string;
  masterKey: string;
  constructor(origin: string, pairId: string, masterKey: string) {
    this.origin = origin; this.pairId = pairId; this.masterKey = masterKey;
  }
}

export function parsePairQr(raw: string): DshPairLink {
  const parsed = new url.URL(raw.trim());
  if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || parsed.username || parsed.password ||
    !parsed.hostname || (parsed.port && (Number(parsed.port) < 1 || Number(parsed.port) > 65535))) {
    throw new Error('二维码地址无效');
  }
  const fragment = parsed.hash.substring(1);
  let query = parsed.search;
  if (fragment.includes('?')) query = fragment.substring(fragment.indexOf('?') + 1);
  else if (fragment.includes('=')) query = fragment;
  const params = new url.URLParams(query);
  const ids = params.getAll('id'), keys = params.getAll('key');
  if (ids.length !== 1 || keys.length !== 1 || !ids[0] || !keys[0]) throw new Error('二维码缺少唯一的配对参数');
  if (['localhost', '127.0.0.1', '::1', '[::1]'].includes(parsed.hostname.toLowerCase())) {
    throw new Error('二维码里是电脑本机地址，手机访问不到。请让电脑用局域网 IP 生成二维码。');
  }
  return new DshPairLink(parsed.origin, ids[0], keys[0]);
}

export function toWs(origin: string): string {
  const parsed = new url.URL(origin);
  parsed.protocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
  return parsed.toString().replace(/\/$/, '');
}

export function relayMessage(error: Object, secrets: string[] = []): string {
  let message = error instanceof Error ? error.message : '扫码隧道失败';
  for (let i = 0; i < secrets.length; i++) if (secrets[i]) message = message.split(secrets[i]).join('[redacted]');
  return message.substring(0, 512);
}

export async function relayPost(url: string, body: string, token: string = '', current: () => boolean = () => true): Promise<HttpJsonResult> {
  if (!current()) throw new Error('连接操作已取消');
  try {
    return await postJsonOverSystemHttp(url, body, token, 20000);
  } catch (error) {
    // Only a live attempt may start fallback I/O after the system request failed.
    if (current() && url.startsWith('http://')) return await postJsonOverTcp(url, body, token, 20000);
    throw new Error('Relay 网络请求失败');
  }
}

/** Preserve the IPv4 Wi-Fi workaround; cancelled attempts must not change process-wide network routing. */
let routeChain: Promise<void> = Promise.resolve();
export function prepareRelayNetwork(origin: string, current: () => boolean): Promise<void> {
  const run = (): Promise<void> => bindRelayNetwork(origin, current);
  routeChain = routeChain.then(run, run);
  return routeChain;
}

async function bindRelayNetwork(origin: string, current: () => boolean): Promise<void> {
  const host = new url.URL(origin).hostname;
  if (!isIpv4(host) || !current()) return;
  const parts = host.split('.').map((value: string) => Number(value));
  const privateLan = parts[0] === 10 || parts[0] === 127 || (parts[0] === 192 && parts[1] === 168) ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31);
  try {
    const nets = await connection.getAllNets();
    if (!current()) return;
    for (let i = 0; i < nets.length; i++) {
      const caps = await connection.getNetCapabilities(nets[i]);
      if (!current()) return;
      const wifi = caps.bearerTypes.includes(connection.NetBearType.BEARER_WIFI) ||
        caps.bearerTypes.includes(connection.NetBearType.BEARER_ETHERNET);
      if (wifi && (privateLan || !caps.networkCap || caps.networkCap.length === 0 ||
        caps.networkCap.includes(connection.NetCap.NET_CAPABILITY_INTERNET))) {
        await connection.setAppNet(nets[i]);
        return;
      }
    }
  } catch (error) { dshLog('Relay IPv4 network binding unavailable'); }
}

function isIpv4(host: string): boolean {
  const parts = host.split('.');
  return parts.length === 4 && parts.every((value: string) => /^\d{1,3}$/.test(value) && Number(value) <= 255);
}
