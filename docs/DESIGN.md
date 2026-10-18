# Forkcast — Design guide

Material 3 is the component foundation (accessibility, motion, and system behavior come for free). Forkcast's identity sits on top: its own color, type, and one signature element. When in doubt, choose the quieter option. A food log is opened 10+ times a day; it must feel calm and fast, never busy.

## 1. Idea

**The plate is a forecast.** The logo is a fork inside a progress ring with an apricot dot marking "now". The Today screen's budget ring is the same object at full size, and it does one thing no other tracker does: besides what you've eaten, it shows a faint **forecast arc**, the calories you'll probably still eat today based on your own recent pattern. You see at a glance whether today is on course *before* dinner, not after.

That ring is the one bold element. Everything else (lists, settings, forms) stays plain and disciplined.

## 2. Color

| Token | Light | Dark | Use |
|---|---|---|---|
| `pine` (primary) | `#0F7B5F` | `#5FCFA9` | Ring "eaten" arc, primary buttons, selected states |
| `pineDeep` | `#0A5A46` | `#3FAF8A` | Pressed states, icon background gradient end |
| `apricot` (accent) | `#F08A3E` | `#FFB27A` | "Now" dot, approaching-budget state (≥ 80%), highlights. Never for errors |
| `plum` (over) | `#8C4A8E` | `#D69AD8` | Over-budget arc and number. Deliberately not red: going over is information, not failure |
| `ink` | `#14201B` | `#E6EEEA` | Primary text |
| `porcelain` (background) | `#F3F6F4` | `#0E1512` | App background (cool, faintly green neutral) |
| `surface` | `#FFFFFF` | `#16201C` | Sheets, meal groups |
| `muted` | `#5E6B65` | `#9AA8A1` | Secondary text |
| `error` | `#B3261E` | `#F2B8B5` | Errors only (failed estimate, invalid key) |

Nutrient colors (used consistently in bars, charts, chips):
- Protein `#4C6FFF` / dark `#9DB0FF`
- Carbs `#D99A1E` / dark `#F2C46B`
- Fat `#D45D79` / dark `#F29AAE`
- Fiber `#3E9B62` / dark `#86D3A3`

Rules:
- Color is never the only signal: states also change the label ("1,240 left" → "120 over") and ring arcs differ in pattern (solid eaten, dashed forecast).
- All text meets WCAG AA contrast on its background in both themes; verify with a contrast test in CI (Compose screenshot test + simple luminance check on tokens).
- "Use wallpaper colors" (Material You) is an opt-in setting; default is the Forkcast palette.

## 3. Type

One family: **Manrope** (Google Fonts, OFL), bundled as a downloadable font with system fallback. Weights 400, 600, 800. Enable tabular figures (`fontFeatureSettings = "tnum"`) on every number that updates, so digits don't jitter.

| Role | Size / line | Weight | Notes |
|---|---|---|---|
| Ring number | 56 / 60 | 800 | Tight tracking (−2%) |
| Screen title | 24 / 30 | 800 | Sentence case |
| Section header (meal names) | 17 / 22 | 600 | |
| Body | 16 / 24 | 400 | |
| Secondary / meta | 14 / 20 | 400 | `muted` color |
| Small numbers in rows | 15 / 20 | 600 | tnum |

- Sentence case everywhere. No all-caps labels.
- Numbers are formatted with grouping (`1,240`), units after a thin space in small text (`32 g`).
- Must remain usable at 200% system font scale: rows grow vertically, nothing truncates important numbers.

## 4. Shape, spacing, elevation
- 4 dp grid; screen side padding 16 dp; between groups 24 dp
- Radii by hierarchy, not one radius everywhere: bottom sheets 28, meal groups 20, text fields 12, chips fully rounded
- Elevation: almost none. Meal groups sit on `surface` against `porcelain` with no shadow; sheets get the standard M3 sheet elevation. No decorative gradients outside the app icon

## 5. Today screen

```
┌──────────────────────────────────────┐
│ ‹  Today, Fri Oct 16            ›  📅 │
│                                      │
│          ╭────── ● ──╮               │  ● apricot "now" dot at end of eaten arc
│        ╱   1,240      ╲              │  solid pine arc = eaten
│       │    kcal left   │             │  dashed pale arc = forecast for rest of day
│        ╲  on course   ╱              │  status line: "on course" / "tight" / "120 over"
│          ╰──────────╯                │
│   Eaten 860 · Budget 2,100 · +0 ex   │
│                                      │
│  Protein ▓▓▓▓▓░░░░  62 / 150 g       │
│  Carbs   ▓▓▓▓░░░░░  95 / 210 g       │
│  Fat     ▓▓▓░░░░░░  28 / 70 g        │
│                                      │
│ Breakfast                    410 kcal│
│   Greek yogurt, 200 g          146   │
│   Banana, medium               105   │
│   Coffee w/ milk          AI·review │
│ Lunch                        450 kcal│
│   ⟳ Estimating… (photo)              │
│ Dinner              Same as yesterday│
│ Snacks                         + Add │
│                                      │
│ Water ▓▓▓░░░ 1.0 / 2.5 L   [+ glass] │
│ Weight 196.4 lb  trend ↓ 0.6/wk      │
│                               ( Log )│  FAB
└──────────────────────────────────────┘
```

