#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@protocol DshSshSession <NSObject>
- (BOOL)connectHost:(NSString *)host port:(NSInteger)port;
- (NSString *)fingerprint;
- (BOOL)authenticateUser:(NSString *)user key:(NSData *)key passphrase:(NSString *)passphrase;
- (NSInteger)startForwarding;
- (BOOL)pumpRemotePort:(NSInteger)port;
/** May be called from another thread. Only shutdown is allowed here, never native handle free/close. */
- (void)cancel;
/** All libssh2 operations, including final resource release, run on the owning worker. */
- (void)close;
@end

@interface DshLibsshSession : NSObject <DshSshSession>
@end

typedef id<DshSshSession> _Nonnull (^DshSshSessionFactory)(void);

NS_ASSUME_NONNULL_END
