"""Draws media/icon.png, the Marketplace icon.

The gallery needs a raster image -- an SVG is refused -- and there is no image
library or converter on this machine, so the same glyph media/grails.svg draws
for the activity bar is rasterised here with the standard library alone: a few
rectangles and a triangle, sampled 4x4 per pixel for smooth edges, deflated, and
wrapped in the three PNG chunks a decoder needs.

Deliberately not the Grails logo, which is a trademark: three bars and a play
triangle, the same "things you can run" glyph as the activity bar icon, so the
listing and the sidebar look like the same extension.

    python scripts/make-icon.py
"""

import os
import struct
import zlib

SIZE = 128
SAMPLES = 4  # per axis, so 16 samples a pixel

BACKGROUND = (0x21, 0x2A, 0x35)   # slate, dark enough for a light or dark gallery
GLYPH = (0xE6, 0xED, 0xF3)        # off-white
ACCENT = (0x59, 0xC2, 0x7A)       # the triangle, so the icon is not one flat colour

# The glyph in the 24x24 space media/grails.svg uses, scaled to fit with a margin.
BARS = [
    (3, 5, 13.5, 2),
    (3, 11, 9, 2),
    (3, 17, 9, 2),
]
TRIANGLE = [(16.25, 10.75), (16.25, 18.75), (22.75, 14.75)]

MARGIN = 0.14  # of the canvas, on every side


def to_canvas(x, y):
    """24-unit glyph space to pixel space, centred with a margin."""
    span = SIZE * (1 - 2 * MARGIN)
    return MARGIN * SIZE + x * span / 24.0, MARGIN * SIZE + y * span / 24.0


def in_bars(px, py):
    for bx, by, bw, bh in BARS:
        x0, y0 = to_canvas(bx, by)
        x1, y1 = to_canvas(bx + bw, by + bh)
        if x0 <= px <= x1 and y0 <= py <= y1:
            return True
    return False


def in_triangle(px, py):
    (ax, ay), (bx, by), (cx, cy) = [to_canvas(x, y) for x, y in TRIANGLE]

    def side(x1, y1, x2, y2):
        return (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2)

    d1, d2, d3 = side(ax, ay, bx, by), side(bx, by, cx, cy), side(cx, cy, ax, ay)
    negative = d1 < 0 or d2 < 0 or d3 < 0
    positive = d1 > 0 or d2 > 0 or d3 > 0
    return not (negative and positive)


def blend(base, over, coverage):
    return tuple(int(round(b + (o - b) * coverage)) for b, o in zip(base, over))


def rows():
    step = 1.0 / SAMPLES
    for y in range(SIZE):
        row = bytearray()
        for x in range(SIZE):
            bar_hits = 0
            triangle_hits = 0
            for sy in range(SAMPLES):
                for sx in range(SAMPLES):
                    px = x + (sx + 0.5) * step
                    py = y + (sy + 0.5) * step
                    if in_bars(px, py):
                        bar_hits += 1
                    elif in_triangle(px, py):
                        triangle_hits += 1
            total = float(SAMPLES * SAMPLES)
            colour = BACKGROUND
            if triangle_hits:
                colour = blend(colour, ACCENT, triangle_hits / total)
            if bar_hits:
                colour = blend(colour, GLYPH, bar_hits / total)
            row.extend(colour)
        yield bytes(row)


def png(width, height, pixel_rows):
    def chunk(kind, data):
        return (struct.pack('>I', len(data)) + kind + data
                + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF))

    raw = b''.join(b'\x00' + row for row in pixel_rows)  # filter 0 per scanline
    header = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)  # 8-bit RGB
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', header)
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    target = os.path.join(here, '..', 'media', 'icon.png')
    with open(target, 'wb') as out:
        out.write(png(SIZE, SIZE, rows()))
    print('wrote %s (%d bytes)' % (os.path.normpath(target), os.path.getsize(target)))


if __name__ == '__main__':
    main()
