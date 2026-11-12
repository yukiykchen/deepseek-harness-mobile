#import "DshSshSessionInternal.h"
#import <CommonCrypto/CommonDigest.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>

static NSTimeInterval deadlineAfter(NSTimeInterval seconds) { return NSProcessInfo.processInfo.systemUptime + seconds; }

static NSArray<NSData *> *resolveAddresses(DshLibsshSession *owner, NSString *host, NSInteger port, NSTimeInterval deadline) {
    static dispatch_queue_t resolver;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ resolver = dispatch_queue_create("dsh-ssh-resolver", DISPATCH_QUEUE_SERIAL); });
    dispatch_group_t completion = dispatch_group_create();
    __block NSArray<NSData *> *result = nil;
    __weak DshLibsshSession *weakOwner = owner;
    dispatch_group_async(completion, resolver, ^{
        @autoreleasepool {
            DshLibsshSession *session = weakOwner;
            if (!session || [session isCancelled] || NSProcessInfo.processInfo.systemUptime >= deadline) return;
            struct addrinfo hints = {0}, *addresses = NULL;
            hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
            NSString *service = [NSString stringWithFormat:@"%ld", (long)port];
            if (getaddrinfo(host.UTF8String, service.UTF8String, &hints, &addresses) != 0) return;
            NSMutableArray<NSData *> *resolved = [NSMutableArray array];
            for (struct addrinfo *address = addresses; address; address = address->ai_next) {
                if ((address->ai_family == AF_INET || address->ai_family == AF_INET6) &&
                    address->ai_addrlen <= sizeof(struct sockaddr_storage)) {
                    [resolved addObject:[NSData dataWithBytes:address->ai_addr length:address->ai_addrlen]];
                }
            }
            freeaddrinfo(addresses);
            result = resolved;
        }
    });
    // getaddrinfo cannot be cancelled. Its queue owns all buffers; the SSH worker can stop waiting promptly.
    while (![owner isCancelled] && NSProcessInfo.processInfo.systemUptime < deadline) {
        if (dispatch_group_wait(completion, dispatch_time(DISPATCH_TIME_NOW, 100 * NSEC_PER_MSEC)) == 0) return result;
    }
    return nil;
}

@implementation DshLibsshSession

- (instancetype)init {
    if ((self = [super init])) {
        _socket = -1;
        _listener = -1;
        _cancelLock = [NSLock new];
    }
    return self;
}

- (BOOL)isCancelled {
    [_cancelLock lock];
    BOOL cancelled = _cancelled;
    [_cancelLock unlock];
    return cancelled;
}

- (void)cancel {
    [_cancelLock lock];
    _cancelled = YES;
    // The worker retains descriptor ownership until close; shutdown cannot touch a reused descriptor.
    if (_socket >= 0) shutdown(_socket, SHUT_RDWR);
    if (_listener >= 0) shutdown(_listener, SHUT_RDWR);
    [_cancelLock unlock];
}

- (BOOL)connectHost:(NSString *)host port:(NSInteger)port {
    if (_socket >= 0 || _session || port < 1 || port > 65535 || host.length == 0) return NO;
    static dispatch_once_t once;
    static int initialized;
    dispatch_once(&once, ^{ initialized = libssh2_init(0); });
    if (initialized != 0 || [self isCancelled]) return NO;
    NSTimeInterval deadline = deadlineAfter(10);
    NSArray<NSData *> *addresses = resolveAddresses(self, host, port, deadline);
    BOOL connected = NO;
    for (NSData *resolved in addresses) {
        if ([self isCancelled] || NSProcessInfo.processInfo.systemUptime >= deadline) break;
        const struct sockaddr *address = resolved.bytes;
        int fd = socket(address->sa_family, SOCK_STREAM, 0);
        if (fd < 0) continue;
        int flags = fcntl(fd, F_GETFL, 0), noSignal = 1;
        if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0 ||
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, sizeof(noSignal)) != 0) { close(fd); continue; }
        [_cancelLock lock];
        if (_cancelled) { [_cancelLock unlock]; close(fd); break; }
        _socket = fd;
        [_cancelLock unlock];
        int result = connect(fd, address, (socklen_t)resolved.length);
        if (result < 0 && errno == EINPROGRESS) {
            while (![self isCancelled] && NSProcessInfo.processInfo.systemUptime < deadline) {
                struct pollfd ready = {fd, POLLOUT, 0};
                int polled = poll(&ready, 1, 100);
                if (polled < 0 && errno == EINTR) continue;
                if (polled <= 0) { if (polled < 0) break; continue; }
                int error = 0; socklen_t length = sizeof(error);
                result = getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) == 0 && error == 0 ? 0 : -1;
                break;
            }
        }
        if (result == 0 && ![self isCancelled]) { connected = YES; break; }
        [_cancelLock lock];
        close(_socket); _socket = -1;
        [_cancelLock unlock];
        if (NSProcessInfo.processInfo.systemUptime >= deadline) break;
    }
    if (!connected) return NO;
    _session = libssh2_session_init_ex(NULL, NULL, NULL, NULL);
    if (!_session) return NO;
    libssh2_session_set_blocking(_session, 0);
    int result;
    do {
        if ([self isCancelled]) return NO;
        result = libssh2_session_handshake(_session, _socket);
    } while (result == LIBSSH2_ERROR_EAGAIN && [self waitForSessionUntil:deadline]);
    return result == 0 && ![self isCancelled];
}

