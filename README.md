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

### v0.3

"+" now opens an Add chooser (Text or List). Google sync (Drive app-data folder, last change wins per note, tombstones for
deletions), a fixed debug signing key so updates install over older builds, and an INTERNET permission that is used only
for sync. Setup steps: `docs/google-sync-setup.md`. Sync logic, the Drive client (against a local fake server) and the
database upgrades are covered by tests; real Google sign-in has not been exercised.

### v0.4

The "+" button is now a speed-dial (Image, Drawing, Audio, List, Text). Notes can carry photos (resized to 1280 px, EXIF
rotation fixed), drawings (pen colours, sizes, eraser, undo; tap a drawing to draw on top of it) and voice notes
(AAC, up to 10 minutes). Cards show a thumbnail and the voice-note length. Attachments sync through the same Drive
app-data folder as notes: files are uploaded before the notes file, and unreferenced files are only swept after 24 hours.

Known limits: the JSON backup and the daily snapshots contain note text only, not attachments (use Google sync to keep
them safe); camera capture, microphone recording and audio playback are not covered by automated tests.

### v0.5

Each photo, drawing and voice note has a ⋮ menu: **Share** (Android share sheet, so Telegram, WhatsApp and any other app;
the file is given a readable name such as "Weekend trip - Photo - 2026-10-14 15.30.jpg"), **Save to phone**
(Pictures/HueNotes for photos and drawings, Music/HueNotes for voice notes; on Android 9 and older a save dialog asks where)
and **Remove**. Sharing a whole note sends its text together with its attachments.

"+" gained a **Link** line: paste or type an address (a copied link is filled in), optionally a title; the page title is
looked up in the background when you leave it empty (Settings → Notes → Fetch link titles, on by default; it contacts
that website). Link notes show the site name on their card and open the page when tapped; a **Links** chip filters the
list to them. Sharing a link from a browser to **HueNotes** saves it (any shared text is saved as a note).
A link note is simply a note whose first line is a web address, so backup and sync need no changes.

The share-sheet entry's label is a string (`share_target_label`) that spells out the app name: update it when renaming.

### v0.6

Checklist items can have **sub-items** (one level). Each row being typed in shows an indent button; sub-items show a guide line
and a "move out" button. Ticking a parent ticks its sub-items; ticking the last open sub-item ticks the parent; unticking any
sub-item unticks it; a parent with some sub-items done shows a dash. Enter on a sub-item adds a sibling, Enter on a parent adds
a top-level row after its group, Backspace on an empty sub-item moves it out and then deletes it, and removing a parent keeps its
sub-items as top-level rows. Cards show sub-items indented and "3 of 7 done"; the editor line reads "7 items · 3 done"; shared
text keeps the indentation. Stored as plain text (two spaces before a sub-item), so old lists, backups and sync are unchanged.
The rules live in `Checklist.kt` and are covered by `ChecklistTest`; `SubItemUiTest` drives the real editor.

### v0.7

The **Links** chip is now a links section: every web address found in any note (text, checklist items, titles; `https://…` and
`www.…`) is listed automatically, nothing to add by hand, and it stays in step as notes change. The chip shows the count
("Links · 12"). Each row shows the site (or a link note's title), the address and "in <note>"; tap to open, ⋮ for Open, Copy,
Share or Open note. Search, the colour filter and the Notes/Archive tabs apply to it; trashed notes are left out. A link
note (one that starts with an address) is still what "+ → Link" and "Share → HueNotes" create. The list is built on the fly from
the notes (`LinkIndex.kt`), so backup and sync are unaffected. Addresses keep brackets that belong to them
(`…/Foo_(bar)`) and drop punctuation that doesn't (`see https://a.com.`).

### v0.8 — Android 16 target
- compileSdk/targetSdk 36 (AGP 8.13.2, Kotlin 2.2.21); every screen is edge-to-edge with system-bar/keyboard insets.
- Removed the unused `SCHEDULE_EXACT_ALARM` permission; reminders use `setAndAllowWhileIdle`, and are re-armed after an app update.
- Not yet tested on a real Android 16 device (no emulator in the build container).

### v0.9 — Version history
- Every content change keeps the previous text as a version (min. 5 min apart, max 50 per note, local only); content replaced by a sync is kept too.
- Editor overflow menu → "Version history" → preview → Restore. Deleting a note deletes its history. DB version 5.
