"""Stitch five geostationary IR images into one global cloud map (prototype).

1. Every source -> brightness temperature (deg C) on a 5120x2560 lat/lon grid + validity mask.
   GIBS PNGs are colour-coded: invert their published colour map. EUMETSAT PNGs are grey: fit grey->BT
   by regression against GIBS where the discs overlap at the same time slot.
2. Blend: each pixel takes a weighted mean of every satellite that sees it, weight = (cos of the
   satellite's viewing zenith angle)^4, so the most head-on view dominates and seams feather out.
3. BT -> cloud brightness (cold tops = bright), fill the poles from the 3-hourly global map.
"""
import sys, glob, re, json
import numpy as np
from PIL import Image

D = sys.argv[1] if len(sys.argv) > 1 else 't0700'
W, H = 5120, 2560
lon = (np.arange(W) + 0.5) / W * 360 - 180
lat = 90 - (np.arange(H) + 0.5) / H * 180
LON, LAT = np.meshgrid(np.radians(lon), np.radians(lat))

# ---------------------------------------------------------------- GIBS colour map inversion
cm = open('cmap.xml', encoding='utf-8').read()
lut = {}
for rgb, val in re.findall(r'rgb="(\d+,\d+,\d+)"[^>]*?value="([^"]+)"', cm):
    nums = [float(x) for x in re.findall(r'-?\d+(?:\.\d+)?', val)]
    if not nums: continue
    r, g, b = map(int, rgb.split(','))
    lut[(r << 16) | (g << 8) | b] = sum(nums) / len(nums)
keys = np.array(sorted(lut)); vals = np.array([lut[k] for k in keys])
pal = np.stack([(keys >> 16) & 255, (keys >> 8) & 255, keys & 255], 1).astype(np.float32)

def gibs(name):
    img = np.zeros((H, W, 4), np.uint8)
    for f in glob.glob(f'{D}/{name}/*.png'):
        r, c = map(int, re.findall(r'(\d+)_(\d+)\.png', f)[0])
        t = np.array(Image.open(f).convert('RGBA'))
        y0, x0 = r * 512, c * 512
        hh, ww = min(512, H - y0), min(512, W - x0)
        if hh > 0 and ww > 0: img[y0:y0 + hh, x0:x0 + ww] = t[:hh, :ww]
    ok = img[..., 3] > 0
    packed = (img[..., 0].astype(np.int64) << 16) | (img[..., 1].astype(np.int64) << 8) | img[..., 2]
    bt = np.full((H, W), np.nan, np.float32)
    idx = np.searchsorted(keys, packed[ok]); idx = np.clip(idx, 0, len(keys) - 1)
    exact = keys[idx] == packed[ok]
    out = np.where(exact, vals[idx], np.nan)
    if (~exact).any():   # anti-aliased / resampled colours: nearest palette colour
        rgb = img[..., :3][ok][~exact].astype(np.float32)
        best = np.empty(len(rgb), np.int64)
        for i in range(0, len(rgb), 200000):
            d = ((rgb[i:i + 200000, None, :] - pal[None]) ** 2).sum(-1); best[i:i + 200000] = d.argmin(1)
        out[~exact] = vals[best]
    bt[ok] = out
    print(f'{name}: {ok.mean()*100:.0f}% of globe, exact colour match {exact.mean()*100:.1f}%')
    return bt

def eum_grey(name):
    a = np.array(Image.open(f'{D}/{name}.png').convert('RGBA'))
    ok = a[..., 3] > 0
    g = np.full((H, W), np.nan, np.float32); g[ok] = a[..., 0][ok]
    print(f'{name}: {ok.mean()*100:.0f}% of globe (grey)')
    return g

def fit_grey(grey, ref, region):
    """Monotonic grey->BT curve from binned medians (robust to cloud-edge misregistration between satellites)."""
    m = region & np.isfinite(grey) & np.isfinite(ref)
    x, y = grey[m], ref[m]
    centers, meds = [], []
    for lo in range(0, 256, 8):
        sel = (x >= lo) & (x < lo + 8)
        if sel.sum() > 300: centers.append(lo + 3.5); meds.append(np.median(y[sel]))
    meds = np.minimum.accumulate(np.array(meds))          # colder as grey increases
    out = np.interp(grey, centers, meds, left=np.nan, right=np.nan)
    # extend linearly beyond the calibrated range
    k = (meds[-1] - meds[-4]) / (centers[-1] - centers[-4]); k0 = (meds[3] - meds[0]) / (centers[3] - centers[0])
    out = np.where(grey > centers[-1], meds[-1] + k * (grey - centers[-1]), out)
    out = np.where(grey < centers[0], meds[0] + k0 * (grey - centers[0]), out)
    fin = np.isfinite(out) & m
    print(f'   grey->BT curve from {m.sum()} px: g=32 -> {np.interp(32,centers,meds):.1f} C, g=128 -> {np.interp(128,centers,meds):.1f} C, g=220 -> {np.interp(220,centers,meds):.1f} C; median abs err {np.median(np.abs(out[fin]-ref[fin])):.1f} C')
    return out.astype(np.float32)