- The forecast arc appears only after 7+ logged days (otherwise hidden; no fake data). Tapping the ring explains it in one sentence: "Based on what you usually eat after 4 pm, you'll likely end near 1,950."
- Status line wording: "on course" (forecast ≤ budget), "tight" (forecast within 5% over), "likely over by ~150" (forecast > budget), "120 over" (eaten > budget).
- Meal groups are list sections, not identical cards. Entries are single-line rows; long names truncate in the middle of the brand, never the kcal.
- Source badges are tiny and textual: `USDA`, `OFF`, `Label`, `AI`, `AI·review` (apricot outline until reviewed).

## 6. Logging flows

**Log sheet** (FAB): a bottom sheet, primary actions as large tiles in one row: Scan · Photo · Search. Below, smaller rows: Quick add calories · Saved meals · Recent (first 5 recents shown inline with one-tap add). The sheet remembers which tile was used last and puts focus there.

**Scanner**: full-bleed camera, a rounded scan window, torch button, "Type barcode" text button. On detect: haptic, window flashes pine, sheet slides up with the food. Not found: the sheet says "Not in the databases yet. Scan the nutrition label and we'll save it for next time." with a primary button "Scan label".

**Photo meal**: camera with a one-line hint at the top ("Fit the whole plate. A fork or hand helps with size."), shutter, up to 3 thumbnails, gallery button. Next screen: description field (multi-line, examples in the hint), meal chip (pre-selected), primary button "Estimate". Returns to Today immediately with the pending row.

**Review AI estimate**: photo at top (tap to enlarge), meal name, total with range ("~640 kcal, likely 520–760"), then component rows: name, matched food (tap to change), grams stepper with a slider below bounded to the model's range ×1.5, kcal. Hidden ingredients (oil, dressing) are grouped under "Likely added" so they can be removed in one tap. "Correct it" text field at the bottom with Send. Buttons: "Save" and "Delete estimate".

**Food detail**: name, brand, source; serving selector as chips; quantity stepper with decimal keypad on tap; nutrient preview shows the four core values large and "All nutrients" expandable. Primary button "Add to Lunch" (names the meal).

## 7. Other screens
- **Onboarding**: one question per screen, big tappable options, progress bar, "Back" always available. The final screen shows the budget with "How we calculated this" expandable, and any guardrail warnings in a neutral tone.
- **Trends**: segmented control (Week / Month / 3 months); one chart per section, no chart junk; tap-and-hold scrubbing with a value tooltip.
- **Settings**: standard M3 list. Group order: Profile & goals, Daily targets, Meals, Notifications, AI, Food databases, Health Connect, Backup & data, Appearance, About.

## 8. Motion
- Only motion that answers an action: the ring animates from the old to the new total when an entry is added/edited (400 ms, emphasized easing); rows added slide in; deleted rows collapse with Undo
- The pending AI row shows a subtle indeterminate progress line, not a spinner overlay
- Respect "Remove animations": all of the above become instant

## 9. Writing

Plain, specific, kind. Sentence case. The button names the action and the result uses the same verb ("Add to Lunch" → "Added to Lunch").

| Situation | Write | Don't write |
|---|---|---|
| 80% threshold | "1,680 of 2,100 kcal so far. About 420 left for today." | "Careful! You're almost at your limit!" |
| Over budget | "You're 120 kcal over today's budget." | "You blew your budget." |
| Evening outlook | "1,050 kcal left. Your usual dinner is about 750." | "Don't overeat tonight!" |
| Estimate ready | "Lunch estimated: ~640 kcal (520–760). Tap to review." | "Your AI analysis is complete!" |
| Invalid key | "Claude rejected the API key. Check it in Settings → AI." | "Something went wrong." |
| Offline estimate | "No connection. The estimate will run when you're back online." | "Error: network unavailable" |
| Empty meal | "Nothing logged yet." + buttons "Same as yesterday" / "Add" | "No data" |
| Barcode not found | "Not in the databases yet. Scan the nutrition label and we'll save it for next time." | "Product not found." |

No exclamation marks in system copy. No streak guilt. No food is called good or bad.

## 10. Icon assets (`assets/brand/`)
- `icon-foreground.svg`, `icon-background.svg`, `icon-monochrome.svg` — adaptive icon layers on the 108 dp grid; art stays inside the 66 dp safe circle. Convert to VectorDrawables (`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`; background as a gradient drawable or solid `#0F7B5F` if gradients misbehave in a launcher) and wire `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` with the `<monochrome>` layer for Android 13+ themed icons
- `logo.svg` / `logo-512.png` — rounded-square app logo (README, GitHub social preview, Obtainium)
- `logo-wordmark.svg` — logo + "forkcast" wordmark in Manrope 800
- Splash screen (SplashScreen API): `porcelain` background, the foreground layer as the animated icon (static is fine)
