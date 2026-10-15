# ColorNote `.backup` files — what the app's code shows (4.8.8)

Source: static analysis of the user's own ColorNote 4.8.8 APK with REA/JADX (class names are obfuscated in places). **No real `.backup` file has been decoded yet**, so everything below is read from code, not verified against a file.

- The file is **encrypted**: `AES/CBC/PKCS5Padding`, IV = 16 zero bytes.
- Key, restore path (`data.d.I(password)`): `PBEWITHMD5AND128BITAES-CBC-OPENSSL` (OpenSSL-style MD5 key derivation), salt = the ASCII text `ColorNote Fixed Salt`, 20 iterations, password = the one the user set for the backup.
- Backup path (`data.d.H`): if no password was set, the key is a **random per-install secret** (`BACKUP_SECRET_KEY`) kept by the app. Where that secret is stored for restoring on another phone is not yet understood.
- Inside the encryption there is a custom binary container (`DataInputStream` records, `.idx`/`.dat`/`.doc` parts, `ContentValues`-like rows, JSON for some fields). Its layout is not mapped yet.
- Other constants seen: salt bytes `01 15 03 18 05 24 07 30 09 0a 0b 0c 0d 0e 0f 10` with 15 iterations (`PBEWITHSHA256AND256BITAES-CBC-BC`, used by Android's own backup agent, not by `.backup` files); a fixed password string `ColorNote Password` used for an older "version 1" scheme.

Next step needs a sample: the `.backup` file (or just its first ~64 bytes and size) and whether a backup password was set in ColorNote.
