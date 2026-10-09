"""Download one time slot of geostationary IR imagery from all five satellites (free, no key).
NASA GIBS WMTS (GOES-East, GOES-West, Himawari) as 0.0703 deg tiles + EUMETSAT WMS (MTG 0 deg, MSG Indian Ocean).
usage: python fetch_sat.py 2026-10-09T07:00:00Z out_dir
"""
import sys, os, time, urllib.request, concurrent.futures as cf

T, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
GIBS = 'https://gibs.earthdata.nasa.gov/wmts/epsg4326/best/{layer}/default/{t}/2km/3/{r}/{c}.png'
SATS = {'goes_e': 'GOES-East_ABI_Band13_Clean_Infrared', 'goes_w': 'GOES-West_ABI_Band13_Clean_Infrared',
        'hima': 'Himawari_AHI_Band13_Clean_Infrared'}
EUM = ('https://view.eumetsat.int/geoserver/ows?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0&CRS=EPSG:4326'
       '&BBOX=-90,-180,90,180&WIDTH=5120&HEIGHT=2560&FORMAT=image/png&TRANSPARENT=TRUE&STYLES=&TIME={t}&LAYERS={layer}')
EUMS = {'mtg': 'mtg_fd:ir105_hrfi', 'iodc': 'msg_iodc:ir108'}

def get(url, path):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'earth-wallpaper/1.0'}), timeout=60) as r:
                data = r.read()
            open(path, 'wb').write(data)
            return len(data)
        except Exception as e:
            err = e; time.sleep(1 + attempt)
    return f'ERR {err}'

jobs = []
for k, layer in SATS.items():
    os.makedirs(f'{OUT}/{k}', exist_ok=True)
    for r in range(5):
        for c in range(10):
            jobs.append((GIBS.format(layer=layer, t=T, r=r, c=c), f'{OUT}/{k}/{r}_{c}.png'))
for k, layer in EUMS.items():
    jobs.append((EUM.format(layer=layer, t=T), f'{OUT}/{k}.png'))

t0 = time.time()
with cf.ThreadPoolExecutor(12) as ex:
    res = list(ex.map(lambda j: (j[1], get(*j)), jobs))
total = sum(v for _, v in res if isinstance(v, int))
errs = [(p, v) for p, v in res if not isinstance(v, int)]
print(f'{len(jobs)} files, {total/1e6:.1f} MB in {time.time()-t0:.1f}s, errors: {len(errs)}')
for p, v in errs[:5]: print(p, v)
