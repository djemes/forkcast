# Forkcast — Product & Technical Spec

Version 1.0 · 2026-10-16 · Owner: Gabe Mompean

Forkcast is a native Android app for losing weight by tracking what you eat. Logging has to be fast: barcode scanning and photo-plus-description are the primary paths, manual search and entry are the fallback. The app turns a weight goal into a daily calorie and nutrient budget, learns your real maintenance calories from your data, and nudges you during the day before you overshoot.

Design inspiration: MyNetDiary (daily dashboard, meal sections, nutrient detail, analysis). Don't copy its UI; match its usefulness.

---

## 1. Principles

1. **Logging in under 10 seconds** for scanned or repeated foods. Every extra tap is a reason to stop logging.
2. **Honest numbers.** Every estimate shows its source (label, USDA, AI) and AI estimates show a range. Never present a guess as a measurement.
3. **Your data, your device.** Offline-first, local database, full JSON export/import, backup to the user's own Google Drive. No accounts, no server of ours, no analytics.
4. **Bring your own AI.** One provider interface; Claude, Gemini, and any OpenAI-compatible endpoint (OpenAI, OpenRouter, a self-hosted Ollama) are interchangeable from Settings.
5. **Anyone can install it.** No personal data hardcoded. One profile per phone. Users supply their own API keys.
6. **Supportive, not judgmental.** Copy and notifications are neutral and factual. Red is for errors, not for eating.

## 2. Users and scope

- Primary: the owner, on a Galaxy S25 Ultra (Android 15/16), with a smart scale syncing to Samsung Health → Health Connect.
- Secondary: anyone who sideloads the APK. One profile per device.
- Out of scope for v1: iOS, web app, multi-profile, social features, meal-plan generation, Play Store release (keep it possible — no policy-violating APIs).

## 3. Build phases

Finish each phase completely (tests green, runs on device, acceptance criteria checked) before starting the next. Each phase should produce an installable, useful app.

| Phase | Goal | Contents |
|---|---|---|
| 0 | Skeleton | Project scaffold, theme, fonts, icon, navigation shell, Room + DataStore wiring, CI (build + test + lint), release workflow, `DECISIONS.md` |
| 1 | Usable tracker (MVP) | Onboarding + goal math, Today dashboard, food search (USDA + Open Food Facts), barcode scan, nutrition-label OCR, manual/quick add, custom foods, favorites/recents, saved meals, copy meals, water, manual weight, JSON export/import |
| 2 | AI photo logging + nudges | Provider abstraction (Claude, Gemini, OpenAI-compatible), settings + key storage + model picker + test button, photo capture → estimate → review flow, USDA matching, usage/cost tracking, forecast logic + forecast arc on the ring (§4.2), notification system (budget thresholds, evening outlook, reminders, estimate-ready) |
| 3 | Health Connect + adaptive budget | Read weight/body fat/steps/active calories/exercise; write nutrition + hydration; weight trend; adaptive TDEE; exercise calorie handling |
| 4 | Backup + insights | Google Drive auto-backup + restore, Android Auto Backup rules, Trends screen (calories, macros, weight trend, nutrient report), recipes builder |
| 5 | Polish | Home-screen widget (Glance), app shortcuts, Quick Settings tile for scan, Portuguese (pt-BR) translation, performance pass, accessibility audit |

---

## 4. Features and acceptance criteria

### 4.1 Onboarding and profile (Phase 1)

Collect, one question per screen with a progress indicator and sensible defaults:
- Units (lb/kg, ft-in/cm, kcal/kJ) — default from device locale
- Sex used for the BMR equation (male/female), date of birth, height, current weight
- Activity level (sedentary, light, moderate, very active, extra active) with plain-language descriptions
- Goal: lose / maintain / gain; target weight; then either a target date or a weekly rate (the other is computed live)
- Show the resulting daily budget and macro targets, with the math expandable ("How we calculated this")
- Short note that the app gives estimates, not medical advice

Acceptance:
- [x] Every field editable later in Settings → Profile & Goals, and changes recompute targets immediately
- [x] Target date and weekly rate stay in sync as the user edits either
- [x] Warnings appear (not blocks) per §5.4 guardrails; the user can still proceed

### 4.2 Today dashboard (Phase 1)

