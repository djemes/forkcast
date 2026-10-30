<p align="center"><img src="assets/brand/logo-512.png" width="96" alt="Forkcast logo: a fork inside a ring"></p>

<h1 align="center">Forkcast</h1>

<p align="center"><b>A free calorie and nutrition tracker for Android.<br>Your food log stays on your phone.</b></p>

<p align="center">
  <a href="https://github.com/djemes/forkcast/releases/latest">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="assets/readme/download-dark.png">
      <img src="assets/readme/download-light.png" width="300" alt="Download for Android">
    </picture>
  </a>
</p>

<p align="center">
  <img src="assets/readme/badges/android.svg" alt="Android 10 or newer">
  <img src="assets/readme/badges/license.svg" alt="License: GPL-3.0">
  <img src="assets/readme/badges/price.svg" alt="Free, no ads">
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/readme/banner-dark.webp">
    <img src="assets/readme/banner-light.webp" width="100%" alt="Three Forkcast screens: Today with the calorie ring and the day's meals; an AI estimate of a described meal, about 371 kcal and likely 275 to 507, ready to review; and Trends with calories against the budget and the weight trend">
  </picture>
</p>

## Why Forkcast

- **Log a meal your way:** scan a barcode, search, or describe it in a sentence or a photo.
- **A budget that learns:** your daily budget adjusts to the calories you actually burn, worked out from your weight trend.
- **Your data stays on your phone:** no account, no ads, no tracking.
- **Free and open source.**

## What it does

<img src="assets/readme/screens/log-light.webp#gh-light-mode-only" align="right" width="240" alt="The Log sheet: meal chips, then Scan, Photo, Describe and Search, Quick add calories, Recipes, and recent and favorite foods">
<img src="assets/readme/screens/log-dark.webp#gh-dark-mode-only" align="right" width="240" alt="The Log sheet in the dark theme">

### Logging

Scan a barcode, search Open Food Facts (and USDA FoodData Central, with a free key), or read a nutrition label with the camera. Favorites and recent foods are one tap away.

With an AI service you set up, take a photo of a meal or describe it in a sentence: Forkcast shows an estimate with a likely range, which you can review and adjust. AI is optional, uses your own key, and its estimates can be wrong.

<br clear="right">

<img src="assets/readme/screens/budget-light.webp#gh-light-mode-only" align="left" width="240" alt="Today's budget: maintenance, goal deficit, budget, eaten and what's left, then each meal against its share">
<img src="assets/readme/screens/budget-dark.webp#gh-dark-mode-only" align="left" width="240" alt="Today's budget in the dark theme">

### A daily budget

Your budget starts from your goal and the pace you pick. After a few weeks of logging and weighing in, Forkcast compares what you ate with how your weight trend moved and adjusts to what your body actually burns. Unusually active days add part of the exercise back. Tap the ring to see how today's budget was worked out.

<br clear="left">

<img src="assets/readme/screens/trends-light.webp#gh-light-mode-only" align="right" width="240" alt="Trends: average eaten against the average budget, a bar chart of calories per day with the budget as a dashed line, and the weight trend">
<img src="assets/readme/screens/trends-dark.webp#gh-dark-mode-only" align="right" width="240" alt="Trends in the dark theme">

### Trends

Calories against your budget, your weight trend and goal date, macros, nutrients, meal timing, and a weekly check-in.

### Water and Health Connect

One tap adds your usual glass or bottle. With Health Connect, weight, body fat, steps and workouts come in from your other apps and devices, and your food and water go out.

<br clear="right">

<img src="assets/readme/screens/backup-light.webp#gh-light-mode-only" align="left" width="240" alt="Backup and data: backup password, Back up now, Back up and share, and automatic backup to a folder you choose">
<img src="assets/readme/screens/backup-dark.webp#gh-dark-mode-only" align="left" width="240" alt="Backup and data in the dark theme">

### Backups

One encrypted backup file you save wherever you want, and automatic backups to a folder you choose, such as one your cloud drive syncs. Moving to a new phone is a restore away.

English and Portuguese (Brazil).

<br clear="left">

## Privacy at a glance

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/readme/privacy-dark.png">
    <img src="assets/readme/privacy-light.png" width="640" alt="Your phone holds your food log and weights, meal photos, settings and your keys, encrypted. Only what you use connects to it: food databases when you search or scan (Open Food Facts, and USDA with your own key); your AI service, optional, with your own key and asked first (meal photos and descriptions); your backups, wherever you save them, and Android's device backup if it's on; Health Connect, on your phone, shared only with what you allow; and GitHub, to check for updates. No account, no ads, no tracking.">
  </picture>
</p>

Forkcast has no servers and no accounts, and its developer receives none of your data. The [Privacy Policy](PRIVACY.md) lists exactly what goes where.

## Get started

1. **Download** the APK from the [latest release](https://github.com/djemes/forkcast/releases/latest).
2. **Install** it: your phone asks once to allow installs from your browser.
3. **Set up:** a few questions give you your daily budget. Then tap **Log**.

The [install guide](INSTALL.md) walks through every step, including updates and backups.

<details>
<summary><b>Questions</b></summary>

**Why isn't it on Google Play?**
Forkcast is a free project by one person and is published here on GitHub. You install it once; after that it updates itself from Settings › Updates.

**Do I need AI?**
No. Barcode, search, labels, recipes and quick add all work without it. AI is an extra for logging from a photo or a sentence, with a key from a provider you choose (Google's Gemini has a free tier).

**What leaves my phone?**
Only what a feature you use needs: a search or barcode goes to the food databases, a meal photo or description to your AI service, an update check to GitHub. Your weight, goals and other health data never go to an AI service. See the [Privacy Policy](PRIVACY.md).

**How do updates work?**
Settings › Updates checks this page's releases, downloads the new version, checks it, and asks Android to install it. Your data stays.
</details>

---

AI is optional, uses your own key, and its estimates can be wrong. Forkcast is for adults (18+) and is not medical advice; see the [Terms of Use and Disclaimer](TERMS.md).

Help is best effort, through [GitHub](SUPPORT.md).

Free and open-source under the [GNU GPL, version 3](LICENSE), with an [additional permission](LICENSE-ADDITIONAL-PERMISSION.md). Food data from [Open Food Facts](https://openfoodfacts.org) (Open Database License) and [USDA FoodData Central](https://fdc.nal.usda.gov).

Forkcast is not affiliated with or endorsed by Google, Anthropic, OpenAI, the USDA, Open Food Facts, Samsung, or Ko-fi. Product names belong to their owners.