- (BOOL)waitForSessionUntil:(NSTimeInterval)deadline {
    if ([self isCancelled] || NSProcessInfo.processInfo.systemUptime >= deadline) return NO;
    int directions = libssh2_session_block_directions(_session);
    short events = 0;
    if (directions & LIBSSH2_SESSION_BLOCK_INBOUND) events |= POLLIN;
    if (directions & LIBSSH2_SESSION_BLOCK_OUTBOUND) events |= POLLOUT;
    struct pollfd ready = {_socket, events ?: POLLIN, 0};
    int result = poll(&ready, 1, 100);
    return (result >= 0 || errno == EINTR) && ![self isCancelled] && !(ready.revents & (POLLERR | POLLNVAL));
}

- (NSString *)fingerprint {
    if (!_session || [self isCancelled]) return @"";
    size_t length = 0; int type = 0;
    const char *key = libssh2_session_hostkey(_session, &length, &type);
    if (!key || !length || length > UINT32_MAX) return @"";
    unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(key, (CC_LONG)length, digest);
    NSString *base64 = [[NSData dataWithBytes:digest length:sizeof(digest)] base64EncodedStringWithOptions:0];
    // Match OpenSSH/MINA fingerprints: canonical SHA256 base64 has no padding.
    return [@"SHA256:" stringByAppendingString:[base64 stringByReplacingOccurrencesOfString:@"=" withString:@""]];
}

- (BOOL)authenticateUser:(NSString *)user key:(NSData *)key passphrase:(NSString *)passphrase {
    if (!_session || key.length == 0 || [self isCancelled]) return NO;
    NSData *username = [user dataUsingEncoding:NSUTF8StringEncoding];
    NSTimeInterval deadline = deadlineAfter(10);
    int result;
    do {
        if ([self isCancelled]) return NO;
        result = libssh2_userauth_publickey_frommemory(_session, username.bytes, username.length,
            NULL, 0, key.bytes, key.length, passphrase.UTF8String);
    } while (result == LIBSSH2_ERROR_EAGAIN && [self waitForSessionUntil:deadline]);
    return result == 0 && libssh2_userauth_authenticated(_session) && ![self isCancelled];
}

- (NSInteger)startForwarding {
    if ([self isCancelled]) return 0;
    if (!_session || !libssh2_userauth_authenticated(_session)) return 0;
    if (_listener >= 0) return _localPort;
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) return 0;
    struct sockaddr_in address = {0};
    address.sin_len = sizeof(address);
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0 ||
        bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0 || listen(fd, 32) != 0) { close(fd); return 0; }
    socklen_t length = sizeof(address);
    if (getsockname(fd, (struct sockaddr *)&address, &length) != 0) { close(fd); return 0; }
    [_cancelLock lock];
    if (_cancelled) { [_cancelLock unlock]; close(fd); return 0; }
    _listener = fd;
    _localPort = ntohs(address.sin_port);
    [_cancelLock unlock];
    return _localPort;
}

- (BOOL)pumpRemotePort:(NSInteger)port { return [self runForwarder:port]; }

- (void)close {
    // Only the worker calls close. cancel may concurrently interrupt sockets but cannot free the session.
    [self cancel];
    if (_session) {
        libssh2_session_set_blocking(_session, 1);
        libssh2_session_set_timeout(_session, 1000);
        libssh2_session_free(_session);
        _session = NULL;
    }
    [_cancelLock lock];
    if (_listener >= 0) close(_listener);
    if (_socket >= 0) close(_socket);
    _listener = -1; _socket = -1; _localPort = 0;
    [_cancelLock unlock];
}

@end
