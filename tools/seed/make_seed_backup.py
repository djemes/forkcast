"""Builds a year of realistic Forkcast data as a backup file, for upgrade and restore tests.

  python tools/seed/make_seed_backup.py out.forkcast [--days 365] [--photos 20] [--demo]

--demo makes the README's demo data (tools/screenshots): no brand names, a steady loss of about
0.45 kg a week on meals near the budget, and no photos. Without it, the upgrade-test data as before.

The result is a zip with forkcast.json (export schema v9) and meal photos, a .forkcast without
encryption that Forkcast restores. Deterministic (fixed seed), so two runs give the same data.
"""
import argparse
import datetime as dt
import io
import json
import random
import struct
import uuid
import zipfile
import zlib

parser = argparse.ArgumentParser()
parser.add_argument("out")
parser.add_argument("--days", type=int, default=365)
parser.add_argument("--photos", type=int, default=20)
parser.add_argument("--demo", action="store_true")
args = parser.parse_args()
if args.demo:
    args.photos = 0

rnd = random.Random(2026)
ZONE = "America/New_York"
TODAY = dt.date.today()
START = TODAY - dt.timedelta(days=args.days - 1)


def uid():
    return str(uuid.UUID(int=rnd.getrandbits(128), version=4))


def ms(day, minute=12 * 60):
    t = dt.datetime.combine(day, dt.time()) + dt.timedelta(minutes=minute, hours=4)  # ~New York in UTC
    return int(t.replace(tzinfo=dt.timezone.utc).timestamp() * 1000)


def stamp(row, day, minute=12 * 60):
    t = ms(day, minute)
    row.update(createdAt=t, updatedAt=t, deletedAt=None)
    return row


# Meal slots, as onboarding makes them.
slots = []
for i, (name, a, b) in enumerate([("Breakfast", 240, 660), ("Lunch", 660, 900), ("Dinner", 1020, 1260), ("Snacks", 0, 1439)]):
    slots.append(stamp({"id": uid(), "name": name, "sortOrder": i, "startMinute": a, "endMinute": b}, START))

# Foods: per 100 g.
FOODS = [
    ("Rolled oats", None, 379, 13.2, 67.7, 6.5, 40, "½ cup"), ("Banana", None, 89, 1.1, 22.8, 0.3, 118, "1 medium"),
    ("Greek yogurt, plain", "Fage", 73, 10.0, 3.9, 1.9, 170, "1 container"), ("Egg, whole", None, 143, 12.6, 0.7, 9.5, 50, "1 large"),
    ("Whole wheat bread", "Dave's Killer Bread", 247, 13.0, 41.0, 3.4, 45, "1 slice"), ("Chicken breast, grilled", None, 165, 31.0, 0.0, 3.6, 120, "1 piece"),
    ("Brown rice, cooked", None, 123, 2.7, 25.6, 1.0, 195, "1 cup"), ("Broccoli, steamed", None, 35, 2.4, 7.2, 0.4, 90, "1 cup"),
    ("Salmon, baked", None, 206, 22.1, 0.0, 12.4, 140, "1 fillet"), ("Olive oil", None, 884, 0.0, 0.0, 100.0, 14, "1 tbsp"),
    ("Apple", None, 52, 0.3, 13.8, 0.2, 182, "1 medium"), ("Almonds", None, 579, 21.2, 21.6, 49.9, 28, "1 oz"),
    ("Peanut butter", "Smucker's", 588, 25.0, 20.0, 50.0, 32, "2 tbsp"), ("Coffee, black", None, 1, 0.1, 0.0, 0.0, 240, "1 cup"),
    ("Milk, 2%", None, 50, 3.3, 4.8, 2.0, 244, "1 cup"), ("Pasta, cooked", None, 158, 5.8, 30.9, 0.9, 140, "1 cup"),
    ("Marinara sauce", "Rao's", 70, 1.5, 6.0, 4.5, 125, "½ cup"), ("Ground beef 90%, cooked", None, 217, 26.1, 0.0, 11.7, 85, "3 oz"),
    ("Black beans, canned", None, 91, 6.0, 16.6, 0.3, 130, "½ cup"), ("Tortilla, flour", "Mission", 312, 8.3, 51.6, 7.8, 45, "1 tortilla"),
    ("Cheddar cheese", None, 403, 24.9, 1.3, 33.1, 28, "1 slice"), ("Avocado", None, 160, 2.0, 8.5, 14.7, 68, "½ avocado"),
    ("Spinach, raw", None, 23, 2.9, 3.6, 0.4, 30, "1 cup"), ("Protein bar", "RXBAR", 380, 23.0, 42.0, 14.0, 52, "1 bar"),
    ("Dark chocolate 70%", None, 598, 7.8, 45.9, 42.6, 20, "2 squares"), ("Orange juice", "Tropicana", 45, 0.7, 10.4, 0.2, 240, "1 cup"),
    ("Pizza, pepperoni", "Domino's", 270, 11.5, 30.0, 11.5, 110, "1 slice"), ("Caesar salad", None, 190, 4.0, 8.0, 16.0, 150, "1 bowl"),
]
foods = []
for name, brand, kcal, p, c, f, grams, label in FOODS:
    if args.demo:
        brand = None  # no real products in the README's pictures
    foods.append(stamp({
        "id": uid(), "name": name, "brand": brand, "source": "USDA" if args.demo else (rnd.choice(["USDA", "OFF", "CUSTOM"]) if brand is None else "OFF"),
        "sourceId": None, "barcode": None, "servingSizes": [{"label": label, "grams": grams}, {"label": "100 g", "grams": 100.0}],
        "nutrientsPer100g": {"ENERGY_KCAL": kcal, "PROTEIN_G": p, "CARBS_G": c, "FAT_G": f, "FIBER_G": round(rnd.uniform(0, 6), 1), "SODIUM_MG": round(rnd.uniform(0, 400))},
        "isFavorite": rnd.random() < 0.2, "lastUsedAt": None, "lastQuantity": None, "lastServingLabel": None,
        "countsAsWater": None, "describedAs": None,
    }, START))


