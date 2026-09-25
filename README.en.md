# Thrice Journal · 叁省手账

English | [简体中文](README.md)

An offline-first Android personal organizer:
**timetable · tasks · bookkeeping · focus timer · sleep tracking · notes**,
plus an **AI assistant** driven entirely by your own API key.
Timetables exported from any scheduling system (PDF / CSV / pasted text) are recognized and imported in one tap,
and all business data stays on your device.

> **Compliance**: the app never logs into any website on your behalf, never crawls websites and never automates actions.
> It ships with no backend. The only network traffic happens when **you configure your own AI provider URL and API key
> and start a conversation** (requests go directly to the provider you entered). Without AI configured, every other
> feature works fully offline.

## Download & Install (for users)

1. Go to this repo's **Releases** page: <https://github.com/EdmundAshford/Thrice/releases>
2. Download the latest `Sanxing.apk` (~117 MB, bundles 12 open-source fonts, no other dependencies)
3. Transfer it to your phone and install; allow "install from unknown sources" when prompted (menu wording varies by vendor)

> No account, no ads, no in-app purchases; all data stays on your device.
> The file is large because it bundles 12 open-source Chinese fonts (~45 MB) — that is expected.

## Features

### Timetable
- **Smart import**: tables copied from Excel / WPS or other systems, CSVs with Chinese or English headers,
  PDF text, and free text like “Course Mon Sec 1-2 Weeks 1-16 Room”. Weekdays, sections and weeks
  (including odd/even weeks) are extracted automatically and can be fixed row by row
- Weekly grid; reschedule / suspension / makeup exceptions; “other courses” list; all-courses page
- Multiple independent **timetable schemes (terms)**, switch anytime
- Create and name a new term at the last import step, or import into an existing term
- Postcard-style long-image share; ICS calendar export (whole term / this week)
- Pre-class reminders via exact alarms (re-scheduled after reboot); Glance home-screen widgets
  (today’s classes / this-week mini grid)

### Tasks
- Calendar and list views; date, start/end time, notes, types and tags
- **Recurrence**: daily / weekly / monthly / yearly / every N days / legal workdays / Ebbinghaus / fixed date set,
  with end date or max count and per-occurrence completion
- Notifications delivered through system exact alarms

### Bills
- Expense / income entries with notes, customizable categories and a 15-color palette
- Monthly / yearly stats: daily and monthly expense bars, category-share donut, balance and daily average

### Focus
- Count-up / countdown pomodoro with an animated water sphere; pause / discard / finish
- Auto-settlement via exact alarm + foreground service (survives process death), finish notification, history stats

### Sleep
- Night sleep and nap records with manual entry; sleep goal by duration or bed/wake times
- Bedtime and nap reminders; duration statistics

### Notes
- Folders, tags, lightweight Markdown-style rendering
- Per-note font size and font family (or follow the global setting)

### AI assistant (optional)
- Multi-provider config: you bring the **endpoint URL and API key**
  (stored in a separate DataStore, excluded from backups)
- Switchable personas (system prompts); per-domain read/write grants over your local data
- Conversation history is truncated before sending to bound token usage

### Personalization
- 10 built-in theme colors plus an RGB custom color; custom wallpaper with independently adjustable opacity;
  dark mode
- Bilingual UI (Chinese / English), following the system or switched manually
- Optional numbered fonts (01–12), see “Fonts” below

### Data
- Local backup / restore (overwrite or merge), per-category export and clearing
- Room database at schema v11 with 10 continuous migrations (v1 → v11, no destructive migration)

## Tech stack

| Item | Version |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose (BOM 2024.10.01, Material3) |
| Build | AGP 8.7.3 + Gradle 8.9 + JDK 17 |
| SDK | compileSdk 35 / minSdk 26 / targetSdk 35 |
| Database | Room 2.6.1 (KSP), continuous migrations |
| DI | Hilt 2.52 (KSP) |
| Preferences / secrets | DataStore Preferences 1.1.1 |
| Networking | OkHttp 4.12 (AI chat to user-configured providers only) |
| Reminders / background | AlarmManager exact alarms + WorkManager 2.9.1 + foreground service |
| Widgets | Glance AppWidget 1.1.0 |
| PDF text | pdfbox-android 2.0.27.0 |