Top to bottom:
1. Date header with ‹ › day navigation and a calendar picker; swipe left/right changes day
2. **Budget ring**: calories remaining (large number), eaten / budget / exercise beneath; ring fills as you eat; color states per DESIGN.md. After 7+ logged days it also draws a **forecast arc**: expected additional intake for the rest of today = median kcal the user logged after the current time of day over the last 14 logged days. Status line: on course / tight / likely over by ~N / N over (wording in DESIGN.md §5). The same forecast powers the Evening outlook notification
3. **Macro row**: protein, carbs, fat — progress bars with g eaten / g target; tap → full nutrient panel for the day
4. **Meal sections** (Breakfast, Lunch, Dinner, Snacks — names and count configurable): each shows kcal subtotal and entries (name, amount, kcal, source badge). Entry tap → edit. Swipe → delete with Undo snackbar. Long-press → multi-select (copy to another day/meal, save as meal, delete)
5. **Water** card with +1 glass / custom amount
6. **Weight** card: today's weight (from Health Connect or manual), trend arrow, tap to log
7. **Activity** card (Phase 3): steps, active calories, exercise sessions from Health Connect
8. **FAB "Log"** → bottom sheet: Scan barcode · Photo meal · Search · Quick add calories · Saved meals · Recent. Default meal = the one that matches the current time window

Acceptance:
- [ ] Today screen opens in under 500 ms cold-cached on the S25 Ultra
- [x] All totals update immediately after any add/edit/delete (Room Flow)
- [ ] Pending AI estimates appear in their meal with a progress indicator (see §4.6)

### 4.3 Food search and manual entry (Phase 1)

