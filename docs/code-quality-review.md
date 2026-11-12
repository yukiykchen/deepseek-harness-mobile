# Code Quality Review

## 2026-11-10: Connection Setup

Resolved issues:

- Pending SSH key validation could save the wrong profile after edits or mode changes. Setup requests now capture the form revision, mode and immutable connection configuration.
- Repeated READY events created multiple Host probes. Probe ownership now includes the native generation and local port; reconnects release the previous probe.
- Probe results could navigate after destruction or stop a newer connection. Lifecycle invalidation and exact probe identity checks now guard these effects.
- Fingerprint confirmation both retried the existing connection and started a second connection. Confirmation now continues only the current native attempt.
- Key import callbacks could overwrite a new selection or leave orphaned keys. The form blocks duplicate picker/import completions and tracks unsaved key ownership, including results queued for later UI delivery.
- Encrypted Android SSH keys were rejected because preconnection validation never received the passphrase. Validation now uses the submitted passphrase; regression tests exercise correct, missing and incorrect passwords.
- Connection setup omitted the passphrase input and lost it during navigation. The input is masked, the passphrase is passed transiently to the next page, and route parameters are cleared when consumed. Config diagnostics redact it.
- Keyboard visibility did not reduce the scrolling viewport. On the Android simulator, the Save action was inaccessible while typing. The viewport now uses shared keyboard inset logic; screenshots confirmed scrolling the masked input and Save action above the keyboard.
- Cancelled scanners could start pairing after returning to an old page. Android captures pairing intent before worker dispatch and gives scanners separate request IDs; iOS captures intent before presentation; OHOS validates its current scanner slot before dispatching pairing.
- Missing or failed storage was treated as successful configuration persistence. Setup now reports failure without navigation; a key already referenced by a stored profile is not deleted if the separate mode write fails.

Verification:

- Focused shared form/setup tests exercise queued and duplicate results, mode changes, edits, reentrant disposal, reconnects, failures, fingerprint confirmation and navigation back from Home.
- Android unit tests parse an actual encrypted OpenSSH private key and verify all three passphrase cases.
- Android simulator screenshots were reviewed at 1080x2400: Relay and SSH forms, keyboard-visible scrolling, masked passphrase and reachable Save action.
- The setup-page taste scan has no confirmed P0/P1 defect. Its remaining X01/P2 navigation notice is intentional: the existing host starts a distinct Home Activity/ViewController; the setup controller resumes on return. V07/P2 inline palette duplication was removed.
- The timer scan's import-only warning does not distinguish scoped and global overloads. These controllers call the explicit pager-ID overload, verified in the installed Kuikly 2.24 source; they do not use the deprecated current-page overload.

Remaining scope, not considered complete:

- The 16 KB Android emulator displayed an App Compatibility dialog. ELF inspection identified the existing CameraX `libimage_processing_util_jni.so` and Kuikly SQLite `libsqlite3.so` as 4 KB-aligned; JNA and MLKit barcode libraries are already 16 KB ELF-aligned. A replacement native SQLite module and CameraX upgrade were attempted, but the incomplete dependency-transform work was rolled back to keep the verified build functional. Functional tests using compatibility mode do not prove native 16 KB support.
- Whole-repository UI scanning still has findings outside the connection setup page, requiring source triage and rendered-state review.
- OHOS HUKS encryption has host-boundary tests and real ArkTS compilation, but lacks device service verification and a regenerated signing profile for the renamed bundle.
- Simulator and unsigned iOS builds do not prove device permissions, camera behavior or Keychain access under a production signing identity.
