# What makes a notes & productivity app top tier (and where HueNotes stands)

Research date: 14 Oct 2026. Evidence labels: **[P]** primary documentation, **[R]** reviews/roundups/blogs (many are affiliate or vendor-written, so indicative only),
**[E]** engineering judgement from our own code. Figures marked "unverified" came from one secondary source.

## 1. What the market says

**Why people leave note apps.** An editorial analysis of 1–3★ reviews of six big apps (Notion, Evernote, Obsidian, Apple Notes, OneNote, Bear) reports:
sync failures/data loss 22%, price hikes on existing users 17%, offline mode failing 13%, search missing written content 11%,
slowness with 1,000+ notes 10%, export lock-in 8%, cross-device formatting drift 7%. Its methodology and sample sizes are not documented, and the
headline line is "nobody complains about the feature list". [R: unstar.app] **Reading:** trust and basics beat feature count.

**Google Keep (the main thing HueNotes is compared with)** is praised for fast capture, checklists, labels, colours, pins, reminders, and criticised for:
no real organisation beyond labels, no end-to-end encryption, headers that can't collapse or nest, a checkbox that turns the *whole* note into a list, and formatted notes that can't be
exported with their formatting. Google is reportedly testing inline to-do lines inside normal notes (Aug 2026) and showed voice-driven "Keep Live" at I/O 2026, reportedly tied to paid Google AI plans. [R]

**ColorNote** reviews (older, small samples) complain about ads in the free version, weak sync/cloud access, and no images or files. [R: techjockey]

**Privacy-first apps** (Standard Notes — now part of Proton, Notesnook, Joplin and Obsidian Sync with E2EE on) win a loyal niche; the trade-off is that a forgotten password
means unrecoverable notes. Notesnook's free tier includes 100 note versions; Standard Notes' free tier is plain text only. [R]

**Pricing mood.** Evernote's 2026 free plan is reported as 50 notes / 1 device; Notability brought back a one-time purchase in July 2026 citing "subscription fatigue"
(a figure of 41% of consumers is quoted, unverified); Goodnotes users complained when lifetime licences were overtaken by a subscription. [R]
Cloud sync and AI are the things people still accept paying for.

**AI in notes (2026).** Transcription, summaries, semantic-style search and voice capture are the common features. Most are cloud and paid. [R]
On Android, Google's ML Kit GenAI offers on-device summarisation, proofreading, rewriting, image description (Beta) and speech recognition (Alpha), but
the first four only run on a listed set of recent flagship phones, only while the app is in the foreground, and with quotas. Basic speech recognition works on most devices with Android 12+. [P]

## 2. Platform facts that decide the roadmap

* **Play Store deadline.** From 31 Aug 2026 new apps and updates must target Android 16 (API 36); an extension to 1 Nov 2026 can be requested; existing apps must target API 35
  to stay visible to new users on newer devices. HueNotes targets API 34 today. [P: developer.android.com/google/play/requirements/target-sdk]
* **Default notes app.** `RoleManager.ROLE_NOTES` + an activity answering `ACTION_CREATE_NOTE` (with `showWhenLocked`/`turnScreenOn`) makes the app launchable from the **lock screen**
  (full screen) and as a **floating window over other apps**; lock-screen launches must protect privacy and normally start a *new* note. Optional content-capture and stylus hooks exist. [P]
* **Document scanning and OCR** are available on-device through ML Kit (scanner ≈ 300 KB, works offline after first use; OCR is a separate text-recognition step). [P/R]
* **Quick capture** apps lean on a Quick Settings tile that opens a new note without unlocking, plus pinned notification notes. [R: Noty, NotiNotes]
* **Switching apps.** Keep exports via Takeout (JSON/HTML). Obsidian's importer notes that Keep checklists import flat because Keep exports no indentation, and reminders are not imported. [R]

## 3. HueNotes today

Colour notes · text/checklists with sub-items · pins · archive · trash (30 days) · search · sort/grid/list · one-time reminders · calendar day view · 2 widgets ·
password screen-lock · photos, drawings, voice notes · share/save attachments · links section · Google Drive sync (needs one-time setup) · JSON backup + daily snapshots ·
5 languages · dark mode · no ads.

## 4. Recommended features, ranked

