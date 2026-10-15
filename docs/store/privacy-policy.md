# HueNotes — Privacy Policy

_Draft for the developer to review and host at a public URL before publishing. Replace the bracketed items._

**Last updated:** [date] · **Contact:** [your e-mail]

## What HueNotes is
HueNotes is a notes app. Your notes, checklists, photos, drawings and voice recordings are stored **on your device**.

## Data we collect
**None.** The developer has no server and receives nothing from the app. There are no ads, no analytics and no crash-reporting services
added by the developer. The optional features below talk to Google (or a website you saved) directly; the developer never receives that data.

## Link titles
When you save a link without a title, HueNotes fetches that web page once to read its title (Settings → Notes → Fetch link titles, on by default).
The website sees an ordinary request from your phone.

## Transcripts of voice notes
When ML Kit (below) isn't available, recordings are recognised by your phone's speech service (normally Google's), **on-device**
whenever the phone can, so they stay on the phone; the language is downloaded once by that service, from Google, when you first need
it. Only if offline recognition can't handle your language **and** you agree (or switch on Settings → Notes → Online speech
recognition) is a recording sent to the speech service to be recognised there, under that provider's privacy policy.

Transcripts of recordings use Google's **ML Kit speech recognition** where available: it passes the recording to Google's
speech app on your phone, which recognises it on the device; its speech model is downloaded from Google once, after you agree.
Like other ML Kit features it may send Google diagnostic information (device and app information, performance and error data), not
the recording.

## Search text in photos
Off until you turn it on. Then the text in your photos and PDFs is read **on your phone** with Google's ML Kit text recognition
(through Google Play services), only so that search can find it. The recognition model is downloaded once from Google after you
agree. The text read stays on the phone (it is not synced or backed up). Like other ML Kit features it may send Google diagnostic
information, not your photos or their text.

## Translation
Notes are translated **on your phone** with Google's ML Kit. Your note text is not sent anywhere. Each language pack (about 30 MB) is
downloaded from Google the first time you need it, and can be deleted in Settings → Notes → Translation languages. As Google documents
for ML Kit, using translation and language detection sends Google diagnostic and usage information: the languages involved, device and
app information, performance metrics and error codes (encrypted in transit, not shared with third parties). This happens only when you
use Translate.

## Optional Google sync
If (and only if) you switch on "Sync with Google" in Settings:
- You sign in with your Google account and grant the permission to store app data in your Google Drive (the hidden, app-only "appDataFolder"). The app also reads your account e-mail address to show which account is connected.
- Your notes and their attachments are copied to that hidden folder, which only HueNotes can see, and they are downloaded again on your other devices.
- This data goes directly between your device and Google. It is governed by Google's privacy policy. The developer cannot see it.
- Turning sync off stops all transfers. You can delete the synced copy from your Google Account settings (Manage third-party access / Drive hidden app data).

## Permissions
- **Microphone** — only when you record a voice note. Recordings stay in the app.
- **Notifications** — to show reminders you set.
- **Internet** — Google sync, link titles, downloading speech and translation language packs, ML Kit diagnostics during translation, and online speech recognition if you allow it.
- **Photos/Files** — only the items you pick or share into the app.

## Sharing
Notes leave your device only when you share them yourself (share sheet, export, backup) or switch on Google sync. Recordings being transcribed leave it
only if you allow online speech recognition.

## App lock
If you set a password, it is stored on the device as a salted hash. It cannot be recovered; the lock is not a substitute for device encryption.

## Children
HueNotes is not directed to children and collects no personal data.

## Deleting your data
Uninstalling removes the on-device data. Delete the synced copy as described above.

## Changes
Changes to this policy will be posted at this address with a new date.
