#import "DshSshSession.h"
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#if __has_include(<DshLibssh2/libssh2.h>)
#include <DshLibssh2/libssh2.h>
#else
#include <libssh2.h>
#endif

@interface DshLibsshSession () {
@public
    NSLock *_cancelLock;
    BOOL _cancelled;
    int _socket;
    int _listener;
    NSInteger _localPort;
    LIBSSH2_SESSION *_session;
}
- (BOOL)isCancelled;
- (BOOL)waitForSessionUntil:(NSTimeInterval)deadline;
@end

@interface DshLibsshSession (Forwarder)
- (BOOL)runForwarder:(NSInteger)remotePort;
@end
