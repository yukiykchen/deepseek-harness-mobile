#import "KuiklyRenderViewController.h"
#import "UINavigationController+FDFullscreenPopGesture.h"
#import <OpenKuiklyIOSRender/KuiklyRenderViewControllerBaseDelegator.h>
#import <OpenKuiklyIOSRender/KuiklyRenderContextProtocol.h>
#import "iosApp-Swift.h"

#define HRWeakSelf __weak typeof(self) weakSelf = self;
@interface KuiklyRenderViewController()<KuiklyRenderViewControllerBaseDelegatorDelegate>

@property (nonatomic, strong) KuiklyRenderViewControllerBaseDelegator *delegator;
@property (nonatomic, assign) BOOL lastSystemDark;
@property (nonatomic, assign) BOOL chromeIsDark;
@property (nonatomic, assign) BOOL chromeApplied;

@end

@implementation KuiklyRenderViewController {
    NSDictionary *_pageData;
}

- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData {
    if (self = [super init]) {
        pageData = [self p_mergeExtParamsWithOriditalParam:pageData];
        _pageData = pageData;
        _delegator = [[KuiklyRenderViewControllerBaseDelegator alloc] initWithPageName:pageName pageData:pageData];
        _delegator.delegate = self;
        _lastSystemDark = [DshThemeChrome systemIsDark];
        _chromeIsDark = [DshThemeChrome resolveIsDark];
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.fd_prefersNavigationBarHidden = YES;
    [self applyThemeChrome:_chromeIsDark];
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
    [self applyThemeChrome:_chromeIsDark];
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

- (UIStatusBarStyle)preferredStatusBarStyle {
    return [DshThemeChrome statusBarStyle:_chromeIsDark];
}

- (void)traitCollectionDidChange:(UITraitCollection *)previousTraitCollection {
    [super traitCollectionDidChange:previousTraitCollection];
    if (!previousTraitCollection) {
        return;
    }
    BOOL systemDark = [DshThemeChrome systemIsDarkIn:self.traitCollection];
    if (systemDark == self.lastSystemDark) {
        return;
    }
    self.lastSystemDark = systemDark;
    [_delegator sendWithEvent:DshThemeChrome.themeDidChangedEvent
                         data:@{ DshThemeChrome.isNightModeKey: @(systemDark) }];
    [self applyThemeChrome:[DshThemeChrome resolveIsDark]];
}

- (void)applyThemeChrome:(BOOL)isDark {
    if (_chromeApplied && _chromeIsDark == isDark) {
        return;
    }
    _chromeIsDark = isDark;
    _chromeApplied = YES;
    [DshThemeChrome applyTo:self isDark:isDark];
}

#pragma mark - private

- (NSDictionary *)p_mergeExtParamsWithOriditalParam:(NSDictionary *)pageParam {
    NSMutableDictionary *mParam = [(pageParam ?: @{}) mutableCopy];
    mParam[DshThemeChrome.isNightModeKey] = @([DshThemeChrome systemIsDark]);
    return mParam;
}

#pragma mark - KuiklyRenderViewControllerDelegatorDelegate

- (UIView *)createLoadingView {
    UIView *loadingView = [[UIView alloc] init];
    loadingView.backgroundColor = [UIColor clearColor];
    return loadingView;
}

- (UIView *)createErrorView {
    UIView *errorView = [[UIView alloc] init];
    errorView.backgroundColor = [DshThemeChrome backgroundColor:_chromeIsDark];
    return errorView;
}

- (void)fetchContextCodeWithPageName:(NSString *)pageName resultCallback:(KuiklyContextCodeCallback)callback {
    if (callback) {
        callback(@"shared", nil);
    }
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

@end
