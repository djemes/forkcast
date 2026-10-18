# Progress

Read this first in every session. Update it after every finished feature (keep it short).

- **Current:** SETUP Stage 1 done. Phase 0 plan awaiting owner OK
- **Phase status:** 0 ☐ · 1 ☐ · 2 ☐ · 3 ☐ · 4 ☐ · 5 ☐
- **Setup stages done:** 1.1 toolchain (2026-10-18), 1.2 GitHub repo `djemes/forkcast` private (2026-10-18), 1.3 phone paired over wireless debugging (2026-10-18; SM-S938U1, Android 17 / API 37)
- **Done in current phase:** —
- **Next step:** Phase 0 plan → owner OK → scaffold
- **Waiting on owner:** OK on the Phase 0 plan
- **Open questions:** —

Environment notes for later sessions:
- JDK 21 at `C:\Program Files\Java\jdk-21.0.12` (JAVA_HOME); SDK at `%LOCALAPPDATA%\Android\Sdk` (ANDROID_HOME). A session started before 2026-10-18 setup may not see these env vars; set `$env:` for the process if needed
- `gh` is at `%LOCALAPPDATA%\Microsoft\WinGet\Packages\GitHub.cli_Microsoft.Winget.Source_8wekyb3d8bbwe\bin\gh.exe` (on user PATH after an app restart)
- Interactive prompts don't work in the Terminal panel tab from here; run CLI sign-ins in the background and relay codes
- Phone: paired once; adb auto-connects by mDNS name `adb-R5CY30VX10T-8VxhzA._adb-tls-connect._tcp` while Wireless debugging is on. If `adb devices` is empty, ask the owner to toggle Wireless debugging; if that fails, `adb mdns services` shows the current IP:port
- Project lives in OneDrive (owner's choice); build output is redirected outside it (see DECISIONS.md)
