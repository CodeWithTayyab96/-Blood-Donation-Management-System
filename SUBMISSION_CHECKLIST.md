# Blood Donation Management System — Submission Checklist

## Update, v1.3 Supabase version — submission now Monday 2026-11-02 (supersedes the Oct 26 date below)
- **New due date: Monday 2026-11-02.** v1.2 (signed release, live) stays
  the safe fallback — nothing in v1.3 touches it. v1.3 is built in a
  separate copy and is NOT pushed, tagged or released yet.
- **What v1.3 adds:** donors, donations, requests and stock sync with
  Supabase PostgREST (tables already created and verified in project
  "CodeWithTayyab96's Project": donors, donations, requests, stock,
  users; stock has all 8 groups at 0; donors has the same 5 seed rows
  as the app). Login/accounts stay on local SQLite; local SQLite is
  the offline fallback — if Supabase is unreachable the app still
  works exactly like v1.2 and never crashes.
- **CI is wired for Supabase:** both `.github/workflows/android.yml`
  (debug) and `.github/workflows/release.yml` (signed release) pass
  the repository secrets `SUPABASE_URL` and `SUPABASE_ANON_KEY` through
  as Gradle properties into the BuildConfig fields of the same names.
  Those secrets already exist in the repo (added 2026-10-26, names
  only); no key value appears anywhere in code or workflows. Without
  them a CI-built APK would silently fall back to local SQLite, so
  confirm the secrets are present before judging a build "online".
- **Still to decide / do before submission (do NOT skip):**
  1. Review `supabase_policies_review.sql` and decide whether to apply
     RLS policies to the live project. If RLS is on without an anon
     policy, PostgREST returns 401/403 and the app falls back to
     SQLite. The review policies are permissive — anyone with the
     project URL + anon key could read/write the demo data — so no
     real donor phone numbers until proper auth is added.
  2. Push approval for v1.3, then a green GitHub Actions build
     (debug and/or signed release, version 1.3 / versionCode 4).
  3. Phone smoke test of the CI-built APK with the secrets in place:
     add a donor, record a donation, create + fulfil a request, then
     check the same rows appear in the Supabase dashboard; then turn
     off network and confirm the app still works from SQLite.
  4. Demo talking points, updated: online Supabase data + offline
     SQLite fallback; eligibility 18–65 / ≥50 kg / 90-day gap; O−
     universal donor, AB+ universal recipient; login stays local.
- The dated sections below are the v1.1/v1.2 history and remain for
  reference only.

# Blood Donation Management System — Submission Checklist (history)
**Due: Monday, Oct 26** · App is built, verified and pushed — these are the final laptop-side steps.

## Update, Sat 24 Oct ~21:16 PKT — cloud build is now GREEN, APK ready (supersedes the 20:48 failure note below)
- Fix pushed as `dafad56` over SSH: removed the failing `android-actions/setup-android@v3` step (GitHub's `ubuntu-latest` runners already ship the Android SDK) and switched the build to `./gradlew assembleDebug` with Gradle caching.
- Run #2 for `dafad56` completed successfully: checkout, JDK 17, Gradle caching, build debug APK, upload APK — all green. The APK is in the repo's Actions run as artifact **blood-donation-debug-apk**.
- Repo made PUBLIC on 24 Oct ~21:06 PKT, so the Actions page is readable without sign-in.
- Fastest route for Monday is now: repo → Actions → latest green run → download `blood-donation-debug-apk` → install on phone (Android 8.0+) and smoke-test (steps below). The laptop Android Studio build is now only a fallback, not the main route.

## Update, Sat 24 Oct ~20:48 PKT — v1.1 IS now on GitHub, but its cloud build failed
- Pushed via SSH deploy key (bypassing the GitHub App 403): new head `ee6d935` (on top of the Oct 22 commit `3af234a`), 22 files — the full v1.1 priority merge (login + Manager/Donor roles, My Profile with next eligible date, city filter) plus the GitHub Actions workflow plus `gradle/wrapper/gradle-wrapper.jar` and the `gradlew` executable-bit fix. Read-back verified: workflow file and Login screen are live on `main`.
- The cloud build for that push FAILED: GitHub's notification email (20:49 PKT) says the job failed in 18 seconds with 3 annotations. The exact failing step is still unknown — the GitHub connector has no Actions-run/log tool, and the browser read of the Actions page hit a sign-in wall. He was asked (20:53 PKT) for either the GitHub browser sign-in or an Actions screenshot so the exact error can be fixed and re-pushed over SSH. Do not claim an Actions-built APK is ready until a green run is seen.
- Because the wrapper jar is now in the repo, a fresh clone no longer needs the old restore-wrapper step below — `./gradlew assembleDebug` should work straight from the clone (Android Studio Hedgehog+, JDK 17).

## Where things stand (updated 24 Oct ~21:16 PKT)
- v1.1 pushed to GitHub (`main`, commit `ee6d935`, on top of `3af234a`), fix commit `dafad56` — read-back verified, cloud build green.
- Repo: https://github.com/CodeWithTayyab96/-Blood-Donation-Management-System (now PUBLIC; note the stray leading `-`, cosmetic — see step 1)
- All 19 Kotlin files (v1.1) compile clean (0 errors) and all resources pass `aapt2`.
- The earlier 403 problem is bypassed for this repo by the SSH deploy key he added (titled Muse, write access) — future pushes can go over SSH.

## Fastest route: download the GitHub-built APK (no Android Studio needed) — GREEN since 24 Oct ~21:16 PKT
1. Open the repo's Actions tab → latest "Build Android APK" run for `dafad56` (green) → Artifacts → `blood-donation-debug-apk` — that's your `app-debug.apk`.
2. Install it on your phone (Android 8.0+) and smoke-test the demo flow below.
3. Fallback that always works: build on your laptop from the clone (steps below) or from the v1.1 zip sent in chat on 24 Oct — the zip already contains everything, including the workflow file and wrapper jar.

## Steps on your laptop
1. **(Optional, 30 seconds)** Remove the leading dash from the repo name: open the repo → **Settings → General → Repository name** → change `-Blood-Donation-Management-System` to `Blood-Donation-Management-System` → approve the 2FA prompt. Old links redirect automatically.
2. **Clone (or pull) the repo** on your laptop (note the leading hyphen in the current repo name):
   `git clone https://github.com/CodeWithTayyab96/-Blood-Donation-Management-System.git`
3. **Skip the old restore step — DONE in `ee6d935`:** `gradle/wrapper/gradle-wrapper.jar` is now in the repo. Just check it is present after cloning; no copy or commit needed.
4. **Open in Android Studio** (Hedgehog or newer, JDK 17), let Gradle sync.
5. **Build the APK:** Build → Build Bundle(s) / APK(s) → Build APK(s) — or `./gradlew assembleDebug` in the terminal.
6. **Install on your phone** (Android 8.0+) or an emulator and smoke-test the demo flow:
   - Dashboard shows the 5 seeded donors on first launch
   - Add a donor — try an invalid one (e.g. age 16, weight 45 kg) to show the eligibility validation working
   - Record a donation → stock count goes up
   - Create a blood request → see compatible, eligible donors listed

## Demo talking points (if asked)
- **Eligibility rule:** 18–65 years old, weight ≥ 50 kg, no donation in the last 90 days.
- **Compatibility:** standard transfusion rules — O− is the universal donor, AB+ the universal recipient.
- **Storage:** local SQLite, works fully offline; dashboard shows donors, units in stock, pending requests, units donated this month.
