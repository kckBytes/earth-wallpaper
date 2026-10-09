$ProgressPreference = 'SilentlyContinue'
$out = 'C:\Users\chait\EarthWallpaper\raw\feeds'
New-Item -ItemType Directory -Force $out | Out-Null
function probe($name, $url, $file) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing $url -TimeoutSec 40 -OutFile "$out\$file" -PassThru
        "{0,-28} {1} {2,9} bytes  {3}  lastmod={4}" -f $name, $r.StatusCode, (Get-Item "$out\$file").Length, $r.Headers['Content-Type'], $r.Headers['Last-Modified']
    } catch { "{0,-28} ERR {1}" -f $name, $_.Exception.Message }
}
# NASA GIBS WMS (EPSG:4326 = equirectangular), geostationary IR, 10-min cadence; default TIME = latest
$gibs = 'https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0&CRS=EPSG:4326&BBOX=-90,-180,90,180&WIDTH=2048&HEIGHT=1024&FORMAT=image/png&STYLES=&TRANSPARENT=TRUE&LAYERS='
probe 'GIBS GOES-East IR'   ($gibs + 'GOES-East_ABI_Band13_Clean_Infrared') 'goes_east.png'
probe 'GIBS GOES-West IR'   ($gibs + 'GOES-West_ABI_Band13_Clean_Infrared') 'goes_west.png'
probe 'GIBS Himawari IR'    ($gibs + 'Himawari_AHI_Band13_Clean_Infrared') 'himawari.png'
# GIBS capabilities: find the latest time step for GOES-East
try {
  $cap = (Invoke-WebRequest -UseBasicParsing 'https://gibs.earthdata.nasa.gov/wmts/epsg4326/best/GOES-East_ABI_Band13_Clean_Infrared/default/WMTSCapabilities.xml' -TimeoutSec 40).Content
} catch { $cap = '' }
if (-not $cap) { try { $cap = (Invoke-WebRequest -UseBasicParsing 'https://gibs.earthdata.nasa.gov/wmts/epsg4326/best/1.0.0/WMTSCapabilities.xml' -TimeoutSec 60).Content } catch { $cap = '' } }
$i = $cap.IndexOf('GOES-East_ABI_Band13_Clean_Infrared')
if ($i -ge 0) { $seg = $cap.Substring($i, [Math]::Min(4000, $cap.Length - $i)); ([regex]::Matches($seg, '<Default>[^<]+</Default>|<Value>[^<]+</Value>') | Select -First 3 | % { $_.Value }) -join ' | ' } else { 'GIBS capabilities: GOES-East layer not found' }
# EUMETSAT View Service (public WMS) — Meteosat 0 deg and Indian Ocean, IR 10.8
$eum = 'https://view.eumetsat.int/geoserver/wms?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0&CRS=EPSG:4326&BBOX=-81,-81,81,81&WIDTH=1024&HEIGHT=1024&FORMAT=image/png&STYLES=&LAYERS='
probe 'EUMETView MSG 0deg IR'  ($eum + 'msg_fes:ir108') 'msg_fes.png'
probe 'EUMETView MSG IODC IR'  ($eum + 'msg_iodc:ir108') 'msg_iodc.png'
probe 'NOAA GOES-19 FD band13' 'https://cdn.star.nesdis.noaa.gov/GOES19/ABI/FD/13/1808x1808.jpg' 'goes19_fd13.jpg'
Get-ChildItem $out | Select Name, Length, LastWriteTime | Format-Table -AutoSize | Out-String
