# Adding a language to Forkcast

Every word Forkcast shows lives in Android string resources, so a new language is a set of files. Lint fails the build if user-facing text is written directly in Kotlin or XML.

## Steps

1. **Copy the strings.** For each `strings*.xml` file in `app/src/main/res/values/` and `core/data/src/main/res/values/`, create the same file in a new folder named for the language, and translate the text between the tags:
   - Brazilian Portuguese: `values-pt-rBR/` (already done)
   - Latin American Spanish: `values-b+es+419/`
   - Keep every `%1$s`, `%2$d` and `\'` as they are; reorder `%1$s` and `%2$s` freely if the sentence needs it
   - Plurals (`<plurals>`) need the quantities the language uses (Portuguese: `one`, `many`, `other`)
2. **List the language** in `app/src/main/res/xml/locales_config.xml` (for example `<locale android:name="es-419" />`). Settings › Language, and Android 13+'s own app-language setting, pick it up from there.
3. **Ship its resources:** add it to `localeFilters` in `app/build.gradle.kts` (for example `"b+es+419"`).
4. **Run the tests:** `./gradlew testDebugUnitTest lintDebug`. `AppLanguageTest` checks that every listed language has its strings and the other way round; lint reports any string missing a translation.

## Translating beyond the app

- The AI asks for food and meal names in the app's language automatically.
- Food data: Open Food Facts covers many countries; USDA is US-centric.

## Testing

- Settings › Language, or on Android 13+: Settings › Apps › Forkcast › Language
- Debug builds include the pseudo-locales `en-XA` (longer, accented text) and `ar-XB` (right-to-left) to find clipped or hardcoded text: `adb shell cmd locale set-app-locales app.forkcast --locales en-XA`
- Check at 200% font size too
