#import "DshRelayModule.h"

#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import "iosApp-Swift.h"

@implementation DshRelayModule {
    NSUInteger _listenerToken;
    NSUInteger _pairVersion;
    BOOL _pairing;
    DshQrScanViewController *_scanner;
}

@synthesize hr_rootView;

- (void)dealloc {
    if (_pairing) [[DshRelayRuntime shared] disconnect];
    _scanner.onResult = nil;
    if (_listenerToken != 0) {
        [[DshRelayRuntime shared] removeListener:_listenerToken];
    }
}

- (void)scanAndPair:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (_pairing) { if (callback) callback(@{ @"ok": @NO, @"message": @"扫码配对正在进行中" }); return; }
    UIViewController *presenter = [DshNativeUi topViewController];
    if (!presenter) {
        if (callback) callback(@{ @"ok": @NO, @"message": @"无法打开扫码页" });
        return;
    }
    DshQrScanViewController *scanner = [[DshQrScanViewController alloc] init];
    _scanner = scanner;
    _pairing = YES;
    NSUInteger version = ++_pairVersion;
    int64_t generation = [[DshRelayRuntime shared] beginPairing];
    __weak DshRelayModule *weakSelf = self;
    scanner.modalPresentationStyle = UIModalPresentationFullScreen;
    scanner.onResult = ^(NSString *qr) {
        DshRelayModule *owner = weakSelf;
        if (!owner || owner->_pairVersion != version || !owner->_pairing) return;
        owner->_scanner = nil;
        if (qr.length == 0) {
            owner->_pairing = NO;
            [[DshRelayRuntime shared] disconnect];
            if (callback) callback(@{ @"ok": @NO, @"message": @"已取消扫码" });
            return;
        }
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            NSDictionary *result = [[DshRelayRuntime shared] pairFromQr:qr generation:generation];
            dispatch_async(dispatch_get_main_queue(), ^{
                DshRelayModule *current = weakSelf;
                if (!current || current->_pairVersion != version || !current->_pairing) return;
                current->_pairing = NO;
                if (callback) callback(result);
            });
        });
    };
    [presenter presentViewController:scanner animated:YES completion:nil];
}

- (void)connect:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    [self listen:callback];
    [[DshRelayRuntime shared] connect];
}

- (void)disconnect:(NSDictionary *)args {
    [self cancelPairing];
    [[DshRelayRuntime shared] disconnect];
}

- (void)forget:(NSDictionary *)args {
    [self cancelPairing];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    [[DshRelayRuntime shared] forgetPairing];
    if (callback) callback(@{ @"ok": @YES });
}

- (void)cancelPairing {
    _pairVersion++;
    _pairing = NO;
    _scanner.onResult = nil;
    [_scanner dismissViewControllerAnimated:YES completion:nil];
    _scanner = nil;
}

- (id)status:(NSDictionary *)args {
    return [[DshRelayRuntime shared] currentState];
}

- (void)subscribe:(NSDictionary *)args {
    [self listen:args[KR_CALLBACK_KEY]];
}

- (void)listen:(KuiklyRenderCallback)callback {
    if (_listenerToken != 0) {
        [[DshRelayRuntime shared] removeListener:_listenerToken];
        _listenerToken = 0;
    }
    if (!callback) return;
    _listenerToken = [[DshRelayRuntime shared] addListener:^(NSDictionary *state) {
        callback(state);
    }];
}

@end
