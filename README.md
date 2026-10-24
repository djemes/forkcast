<p align="center"><img src="assets/brand/logo-512.png" width="112" alt="Forkcast logo"></p>

# Forkcast

A private, offline-first Android calorie and nutrition tracker. Log a meal by scanning a barcode, searching, taking a photo or describing it in a sentence. Forkcast tracks it against a daily budget built from your weight goal and your real maintenance calories.

<p align="center">
  <img src="docs/screenshots/today.png" width="240" alt="Today: calories left on the ring, macros and meals">
  <img src="docs/screenshots/log.png" width="240" alt="Log sheet: Scan, Photo, Describe, Search">
  <img src="docs/screenshots/trends.png" width="240" alt="Trends: calories against the budget and the weight trend">
</p>

## What it does

- **Barcode, search and labels:** USDA FoodData Central and Open Food Facts, plus nutrition-label scanning for products the databases don't know
- **AI logging:** a photo (up to three) or a short description goes to the AI provider you choose (Gemini, Claude or any OpenAI-compatible endpoint), comes back as itemized foods matched to USDA, and you review and adjust it. If one profile is busy or out of quota, the next one in your list takes over
- **Budget with exercise eat-back:** the daily budget follows your goal and pace, learns your real maintenance from your weight trend, and adds part of unusually active days back
- **Health Connect:** weight, body fat, steps and workouts come in; food and water go out
- **Water:** one tap adds your usual glass, can, bottle or tumbler; drinks like water and plain tea count automatically
- **Trends:** calories against the budget, weight trend and goal date, macros, nutrients, meal timing, and a weekly check-in
- **Backups:** JSON export and import, plus automatic backups to your own Google Drive (weekly by default)
- **Privacy, bring your own key:** your data stays on your phone. No account, no server, no analytics. API keys are encrypted on the phone, and Android's unlock prompt is required before one is shown

## Basic use

1. Install the APK from [Releases](../../releases). Later versions arrive through About › Check for updates.
2. Answer the onboarding questions (units, body, activity, goal) to get your daily budget.
3. Optional: add an AI key in Settings › AI photo estimates (a free Gemini key works), connect Health Connect, and connect Google Drive in Settings › Backup & data.
4. Tap **Log** on Today and pick Scan, Photo, Describe or Search. The ring shows what's left for the day.

Android 10 or newer. English and Portuguese (Brazil).

More detail: [Spec](docs/SPEC.md) · [AI providers](docs/AI_PROVIDERS.md) · [Design](docs/DESIGN.md) · [Setup](SETUP.md)

Not medical advice. Estimates, especially from photos, can be well off; weigh food when accuracy matters.
