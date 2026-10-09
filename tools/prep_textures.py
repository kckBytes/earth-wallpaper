import os
import numpy as np
from PIL import Image, ImageFilter
Image.MAX_IMAGE_PIXELS = None
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, 'raw'); A = os.path.join(ROOT, 'app', 'assets'); P = os.path.join(RAW, 'peek')
SZ = (4096, 2048)

# --- city lights: warm emitters only (drop the moonlit land/ice that is baked into Black Marble)
bm = np.asarray(Image.open(os.path.join(RAW, 'blackmarble.jpg')).convert('RGB').resize(SZ, Image.LANCZOS), dtype=np.float32)
lights = np.clip((bm[..., 0] - 0.72 * bm[..., 2] - 4.0) * 1.6, 0, 255)
Image.fromarray(lights.astype(np.uint8), 'L').save(os.path.join(A, 'lights.jpg'), quality=92)

# --- water mask from Blue Marble colour (water in both Jan and Jul => no seasonal snow confusion)
def water(m):
    d = np.asarray(Image.open(os.path.join(A, 'day', '%02d.jpg' % m)), dtype=np.float32)
    r, g, b = d[..., 0], d[..., 1], d[..., 2]
    return (b > r * 1.35) & (b > g * 1.02) & (r + g + b < 420)
w = (water(1) & water(7)).astype(np.uint8) * 255
wm = Image.fromarray(w, 'L').filter(ImageFilter.MedianFilter(5)).filter(ImageFilter.GaussianBlur(1.2))
wm.save(os.path.join(A, 'water.jpg'), quality=92)

# --- bundled fallback clouds (whatever is current right now), single channel
c = Image.open(os.path.join(RAW, 'clouds.jpg')).convert('L')
c.save(os.path.join(A, 'clouds.jpg'), quality=90)

for n in ['lights.jpg', 'water.jpg']:
    Image.open(os.path.join(A, n)).resize((1024, 512), Image.LANCZOS).save(os.path.join(P, n), quality=88)
print('lights mean', lights.mean(), 'water frac', (w > 0).mean())
print(sorted(os.listdir(A)))
