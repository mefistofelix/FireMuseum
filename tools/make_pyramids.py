#!/usr/bin/env python3
"""Prepara sul PC le piramidi Deep Zoom (.zip) delle opere giga e le copia sul disco del Fire TV.

Perché: un JPEG da 40.000 px / 300 MB non si decodifica bene su una chiavetta Fire TV
(1,7 GB di RAM, CPU ARM modesta, eventuali JPEG progressive). Sul PC libvips lo spezza in
tasselli in pochi minuti; l'app li legge da un unico .zip "stored" (niente 100.000 file piccoli
sull'exFAT della chiavetta) con accesso casuale immediato.

Requisiti: Python 3.9+ (solo libreria standard) e libvips (comando `vips` nel PATH):
  Windows: https://github.com/libvips/build-win64-mxe/releases  (vips-dev-w64-web-*.zip, aggiungi bin/ al PATH)
  macOS:   brew install vips       Linux: apt install libvips-tools

Esempi:
  python tools/make_pyramids.py --list
  python tools/make_pyramids.py bruegel-childrens-games bruegel-babel --contact tu@example.org
  python tools/make_pyramids.py --all-commons --min-side 15000 --push        # via adb sul volume USB
  python tools/make_pyramids.py --serve                                       # server LAN per l'app

Cortesia verso Wikimedia: download UNO alla volta, User-Agent con un tuo contatto (--contact),
pausa tra un file e l'altro, rispetto di Retry-After sui 429. Gli originali sono enormi: non
lanciare download paralleli.
"""
import argparse
import http.server
import json
import os
import pathlib
import shutil
import socketserver
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "catalog.json"
WORK = ROOT / "work"
PKG = "it.firemuseum"


def ua(contact):
    c = f"; {contact}" if contact else ""
    return f"FireMuseumPyramids/0.1 (private non-commercial tiling tool{c})"


