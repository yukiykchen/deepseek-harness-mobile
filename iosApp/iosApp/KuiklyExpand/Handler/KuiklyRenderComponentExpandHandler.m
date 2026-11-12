#import "KuiklyRenderComponentExpandHandler.h"
#import <SDWebImage/UIImageView+WebCache.h>
#import <SVGKit/SVGKit.h>
#import <objc/runtime.h>

static NSString * const kDshImageTag = @"DshImage";
static const void *kDshImageSrcKey = &kDshImageSrcKey;

static void DshImageDebugLog(NSString *message) {
#if DEBUG
    NSLog(@"[%@] %@", kDshImageTag, message);
#endif
}

@implementation KuiklyRenderComponentExpandHandler

+ (void)load {
    [KuiklyRenderBridge registerComponentExpandHandler:[self new]];
}

- (NSURL *)hr_customBundleUrlForFileName:(NSString *)fileName extension:(NSString *)fileExtension {
    [self dsh_logBundleSvgsOnce];
    NSString *baseName = fileName.lastPathComponent;
    NSURL *byBase = [[NSBundle mainBundle] URLForResource:baseName withExtension:fileExtension];
    NSURL *byPath = [[NSBundle mainBundle] URLForResource:fileName withExtension:fileExtension];
    NSURL *inCommon = [[NSBundle mainBundle] URLForResource:baseName
                                              withExtension:fileExtension
                                               subdirectory:@"common"];
    NSURL *resolved = byBase ?: byPath ?: inCommon;
    DshImageDebugLog([NSString stringWithFormat:@"bundle lookup ext=%@ resolved=%d",
                      fileExtension, resolved != nil]);
    return resolved;
}

- (BOOL)hr_setImageWithUrl:(NSString *)url forImageView:(UIImageView *)imageView {
    if (url.length == 0) {
        imageView.image = nil;
        objc_setAssociatedObject(imageView, kDshImageSrcKey, nil, OBJC_ASSOCIATION_COPY_NONATOMIC);
        DshImageDebugLog(@"clear image");
        return YES;
    }
    [self dsh_logBundleSvgsOnce];
    NSURL *resolved = [self dsh_resolvedURLForSrc:url];
    BOOL isSvg = [self dsh_isSVG:url] || [self dsh_isSVG:resolved.path] || [self dsh_isSVG:resolved.absoluteString];
    BOOL exists = resolved.isFileURL && resolved.path.length > 0 &&
        [[NSFileManager defaultManager] fileExistsAtPath:resolved.path];
    DshImageDebugLog([NSString stringWithFormat:@"image srcChars=%lu file=%d svg=%d exists=%d",
                      (unsigned long)url.length, resolved.isFileURL, isSvg, exists]);

    objc_setAssociatedObject(imageView, kDshImageSrcKey, url, OBJC_ASSOCIATION_COPY_NONATOMIC);
    if (isSvg) {
        NSURL *svgURL = resolved;
        __weak UIImageView *weakView = imageView;
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            SVGKImage *svgImage = nil;
            if (svgURL.isFileURL && svgURL.path.length > 0) {
                svgImage = [SVGKImage imageWithContentsOfFile:svgURL.path];
            } else if (svgURL) {
                svgImage = [SVGKImage imageWithContentsOfURL:svgURL];
            }
            NSArray *fatal = svgImage.parseErrorsAndWarnings.errorsFatal;
            if (fatal.count > 0) {
                DshImageDebugLog([NSString stringWithFormat:@"svg parse fatal errors=%lu",
                                  (unsigned long)fatal.count]);
            }
            UIImage *uiImage = svgImage.UIImage;
            dispatch_async(dispatch_get_main_queue(), ^{
                UIImageView *strongView = weakView;
                NSString *latest = objc_getAssociatedObject(strongView, kDshImageSrcKey);
                if (![latest isEqualToString:url]) {
                    return;
                }
                if (uiImage) {
                    strongView.image = uiImage;
                    DshImageDebugLog([NSString stringWithFormat:@"svg loaded size=%.0fx%.0f",
                                      uiImage.size.width, uiImage.size.height]);
                } else {
                    DshImageDebugLog(@"svg load failed");
                }
            });
        });
        return YES;
    }
    [imageView sd_setImageWithURL:resolved ?: [NSURL URLWithString:url]];
    return YES;
}

- (UIColor *)hr_colorWithValue:(NSString *)value {
    return nil;
}

- (BOOL)dsh_isSVG:(NSString *)value {
    return [[value.pathExtension lowercaseString] isEqualToString:@"svg"];
}

- (NSURL *)dsh_resolvedURLForSrc:(NSString *)src {
    if ([src hasPrefix:@"file://"]) {
        NSURL *url = [NSURL URLWithString:src];
        if (url.path.length > 0) {
            return url;
        }
        NSString *path = [src substringFromIndex:@"file://".length];
        return [NSURL fileURLWithPath:path];
    }
    if ([src hasPrefix:@"http://"] || [src hasPrefix:@"https://"]) {
        return [NSURL URLWithString:src];
    }
    if ([src hasPrefix:@"assets://"]) {
        NSString *relative = [src substringFromIndex:@"assets://".length];
        if ([relative hasPrefix:@"common/"]) {
            relative = [relative substringFromIndex:@"common/".length];
        }
        NSString *ext = relative.pathExtension;
        NSString *name = relative.stringByDeletingPathExtension.lastPathComponent;
        NSURL *url = [[NSBundle mainBundle] URLForResource:name withExtension:ext];
        DshImageDebugLog([NSString stringWithFormat:@"assets ext=%@ resolved=%d",
                          ext, url != nil]);
        return url;
    }
    return [NSURL URLWithString:src];
}

- (void)dsh_logBundleSvgsOnce {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSArray<NSString *> *svgs = [[NSBundle mainBundle] pathsForResourcesOfType:@"svg" inDirectory:nil];
        DshImageDebugLog([NSString stringWithFormat:@"mainBundle svgCount=%lu",
                          (unsigned long)svgs.count]);
    });
}

@end
