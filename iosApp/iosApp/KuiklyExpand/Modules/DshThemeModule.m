#import "DshThemeModule.h"
#import "KuiklyRenderViewController.h"
#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import "iosApp-Swift.h"

@implementation DshThemeModule

- (void)applyNativeChrome:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    BOOL isDark = [params[@"isDark"] boolValue];
    dispatch_async(dispatch_get_main_queue(), ^{
        UIViewController *controller = [DshNativeUi topViewController];
        if ([controller isKindOfClass:[KuiklyRenderViewController class]]) {
            [(KuiklyRenderViewController *)controller applyThemeChrome:isDark];
        }
    });
}

@end
