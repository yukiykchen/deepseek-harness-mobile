#import "DshSshTunnel.h"
#import "DshSshSessionInternal.h"
#import "DshSshKeyValidator.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <openssl/crypto.h>

static void require(BOOL value, NSString *message) {
    if (!value) @throw [NSException exceptionWithName:@"TestFailure" reason:message userInfo:nil];
}

static void spinUntil(int (^predicate)(void)) {
    NSTimeInterval deadline = NSProcessInfo.processInfo.systemUptime + 5;
    while (!predicate() && NSProcessInfo.processInfo.systemUptime < deadline) {
        [NSRunLoop.currentRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    }
    require(predicate(), @"Timed out waiting for state");
}

@interface FakeSshSession : NSObject <DshSshSession>
@property NSCondition *gate;
@property BOOL entered, released, cancelled, closed;
@property NSString *stage, *offered;
@property BOOL failAuthentication;
@property BOOL failConnect;
@property NSThread *worker;
@property NSData *authKey;
@property NSInteger port;
@end

@implementation FakeSshSession
- (instancetype)init {
    if ((self = [super init])) { _gate = [NSCondition new]; _stage = @"pump"; _offered = @"SHA256:trusted"; _port = 18000; }
    return self;
}
- (void)waitAt:(NSString *)stage {
    _worker = NSThread.currentThread;
    if (![_stage isEqual:stage]) return;
    [_gate lock]; _entered = YES; [_gate broadcast];
    while (!_released) [_gate wait];
    [_gate unlock];
}
- (BOOL)connectHost:(NSString *)host port:(NSInteger)port { [self waitAt:@"connect"]; return !_failConnect; }
- (NSString *)fingerprint { return _offered; }
- (BOOL)authenticateUser:(NSString *)user key:(NSData *)key passphrase:(NSString *)passphrase {
    _authKey = key; [self waitAt:@"authenticate"]; return !_failAuthentication;
}
- (NSInteger)startForwarding { [self waitAt:@"forward"]; return _port; }
- (BOOL)pumpRemotePort:(NSInteger)port { [self waitAt:@"pump"]; return NO; }
- (void)cancel { [_gate lock]; _cancelled = YES; [_gate broadcast]; [_gate unlock]; }
- (void)close {
    require(NSThread.currentThread == _worker, @"Session must close on its owning worker");
    _closed = YES;
}
- (void)releaseGate { [_gate lock]; _released = YES; [_gate broadcast]; [_gate unlock]; }
@end

static void startTunnel(DshSshTunnel *tunnel) {
    [tunnel connectWithHost:@"host" port:22 username:@"user" remoteDshPort:3080
        keyBytes:[@"private-key" dataUsingEncoding:NSUTF8StringEncoding] passphrase:@"secret-password" fingerprint:@"SHA256:trusted"];
}

static void lifecycleTests(void (^test)(NSString *, void (^)(void))) {
    for (NSString *stage in @[@"connect", @"authenticate", @"forward", @"pump"]) {
        test([NSString stringWithFormat:@"cancel at %@ cannot publish a stale READY and closes on the worker", stage], ^{
            FakeSshSession *session = [FakeSshSession new]; session.stage = stage;
            DshSshTunnel *tunnel = [[DshSshTunnel alloc] initWithSessionFactory:^{ return session; }];
            NSMutableArray *states = [NSMutableArray array];
            tunnel.onState = ^(NSDictionary *state) { [states addObject:state[@"phase"]]; };
            startTunnel(tunnel); spinUntil(^int{ return session.entered; });
            [tunnel disconnect]; [states removeAllObjects];
            [session releaseGate]; spinUntil(^int{ return session.closed && [states containsObject:@"STOPPED"]; });
            require([tunnel endpoint] == nil, @"Endpoint must clear on stop");
            require(![states containsObject:@"READY"], @"Late READY delivered after stop");
            if (session.authKey) {
                for (NSUInteger i = 0; i < session.authKey.length; i++) require(((unsigned char *)session.authKey.bytes)[i] == 0, @"Worker key not cleared");
            }
        });
    }
    test(@"an old authentication worker cannot close or repaint the next session", ^{
        FakeSshSession *old = [FakeSshSession new]; old.stage = @"authenticate";
        FakeSshSession *next = [FakeSshSession new]; next.port = 19000;
        __block NSUInteger count = 0;
        DshSshTunnel *tunnel = [[DshSshTunnel alloc] initWithSessionFactory:^{ return count++ == 0 ? old : next; }];
        startTunnel(tunnel); spinUntil(^int{ return old.entered; });
        startTunnel(tunnel); spinUntil(^int{ return next.entered; });
        [old releaseGate]; spinUntil(^int{ return old.closed; });
        require([[tunnel endpoint] isEqual:@"http://127.0.0.1:19000"], @"Old attempt changed new endpoint");
        require(!next.cancelled && !next.closed, @"Old attempt closed new resources");
        [tunnel disconnect]; [next releaseGate]; spinUntil(^int{ return next.closed; });
    });
    test(@"queued callback from an old subscription is suppressed", ^{
        FakeSshSession *session = [FakeSshSession new]; session.stage = @"connect";
        DshSshTunnel *tunnel = [[DshSshTunnel alloc] initWithSessionFactory:^{ return session; }];
        __block NSUInteger oldCalls = 0, newCalls = 0;
        tunnel.onState = ^(NSDictionary *state) { oldCalls++; };
        startTunnel(tunnel);
        tunnel.onState = ^(NSDictionary *state) { newCalls++; };
        spinUntil(^int{ return session.entered; });
        require(oldCalls == 0 && newCalls == 0, @"Replaced subscription received stale state");
        [tunnel disconnect]; [session releaseGate]; spinUntil(^int{ return session.closed; });
    });
    test(@"host key confirmation requires the exact current fingerprint", ^{
        FakeSshSession *first = [FakeSshSession new]; first.offered = @"SHA256:changed";
        FakeSshSession *second = [FakeSshSession new]; second.offered = @"SHA256:changed";
        __block NSUInteger count = 0;
        __block NSString *phase = @"";
        DshSshTunnel *tunnel = [[DshSshTunnel alloc] initWithSessionFactory:^{ return count++ == 0 ? first : second; }];
        tunnel.onState = ^(NSDictionary *state) { phase = state[@"phase"]; };
        startTunnel(tunnel); spinUntil(^int{ return [phase isEqual:@"FINGERPRINT_REQUIRED"] && first.closed; });
        [tunnel acceptFingerprint:@"SHA256:other"];
        require(count == 1, @"Arbitrary fingerprint was accepted");
        [tunnel acceptFingerprint:@"SHA256:changed"]; spinUntil(^int{ return second.entered; });
        require(count == 2, @"Confirmed fingerprint did not retry");
        [tunnel disconnect]; [second releaseGate]; spinUntil(^int{ return second.closed; });
    });
    test(@"authentication failure closes resources and remains permanent", ^{
        FakeSshSession *session = [FakeSshSession new]; session.failAuthentication = YES;
        __block NSDictionary *last = nil;
        DshSshTunnel *tunnel = [[DshSshTunnel alloc] initWithSessionFactory:^{ return session; }];
        tunnel.onState = ^(NSDictionary *state) { last = state; };
        startTunnel(tunnel); spinUntil(^int{ return [last[@"phase"] isEqual:@"ERROR"] && session.closed; });
        require([last[@"localPort"] integerValue] == 0, @"Failure retained an endpoint");
        require(![last[@"message"] containsString:@"secret-password"], @"State exposed credentials");
        [tunnel disconnect];
    });
    test(@"a delayed network retry cannot restart a stopped tunnel", ^{
        FakeSshSession *session = [FakeSshSession new]; session.failConnect = YES;
        __block NSUInteger count = 0;
        __block NSString *phase = @"";
        DshSshTunnel *tunnel = [[DshSshTunnel alloc] initWithSessionFactory:^{ count++; return session; }];
        tunnel.onState = ^(NSDictionary *state) { phase = state[@"phase"]; };
        startTunnel(tunnel); spinUntil(^int{ return [phase isEqual:@"RECONNECTING"]; });
        [tunnel disconnect];
        [NSRunLoop.currentRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:1.2]];
        require(count == 1 && [phase isEqual:@"STOPPED"], @"Cancelled retry restarted the tunnel");
    });
    test(@"a failing external callback cannot strand the state lock", ^{
        FakeSshSession *session = [FakeSshSession new]; session.stage = @"connect";
        DshSshTunnel *tunnel = [[DshSshTunnel alloc] initWithSessionFactory:^{ return session; }];
        tunnel.onState = ^(NSDictionary *state) { @throw [NSException exceptionWithName:@"External" reason:@"failure" userInfo:nil]; };
        startTunnel(tunnel); spinUntil(^int{ return session.entered; });
        [tunnel disconnect]; [session releaseGate]; spinUntil(^int{ return session.closed; });
        require([tunnel endpoint] == nil, @"Callback exception left tunnel active");
    });
}

