"""Turns the raw emulator captures into the README's images (roadmap 2.0, Phase 5.3).

  python -X utf8 tools/screenshots/compose.py --raw build/readme-raw --out assets/readme --crop-top 159 --crop-bottom 72

Writes, for light and dark:
  screens/<name>-<theme>.webp   each screen at 2x its README width, rounded corners, thin outline
  banner-<theme>.webp           Today, Log and Trends side by side (the hero)
  privacy-<theme>.png           the privacy-at-a-glance diagram (drawn here, text in Manrope)
  download-<theme>.png          the "Download for Android" button
and social-preview.png (1280 x 640, for the repository settings). Needs Pillow only.
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "manrope.ttf")
LOGO = os.path.join(ROOT, "assets", "brand", "logo-512.png")

# docs/DESIGN.md colors: light, dark
PINE = ("#0F7B5F", "#5FCFA9")
PINE_DEEP = "#0A5A46"
APRICOT = ("#F08A3E", "#FFB27A")
INK = ("#14201B", "#E6EEEA")
MUTED = ("#5E6B65", "#9AA8A1")
SURFACE = ("#FFFFFF", "#16201C")
OUTLINE = ("#D3DCD8", "#2C3833")

SCREEN_WIDTH = 540  # shown at 270 px
# Part of each screen to keep, from the top (the rest is cut, e.g. rows that only matter after a restore).
KEEP = {"today": 1.0, "log": 1.0, "budget": 1.0, "trends": 1.0, "backup": 0.62}
HERO = ["today", "log", "trends"]


def font(size, weight="Regular"):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name(weight)
    except (OSError, ValueError):
        pass
    return f


def rounded(im, radius, outline):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im.convert("RGBA"), (0, 0), mask)
    ImageDraw.Draw(out).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius, outline=outline, width=2)
    return out


def load_screen(raw, name, theme, top, bottom):
    im = Image.open(os.path.join(raw, f"{name}-{theme}.png")).convert("RGB")
    im = im.crop((0, top, im.width, im.height - bottom))
    keep = int(im.height * KEEP.get(name, 1.0))
    return im.crop((0, 0, im.width, keep))


def fit_width(im, width):
    return im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)


def banner(screens, theme_index):
    w, gap, pad = 400, 36, 8
    h = 820
    canvas = Image.new("RGBA", (pad * 2 + w * 3 + gap * 2, h + pad * 2), (0, 0, 0, 0))
    for i, im in enumerate(screens):
        tile = fit_width(im, w)
        tile = rounded(tile.crop((0, 0, w, min(h, tile.height))), 28, OUTLINE[theme_index])
        canvas.alpha_composite(tile, (pad + i * (w + gap), pad))
    return canvas


def text_center(draw, xy, text, f, fill):
    box = draw.textbbox((0, 0), text, font=f)
    draw.text((xy[0] - (box[2] - box[0]) / 2, xy[1] - (box[3] - box[1]) / 2 - box[1]), text, font=f, fill=fill)


def privacy_diagram(t):
    """The phone in the middle with what stays on it; around it only what the user turns on."""
    W, H = 1280, 900
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    title, body, small = font(34, "Bold"), font(26, "Regular"), font(23, "Regular")

    # Phone
    px, py, pw, ph = W // 2 - 190, 210, 380, 480
    d.rounded_rectangle((px, py, px + pw, py + ph), 48, fill=SURFACE[t], outline=PINE[t], width=6)
    d.rounded_rectangle((px + pw // 2 - 40, py + 18, px + pw // 2 + 40, py + 28), 5, fill=OUTLINE[t])
    text_center(d, (W // 2, py + 80), "Your phone", title, INK[t])
    for i, line in enumerate(["Food log and weights", "Meal photos", "Settings", "Your keys, encrypted"]):
        text_center(d, (W // 2, py + 160 + i * 62), line, body, INK[t])
    text_center(d, (W // 2, py + ph - 52), "No account. No ads. No tracking.", small, PINE[t])

    boxes = [
        ((40, 40), "Food databases", ["When you search or scan:", "Open Food Facts, and USDA", "with your own key"]),
        ((W - 420, 40), "Your AI service", ["Optional, your own key,", "asked first: meal photos", "and descriptions"]),
        ((40, 640), "Your backups", ["Wherever you save them,", "and Android's device", "backup if it's on"]),
        ((W - 420, 640), "Health Connect", ["On your phone, shared", "only with what you allow"]),
        ((W // 2 - 190, 760), "GitHub", ["Checking for updates"]),
    ]
    for (bx, by), head, lines in boxes:
        bw, bh = 380, 70 + 34 * len(lines)
        if head == "GitHub":
            bh = 110
        d.rounded_rectangle((bx, by, bx + bw, by + bh), 22, fill=None, outline=OUTLINE[t], width=3)
        d.text((bx + 24, by + 18), head, font=font(28, "Bold"), fill=INK[t])
        for i, line in enumerate(lines):
            d.text((bx + 24, by + 62 + i * 34), line, font=small, fill=MUTED[t])
        # A dashed line from the box to the phone: only when used.
        cx, cy = bx + bw / 2, by + bh / 2
        tx = px if cx < px else (px + pw if cx > px + pw else W // 2)
        ty = py + ph if head == "GitHub" else (py + 120 if by < py else py + ph - 120)
        sx = bx + bw if cx < px else (bx if cx > px + pw else cx)
        sy = by if head == "GitHub" else cy
        steps = 14
        for s in range(steps):
            if s % 2:
                continue
            a, b = s / steps, (s + 1) / steps
            d.line((sx + (tx - sx) * a, sy + (ty - sy) * a, sx + (tx - sx) * b, sy + (ty - sy) * b), fill=APRICOT[t], width=5)
    return im


def download_button(t):
    """The README's "Download for Android" button, at 2x (shown 300 x 64)."""
    W, H = 600, 128
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    fill, ink = (PINE[0], "#FFFFFF") if t == 0 else (PINE[1], "#0E1512")
    d.rounded_rectangle((0, 0, W - 1, H - 1), H // 2, fill=fill)
    # A down arrow onto a tray
    x, y = 74, 34
    d.line((x, y, x, y + 44), fill=ink, width=8)
    d.line((x - 20, y + 26, x, y + 46, x + 20, y + 26), fill=ink, width=8, joint="curve")
    d.line((x - 26, y + 62, x + 26, y + 62), fill=ink, width=8)
    d.text((124, H // 2), "Download for Android", font=font(40, "Bold"), fill=ink, anchor="lm")
    return im


def social_preview(today, trends):
    W, H = 1280, 640
    im = Image.new("RGB", (W, H), PINE_DEEP)
    grad = Image.linear_gradient("L").resize((W, H))  # #14906F at the top to pineDeep at the bottom, like the icon
    im = Image.composite(Image.new("RGB", (W, H), "#14906F"), im, grad.point(lambda v: 255 - v))
    d = ImageDraw.Draw(im)
    logo = Image.open(LOGO).convert("RGBA").resize((120, 120), Image.LANCZOS)
    im.paste(logo, (90, 150), logo)
    d.text((90, 300), "Forkcast", font=font(84, "ExtraBold"), fill="#FFFFFF")
    for i, line in enumerate(["A free calorie and nutrition tracker", "for Android. Your data stays on", "your phone."]):
        d.text((92, 412 + i * 44), line, font=font(34, "Medium"), fill="#E6EEEA")
    for i, shot in enumerate([today, trends]):
        tile = fit_width(shot, 300).crop((0, 0, 300, 560))
        tile = rounded(tile, 26, "#0A5A46")
        im.paste(tile, (650 + i * 320, 40), tile)
    return im


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--raw", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--crop-top", type=int, default=0)
    p.add_argument("--crop-bottom", type=int, default=0)
    a = p.parse_args()
    os.makedirs(os.path.join(a.out, "screens"), exist_ok=True)

    names = sorted({f.rsplit("-", 1)[0] for f in os.listdir(a.raw) if f.endswith(".png") and "-" in f})
    for t, theme in enumerate(("light", "dark")):
        loaded = {}
        for name in names:
            if not os.path.exists(os.path.join(a.raw, f"{name}-{theme}.png")):
                continue
            loaded[name] = load_screen(a.raw, name, theme, a.crop_top, a.crop_bottom)
            out = rounded(fit_width(loaded[name], SCREEN_WIDTH), 36, OUTLINE[t])
            out.save(os.path.join(a.out, "screens", f"{name}-{theme}.webp"), "WEBP", quality=86, method=6)
        if all(n in loaded for n in HERO):
            banner([loaded[n] for n in HERO], t).save(os.path.join(a.out, f"banner-{theme}.webp"), "WEBP", quality=86, method=6)
        download_button(t).save(os.path.join(a.out, f"download-{theme}.png"), optimize=True)
        privacy_diagram(t).save(os.path.join(a.out, f"privacy-{theme}.png"), optimize=True)
        if theme == "light" and "today" in loaded and "trends" in loaded:
            social_preview(loaded["today"], loaded["trends"]).save(os.path.join(a.out, "social-preview.png"), optimize=True)

    total = 0
    for root, _, files in os.walk(a.out):
        for f in files:
            size = os.path.getsize(os.path.join(root, f))
            total += size
            print(f"{os.path.relpath(os.path.join(root, f), a.out):34} {size / 1024:7.0f} KB")
    print(f"{'total':34} {total / 1024:7.0f} KB")


if __name__ == "__main__":
    main()
