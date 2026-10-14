# Clean-room spec: colour-coded notes app

Source of this spec: REA's static view of ColorNote 4.8.8 (component/class
names and manifest structure, see `reports/com.socialnmobile.dictapps.notepad.color.note/`)
plus ordinary knowledge of the product category. No decompiled method bodies,
layouts, strings, icons or branding are copied. The implementation in
`huenotes/` is an original work with its own name.

## Feature inventory (what the original's structure indicates)

| Area | Evidence in original | In scope for HueNotes v0 |
|------|---------------------|--------------------------|
| Note list / main screen | `activity.Main`, `NoteList` | yes |
| Note editor (text + checklist) | `activity.NoteEditor`, `LinedEditText` | yes |
| Per-note colour | `ColorPreference`, `NotesOptions` | yes |
| Archive | `activity.Archive` | yes |
| Search | `activity.Search` | yes |
| Reminders | `ReminderReceiver`, `ReminderSettings`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` | yes (re-armed on boot) |
| Share / export | `SendAsAttachment`, `SendToClipboard` | share as text |
| Password lock | `PasswordSetting` | later |
| Calendar / Today view | `ScreenCalendar`, `MonthView`, `Today` | later |
| Home-screen widgets | `NoteWidget`, `TodayWidget2x2` | later |
| Cloud sync, accounts, ads, billing | `sync.*`, `oauth.*`, billing/AD_ID perms | out of scope |

## Data model (own design)

`notes(id, title, body, kind[text|checklist], color, archived, reminder_at, created_at, modified_at)`

Checklist bodies: one item per line, prefix `[x] ` done / `[ ] ` open.

## Behaviour

- List shows non-archived notes, newest-modified first; colour shown as a stripe.
- Tap opens editor; save-on-exit; empty notes are discarded.
- Menu: archive/unarchive, delete, set colour, set reminder, share.
- Reminder fires a notification; reboot re-arms future reminders.
- Search filters title+body, case-insensitive.
