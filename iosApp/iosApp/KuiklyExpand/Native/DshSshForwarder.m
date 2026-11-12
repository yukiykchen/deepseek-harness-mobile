#import "DshSshSessionInternal.h"
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

static const NSUInteger kMaxPeers = 32;
static const NSUInteger kBufferBytes = 16384;

@interface DshSshPeer : NSObject {
@public
    int fd;
    LIBSSH2_CHANNEL *channel;
    unsigned char toHost[16384];
    unsigned char toLocal[16384];
    NSUInteger hostOffset, hostSize, localOffset, localSize;
    BOOL localEof, remoteEof, eofSent, closing;
    NSTimeInterval openedAt, lastProgress;
}
@end

@implementation DshSshPeer
- (instancetype)init {
    if ((self = [super init])) { fd = -1; openedAt = lastProgress = NSProcessInfo.processInfo.systemUptime; }
    return self;
}
- (void)dealloc { if (fd >= 0) close(fd); }
@end

static BOOL wouldBlock(void) { return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR; }

static void markClosed(DshSshPeer *peer) {
    if (!peer->closing) peer->lastProgress = NSProcessInfo.processInfo.systemUptime;
    peer->closing = YES;
    if (peer->fd >= 0) { close(peer->fd); peer->fd = -1; }
}

static void moveBytes(DshSshPeer *peer) {
    if (peer->hostOffset == peer->hostSize && !peer->localEof) {
        ssize_t count = recv(peer->fd, peer->toHost, kBufferBytes, 0);
        if (count > 0) { peer->hostOffset = 0; peer->hostSize = (NSUInteger)count; peer->lastProgress = NSProcessInfo.processInfo.systemUptime; }
        else if (count == 0) peer->localEof = YES;
        else if (!wouldBlock()) { markClosed(peer); return; }
    }
    if (peer->hostOffset < peer->hostSize) {
        ssize_t count = libssh2_channel_write(peer->channel, (char *)peer->toHost + peer->hostOffset, peer->hostSize - peer->hostOffset);
        if (count > 0) { peer->hostOffset += (NSUInteger)count; peer->lastProgress = NSProcessInfo.processInfo.systemUptime; }
        else if (count < 0 && count != LIBSSH2_ERROR_EAGAIN) { markClosed(peer); return; }
    }
    if (peer->localEof && peer->hostOffset == peer->hostSize && !peer->eofSent) {
        int result = libssh2_channel_send_eof(peer->channel);
        if (result == 0) peer->eofSent = YES;
        else if (result != LIBSSH2_ERROR_EAGAIN) { markClosed(peer); return; }
    }
    if (peer->localOffset == peer->localSize && !peer->remoteEof) {
        ssize_t count = libssh2_channel_read(peer->channel, (char *)peer->toLocal, kBufferBytes);
        if (count > 0) { peer->localOffset = 0; peer->localSize = (NSUInteger)count; peer->lastProgress = NSProcessInfo.processInfo.systemUptime; }
        else if (count < 0 && count != LIBSSH2_ERROR_EAGAIN) { markClosed(peer); return; }
        else if (count == 0 && libssh2_channel_eof(peer->channel)) peer->remoteEof = YES;
    }
    if (peer->localOffset < peer->localSize) {
        ssize_t count = send(peer->fd, peer->toLocal + peer->localOffset, peer->localSize - peer->localOffset, 0);
        if (count > 0) { peer->localOffset += (NSUInteger)count; peer->lastProgress = NSProcessInfo.processInfo.systemUptime; }
        else if (count == 0 || !wouldBlock()) { markClosed(peer); return; }
    }
    // Drain all channel bytes before closing an EOF response; partial writes remain queued across polls.
    if (peer->remoteEof && peer->localOffset == peer->localSize) markClosed(peer);
    else if ((peer->hostOffset < peer->hostSize || peer->localOffset < peer->localSize) &&
        NSProcessInfo.processInfo.systemUptime - peer->lastProgress > 30) markClosed(peer);
}

@implementation DshLibsshSession (Forwarder)

