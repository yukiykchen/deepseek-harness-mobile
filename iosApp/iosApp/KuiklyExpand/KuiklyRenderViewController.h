#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN

@interface KuiklyRenderViewController : UIViewController

/*
 * @brief 创建实例对应的初始化方法.
 * @param pageName 页面名 （对应的值为kotlin侧页面注解 @Page("xxxx")中的xxx名）
 * @param params 页面对应的参数（kotlin侧可通过pageData.params获取）
 * @return 返回KuiklyRenderViewController实例
 */
- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData;

/*
 * @brief 由 shared 层在应用主题变化时调用，同步状态栏样式与窗口底色。
 * @param dark 解析后的应用调色板是否为暗色
 */
- (void)applyThemeChrome:(BOOL)dark;
@end

NS_ASSUME_NONNULL_END