bt = {k: gibs(k) for k in ['goes_e', 'goes_w', 'hima']}
mtg_g, iodc_g = eum_grey('mtg'), eum_grey('iodc')
lonA = np.degrees(LON)
bt['mtg'] = fit_grey(mtg_g, bt['goes_e'], (lonA > -45) & (lonA < -15) & (np.abs(np.degrees(LAT)) < 50))
bt['iodc'] = fit_grey(iodc_g, bt['mtg'], (lonA > 20) & (lonA < 35) & (np.abs(np.degrees(LAT)) < 45))

# ---------------------------------------------------------------- blend by viewing angle
SUBLON = {'goes_e': -75.2, 'goes_w': -137.2, 'hima': 140.7, 'mtg': 0.0, 'iodc': 45.5}
num = np.zeros((H, W), np.float64); den = np.zeros((H, W), np.float64); best = np.full((H, W), -1, np.int8)
bestw = np.zeros((H, W))
for i, (k, v) in enumerate(bt.items()):
    cosg = np.cos(LAT) * np.cos(LON - np.radians(SUBLON[k]))     # cos of Earth-centre angle to sub-satellite point
    # satellite zenith from the ground: geostationary radius 6.62 Earth radii
    r = 6.6227; d = np.sqrt(1 + r * r - 2 * r * cosg); cosz = (r * cosg - 1) / d
    w = np.clip(cosz - 0.12, 0, None) ** 4
    w = np.where(np.isfinite(v), w, 0)
    num += w * np.nan_to_num(v); den += w
    better = w > bestw; best[better] = i; bestw = np.maximum(bestw, w)
BT = np.where(den > 0, num / np.maximum(den, 1e-9), np.nan)
cover = den > 1e-4
print(f'covered by geostationary: {cover.mean()*100:.1f}% of the grid; lat range {np.degrees(LAT[cover.any(1)]).min():.0f}..{np.degrees(LAT[cover.any(1)]).max():.0f}')

# ---------------------------------------------------------------- BT -> cloud brightness, polar fill
old = np.array(Image.open('clouds_3h.jpg').convert('L').resize((W, H), Image.BILINEAR), np.float32) / 255
# IR sees temperature, not clouds: at night land cools and looks "cloudy"; by day deserts are hot.
# Estimate the clear-sky temperature locally (warm envelope: 95th percentile in ~4 deg windows, smoothed)
# and call a pixel cloudy by how much colder than its surroundings' clear sky it is.
from scipy import ndimage
cs = 16                                             # 16 px = 1.1 deg blocks
bt_b = np.where(np.isfinite(BT), BT, -999).reshape(H // cs, cs, W // cs, cs)
warm = np.percentile(bt_b, 95, axis=(1, 3))
warm = np.where(warm < -200, np.nan, warm)
filled = np.where(np.isfinite(warm), warm, -999)
env = ndimage.maximum_filter(filled, size=(3, 5), mode=('nearest', 'wrap'))   # ~3 x 5.5 deg
env = ndimage.uniform_filter(np.where(env < -200, np.nan_to_num(np.nanmean(warm)), env), size=(3, 5), mode=('nearest', 'wrap'))
TCLEAR = np.array(Image.fromarray(env.astype(np.float32)).resize((W, H), Image.BILINEAR))
TCLEAR = np.maximum(TCLEAR, np.where(np.isfinite(BT), BT, -999))
deficit = TCLEAR - BT                                # K colder than clear sky
idx = np.clip((deficit - 6) / 44, 0, 1)             # 6 K margin for noise; 50 K below clear sky = thick
idx = ndimage.median_filter(np.nan_to_num(idx), size=3)   # drop single-pixel speckle
idx = np.maximum(idx, np.clip((-25 - BT) / 35, 0, 1))  # anything colder than -25 C is cloud regardless
idx = np.where(np.isfinite(BT), idx, np.nan)
# Encode for the shader's ramp, c = (raw - 0.40) / 0.56: clear sky sits just below the ramp.
raw = np.where(idx > 0, 0.40 + 0.56 * idx ** 0.7, 0.38)
raw = np.where(np.isfinite(idx), raw, np.nan)
wfill = np.clip(den / 1e-2, 0, 1)                 # fade to the 3-hour global map where no satellite sees well
out = np.where(np.isfinite(raw), raw, old) * wfill + old * (1 - wfill)
Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8)).save('stitched.png')
Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8)).resize((4096, 2048), Image.LANCZOS).save('stitched_4096.jpg', quality=90)
# a map of which satellite won each pixel, for checking the seams
cols = np.array([[255, 90, 90], [90, 160, 255], [255, 210, 60], [90, 220, 120], [200, 120, 255]], np.uint8)
srcmap = np.where(best[..., None] >= 0, cols[np.clip(best, 0, 4)], 0).astype(np.uint8)
Image.fromarray(srcmap).resize((1280, 640)).save('sources.png')
print('saved stitched.png, stitched_4096.jpg, sources.png')