- (BOOL)runForwarder:(NSInteger)remotePort {
    NSMutableArray<DshSshPeer *> *peers = [NSMutableArray array];
    DshSshPeer *opening = nil;
    NSTimeInterval keepalive = NSProcessInfo.processInfo.systemUptime;
    libssh2_keepalive_config(_session, 1, 15);
    BOOL healthy = YES;
    @try {
        while (![self isCancelled] && healthy) {
            @autoreleasepool {
                struct pollfd ready[34] = {0};
                ready[0].fd = _socket;
                ready[0].events = POLLIN;
                if (libssh2_session_block_directions(_session) & LIBSSH2_SESSION_BLOCK_OUTBOUND) ready[0].events |= POLLOUT;
                ready[1].fd = _listener; ready[1].events = POLLIN;
                NSUInteger count = 2;
                for (DshSshPeer *peer in peers) {
                    ready[count].fd = peer->fd;
                    if (peer->channel && !peer->closing) {
                        if (!peer->localEof && peer->hostOffset == peer->hostSize) ready[count].events |= POLLIN;
                        if (peer->localOffset < peer->localSize) ready[count].events |= POLLOUT;
                    }
                    count++;
                }
                int result = poll(ready, (nfds_t)count, 100);
                if ([self isCancelled]) break;
                if (result < 0) { if (errno == EINTR) continue; healthy = NO; break; }
                if (ready[0].revents & (POLLERR | POLLHUP | POLLNVAL)) { healthy = NO; break; }
                if (ready[1].revents & POLLIN) {
                    int local = accept(_listener, NULL, NULL);
                    if (local >= 0) {
                        int flags = fcntl(local, F_GETFL, 0), enabled = 1;
                        if (peers.count >= kMaxPeers || flags < 0 || fcntl(local, F_SETFL, flags | O_NONBLOCK) != 0 ||
                            setsockopt(local, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled)) != 0) close(local);
                        else {
                            setsockopt(local, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled));
                            DshSshPeer *peer = [DshSshPeer new]; peer->fd = local; [peers addObject:peer];
                        }
                    }
                }
                if (!opening) {
                    for (DshSshPeer *peer in peers) { if (!peer->channel && !peer->closing) { opening = peer; break; } }
                }
                if (opening) {
                    opening->channel = libssh2_channel_direct_tcpip_ex(_session, "127.0.0.1", (int)remotePort, "127.0.0.1", (int)_localPort);
                    if (opening->channel) opening = nil;
                    else if (libssh2_session_last_errno(_session) != LIBSSH2_ERROR_EAGAIN) { markClosed(opening); opening = nil; }
                    else if (NSProcessInfo.processInfo.systemUptime - opening->openedAt > 10) {
                        // An incomplete libssh2 channel-open operation cannot be abandoned and replaced on the same session.
                        healthy = NO; break;
                    }
                }
                for (DshSshPeer *peer in peers.copy) {
                    if (peer->channel && !peer->closing) moveBytes(peer);
                    if (peer->closing) {
                        int freed = peer->channel ? libssh2_channel_free(peer->channel) : 0;
                        if (freed == 0) {
                            peer->channel = NULL;
                            [peers removeObject:peer];
                        } else if (freed != LIBSSH2_ERROR_EAGAIN || NSProcessInfo.processInfo.systemUptime - peer->lastProgress > 10) {
                            healthy = NO;
                            break;
                        }
                    }
                }
                NSTimeInterval now = NSProcessInfo.processInfo.systemUptime;
                if (now - keepalive >= 15) {
                    int seconds = 0;
                    int sent = libssh2_keepalive_send(_session, &seconds);
                    if (sent == 0) keepalive = now;
                    else if (sent != LIBSSH2_ERROR_EAGAIN) { healthy = NO; break; }
                    else if (now - keepalive > 45) { healthy = NO; break; }
                }
            }
        }
    } @finally {
        [self cancel];
        libssh2_session_set_blocking(_session, 1);
        libssh2_session_set_timeout(_session, 1000);
        for (DshSshPeer *peer in peers) {
            markClosed(peer);
            if (peer->channel) { libssh2_channel_free(peer->channel); peer->channel = NULL; }
        }
    }
    return healthy;
}

@end
