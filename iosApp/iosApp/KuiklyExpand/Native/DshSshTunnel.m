#import "DshSshTunnel.h"
#import "DshSshSession.h"

@interface DshSshAttempt : NSObject
@property (nonatomic) int64_t generation;
@property (nonatomic, strong) NSMutableData *key;
@property (nonatomic, copy) NSString *passphrase;
@property (nonatomic, copy) NSString *host;
@property (nonatomic, copy) NSString *username;
@property (nonatomic, copy) NSString *fingerprint;
@property (nonatomic) NSInteger port;
@property (nonatomic) NSInteger remotePort;
@property (nonatomic, strong) id<DshSshSession> session;
@end
@implementation DshSshAttempt
@end

@implementation DshSshTunnel {
    NSRecursiveLock *_lock;
    DshSshSessionFactory _factory;
    DshSshAttempt *_active;
    NSDictionary *_state;
    int64_t _generation;
    BOOL _enabled;
    NSUInteger _retries;
    NSString *_host, *_username, *_passphrase, *_fingerprint, *_pendingFingerprint;
    NSInteger _port, _remotePort;
    NSMutableData *_key;
    void (^_onState)(NSDictionary *);
}

- (instancetype)init { return [self initWithSessionFactory:^{ return [DshLibsshSession new]; }]; }

- (instancetype)initWithSessionFactory:(DshSshSessionFactory)factory {
    if ((self = [super init])) {
        _factory = [factory copy];
        _lock = [NSRecursiveLock new];
        _state = @{ @"phase": @"IDLE", @"localPort": @0, @"generation": @0 };
    }
    return self;
}

- (void)setOnState:(void (^)(NSDictionary *))callback { [_lock lock]; _onState = [callback copy]; [_lock unlock]; }
- (void (^)(NSDictionary *))onState { [_lock lock]; id callback = _onState; [_lock unlock]; return callback; }

- (void)connectWithHost:(NSString *)host port:(NSInteger)port username:(NSString *)username
          remoteDshPort:(NSInteger)remotePort keyBytes:(NSData *)key passphrase:(NSString *)passphrase fingerprint:(NSString *)fingerprint {
    [_lock lock];
    @try {
        if (host.length == 0 || username.length == 0 || port < 1 || port > 65535 || remotePort < 1 || remotePort > 65535 || key.length == 0 || key.length > 65536) {
            [self stopResources];
            _generation++;
            [self emit:@"ERROR" message:@"SSH 配置或私钥无效" port:0];
            return;
        }
        [self stopResources];
        _enabled = YES;
        _host = [host copy]; _username = [username copy]; _port = port; _remotePort = remotePort;
        _key = [key mutableCopy]; _passphrase = [passphrase copy]; _fingerprint = [fingerprint copy];
        _pendingFingerprint = @""; _retries = 0;
        [self startAttempt];
    } @finally { [_lock unlock]; }
}

- (void)acceptFingerprint:(NSString *)fingerprint {
    [_lock lock];
    @try {
        if (!_enabled || ![_state[@"phase"] isEqual:@"FINGERPRINT_REQUIRED"] || fingerprint.length == 0 ||
            ![fingerprint isEqual:_pendingFingerprint]) return;
        _fingerprint = [fingerprint copy]; _pendingFingerprint = @""; _retries = 0;
        [self startAttempt];
    } @finally { [_lock unlock]; }
}

- (void)disconnect {
    [_lock lock];
    [self stopResources];
    _generation++;
    [self emit:@"STOPPED" message:@"SSH 已断开" port:0];
    [_lock unlock];
}

- (NSString *)endpoint {
    [_lock lock];
    NSString *value = [_state[@"phase"] isEqual:@"READY"] && [_state[@"localPort"] integerValue] > 0
        ? [NSString stringWithFormat:@"http://127.0.0.1:%@", _state[@"localPort"]] : nil;
    [_lock unlock];
    return value;
}

- (void)startAttempt {
    [self releaseAttempt];
    DshSshAttempt *attempt = [DshSshAttempt new];
    attempt.generation = ++_generation;
    attempt.key = [_key mutableCopy]; attempt.passphrase = _passphrase;
    attempt.host = _host; attempt.username = _username; attempt.port = _port; attempt.remotePort = _remotePort;
    attempt.fingerprint = _fingerprint; attempt.session = _factory();
    _active = attempt;
    [self emit:@"CONNECTING" message:@"正在连接 SSH" port:0];
    [NSThread detachNewThreadWithBlock:^{ @autoreleasepool { [self runAttempt:attempt]; } }];
}

- (BOOL)accepts:(DshSshAttempt *)attempt {
    [_lock lock];
    BOOL valid = _enabled && _active == attempt && _generation == attempt.generation;
    [_lock unlock];
    return valid;
}