def portion(food, qty=1.0):
    g = food["servingSizes"][0]["grams"] * qty
    n = {k: round(v * g / 100, 2) for k, v in food["nutrientsPer100g"].items()}
    return g, n


PLANS = {"Breakfast": [[0, 1, 13], [2, 1, 13], [3, 3, 4, 13], [0, 14, 10]], "Lunch": [[5, 6, 7], [19, 18, 20, 21], [26], [27, 5]],
         "Dinner": [[8, 6, 7, 9], [15, 16, 17], [5, 22, 9, 6], [26, 27]], "Snacks": [[10], [11], [12, 10], [23], [24]]}

entries, water, weights, days, activity = [], [], [], [], []
weight = 90.0 if args.demo else 92.0
QTYS = [1.0, 1.0, 1.5, 1.5, 2.0] if args.demo else [1.0, 1.0, 1.0, 0.5, 1.5, 2.0]
day = START
while day <= TODAY:
    skip = rnd.random() < (0.02 if args.demo else 0.06)  # an unlogged day now and then
    if not skip:
        for slot in slots:
            if slot["name"] == "Snacks" and rnd.random() < (0.1 if args.demo else 0.4):
                continue
            if args.demo and day == TODAY and slot["name"] in ("Dinner", "Snacks"):
                continue  # the README's Today is an afternoon: breakfast and lunch so far
            minute = rnd.randint(slot["startMinute"] + 30, min(slot["endMinute"], slot["startMinute"] + 200)) if slot["name"] != "Snacks" else rnd.randint(840, 1290)
            for idx in rnd.choice(PLANS[slot["name"]]):
                food = foods[idx]
                qty = rnd.choice(QTYS)
                if args.demo and day == TODAY:
                    qty = 1.0  # single portions so far, so the afternoon ring isn't "Likely over"
                g, n = portion(food, qty)
                entries.append(stamp({
                    "id": uid(), "date": day.isoformat(), "zoneId": ZONE, "mealSlotId": slot["id"], "foodId": food["id"],
                    "displayName": food["name"], "brand": food["brand"], "quantity": qty, "servingLabel": food["servingSizes"][0]["label"],
                    "grams": g, "nutrients": n, "source": food["source"], "aiEstimateId": None, "healthConnectRecordId": None,
                    "note": None, "loggedAt": ms(day, minute), "waterMl": None, "eatenMinute": minute,
                }, day, minute))
        for _ in range(rnd.randint(3, 8)):
            m = rnd.randint(420, 1320)
            water.append(stamp({"id": uid(), "timestamp": ms(day, m), "date": day.isoformat(), "zoneId": ZONE, "ml": rnd.choice([250.0, 355.0, 500.0]), "hcRecordId": None}, day, m))
    weight += rnd.uniform(-0.2, 0.07) if args.demo else rnd.uniform(-0.25, 0.18)
    if rnd.random() < 0.55:
        scale = weight + (rnd.uniform(-0.6, 0.6) if args.demo else 0.0)  # day-to-day water weight
        weights.append(stamp({"id": uid(), "timestamp": ms(day, 420), "kg": round(scale, 2), "bodyFatPct": None, "source": "MANUAL", "hcRecordId": None}, day, 420))
    if rnd.random() < 0.7:
        steps = rnd.randint(2500, 14000)
        activity.append(stamp({"id": uid(), "date": day.isoformat(), "steps": steps, "activeKcal": round(steps * 0.04, 1), "totalKcal": None, "exerciseMinutes": rnd.choice([None, 20, 30, 45]), "sessions": []}, day, 1380))
    if not skip and rnd.random() < 0.3:
        days.append(stamp({"id": uid(), "date": day.isoformat(), "markedComplete": True, "note": None}, day, 1380))
    day += dt.timedelta(days=1)

