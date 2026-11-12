#import "DshSshKeyValidator.h"
#include <libssh2.h>
#include <string.h>

@implementation DshSshKeyValidator

+ (BOOL)validateKeyData:(NSData *)keyData passphrase:(NSString *)passphrase {
    if (keyData.length == 0 || keyData.length > 64 * 1024) return NO;
    if (libssh2_init(0) != 0) return NO;
    LIBSSH2_SESSION *session = libssh2_session_init_ex(NULL, NULL, NULL, NULL);
    if (!session) return NO;
    libssh2_session_set_blocking(session, 1);
    const char *secret = passphrase.length > 0 ? passphrase.UTF8String : "";
    // This parser only reads the key data in memory; no socket is attached to the session.
    int result = libssh2_userauth_publickey_frommemory(session, "", 0, NULL, 0,
        keyData.bytes, keyData.length, secret);
    BOOL parsed = result != LIBSSH2_ERROR_FILE && result != LIBSSH2_ERROR_PUBLICKEY_UNRECOGNIZED &&
        result != LIBSSH2_ERROR_KEYFILE_AUTH_FAILED;
    libssh2_session_free(session);
    return parsed;
}

@end
