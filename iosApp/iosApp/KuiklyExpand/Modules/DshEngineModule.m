#import "DshEngineModule.h"

#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import "DshSshTunnel.h"
#import "iosApp-Swift.h"

static NSString *DshPreparedToken;
static NSDictionary *DshPreparedConfig;
static NSTimeInterval DshPreparedAt;

@implementation DshEngineModule {
    DshSshTunnel *_tunnel;
}

@synthesize hr_rootView;

- (void)prepareSsh:(NSDictionary *)args {
    NSString *token = [NSUUID UUID].UUIDString;
    @synchronized ([DshEngineModule class]) {
        DshPreparedToken = token;
        DshPreparedConfig = [[args[KR_PARAM_KEY] hr_stringToDictionary] copy];
        DshPreparedAt = [NSDate date].timeIntervalSince1970;
    }
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 60 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        @synchronized ([DshEngineModule class]) {
            if ([DshPreparedToken isEqualToString:token]) {
                DshPreparedToken = nil;
                DshPreparedConfig = nil;
            }
        }
    });
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) callback(@{ @"preparedId": token });
}

- (void)startSsh:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *preparedId = params[@"preparedId"] ?: @"";
    if (preparedId.length > 0) {
        @synchronized ([DshEngineModule class]) {
            if ([preparedId isEqualToString:DshPreparedToken] && [NSDate date].timeIntervalSince1970 - DshPreparedAt <= 60) {
                params = DshPreparedConfig;
                DshPreparedToken = nil;
                DshPreparedConfig = nil;
            } else {
                params = nil;
            }
        }
        if (!params) {
            if (callback) callback(@{ @"phase": @"ERROR", @"message": @"本次 SSH 配置已失效，请重新填写私钥口令" });
            return;
        }
    }
    NSString *keyId = params[@"keyId"] ?: @"";
    if (![[DshSshKeyStore shared] exists:keyId]) {
        if (callback) callback(@{ @"phase": @"ERROR", @"message": @"SSH 私钥不存在", @"localPort": @0, @"generation": @0 });
        return;
    }
    NSData *keyBytes = [[DshSshKeyStore shared] readKeyBytes:keyId];
    if (!keyBytes) {
        if (callback) callback(@{ @"phase": @"ERROR", @"message": @"SSH 私钥读取失败", @"localPort": @0, @"generation": @0 });
        return;
    }
    [_tunnel disconnect];
    _tunnel = [[DshSshTunnel alloc] init];
    _tunnel.onState = ^(NSDictionary *state) {
        if (callback) callback(state);
    };
    NSInteger port = [params[@"port"] integerValue];
    if (port <= 0) port = 22;
    NSInteger remoteDshPort = [params[@"remoteDshPort"] integerValue];
    if (remoteDshPort <= 0) remoteDshPort = 3080;
    [_tunnel connectWithHost:params[@"host"] ?: @""
                        port:port
                    username:params[@"username"] ?: @""
               remoteDshPort:remoteDshPort
                    keyBytes:keyBytes
                  passphrase:params[@"keyPassphrase"] ?: @""
                 fingerprint:params[@"hostFingerprint"] ?: @""];
}

- (void)trustSshFingerprint:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    [_tunnel acceptFingerprint:params[@"fingerprint"] ?: @""];
}

- (void)stopSsh:(NSDictionary *)args {
    [_tunnel disconnect];
    _tunnel.onState = nil;
}

- (id)sshEndpoint:(NSDictionary *)args {
    return [_tunnel endpoint] ?: @"";
}

@end
