"""
Generate Google Play Store assets:
1. App icon: 512x512 PNG from the existing adaptive icon vector
2. Feature graphic: resize to exactly 1024x500 PNG
"""

from PIL import Image, ImageDraw, ImageFont
import math
import os

OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "store_assets")
os.makedirs(OUTPUT_DIR, exist_ok=True)

# ─── 1. APP ICON (512x512) ────────────────────────────────────────
# Recreate the adaptive icon from ic_launcher_foreground.xml + ic_launcher_background.xml
# Background: gradient #FF6B5CFF → #FF3D30CC at 135°
# Foreground: white keycap with purple "T", ripple arcs, tap dot

SIZE = 512
SAFE_ZONE = 72  # 72dp inset from each side (maps from 18dp in 108dp space)

img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# --- Background: purple gradient at 135 degrees ---
start_color = (0x6B, 0x5C, 0xFF)  # #6B5CFF
end_color = (0x3D, 0x30, 0xCC)    # #3D30CC

for y in range(SIZE):
    for x in range(SIZE):
        # 135° gradient: top-right to bottom-left
        t = ((x / SIZE) + (1 - y / SIZE)) / 2
        r = int(start_color[0] + (end_color[0] - start_color[0]) * (1 - t))
        g = int(start_color[1] + (end_color[1] - start_color[1]) * (1 - t))
        b = int(start_color[2] + (end_color[2] - start_color[2]) * (1 - t))
        img.putpixel((x, y), (r, g, b, 255))

# Scale factor: the vector is 108dp, we map the inner 72dp content area
# The foreground group is translated by (18, 18) in the 108dp space
# So content starts at 18dp and the 72dp maps to the full 512px
# But for the icon, we want the full 108dp → 512px
scale = SIZE / 108.0
offset_x = 18 * scale  # group translateX
offset_y = 18 * scale  # group translateY

def s(v):
    """Scale a coordinate from the 72dp content space to pixel space"""
    return v * scale

# --- Foreground elements ---

# Key shadow/depth - dark purple
shadow_x = s(8) + offset_x
shadow_y = s(20) + offset_y  
shadow_w = s(40)  # 48-8
shadow_h = s(42)  # 62-20
shadow_r = s(6)
draw.rounded_rectangle(
    [shadow_x, shadow_y + s(2), shadow_x + shadow_w, shadow_y + shadow_h + s(2)],
    radius=int(shadow_r),
    fill=(0x3D, 0x2E, 0xB3, 255)
)

# Key top surface - white
key_x = s(8) + offset_x
key_y = s(18) + offset_y
key_w = s(40)
key_h = s(42)  # 60-18
key_r = s(6)
draw.rounded_rectangle(
    [key_x, key_y, key_x + key_w, key_y + key_h],
    radius=int(key_r),
    fill=(255, 255, 255, 255)
)

# Key bottom shadow band (subtle darkening at bottom of key)
band_y = s(50) + offset_y
draw.rounded_rectangle(
    [key_x, band_y, key_x + key_w, key_y + key_h],
    radius=int(key_r),
    fill=(0, 0, 0, 32)  # 20% black overlay
)

# Letter "T" - purple
t_color = (0x5B, 0x4C, 0xFF, 255)
# Top bar of T: from (20,34) to (36,38)
t_top_x = s(20) + offset_x
t_top_y = s(34) + offset_y
t_top_w = s(16)  # 36-20
t_top_h = s(4)   # 38-34
draw.rectangle([t_top_x, t_top_y, t_top_x + t_top_w, t_top_y + t_top_h], fill=t_color)

# Stem of T: from (25.5, 38) to (30.5, 52)
t_stem_x = s(25.5) + offset_x
t_stem_y = s(38) + offset_y
t_stem_w = s(5)   # 30.5-25.5
t_stem_h = s(14)  # 52-38
draw.rectangle([t_stem_x, t_stem_y, t_stem_x + t_stem_w, t_stem_y + t_stem_h], fill=t_color)

# Tap ripple arcs (outer) - semi-transparent white
arc_color_outer = (255, 255, 255, 85)  # #55FFFFFF
arc_color_inner = (255, 255, 255, 136) # #88FFFFFF

# Outer arc
for angle_step in range(0, 90, 1):
    angle = math.radians(angle_step - 90)  # from top to right
    cx = s(36) + offset_x + math.cos(angle) * s(22)
    cy = s(10) + offset_y + s(20) + math.sin(angle) * s(20)
    for w in range(int(s(2.5))):
        a = math.radians(angle_step - 90)
        px = s(36) + offset_x + math.cos(a) * (s(20) + w)
        py = s(10) + offset_y + s(10) + math.sin(a) * (s(18) + w)

# Simplified arcs using ellipse
outer_arc_bbox = [s(36) + offset_x - s(22), s(10) + offset_y - s(20),
                  s(36) + offset_x + s(22), s(10) + offset_y + s(20)]
draw.arc(outer_arc_bbox, start=-45, end=45, fill=(255, 255, 255, 120), width=int(s(2.5)))

inner_arc_bbox = [s(36) + offset_x - s(14), s(18) + offset_y - s(12),
                  s(36) + offset_x + s(14), s(18) + offset_y + s(12)]
draw.arc(inner_arc_bbox, start=-45, end=45, fill=(255, 255, 255, 180), width=int(s(2.5)))

# Tap dot - semi-transparent white
dot_cx = s(54) + offset_x
dot_cy = s(58) + offset_y
dot_r = s(3)
draw.ellipse([dot_cx - dot_r, dot_cy - dot_r, dot_cx + dot_r, dot_cy + dot_r],
             fill=(255, 255, 255, 136))

# Make it circular (mask with circle)
mask = Image.new("L", (SIZE, SIZE), 0)
mask_draw = ImageDraw.Draw(mask)
mask_draw.ellipse([0, 0, SIZE, SIZE], fill=255)
img.putalpha(mask)

# Save
icon_path = os.path.join(OUTPUT_DIR, "app_icon_512x512.png")
img.save(icon_path, "PNG")
print(f"✅ App icon saved: {icon_path}")
print(f"   Size: {os.path.getsize(icon_path)} bytes")

# ─── 2. FEATURE GRAPHIC (1024x500) ────────────────────────────────
# Find the generated feature graphic and resize it
feature_src = None
brain_dir = os.path.expanduser("~/.gemini/antigravity/brain")
for root, dirs, files in os.walk(brain_dir):
    for f in files:
        if "feature_graphic" in f and f.endswith(".png"):
            feature_src = os.path.join(root, f)
            break
    if feature_src:
        break

if feature_src:
    feat_img = Image.open(feature_src)
    print(f"\n📐 Original feature graphic: {feat_img.size}")
    feat_resized = feat_img.resize((1024, 500), Image.LANCZOS)
    feat_path = os.path.join(OUTPUT_DIR, "feature_graphic_1024x500.png")
    feat_resized.save(feat_path, "PNG")
    print(f"✅ Feature graphic saved: {feat_path}")
    print(f"   Size: {os.path.getsize(feat_path)} bytes ({os.path.getsize(feat_path) / 1024 / 1024:.2f} MB)")
else:
    print("⚠️  Could not find feature graphic source image")

print(f"\n📁 All assets saved to: {OUTPUT_DIR}")
