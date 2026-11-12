#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface DshSshKeyValidator : NSObject
/** Parses the private key without network I/O. A passphrase is checked by decryption. */
+ (BOOL)validateKeyData:(NSData *)keyData passphrase:(NSString *)passphrase;
@end

NS_ASSUME_NONNULL_END