## Modules

```
:app          Android app (Compose UI / reminders / widgets / import & export / AI screens)
:core:data    Android library: Room / Repository / DataStore / AI tool engine
:core:parser  Pure Kotlin JVM: timetable parsing, ICS / backup formats, reminder math; no Android dependency
```

Dependency direction: `:app → :core:data → :core:parser`.

## Build

Requirements: **JDK 17** and **Android SDK Platform 35** (plus Build-Tools / Platform-Tools for CLI builds).

The gradle-wrapper.jar is included. Before the first build, create `local.properties` at the project root
(it is git-ignored):

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

```bash
# Debug build installs directly; no signing config needed
./gradlew :app:assembleDebug

# Pure-JVM parser tests (no Android device needed)
./gradlew :core:parser:test
```

> Dependency resolution prefers Aliyun mirrors with official repos as fallback, and the Gradle distribution
> is fetched via a Tencent Cloud mirror (handy in mainland China). Adjust `settings.gradle.kts` and
> `gradle/wrapper/gradle-wrapper.properties` for other networks.

Release builds ship with **no signing config**: add your own `signingConfigs` in `app/build.gradle.kts`.
No keystore is included or will ever be committed.

### Fonts

The 12 numbered font files (`f01.ttf` … `f12.ttf`) under `app/src/main/res/font/` **ship with this
repository**. All of them are open-source fonts licensed under the **SIL Open Font License 1.1**
(Noto Sans SC, three ZCOOL faces, four LXGW WenKai variants, Ma Shan Zheng, Zhi Mang Xing,
Long Cang and Liu Jian Mao Cao) and may be used and redistributed freely.

What is committed is a **subsetted, modified version** of each font (ASCII + GB2312 codepoints only,
with the `name` table, hinting and OpenType layout tables stripped), about 45 MB in total.
Per the OFL “Reserved Font Name” clause a modified version must not use the original font name,
so the UI only ever shows numbers 01–12. Font names, copyright and the full license text are in
**[THIRD_PARTY_FONTS.md](THIRD_PARTY_FONTS.md)**.

> This repository only accepts fonts that permit redistribution. Subsetting does **not** change
> glyph outlines and does not remove foundry markers such as `OS/2.achVendID`, so commercial fonts
> (Hanyi, Founder, Arphic, DynaComware, …) must never be added.

To rebuild them (e.g. when adding or removing a font):

```bash
pip install fonttools
python tools/fetch_open_fonts.py   # download the 12 original OFL fonts into Fonts/ (~110 MB, git-ignored)
python tools/subset_fonts.py       # subset into app/src/main/res/font/fNN.ttf
```

When the number of fonts changes, also update the `AppFontOption` enum in `ui/theme/Theme.kt`;
the picker narrows itself to the fonts actually present in the package.

## Built-in samples

- “Load built-in sample” uses the two PDFs under `app/src/main/assets/samples/`.
  They contain a **fully fictional timetable** (fake name, student ID, group chats, teachers and rooms).
- Golden test samples live in `core/parser/src/test/resources/samples/`.

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | AI chat only, to the provider you configured; no network access at all when AI is unused |
| `POST_NOTIFICATIONS` | Course / task / sleep / focus reminders (runtime request on Android 13+) |
| `SCHEDULE_EXACT_ALARM` | Fire reminders exactly on time; gracefully degrades to inexact alarms if denied |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule reminders after reboot |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep an active focus session alive |

Apart from AI conversations you initiate, the app uploads nothing.

## Testing & debugging

```bash
./gradlew :core:parser:test                                  # parser / ICS / backup round-trip tests
./gradlew :core:parser:test -DupdateGolden=true              # regenerate full golden snapshots
```

- Golden baselines: `core/parser/src/test/resources/golden/`
- Full parse snapshots are written to each module’s `build/test-golden/` for manual inspection

## Known limitations

- A small amount of historical seed data (default task type and bill category names) was written in Chinese
  by older app versions and does not switch with the system language; rename or reset it manually.
- “Legal workday” rules ship with mainland-China 2026 holiday / makeup-workday data; other years fall back
  to Monday–Friday.

## License

[MIT License](LICENSE), copyright EdmundAshford. Issues and PRs are welcome:
<https://github.com/EdmundAshford/Thrice>
