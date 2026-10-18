# Progress

Read this first in every session. Update it after every finished feature (keep it short).

- **Current:** Phase 1 in progress (owner approved plan 2026-10-18)
- **Phase status:** 0 ✓ · 1 ☐ · 2 ☐ · 3 ☐ · 4 ☐ · 5 ☐
- **Setup stages done:** 1.1 toolchain (2026-10-18), 1.2 GitHub repo `djemes/forkcast` private (2026-10-18), 1.3 phone paired over wireless debugging (2026-10-18; SM-S938U1, Android 17 / API 37); Stage 2 key + GitHub secrets (2026-10-18; owner's Vaultwarden copy pending); Stage 7 emulator set up early at owner's request (2026-10-18)
- **Phase 0 done:** scaffold + modules, theme/font/contrast test, icon + splash, Today/Log sheet/Settings/Appearance/About shell, Room v1 + migration harness, JSON DataStore, CI + release workflows (green), release key, emulator, signed release pipeline verified with a throwaway v0.0.1 (deleted)
- **Done in current phase:** goal math (core:domain), DB v2 + migration test, onboarding + Profile & goals (§4.1 ✓), Today dashboard (ring, macros, nutrient panel, entries edit/swipe-delete+Undo/multi-select, day swipe), core:network (USDA + OFF clients, GTIN normalization, fixtures from live APIs), §4.3 food search/detail/quick add/custom foods/favorites/recents + Tink SecretStore + Settings › Food databases (§4.3 ✓)
- **Next step:** §4.4 barcode scanning (CameraX + ML Kit bundled; lookup local → USDA ∥ OFF) then label OCR (§4.4 not-found path). Order after that: → saved meals/copy/water/weight → export/import → v0.1.0
- **Setup notes:** USDA key is in `keys/usda-api-key.txt` (Stage 3.1 done by owner). Settings → Food databases now exists: owner pastes the key there on the phone (Claude must not type real keys into apps). Emulator uses DEMO_KEY (10 req/h). Stage 6 = no Obtainium (see SETUP)
- **Waiting on owner:** save `keys\forkcast-release.jks` + `keys\forkcast-release-credentials.txt` (in the project folder) into Vaultwarden
- **Open questions:** —

Environment notes for later sessions:
- JDK 21 at `C:\Program Files\Java\jdk-21.0.12` (JAVA_HOME); SDK at `%LOCALAPPDATA%\Android\Sdk` (ANDROID_HOME). A session started before 2026-10-18 setup may not see these env vars; set `$env:` for the process if needed
- `gh` is at `%LOCALAPPDATA%\Microsoft\WinGet\Packages\GitHub.cli_Microsoft.Winget.Source_8wekyb3d8bbwe\bin\gh.exe` (on user PATH after an app restart)
- Interactive prompts don't work in the Terminal panel tab from here; run CLI sign-ins in the background and relay codes
- Phone: paired once; adb auto-connects by mDNS name `adb-R5CY30VX10T-8VxhzA._adb-tls-connect._tcp` while Wireless debugging is on. If `adb devices` is empty, ask the owner to toggle Wireless debugging; if that fails, `adb mdns services` shows the current IP:port
- Emulator: AVD `forkcast_api37` (Pixel 10 Pro XL profile, Android 17 Google Play image, WHPX). Start: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd forkcast_api37 -no-boot-anim` (run in background). Do development testing there. With the phone also connected, ALWAYS pass `-s emulator-5554` to adb (or set ANDROID_SERIAL) so test builds never land on the phone by accident. The phone is for end-of-phase checks and when the owner asks
- Phone has a Samsung Secure Folder (user 150): use `--user 0` for `pm`/`install` commands
- Project lives in OneDrive (owner's choice); build output is redirected outside it (see DECISIONS.md)
