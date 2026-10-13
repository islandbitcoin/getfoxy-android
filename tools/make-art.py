#!/usr/bin/env python3
"""Cut Android's launcher icon, splash icon and notification icon from the iOS art.

    tools/make-art.py <ios-clone>        (needs Pillow; run by hand, not in CI)
    tools/make-art.py --store <ios-clone> [font]
                                         the Play listing's graphics only (docs/store/graphics)

The sources are read from the getfoxy/iOS commit in core/PIN with `git show`,
so the clone's working tree is never touched. app/art/NOTICE.md lists each
source with its sha256 and the upstream licence; ArtTest checks every file
this writes (sizes, the splash crop against core/web, the white-only small
icon, the safe zones).

  - Foxy/Assets.xcassets/AppIcon.appiconset/foxy app icon.png (1024 px, white
    fox head on black): the adaptive icon's foreground and monochrome layers,
    the notification's small icon and the Play Store's 512 px icon.
  - Web/foxy-splash-still.png (600 x 1304, the intro's first frame): the
    splash icon, a 600 px square around the sleeping fox, unscaled.
  - Web/foxy-intro-last.png (600 x 1304, the intro's last frame, the standing
    fox): the Play feature graphic's fox (--store).

--store writes docs/store/graphics/icon-512.png (the app icon at 512 px,
opaque RGBA: Play masks the corners itself) and
docs/store/graphics/feature-graphic-1024x500.png (the standing fox on the
page's background with the name "Foxy Wallet" in Inter Display SemiBold, the
font file given, or ~/Library/Fonts/InterDisplay-SemiBold.otf).
"""
import hashlib
import io
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

ICON = "Foxy/Assets.xcassets/AppIcon.appiconset/foxy app icon.png"
STILL = "Web/foxy-splash-still.png"
STANDING = "Web/foxy-intro-last.png"

STORE = os.path.join(ROOT, "docs", "store", "graphics")
PAGE = (0x0B, 0x0B, 0x0C)  # res/values/colors.xml "page"
INK = (0xF5, 0xF1, 0xEC)  # "ink"
ACCENT = (0xF2, 0x80, 0x2E)  # "accent"
# the standing fox in the last frame (x 269-456, y 474-742), with a few px of black around it
STANDING_BOX = (262, 466, 464, 750)

# The adaptive icon is 108 dp, of which a launcher shows at most the middle
# 72 dp (18 dp to 90 dp) and always the 66 dp circle in the middle. The art
# (head and neck, the neck cut by its bottom edge) is drawn 64 dp square,
# centred, its bottom on the 90 dp line, so the ear tips sit on the safe
# circle; its last row is carried on to the canvas's bottom edge, so the neck
# runs off the bottom of every mask as it runs off the iOS icon.
ADAPTIVE_DP = 108
ART_DP = 64
ART_BOTTOM_DP = 90

# The small icon is 24 dp; the head (the icon's top 824 px, centred) is drawn 22 dp.
NOTIFY_DP = 24
NOTIFY_ART_DP = 22
HEAD_BOX = (100, 76, 924, 900)

# The splash icon: 600 px square centred on the fox (its box in the still is
# x 122-477, y 584-720, centred on 300, 652), so the fox sits inside the
# middle two thirds Android 12+ shows of a splash icon.
SPLASH_BOX = (0, 352, 600, 952)


def git_show(clone, commit, path):
    return subprocess.run(["git", "-C", clone, "show", f"{commit}:{path}"], check=True, capture_output=True).stdout


def white_on_clear(image):
    """The art is grey on black: white, as opaque as it is bright (over black it is the same picture)."""
    grey = image.convert("L")
    out = Image.new("RGBA", image.size, (255, 255, 255, 0))
    out.putalpha(grey)
    return out


def resized(image, size):
    """A resize leaves the art's solid white at 254 here and there: anything that close is solid again."""
    out = image.resize(size, Image.LANCZOS)
    out.putalpha(out.getchannel("A").point(lambda a: 255 if a >= 250 else a))
    return out


