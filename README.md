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

### v0.10 — Conflict copies
- Sync remembers the version both devices last agreed on (`sync_base`, DB v6). If a note was changed on two devices with different text, the newer edit stays as the note and the older becomes a "(conflict copy)" note (same copy on every device); attachments from both sides are kept. A toast reports it.

### v0.11 — Sync status and background sync
- WorkManager: sync every ~6 h when online and ~30 s after a change (debounced), only when connected and "Sync automatically" is on. Never shows a consent screen; if sign-in has expired the status line says so.
- Settings → Sync shows the last sync time or the last error in plain words (offline, sign-in expired, Google busy). Transient failures are retried up to 3 times.
- Not yet tested against real Google or on a device; background timing is up to Android.

### v0.12 — Full backup
- "Back up everything" writes a `.zip` (`notes.json` + `attachments/…`) with photos, drawings and recordings; "Restore" accepts `.zip` and older `.json` files, never uses zip entry names as paths, and caps sizes.
- Automatic backup: pick a folder through the system picker; a daily zip is written there (WorkManager) and the newest 7 are kept. "Back up now" shows the result.
- The folder picker and writing into it are not covered by unit tests (no Storage Access Framework in the test environment); try it on a device.

### v0.13 — Import and Markdown export
- Settings → Backup → "Import from other apps": Google Keep (Takeout `.zip` or loose note `.json`: text, checklists, colour, pin, archive, dates, photos, audio; Keep's trash is skipped) and `.txt`/`.md` files (a `# Heading` is the title, otherwise the file name; all-checkbox files become checklists with sub-items). Re-importing adds nothing twice.
- "Export as Markdown": a `.zip` with one `.md` per note and `attachments/`.
- ColorNote's own backup is a proprietary encrypted format and is not supported; use its plain-text export.

### v0.14 — Receive photos, PDFs and files from other apps
- **Share → Save to HueNotes** now accepts photos, PDFs, documents and any other file, one or many at once (`SEND` and `SEND_MULTIPLE`). Everything shared together becomes one note: shared text is its body, the subject (or a lone file's name) its title.
- Photos are stored like camera photos (scaled to 1280 px, EXIF rotation fixed); GIFs, SVGs, pictures Android can't decode and all other files are kept byte for byte under their own name, up to 25 MB each. A single shared `.txt`/`.md` file becomes the note's text, like Import.
- In the editor a file shows its name, type and size (a PDF also shows its first page); tap to open it in another app, ⋮ for Open, Share, Save to phone (`Download/HueNotes`) and Remove. Cards show the file name or "3 files".
- Files sync, back up (zip), restore and export to Markdown like other attachments. Only `content://` addresses from other apps are read (`file://` and HueNotes' own are refused), and names are cleaned before use.
- DB version 7 (file name and type columns). The sync file and JSON backup move to format 3 / version 4 **only once a note has a file**, so older HueNotes installs report "from a newer version" instead of showing a broken photo: update every device.
- Not yet tried on a device: the share sheet itself, opening files in other apps, and PDF page previews (Robolectric has no PDF engine).

### v0.15 — Dictation and voice-note transcripts
- **Dictate** (new button in the editor's bottom bar, and "+ → Dictate" for a new note): speak, and the words go where the cursor is (title, text or checklist row; otherwise the end of the note or a new row) with spaces and sentence capitals fixed. It keeps listening across pauses until **Done**; **Cancel** throws that dictation away.
- **Transcribe** (⋮ on a voice note or a received audio file, Android 13+): the recording is decoded to 16 kHz mono and streamed to the recogniser; the transcript is added as a new paragraph (or checklist row), so it is searchable, editable, and syncs and backs up with no format change. **Stop** keeps what is done so far.
- Uses Android's own `SpeechRecognizer`, **on-device** whenever the phone has it (Android 12+), so the voice stays on the phone. Phones without it can opt in to the regular, possibly online, recogniser: Settings → Notes → **Online speech recognition** (off by default). If the language isn't on the phone, Android 13+ offers to download it. Recognition follows the app's language.
- Why not ML Kit's GenAI speech API: it is alpha (no deprecation policy), its basic mode is the same on-device recogniser, and file input needs the same real-time PCM feed.
- Not yet tried on a device: real recognition quality, continuous listening on specific recognisers, and transcription (decoding uses `MediaCodec`, which Robolectric doesn't run). The recogniser conversation, text placement, resampling and the editor flow are covered by `SpeechTest`.

### v0.16 — Speech language download on first use
- Before the first dictation or transcript, HueNotes asks the phone's speech service (normally Google's) which languages it has offline (Android 13+). It uses the installed variant of the app's language (e.g. `fr-CA` when `fr-FR` isn't there).
- If the language isn't on the phone yet, a dialog offers to **download** it. The download is done by the speech service from Google's servers, once; HueNotes ships no speech model. Android 14+ shows progress here and continues with the dictation when it finishes; if the service schedules it for later, won't report progress, or refuses, the dialog says so and offers **Speech settings** (the phone's voice input settings) to download it by hand.
- If offline speech doesn't offer the language at all, HueNotes offers **online recognition** (voice sent to the speech service), only after the person agrees; it never switches silently.
- Not yet tried on a device: how each phone's speech service answers these requests, and where "Speech settings" leads on each brand.

### v0.17 — Translation, and a visible Transcribe button
- **Translate** (editor ⋮ menu): the note's language is detected (ML Kit language ID, asked for when unsure), you pick the target (app language and recent choices first), and the result is shown with **Add below**, **Replace** (the original is kept in Version history) and **Copy**. Only words go to the translator: links, indentation, blank lines and checklist ticks/sub-items are kept as they are.
- Runs on the phone with Google's **ML Kit Translation**. Each language pack (~30 MB) is downloaded from Google the first time it's needed, after asking; Settings → Notes → **Translation languages** lists and deletes them. ML Kit sends Google diagnostics (languages used, device/app info, error codes; not the note text) when translating — see `docs/store/privacy-policy.md` and `docs/store/play-data-safety.md`.
- Voice notes now show a **Transcribe** button on the player when the phone can transcribe (Android 13+), besides the ⋮ menu entry.
- The translation engine is a 12–17 MB native library per CPU type, so debug APKs are now **split per ABI**: install `app-arm64-v8a-debug.apk` on current phones (`armeabi-v7a` for old 32-bit ones). The Play App Bundle splits by itself.
- Not yet tried on a device: real ML Kit downloads and translation quality (tests use a fake engine).

### v0.18 — Transcription crash fix, sturdier translation downloads
- **Fixed:** Transcribe closed the app. The audio pipe handed to the recogniser was closed right after `startListening()`, but Android passes the request to the speech service a moment later, and a closed descriptor there crashes the app. The pipe now stays open for the whole session (covered by `SpeechTest`). If the service never says it's finished, HueNotes stops waiting 30 s after the whole recording was handed over and keeps what was transcribed.
- **Translation packs** now behave like the speech download: on first use HueNotes asks, shows "Downloading French…", and carries on with the translation when done. Before each translation it also makes sure the packs are really present (a no-op when they are), so a missing pack is fetched instead of the translation failing. A failed download says why and offers **Try again**. Every ML Kit call is guarded so problems end in a message, not a closed app.
- **Error reports:** if HueNotes closes because of a bug, the error (versions, phone model, stack trace; no notes) is kept on the phone and the next start offers to share it. Nothing is sent unless the person shares it.

### v0.19 — Reports for freezes and native crashes
- On Android 11+, the next start also reports a **freeze (ANR)** or **native crash** (which the in-app crash handler can't catch), read from Android's own exit record: the main thread's stack for freezes, the readable parts of the tombstone for native crashes. Each report is offered once, and only shared if the person chooses to.
- HueNotes labels what it is doing (dictate, transcribe with language and file size, translate with the language pair) in Android's exit record and in crash reports, so a report says which action failed. No note content is included.
- Audio decoding for transcripts gives up after 3 s without output once the whole file was read (some decoders never flag the end), instead of waiting forever.

### v0.20 — Transcription that doesn't hang
- On a real phone (Transsion, Android 14) transcription stopped at 0:01: the speech service never read the audio handed to it, so HueNotes waited forever.
- The recording is now decoded first, then fed to the recogniser **at real-time speed** (as Google's engine expects for recordings), so a 9-second note takes about 9 seconds and the progress follows it.
- Two ways of listening: the long **segmented** session first; if the service stops reading the audio (6 s) or never answers (8 s), HueNotes switches to the classic **phrase-by-phrase** session (the one dictation uses), restarting after each phrase from where the audio had got to (rewound 0.5 s so no word is cut). If neither works, it stops with a clear message and **Share details**: a log of modes, recogniser events and timings, without transcript text.
- Fixed: stopping one session could make the recogniser report an error that was taken for the next session's.

### v0.21 — Transcription through ML Kit
- A device log (TECNO KL6, Android 14) showed Google's speech service never reads recordings handed over through Android's `EXTRA_AUDIO_SOURCE`, in either mode: it only listens to the microphone. So **Transcribe now uses Google's ML Kit speech recognition** (`genai-speech-recognition`, basic mode, alpha), which reads the recording itself (fed at real-time speed through a pipe) and talks to Google's speech app directly.
- First use: if ML Kit's speech model for the language isn't on the phone, HueNotes asks, then downloads it with progress (Google's servers), then transcribes. Android's recogniser (v0.20 path) remains the fallback when ML Kit isn't available or fails before producing text; if both fail, the shared log contains both attempts.
- Shown on Android 12+ when Google's speech app is installed. **Minimum Android is now 8.0** (ML Kit speech requires it; this also removes the old Android 7 reminder-channel lint error).
- **Confirmed on a device** (TECNO KL6, Android 14, French): dictation, the speech-language download, and transcription through ML Kit with its first-use model download.

### v0.22 — Labels and saved filters
- **Labels**: editor ⋮ → Labels (or long-press a note in the list → Labels). Tick existing labels or create one with **New label**. A note's labels show as chips under its title (× takes one off) and as a line on its card. Names are tidied and compared without regard to case; renaming onto an existing label merges the two.
- **List**: each label is a chip next to Links and the colours; tap to show only those notes (it combines with search and colour), long-press to rename or delete it everywhere. Search also finds label names. A new note made while a label is selected gets that label.
- **Saved filters**: when a search, colour or label is active, a **Save filter** chip names the combination; saved filters appear first in the chip row (tap to apply or clear, long-press to rename or delete). They are kept on this phone.
- Labels live on the note (DB v8 `labels` column), so they sync, back up, restore, export to Markdown ("Labels: …") and come in from Google Keep. The sync file (format 4) and backups (version 5) move up only once a note has a label, so older HueNotes installs ask to be updated instead of dropping labels. Changing labels doesn't change "last edited".

### v0.23 — Search text in photos, and type chips
- **Search text in photos** (Settings → Notes, or the **Also search photos** chip that appears while searching): the text in photos, pictures and PDFs (first 3 pages) is read on the phone with Google's ML Kit text recognition, so search finds notes by what their photos say, and the card quotes the match ("In a photo: …"). Off by default; turning it on asks before the one-time model download (via Google Play services) and shows progress. Photos are read in the background (WorkManager) as they are added, synced or shared in; Settings shows how many have been read.
- The text read is stored next to each attachment (DB v9 `ocr` column), **local only**: not synced or backed up, so no format change; each phone reads its own copies, and a sync rewriting a note's attachments keeps what was already read.
- **Type chips** in the list: Photos, Voice, Lists, Files (next to Links). They combine with search, colour and labels, and saved filters remember them.
- Database upgrades now skip columns that already exist, so a repeated or interrupted upgrade can't fail.
- Not yet tried on a device: ML Kit text recognition itself (tests use a fake recogniser).

### v0.24 — Dictation that never just sits there
- Reported on the TECNO: dictation showed "Speak now", the person spoke, and nothing happened.
- Likely cause fixed: after a transcription, HueNotes waited for ML Kit to confirm its stop before releasing Google's speech app; if that confirmation never came, the session could stay open and keep the speech app busy. It is now released after at most 2 s whatever happens.
- Dictation keeps a log (recogniser events and timings, no words). If the speech service gives no sign of life within 8 s, dictation stops and says so; if it ends with nothing heard, a message says so instead of closing silently. Both offer **Share details**.

### v0.25 — Dictation: silent microphone detection, language with region
- A device log showed the speech service starting ("ready"), then only 3 sound-level reports in 19 s and no speech detected: the microphone stopped delivering sound right after start. Dictation now watches the sound-level reports; if they stop for 4 s it starts over once with a fresh recogniser, and if it happens again it stops with a clear message (another app using the microphone, or the speech service stuck: restart the phone) and **Share details**.
- Speech now always asks for a language with its region ("fr" → fr-FR, as transcription does), also when the app's or phone's language has none.
- The dictation log records what the language check returned.

### v0.26 — Dictation removed
- Dictation (the editor's Dictate button and "+ → Dictate") is removed at the tester's request: on the TECNO KL6 the microphone stopped delivering sound to Android's speech recogniser right after it started, even after a phone restart, so it never worked reliably there. Voice typing remains available from the phone's keyboard.
- **Transcription of voice notes stays** (ML Kit first, Android's recogniser as fallback), with its first-use language download, online-recognition setting and error logs; the wording of those settings and messages now speaks of recordings rather than your voice.

### v0.27 — Transcription on phones without a usable recogniser
- A tester's log (Infinix X6525, Android 13) showed ML Kit reporting French as unavailable, and the phone has no on-device Android recogniser; HueNotes then showed "the speech service didn't accept the recording", which was wrong (nothing had been sent) and left no way forward.
- Now it says the phone can't transcribe by itself yet and offers **Update speech app** (opens Google's "Speech Recognition & Synthesis" in the Play Store: an outdated preinstalled version is the usual cause). If the phone has an online recogniser, it also offers **Use online**, which turns on online recognition only after the person agrees; otherwise **Share details**.
- The log now records why ML Kit was unavailable, which recognisers the phone has, and the speech app's version.
