# Setup runbook (for Claude Code)

Claude Code runs this file. Do every step yourself with the shell and your tools. Stop only at **[OWNER]** steps: tell the owner exactly what to click or type, one step at a time, then wait and verify the result before continuing. Run each stage when the phase that needs it comes up (noted in each heading). Record progress in `docs/PROGRESS.md` ("Setup stage N done, date") so a later session knows what's finished.

Environment: Windows 11, PowerShell (Git Bash once Git is installed), Claude desktop app → Code tab, local session. Owner's phone: Galaxy S25 Ultra.

General rules:
- Check before installing; skip anything already present and working
- Prefer `winget install --id <id> -e --accept-source-agreements --accept-package-agreements`. Installers may show a Windows admin prompt: tell the owner to click **Yes**
- New user environment variables don't reach the already-running session. Set them for the user **and** for the current process (`$env:NAME = ...`), and use full paths where needed. Tell the owner if the Claude app needs a restart for future sessions
- **Secrets are never printed, echoed, logged, or committed.** Generate them inside scripts that write straight to their destination. Never paste a secret into the conversation
- Before downloading a toolchain file, look up its current URL and version on the official page instead of guessing

---

## Stage 1 — Machine, GitHub, phone (before Phase 0)

### 1.1 Toolchain
1. Check: `git --version`, `gh --version`, `java -version`, `$env:ANDROID_HOME`, `adb version`
2. Install what's missing:
   - Git: `Git.Git`
   - GitHub CLI: `GitHub.cli`
   - JDK: Eclipse Temurin (`EclipseAdoptium.Temurin.21.JDK`, or the version the current Android Gradle Plugin requires — `// VERIFY:` on developer.android.com)
   - Android Studio: `Google.AndroidStudio` (the owner will use it for screen previews, the emulator, and Logcat; the build itself uses the SDK below)
3. Android SDK without the Studio wizard:
   - Get the current "Command line tools only" Windows zip URL from developer.android.com/studio
   - Extract to `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\` (so `...\latest\bin\sdkmanager.bat` exists)
   - Accept licenses non-interactively, then install `platform-tools`, the latest stable `platforms;android-<API>`, and matching `build-tools;<version>`
4. Set user environment variables (`[Environment]::SetEnvironmentVariable(..., "User")`) and the current process:
   - `ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk`
   - `JAVA_HOME` = the Temurin install folder
   - `Path` += `%ANDROID_HOME%\platform-tools` and `%JAVA_HOME%\bin` (don't duplicate entries)
5. Verify all of 1.1.1 again

### 1.2 GitHub
1. If `gh auth status` fails: run `gh auth login --hostname github.com --git-protocol https --web`. **[OWNER]** It shows a one-time code and opens the browser; enter the code and approve. If the command can't run interactively from here, ask the owner to run that same line in a PowerShell window, then continue
2. `gh auth setup-git`
3. Git identity, using the GitHub no-reply address (keeps the owner's email out of commits):
   `git config --global user.name "<GitHub display name or login>"` and `git config --global user.email "<id>+<login>@users.noreply.github.com"` (get id/login from `gh api user`)
4. In this folder: `git init -b main` (if needed), make sure `.gitignore` covers Android/Gradle/IDE files, `local.properties`, `*.jks`, `*.keystore`, `keystore.properties`, `.env*`
5. First commit of the kit (docs, assets, CLAUDE.md, this file)
6. `gh repo create forkcast --private --source . --remote origin --push --description "Private offline-first Android calorie and nutrition tracker"`

### 1.3 Phone connection (wireless debugging; no USB driver needed)
1. **[OWNER]** On the phone: Settings → About phone → Software information → tap **Build number** 7 times → enter PIN
2. **[OWNER]** Settings → Developer options → turn on **Wireless debugging** (phone and PC on the same Wi-Fi) → tap it → **Pair device with pairing code**. Read out the IP:port and the 6-digit code
3. `adb pair <ip:port> <code>`, then `adb connect <ip:port shown on the Wireless debugging screen>` (it's a different port from pairing)
4. Verify `adb devices` lists the phone as `device`
5. Fallback if wireless fails: **[OWNER]** turn on **USB debugging** and plug in; accept the prompt on the phone. If adb doesn't see a Samsung phone over USB, install the Samsung Android USB Driver for Windows (from Samsung's developer site)
6. Note for later sessions: the wireless connect port changes after the phone restarts or Wi-Fi drops. If `adb devices` is empty, ask the owner for the current IP:port from the Wireless debugging screen and `adb connect` again

---

## Stage 2 — Release signing and CI secrets (during Phase 0, after the workflows exist)

1. Write a PowerShell script (outside the repo, e.g. in a temp folder) that, without printing secrets:
   - Creates the project's git-ignored `keys\` folder (owner's choice, 2026-10-18)
   - Generates one strong random password (cryptographic RNG, ~32 chars; PKCS12 keystores use the same password for store and key)
   - Runs `keytool -genkeypair -keystore <keys>\forkcast-release.jks -alias forkcast -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Forkcast" -storepass <pw> -keypass <pw>`
   - Writes `<keys>\forkcast-release-credentials.txt` with alias and password
   - Sets GitHub secrets with `--body` (no trailing newline issues): `KEYSTORE_BASE64` (base64 of the .jks), `KEYSTORE_PASSWORD`, `KEY_PASSWORD` (same value), `KEY_ALIAS` = `forkcast`
   - Prints only the SHA-1 and SHA-256 fingerprints (not secret)
