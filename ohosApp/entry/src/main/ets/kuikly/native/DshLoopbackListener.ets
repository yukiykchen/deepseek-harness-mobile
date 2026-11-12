import { socket } from '@kit.NetworkKit';

/** API 12 has no TCP server close method. Own one listener for the process, not one leaked port per reconnect. */
export class DshLoopbackListener {
  private static server: socket.TCPSocketServer | null = null;
  private static starting: Promise<number> | null = null;
  private static owner: Object | null = null;
  private static receiver: ((client: socket.TCPSocketConnection) => void) | null = null;

  static async acquire(owner: Object, receiver: (client: socket.TCPSocketConnection) => void): Promise<number> {
    DshLoopbackListener.owner = owner;
    DshLoopbackListener.receiver = receiver;
    if (!DshLoopbackListener.starting) DshLoopbackListener.starting = DshLoopbackListener.bind();
    try { return await DshLoopbackListener.starting; }
    catch (error) { DshLoopbackListener.starting = null; throw new Error('Loopback bind failed'); }
  }

  static release(owner: Object): void {
    if (DshLoopbackListener.owner === owner) {
      DshLoopbackListener.owner = null;
      DshLoopbackListener.receiver = null;
    }
  }

  private static async bind(): Promise<number> {
    const server = socket.constructTCPSocketServerInstance();
    DshLoopbackListener.server = server;
    server.on('connect', (client: socket.TCPSocketConnection) => {
      const receiver = DshLoopbackListener.receiver;
      if (receiver) receiver(client); else client.close().catch(() => {});
    });
    await server.listen({ address: '127.0.0.1', port: 0, family: 1 });
    const address = await server.getLocalAddress();
    const port = address.port ?? 0;
    if (port < 1 || port > 65535) throw new Error('Invalid loopback port');
    return port;
  }
}