- Single search box with results merged from: local foods (custom, recents, favorites) first, then USDA FoodData Central (Foundation, SR Legacy, Survey/FNDDS, Branded), then Open Food Facts
- Debounced (300 ms), cancellable; local results show instantly and work offline
- Result row: name, brand, serving description, kcal per serving, source badge
- Food detail: serving selector (food's own servings + g + oz + "custom"), quantity stepper with decimal support, live nutrient preview, Add button
- **Quick add**: just calories (and optionally protein/carbs/fat) with a label
- **Custom food**: name, brand, serving size, full nutrient panel (all fields in §6.3 optional except kcal), optional barcode
- Favorites (star) and Recents (last 50 distinct foods, with last-used quantity)

Acceptance:
- [x] Search works offline against local foods
- [x] Adding a recent food takes ≤ 3 taps from the Today screen (Log → recent row: 2 taps)
- [x] A USDA or OFF food added once is cached locally with its full nutrient data (cached when opened, refreshed from the USDA detail record)

### 4.4 Barcode scanning (Phase 1)

- CameraX preview + ML Kit Barcode Scanning (bundled model, works offline); formats: EAN-13, EAN-8, UPC-A, UPC-E
- Continuous scan; haptic + sound on detection; torch toggle; manual barcode entry fallback
- Lookup order: local cache → **in parallel** USDA Branded (by GTIN/UPC) and Open Food Facts (`/api/v2/product/{barcode}`); prefer the record with more complete nutrients, prefer USDA for US-market products on ties; show source
- Normalize GTINs (UPC-E → UPC-A, zero-pad to GTIN-14 for comparison). Verified 2026-10-18: FDC returns `gtinUpc` as a 14-digit, zero-padded string (e.g. `00016000275287`), and a `/foods/search` query for the digits finds it; still compare normalized GTIN-14s because older records vary
- **Not found** → offer "Scan the nutrition label": photo → ML Kit Text Recognition → parse serving size, calories, fat, sat fat, trans fat, cholesterol, sodium, carbs, fiber, sugars, added sugars, protein → editable form → save as custom food linked to the barcode, so the next scan resolves locally
- Optional (Phase 2): if label OCR is poor, send the label photo to the configured AI provider for extraction

Acceptance:
- [ ] Scan → food detail in < 1.5 s on a cached item, < 4 s on a network lookup (good connection)
- [x] A barcode that was resolved via label OCR resolves locally afterward, offline
- [x] Open Food Facts requests send a descriptive User-Agent (`Forkcast/<version> (<contact>)`) per their API terms

### 4.5 Saved meals, recipes, copying (Phase 1; recipes Phase 4)

- **Saved meal**: a named group of entries ("Usual breakfast"); log with one tap, then adjust
- **Copy**: copy a meal or a whole day to another date/meal; "Same as yesterday" button on empty meal sections
- **Recipe** (Phase 4): ingredients + number of servings (or final cooked weight) → per-serving/per-gram nutrients; becomes a loggable food

### 4.6 AI photo meal logging (Phase 2)

Flow:
1. User taps **Photo meal** → camera with a framing hint ("Include the whole plate; a fork or hand helps with scale"). Allow 1–3 photos (e.g., top + side) or pick from gallery
2. Description field (optional but encouraged) with examples in the hint text: "chicken thigh, ~1.5 cups white rice, cooked in olive oil". Keyboard voice input works
3. Meal is pre-selected by time; user taps **Estimate**
4. A **pending entry** appears immediately in that meal section ("Estimating…"). The user can leave the screen
5. An **expedited WorkManager job** sends the request (images downscaled per AI_PROVIDERS.md, EXIF stripped) through the profile chain (§4.7), falling through to the next profile on rate limit or outage. Typical cloud latency is seconds; if offline it waits for network and retries with backoff; if every profile is out of quota it stays pending and retries after the earliest quota reset
6. Result: the model returns components with estimated grams and ranges (see AI_PROVIDERS.md). The app matches each component to USDA (§4.6.1) and computes nutrients
7. **Auto-accept** (setting, default ON): the estimate is logged immediately with an "AI · review" badge so the budget and notifications stay accurate. Tapping opens the **Review screen**
8. **Review screen**: photo thumbnail, meal name, list of components each with: name, matched food (tap to change match), grams with stepper and slider bounded by the model's low/high range ×1.5, kcal; total with range (sum of lows – sum of highs); model's assumptions as small text; "Correct it" field to send a follow-up ("it was fried in butter", "only ate half") that re-runs the estimate with the previous result as context; Save / Delete
9. If the model returns a clarifying question, show it in the review screen and keep the entry pending until answered or the user accepts as-is
10. Notification "Lunch estimated: ~640 kcal (520–760). Tap to review." if the user left the screen (§4.8)

#### 4.6.1 Matching components to USDA
- For each component, search FDC using the model's `usda_query`, restricted to Foundation, SR Legacy, Survey (FNDDS) data types; score candidates by token overlap with the query and preparation, prefer FNDDS for mixed dishes, Foundation/SR for single ingredients
- If the best match score ≥ threshold and its kcal/g is within ±40% of the model's own per-gram estimate → use USDA values × grams
- Otherwise use the model's per-item nutrients and mark the component "AI values"
- Store both the model's numbers and the matched FDC id on the component, so the user can switch
- Cache query → fdcId matches locally to cut latency and API calls over time

Acceptance:
- [ ] Pending entry appears instantly; estimate lands without the app in the foreground
- [ ] Every AI-logged item shows its source, range, and can be edited to exact grams
- [ ] Switching provider in Settings changes the next estimate's provider with no other change
- [ ] With the first profile returning 429, the estimate completes on the next profile and the review screen names the profile used
- [ ] No AI request body contains weight, goal, age, sex, or other body data (unit test inspects the serialized requests)
- [ ] Network failure, invalid key, rate limit, and malformed JSON each produce a specific, actionable message and leave the entry pending (retryable), never a crash
- [ ] Photos are stored compressed in app-private storage; a setting deletes photos older than N days (default: keep 90 days)

### 4.7 AI provider settings (Phase 2)

Settings → AI is an ordered list of **AI profiles** with automatic fallback (full rules in AI_PROVIDERS.md §2.5–2.6):
- Each profile: provider (**Claude (Anthropic)**, **Gemini (Google)**, **OpenAI-compatible** — custom base URL for OpenAI, OpenRouter, Ollama, LM Studio, vLLM), API key, model, display name, and for Gemini a tier label (Free / Paid / Not sure). AI can also be turned **Off** entirely
- "Use in this order" list: drag to reorder, toggle each profile, per-profile Test button and "Used today: N" counter. Estimates fall through to the next profile on rate limit, quota, server error, or timeout
- Owner's default chain: Gemini 3.8 Flash (free) → Gemini 3.5 Flash-Lite (same free key) → Claude (paid, optional)
- API key field per profile (masked; stored encrypted; "Paste" button; link to where to get a key)
- Model picker: fetched from the provider's list-models endpoint, filtered to vision-capable models where the API says so, with the recommended default pre-selected (defaults live in one config file — see AI_PROVIDERS.md). Free-text override for custom IDs
- **Test connection**: sends a tiny text-only request; reports success, latency, and the model ID
- "Personal context" free text sent with every estimate (e.g., "I usually cook with olive oil; my dinner plates are 27 cm"), with the note "Sent with every photo. Don't include personal details." The app itself never sends weight, goals, age, sex, or other body data to any AI provider
- Usage: estimates this month, input/output tokens, estimated cost (user-editable price per million tokens per model, pre-filled from the config file)
- Privacy note on any profile labeled Gemini free (or Not sure), shown before the key is saved: "On Gemini's free tier, Google may use your photos and descriptions to improve its products, and people may review them. Don't include faces, documents, or personal details. Users in the EEA, UK, or Switzerland need a paid key." The camera screen repeats a one-line version when the first profile in the chain is free tier

### 4.8 Notifications (Phase 2)

Separate Android notification channels so the user can tune each one in system settings too. In-app toggles for each, plus quiet hours (default 22:00–07:00).

| Notification | Trigger | Default |
|---|---|---|
| Budget threshold | After a log changes the day's total and crosses a threshold: 80%, 100%, 110% of budget (editable) | On (80%, 100%) |
| Evening outlook | At a user time (default 16:30): "1,050 kcal left. Your usual dinner is ~750." Uses the median kcal logged after that time over the last 14 days | Off |
| Protein check | At a user time: protein remaining if > 25% of target | Off |
| Meal reminder | At configurable times if that meal is still empty | On (lunch 13:30, dinner 20:00) |
| Weigh-in reminder | Morning, if no weight today (skipped when Health Connect delivers one) | On, 07:30 |
| Estimate ready | When an AI estimate finishes and the app is not in the foreground | On |
| Backup failed | When a scheduled backup fails twice in a row | On |

Rules:
- Each threshold fires at most once per day; editing entries down and back up doesn't re-fire it
- Copy is factual and kind. No "bad", "cheat", "fail", no guilt. Examples in DESIGN.md
- Request `POST_NOTIFICATIONS` in context (first time the user enables a notification), never on first launch
- Use exact alarms only if needed for reminders; prefer inexact WorkManager windows (±15 min is fine)

### 4.9 Weight, water, body data (Phase 1 manual; Phase 3 Health Connect)

- Weight log with date/time, optional body fat %; chart with raw points + trend line (§5.5)
- Water: glasses or ml/oz; daily target (default 2.5 L men / 2.0 L women, editable)
- Health Connect (Phase 3): read Weight, BodyFat, Height, Steps, ActiveCaloriesBurned, TotalCaloriesBurned, ExerciseSession; write Nutrition (per log entry, with meal type) and Hydration. Request permissions in context with a rationale screen; implement the required privacy-policy activity/intent for Health Connect
- Dedupe: a Health Connect weight within ±10 minutes of a manual one is the same reading; prefer the scale's
- Respect deletions: if the user deletes an entry in Forkcast, delete the Nutrition record we wrote

### 4.10 Trends and reports (Phase 4)

- Calories: daily bars vs budget line, weekly average; tap a bar → that day
- Weight: raw + trend, projected goal date from the current trend rate
- Macros: average split vs targets over 7/30/90 days
- Nutrient report: average intake of every tracked nutrient vs target over a period, sorted by most under/over
- Logging streak and "days logged" — shown plainly, no gamified pressure

### 4.11 Data export, import, backup (Phase 1 manual; Phase 4 Drive)

- **Export** (Settings → Data): full JSON (`forkcast-YYYYMMDD-HHMM.json`) via the system file picker (so it can go to Downloads, Google Drive, or a network share). Option "Include photos" produces a `.zip` with the JSON + images. Also CSV of the food log and weights for spreadsheets
- **Import**: pick a `.json` or `.zip` → preview (counts per record type, date range) → **Merge** (upsert by UUID, newer `updatedAt` wins, soft-deletes respected) or **Replace** (automatically exports a safety backup first) → result summary
  - Built 2026-10-18 (Phase 1): JSON + CSV export, `.json` import. The "Include photos" `.zip` arrives with photos in Phase 2. Merge keeps this phone's settings (they have no edit times); Replace imports them. Merge maps meal slots from another install to local ones by name, and keeps one active goal (the most recently edited). The Replace safety backup goes to app storage (last 3 kept, excluded from Auto Backup) and Backup & data offers "Undo the last replace"
- **Google Drive auto-backup** (Phase 4): sign in with Google using the `drive.file` scope (the app only sees files it created). Creates a "Forkcast Backups" folder; uploads a gzipped JSON daily (configurable: daily/weekly, Wi-Fi only, charging only); keeps the last 14 daily + last 12 monthly; restore screen lists backups by date and runs the Import flow
- **Android Auto Backup**: enabled, with `data_extraction_rules.xml` / `full_backup_content` that **exclude** the encrypted-secrets file and the photos folder (25 MB cap). After a restore onto a new phone, the app detects missing API keys and asks for them
- Secrets (API keys, OAuth tokens) are never in any export or backup

### 4.12 Settings (cumulative)

Profile & goals · Daily targets (override any nutrient target) · Meals (names, count, time windows) · Units · AI provider · Food databases (USDA API key — optional, falls back to `DEMO_KEY` with a rate-limit warning; Open Food Facts country) · Notifications · Health Connect · Backup & data · Appearance (system/light/dark; "Use wallpaper colors" toggle, default off) · About (version, licenses, privacy statement)

---

## 5. Calculations (all in `:core:domain`, pure Kotlin, unit-tested)

### 5.1 BMR — Mifflin-St Jeor
- Male: `BMR = 10·W + 6.25·H − 5·A + 5`
- Female: `BMR = 10·W + 6.25·H − 5·A − 161`
- W = kg, H = cm, A = years (computed from date of birth, today's date)

### 5.2 TDEE (initial, formula-based)
`TDEE = BMR × activityFactor` — sedentary 1.2, light 1.375, moderate 1.55, very active 1.725, extra 1.9

Exercise calories (Phase 3): two modes, user-selectable:
- **Included** (default): activity factor already accounts for exercise; synced exercise is shown but not added to the budget
- **Added back**: use factor 1.2 and add a percentage (default 50%) of synced active calories to the day's budget, since wearables tend to overestimate

### 5.3 Goal → daily budget
- `energyPerKg = 7700 kcal` (≈3500 kcal/lb); a documented approximation, shown in "How we calculated this"
- `dailyDeficit = (currentKg − targetKg) × 7700 / daysToTarget`
- `budget = TDEE − dailyDeficit` (or + surplus for gain)
- Round to nearest 10 kcal

### 5.4 Guardrails (warn, never block; user can override and the override is visible on the dashboard as a small "custom" chip)
- Planned loss faster than **1.0% of body weight per week** → warning with the date that a 1%/week pace would reach
- Budget below **1,500 kcal (male) / 1,200 kcal (female)** or below BMR → warning recommending talking to a doctor or dietitian before going lower
- These thresholds live in one constants file

### 5.5 Weight trend
- Exponentially smoothed moving average on daily weights: `trend_t = trend_{t−1} + 0.1 × (w_t − trend_{t−1})`; days without a weigh-in carry the trend forward
- Multiple weigh-ins in one day → use the earliest (morning) reading

### 5.6 Adaptive TDEE (Phase 3)
- Eligible when, in the trailing 28 days: ≥ 14 days logged as "complete" (user marks a day complete, or ≥ 2 meals logged and ≥ 1,000 kcal), and ≥ 6 weigh-ins
- `observedTDEE = avgIntake(complete days) − (Δtrend_kg × 7700 / days)`
- Update weekly: `TDEE_new = TDEE_old + clamp(0.3 × (observedTDEE − TDEE_old), −150, +150)`
- Show "Estimated maintenance: 2,640 kcal (based on your last 4 weeks)" with confidence (low/medium/high by data completeness); user can lock to the formula value
- When TDEE changes, the budget recomputes; the deficit for the goal is preserved, guardrails re-checked

### 5.7 Default nutrient targets (all overridable)
- Protein: 1.6 g/kg of current body weight, capped at 2.2 g/kg of goal weight
- Fat: 30% of budget kcal (÷ 9)
- Carbs: remaining kcal (÷ 4)
- Fiber: 14 g per 1,000 kcal
- Sodium: ≤ 2,300 mg; Saturated fat: < 10% kcal; Added sugars: < 10% kcal
- Micronutrients (when data exists): US Daily Values as targets; displayed only in the nutrient panel

---

## 6. Data model (Room)

All tables: `id` (UUID string, PK), `createdAt`, `updatedAt` (epoch ms UTC), `deletedAt` (nullable, soft delete). Dates for logs stored as local `LocalDate` string plus the user's zone at the time.

### 6.1 Entities
- `Profile` (single row): sex, birthDate, heightCm, units prefs, activityLevel, exerciseMode, exerciseAddBackPct
- `Goal`: startDate, startWeightKg, targetWeightKg, targetDate | weeklyRateKg, goalType; history kept (one active)
- `TargetOverride`: nutrient key → value, effectiveFrom
- `TdeeEstimate`: date, method (formula/adaptive), value, confidence
- `Food`: name, brand, source (USDA/OFF/CUSTOM/RECIPE/AI), sourceId (fdcId/OFF code), barcode (GTIN-14 normalized), servingSizes (JSON list: label, grams), nutrientsPer100g (JSON map NutrientKey→Double), isFavorite, lastUsedAt, lastQuantity
- `Recipe` + `RecipeIngredient` (Phase 4)
- `SavedMeal` + `SavedMealItem`
- `MealSlot`: name, order, startTime, endTime (user-configurable)
- `LogEntry`: date, mealSlotId, foodId (nullable for quick add/AI), displayName, quantity, servingLabel, grams, **nutrients snapshot (JSON map)**, source, aiEstimateId (nullable), healthConnectRecordId (nullable), note
- `AiEstimate`: status (PENDING/RUNNING/DONE/NEEDS_INPUT/FAILED), provider, model, request description, personal context used, photo paths, raw JSON response, parsed components, totals low/expected/high, input/output tokens, latencyMs, error, attempts
- `AiComponent`: estimateId, name, preparation, grams, gramsLow, gramsHigh, confidence, usdaQuery, matchedFdcId, matchScore, nutrientsAi (JSON), nutrientsUsed (JSON), useSource (USDA/AI)
- `WeightEntry`: timestamp, kg, bodyFatPct, source (MANUAL/HEALTH_CONNECT), hcRecordId
- `WaterEntry`: timestamp, ml
- `ActivityDay` (cache from Health Connect): date, steps, activeKcal, totalKcal, exerciseMinutes
- `DayStatus`: date, markedComplete, note
- `NotificationLog`: date, type, threshold — enforces once-per-day
- `AiProfile`: name, provider, model, baseUrl, tierLabel (FREE/PAID/UNKNOWN), order, enabled, needsAttention, cooldownUntil (key stored separately, encrypted, referenced by profile id)
- `UsageRecord`: day, profileId, provider, model, requests, rateLimited count, inputTokens, outputTokens (monthly totals are aggregated from days)

Settings in DataStore (Proto): units, meals, notifications, AI provider + model per provider + personal context, food DB prefs, backup prefs, appearance. API keys in a separate Tink-encrypted file, excluded from backup.

### 6.2 Nutrient keys (enum, extensible)
`ENERGY_KCAL, PROTEIN_G, CARBS_G, FAT_G, SAT_FAT_G, TRANS_FAT_G, MONO_FAT_G, POLY_FAT_G, CHOLESTEROL_MG, FIBER_G, SUGARS_G, ADDED_SUGARS_G, SODIUM_MG, POTASSIUM_MG, CALCIUM_MG, IRON_MG, MAGNESIUM_MG, VITAMIN_A_UG, VITAMIN_C_MG, VITAMIN_D_UG, VITAMIN_B12_UG, CAFFEINE_MG, ALCOHOL_G, WATER_G`
Map USDA nutrient numbers and OFF `nutriments` keys to these in one mapping file each, with unit conversion.

### 6.3 Export schema (v1)
```json
{
  "schemaVersion": 1,
  "app": "Forkcast",
  "appVersion": "1.0.0",
  "exportedAt": "2026-10-16T14:00:00Z",
  "timeZone": "America/New_York",
  "profile": {}, "goals": [], "targetOverrides": [], "tdeeEstimates": [],
  "mealSlots": [], "foods": [], "recipes": [], "savedMeals": [],
  "logEntries": [], "aiEstimates": [], "weights": [], "water": [],
  "activityDays": [], "dayStatus": [], "settings": {}
}
```
- Field names are camelCase and mirror the entities; nutrient maps use the enum names
- `settings` excludes secrets; photos referenced by relative path only in `.zip` exports
- Write a JSON Schema file (`docs/export-schema-v1.json`) and a round-trip test: export → wipe → import → export again → identical except `exportedAt`

---

## 7. External services

| Service | Use | Auth | Notes |
|---|---|---|---|
| USDA FoodData Central | Search, food details, barcode (Branded `gtinUpc`), AI component matching | Free api.data.gov key (user-supplied; `DEMO_KEY` fallback) | Cache aggressively. Verified 2026-10-18 from response headers: personal key 3,600 requests/hour; `DEMO_KEY` only 10/hour. Key sent in the `X-Api-Key` header, not the URL. Foundation foods report energy only as nutrient 2047/2048 (Atwater), so energy falls back 1008 → 2048 → 2047 |
| Open Food Facts | Barcode lookup, search (international products) | None; custom User-Agent required | Read-only; respect rate limits; data is ODbL — show attribution in About. Verified 2026-10-18: product lookup `GET /api/v2/product/{code}` (v3 also exists); text search uses the newer `https://search.openfoodfacts.org/search` (the legacy `cgi/search.pl` is deprecated) |
| AI providers | Meal estimation, optional label extraction | User's API key | See AI_PROVIDERS.md |
| Health Connect | Weight, body fat, steps, energy, exercise in; nutrition, hydration out | Android permissions | Phase 3 |
| Google Drive | Backup | Google sign-in, `drive.file` scope | Phase 4; OAuth client set up per SETUP.md Stage 5 |

All network code: timeouts (connect 10 s, read 60 s for AI, 15 s otherwise), retries with exponential backoff for 429/5xx only, no retries for 4xx auth errors.

---

## 8. Security and privacy

- API keys: Tink AEAD with an Android Keystore master key; decrypted only in memory at request time; never logged (add an OkHttp/Ktor logging redactor for `x-api-key`, `x-goog-api-key`, `Authorization`)
- Release builds: R8 enabled, logging stripped, `android:allowBackup` with explicit rules (§4.11)
- Photos: app-private storage, EXIF (including GPS) stripped before storing and before upload
- `network_security_config`: cleartext disabled except a user-opted toggle for a LAN/VPN OpenAI-compatible endpoint (e.g., `http://` Ollama over Tailscale), shown with a warning
- Privacy statement in About: what leaves the device (food searches to USDA/OFF, photos + descriptions + personal-context text to the AI providers in the user's chain, backups to the user's Drive) and nothing else. No body or health data is ever sent to AI providers

---

## 9. Quality

- Unit tests: all of §5; nutrient mapping; GTIN normalization; label OCR parser (fixture strings from real labels); AI response parsing per provider (fixture JSON, including malformed and refusal cases); import/export round trip; notification threshold logic
- Room migration tests from v1 onward
- Compose UI tests: onboarding happy path, add food from search, scan flow with a fake scanner, photo flow with a fake estimator
- Fakes: `FakeMealEstimator`, `FakeFoodRepository`, `FakeHealthConnect` for tests and for a "demo mode" build flavor
- Performance: no main-thread disk/network (StrictMode in debug); Baseline Profile in Phase 5

## 10. CI/CD and distribution

- GitHub Actions `ci.yml`: on push/PR → JDK setup, Gradle cache, `assembleDebug testDebugUnitTest lintDebug`, upload debug APK artifact
- `release.yml`: on tag `v*` → decode keystore from secrets, `assembleRelease`, sign, attach APK to a GitHub Release with auto-generated notes. `versionName` from the tag, `versionCode` = git commit count (changed 2026-10-18 so PC builds and releases share one rising counter; see DECISIONS.md)
- Install/update on the phone by downloading the APK from the private repo's Releases, or by Claude Code over adb on request (no Obtainium; owner's choice 2026-10-18) — see SETUP.md Stage 6
- Keep the same signing key forever (updates fail otherwise); the Google OAuth client depends on its SHA-1

## 11. Open decisions (resolve with the owner when reached)
- Whether to add on-device food recognition (ML Kit / Gemini Nano) as an offline hint for the photo flow — research in Phase 5, not before
- Whether to support multiple photos as a "before/after" pair to estimate leftovers
- Barcode source priority for non-US products