static NSData *readExactly(int fd, NSUInteger size) {
    NSMutableData *result = [NSMutableData dataWithLength:size];
    NSUInteger offset = 0;
    NSTimeInterval deadline = NSProcessInfo.processInfo.systemUptime + 20;
    while (offset < size && NSProcessInfo.processInfo.systemUptime < deadline) {
        struct pollfd ready = {fd, POLLIN, 0};
        if (poll(&ready, 1, 100) <= 0) continue;
        ssize_t count = recv(fd, (unsigned char *)result.mutableBytes + offset, size - offset, 0);
        require(count > 0, [NSString stringWithFormat:@"response truncated at %lu/%lu", offset, size]);
        offset += (NSUInteger)count;
    }
    require(offset == size, @"Timed out reading response");
    return result;
}

static int localSocket(NSInteger port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in address = {0}; address.sin_len = sizeof(address); address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = htons((uint16_t)port);
    require(fd >= 0 && connect(fd, (struct sockaddr *)&address, sizeof(address)) == 0, @"Cannot connect local peer");
    int enabled = 1; setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled));
    return fd;
}

static void nativeTests(NSDictionary *config, void (^test)(NSString *, void (^)(void))) {
    test(@"private key validation accepts real unencrypted and encrypted keys only with the right secret", ^{
        NSData *plain = [NSData dataWithContentsOfFile:config[@"ed25519Path"]];
        NSData *encrypted = [NSData dataWithContentsOfFile:config[@"encryptedPath"]];
        require([DshSshKeyValidator validateKeyData:plain passphrase:@""], @"Plain key rejected");
        require([DshSshKeyValidator validateKeyData:encrypted passphrase:@"test-passphrase"], @"Correct passphrase rejected");
        require(![DshSshKeyValidator validateKeyData:encrypted passphrase:@""], @"Missing passphrase accepted");
        require(![DshSshKeyValidator validateKeyData:encrypted passphrase:@"wrong-password"], @"Wrong passphrase accepted");
        NSData *fake = [@"-----BEGIN ENCRYPTED PRIVATE KEY-----\nbody\n-----END ENCRYPTED PRIVATE KEY-----" dataUsingEncoding:NSUTF8StringEncoding];
        require(![DshSshKeyValidator validateKeyData:fake passphrase:@"anything"], @"Invalid PEM accepted");
    });
    test(@"current OpenSSH Ed25519 private keys authenticate with the application crypto backend", ^{
        DshLibsshSession *session = [DshLibsshSession new];
        @try {
            require([session connectHost:@"127.0.0.1" port:[config[@"port"] integerValue]], @"SSH connect failed");
            require([session authenticateUser:@"test-user" key:[NSData dataWithContentsOfFile:config[@"ed25519Path"]] passphrase:@""], @"Ed25519 authentication failed");
        } @finally { [session close]; }
    });
    test(@"encrypted OpenSSH Ed25519 keys accept the correct passphrase", ^{
        DshLibsshSession *session = [DshLibsshSession new];
        @try {
            require([session connectHost:@"127.0.0.1" port:[config[@"port"] integerValue]], @"SSH connect failed");
            require([session authenticateUser:@"test-user" key:[NSData dataWithContentsOfFile:config[@"encryptedPath"]] passphrase:@"test-passphrase"], @"Encrypted key authentication failed");
        } @finally { [session close]; }
    });
    test(@"encrypted OpenSSH private keys reject an incorrect passphrase", ^{
        DshLibsshSession *session = [DshLibsshSession new];
        @try {
            require([session connectHost:@"127.0.0.1" port:[config[@"port"] integerValue]], @"SSH connect failed");
            require(![session authenticateUser:@"test-user" key:[NSData dataWithContentsOfFile:config[@"encryptedPath"]] passphrase:@"wrong-password"], @"Wrong passphrase accepted");
        } @finally { [session close]; }
    });
    test(@"an idle peer cannot prevent another local peer from completing an SSH request", ^{
        DshLibsshSession *session = [DshLibsshSession new];
        require([session connectHost:@"127.0.0.1" port:[config[@"port"] integerValue]], @"SSH connect failed");
        require([session authenticateUser:@"test-user" key:[NSData dataWithContentsOfFile:config[@"keyPath"]] passphrase:@""], @"SSH authentication failed");
        NSInteger port = [session startForwarding]; require(port > 0, @"Cannot start forwarder");
        __block NSException *clientError = nil;
        dispatch_group_t done = dispatch_group_create(); dispatch_group_enter(done);
        [NSThread detachNewThreadWithBlock:^{ @autoreleasepool {
            int idle = -1, active = -1;
            @try {
                idle = localSocket(port); active = localSocket(port);
                NSData *payload = [@"second-peer" dataUsingEncoding:NSUTF8StringEncoding];
                require(send(active, payload.bytes, payload.length, 0) == (ssize_t)payload.length, @"Local send failed");
                require([readExactly(active, payload.length) isEqual:payload], @"Idle peer blocked response");
            } @catch (NSException *error) { clientError = error; }
            @finally { if (idle >= 0) close(idle); if (active >= 0) close(active); [session cancel]; dispatch_group_leave(done); }
        } }];
        [session pumpRemotePort:[config[@"echoPort"] integerValue]]; [session close];
        require(dispatch_group_wait(done, dispatch_time(DISPATCH_TIME_NOW, 20 * NSEC_PER_SEC)) == 0, @"Client did not finish");
        if (clientError) @throw clientError;
    });
    test(@"real libssh2 authentication, slow binary forwarding and EOF preserve all bytes", ^{
        DshLibsshSession *session = [DshLibsshSession new];
        require([session connectHost:@"127.0.0.1" port:[config[@"port"] integerValue]], @"SSH connect failed");
        require([[session fingerprint] isEqual:config[@"fingerprint"]], @"Noncanonical host fingerprint");
        NSData *key = [NSData dataWithContentsOfFile:config[@"keyPath"]];
        require([session authenticateUser:@"test-user" key:key passphrase:@""], @"SSH authentication failed");
        NSInteger port = [session startForwarding]; require(port > 0, @"Cannot start forwarder");
        require([session startForwarding] == port, @"Duplicate forward setup allocated a second listener");
        __block NSException *clientError = nil;
        NSCondition *done = [NSCondition new]; __block BOOL completed = NO;
        [NSThread detachNewThreadWithBlock:^{ @autoreleasepool {
            int fd = -1;
            @try {
                fd = localSocket(port);
                NSMutableData *payload = [NSMutableData dataWithLength:512 * 1024];
                for (NSUInteger i = 0; i < payload.length; i++) ((unsigned char *)payload.mutableBytes)[i] = (unsigned char)(i % 251);
                NSUInteger offset = 0;
                while (offset < payload.length) {
                    ssize_t count = send(fd, (unsigned char *)payload.bytes + offset, MIN(1024, payload.length - offset), 0);
                    require(count > 0, @"Send failed"); offset += (NSUInteger)count;
                }
                shutdown(fd, SHUT_WR);
                [NSThread sleepForTimeInterval:0.2];
                require([readExactly(fd, payload.length) isEqual:payload], @"Binary response mismatch");
            } @catch (NSException *error) { clientError = error; }
            @finally {
                if (fd >= 0) close(fd);
                [session cancel];
                [done lock]; completed = YES; [done broadcast]; [done unlock];
            }
        } }];
        [session pumpRemotePort:[config[@"echoPort"] integerValue]];
        [session close];
        [done lock]; while (!completed) [done wait]; [done unlock];
        if (clientError) @throw clientError;
    });
    test(@"real authentication failure never authorizes a channel", ^{
        DshLibsshSession *session = [DshLibsshSession new];
        @try {
            require([session connectHost:@"127.0.0.1" port:[config[@"port"] integerValue]], @"SSH connect failed");
            require(![session authenticateUser:@"wrong-user" key:[NSData dataWithContentsOfFile:config[@"keyPath"]] passphrase:@""], @"Invalid user authorized");
        } @finally { [session close]; }
    });
    test(@"shutdown interrupts a silent SSH handshake without freeing from the cancelling thread", ^{
        DshLibsshSession *session = [DshLibsshSession new];
        [NSThread detachNewThreadWithBlock:^{ [NSThread sleepForTimeInterval:0.1]; [session cancel]; }];
        NSTimeInterval start = NSProcessInfo.processInfo.systemUptime;
        require(![session connectHost:@"127.0.0.1" port:[config[@"silentPort"] integerValue]], @"Silent SSH handshake succeeded");
        [session close];
        require(NSProcessInfo.processInfo.systemUptime - start < 2, @"Cancellation did not interrupt handshake");
    });
    test(@"descriptor close remains worker-owned after cross-thread shutdown", ^{
        DshLibsshSession *session = [DshLibsshSession new];
        require([session connectHost:@"127.0.0.1" port:[config[@"port"] integerValue]], @"SSH connect failed");
        int fd = session->_socket;
        dispatch_group_t done = dispatch_group_create();
        dispatch_group_async(done, dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{ [session cancel]; });
        dispatch_group_wait(done, DISPATCH_TIME_FOREVER);
        require(fcntl(fd, F_GETFD) >= 0, @"Cancel closed the descriptor before the worker finished");
        [session close];
        require(fcntl(fd, F_GETFD) < 0 && errno == EBADF, @"Worker did not close descriptor");
    });
}

