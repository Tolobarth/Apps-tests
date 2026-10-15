# Google Play — Data safety answers (draft)

Prepared from how the app is built at v0.8; updated for v0.17 (speech and ML Kit translation). **Re-check each answer in Play Console against the current form before submitting; the developer is responsible for the final declaration.**

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Collect: **No** when sync is off. With the optional Google sync, data is sent to Google Drive on the user's own account; the developer never receives it. Declare conservatively (see below). |
| Data shared with third parties | No |
| Data encrypted in transit | Yes (HTTPS to Google) |
| Way to request data deletion | Yes — uninstall; synced copy deletable from the Google Account |
| Ads / analytics / crash SDKs | No ads, analytics or crash SDKs added by the developer. **ML Kit (translation, language ID) is an SDK that collects data** — see below. |

## ML Kit translation (v0.17) — must be declared
Per Google's ML Kit data disclosure, `com.google.mlkit:translate` collects the configured source and target languages, and
`com.google.mlkit:language-id` the identified languages, plus device information, app information, performance metrics, API
configuration, feature version, event type and error codes — "for diagnostics and usage analytics", encrypted in transit with HTTPS,
not transferred to third parties. ML Kit also uses Firebase Remote Config and Firebase Installations (see Firebase's own Play
disclosure). Likely categories: **App info and performance → Diagnostics / Other app performance data**, and **Device or other IDs**
(Firebase Installations ID); purpose **Analytics** (Google's own diagnostics) and app functionality. Collected only when the person
uses Translate. Check each item against the current form and Google's page:
https://developers.google.com/ml-kit/android-data-disclosure

## ML Kit speech recognition (v0.21)
`com.google.mlkit:genai-speech-recognition` (alpha) transcribes recordings via Google's speech app on the device. Check Google's ML
Kit data disclosure page for its entry (it was not listed when this was written) and declare it like the translation SDK.

## Speech
Dictation uses the phone's speech service; on-device by default, so no audio leaves the phone through HueNotes. With "Online speech
recognition" allowed, audio goes to the phone's speech service (a system app, normally Google's), not to the developer; check whether
Play counts this as data the app shares.

## If you choose to declare the sync data
- Personal info → Email address (read to display the connected account; not stored by the developer).
- Files and docs / Photos / Audio → user content synced to the user's own Drive appDataFolder.
- Purpose: App functionality. Not shared, not used for advertising.
(Google's guidance allows data processed only on-device or sent to the user's own account to be treated as not collected by the developer; confirm with the current Play policy text.)

## Permissions to justify
RECORD_AUDIO (voice notes and dictation), POST_NOTIFICATIONS (reminders), RECEIVE_BOOT_COMPLETED (re-arm reminders after reboot/update), INTERNET (sync, link titles, speech/translation language packs, ML Kit diagnostics).
No exact-alarm permission is requested.

## Other Play Console items
- Target API: 36 (done in v0.8).
- Content rating questionnaire: notes app, no user-generated public content.
- App access: no login needed for review.
- Privacy-policy URL: host `privacy-policy.md` (e.g. GitHub Pages) and enter the link.
