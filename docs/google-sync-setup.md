# Turning on Google sync

HueNotes syncs through the user's own Google account. Notes are stored as one file in the hidden **Drive app-data
folder** (scope `https://www.googleapis.com/auth/drive.appdata`): it does not appear in the Drive app, other apps cannot
read it, and nothing goes through a server of ours. Notes are not stored in Gmail.

Google only lets an app sign in once an **OAuth client** exists for its package name and signing key. That registration
belongs to whoever owns the Google Cloud project, so it is a one-time manual step:

1. Open <https://console.cloud.google.com/>, create a project (e.g. "HueNotes").
2. **APIs & Services → Library → Google Drive API → Enable.**
3. **APIs & Services → OAuth consent screen**: user type *External*, fill in the app name and your email. Add the
   scope `.../auth/drive.appdata` (a non-sensitive scope) and, while the app is in *Testing*, add the Gmail addresses
   that may sign in under **Test users**.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `dev.huenotes`
   - SHA-1 certificate fingerprint of the debug key in this repo (`huenotes/app/debug.keystore`):
     `01:8F:E5:12:5E:54:E6:2E:8E:2E:06:36:EC:81:37:1E:C9:FA:61:70`
5. Install the build, open **Settings → Sync → Sync with your Google account**, pick the account, accept the consent.

No client ID goes into the code: Google matches the app by package name and SHA-1.
Until step 4 is done the app shows "Google sign-in isn't set up for this build yet" (Google error 10).

## Before publishing on Google Play

- `debug.keystore` is the public Android debug key. For a Play release, create your own upload key, add its SHA-1 as a
  second Android OAuth client (and the Play App Signing SHA-1 Google shows you), and publish the consent screen.
- Rotating the signing key means users must reinstall (and would lose local notes that were never synced or exported).

## How conflicts are handled

Each note has a stable id and a "last changed" time. Per note, the latest change wins; editing the same note on two
devices before they sync keeps only the later edit. Deleting for good leaves a tombstone (kept 90 days) so other
devices delete it too; a note edited after that deletion is kept. Merge rules are covered by `SyncTest`.

## Attachments

Photos, drawings and voice notes are stored next to the notes file as separate hidden files (`att-<id>`). Photos are
resized to 1280 px and voice notes are about 0.5 MB per minute, so they count lightly against the user's Drive storage.

## Privacy note

The Drive copy is readable by Google like any Drive data; it is not end-to-end encrypted. The in-app password only locks
the screens.