Effort: S ≈ a day, M ≈ several days, L ≈ a week or more.

### A. Trust: never lose a note (the biggest churn driver)
| # | Feature | Why | Effort |
|---|---|---|---|
| 1 | **Note version history** (restore earlier versions; keep N per note) | Directly answers "sync/data loss"; Notesnook's free tier advertises 100 versions [R] | M |
| 2 | **Conflict copies** instead of silent last-writer-wins | Our sync keeps only the later edit of a note edited on two devices [E] | M |
| 3 | **Sync status + background sync** (WorkManager), last-synced time, readable errors | Today it syncs on app open only [E] | M |
| 4 | **Full backup as ZIP incl. attachments**, plus scheduled auto-export to a folder the user picks | JSON backup and snapshots currently omit attachments [E] | M |
| 5 | **Import from Google Keep (Takeout), ColorNote, Markdown/text folders; export to Markdown** | Export lock-in is 8% of complaints [R]; it is also the main way to win users from Keep/ColorNote | M |

### B. Capture in two seconds
| # | Feature | Why | Effort |
|---|---|---|---|
| 6 | **Default notes app role** (lock-screen quick note + floating window) | Official Android path to the fastest capture [P] | M |
| 7 | **Quick Settings tile + long-press app shortcuts** (new note / list / voice / scan) | The pattern quick-capture apps use [R] | S |
| 8 | **Interactive checklist widget** (tick items on the home screen), quick-add row, pin-one-note widget | Widgets are where list apps live day to day [E] | M |
| 9 | **Receive more from other apps**: images, PDFs, files, not just text | We only accept shared text [E] | S |
| 10 | **Voice dictation / transcription** of voice notes | Basic ML Kit speech works on most Android 12+ devices; Alpha [P] | M |

### C. Find anything
| # | Feature | Why | Effort |
|---|---|---|---|
| 11 | **Labels/tags + saved filters** (colour stays) | Colour alone doesn't scale past a few dozen notes; every rival has labels/folders [R] | M |
| 12 | **Search inside photos (OCR) and scans** + type filters (images, audio, lists, links) | "Search missing written content" is 11% [R]; Keep has "grab image text" [R] | M |
| 13 | **Document scanner** (ML Kit) → image + searchable text | Offline, tiny, no camera permission needed by the app [P] | S |
| 14 | **Scale test and speed**: list/query off the main thread, full-text index, 10k-note test | 10% of complaints are slowness at 1,000+ notes; our list loads everything on the main thread [R/E] | M |

### D. Real productivity
| # | Feature | Why | Effort |
|---|---|---|---|
| 15 | **Recurring reminders**, snooze, several per note, "Done" button in the notification | Basics in TickTick/Keep; we only have one-time [R/E] | M |
| 16 | **Natural-language reminders** ("tomorrow 9am", "every Monday") | TickTick-style quick entry [R] | M |
| 17 | **List power-ups**: drag to reorder, move ticked items to the bottom, clear completed, per-item due date | Reordering is missing today [E] | M |
| 18 | **Templates** (shopping, packing, trip, meeting) | Cheap, loved, and shows off sub-items | S |
| 19 | **Hybrid notes: checklist lines inside text** | Keep is only now testing it (Aug 2026, reported) [R] — a chance to be first | L |
| 20 | **Rich text / Markdown** incl. collapsible headings | Keep still can't collapse headings [R] | L |

### E. Privacy and security (HueNotes's natural identity)
| # | Feature | Why | Effort |
|---|---|---|---|
| 21 | **Biometric unlock** (fingerprint/face) | Table stakes; today only a password [E] | S |
| 22 | **End-to-end encrypted sync + encrypted local database**, with a recovery key | Standard Notes/Notesnook/Joplin win on this; our password only locks the screens and the Drive copy is readable by Google [E/R] | L |
| 23 | **Per-note lock** and hide-from-widgets/notifications | Common in locked-notes apps [R] | M |

### F. Smart, but private
| # | Feature | Why | Effort |
|---|---|---|---|
| 24 | **On-device "smart" tools**: summarise, proofread, rewrite, describe image | Free and private, unlike paid cloud AI; **only on listed flagship phones, foreground, Beta** — must degrade gracefully [P] | M |
| 25 | **Smart conversion**: text → checklist, spot dates → reminder, suggest labels | Matches Keep's "Help me create a list" without the cloud [R] | M |

