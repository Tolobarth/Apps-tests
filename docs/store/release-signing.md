# Release signing & OAuth consent

The repo's debug key (`app/debug.keystore`) is public. **Never publish with it.**

## Create your own upload key (once, keep it OFF the repo)
```
keytool -genkeypair -v -keystore ~/huenotes-release.jks -alias huenotes \
  -keyalg RSA -keysize 4096 -validity 10000
```
Back it up (password manager + offline copy). Enrol in **Play App Signing** so Google holds the app-signing key and you only keep an upload key.

## Build a signed release
`app/build.gradle.kts` reads these only if present (nothing secret is committed):
```
export HUENOTES_KEYSTORE=~/huenotes-release.jks
export HUENOTES_KEYSTORE_PASSWORD=...
export HUENOTES_KEY_ALIAS=huenotes
export HUENOTES_KEY_PASSWORD=...
gradle bundleRelease   # Play wants an .aab
```

## Google sign-in after switching key
Add the SHA-1 of **each** key you ship (debug, upload, and the Play App Signing key shown in Play Console → App integrity) as Android OAuth clients in Google Cloud, package `dev.huenotes` (see `docs/google-sync-setup.md`).
Without the right SHA-1, sync sign-in fails in that build.

## OAuth consent screen
- User type External; app name, support e-mail, privacy-policy link (from `privacy-policy.md`).
- Scopes: `drive.appdata` (non-sensitive) and e-mail/profile only. No restricted scopes, so no security assessment is expected; verification may still be required for production publishing.
- While in "Testing" mode only listed test users can sign in and tokens expire after 7 days — move to production before launch.
