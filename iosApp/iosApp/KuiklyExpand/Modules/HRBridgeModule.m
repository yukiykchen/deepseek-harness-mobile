#import "HRBridgeModule.h"

#import "KuiklyRenderViewController.h"
#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import "iosApp-Swift.h"

#define REQ_PARAM_KEY @"reqParam"
#define CMD_KEY @"cmd"
#define FROM_HIPPY_RENDER @"from_hippy_render"
// 扩展桥接接口
/*
 * @brief Native暴露接口到kotlin侧，提供kotlin侧调用native能力
 */

/// Refuses every redirect so `GET /?token=` returns its 303 (with Set-Cookie) directly.
@interface DshNoRedirectDelegate : NSObject <NSURLSessionTaskDelegate>
@end

@implementation DshNoRedirectDelegate
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task willPerformHTTPRedirection:(NSHTTPURLResponse *)response newRequest:(NSURLRequest *)request completionHandler:(void (^)(NSURLRequest *))completionHandler {
    completionHandler(nil);
}
@end

@implementation HRBridgeModule

@synthesize hr_rootView;

- (void)copyToPasteboard:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    UIPasteboard *pasteboard = [UIPasteboard generalPasteboard];
    pasteboard.string = content;
}

- (void)log:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    NSLog(@"KuiklyRender:%@", content);
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

- (void)setSystemBarsDimmed:(NSDictionary *)args {
}

- (void)shareText:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *text = params[@"text"];
    if (text.length == 0) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        UIViewController *presenter = [DshNativeUi topViewController];
        if (!presenter) return;
        UIActivityViewController *sheet =
            [[UIActivityViewController alloc] initWithActivityItems:@[ text ] applicationActivities:nil];
        sheet.popoverPresentationController.sourceView = presenter.view;
        sheet.popoverPresentationController.sourceRect =
            CGRectMake(CGRectGetMidX(presenter.view.bounds), CGRectGetMaxY(presenter.view.bounds), 0, 0);
        [presenter presentViewController:sheet animated:YES completion:nil];
    });
}

- (void)setStatusBarStyle:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    BOOL dark = [params[@"dark"] intValue] == 1;
    dispatch_async(dispatch_get_main_queue(), ^{
        UIViewController *top = [DshNativeUi topViewController];
        if ([top isKindOfClass:[KuiklyRenderViewController class]]) {
            [(KuiklyRenderViewController *)top applyThemeChrome:dark];
        }
    });
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
    BOOL valid = [[DshSshKeyStore shared] validateKey:params[@"keyId"] ?: @""];
    if (callback) callback(@{ @"valid": @(valid) });
}

- (void)deleteSshKey:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    [[DshSshKeyStore shared] deleteKey:params[@"keyId"] ?: @""];
}

/// DSH >= 0.1.2 browser-session bootstrap: `GET {baseUrl}/?token=` answers 303 + Set-Cookie.
/// Redirects are refused in the delegate so the cookie of the first response is kept.
- (void)mintAuthCookie:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *baseUrl = params[@"baseUrl"] ?: @"";
    NSString *token = params[@"token"] ?: @"";
    NSString *bearer = params[@"bearer"] ?: @"";
    while ([baseUrl hasSuffix:@"/"]) baseUrl = [baseUrl substringToIndex:baseUrl.length - 1];
    if (baseUrl.length == 0 || token.length == 0) {
        if (callback) callback(@{ @"cookie": @"", @"status": @0, @"message": @"missing baseUrl or token" });
        return;
    }
    NSString *encoded = [token stringByAddingPercentEncodingWithAllowedCharacters:[NSCharacterSet URLQueryAllowedCharacterSet]];
    NSURL *url = [NSURL URLWithString:[NSString stringWithFormat:@"%@/?token=%@", baseUrl, encoded ?: token]];
    if (!url) {
        if (callback) callback(@{ @"cookie": @"", @"status": @0, @"message": @"invalid baseUrl" });
        return;
    }
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
    request.HTTPMethod = @"GET";
    request.timeoutInterval = 30;
    if (bearer.length > 0) {
        [request setValue:[NSString stringWithFormat:@"Bearer %@", bearer] forHTTPHeaderField:@"Authorization"];
    }
    NSURLSessionConfiguration *configuration = [NSURLSessionConfiguration ephemeralSessionConfiguration];
    configuration.HTTPShouldSetCookies = NO;
    configuration.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicyNever;
    DshNoRedirectDelegate *delegate = [DshNoRedirectDelegate new];
    NSURLSession *session = [NSURLSession sessionWithConfiguration:configuration delegate:delegate delegateQueue:nil];
    NSURLSessionDataTask *task = [session dataTaskWithRequest:request completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        NSInteger status = 0;
        NSString *cookie = @"";
        if ([response isKindOfClass:[NSHTTPURLResponse class]]) {
            NSHTTPURLResponse *http = (NSHTTPURLResponse *)response;
            status = http.statusCode;
            // NSHTTPURLResponse joins repeated Set-Cookie headers with ", "; split on the cookie-name pattern.
            NSString *setCookie = http.allHeaderFields[@"Set-Cookie"] ?: @"";
            NSArray<NSString *> *candidates = [setCookie componentsSeparatedByString:@", dsh-auth-"];
            for (NSUInteger index = 0; index < candidates.count; index++) {
                NSString *candidate = candidates[index];
                if (index > 0) candidate = [@"dsh-auth-" stringByAppendingString:candidate];
                NSString *pair = [[candidate componentsSeparatedByString:@";"].firstObject stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
                if ([pair hasPrefix:@"dsh-auth-"] && [pair containsString:@"="]) { cookie = pair; break; }
            }
        }
        NSString *message = error ? (error.localizedDescription ?: @"request failed")
            : (cookie.length == 0 ? [NSString stringWithFormat:@"HTTP %ld: no session cookie returned", (long)status] : @"");
        [session finishTasksAndInvalidate];
        dispatch_async(dispatch_get_main_queue(), ^{
            if (callback) callback(@{ @"cookie": cookie, @"status": @(status), @"message": message });
        });
    }];
    [task resume];
}

- (void)startSshKeepAlive:(NSDictionary *)args {
}

- (void)stopSshKeepAlive:(NSDictionary *)args {
}

@end
