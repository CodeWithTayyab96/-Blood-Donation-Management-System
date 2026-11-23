# Signing and releasing new versions

## Why a signing key matters

Android only lets an app update itself in place if the new APK is signed
with the **same key** as the installed APK. If the key changes, or is
lost, users get a signature error and must uninstall first, and
uninstalling deletes all their donor data, because this app stores
everything locally in SQLite.

That is why:

- The keystore is **never** committed to git (see `.gitignore`).
- The release workflow fails if the signing secrets are missing,
  instead of publishing an unsigned APK.
- A second key must never be generated: it would break in-place updates
  for everyone who already installed the app.

## The key (already set up)

The release key already exists and is stored as four GitHub Secrets
(repo > Settings > Secrets and variables > Actions):

- `RELEASE_KEYSTORE_BASE64` — base64 of `release.keystore`
- `RELEASE_KEY_ALIAS` — `blooddonation`
- `RELEASE_STORE_PASSWORD` — the keystore password
- `RELEASE_KEY_PASSWORD` — the key password

Key facts: RSA 2048, alias `blooddonation`, valid to 2054. Keep a backup
of the keystore and both passwords somewhere safe and offline. If the
key is lost, in-place updates are gone for good. Never share the
keystore or passwords in chat, screenshots, or a public repo.

## Releasing a new version

1. Bump `versionCode` (+1) and `versionName` in
   `app/build.gradle.kts`. Never reuse a versionCode.
2. Add a `## <versionName>` section to `CHANGELOG.md` in the same
   commit. The workflow uses it as the release notes.
3. Commit and push to `main`.
4. The **Release APK** workflow runs the SQL checks and the unit tests,
   builds the signed APK, tags `v<versionName>` and publishes a normal
   release that shows as **Latest**. There is no manual tagging and no
   pre-release step. Full gates and rollback: `RELEASING.md`.

## If a release fails

- "RELEASE_KEYSTORE_BASE64 secret is missing" — one of the four
  secrets above is missing. Add it and push again.
- "Tag vX already exists" or "versionCode is not greater than the last
  release's" — the version was not bumped. Bump `versionName` and
  `versionCode` in the same commit and push again.
- Install fails with a signature error on a phone — the phone has an
  APK signed with a different key (usually an old debug build).
  Uninstall that build first, then install the release build.
