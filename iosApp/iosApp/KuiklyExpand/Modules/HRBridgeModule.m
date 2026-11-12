#import "HRBridgeModule.h"

#import "KuiklyRenderViewController.h"
#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import "iosApp-Swift.h"

@implementation HRBridgeModule

@synthesize hr_rootView;

- (void)copyToPasteboard:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    UIPasteboard *pasteboard = [UIPasteboard generalPasteboard];
    pasteboard.string = content;
}

- (void)toast:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    if (content.length == 0) return;
    [DshNativeUi toast:content];
}

- (NSString *)closeKeyboard:(NSDictionary *)args {
    void (^dismissKeyboard)(void) = ^{
        [self.hr_rootView endEditing:YES];
        [self.hr_rootView.window endEditing:YES];
    };
    if ([NSThread isMainThread]) {
        dismissKeyboard();
    } else {
        dispatch_sync(dispatch_get_main_queue(), dismissKeyboard);
    }
    return @"true";
}

- (NSString *)currentTimestamp:(NSDictionary *)args {
    return [NSString stringWithFormat:@"%lld", (long long)(NSDate.date.timeIntervalSince1970 * 1000)];
}

- (void)setSystemBarsDimmed:(NSDictionary *)args {
}

- (void)pickSshKey:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    UIViewController *presenter = [DshNativeUi topViewController];
    if (!presenter) {
        if (callback) callback(@{ @"uri": @"" });
        return;
    }
    [[DshSshKeyStore shared] pickKeyFrom:presenter completion:^(NSString *uri) {
        if (callback) callback(@{ @"uri": uri ?: @"" });
    }];
}

- (void)importSshKey:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *keyId = [[DshSshKeyStore shared] importUri:params[@"uri"] ?: @""];
    if (keyId.length == 0) {
        if (callback) callback(@{ @"ok": @NO, @"message": @"无法读取 SSH 私钥" });
        return;
    }
    if (callback) callback(@{ @"ok": @YES, @"keyId": keyId });
}

- (void)validateSshKey:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    BOOL valid = [[DshSshKeyStore shared] validateKey:params[@"keyId"] ?: @""
                                             passphrase:params[@"keyPassphrase"] ?: @""];
    if (callback) callback(@{ @"valid": @(valid) });
}

- (void)deleteSshKey:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    [[DshSshKeyStore shared] deleteKey:params[@"keyId"] ?: @""];
}

@end
