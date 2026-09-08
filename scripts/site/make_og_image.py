#!/usr/bin/env python3
"""
make_og_image.py — render the 1200×630 social-share card (og:image) for
Common Root?, composited from the existing PWA logo mark on the brand navy.

Output:  app/src/main/resources/META-INF/resources/images/og-cover.png
Served:  https://common-root.org/images/og-cover.png
         (referenced by SocialPreviewInitListener)

Run on the Mac (PIL is already present from the favicon work):
  python3 scripts/site/make_og_image.py

Tweakables below: SHOW_WORDMARK (set False if you'd rather swap in logo-about.png,
which already contains the wordmark), the tagline, and the logo size.
"""
import os
from PIL import Image, ImageDraw, ImageFont

REPO = os.environ.get("REPO", os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
RES = os.path.join(REPO, "app/src/main/resources/META-INF/resources")
LOGO = os.path.join(RES, "icons/icon-512.png")
OUTDIR = os.path.join(RES, "images")
OUT = os.path.join(OUTDIR, "og-cover.png")

W, H = 1200, 630
NAVY = (3, 21, 59)          # #03153b — brand background
INK = (237, 241, 248)       # near-white title
MUTE = (150, 165, 192)      # muted slate tagline

SHOW_WORDMARK = True        # False if the logo image already includes the name
LOGO_PX = 300
CORNER_RADIUS = round(LOGO_PX * 0.18)  # rounds off the maskable icon's filled corners
TAGLINE = "The Abrahamic scriptures, side by side."


def _font(size, bold=False):
    """Resolve a serif face on macOS; fall back gracefully."""
    candidates = (
        ["/System/Library/Fonts/Supplemental/Georgia Bold.ttf",
         "/Library/Fonts/Georgia Bold.ttf"]
        if bold else
        ["/System/Library/Fonts/Supplemental/Georgia.ttf",
         "/Library/Fonts/Georgia.ttf"]
    ) + ["/System/Library/Fonts/Supplemental/Times New Roman.ttf"]
    for p in candidates:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def _navy_tile(src):
    """The PWA mark is a maskable icon: its corners are filled (black here), which
    composite as black triangles on the card. Flatten the mark onto a navy tile
    and round the corners so they resolve to brand navy. Returns opaque RGB.
    Tune CORNER_RADIUS: raise it if any black slivers remain, lower it if the
    panel looks over-rounded."""
    navy = Image.new("RGBA", src.size, NAVY + (255,))
    flat = Image.alpha_composite(navy, src)
    mask = Image.new("L", src.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, src.size[0] - 1, src.size[1] - 1], radius=CORNER_RADIUS, fill=255)
    return Image.composite(flat, navy, mask).convert("RGB")


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    img = Image.new("RGB", (W, H), NAVY)
    draw = ImageDraw.Draw(img)

    logo = _navy_tile(Image.open(LOGO).convert("RGBA").resize((LOGO_PX, LOGO_PX), Image.LANCZOS))

    if SHOW_WORDMARK:
        # logo left, text block right
        lx, ly = 100, (H - LOGO_PX) // 2
        img.paste(logo, (lx, ly))
        tx = lx + LOGO_PX + 60
        draw.text((tx, 238), "Common Root?", font=_font(78, bold=True), fill=INK)
        draw.text((tx, 338), TAGLINE, font=_font(33), fill=MUTE)
    else:
        # logo centered (it carries the wordmark itself)
        img.paste(logo, ((W - LOGO_PX) // 2, (H - LOGO_PX) // 2 - 20))
        tw = draw.textlength(TAGLINE, font=_font(33))
        draw.text(((W - tw) / 2, (H + LOGO_PX) // 2 - 10), TAGLINE, font=_font(33), fill=MUTE)

    img.save(OUT, "PNG")
    print(f"wrote {OUT}  ({img.size[0]}x{img.size[1]})")


if __name__ == "__main__":
    main()
