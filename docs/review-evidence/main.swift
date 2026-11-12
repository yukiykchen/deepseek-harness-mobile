import Foundation

let directory = CommandLine.arguments[1]
let encrypted = try String(contentsOfFile: directory + "/encrypted-ed25519", encoding: .utf8)
let plain = try String(contentsOfFile: directory + "/plain-ed25519", encoding: .utf8)
let fake = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nnot-base64-or-a-key\n-----END ENCRYPTED PRIVATE KEY-----"
print("realEncryptedOpenSSHWithCorrectPassphrase=\(DshSshKeyValidator.validate(key: encrypted, passphrase: "review-test"))")
print("realPlainOpenSSHWithEmptyPassphrase=\(DshSshKeyValidator.validate(key: plain, passphrase: ""))")
print("realPlainOpenSSHWithExtraPassphrase=\(DshSshKeyValidator.validate(key: plain, passphrase: "review-test"))")
print("invalidEncryptedPEMWithArbitraryPassphrase=\(DshSshKeyValidator.validate(key: fake, passphrase: "anything"))")
