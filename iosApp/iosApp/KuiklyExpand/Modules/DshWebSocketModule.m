#import "DshWebSocketModule.h"

#import <OpenKuiklyIOSRender/NSObject+KR.h>

@interface DshWebSocketConnection : NSObject <NSURLSessionWebSocketDelegate, NSURLSessionTaskDelegate>
@property (nonatomic, copy) KuiklyRenderCallback callback;
@property (nonatomic, copy) dispatch_block_t onFinished;
@property (nonatomic, strong, nullable) NSURLSession *session;
@property (nonatomic, strong, nullable) NSURLSessionWebSocketTask *webSocketTask;
@property (atomic, assign) BOOL closed;
@property (atomic, assign) BOOL finished;
@property (atomic, assign) BOOL opened;
- (instancetype)initWithURL:(NSURL *)url token:(NSString *)token cookie:(NSString *)cookie callback:(KuiklyRenderCallback)callback onFinished:(dispatch_block_t)onFinished;
- (void)start;
- (void)send:(NSString *)text;
- (void)close;
@end

@implementation DshWebSocketConnection {
    NSURL *_url;
    NSString *_token;
    NSString *_cookie;
}

- (instancetype)initWithURL:(NSURL *)url token:(NSString *)token cookie:(NSString *)cookie callback:(KuiklyRenderCallback)callback onFinished:(dispatch_block_t)onFinished {
    self = [super init];
    if (self) {
        _url = url;
        _token = [token copy];
        _cookie = [cookie copy];
        _callback = [callback copy];
        _onFinished = [onFinished copy];
    }
    return self;
}

- (void)start {
    NSURLComponents *components = [NSURLComponents componentsWithURL:_url resolvingAgainstBaseURL:NO];
    components.scheme = [components.scheme isEqualToString:@"https"] ? @"wss" : @"ws";
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:components.URL];
    if (_token.length > 0) {
        [request setValue:[NSString stringWithFormat:@"Bearer %@", _token] forHTTPHeaderField:@"Authorization"];
    }
    if (_cookie.length > 0) {
        [request setValue:_cookie forHTTPHeaderField:@"Cookie"];
    }
    NSURLSessionConfiguration *configuration = [NSURLSessionConfiguration ephemeralSessionConfiguration];
    configuration.timeoutIntervalForRequest = 10;
    // The DSH cookie is bound to the Host authority we send; never let the shared jar rewrite it.
    configuration.HTTPShouldSetCookies = NO;
    configuration.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicyNever;
    self.session = [NSURLSession sessionWithConfiguration:configuration delegate:self delegateQueue:nil];
    self.webSocketTask = [self.session webSocketTaskWithRequest:request];
    [self.webSocketTask resume];
}

- (void)send:(NSString *)text {
    if (self.closed || text.length == 0 || !self.webSocketTask) return;
    NSURLSessionWebSocketMessage *message = [[NSURLSessionWebSocketMessage alloc] initWithString:text];
    [self.webSocketTask sendMessage:message completionHandler:^(NSError *error) {
        if (error) NSLog(@"DshWebSocket: send failed %@", error.localizedDescription);
    }];
}

- (void)close {
    self.closed = YES;
    [self.webSocketTask cancelWithCloseCode:NSURLSessionWebSocketCloseCodeNormalClosure reason:nil];
    [self.session invalidateAndCancel];
    self.webSocketTask = nil;
    self.session = nil;
    [self finish];
}

- (void)URLSession:(NSURLSession *)session webSocketTask:(NSURLSessionWebSocketTask *)webSocketTask didOpenWithProtocol:(NSString *)protocol {
    if (self.closed) return;
    self.opened = YES;
    [self emit:@{ @"kind": @"OPEN" }];
    [self receiveWebSocketMessage];
}

