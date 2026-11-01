# Releasing

Short version: **tag a commit whose `main` run is green, and the tag publishes
a pre-release.** Test the APK from the release page, then promote it. Nothing
reaches a phone's in-app updater until you promote.

## Before you tag

1. **Version.** `app/build.gradle.kts` must hold `versionCode` = last published
   + 1 and `versionName` = the tag without the `v`. At the time of writing that
   is `1.6` / `7`, so the tag is `v1.6`.
2. **Signing secrets exist** (Settings → Secrets and variables → Actions):
   `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEY_ALIAS`, `RELEASE_STORE_PASSWORD`,
   `RELEASE_KEY_PASSWORD`. The workflow fails on purpose if the keystore secret
   is missing — it will not publish an APK nobody can update over. See
   `SIGNING.md`.
3. **`main` is green.** Check the latest run of **Build Android APK** (it runs
   the unit tests and builds) and **SQL checks**. Never tag a red or untested
   commit: the release workflow only assembles — it does not run tests.
4. **The changelog matches.** `CHANGELOG.md` has a section for this version,
   and the release body in `.github/workflows/release.yml` carries the same
   list in short form.

## Tag and push

```bash
git tag v1.6
git push origin v1.6
```

The tag must match `versionName` exactly (`v1.6` ↔ `"1.6"`). The workflow
compares the two and exits 1 with "Bump versionName (and versionCode) before
tagging" if they disagree.

## What the tag does

`.github/workflows/release.yml` runs on any `v*` tag:

1. restores the keystore from `RELEASE_KEYSTORE_BASE64`;
2. `assembleRelease` with the signing config and the Supabase properties
   (Firebase properties too, if those secrets ever exist);
3. deletes the keystore from the workspace;
4. writes `update.json` with the exact `versionCode`, `versionName` and APK URL;
5. publishes a GitHub Release with `app-release.apk` + `update.json`,
   **marked as a pre-release**.

## Promote it

After the APK passes the test plan:

```bash
gh release edit v1.6 --prerelease=false
```

(or untick **Set as a pre-release** on the release page and save).

**Why this matters:** the in-app updater reads
`/repos/.../releases/latest`, and GitHub's `latest` endpoint ignores
pre-releases. So while the release is a pre-release, phones on the previous
version keep seeing the previous version and are never prompted. Promoting it
is the single step that makes the new `versionCode` visible to them — no
re-tag, no rebuild, no asset changes.

## Roll back

Before anyone installed it:

```bash
gh release delete v1.6 --yes          # removes the release + its assets
git push origin :refs/tags/v1.6       # deletes the tag on the remote
git tag -d v1.6                       # ...and locally
```

The updater then sees the previous release again and phones stay where they
are.

**If a phone already installed it:** do **not** reuse that `versionCode` for
the retry. Bump it (7 → 8) in the same commit as the fix. Android refuses to
install a lower `versionCode` over a higher one, and the updater compares
`versionCode` — reusing it leaves that phone unable to move forward cleanly.

## Notes

- A pre-release is the default on purpose: a tag can never push itself onto
  someone else's phone.
- Deleting a tag does not delete the release, and deleting a release does not
  delete the tag. Do both, as above.
- Tags are the only trigger for a release. Pushing to `main` never publishes.