2. Run it, then delete the script
3. Make sure a debug keystore exists (`%USERPROFILE%\.android\debug.keystore`, created by the first debug build) and get its SHA-1
4. Save both fingerprint sets in `docs/DECISIONS.md` (fingerprints are not secret; needed for Google sign-in in Stage 5)
5. **[OWNER]** Save `forkcast-release.jks` and `forkcast-release-credentials.txt` from the project's `keys\` folder into Vaultwarden (as attachments to one item). Explain why: losing them means the installed app can never be updated again without uninstalling it
6. Push a test tag only if the owner agrees; otherwise verify `release.yml` on the first real release

---

## Stage 3 — API keys (USDA before Phase 1 food search; AI before Phase 2)

Keys are entered by the owner in the app's Settings on the phone. They never go in the repo, `local.properties`, or the conversation. Development and tests use `DEMO_KEY` for USDA and fixtures/fakes for AI.

1. **[OWNER]** USDA FoodData Central: open the FDC API key signup page (find the current URL on fdc.nal.usda.gov), fill the short form; the key arrives by email
2. **[OWNER]** Gemini free key: aistudio.google.com → **Get API key** → **Create API key** in a **new project** named `forkcast-free`. Do **not** enable billing on that project (it would make all its usage paid)
3. **[OWNER]** In AI Studio, open the project's **Rate limits** page and note the requests/minute and requests/day for Gemini 3.8 Flash and 3.5 Flash-Lite. Record the numbers in `docs/DECISIONS.md` when the owner reads them out
4. Optional **[OWNER]** Claude paid fallback: platform.claude.com → API keys → create key; set a monthly spend limit
5. Once Phase 2 settings exist: **[OWNER]** paste the keys into Forkcast → Settings → AI as: Gemini 3.8 Flash (free) → Gemini 3.5 Flash-Lite (same key) → Claude (optional)

---

## Stage 4 — Health Connect (Phase 3)

1. **[OWNER]** Samsung Health → Settings → Health Connect → allow Samsung Health to **write** Weight, Body fat, Steps, Exercise, Active calories. Confirm the scale's app syncs weight into Samsung Health
2. Forkcast requests its own read/write permissions in-app; walk the owner through the prompts on first use

---

## Stage 5 — Google Drive backup (Phase 4)

1. Install Google Cloud CLI (`Google.CloudSDK`) if missing
2. `gcloud auth login` — **[OWNER]** approve in the browser (same fallback as 1.2.1 if it can't run here)
3. Create a project with a unique ID (e.g. `forkcast-backup-<random>`), set it as current, `gcloud services enable drive.googleapis.com`
4. **[OWNER]** The remaining steps have no CLI. In console.cloud.google.com for that project (Google Auth Platform): app name **Forkcast**, owner's email as support/developer contact, audience **External**, add the owner as a test user, data access scope `https://www.googleapis.com/auth/drive.file`. Then create two **Android** OAuth clients for package `app.forkcast`: one with the release SHA-1, one with the debug SHA-1 (give the owner the exact values from `docs/DECISIONS.md`). Finally set publishing status to **In production** so sign-in doesn't expire weekly (`drive.file` is a non-sensitive scope). If a browser tool is available in this session, offer to click through it with the owner watching instead
5. Record the project ID in `docs/DECISIONS.md`

---

## Stage 6 — Updates on the phone (first release tag, end of Phase 1)

1. Download the latest Obtainium release APK from its official GitHub releases (verify the repo is `ImranR98/Obtainium`) and `adb install` it
2. **[OWNER]** GitHub → Settings → Developer settings → Fine-grained tokens → new token: repository access **only `forkcast`**, permission **Contents: Read-only**, 1-year expiry. Paste it in Obtainium → Settings → GitHub (on the phone; never into this conversation)
3. **[OWNER]** Obtainium → Add app → `https://github.com/<owner>/forkcast`
4. From then on, releases ship by `git tag vX.Y.Z` + `git push --tags`; Obtainium offers the update

---

## Stage 7 — Emulator (when the owner starts using the app daily)

1. **[OWNER]** If needed, enable **Windows Hypervisor Platform** (Windows Features; admin + reboot)
2. Install a current Google APIs x86_64 system image with `sdkmanager`, create an AVD with `avdmanager` (Pixel profile), verify it boots with `emulator -avd <name>`
3. From here on, test on the emulator and install on the phone only when the owner asks (see CLAUDE.md, Phone safety)

---

## Owner-only, no Claude involvement

- **Kitchen-scale test set (before end of Phase 2):** for ~20 normal meals, weigh each component, take the same kind of photo you'd take in the app, and note what it was and the weights. Claude Code will run them through the app after Phase 2 to measure accuracy per model.