- (void)URLSession:(NSURLSession *)session webSocketTask:(NSURLSessionWebSocketTask *)webSocketTask didCloseWithCode:(NSURLSessionWebSocketCloseCode)closeCode reason:(NSData *)reason {
    self.webSocketTask = nil;
    if (!self.closed) [self emit:@{ @"kind": @"CLOSED" }];
    [self.session finishTasksAndInvalidate];
    self.session = nil;
    [self finish];
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    // A rejected upgrade (401 / 403) completes the task without ever opening.
    if (self.closed || self.opened || !error) return;
    NSInteger status = 0;
    if ([task.response isKindOfClass:[NSHTTPURLResponse class]]) {
        status = ((NSHTTPURLResponse *)task.response).statusCode;
    }
    [self emit:@{ @"kind": @"ERROR", @"message": error.localizedDescription ?: @"WebSocket connection failed", @"httpStatus": @(status) }];
    self.webSocketTask = nil;
    [self.session invalidateAndCancel];
    self.session = nil;
    [self finish];
}

- (void)receiveWebSocketMessage {
    if (self.closed || !self.webSocketTask) return;
    __weak typeof(self) weakSelf = self;
    [self.webSocketTask receiveMessageWithCompletionHandler:^(NSURLSessionWebSocketMessage *message, NSError *error) {
        __strong typeof(weakSelf) self = weakSelf;
        if (!self || self.closed) return;
        if (error) {
            NSInteger status = 0;
            if ([self.webSocketTask.response isKindOfClass:[NSHTTPURLResponse class]]) {
                status = ((NSHTTPURLResponse *)self.webSocketTask.response).statusCode;
            }
            [self emit:@{ @"kind": @"ERROR", @"message": error.localizedDescription ?: @"WebSocket connection failed", @"httpStatus": @(status) }];
            [self.webSocketTask cancelWithCloseCode:NSURLSessionWebSocketCloseCodeGoingAway reason:nil];
            self.webSocketTask = nil;
            [self.session invalidateAndCancel];
            self.session = nil;
            [self finish];
            return;
        }
        if (message.type == NSURLSessionWebSocketMessageTypeString && message.string.length > 0) {
            [self emit:@{ @"kind": @"FRAME", @"data": message.string }];
        }
        [self receiveWebSocketMessage];
    }];
}

- (void)emit:(NSDictionary *)event {
    KuiklyRenderCallback callback = self.callback;
    if (!callback) return;
    dispatch_async(dispatch_get_main_queue(), ^{ callback(event); });
}

- (void)finish {
    @synchronized (self) {
        if (self.finished) return;
        self.finished = YES;
    }
    if (self.onFinished) self.onFinished();
}

@end

@interface DshWebSocketModule ()
@property (nonatomic, strong) NSMutableDictionary<NSString *, DshWebSocketConnection *> *connections;
@end

@implementation DshWebSocketModule

@synthesize hr_rootView;

- (instancetype)init {
    self = [super init];
    if (self) _connections = [NSMutableDictionary dictionary];
    return self;
}

- (void)dealloc {
    for (DshWebSocketConnection *connection in self.connections.allValues) [connection close];
}

- (void)connect:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    NSURL *url = [NSURL URLWithString:params[@"url"] ?: @""];
    if (connectionId.length == 0 || !url || !callback) return;
    [self.connections[connectionId] close];
    __weak typeof(self) weakSelf = self;
    DshWebSocketConnection *connection = [[DshWebSocketConnection alloc]
        initWithURL:url token:params[@"token"] ?: @"" cookie:params[@"cookie"] ?: @"" callback:callback onFinished:^{
            [weakSelf.connections removeObjectForKey:connectionId];
        }];
    self.connections[connectionId] = connection;
    [connection start];
}

- (void)send:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    [self.connections[params[@"connectionId"] ?: @""] send:params[@"data"] ?: @""];
}

- (void)disconnect:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *connectionId = params[@"connectionId"];
    [self.connections[connectionId] close];
    [self.connections removeObjectForKey:connectionId];
}

@end
