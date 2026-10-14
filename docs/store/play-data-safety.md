# Google Play — Data safety answers (draft)

Prepared from how the app is built at v0.8. **Re-check each answer in Play Console against the current form before submitting; the developer is responsible for the final declaration.**

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Collect: **No** when sync is off. With the optional Google sync, data is sent to Google Drive on the user's own account; the developer never receives it. Declare conservatively (see below). |
| Data shared with third parties | No |
| Data encrypted in transit | Yes (HTTPS to Google) |
| Way to request data deletion | Yes — uninstall; synced copy deletable from the Google Account |
| Ads / analytics / crash SDKs | None |

## If you choose to declare the sync data
- Personal info → Email address (read to display the connected account; not stored by the developer).
- Files and docs / Photos / Audio → user content synced to the user's own Drive appDataFolder.
- Purpose: App functionality. Not shared, not used for advertising.
(Google's guidance allows data processed only on-device or sent to the user's own account to be treated as not collected by the developer; confirm with the current Play policy text.)

## Permissions to justify
RECORD_AUDIO (audio notes), POST_NOTIFICATIONS (reminders), RECEIVE_BOOT_COMPLETED (re-arm reminders after reboot/update), INTERNET (sync).
No exact-alarm permission is requested.

## Other Play Console items
- Target API: 36 (done in v0.8).
- Content rating questionnaire: notes app, no user-generated public content.
- App access: no login needed for review.
- Privacy-policy URL: host `privacy-policy.md` (e.g. GitHub Pages) and enter the link.
