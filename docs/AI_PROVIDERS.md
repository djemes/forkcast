# AI providers — design, prompt, schema, costs

Researched 2026-10-16. Prices and model IDs change; the app must read them from config/settings, never from logic. Re-check the official pages before release:
- Claude pricing: https://platform.claude.com/docs/en/about-claude/pricing
- Claude models: https://platform.claude.com/docs/en/models/overview
- Gemini pricing: https://ai.google.dev/gemini-api/docs/pricing
- Gemini models: https://ai.google.dev/gemini-api/docs/models

## 1. Interface

```kotlin
interface MealEstimator {
    val providerId: ProviderId                    // CLAUDE, GEMINI, OPENAI_COMPAT
    suspend fun listModels(): Result<List<ModelInfo>>
    suspend fun testConnection(): Result<ConnectionReport>   // tiny text-only call
    suspend fun estimate(request: MealEstimateRequest): Result<MealEstimateResult>
}

data class MealEstimateRequest(
    val images: List<EncodedImage>,      // JPEG bytes, already downscaled, EXIF stripped
    val description: String?,
    val mealSlot: String,                // "Lunch"
    val localTime: String,               // "12:40"
    val unitsHint: String,               // "US customary" | "metric"
    val personalContext: String?,        // from settings
    // Never add weight, goals, age, sex or other body/health data to this request (see §2.5).
    val previous: MealEstimateResult?,   // for "Correct it" follow-ups
    val correction: String?,             // the user's follow-up text
    val model: String,
)

data class MealEstimateResult(
    val parsed: MealEstimateJson,        // see §3
    val rawJson: String,
    val inputTokens: Int?, val outputTokens: Int?,
    val latencyMs: Long, val model: String,
)
```

- One implementation per provider in `:core:network`, each a thin Ktor client — **no vendor SDKs**, so the app has one HTTP stack and no SDK lock-in.
- A `MealEstimatorFactory` picks the implementation from settings at call time. Switching provider = changing a setting.
- All three implementations return the **same** `MealEstimateJson`. Provider differences (how to force JSON, image encoding, auth headers) stay inside each implementation.
- Errors map to one sealed type: `InvalidKey`, `RateLimited(retryAfter)`, `QuotaExhausted`, `ModelNotFound`, `ContentRefused`, `BadResponse(raw)`, `Network`, `Timeout`, `Server`. The UI shows a specific message for each.

## 2. Providers

### 2.1 Claude (Anthropic Messages API)
- `POST https://api.anthropic.com/v1/messages`
- Headers: `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`
- Images: `{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"..."}}` blocks placed **before** the text block
- Structured output: use the native structured-outputs feature if available for the chosen model; otherwise define one tool `record_meal_estimate` with the §3 schema as `input_schema` and force it with `tool_choice: {"type":"tool","name":"record_meal_estimate"}`. `// VERIFY:` current structured-outputs request shape at https://platform.claude.com/docs/en/build-with-claude/structured-outputs
- List models: `GET https://api.anthropic.com/v1/models`
- Usage: read `usage.input_tokens` / `usage.output_tokens`
- Recommended defaults: **`claude-sonnet-5-5`** (best accuracy/cost balance), budget option **`claude-haiku-5-5`**

### 2.2 Gemini (Gemini Developer API)
- `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
- Header: `x-goog-api-key`
- Images: `{"inline_data":{"mime_type":"image/jpeg","data":"..."}}` parts, then the text part
- Structured output: `generationConfig.responseMimeType = "application/json"` plus the §3 schema (`responseJsonSchema` or `responseSchema`). `// VERIFY:` field name for the current API version, and whether `generateContent` or the newer Interactions API is preferred: https://ai.google.dev/gemini-api/docs/structured-output
- Keep thinking low/minimal if the model supports a thinking budget — output pricing includes thinking tokens
- List models: `GET https://generativelanguage.googleapis.com/v1beta/models`
- Usage: `usageMetadata.promptTokenCount` / `candidatesTokenCount` (+ thoughts if reported)
- Recommended defaults: **`gemini-3.8-flash`**, budget option **`gemini-3.5-flash-lite`**
- Both are available on the **free tier** (no billing on the Google Cloud project). See §2.5.

