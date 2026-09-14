#import "KuiklyRenderViewController.h"
#import "UINavigationController+FDFullscreenPopGesture.h"
#import <OpenKuiklyIOSRender/KuiklyRenderViewControllerBaseDelegator.h>
#import <OpenKuiklyIOSRender/KuiklyRenderContextProtocol.h>

#define HRWeakSelf __weak typeof(self) weakSelf = self;

static NSString *const kDshIsNightModeKey = @"isNightMode";
static NSString *const kDshThemeDidChangedEvent = @"themeDidChanged";

@interface KuiklyRenderViewController()<KuiklyRenderViewControllerBaseDelegatorDelegate>

@property (nonatomic, strong) KuiklyRenderViewControllerBaseDelegator *delegator;
@property (nonatomic, assign) BOOL dshDarkChrome;

@end

@implementation KuiklyRenderViewController {
    NSDictionary *_pageData;
}

- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData {
    if (self = [super init]) {
        pageData = [self p_mergeExtParamsWithOriditalParam:pageData];
        _pageData = pageData;
        _dshDarkChrome = [pageData[kDshIsNightModeKey] boolValue];
        _delegator = [[KuiklyRenderViewControllerBaseDelegator alloc] initWithPageName:pageName pageData:pageData];
        _delegator.delegate = self;
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.fd_prefersNavigationBarHidden = YES;
    self.view.backgroundColor = [self p_backgroundColorForDark:self.dshDarkChrome];
    [_delegator viewDidLoadWithView:self.view];
    [self.navigationController setNavigationBarHidden:YES animated:NO];

}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [_delegator viewDidLayoutSubviews];

}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [_delegator viewWillAppear];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
}

- (void)viewDidAppear:(BOOL)animated {
    [super viewDidAppear:animated];
    [_delegator viewDidAppear];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    [_delegator viewWillDisappear];
}

- (void)viewDidDisappear:(BOOL)animated {
    [super viewDidDisappear:animated];
    [_delegator viewDidDisappear];
}

- (void)traitCollectionDidChange:(UITraitCollection *)previousTraitCollection {
    [super traitCollectionDidChange:previousTraitCollection];
    if (@available(iOS 13.0, *)) {
        if (previousTraitCollection.userInterfaceStyle == self.traitCollection.userInterfaceStyle) {
            return;
        }
        BOOL night = self.traitCollection.userInterfaceStyle == UIUserInterfaceStyleDark;
        [_delegator sendWithEvent:kDshThemeDidChangedEvent data:@{ kDshIsNightModeKey: @(night) }];
    }
}

#pragma mark - theme

- (void)applyThemeChrome:(BOOL)dark {
    self.dshDarkChrome = dark;
    self.view.backgroundColor = [self p_backgroundColorForDark:dark];
    [self setNeedsStatusBarAppearanceUpdate];
}

- (UIStatusBarStyle)preferredStatusBarStyle {
    if (@available(iOS 13.0, *)) {
        return self.dshDarkChrome ? UIStatusBarStyleLightContent : UIStatusBarStyleDarkContent;
    }
    return self.dshDarkChrome ? UIStatusBarStyleLightContent : UIStatusBarStyleDefault;
}

/// Mirrors DshTheme.light.background / DshTheme.dark.background.
- (UIColor *)p_backgroundColorForDark:(BOOL)dark {
    if (dark) {
        return [UIColor colorWithRed:0x10 / 255.0 green:0x12 / 255.0 blue:0x15 / 255.0 alpha:1.0];
    }
    return [UIColor colorWithRed:0xF7 / 255.0 green:0xF9 / 255.0 blue:0xFA / 255.0 alpha:1.0];
}

#pragma mark - private

- (NSDictionary *)p_mergeExtParamsWithOriditalParam:(NSDictionary *)pageParam {
    NSMutableDictionary *mParam = [(pageParam ?: @{}) mutableCopy];
    BOOL night = NO;
    if (@available(iOS 13.0, *)) {
        night = UITraitCollection.currentTraitCollection.userInterfaceStyle == UIUserInterfaceStyleDark;
    }
    mParam[kDshIsNightModeKey] = @(night);
    return mParam;
}

#pragma mark - KuiklyRenderViewControllerDelegatorDelegate

- (UIView *)createLoadingView {
    UIView *loadingView = [[UIView alloc] init];
    loadingView.backgroundColor = [self p_backgroundColorForDark:self.dshDarkChrome];
    return loadingView;
}

- (UIView *)createErrorView {
    UIView *errorView = [[UIView alloc] init];
    errorView.backgroundColor = [self p_backgroundColorForDark:self.dshDarkChrome];
    return errorView;
}

- (void)fetchContextCodeWithPageName:(NSString *)pageName resultCallback:(KuiklyContextCodeCallback)callback {
    if (callback) {
        // 返回对应framework名字
        callback(@"shared", nil);
    }
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

@end