def http_get(url, contact, headers=None, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": ua(contact), **(headers or {})})
    return urllib.request.urlopen(req, timeout=timeout)


def load_catalog(extra):
    arts = {a["id"]: a for a in json.loads(CATALOG.read_text(encoding="utf-8"))["artworks"]}
    for p in extra or []:
        for a in json.loads(pathlib.Path(p).read_text(encoding="utf-8"))["artworks"]:
            arts[a["id"]] = a
    return arts


def commons_info(file, contact):
    t = urllib.parse.quote("File:" + file.replace("_", " "))
    u = f"https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url|size|mime&titles={t}"
    d = json.load(http_get(u, contact))
    page = next(iter(d["query"]["pages"].values()))
    return page["imageinfo"][0]


def download(url, dest, size, contact):
    """Download con ripresa e back-off sui 429/503."""
    part = dest.with_suffix(dest.suffix + ".part")
    attempt = 0
    while True:
        have = part.stat().st_size if part.exists() else 0
        if size and have >= size:
            break
        try:
            r = http_get(url, contact, {"Range": f"bytes={have}-"} if have else None, timeout=120)
            mode = "ab" if r.status == 206 else "wb"
            done = have if mode == "ab" else 0
            t0 = time.time()
            with open(part, mode) as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
                    done += len(chunk)
                    if size:
                        rate = (done - have) / max(1e-3, time.time() - t0) / 1e6
                        print(f"\r  {done / 1e6:7.1f} / {size / 1e6:.1f} MB  {rate:4.1f} MB/s", end="", flush=True)
            print()
            break
        except urllib.error.HTTPError as e:
            attempt += 1
            if e.code in (429, 503) and attempt < 12:
                wait = int(e.headers.get("Retry-After") or 0) or min(900, 30 * attempt * attempt)
                print(f"\n  HTTP {e.code}: attendo {wait}s (rispetto del limite Wikimedia)…")
                time.sleep(wait)
                continue
            raise
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            attempt += 1
            if attempt > 8:
                raise
            print(f"\n  rete: {e} — riprovo")
            time.sleep(10 * attempt)
    part.replace(dest)


def tile(src, out_zip):
    vips = shutil.which("vips")
    if not vips:
        sys.exit("Serve libvips: comando `vips` non trovato nel PATH (vedi intestazione del file).")
    base = out_zip.with_suffix("")
    # 510 + overlap 1 = tasselli da 512; JPEG Q85; zip senza compressione (accesso casuale veloce)
    cmd = [vips, "dzsave", str(src), str(base) + ".zip", "--tile-size", "510", "--overlap", "1",
           "--suffix", ".jpg[Q=85,optimize_coding,strip]", "--depth", "onepixel"]
    print("  ", " ".join(cmd))
    subprocess.run(cmd, check=True)


def adb_volume():
    out = subprocess.run(["adb", "shell", "ls", "/storage"], capture_output=True, text=True).stdout.split()
    vols = [v for v in out if v not in ("emulated", "self") and "-" in v]
    return f"/storage/{vols[0]}" if vols else None


def push(zip_path, aid):
    vol = adb_volume()
    if not vol:
        print("  Nessun volume USB visibile via adb: copia a mano il .zip in <disco>/Android/data/it.firemuseum/files/pyramids/")
        return
    dest = f"{vol}/Android/data/{PKG}/files/pyramids/{aid}.zip"
    subprocess.run(["adb", "shell", "mkdir", "-p", os.path.dirname(dest)], check=True)
    subprocess.run(["adb", "push", str(zip_path), dest], check=True)


def serve(port):
    os.chdir(WORK)
    print(f"Servo {WORK} su http://0.0.0.0:{port}/  (nell'app: Impostazioni → Server LAN → http://<IP-del-PC>:{port}/)")
    with socketserver.ThreadingTCPServer(("", port), http.server.SimpleHTTPRequestHandler) as s:
        s.serve_forever()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ids", nargs="*")
    ap.add_argument("--catalog", action="append", help="catalogo extra (da build_catalog.py)")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--all-commons", action="store_true")
    ap.add_argument("--min-side", type=int, default=12000)
    ap.add_argument("--contact", default=os.environ.get("FIREMUSEUM_CONTACT", ""), help="email/URL per lo User-Agent (richiesto da Wikimedia)")
    ap.add_argument("--push", action="store_true", help="adb push sul volume USB del Fire TV")
    ap.add_argument("--keep", action="store_true", help="non cancellare l'originale dopo la tassellatura")
    ap.add_argument("--serve", action="store_true")
    ap.add_argument("--port", type=int, default=8000)
    a = ap.parse_args()

    if a.serve:
        WORK.mkdir(exist_ok=True)
        (WORK / "pyramids").mkdir(exist_ok=True)
        return serve(a.port)

    arts = load_catalog(a.catalog)
    if a.list:
        for x in arts.values():
            print(f"{x['id']:40s} {x.get('width', 0):>6} × {x.get('height', 0):<6} {x['source']['type']:9s} {x['title']}")
        return
    ids = a.ids or [k for k, x in arts.items() if a.all_commons and x["source"]["type"] == "commons"
                    and max(x.get("width", 0), x.get("height", 0)) >= a.min_side]
    if not ids:
        ap.print_help()
        return
    if not a.contact:
        print("⚠ Nessun --contact: Wikimedia chiede uno User-Agent con un contatto; rischi più 429.")
    (WORK / "originals").mkdir(parents=True, exist_ok=True)
    (WORK / "pyramids").mkdir(parents=True, exist_ok=True)
    for i, aid in enumerate(ids):
        x = arts.get(aid)
        if not x or x["source"]["type"] != "commons":
            print(f"- {aid}: non è una sorgente Commons, salto")
            continue
        out = WORK / "pyramids" / f"{aid}.zip"
        print(f"[{i + 1}/{len(ids)}] {aid}")
        if not out.exists():
            info = commons_info(x["source"]["file"], a.contact)
            ext = pathlib.Path(urllib.parse.urlparse(info["url"]).path).suffix.lower() or ".jpg"
            src = WORK / "originals" / f"{aid}{ext}"
            if not src.exists():
                print(f"  originale {info['width']}×{info['height']} {info['size'] / 1e6:.0f} MB")
                download(info["url"], src, info["size"], a.contact)
                time.sleep(5)  # pausa di cortesia
            tile(src, out)
            if not a.keep:
                src.unlink()
        print(f"  piramide: {out} ({out.stat().st_size / 1e6:.0f} MB)")
        if a.push:
            push(out, aid)


if __name__ == "__main__":
    main()
