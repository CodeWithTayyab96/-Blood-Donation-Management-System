# Signing and releasing new versions

Read this before you release v1.2. The short version: **generate the
signing key once, keep it safe forever, then every release after that is
just a tag push.**

## Why a signing key matters

Android only lets an app update itself in place if the new APK is signed
with the **same key** as the installed APK. If the key changes, or is
lost, users get a signature error and must uninstall first — and
uninstalling deletes all their donor data, because this app stores
everything locally in SQLite.

That is why:

- The keystore is **never** committed to git (see `.gitignore`).
- The release workflow fails if the signing secrets are missing,
  instead of publishing an unsigned APK.
- The generate-key workflow refuses to run if a key already exists.

## One-time setup (do this once, before v1.2)

1. Push this code to GitHub, including
   `.github/workflows/generate-key.yml`.
2. On GitHub, go to **Actions > Generate Release Signing Key >
   Run workflow** and run it once.
3. Download the `release-signing-key` artifact immediately. It
   contains:
   - `release.keystore` — the actual signing key
   - `signing-secrets.txt` — the alias and both passwords
4. On GitHub, go to **Settings > Secrets and variables > Actions >
   New repository secret** and add these four secrets:
   - `RELEASE_KEYSTORE_BASE64` — run `base64 -w0 release.keystore`
     on the downloaded keystore and paste the whole output
     (on macOS: `base64 -i release.keystore`)
   - `RELEASE_KEY_ALIAS` — `blooddonation`
   - `RELEASE_STORE_PASSWORD` — from `signing-secrets.txt`
   - `RELEASE_KEY_PASSWORD` — from `signing-secrets.txt`
5. **Delete the workflow artifact** (Actions > the run > Artifacts >
   delete).
6. **Back up `release.keystore` + `signing-secrets.txt` somewhere safe
   and offline** — a USB drive, not just this laptop. If you lose
   them, in-place updates are gone for good.
7. Never share the keystore or passwords in chat, screenshots, or a
   public repo.

## Releasing a new version (every time after that)

1. Bump `versionCode` (+1) and `versionName` in
   `app/build.gradle.kts`. Never reuse a versionCode.
2. Commit and push to `main`.
3. Tag and push the tag, matching the versionName:
   ```
   git tag v1.2
   git push origin v1.2
   ```
4. The **Release APK** workflow builds a signed `app-release.apk`,
   writes `update.json` (with the exact versionCode), and publishes
   both to a GitHub Release with the same tag.
5. Users on the old version open the app, tap **Check for Updates**
   on the home screen, and get a prompt to download and install
   the new version. Android will ask them once to allow installs
   from this app — that is normal.

## Important: the first signed release

v1.1 was published as a **debug** APK, signed with Android's debug
key. The first signed release (v1.2) uses a **different** key, so
Android will refuse to install it over v1.1. Users on v1.1 must
uninstall v1.1 first, then install v1.2 fresh. After v1.2, every
later version uses the same key, so in-app updates will work
without uninstalling.

## If a release fails

- "RELEASE_KEYSTORE_BASE64 secret is missing" — you have not done
  the one-time setup above yet.
- "A release key already exists" from the generate-key workflow —
  do not generate a second key. Use the existing secrets.
- Install fails with a signature error on a phone — the phone has
  an APK signed with a different key (usually the old debug build).
  Uninstall that build first, then install the release build.
