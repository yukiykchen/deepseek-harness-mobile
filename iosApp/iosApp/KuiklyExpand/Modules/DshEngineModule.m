#import "DshEngineModule.h"

#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import "DshSshTunnel.h"
#import "iosApp-Swift.h"

@implementation DshEngineModule {
    DshSshTunnel *_tunnel;
}

@synthesize hr_rootView;

- (void)startSsh:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
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