saved, saved_items = [], []
for name, idxs in [("Usual breakfast", [0, 1, 13]), ("Chicken and rice", [5, 6, 7]), ("Taco night", [19, 18, 20, 21])]:
    m = stamp({"id": uid(), "name": name}, START)
    saved.append(m)
    for i, idx in enumerate(idxs):
        g, n = portion(foods[idx])
        saved_items.append(stamp({"id": uid(), "savedMealId": m["id"], "sortOrder": i, "foodId": foods[idx]["id"], "displayName": foods[idx]["name"], "brand": foods[idx]["brand"],
                                  "quantity": 1.0, "servingLabel": foods[idx]["servingSizes"][0]["label"], "grams": g, "nutrients": n, "source": foods[idx]["source"], "waterMl": None}, START))

recipes, ingredients = [], []
for name, servings, idxs in [("Chili", 6, [17, 18, 16]), ("Overnight oats", 2, [0, 14, 1])]:
    r = stamp({"id": uid(), "name": name, "servings": float(servings), "cookedWeightG": None, "foodId": None}, START)
    recipes.append(r)
    for i, idx in enumerate(idxs):
        g, n = portion(foods[idx], 2.0)
        ingredients.append(stamp({"id": uid(), "recipeId": r["id"], "sortOrder": i, "foodId": foods[idx]["id"], "displayName": foods[idx]["name"], "brand": foods[idx]["brand"],
                                  "quantity": 2.0, "servingLabel": foods[idx]["servingSizes"][0]["label"], "grams": g, "nutrients": n}, START))

export = {
    "schemaVersion": 9, "app": "Forkcast", "appVersion": "seed", "exportedAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"), "timeZone": ZONE,
    "profile": stamp({"id": "profile", "sex": "MALE", "birthDate": "1988-04-12", "heightCm": 180.3, "activityLevel": "LIGHT", "exerciseMode": "INCLUDED", "exerciseAddBackPct": 50}, START),
    "goals": [stamp({"id": uid(), "goalType": "LOSE", "startDate": START.isoformat(), "startWeightKg": weights[0]["kg"] if args.demo else 92.0, "targetWeightKg": 75.0 if args.demo else 82.0, "targetDate": None, "weeklyRateKg": 0.45, "isActive": True}, START)],
    "targetOverrides": [], "tdeeEstimates": [], "mealSlots": slots, "foods": foods, "recipes": recipes, "recipeIngredients": ingredients,
    "savedMeals": saved, "savedMealItems": saved_items, "logEntries": entries, "aiEstimates": [], "aiComponents": [], "aiProfiles": [],
    "usageRecords": [], "notificationLog": [], "aiMatchCache": [], "weights": weights, "water": water, "activityDays": activity, "dayStatus": days,
}


def tiny_jpeg_like_png(seed):
    """A small valid PNG (named .jpg as the app names photos; only bytes are copied, never decoded on restore)."""
    w = h = 8
    raw = b"".join(b"\x00" + bytes((seed * 7 + x * 13 + y * 17) % 256 for x in range(w) for _ in range(3)) for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("forkcast.json", json.dumps(export, indent=2, ensure_ascii=False))
    for i in range(args.photos):
        z.writestr(f"photos/seed{i:03d}/0.jpg", tiny_jpeg_like_png(i))

print(f"{args.out}: {len(entries)} log entries, {len(foods)} foods, {len(weights)} weigh-ins, {len(water)} water, {len(activity)} activity days, "
      f"{len(saved)} saved meals, {len(recipes)} recipes, {args.photos} photos, {START} to {TODAY}")
