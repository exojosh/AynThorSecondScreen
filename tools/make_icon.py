"""Regenerates the mod's icon: a pixel-art AYN Thor.

    python tools/make_icon.py

Drawn on a 64x64 grid and point-scaled x2 to 128, so it stays honestly
pixelated rather than a smooth image pretending to be one. 64 rather than 32
because at 32 a heart is three red pixels and reads as a smudge -- and the
second screen showing the HUD is the whole point of the picture.

Kept as a script rather than just the PNG so the icon can be adjusted without
anyone having to open a pixel editor and match the palette by eye.

Requires Pillow.
"""
import os

from PIL import Image

W = 64
SCALE = 2
OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src", "main", "resources", "assets", "aynthor_secondscreen_v1_21", "icon.png",
)

CLEAR = (0, 0, 0, 0)
OUTLINE = (22, 25, 29, 255)
BODY = (72, 79, 87, 255)
BODY_HI = (103, 111, 120, 255)
BODY_LO = (52, 57, 63, 255)
BEZEL = (16, 19, 22, 255)
SCREEN_DARK = (26, 29, 32, 255)
SKY = (124, 190, 240, 255)
CLOUD = (240, 246, 252, 255)
GRASS = (106, 168, 79, 255)
GRASS_TOP = (127, 192, 96, 255)
DIRT = (134, 96, 67, 255)
HEART = (222, 56, 56, 255)
HEART_HI = (255, 110, 110, 255)
XP = (128, 255, 32, 255)
SLOT = (139, 139, 139, 255)
SLOT_EDGE = (55, 58, 61, 255)
BTN = (206, 210, 214, 255)
BTN_LO = (146, 152, 158, 255)
STICK = (30, 33, 37, 255)
STICK_HI = (64, 69, 75, 255)

img = Image.new("RGBA", (W, W), CLEAR)
px = img.load()


def rect(x0, y0, x1, y1, colour):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if 0 <= x < W and 0 <= y < W:
                px[x, y] = colour


def frame(x0, y0, x1, y1, colour):
    rect(x0, y0, x1, y0, colour)
    rect(x0, y1, x1, y1, colour)
    rect(x0, y0, x0, y1, colour)
    rect(x1, y0, x1, y1, colour)


def sprite(x, y, rows, palette):
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch in palette:
                px[x + dx, y + dy] = palette[ch]


def disc(cx, cy, r, colour):
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r + 1:
                px[x, y] = colour


# --- body ------------------------------------------------------------------
# A wide slab with knocked-off corners: the Thor is a landscape handheld, not
# a phone stood on its end.
rect(3, 9, 60, 54, BODY)
rect(2, 12, 61, 51, BODY)
rect(4, 8, 59, 8, BODY)
rect(4, 55, 59, 55, BODY)

frame(2, 12, 61, 51, OUTLINE)
rect(4, 7, 59, 7, OUTLINE)
rect(4, 56, 59, 56, OUTLINE)
rect(1, 12, 1, 51, OUTLINE)
rect(62, 12, 62, 51, OUTLINE)
for x, y in ((3, 8), (60, 8), (3, 55), (60, 55), (2, 11), (61, 11), (2, 52), (61, 52)):
    px[x, y] = OUTLINE
rect(3, 9, 3, 11, BODY)
rect(60, 9, 60, 11, BODY)

rect(5, 8, 58, 9, BODY_HI)
rect(5, 53, 58, 55, BODY_LO)

# shoulder buttons over the top edge
rect(8, 4, 18, 6, BODY_HI)
frame(8, 3, 18, 6, OUTLINE)
rect(45, 4, 55, 6, BODY_HI)
frame(45, 3, 55, 6, OUTLINE)

# --- top screen: the game ---------------------------------------------------
frame(18, 12, 45, 34, BEZEL)
rect(19, 13, 44, 33, BEZEL)
rect(20, 14, 43, 32, SKY)
rect(20, 26, 43, 27, GRASS_TOP)
rect(20, 28, 43, 29, GRASS)
rect(20, 30, 43, 32, DIRT)
rect(24, 17, 29, 18, CLOUD)
rect(26, 16, 28, 16, CLOUD)
rect(34, 20, 38, 21, CLOUD)

# --- bottom screen: this mod's HUD -----------------------------------------
frame(20, 37, 43, 51, BEZEL)
rect(21, 38, 42, 50, SCREEN_DARK)

HEART_ROWS = (
    ".XX.XX.",
    "XHXXXHX",
    "XXXXXXX",
    ".XXXXX.",
    "..XXX..",
    "...X...",
)
HEART_PAL = {"X": HEART, "H": HEART_HI}
for i in range(3):
    sprite(23 + i * 7, 39, HEART_ROWS, HEART_PAL)

rect(23, 46, 40, 46, SLOT_EDGE)
rect(23, 46, 33, 46, XP)

for i in range(4):
    x = 23 + i * 5
    frame(x, 47, x + 4, 49, SLOT_EDGE)
    rect(x + 1, 48, x + 3, 48, SLOT)

# --- controls ---------------------------------------------------------------
rect(9, 17, 12, 26, BTN)
rect(6, 20, 15, 23, BTN)
rect(9, 17, 12, 17, BTN_LO)
rect(6, 20, 6, 23, BTN_LO)
frame(9, 16, 12, 27, OUTLINE)
frame(6, 20, 15, 23, OUTLINE)
for x, y in ((9, 20), (12, 20), (9, 23), (12, 23)):
    px[x, y] = BTN

for cx, cy in ((51, 17), (46, 22), (56, 22), (51, 27)):
    disc(cx, cy, 2, BTN)
    px[cx, cy - 2] = BTN_LO

for cx in (11, 52):
    disc(cx, 41, 5, OUTLINE)
    disc(cx, 41, 4, STICK)
    disc(cx - 1, 40, 2, STICK_HI)

img.resize((W * SCALE, W * SCALE), Image.NEAREST).save(OUT)
print("wrote", OUT)
