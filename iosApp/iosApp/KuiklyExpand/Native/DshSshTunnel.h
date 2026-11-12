#import <Foundation/Foundation.h>
#import "DshSshSession.h"

NS_ASSUME_NONNULL_BEGIN

@interface DshSshTunnel : NSObject

- (instancetype)initWithSessionFactory:(DshSshSessionFactory)factory;

@property (nonatomic, copy, nullable) void (^onState)(NSDictionary *state);

- (void)connectWithHost:(NSString *)host
                   port:(NSInteger)port
               username:(NSString *)username
          remoteDshPort:(NSInteger)remoteDshPort
               keyBytes:(NSData *)keyBytes
             passphrase:(NSString *)passphrase
            fingerprint:(NSString *)fingerprint;
- (void)acceptFingerprint:(NSString *)fingerprint;
- (void)disconnect;
- (nullable NSString *)endpoint;

@end

NS_ASSUME_NONNULL_END
