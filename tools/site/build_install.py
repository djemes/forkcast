"""Writes site/install.html from INSTALL.md, so the install guide has one source (roadmap 2.0, Phase 5.2).

Handles the Markdown INSTALL.md uses: headings, paragraphs, ordered and bulleted lists, **bold**,
`code`, [links](...) and raw <details>/<summary> lines. Links to PRIVACY.md and TERMS.md go to the
site's own pages; other repository files go to GitHub.

Usage: python3 tools/site/build_install.py [INSTALL.md] [site/install.html]
"""
import html
import re
import sys

REPO = "https://github.com/djemes/forkcast/blob/main/"
SITE_PAGES = {"PRIVACY.md": "privacy.html", "TERMS.md": "terms.html", "INSTALL.md": "install.html"}


def slug(text):
    return re.sub(r"[^a-z0-9 -]", "", text.lower()).strip().replace(" ", "-")


def link_target(url):
    if url.startswith(("http://", "https://", "#")):
        return url
    return SITE_PAGES.get(url, REPO + url)


def inline(text):
    parts = re.split(r"(`[^`]+`)", text)
    out = []
    for part in parts:
        if part.startswith("`") and part.endswith("`") and len(part) > 1:
            out.append("<code>" + html.escape(part[1:-1]) + "</code>")
            continue
        s = html.escape(part, quote=False)
        s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)
        s = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", lambda m: f'<a href="{html.escape(link_target(m.group(2)))}">{m.group(1)}</a>', s)
        out.append(s)
    return "".join(out)


def convert(md):
    body, para, lst = [], [], None

    def flush():
        nonlocal lst
        if para:
            body.append("<p>" + inline(" ".join(para)) + "</p>")
            para.clear()
        if lst:
            tag, items = lst
            body.append(f"<{tag}>" + "".join(f"<li>{inline(i)}</li>" for i in items) + f"</{tag}>")
            lst = None

    for raw in md.splitlines():
        line = raw.rstrip()
        if not line:
            flush()
            continue
        if line.startswith(("<details", "</details", "<summary")):
            flush()
            body.append(line)
            continue
        m = re.match(r"^(#{1,3}) (.+)$", line)
        if m:
            flush()
            level, text = len(m.group(1)), m.group(2)
            body.append(f'<h{level} id="{slug(text)}">{inline(text)}</h{level}>')
            continue
        m = re.match(r"^(\d+)\. (.+)$", line) or re.match(r"^- (.+)$", line)
        if m:
            if para:
                flush()
            tag = "ol" if line[0].isdigit() else "ul"
            if lst is None or lst[0] != tag:
                flush()
                lst = (tag, [])
            lst[1].append(m.group(m.lastindex))
            continue
        if lst is not None and raw.startswith("  "):
            lst[1][-1] += " " + line.strip()
            continue
        para.append(line.strip())
    flush()
    return "\n  ".join(body)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "INSTALL.md"
    dst = sys.argv[2] if len(sys.argv) > 2 else "site/install.html"
    with open(src, encoding="utf-8") as f:
        content = convert(f.read())
    page = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Installing Forkcast</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
<main>
  {content}
  <footer class="muted"><a href="index.html">Forkcast</a> · <a href="privacy.html">Privacy policy</a> · <a href="terms.html">Terms</a></footer>
</main>
</body>
</html>
"""
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(page)


if __name__ == "__main__":
    main()
