# Releasing

**You do not tag anything any more.** A push to `main` that changes the app is
the release. The `Release APK` workflow builds it, tags it, and publishes it as
a normal release, which becomes **Latest** — the thing the in-app updater reads.

## What makes a release happen

The workflow runs on every push to `main`, then decides:

| Changed in the commit | Release? |
|---|---|
| `app/**` | yes |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/**` | yes |
| `CHANGELOG.md` | yes |
| only `supabase/**`, `*.sql`, `*.md`, `.github/**` | no — nothing installable changed |
| anything, with `[skip release]` in the commit message | no |

## The gates, in order

1. **SQL checks for the same commit must pass.** They run in parallel; the
   release job waits up to six minutes and then fails rather than publishing on
   an unverified commit.
2. **Unit tests** (`testDebugUnitTest`) must pass. They compile the app, so a
   broken build stops here.
3. **The tag must not exist.** Tag = `v` + `versionName`.
4. **`versionCode` must be greater than the last release's.** The job reads the
   previous release's `update.json` and fails with a clear message if it is not.
   This is the rule that keeps the in-app updater sane: it compares
   `versionCode`, and Android refuses to install a lower one over a higher.

So the only thing you must do by hand is **bump both numbers and write the
changelog, in the same commit as the change**:

```kotlin
// app/build.gradle.kts
versionCode = 9          // always +1, never reused
versionName = "1.8"      // the tag becomes v1.8
```

```markdown
<!-- CHANGELOG.md, at the top, newest first -->
## 1.8 — 2026-11-02

- What changed, in plain English.
```

The workflow extracts everything under `## <versionName>` and uses it as the
release notes. No section, no notes — the release still happens, but says so.

## What gets published

- tag `v<versionName>` (annotated, pushed by the workflow);
- a **normal** release — not a pre-release — marked **Latest**;
- `app-release.apk` (signed) and `update.json`:

```json
{ "versionName": "1.8", "versionCode": 9,
  "apkUrl": "https://github.com/<owner>/<repo>/releases/download/v1.8/app-release.apk",
  "tag": "v1.8" }
```

Phones on an older version see it on their next update check (at app start and
every ~12 hours) and are offered it by the in-app updater.

## Forcing a release

Actions → **Release APK** → *Run workflow* → tick **force**. It runs the same
gates from the current `main`, even if the last commit changed nothing
installable. Use it after fixing a failed release without touching the app.

## When a release fails

| Message | Meaning | Fix |
|---|---|---|
| `Tag v1.8 already exists` | you bumped the name but not the number, or re-pushed an old commit | bump `versionName` |
| `versionCode 8 is not greater than the last release's 8` | forgot the bump | bump `versionCode` and `versionName` |
| `SQL checks finished 'failure'` | the SQL files are broken | fix them; the release waits for green |
| `RELEASE_KEYSTORE_BASE64 is missing` | secrets were removed | see `SIGNING.md` |

Nothing is published on a failure, so a red run costs you nothing but the
retry.

## Rolling a release back

```bash
gh release delete v1.8 --yes      # removes the release and its assets
git push origin :refs/tags/v1.8   # deletes the tag on the remote
git tag -d v1.8                   # ...and locally
```

**If a phone already installed it, do not reuse that `versionCode`** — bump it
for the retry, or that phone can never move forward cleanly. Deleting a tag
does not delete a release, and the other way round; do both, as above.

## Things that are deliberately gone

- **Debug APKs.** No workflow builds one. The only APK is signed with the
  release key, because an unsigned or debug-signed build can never update an
  installed copy in place.
- **Manual tagging.** A `v*` tag pushed by hand no longer publishes anything;
  `Release APK` only reacts to `main` and to a manual run of itself.
- **Pre-releases.** Releases are normal, so they show as Latest. If you ever
  need a build that phones must not pick up, run the workflow and immediately
  tick *Set as a pre-release* on the result — but that is a manual step, and
  the next normal release supersedes it.
