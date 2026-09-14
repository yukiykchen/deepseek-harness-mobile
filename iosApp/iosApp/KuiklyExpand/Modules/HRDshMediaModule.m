#import "HRDshMediaModule.h"

#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import <AVFoundation/AVFoundation.h>
#import <PhotosUI/PhotosUI.h>
#import <UIKit/UIKit.h>

/// Collects the picker/camera result, encodes it, and answers the shared module once.
@interface HRDshMediaPicker : NSObject <PHPickerViewControllerDelegate,
                                        UIImagePickerControllerDelegate,
                                        UINavigationControllerDelegate>
@property (nonatomic, copy) KuiklyRenderCallback callback;
@property (nonatomic, assign) NSInteger maxCount;
@property (nonatomic, assign) NSInteger maxDimension;
@property (nonatomic, assign) long long maxBytes;
@property (nonatomic, strong) NSMutableArray<NSDictionary *> *images;
@property (nonatomic, assign) NSInteger pending;
@end

@implementation HRDshMediaModule {
    HRDshMediaPicker *_picker;
}

#pragma mark - Shared-module methods

- (void)pickImages:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    HRDshMediaPicker *picker = [self pickerWithParams:params callback:args[KR_CALLBACK_KEY]];
    UIViewController *host = [self topViewController];
    if (!host) {
        [picker finishWithError:@"no-picker" message:@"The app window is gone."];
        return;
    }
    if (@available(iOS 14.0, *)) {
        PHPickerConfiguration *configuration = [[PHPickerConfiguration alloc] init];
        configuration.filter = [PHPickerFilter imagesFilter];
        configuration.selectionLimit = MAX((NSInteger)1, picker.maxCount);
        PHPickerViewController *controller = [[PHPickerViewController alloc] initWithConfiguration:configuration];
        controller.delegate = picker;
        [host presentViewController:controller animated:YES completion:nil];
        return;
    }
    UIImagePickerController *legacy = [[UIImagePickerController alloc] init];
    legacy.sourceType = UIImagePickerControllerSourceTypePhotoLibrary;
    legacy.delegate = picker;
    [host presentViewController:legacy animated:YES completion:nil];
}

- (void)captureImage:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    HRDshMediaPicker *picker = [self pickerWithParams:params callback:args[KR_CALLBACK_KEY]];
    UIViewController *host = [self topViewController];
    if (!host || ![UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera]) {
        [picker finishWithError:@"no-picker" message:@"This device has no camera."];
        return;
    }
    // The system prompt is raised by the picker itself; a denial comes back as
    // AVAuthorizationStatusDenied, which reads as a permission error rather than silence.
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
    if (status == AVAuthorizationStatusDenied || status == AVAuthorizationStatusRestricted) {
        [picker finishWithError:@"permission-denied"
                        message:@"Camera access is off. Allow it in Settings to take a photo."];
        return;
    }
    UIImagePickerController *camera = [[UIImagePickerController alloc] init];
    camera.sourceType = UIImagePickerControllerSourceTypeCamera;
    camera.delegate = picker;
    [host presentViewController:camera animated:YES completion:nil];
}

#pragma mark - Helpers

- (HRDshMediaPicker *)pickerWithParams:(NSDictionary *)params callback:(KuiklyRenderCallback)callback {
    HRDshMediaPicker *picker = [[HRDshMediaPicker alloc] init];
    picker.callback = callback;
    picker.maxCount = MAX((NSInteger)1, [params[@"maxCount"] integerValue]);
    picker.maxDimension = [params[@"maxDimension"] integerValue];
    picker.maxBytes = [params[@"maxBytes"] longLongValue];
    _picker = picker;
    return picker;
}

- (UIViewController *)topViewController {
    UIWindow *window = nil;
    for (UIWindow *candidate in UIApplication.sharedApplication.windows) {
        if (candidate.isKeyWindow) {
            window = candidate;
            break;
        }
    }
    UIViewController *controller = window.rootViewController;
    while (controller.presentedViewController) {
        controller = controller.presentedViewController;
    }
    return controller;
}

@end

@implementation HRDshMediaPicker

- (instancetype)init {
    self = [super init];
    if (self) {
        _images = [NSMutableArray array];
    }
    return self;
}

#pragma mark - PHPickerViewControllerDelegate

