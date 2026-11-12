This directory holds a **public local debug** signing key. It is intentionally
weak and is never used for the `release` build type. A release build is unsigned
unless a private CI signing step provides its own keystore.

`DSH_PREVIEW_KEYSTORE`, `DSH_PREVIEW_KEYSTORE_PASSWORD`, `DSH_PREVIEW_KEY_ALIAS`
and `DSH_PREVIEW_KEY_PASSWORD` can point debug builds to a local throwaway key.
Never reuse this keystore or these defaults for Play, enterprise distribution,
or any APK that real users install as an upgradeable app.

Release signing uses `DSH_RELEASE_KEYSTORE`, `DSH_RELEASE_KEYSTORE_PASSWORD`,
`DSH_RELEASE_KEY_ALIAS` and `DSH_RELEASE_KEY_PASSWORD`. GitHub Release CI stores
the private keystore as `DSH_RELEASE_KEYSTORE_BASE64`; unsigned release artifacts
are rejected before upload.
