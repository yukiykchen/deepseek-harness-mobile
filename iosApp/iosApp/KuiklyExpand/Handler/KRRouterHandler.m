#import "KRRouterHandler.h"
#import "KuiklyRenderViewController.h"

@implementation KRRouterHandler

+ (void)load {
    [KRRouterModule registerRouterHandler:[self new]];
}

- (void)openPageWithName:(NSString *)pageName pageData:(NSDictionary *)pageData controller:(UIViewController *)controller {
    KuiklyRenderViewController *renderViewController = [[KuiklyRenderViewController alloc] initWithPageName:pageName pageData:pageData];
    if ([pageData[@"replaceCurrentPage"] boolValue] && controller.navigationController.topViewController == controller) {
        NSMutableArray *stack = [controller.navigationController.viewControllers mutableCopy];
        [stack removeLastObject];
        [stack addObject:renderViewController];
        [controller.navigationController setViewControllers:stack animated:YES];
    } else {
        [controller.navigationController pushViewController:renderViewController animated:YES];
    }
}

- (void)closePage:(UIViewController *)controller {
    [controller.navigationController popViewControllerAnimated:YES];
}

@end