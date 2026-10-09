import sys, os, json, base64, threading, http.server, functools, socketserver
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, 'out'); os.makedirs(OUT, exist_ok=True)
Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=ROOT)
Handler.log_message = lambda *a: None
srv = socketserver.TCPServer(('127.0.0.1', 0), Handler); port = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()

shots = json.loads(sys.argv[1])  # [{"name":..., "q": "utc=...&lat=..."}]
with sync_playwright() as p:
    b = p.chromium.launch(args=['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'])
    for s in shots:
        pg = b.new_page(viewport={'width': 1280, 'height': 2856})
        pg.goto(f'http://127.0.0.1:{port}/render.html?{s["q"]}')
        pg.wait_for_function("document.title.startsWith('done') || document.title.startsWith('error')", timeout=240000)
        print(s['name'], pg.title())
        data = pg.evaluate("document.getElementById('c').toDataURL('image/png')")
        open(os.path.join(OUT, s['name'] + '.png'), 'wb').write(base64.b64decode(data.split(',')[1]))
        pg.close()
    b.close()
