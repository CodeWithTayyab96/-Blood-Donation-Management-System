# Blood Donation Management System

[![Release APK](https://github.com/CodeWithTayyab96/-Blood-Donation-Management-System/actions/workflows/android.yml/badge.svg)](https://github.com/CodeWithTayyab96/-Blood-Donation-Management-System/actions/workflows/android.yml)

An Android app that helps a blood bank connect donors with patients who need
blood. Donors register and manage their profile, families raise requests, and
managers see live stock and fulfil requests from it. It works online through
Supabase and keeps working offline on the phone's own database.

**Current version:** 1.28 (versionCode 29)
**Platform:** Android 8.0 (API 26) and above
**Language:** Kotlin

## Download

[Download the latest APK](https://github.com/CodeWithTayyab96/-Blood-Donation-Management-System/releases/latest/download/app-release.apk)

Requires Android 8.0 or newer. On first install, Android asks once to allow
installs from this app. All releases are on the
[releases page](https://github.com/CodeWithTayyab96/-Blood-Donation-Management-System/releases).

## Features

- **Donors** — register with name, age, gender, blood group, phone, city,
  weight and last donation date. Search by name or city, filter by blood
  group, and see the next eligible date (last donation + 90 days).
- **Eligibility** — a donor can only be offered for a request when they are
  18 to 65 years old, weigh at least 50 kg, and have not donated in the last
  90 days.
- **Blood stock** — live units for all 8 blood groups, with AVAILABLE, LOW
  and OUT OF STOCK indicators, plus a warning when a group runs low.
- **Blood requests** — create patient requests (hospital, group, units,
  urgency, contact) and fulfil them from stock. Every request shows the
  compatible, eligible donors using standard transfusion rules.
- **Urgent alerts** — signed-in donors are notified about pending Urgent and
  Critical requests their blood group can serve, at most once per request.
  Delivery is by Firebase push when configured, with a 15-minute background
  poll as the fallback, so alerts are prompt but never instant.
- **Blood camps and appointments** — managers create camps and donation
  slots; donors check in at camps and book slots, with capacity enforced.
- **Donor verification** — managers verify donors, and verified donors carry
  a badge on their profile.
- **Stock forecast** — per-group burn rate and days of stock left, from real
  donation and fulfilment history.
- **Offline emergency mode** — the most urgent requests stay cached on the
  phone, and a request written without internet is queued and sent when the
  connection returns.
- **Accounts** — real accounts through Supabase Auth (email + password), with
  a Manager role and a Donor role. Password reset by a 6-digit emailed code,
  and self-service account deletion.
- **English and اردو** — the whole app switches language from Settings,
  with right-to-left layout for Urdu.
- **AI helper (optional)** — "Can I donate?" answers and a daily motivation
  line, backed by an Edge Function. No personal data leaves the phone.
- **In-app updates** — the app checks for a new release on launch and offers
  the update, verified against the installed signing certificate.

## How it works

| Layer | Technology |
|---|---|
| App | Kotlin, Android 8.0+ (minSdk 26, targetSdk 34) |
| Online data | Supabase (PostgreSQL, Auth, PostgREST, Edge Functions) |
| Offline data | SQLite on the phone, synced when online |
| Background work | WorkManager (alerts, reminders, update checks) |
| Push (optional) | Firebase Cloud Messaging |
| AI helper (optional) | OpenRouter through the `ai-coach` Edge Function |

Online first, offline safe: each screen shows the local copy at once, then
refreshes from Supabase in the background. Writes go online first and then
into SQLite; if there is no connection, the change still saves on the phone
and the app says so.

## Getting started

1. Open the project in **Android Studio** (Hedgehog or newer).
2. Let Gradle sync (JDK 17 required).
3. Run on an emulator or a physical device running Android 8.0 or newer.

For a Supabase-enabled build, pass the project URL and anon key as Gradle
properties (values stay on your machine or in CI secrets, never in the repo):

```
./gradlew assembleRelease -PSUPABASE_URL=... -PSUPABASE_ANON_KEY=...
```

Without them the app runs fully offline on SQLite. Use the project's legacy
**anon public** key, never the service_role key.

## Releases

A push to `main` that changes the app is the release. The **Release APK**
workflow runs the SQL checks and the unit tests, builds the signed APK, tags
`v<versionName>` and publishes a normal release that shows as **Latest**.
There are no debug APKs and no manual tagging.

The only manual step is bumping `versionCode` (+1) and `versionName` in
`app/build.gradle.kts`, and adding a `## <versionName>` section to
`CHANGELOG.md` in the same commit. The workflow uses that section as the
release notes and refuses to publish if the version did not increase.
Full gates and rollback: `RELEASING.md`.

## Documentation

| Document | What it covers |
|---|---|
| `RUNBOOK.md` | Full operations guide: Supabase setup, Auth, email, push, AI |
| `SUPABASE_SETUP.md` | Supabase project setup and what changed per version |
| `PUSH_SETUP.md` | Firebase push notification setup |
| `AI_SETUP.md` | AI helper setup (Edge Function, model, limits) |
| `RELEASING.md` | Release gates, tagging and rollback |
| `SIGNING.md` | Signing key and release signing |
| `CHANGELOG.md` | What changed in every version |
| `DESIGN_P2P.md` | Design of donor offers and request flow |
| `sql/applied/` | Every SQL migration already applied, with rollbacks |

## Project structure

```
app/src/main/java/com/tayyab/blooddonation/
├── db/DbHelper.kt            SQLite schema and all queries
├── model/Models.kt           Donor, Donation, BloodRequest, UserAccount
├── util/                     Compatibility, eligibility, session, Supabase
│                             clients, sync, push, updater
└── ui/                       Activities and RecyclerView adapters
```

## Author

Muhammad Tayyab Imran — BS Software Engineering, The University of Faisalabad