int main(int argc, char **argv) {
    @autoreleasepool {
        __block NSUInteger passed = 0, failed = 0;
        void (^test)(NSString *, void (^)(void)) = ^(NSString *name, void (^work)(void)) {
            @try { work(); passed++; printf("PASS %s\n", name.UTF8String); }
            @catch (NSException *error) { failed++; fprintf(stderr, "FAIL %s: %s\n", name.UTF8String, error.reason.UTF8String); }
        };
        test(@"test binary uses the pinned application SSH and OpenSSL versions", ^{
            require(LIBSSH2_VERSION_NUM == 0x010b01, @"Unexpected libssh2 headers");
            require(strncmp(libssh2_version(0), "1.11.1", 6) == 0, @"Unexpected libssh2 runtime");
            require(OPENSSL_VERSION_NUMBER == 0x30600020L, @"Unexpected OpenSSL headers");
            require(OpenSSL_version_num() == OPENSSL_VERSION_NUMBER, @"OpenSSL header/runtime mismatch");
        });
        lifecycleTests(test);
        NSString *configPath = argc == 2 ? @(argv[1]) : [NSBundle.mainBundle pathForResource:@"fixture" ofType:@"json"];
        if (configPath) {
            NSMutableDictionary *config = [[NSJSONSerialization JSONObjectWithData:[NSData dataWithContentsOfFile:configPath] options:0 error:nil] mutableCopy];
            for (NSString *field in @[@"keyPath", @"ed25519Path", @"encryptedPath"]) {
                if (![config[field] isAbsolutePath]) config[field] = [configPath.stringByDeletingLastPathComponent stringByAppendingPathComponent:config[field]];
            }
            nativeTests(config, test);
        }
        printf("Objective-C SSH tests: %lu passed, %lu failed\n", passed, failed);
        return failed ? 1 : 0;
    }
}
