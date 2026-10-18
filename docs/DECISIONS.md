# Decisions log

One dated line per non-obvious choice. Newest at the bottom.

- 2026-10-18 · **Project folder stays in OneDrive** (owner's choice). To keep OneDrive from syncing or locking build files, Gradle build output, the Gradle project cache, and Kotlin's `.kotlin` folder are redirected to `%LOCALAPPDATA%\forkcast-build\` (configured in Phase 0). GitHub is the real backup.
- 2026-10-18 · **JDK:** use the already-installed Oracle JDK 21 (`C:\Program Files\Java\jdk-21.0.12`) instead of installing Temurin. AGP 9.4 needs JDK ≥ 17. CI uses Temurin 21, so local and CI match on major version.
- 2026-10-18 · **Android Studio** installed from Google's official zip (2026.2.1.8, SHA-256 verified) into `%LOCALAPPDATA%\Programs\android-studio` because the .exe installer's admin prompt didn't go through. No admin rights needed; shortcut added to the Start menu.
- 2026-10-18 · **Git identity set for this repo only** (`Gabe` / `46485696+djemes@users.noreply.github.com`), not globally, because the owner's global Git identity is already used by other projects. Keeps the real email out of commits.
- 2026-10-18 · **One signing key for every build that reaches the phone** (owner approved). Debug builds made on the owner's PC are signed with the release key (read from the project's git-ignored `keys/forkcast-release-credentials.txt`); CI debug builds use the standard debug key and never go on the phone. Reason: Android only installs an update signed with the same key, so mixing keys would force an uninstall (data loss). Consequence: Stage 5 needs only one Android OAuth client (release SHA-1).
- 2026-10-18 · **versionCode = git commit count** for local and CI builds (owner approved), replacing SPEC §10's `GITHUB_RUN_NUMBER`. Keeps version numbers rising across PC builds and Obtainium releases. CI checks out full history.
- 2026-10-18 · **Signing keys live in the project's `keys/` folder** (owner's choice; moved from `C:\Users\gabri\Documents\keys`). Git-ignored (`/keys/` plus the `*.jks` and `*credentials*.txt` patterns) and synced by OneDrive as a backup. Trade-off accepted: key and password sit together in the OneDrive cloud. Vaultwarden stays the master copy because OneDrive also syncs deletions.
- 2026-10-18 · **Room 2.8.5, not Room 3.0.** Room 3 (androidx.room3) is stable since 2026-07 but still shipping deadlock/regression fixes; 2.8 is mature. Moving later is a code change only; the database file format is the same.
- 2026-10-18 · **Settings in DataStore as JSON (kotlinx.serialization), not Proto.** Same type safety and corruption handling without a protobuf code generator in the build. Unknown keys ignored, missing keys default, so older/newer files open.
- 2026-10-18 · **Manrope bundled as a variable TTF** (165 KB, SIL OFL) instead of a downloadable font, so it works offline and on phones without Google Play services.
- 2026-10-18 · **Apricot (#F08A3E) is decoration only in light theme:** it has ~2.2:1 contrast on porcelain, below the 3:1 graphics minimum. It's used only alongside a text change (DESIGN.md §2 rule), and any apricot-family text uses tertiary `#9A4A0E`. All other tokens pass the contrast test.
- 2026-10-18 · **Meal time windows (defaults):** Breakfast 04:00–11:00, Lunch 11:00–15:00, Dinner 17:00–22:00, Snacks = no window (fallback). Stored as minutes after midnight on `meal_slot`.
- 2026-10-18 · **Robolectric tests run with `--add-exports java.base/jdk.internal.access`** (needed for its Android 16 runtime on JDK 21); Room migration tests use the file+driver `MigrationTestHelper` because the name-based one mis-compares Windows paths.
- 2026-10-18 · **No INTERNET permission in Phase 0** (no network code yet); added in Phase 1 with food search.
- 2026-10-18 · **Emulator set up during Phase 0** (owner asked; SETUP Stage 7 moved earlier). WHPX was already usable, so no admin step. Day-to-day testing runs on the emulator; the phone gets builds at phase ends or on request.
- 2026-10-18 · **Splash icon = full adaptive launcher icon** (`@mipmap/ic_launcher`) instead of the white foreground: the white fork was invisible on porcelain because the icon-background color isn't applied to a 108dp foreground.
- 2026-10-18 · **No Obtainium** (owner's choice). Updates: download the APK from GitHub Releases on the phone, or Claude Code installs it over adb on request. SETUP Stage 6 rewritten.
- 2026-10-18 · **USDA key** saved by the owner to `keys/usda-api-key.txt` (git-ignored) for local development. The app reads the key only from its encrypted settings, entered by the owner on the phone; builds never embed it.
- 2026-10-18 · **Open Food Facts User-Agent:** `Forkcast/<version> (https://github.com/djemes/forkcast)`; no personal email hardcoded (SPEC principle 5).
- 2026-10-18 · **Food APIs (verified live):** USDA key in `X-Api-Key` header; personal key 3,600 req/h, DEMO_KEY 10 req/h (so the app warns clearly when on DEMO_KEY). OFF product v2 for barcodes, search-a-licious (`search.openfoodfacts.org/search`) for text search. SPEC §4.4/§7 updated.
- 2026-10-18 · **Remote search results are cached when opened** (not only when logged), so a food you've looked at works offline later and detail navigation is by local id. Local search ranks favorites and recently used first, so cached-but-unused foods don't crowd results.
- 2026-10-18 · **Logging goes to the day on screen**, so you can fill in yesterday from the Today screen.
- 2026-10-18 · **Custom foods take label values per serving**; stored per 100 g. Serving weight in grams is required (needed for the conversion); ml servings are treated as grams.
- 2026-10-18 · **Toolchain versions at setup:** AGP 9.4.0 (Gradle 9.6.0 min), max API 37; SDK platform `android-37.0`, build-tools 37.0.0, platform-tools 37.0.1, cmdline-tools build 15859902.

## Signing certificate fingerprints (not secret)
Release key `forkcast-release.jks`, alias `forkcast`, created 2026-10-18, RSA 4096, valid 10,000 days. Used for every build that reaches the phone (see above), so this is the only SHA-1 the Google OAuth client (SETUP Stage 5) needs.
- SHA-1: `8F:C2:19:E0:BF:5E:20:80:F6:27:6E:BE:69:22:28:42:FA:26:FA:3D`
- SHA-256: `39:A4:DE:39:DE:6A:86:58:18:43:B3:42:29:1F:39:D6:3B:C7:4A:EA:94:7C:74:76:E8:87:93:4B:66:47:16:A0`
