# Blood Donation Management System

An Android app for managing blood donors, blood stock, donation records and
blood requests for a blood bank / hospital blood unit.

**Platform:** Android 8.0 (API 26) and above
**Language:** Kotlin · **Storage:** SQLite (local, offline)

## Features

- **Sign-in and roles** — local, offline accounts: a Manager (full access)
  and Donors (view everything, but only edit their own profile/donations).
  Demo manager account: `manager` / `manager123`. Donors register from the
  sign-in screen, which creates their account and donor profile together.
  Passwords are stored only as SHA-256 hashes, never in plain text.
- **My Profile** — a donor's own details, donation history, and their
  **next eligible date** (last donation + 90 days, or "eligible now").
- **Dashboard** — live counts: registered donors, units in stock, pending
  requests, and units donated this month, plus stock per blood group.
- **Donor management** — register donors (name, age, gender, blood group,
  phone, city, weight, last donation date), search by name/city, and filter
  by blood group **and by city** (nearby-donor search).
- **Eligibility checker** — a donor is eligible only if they are 18–65 years
  old, weigh at least 50 kg, and have not donated in the last 90 days.
- **Record donations** — one tap records a donation, updates the donor's
  last-donation date, and adds the unit to stock.
- **Blood stock** — available units for all 8 blood groups with
  AVAILABLE / LOW / OUT OF STOCK indicators.
- **Blood requests** — create patient requests (hospital, group, units,
  urgency, contact), fulfil them from stock, and instantly see every
  **compatible, eligible donor** using standard transfusion compatibility
  rules (O− is the universal donor, AB+ the universal recipient).

## Screenshots

_Add screenshots here after running the app._

## How to run

1. Open the project in **Android Studio** (Hedgehog or newer).
2. Let Gradle sync (JDK 17 required).
3. Run on an emulator or a physical device running Android 8.0+.

The app seeds five sample donors on first launch so every screen can be
explored immediately; all data is stored locally in SQLite.

## Project structure

```
app/src/main/java/com/tayyab/blooddonation/
├── db/DbHelper.kt            SQLite schema + all queries (v2 adds users)
├── model/Models.kt           Donor, Donation, BloodRequest, UserAccount
├── util/BloodCompatibility.kt  Compatibility table + eligibility rules
├── util/Session.kt           Signed-in account + password hashing
└── ui/                       Activities and RecyclerView adapters
    ├── LoginActivity.kt      Sign-in (launcher activity)
    ├── RegisterActivity.kt   Donor account + profile creation
    └── ProfileActivity.kt    Own profile + next eligible date
```

## Inspiration and attribution

The login/manager split, profile view, and city-based donor search were
inspired by public open-source blood-donation apps (for example
Vinayak2002/Blood_Alliance and Amine-Smahi/Blood-Donation, the latter
MIT-licensed). No code was copied from those projects: every feature above
was written fresh for this app's own SQLite schema. The dashboard,
eligibility rules, compatibility matching, stock, and request fulfilment
are this project's own work.

## Author

Muhammad Tayyab Imran — BS Software Engineering, The University of Faisalabad
