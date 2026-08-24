#!/usr/bin/env python3
"""Generate simple launcher icon PNGs for all densities."""
from PIL import Image, ImageDraw
import os

BG_COLOR = "#6750A4"
FG_COLOR = "#FFFFFF"

densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

res_base = "/data/workspace/habitflow/app/src/main/res"

for density, size in densities.items():
    img = Image.new("RGBA", (size, size), BG_COLOR)
    draw = ImageDraw.Draw(img)

    center = size // 2
    bar_w = size // 8
    bar_h = size // 3
    bar_offset = size // 5

    # Left vertical bar
    x0 = center - bar_offset - bar_w
    x1 = center - bar_offset
    y0 = center - bar_h
    y1 = center + bar_h
    draw.rectangle([x0, y0, x1, y1], fill=FG_COLOR)

    # Right vertical bar
    x0 = center + bar_offset
    x1 = center + bar_offset + bar_w
    draw.rectangle([x0, y0, x1, y1], fill=FG_COLOR)

    # Horizontal bar (middle)
    y_mid = center - bar_w // 2
    draw.rectangle([
        center - bar_offset - bar_w,
        y_mid,
        center + bar_offset + bar_w,
        y_mid + bar_w
    ], fill=FG_COLOR)

    dpath = os.path.join(res_base, density)
    os.makedirs(dpath, exist_ok=True)

    p1 = os.path.join(dpath, "ic_launcher.png")
    p2 = os.path.join(dpath, "ic_launcher_round.png")
    img.save(p1)
    img.save(p2)
    print(f"Generated {density}: {size}x{size}")

print("All icons generated successfully!")
