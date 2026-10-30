# Installing Forkcast

Forkcast isn't on Google Play. You download it from GitHub and install it yourself. It takes about two minutes, and you only do it once: after that, Forkcast updates itself from inside the app.

You need an Android phone with Android 10 or newer. Forkcast is for adults (18+).

## 1. Download the app

1. On your phone, open this guide in your browser (Chrome, Samsung Internet or another one).
2. Go to the **[latest release](https://github.com/djemes/forkcast/releases/latest)**.
3. Under **Assets**, tap the file whose name starts with **Forkcast-** and ends with **.apk** (for example `Forkcast-v1.9.0.apk`). Ignore the other files; they're for people who want to check the download.
4. If the browser says the file might be harmful, tap **Download anyway**. Every Android app file gets this warning when it doesn't come from Google Play.

## 2. Install it

1. When the download finishes, tap **Open** in the browser's message, or open the file from the **Downloads** folder (the **Files** or **My Files** app).
2. The first time, Android says your browser isn't allowed to install apps. Tap **Settings**, turn on **Allow from this source**, then go back.
3. Tap **Install**.
4. When it's done, tap **Open**.

You can turn **Allow from this source** off again afterwards in your phone's settings (search for "Install unknown apps"). Forkcast doesn't need it for the browser; it asks for its own permission the first time it installs an update.

### If Google Play Protect shows a message

Play Protect checks apps that don't come from Google Play. Because Forkcast is new and not on the store, it may say it doesn't recognize the app, or recommend a scan.

- If it offers **Scan app**, tap it. When the scan finds nothing, tap **Install**.
- If it says the app is blocked or unknown, tap **More details** (or the small arrow), then **Install anyway**.

Forkcast is open source: anyone can read the code on this page. It has no ads, no tracking and no account, and it doesn't send your data to its developer. The [Privacy Policy](PRIVACY.md) explains what it connects to and why.

## 3. Set it up

When you open Forkcast for the first time:

1. Read and accept the terms.
2. Answer a few questions (units, height, weight, activity, goal). Forkcast works out your daily calorie budget from them.
3. If you're moving from another phone, choose **Restore a backup** instead (see [Backups](#backups)).

Then tap **Log** on the Today screen to add your first food: scan a barcode, search, or type what you ate.

Everything else is optional and can wait:
- **AI services:** take a photo of a meal or describe it in a sentence, and an AI service estimates it. It needs a free key from Google (or another provider) that you add yourself; the app walks you through it in Settings › AI services. Without one, everything else works.
- **Health Connect:** brings in weight, steps and workouts from other apps and devices.
- **Automatic backup:** see below.

## Updating

Forkcast updates from inside the app. You don't need to come back to this page.

1. Open **Settings › Updates** and tap **Check for updates**.
2. If there's a new version, read what's new and tap **Download and install**.
3. The first time, Android asks whether Forkcast may install apps. Allow it, then tap **Install**.

Your food log, settings and keys stay as they are. Turn on automatic checks on the same screen to get a notice when a new version comes out.

You can also install a newer APK from the [Releases page](https://github.com/djemes/forkcast/releases) the same way you installed the first one; it updates the app and keeps your data.

**Back up before you update**, just in case.

## Backups

Everything you log stays on your phone. If the phone is lost, reset or replaced, a backup is the only way to get your data back.

- In **Settings › Backup & data**, set a **backup password** first. It encrypts every backup, and nobody can recover it if you forget it, so save it in your password manager.
- Tap **Back up now** to save a `.forkcast` file wherever you choose, or **Back up and share** to send it to a cloud drive or another app.
- Or turn on **automatic backup**: pick a folder once, choose the days and the time, and Forkcast saves backups there on its own. A folder that a cloud drive app syncs works well.
- To restore, install Forkcast on the new phone, choose **Restore a backup** during setup (or **Settings › Backup & data › Restore**) and type the backup password.

A backup holds everything, including meal photos and the AI keys you added.

## Uninstalling

Uninstalling Forkcast deletes everything it stored on the phone. Make a backup first if you might want your data later.

<details>
<summary>Checking the download (optional, for technical users)</summary>

Each release has a `SHA256SUMS` file with the APK's SHA-256 checksum. Compare it with the checksum of the file you downloaded, for example with `sha256sum Forkcast-v1.9.0.apk` on Linux or `Get-FileHash Forkcast-v1.9.0.apk` in PowerShell. The in-app updater does this check on its own before it installs anything.

Every release is signed with the same key. Android refuses an update signed with a different key, so an update can only come from the same publisher as the app you installed.
</details>

## Need help?

**Settings › Help** in the app answers the most common questions. If something doesn't work, see [Support](SUPPORT.md).
