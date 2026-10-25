# Security

Forkcast keeps everything on your phone. There are no Forkcast servers, no accounts and no analytics. This page explains what the app stores, how it's protected, what it connects to, and how to report a problem.

## Reporting a vulnerability

Please report security problems privately through GitHub: on this repository, open **Security → Report a vulnerability** (GitHub's private vulnerability reporting). Don't open a public issue for a vulnerability. Forkcast is maintained by one person in their spare time, so replies are best effort; you'll get an answer as soon as possible, and a fix ships as a normal release through GitHub.

Supported versions: only the latest release gets fixes.

## What Forkcast stores, and where

Everything lives in the app's private storage, which other apps can't read.

| Data | Where | Protection |
|---|---|---|
| Your food log, foods, recipes, weights, water, goals, profile, AI estimates | App database (Room) | Android's app sandbox and the phone's own encryption |
| Settings | App settings file (DataStore) | Same |
| API keys you add (AI providers, USDA) | One encrypted file | Encrypted with AES-256-GCM (Tink); the key that encrypts it is held by the Android Keystore and never leaves it |
| Meal photos for AI estimates | App storage | Re-encoded before saving, which removes all EXIF data including location. Deleted after 14 days by default (Settings) |
| Safety copy taken before a "Replace" import | App storage | Same as the database |
| Crash report (only after a crash) | App storage, excluded from every backup | Contains app and Android versions, the device model and code locations only; never error messages, your data or keys. Nothing is sent; Today offers to save it, and it's deleted once saved or dismissed |

### Keys

- Saved keys are shown masked. Showing or copying one asks for your fingerprint, face or screen lock, and the unlock lasts about a minute on that screen.
- While a key is visible, the screen can't be screenshotted or recorded and is hidden in Recents.
- A copied key is marked sensitive (hidden in the clipboard preview on Android 13+) and is cleared from the clipboard after a minute, or right away with "Clear clipboard now".
- Keys are never written to logs, exports, backups, crash reports, notifications or intents. Tests seed a fake key and check that it appears in none of those files.
- **Trade-off:** background work (estimating a meal photo while the app is closed) has to read the key without asking you to unlock, so the Keystore key isn't tied to your fingerprint. Anyone who can run code as Forkcast on an unlocked phone could use your keys. Revoke a key at your provider if your phone is lost.

### Backups

- **Backup files** (`.forkcast`) go only where you choose in Android's file picker, or to a folder you pick for automatic backups. They never include API keys. You can protect them with a password: AES-256-GCM, with the key derived from your password by PBKDF2-HMAC-SHA256 (600,000 iterations).
- **Settings export** (to move to another phone) is the only file that can contain your keys, and only when you make one. It's always encrypted with a passphrase you choose, the same way.
- **Android's device backup** (Google One, or a transfer to a new phone) includes your data but never the keys file or per-phone state. Cloud backups also leave out meal photos.

### Files you import

Backup and settings files are treated as untrusted. Forkcast checks the format and version before changing anything, limits the size of every part, refuses paths that point outside its own folders, keeps only photo files with the names it writes itself, and takes a safety copy before a Replace. AI services named in a file you import are listed in the preview, and profiles that use a server you didn't add on this phone arrive turned off.

## What Forkcast connects to

Only services you use or set up, always over HTTPS:

| Service | When | What is sent |
|---|---|---|
| Open Food Facts (`world.openfoodfacts.org`, `search.openfoodfacts.org`) | Searching, scanning a barcode | The search text or barcode |
| USDA FoodData Central (`api.nal.usda.gov`) | Only if you add your own USDA key | The search text, your key |
| Your AI providers: Google Gemini (`generativelanguage.googleapis.com`), Anthropic Claude (`api.anthropic.com`), or an OpenAI-compatible server you enter | Only if you set up AI | The meal photo and description you're estimating, your key |
| GitHub (`api.github.com` and GitHub's download storage) | When you check for updates, and the optional daily check | Nothing about you; the update file is downloaded and its checksum and signing certificate are verified before installing |

- An OpenAI-compatible server you add yourself (for example Ollama on your own network) may use plain `http`; the app warns you before saving it. Every other connection refuses plain `http` in code.
- Standard certificate checks; no custom trust settings.
- Health Connect is on your phone; Forkcast reads and writes only the data types you allow there.
- ML Kit (barcode and label reading) runs on the phone with bundled models. Its built-in usage reporting to Google is switched off in the app's manifest, so no ML Kit data leaves the phone.

## Other notes

- The release APK is signed with one key, the same for every release. The in-app updater refuses an update signed with a different key, with a lower version, or whose checksum doesn't match.
- Release builds are minified with R8. Forkcast's own code doesn't write to the Android log or log network requests.
- Other apps can open Forkcast at a screen (Today, a meal review, Settings › AI, Backup, Updates), the way a notification or widget does. That only navigates; it can't change any data.
- A key you're typing is kept in memory while its field is open, so it survives a screen rotation; it's saved only when you tap Save.
- Static analysis (Android lint, detekt) and the full test suite run on every push.
