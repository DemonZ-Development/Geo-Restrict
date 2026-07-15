"""Generate GeoRestrict's raster brand kit with Pillow.

The artwork intentionally uses simple, reproducible geometry so the project
does not depend on an opaque design source or an SVG toolchain. Run this file
from the repository root after changing the palette or mark.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFilter, ImageFont
except ImportError:
    print("Pillow is not installed. Run: python -m pip install pillow")
    sys.exit(1)


ROOT = Path(__file__).resolve().parent
ASSET_DIR = ROOT / "docs" / "assets"

INK = "#17324D"
DEEP_INK = "#10263C"
CREAM = "#FFF9EE"
PAPER = "#F7F1E5"
TEAL = "#2E8B7D"
TEAL_LIGHT = "#8CD5C7"
CORAL = "#EE6B5F"
CORAL_LIGHT = "#FFB4A9"
GOLD = "#F2B84B"
SKY = "#6FA7D8"
WHITE = "#FFFFFF"
BRAND_MARK_SIZE = 640


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    names = (
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
        if bold
        else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    )
    for name in names:
        if os.path.exists(name):
            return ImageFont.truetype(name, size)
    return ImageFont.load_default()


def fitted_font(
    draw: ImageDraw.ImageDraw,
    text: str,
    initial_size: int,
    max_width: int,
    bold: bool = False,
) -> ImageFont.FreeTypeFont:
    """Return the largest project font that keeps *text* within max_width."""
    size = initial_size
    candidate = font(size, bold=bold)
    while size > 18 and draw.textlength(text, font=candidate) > max_width:
        size -= 2
        candidate = font(size, bold=bold)
    return candidate


def hex_rgba(value: str, alpha: int = 255) -> tuple[int, int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4)) + (alpha,)


def add_soft_shadow(
    canvas: Image.Image,
    box: tuple[int, int, int, int],
    radius: int,
    blur: int,
    offset: tuple[int, int] = (0, 18),
    alpha: int = 54,
) -> None:
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x0, y0, x1, y1 = box
    ox, oy = offset
    d.rounded_rectangle((x0 + ox, y0 + oy, x1 + ox, y1 + oy), radius, fill=(16, 38, 60, alpha))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def draw_penguin_mark(size: int = 1024, transparent: bool = True) -> Image.Image:
    """Return the penguin + location shield mark at the requested size."""
    scale = 3
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0) if transparent else hex_rgba(CREAM))
    d = ImageDraw.Draw(img)

    # A map-pin shield makes the geographic/security meaning readable even at
    # thumbnail size. The friendly penguin keeps the mark from feeling severe.
    cx = s // 2
    shield = [
        (cx, int(s * 0.075)),
        (int(s * 0.83), int(s * 0.20)),
        (int(s * 0.80), int(s * 0.60)),
        (cx, int(s * 0.91)),
        (int(s * 0.20), int(s * 0.60)),
        (int(s * 0.17), int(s * 0.20)),
    ]
    # Keep the mark flat and fully opaque. A translucent halo changes colour
    # when the PNG is viewed on a dark background and made the standalone logo
    # look different from the same mark inside the cream banner.
    d.polygon(shield, fill=hex_rgba(TEAL))
    inner = [(cx + int((x - cx) * 0.87), int(s * 0.49 + (y - s * 0.49) * 0.87)) for x, y in shield]
    d.polygon(inner, fill=hex_rgba(CREAM))

    # Penguin body and flippers.
    d.ellipse((int(s * 0.30), int(s * 0.25), int(s * 0.70), int(s * 0.79)), fill=hex_rgba(DEEP_INK))
    d.ellipse((int(s * 0.22), int(s * 0.43), int(s * 0.39), int(s * 0.70)), fill=hex_rgba(INK))
    d.ellipse((int(s * 0.61), int(s * 0.43), int(s * 0.78), int(s * 0.70)), fill=hex_rgba(INK))
    d.ellipse((int(s * 0.36), int(s * 0.39), int(s * 0.64), int(s * 0.73)), fill=hex_rgba(WHITE))

    # Face, eyes and a slightly asymmetrical beak for warmth.
    d.ellipse((int(s * 0.385), int(s * 0.315), int(s * 0.485), int(s * 0.46)), fill=hex_rgba(WHITE))
    d.ellipse((int(s * 0.515), int(s * 0.315), int(s * 0.615), int(s * 0.46)), fill=hex_rgba(WHITE))
    d.ellipse((int(s * 0.440), int(s * 0.365), int(s * 0.468), int(s * 0.405)), fill=hex_rgba(DEEP_INK))
    d.ellipse((int(s * 0.532), int(s * 0.365), int(s * 0.560), int(s * 0.405)), fill=hex_rgba(DEEP_INK))
    d.polygon(
        [(int(s * 0.462), int(s * 0.425)), (int(s * 0.555), int(s * 0.445)), (int(s * 0.475), int(s * 0.485))],
        fill=hex_rgba(CORAL),
    )

    # Gold feet and a coral location badge on the chest.
    d.ellipse((int(s * 0.32), int(s * 0.70), int(s * 0.49), int(s * 0.77)), fill=hex_rgba(GOLD))
    d.ellipse((int(s * 0.51), int(s * 0.70), int(s * 0.68), int(s * 0.77)), fill=hex_rgba(GOLD))
    badge_r = int(s * 0.068)
    badge_cx, badge_cy = cx, int(s * 0.60)
    d.ellipse((badge_cx - badge_r, badge_cy - badge_r, badge_cx + badge_r, badge_cy + badge_r), fill=hex_rgba(CORAL))
    d.ellipse(
        (badge_cx - badge_r // 3, badge_cy - badge_r // 3, badge_cx + badge_r // 3, badge_cy + badge_r // 3),
        fill=hex_rgba(CREAM),
    )
    d.polygon(
        [(badge_cx - badge_r // 2, badge_cy + badge_r // 2), (badge_cx + badge_r // 2, badge_cy + badge_r // 2), (badge_cx, badge_cy + badge_r)],
        fill=hex_rgba(CORAL),
    )

    return img.resize((size, size), Image.Resampling.LANCZOS)


def generate_icon() -> Image.Image:
    icon = draw_penguin_mark(1024)
    icon.save(ROOT / "georestrict-icon.png", "PNG", optimize=True)
    icon.save(ASSET_DIR / "georestrict-icon.png", "PNG", optimize=True)
    return icon


def generate_wordmark(icon: Image.Image) -> Image.Image:
    size = (2400, 800)
    scale = 2
    img = Image.new("RGBA", (size[0] * scale, size[1] * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    mark = icon.resize((BRAND_MARK_SIZE * scale, BRAND_MARK_SIZE * scale), Image.Resampling.LANCZOS)
    img.alpha_composite(mark, (45 * scale, 80 * scale))

    x = 700 * scale
    title_font = font(248 * scale, bold=True)
    sub_font = font(57 * scale, bold=True)
    y = 178 * scale
    d.text((x, y), "Geo", font=title_font, fill=hex_rgba(INK))
    geo_w = d.textlength("Geo", font=title_font)
    d.text((x + geo_w, y), "Restrict", font=title_font, fill=hex_rgba(CORAL))
    d.rounded_rectangle((x, 515 * scale, (x + 154 * scale), 537 * scale), 11 * scale, fill=hex_rgba(GOLD))
    d.text((x + 185 * scale, 485 * scale), "GEOGRAPHIC ACCESS RULES FOR MINECRAFT", font=sub_font, fill=hex_rgba(TEAL))

    final = img.resize(size, Image.Resampling.LANCZOS)
    final.save(ROOT / "georestrict-logo.png", "PNG", optimize=True)
    return final


def generate_banner(icon: Image.Image) -> Image.Image:
    size = (2400, 960)
    scale = 2
    w, h = size[0] * scale, size[1] * scale
    img = Image.new("RGBA", (w, h), hex_rgba(TEAL_LIGHT))
    d = ImageDraw.Draw(img)

    # Colour lives outside one shared panel; the mascot and every word live inside it.
    d.ellipse((-360 * scale, 470 * scale, 860 * scale, 1450 * scale), fill=hex_rgba(GOLD))
    d.ellipse((1810 * scale, -560 * scale, 2900 * scale, 500 * scale), fill=hex_rgba(CORAL_LIGHT))
    panel = (62 * scale, 62 * scale, w - 62 * scale, h - 62 * scale)
    add_soft_shadow(img, panel, 64 * scale, 30 * scale, (0, 18 * scale), 48)
    d.rounded_rectangle(panel, radius=64 * scale, fill=hex_rgba(CREAM), outline=hex_rgba(INK), width=5 * scale)

    # A few broad colour washes and hairline paper fibres add warmth without a dot pattern.
    texture = Image.new("RGBA", img.size, (0, 0, 0, 0))
    td = ImageDraw.Draw(texture)
    td.ellipse((1430 * scale, -80 * scale, 2420 * scale, 450 * scale), fill=hex_rgba(CORAL_LIGHT, 30))
    td.ellipse((570 * scale, 670 * scale, 1450 * scale, 1080 * scale), fill=hex_rgba(GOLD, 22))
    td.polygon(
        [
            (980 * scale, 120 * scale),
            (1530 * scale, 95 * scale),
            (1460 * scale, 225 * scale),
            (1010 * scale, 245 * scale),
        ],
        fill=hex_rgba(TEAL_LIGHT, 24),
    )
    for index, y in enumerate(range(130 * scale, 850 * scale, 58 * scale)):
        offset = 8 * scale if index % 2 else 0
        td.line(
            [
                (105 * scale, y),
                (680 * scale, y + offset),
                (1320 * scale, y - offset),
                (2290 * scale, y + offset // 2),
            ],
            fill=hex_rgba(INK, 7),
            width=2 * scale,
        )
    img.alpha_composite(texture)
    d = ImageDraw.Draw(img)

    # Paste the same flattened mark at the same final size used by the wordmark.
    # The compact 50px hand-off to the copy avoids the old empty divider gutter.
    mark = icon.resize((BRAND_MARK_SIZE * scale, BRAND_MARK_SIZE * scale), Image.Resampling.LANCZOS)
    img.alpha_composite(mark, (70 * scale, 160 * scale))

    tx = 760 * scale
    right_edge = w - 110 * scale
    eyebrow_text = "GEORESTRICT FOR MINECRAFT NETWORKS"
    headline_one = "Know where players connect from."
    headline_two = "Choose how your server responds."
    body_one = "Country, ASN and VPN/proxy rules for Bukkit, Paper, Folia,"
    body_two = "BungeeCord and Velocity — on the server or at the proxy."
    eyebrow_font = fitted_font(d, eyebrow_text, 39 * scale, right_edge - tx, bold=True)
    title_font = fitted_font(d, headline_two, 90 * scale, right_edge - tx, bold=True)
    body_font = fitted_font(d, body_one, 35 * scale, right_edge - tx)
    chip_font = font(32 * scale, bold=True)
    d.text((tx, 132 * scale), eyebrow_text, font=eyebrow_font, fill=hex_rgba(TEAL))
    d.text((tx, 230 * scale), headline_one, font=title_font, fill=hex_rgba(INK))
    d.text((tx, 345 * scale), headline_two, font=title_font, fill=hex_rgba(CORAL))
    d.text((tx, 500 * scale), body_one, font=body_font, fill=hex_rgba(INK, 218))
    d.text((tx, 552 * scale), body_two, font=body_font, fill=hex_rgba(INK, 218))

    chips = [("ONE JAR", TEAL), ("SERVER OR PROXY", CORAL), ("GATEWAY CODE INCLUDED", GOLD)]
    chip_x = tx
    for label, color in chips:
        text_w = d.textlength(label, font=chip_font)
        box = (chip_x, 688 * scale, chip_x + text_w + 58 * scale, 758 * scale)
        d.rounded_rectangle(box, radius=35 * scale, fill=hex_rgba(color))
        d.text((chip_x + 29 * scale, 700 * scale), label, font=chip_font, fill=hex_rgba(WHITE if color != GOLD else INK))
        chip_x = box[2] + 20 * scale

    final = img.resize(size, Image.Resampling.LANCZOS)
    final.save(ROOT / "georestrict-banner.png", "PNG", optimize=True)
    final.save(ASSET_DIR / "georestrict-banner.png", "PNG", optimize=True)
    return final


def main() -> None:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    icon = generate_icon()
    generate_wordmark(icon)
    generate_banner(icon)
    print("Generated Pillow brand kit:")
    for path in (
        ROOT / "georestrict-icon.png",
        ROOT / "georestrict-logo.png",
        ROOT / "georestrict-banner.png",
        ASSET_DIR / "georestrict-icon.png",
        ASSET_DIR / "georestrict-banner.png",
    ):
        print(f"  {path}")


if __name__ == "__main__":
    main()