- (void)picker:(PHPickerViewController *)picker
    didFinishPicking:(NSArray<PHPickerResult *> *)results API_AVAILABLE(ios(14.0)) {
    [picker dismissViewControllerAnimated:YES completion:nil];
    if (results.count == 0) {
        [self finishWithError:nil message:@""];
        return;
    }
    NSArray<PHPickerResult *> *limited = results.count > (NSUInteger)self.maxCount
        ? [results subarrayWithRange:NSMakeRange(0, self.maxCount)]
        : results;
    self.pending = (NSInteger)limited.count;
    for (PHPickerResult *result in limited) {
        [result.itemProvider loadObjectOfClass:UIImage.class
                            completionHandler:^(__kindof id<NSItemProviderReading> object, NSError *error) {
            NSDictionary *entry = [object isKindOfClass:UIImage.class]
                ? [self entryForImage:(UIImage *)object name:result.itemProvider.suggestedName]
                : nil;
            dispatch_async(dispatch_get_main_queue(), ^{
                if (entry) {
                    [self.images addObject:entry];
                }
                self.pending -= 1;
                if (self.pending <= 0) {
                    [self deliver];
                }
            });
        }];
    }
}

#pragma mark - UIImagePickerControllerDelegate

- (void)imagePickerController:(UIImagePickerController *)picker
    didFinishPickingMediaWithInfo:(NSDictionary<UIImagePickerControllerInfoKey, id> *)info {
    [picker dismissViewControllerAnimated:YES completion:nil];
    UIImage *image = info[UIImagePickerControllerOriginalImage];
    NSDictionary *entry = image ? [self entryForImage:image name:nil] : nil;
    if (entry) {
        [self.images addObject:entry];
    }
    [self deliver];
}

- (void)imagePickerControllerDidCancel:(UIImagePickerController *)picker {
    [picker dismissViewControllerAnimated:YES completion:nil];
    [self finishWithError:nil message:@""];
}

#pragma mark - Encoding

/**
 * Encodes as PNG, or JPEG when the image has to be downscaled to fit [maxDimension] so
 * the Host's pixel limits cannot be tripped by a camera-resolution photo. Data is
 * withheld above [maxBytes] because prevalidation rejects it on the metadata alone.
 */
- (NSDictionary *)entryForImage:(UIImage *)image name:(NSString *)name {
    UIImage *source = image;
    BOOL downscaled = NO;
    CGFloat longest = MAX(image.size.width, image.size.height) * image.scale;
    if (self.maxDimension > 0 && longest > (CGFloat)self.maxDimension) {
        CGFloat ratio = (CGFloat)self.maxDimension / longest;
        CGSize target = CGSizeMake(floor(image.size.width * image.scale * ratio),
                                   floor(image.size.height * image.scale * ratio));
        UIGraphicsBeginImageContextWithOptions(target, NO, 1.0);
        [image drawInRect:CGRectMake(0, 0, target.width, target.height)];
        source = UIGraphicsGetImageFromCurrentImageContext() ?: image;
        UIGraphicsEndImageContext();
        downscaled = YES;
    }
    NSData *data = downscaled ? UIImageJPEGRepresentation(source, 0.9) : UIImagePNGRepresentation(source);
    if (data.length == 0) {
        return nil;
    }
    NSString *mediaType = downscaled ? @"image/jpeg" : @"image/png";
    NSString *fallbackName = [NSString stringWithFormat:@"image.%@", downscaled ? @"jpg" : @"png"];
    NSString *resolvedName = name.length > 0
        ? [[name stringByDeletingPathExtension] stringByAppendingPathExtension:(downscaled ? @"jpg" : @"png")]
        : fallbackName;
    BOOL withhold = self.maxBytes > 0 && (long long)data.length > self.maxBytes;
    return @{
        @"mediaType": mediaType,
        @"data": withhold ? @"" : [data base64EncodedStringWithOptions:0],
        @"name": resolvedName,
        @"bytes": @(data.length),
        @"width": @((NSInteger)(source.size.width * source.scale)),
        @"height": @((NSInteger)(source.size.height * source.scale)),
    };
}

#pragma mark - Delivery

- (void)deliver {
    if (self.images.count == 0) {
        [self finishWithError:@"decode-failed" message:@"That image could not be read."];
        return;
    }
    KuiklyRenderCallback callback = self.callback;
    self.callback = nil;
    if (callback) {
        callback(@{@"images": [self.images copy]});
    }
}

- (void)finishWithError:(NSString *)error message:(NSString *)message {
    KuiklyRenderCallback callback = self.callback;
    self.callback = nil;
    if (!callback) {
        return;
    }
    NSMutableDictionary *payload = [@{@"images": @[]} mutableCopy];
    if (error.length > 0) {
        payload[@"error"] = error;
    }
    if (message.length > 0) {
        payload[@"message"] = message;
    }
    callback(payload);
}

@end