### 2.5 Gemini free tier (owner's planned primary)
Facts checked 2026-10-16 (Google's pricing page, rate-limits page, and Gemini API terms):
- Same models as paid, so estimate quality is identical. Free tier covers Gemini 3.8 Flash, 3.6 Flash, 3.5 Flash-Lite, 3.1 Flash-Lite, 3 Flash Preview. Pro models and Batch are paid-only
- Limits are **per Google Cloud project and per model** (not per key), counted as requests/minute, tokens/minute, and requests/day. Daily quotas reset at **midnight Pacific**. Google doesn't publish fixed free-tier numbers; they're shown per project in AI Studio and "are not guaranteed". Third-party reports range from ~20 to ~1,500 requests/day per model. The app must not assume a number
- Because quotas are per model, a second model on the same key (e.g., Flash-Lite) is an independent free quota and makes a good first fallback
- Exceeding a limit returns **429 `RESOURCE_EXHAUSTED`**; free-tier capacity can also produce more 503s at peak times
- **Data use:** on the free tier, Google uses prompts, images, and responses to improve its products, and human reviewers may read them (after disconnecting them from the account/key/project). Google's terms say not to submit sensitive, confidential, or personal information. Paid tier: not used to improve products
- **Region:** apps offered to users in the EEA, Switzerland, or the UK may only use paid services
- Enabling billing on a project moves **all** its usage to paid. To keep a free key and a paid key, use two separate Google Cloud projects

App requirements that follow from this:
1. **Never put personal or health data in AI prompts**: no weight, goals, age, sex, or body data. Only the photo(s), the meal description, meal slot/time, unit system, and the user's "personal context" text. The personal-context field shows a note: "Sent with every photo. Don't include personal details."
2. Photos: EXIF stripped (already required). On a profile marked free tier, the camera screen shows a one-line hint: "Google may review free-tier photos. Keep faces and documents out of the frame."
3. On a 429 or 503, fall through the profile chain (§2.6) instead of failing; the pending entry only fails if every profile fails
4. Count requests per profile per day (reset at midnight Pacific for Gemini) and show "Used today: N" in Settings → AI, so the owner can learn the real limit
5. When the user picks Gemini, ask "Is billing enabled on this key's project?" (Free / Paid / Not sure) and store it as the profile's tier label. It controls the privacy note and cost display; the app can't detect tier from the API

### 2.6 Provider profiles and fallback chain
- An **AI profile** = provider + API key + model + base URL (OpenAI-compatible) + tier label + display name. Users can create several; the same key can appear in more than one profile with different models
- Settings → AI shows an ordered list ("Use in this order"), drag to reorder, toggle each on/off, and a **Test** button per profile
- Estimation tries profiles in order. Move to the next profile on: `RateLimited`, `QuotaExhausted`, `Server`, `Timeout`, `ModelNotFound`. Do **not** fall through on `ContentRefused` or `BadResponse` after one retry (another provider will likely fail the same way; show the error). On `InvalidKey`, skip that profile, notify once, and mark it "needs attention"
- A profile that returned 429 is skipped until its quota window resets (Gemini daily: next midnight Pacific; per-minute: 60 s), so the app doesn't waste requests
- Record which profile produced each estimate on the `AiEstimate` row and show it in the review screen ("Gemini 3.8 Flash · free")
- **Suggested default chain for the owner:**
  1. Gemini 3.8 Flash · free key
  2. Gemini 3.5 Flash-Lite · same free key (separate quota)
  3. Claude Sonnet 5.5 or Haiku 5.5 · paid key (optional; only used when 1–2 are out of quota or down)
- Onboarding for new users: one profile is enough; suggest the free Gemini setup first because it costs nothing, with the privacy note shown before they paste the key

### 2.3 OpenAI-compatible (OpenAI, OpenRouter, Ollama, LM Studio, vLLM)
- `POST {baseUrl}/v1/chat/completions`, header `Authorization: Bearer <key>` (blank allowed for local servers)
- Images: `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`
- Structured output: `response_format: {"type":"json_schema","json_schema":{...}}`; if the server rejects it, retry once with `{"type":"json_object"}` and the schema pasted into the prompt
- List models: `GET {baseUrl}/v1/models`
- Purpose: future flexibility and a self-hosted option (e.g., Ollama on the owner's Unraid server reached over the VPN). Expect noticeably weaker portion estimates from small local vision models

### 2.4 Defaults file
`core/network/src/main/assets/ai_defaults.json` (or a Kotlin object): provider → recommended model, budget model, price per 1M input/output tokens, max image edge, quota reset rule (Gemini: midnight `America/Los_Angeles`), and the suggested default profile chain (§2.6). Updated by editing one file, no code changes.

## 3. Response schema (all providers)

```json
{
  "type": "object",
  "required": ["meal_name", "items", "overall_confidence", "assumptions", "clarifying_question"],
  "properties": {
    "meal_name": {"type": "string"},
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name", "usda_query", "preparation", "grams", "grams_low", "grams_high",
                     "confidence", "is_hidden_ingredient", "nutrients"],
        "properties": {
          "name": {"type": "string"},
          "usda_query": {"type": "string", "description": "Generic USDA-style name, e.g. 'Rice, white, cooked' or 'Chicken thigh, roasted, meat only'"},
          "preparation": {"type": "string", "description": "raw | boiled | fried | roasted | grilled | baked | sauce | unknown ..."},
          "grams": {"type": "number"},
          "grams_low": {"type": "number"},
          "grams_high": {"type": "number"},
          "confidence": {"type": "number", "minimum": 0, "maximum": 1},
          "is_hidden_ingredient": {"type": "boolean", "description": "true for cooking oil, butter, dressing, sugar in drinks, etc. inferred rather than seen"},
          "nutrients": {
            "type": "object",
            "required": ["kcal", "protein_g", "carbs_g", "fat_g"],
            "properties": {
              "kcal": {"type": "number"}, "protein_g": {"type": "number"},
              "carbs_g": {"type": "number"}, "fat_g": {"type": "number"},
              "fiber_g": {"type": "number"}, "sugar_g": {"type": "number"},
              "sat_fat_g": {"type": "number"}, "sodium_mg": {"type": "number"}
            }
          }
        }
      }
    },
    "overall_confidence": {"type": "number", "minimum": 0, "maximum": 1},
    "assumptions": {"type": "array", "items": {"type": "string"}},
    "clarifying_question": {"type": ["string", "null"]}
  }
}
```

Validate after parsing: grams > 0, low ≤ grams ≤ high, kcal plausible for grams (≤ 9.5 kcal/g). Clamp or reject the item and flag it; never crash on bad output.

## 4. Prompt

System prompt (store in a resource file so it can be tuned without code changes; version it, and record the prompt version on each `AiEstimate`):

```
You estimate the nutritional content of meals from photos and a short description, for a calorie-tracking app.

Method:
1. Identify every distinct food component in the photos. Use the user's description as the primary source of truth; if it conflicts with the image, follow the description and note it in assumptions.
2. Estimate the edible weight in grams of each component as served (cooked weight for cooked foods). Use visual scale cues: plate size (assume a 27 cm dinner plate unless the context says otherwise), utensils, hands, packaging, and depth from multiple photos.
3. Add likely hidden ingredients as separate items with is_hidden_ingredient=true: cooking oil or butter for fried/sautéed/roasted foods, dressings, sauces, sugar or milk in drinks. Estimate them conservatively but do not omit them; they are a major source of underestimation.
4. For each item give a realistic low–high gram range that reflects your uncertainty, and a confidence from 0 to 1.
5. Give usda_query as a generic USDA FoodData Central-style name (e.g. "Rice, white, long-grain, cooked", "Chicken, thigh, roasted, meat only"). For restaurant or packaged items, use the closest generic equivalent.
6. Estimate nutrients for the item at the stated grams.
7. If the photo does not show food, or a key component is impossible to judge, set clarifying_question to one short question; still return your best estimate.

Rules:
- Return only the structured result. No prose outside it.
- Do not round everything to neat numbers; estimate.
- Do not moralize about the food or give diet advice.
```

User turn content: images, then text:
```
Meal: {mealSlot} at {localTime}. Units the user thinks in: {unitsHint}.
Description: {description or "none"}
Personal context: {personalContext or "none"}
```

Follow-up ("Correct it") turn: include the previous JSON and `User correction: {correction}. Return the full updated result.`

## 5. Images
- Downscale so the long edge is ≤ 1568 px, JPEG quality 85, strip EXIF (keep orientation applied to pixels)
- Up to 3 images per request
- Never send the original full-resolution file

## 6. Cost estimates (per photo, single image, as of 2026-10-16)

Assumptions: ~2,500 image tokens + ~1,200 prompt/schema tokens in; ~700 tokens out (Gemini: ~1,500 out including thinking). Real numbers come from the usage fields; the app tracks them.

| Model | Price in / out per 1M tokens | ≈ per photo | ≈ per month at 4 photos/day |
|---|---|---|---|
| Claude Haiku 5.5 | $0.10 / $0.50 (prompts ≤ 100k) | ~$0.001 | ~$0.10 |
| Claude Sonnet 5.5 | $2 / $10 | ~$0.014 | ~$1.70 |
| Gemini 3.8 Flash | $0.75 / $3.75 through Dec 31, 2026; $1.50 / $7.50 from Jan 1, 2027 | ~$0.007 → ~$0.014 | ~$0.85 → ~$1.70 |
| Gemini 3.5 Flash-Lite | $0.30 / $2.50 | ~$0.005 | ~$0.55 |
| Gemini free tier | $0 (rate-limited; Google may use content to improve products) | $0 | $0 |

Requests per day in practice: 1 per photo meal, +1 per "Correct it" follow-up, +1 per label-extraction fallback, +1 per Test button press. A typical day is ~5–15 requests, well inside most reported free-tier limits, and the fallback chain covers the low end.

### Subscriptions vs API keys (important)
- **Claude Pro** does **not** include API credits. **Max 5x / Max 20x** include **$100 / $200 per month** of API credits, claimed on claude.ai (Settings → Billing → API credits) by linking a Claude Console organization; they expire monthly. Otherwise the API is pay-as-you-go from platform.claude.com.
- **Google AI Pro / Ultra** benefits apply inside the Google AI Studio web UI only. An API key used by an app is billed separately (or uses the free tier).
- Either way, the app only needs an API key. Switching providers is a settings change.

## 7. Tests
- Fixture responses per provider: valid, valid-with-clarifying-question, malformed JSON, missing fields, refusal/blocked, 401, 429 with retry-after, 500
- Contract test: all three providers' fixtures parse into identical `MealEstimateJson` for the same logical answer
- A manual "golden set" (not CI): 20 of the owner's weighed meals with photos and true values, to compare providers/models and prompt versions (mean absolute % error on kcal)
