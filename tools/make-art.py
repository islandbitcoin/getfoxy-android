#!/usr/bin/env python3
"""Cut Android's launcher icon, splash icon and notification icon from the iOS art.

    tools/make-art.py <ios-clone>        (needs Pillow; run by hand, not in CI)

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
"""
import hashlib
import io
import os
import subprocess
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

ICON = "Foxy/Assets.xcassets/AppIcon.appiconset/foxy app icon.png"
STILL = "Web/foxy-splash-still.png"

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


def main():
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
