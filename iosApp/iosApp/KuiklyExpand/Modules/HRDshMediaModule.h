#import <OpenKuiklyIOSRender/KRBaseModule.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Phone-side image sources for the official DSH image-prompt pipeline.
 *
 * Mirrors `KRDshMediaModule` on Android: returns canonical Base64 plus `mediaType`,
 * byte count and pixel size, and nothing else about the file.
 */
@interface HRDshMediaModule : KRBaseModule

@end

NS_ASSUME_NONNULL_END
