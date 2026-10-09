import os, sys, urllib.request
from PIL import Image
Image.MAX_IMAGE_PIXELS = None
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, 'raw'); os.makedirs(RAW, exist_ok=True)
ASSETS = os.path.join(ROOT, 'app', 'assets'); os.makedirs(os.path.join(ASSETS, 'day'), exist_ok=True)
NASA = 'https://eoimages.gsfc.nasa.gov/images/imagerecords/'
MONTHS = {1: 73580, 2: 73605, 3: 73630, 4: 73655, 5: 73701, 6: 73726,
          7: 73751, 8: 73776, 9: 73801, 10: 73826, 11: 73884, 12: 73909}

def get(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 100000:
        return True
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'EarthWallpaper/1.0'})
        with urllib.request.urlopen(req, timeout=120) as r, open(dest + '.part', 'wb') as f:
            f.write(r.read())
        os.replace(dest + '.part', dest)
        print('ok  ', url, os.path.getsize(dest)); return True
    except Exception as e:
        print('FAIL', url, e); return False

for m, rec in MONTHS.items():
    name = 'world.topo.bathy.2004%02d.3x5400x2700.jpg' % m
    src = os.path.join(RAW, name)
    if get(NASA + '%d/%d/%s' % (rec // 1000 * 1000, rec, name), src):
        out = os.path.join(ASSETS, 'day', '%02d.jpg' % m)
        Image.open(src).convert('RGB').resize((4096, 2048), Image.LANCZOS).save(out, quality=92, subsampling=0)

get(NASA + '144000/144898/BlackMarble_2016_3km.jpg', os.path.join(RAW, 'blackmarble.jpg'))
get('https://clouds.matteason.co.uk/images/4096x2048/specular.jpg', os.path.join(RAW, 'specular.jpg'))
get('https://clouds.matteason.co.uk/images/4096x2048/clouds.jpg', os.path.join(RAW, 'clouds.jpg'))
get('https://clouds.matteason.co.uk/images/4096x2048/earth.jpg', os.path.join(RAW, 'earth_ref.jpg'))
for f in sorted(os.listdir(RAW)):
    im = Image.open(os.path.join(RAW, f)); print(f, im.size, im.mode)
print('assets/day:', sorted(os.listdir(os.path.join(ASSETS, 'day'))))
