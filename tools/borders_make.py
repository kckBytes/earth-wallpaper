# Rasterises Natural Earth (public domain) country borders + coastlines into an equirectangular line mask.
import json, urllib.request, sys
from PIL import Image, ImageDraw
OUT = sys.argv[1]
W, H, S = 4096, 2048, 2                      # final size; drawn at 2x then downsampled for smooth lines
BASE = 'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/'
def lines(name):
    g = json.load(urllib.request.urlopen(BASE + name, timeout=60))
    for f in g['features']:
        geom = f['geometry']
        if geom is None: continue
        parts = geom['coordinates'] if geom['type'] == 'MultiLineString' else [geom['coordinates']]
        for p in parts: yield p
img = Image.new('L', (W * S, H * S), 0)
d = ImageDraw.Draw(img)
def draw(src, width, val):
    n = 0
    for p in lines(src):
        seg = []
        for lon, lat in p:
            x = (lon + 180) / 360 * W * S; y = (90 - lat) / 180 * H * S
            if seg and abs(x - seg[-1][0]) > W * S / 2:      # crosses the date line: break the stroke
                if len(seg) > 1: d.line(seg, fill=val, width=width, joint='curve')
                seg = []
            seg.append((x, y))
        if len(seg) > 1: d.line(seg, fill=val, width=width, joint='curve'); n += 1
    print(src, n, 'lines')
draw('ne_50m_coastline.geojson', 3, 200)
draw('ne_50m_admin_0_boundary_lines_land.geojson', 3, 255)
img = img.resize((W, H), Image.LANCZOS)
img.save(OUT, optimize=True)
import os; print('saved', OUT, os.path.getsize(OUT), 'bytes')
