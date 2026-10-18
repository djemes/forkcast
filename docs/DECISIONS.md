# Decisions log

One dated line per non-obvious choice. Newest at the bottom.

- 2026-10-18 · **Project folder stays in OneDrive** (owner's choice). To keep OneDrive from syncing or locking build files, Gradle build output, the Gradle project cache, and Kotlin's `.kotlin` folder are redirected to `%LOCALAPPDATA%\forkcast-build\` (configured in Phase 0). GitHub is the real backup.
- 2026-10-18 · **JDK:** use the already-installed Oracle JDK 21 (`C:\Program Files\Java\jdk-21.0.12`) instead of installing Temurin. AGP 9.4 needs JDK ≥ 17. CI uses Temurin 21, so local and CI match on major version.
- 2026-10-18 · **Android Studio** installed from Google's official zip (2026.2.1.8, SHA-256 verified) into `%LOCALAPPDATA%\Programs\android-studio` because the .exe installer's admin prompt didn't go through. No admin rights needed; shortcut added to the Start menu.
- 2026-10-18 · **Git identity set for this repo only** (`Gabe` / `46485696+djemes@users.noreply.github.com`), not globally, because the owner's global Git identity is already used by other projects. Keeps the real email out of commits.
- 2026-10-18 · **Toolchain versions at setup:** AGP 9.4.0 (Gradle 9.6.0 min), max API 37; SDK platform `android-37.0`, build-tools 37.0.0, platform-tools 37.0.1, cmdline-tools build 15859902.
