# Real-time global clouds from five geostationary satellites (prototype)

Measured 2026-10-09 07:32 UTC. All sources are free and need no key.

| Satellite | Covers | Source | Layer | Cadence | Newest frame at 07:32 |
|---|---|---|---|---|---|
| GOES-East (75.2°W) | Americas, Atlantic | NASA GIBS WMTS | GOES-East_ABI_Band13_Clean_Infrared | 10 min | 07:00 |
| GOES-West (137.2°W) | East Pacific | NASA GIBS WMTS | GOES-West_ABI_Band13_Clean_Infrared | 10 min | 07:00 |
| Himawari (140.7°E) | Asia, Australia, West Pacific | NASA GIBS WMTS | Himawari_AHI_Band13_Clean_Infrared | 10 min | 07:00 |
| MTG-I / Meteosat 0° | Europe, Africa, Atlantic | EUMETSAT view WMS | mtg_fd:ir105_hrfi | 10 min | 07:10 |
| MSG Indian Ocean (45.5°E) | India, Middle East, Indian Ocean | EUMETSAT view WMS | msg_iodc:ir108 | 15 min | 07:00 |

Delay is about 20–30 min after the scan starts. A full slot is 152 files, 24 MB at 0.07° (~7.8 km), downloaded in ~10 s.

## How the stitch works (`stitch.py`)
1. **Same physical quantity everywhere.**
   - GIBS PNGs are colour-coded brightness temperature. Inverting the published colour map gives °C.
   - EUMETSAT PNGs are grey. A grey→°C curve is fitted from binned medians against GIBS or MTG where the discs overlap at the same time slot (median error 1–2 °C).
2. **Seams.** Each pixel takes a weighted mean of every satellite that sees it, with weight = (cos viewing zenith − 0.12)⁴. The most head-on view dominates and the overlap feathers out; no visible seams.
3. **IR → clouds.** IR measures temperature, so land that cools at night looks "cloudy" and hot desert looks clear.
   - A local clear-sky temperature (warm envelope) is estimated, and cloudiness is measured as how much colder a pixel is than that.
   - Anything colder than −25 °C counts as cloud regardless.
4. **Poles.** Above ~65° the satellites see poorly, so the map fades to the 3-hourly global composite.

## Known gaps
- Low, warm clouds (marine stratocumulus) are faint in IR. Daytime visible bands could add them.
- High cold terrain (Tibet, the Andes) can read as thin cloud. A terrain-height correction is needed.
- The poles still use the 3-hour map.

## Next
- Run it every 10 minutes, keep 24 h of frames, and interpolate between them along the measured wind field.
- Ship it to the wallpaper and the web app. This needs a free host for the stitched frames, or stitching on the device.