### G. Store readiness (must-do before publishing)
| # | Item | Why | Effort |
|---|---|---|---|
| 26 | **Target API 36** (compileSdk/targetSdk), test edge-to-edge and predictive back | Play requirement since 31 Aug 2026 (extension to 1 Nov) [P] | M |
| 27 | **Remove the unused `SCHEDULE_EXACT_ALARM` permission** (we use inexact alarms) | Avoids avoidable review friction; verify Play's current policy [E] | S |
| 28 | **Privacy policy + Play data-safety answers**, own release key, OAuth consent screen | We now use INTERNET, Drive and the microphone [E] | S |
| 29 | **Accessibility pass** (TalkBack, large fonts, contrast, 48 dp targets) and **large-screen/foldable layouts** | Quality bar for ranking and for floating-window notes [P/E] | M |
| 30 | **Material You dynamic colour** option | Feels native on Android 12+ [E] | S |

### H. Business model that fits the market
Keep the core free and ad-free. Because sync uses the user's own Drive, HueNotes has almost no server cost, so **"sync free forever"** is credible; consider one optional **one-time**
"Supporter/Pro" purchase (themes, advanced widgets, extra versions) rather than a subscription. The evidence for subscription fatigue is directional, not rigorous. [R]

## 5. Suggested order
1. **Trust and store basics:** 26, 27, 28, then 1, 2, 3, 4.
2. **Win switchers:** 5 (Keep/ColorNote import), 9, 21.
3. **Speed:** 7, 6, 8, 14.
4. **Findability:** 11, 13 → 12.
5. **Depth:** 15, 17, 18 → 16.
6. **Identity:** 22, 24, 25.

## Sources
* Roundups: [dupple](https://dupple.com/learn/best-note-taking-apps) · [Zapier](https://zapier.com/blog/best-note-taking-apps/) · [DEV Community](https://dev.to/justin3go/10-best-note-taking-apps-in-2026-free-private-cross-platform-picks-5fk0) · [Zapier AI notes](https://zapier.com/blog/best-ai-notes-apps/)
* Keep: [Keep alternatives (Android Authority)](https://www.androidauthority.com/google-keep-alternatives-android-apps-3705410/) · [Keep missing basics (Yahoo/Android Police)](https://tech.yahoo.com/general/articles/google-keep-missing-6-basic-163023640.html) · [Keep inline to-do teardown](https://www.androidauthority.com/google-keep-to-do-lists-notes-apk-teardown-3703191/) · [Keep Live (Softonic)](https://en.softonic.com/articles/google-gemini-live-comes-to-gmail-and-keep-voice-chat-starts-in-summer-2026)
* Complaints: [unstar.app analysis](https://unstar.app/blog/notion-evernote-obsidian-apple-notes-onenote-note-taking-apps-ranked-2026) · [ColorNote reviews (techjockey)](https://www.techjockey.com/us/reviews/colornote)
* Privacy apps: [Notesnook vs Standard Notes](https://notesnook.com/compare/standard-notes) · [Encrypted note apps compared](https://stateofsurveillance.org/guides/basic/encrypted-note-apps-compared/)
* Pricing: [Notability one-time plan (BusinessWire)](https://www.businesswire.com/news/home/20260714768240/en/) · [subscription fatigue (stora.sh)](https://stora.sh/blog/2026-04-06-subscription-fatigue-one-time-purchases-comeback)
* Android: [Play target API requirement](https://developer.android.com/google/play/requirements/target-sdk) · [Notes role and stylus](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/create-a-note-taking-app) · [ML Kit GenAI](https://developers.google.com/ml-kit/genai) · [ML Kit Document Scanner](https://developers.google.com/ml-kit/vision/doc-scanner/android)
* Switching and quick capture: [Obsidian Keep importer](https://help.obsidian.md/import/google-keep) · [Noty (F-Droid)](https://f-droid.org/packages/com.noty.app/) · [todo apps compared (unstar.app)](https://unstar.app/blog/todoist-ticktick-things-3-microsoft-todo-apple-reminders-todo-apps-ranked-2026)