def save(image, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, optimize=True)
    print(os.path.relpath(path, ROOT))


def store(clone, commit, font_path):
    icon_bytes = git_show(clone, commit, ICON)
    standing_bytes = git_show(clone, commit, STANDING)
    print(f"{ICON} sha256 {hashlib.sha256(icon_bytes).hexdigest()}")
    print(f"{STANDING} sha256 {hashlib.sha256(standing_bytes).hexdigest()}")
    icon = Image.open(io.BytesIO(icon_bytes)).convert("RGBA")
    save(icon.resize((512, 512), Image.LANCZOS), os.path.join(STORE, "icon-512.png"))

    width, height = 1024, 500
    art = Image.new("RGBA", (width, height), PAGE + (255,))
    fox = white_on_clear(Image.open(io.BytesIO(standing_bytes)).convert("RGB").crop(STANDING_BOX))
    fox_h = 420
    fox_w = round(fox.width * fox_h / fox.height)
    fox = resized(fox, (fox_w, fox_h))
    art.alpha_composite(fox, (width - 96 - fox_w, (height - fox_h) // 2))

    draw = ImageDraw.Draw(art)
    font = ImageFont.truetype(font_path, 104)
    left = 80
    top = 150
    draw.text((left, top), "Foxy", font=font, fill=INK + (255,))
    draw.text((left, top + 116), "Wallet", font=font, fill=INK + (255,))
    draw.rectangle((left + 4, top - 34, left + 84, top - 24), fill=ACCENT + (255,))
    save(art.convert("RGB"), os.path.join(STORE, "feature-graphic-1024x500.png"))


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "--store":
        commit = next(l.split("=", 1)[1].strip() for l in open(os.path.join(ROOT, "core", "PIN")) if l.startswith("commit="))
        font = sys.argv[3] if len(sys.argv) > 3 else os.path.expanduser("~/Library/Fonts/InterDisplay-SemiBold.otf")
        return store(sys.argv[2], commit, font)
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    clone = sys.argv[1]
    commit = next(l.split("=", 1)[1].strip() for l in open(os.path.join(ROOT, "core", "PIN")) if l.startswith("commit="))
    icon_bytes = git_show(clone, commit, ICON)
    still_bytes = git_show(clone, commit, STILL)
    print(f"{ICON} sha256 {hashlib.sha256(icon_bytes).hexdigest()}")
    print(f"{STILL} sha256 {hashlib.sha256(still_bytes).hexdigest()}")
    icon = Image.open(io.BytesIO(icon_bytes)).convert("RGB")
    still = Image.open(io.BytesIO(still_bytes)).convert("RGB")
    fox = white_on_clear(icon)

    for name, d in DENSITIES.items():
        canvas = round(ADAPTIVE_DP * d)
        art = round(ART_DP * d)
        layer = Image.new("RGBA", (canvas, canvas), (255, 255, 255, 0))
        bottom = round(ART_BOTTOM_DP * d)
        drawn = resized(fox, (art, art))
        left = (canvas - art) // 2
        layer.alpha_composite(drawn, (left, bottom - art))
        # the bottom row of a resize is softened by the edge; the row three up is the neck itself
        last = drawn.crop((0, art - 3, art, art - 2))
        for y in range(bottom - 2, canvas):
            layer.alpha_composite(last, (left, y))
        save(layer, os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"))

        small = round(NOTIFY_DP * d)
        glyph = round(NOTIFY_ART_DP * d)
        head = resized(fox.crop(HEAD_BOX), (glyph, glyph))
        layer = Image.new("RGBA", (small, small), (255, 255, 255, 0))
        layer.alpha_composite(head, ((small - glyph) // 2, (small - glyph) // 2))
        save(layer, os.path.join(RES, f"drawable-{name}", "ic_notify.png"))

    save(still.crop(SPLASH_BOX), os.path.join(RES, "drawable-nodpi", "splash_fox.png"))
    save(icon.resize((512, 512), Image.LANCZOS), os.path.join(ROOT, "app", "src", "main", "ic_launcher-playstore.png"))


if __name__ == "__main__":
    main()