- (void)runAttempt:(DshSshAttempt *)attempt {
    BOOL retryable = NO;
    NSString *failure = nil;
    @try {
        if (![self accepts:attempt]) return;
        if (![attempt.session connectHost:attempt.host port:attempt.port]) { retryable = YES; failure = @"无法连接 SSH 主机"; return; }
        if (![self accepts:attempt]) return;
        NSString *offered = [attempt.session fingerprint];
        if (offered.length == 0) { failure = @"无法读取 SSH 主机指纹"; return; }
        if (![offered isEqual:attempt.fingerprint]) {
            [_lock lock];
            if ([self accepts:attempt]) {
                _pendingFingerprint = offered;
                _active = nil;
                [self emit:@"FINGERPRINT_REQUIRED" message:offered port:0];
            }
            [_lock unlock];
            return;
        }
        [self emitFor:attempt phase:@"AUTHENTICATING" message:@"正在验证 SSH 身份" port:0];
        if (![self accepts:attempt]) return;
        if (![attempt.session authenticateUser:attempt.username key:attempt.key passphrase:attempt.passphrase]) {
            failure = @"SSH 身份验证失败，请检查用户名、私钥和口令"; return;
        }
        [attempt.key resetBytesInRange:NSMakeRange(0, attempt.key.length)];
        attempt.passphrase = @"";
        [self emitFor:attempt phase:@"FORWARDING" message:@"正在建立 DSH 转发" port:0];
        if (![self accepts:attempt]) return;
        NSInteger port = [attempt.session startForwarding];
        if (port <= 0 || port > 65535) { failure = @"本地 SSH 转发创建失败"; return; }
        [self emitFor:attempt phase:@"READY" message:@"SSH 已连接" port:port];
        if (![self accepts:attempt]) return;
        [attempt.session pumpRemotePort:attempt.remotePort];
        retryable = YES; failure = @"SSH 连接已断开，正在重连";
    } @catch (NSException *error) {
        // Native exceptions may contain key material or server data; expose only a fixed message.
        failure = @"SSH 连接失败";
    } @finally {
        [attempt.key resetBytesInRange:NSMakeRange(0, attempt.key.length)]; attempt.passphrase = @"";
        @try { [attempt.session close]; } @catch (NSException *error) { failure = @"SSH 资源释放失败"; retryable = NO; }
        [_lock lock];
        if ([self accepts:attempt]) {
            _active = nil;
            [self emit:retryable ? @"RECONNECTING" : @"ERROR" message:failure ?: @"SSH 连接失败" port:0];
            if (retryable) [self scheduleRetry:attempt.generation];
        }
        [_lock unlock];
    }
}

- (void)scheduleRetry:(int64_t)generation {
    NSArray<NSNumber *> *delays = @[@1, @2, @4, @8, @16, @30];
    if (!_enabled || _generation != generation || _active || _retries >= delays.count) return;
    NSTimeInterval delay = delays[_retries++].doubleValue;
    __weak DshSshTunnel *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delay * NSEC_PER_SEC)), dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        DshSshTunnel *owner = weakSelf;
        if (!owner) return;
        [owner->_lock lock];
        if (owner->_enabled && owner->_generation == generation && !owner->_active && [owner->_state[@"phase"] isEqual:@"RECONNECTING"]) {
            [owner startAttempt];
        }
        [owner->_lock unlock];
    });
}

- (void)emitFor:(DshSshAttempt *)attempt phase:(NSString *)phase message:(NSString *)message port:(NSInteger)port {
    [_lock lock];
    if ([self accepts:attempt]) { if ([phase isEqual:@"READY"]) _retries = 0; [self emit:phase message:message port:port]; }
    [_lock unlock];
}

- (void)emit:(NSString *)phase message:(NSString *)message port:(NSInteger)port {
    NSDictionary *state = @{ @"phase": phase, @"message": message, @"localPort": @(port), @"generation": @(_generation) };
    _state = state;
    void (^callback)(NSDictionary *) = _onState;
    if (!callback) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        [self->_lock lock];
        // Suppress queued state from old attempts, stopped connections, and replaced subscriptions.
        @try { if (self->_state == state && self->_onState == callback) callback(state); }
        @catch (NSException *error) { NSLog(@"[DshSshTunnel] State callback failed"); }
        @finally { [self->_lock unlock]; }
    });
}

- (void)releaseAttempt {
    DshSshAttempt *attempt = _active;
    _active = nil;
    [attempt.session cancel];
    // The worker clears its own mutable key; never mutate it while native authentication is reading it.
}

- (void)stopResources {
    _enabled = NO;
    [self releaseAttempt];
    [_key resetBytesInRange:NSMakeRange(0, _key.length)]; _key = nil;
    _passphrase = @""; _pendingFingerprint = @"";
}

@end
