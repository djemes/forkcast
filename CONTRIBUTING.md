# Contributing

Thanks for wanting to help. Forkcast is maintained by one person, so the most useful contributions are small and focused.

- **Bug reports are very welcome.** See [SUPPORT.md](SUPPORT.md) for what to include.
- **Feature ideas** are welcome as issues. Forkcast stays deliberately simple, so many ideas won't be built; please don't take it personally.
- **Pull requests** are accepted at the maintainer's discretion. Open an issue first to ask whether a change is wanted, so you don't spend time on something that can't be merged.
- **Translations:** see [LOCALIZATION.md](LOCALIZATION.md). A new language is a files-only change.
- **Security problems:** report them privately, as [SECURITY.md](SECURITY.md) explains, not in an issue.

## Ground rules for code

Forkcast's promises shape what can be merged:
- No analytics, tracking, ads, accounts or servers. The only network calls go to services the user turned on (food databases, their own AI provider, GitHub for updates).
- Everything works offline; network features degrade gracefully.
- No medical claims. AI results are always shown as estimates.
- Every user-facing text is a string resource, in English and Portuguese (Brazil).
- Database changes come with a Room migration and a migration test.

## Building

You need JDK 21 and the Android SDK (Android Studio works).

```
./gradlew assembleDebug                                   # debug APK
./gradlew assembleDebug testDebugUnitTest lintDebug detekt # what CI runs
```

The code is Kotlin with Jetpack Compose, in the modules `:app`, `:core:model`, `:core:data`, `:core:network` and `:core:domain`. Nutrition math lives in `:core:domain` with unit tests.

## License

Forkcast is licensed under the [GNU GPL, version 3](LICENSE) with an [additional permission](LICENSE-ADDITIONAL-PERMISSION.md). By sending a contribution, you agree that it's licensed the same way.
