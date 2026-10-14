# Apps-tests

Reverse-engineering experiments on Android apps using
[REA (Reverse Engineer Anything)](https://github.com/morluto/rea) and its
headless JADX backend.

Only analyze apps you own, open-source apps, or apps you are otherwise
authorized to test, and respect each app's license and local law.

## Layout

| Path | Purpose |
|------|---------|
| `apks/` | Drop APKs here. Git-ignored — binaries are never committed. |
| `reports/<package>/` | One folder per analyzed app: `summary.md` (generated), `AndroidManifest.xml`, `report.md` (write-up), `raw/` (full REA evidence JSON, git-ignored). |
| `templates/report.md` | Skeleton for the write-up. |
| `scripts/setup.sh` | Installs the pinned `rea-agents` CLI and the checksum-verified JADX engine JAR. |
| `scripts/analyze.sh` | Baseline static pass on one APK. |

## Requirements

Node.js ≥ 22.19, a full JDK 17+ (with `javac`), `ps` (procps), `jq`, `curl`.

## Setup

```sh
scripts/setup.sh
export REA_JADX_MCP_JAR="$HOME/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar"
```

In Claude Code, `.claude/settings.json` runs this automatically on session
start and `.mcp.json` registers REA as an MCP server, so the agent can call
REA tools directly.

## Workflow

1. Put the APK in `apks/` (e.g. download an open-source app from F-Droid, or
   pull one from a device with `rea pull-adb-package`).
2. Run the baseline pass:
   ```sh
   scripts/analyze.sh apks/example.apk
   ```
3. Dig in with targeted REA commands:
   ```sh
   rea search-android-classes apks/example.apk Login
   rea inspect-android-class  apks/example.apk com.example.LoginActivity
   rea inspect-android-method apks/example.apk com.example.LoginActivity onCreate
   rea trace-android-references apks/example.apk com.example.api.ApiClient
   ```
   Use `--help` on any command for its exact arguments, and `--format json`
   for machine-readable evidence.
4. Record findings, with evidence, in `reports/<package>/report.md`.

## HueNotes (clean-room clone)

`docs/clean-room-spec.md` describes ColorNote-style behaviour as observed
structurally with REA; `huenotes/` is an original Kotlin implementation
(notes, checklist lines, colours, archive, search, reminders, share).

```sh
export ANDROID_HOME=/opt/android-sdk   # SDK with platform 34 + build-tools 34.0.0
cd huenotes && gradle :app:assembleDebug
```

Also implemented: password lock (UI gate only; the database is not encrypted), calendar/Today view, two home-screen widgets. Backup: JSON export/import from the menu (import adds notes, never overwrites). Tests: `cd huenotes && gradle :app:testDebugUnitTest` (7 Robolectric tests covering store, lock, widgets-when-locked, backup). UI: Material 3 (cards, colour filter, checklist editor, dark mode, adaptive icon); previews in `docs/screens/` are rendered on the JVM by `ScreenshotTest`. Screenshots are blocked only while a password is set. Not yet done: testing on a real device or emulator (no KVM in the cloud container). Not run on a
device or emulator yet, only compiled and statically inspected.

### v0.2 additions

Sort and view sheet (last edited, created, A-Z, colour, reminder; grid or list), pinned notes, swipe to archive with Undo,
long-press quick menu, Trash (restored for 30 days, then purged), reminder presets, checklist progress, word count,
a Settings screen (theme, language, text size, default colour/sort/start screen, typing suggestions, first day of week,
widget opacity, password, backup, daily local snapshots) and five languages: English, Francais, Espanol, Portugues, Deutsch.
Strings are checked by `StringsTest` so no language can miss a key. On Android 12 and older the in-app language applies to
screens; widgets and notifications follow it too through `Loc`.
