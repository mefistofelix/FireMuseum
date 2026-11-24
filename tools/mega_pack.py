#!/usr/bin/env python3
"""Archivio FireMuseum su MEGA: piramidi pronte + indice, leggibili dall'app dal link pubblico.

Le sorgenti originali (Wikimedia) limitano i download (429), quindi le opere del catalogo si
scaricano UNA VOLTA dal PC, in fila e con un User-Agent con contatto, e si pubblicano su MEGA:

  <cartella MEGA>/index.json     catalogo completo (catalog*.json fusi); ogni opera ha "mirror"
  <cartella MEGA>/thumbs.zip     anteprime 400 px, <id>.jpg
  <cartella MEGA>/<id>.zip       piramide Deep Zoom (vips dzsave, zip "stored", tasselli 510+1)

L'app legge il link pubblico della cartella (https://mega.nz/folder/<id>#<chiave>): elenco dei
file con l'API pubblica di MEGA, poi letture a intervalli dei file (AES-CTR permette di decifrare
da qualunque posizione), quindi un tassello alla volta senza scaricare l'intero zip.

Uso (libvips arriva con pyvips[binary], serve Python 3.13):
  uv run --python 3.13 --with "pyvips[binary]" tools/mega_pack.py build
  uv run --python 3.13 tools/mega_pack.py upload          # rclone sync + link pubblico fisso
  uv run --python 3.13 tools/mega_pack.py check           # rilegge dal link come fa il Fire TV

Il link pubblico della cartella si crea UNA volta dall'app MEGA (Condividi -> Copia link) e si
salva in tools/mega_link.txt; upload lo copia anche in app/src/main/assets/mega_link.txt (default
dell'app). I caricamenti successivi aggiornano i file mantenendo lo stesso URL.
"""
from __future__ import annotations

import argparse
import base64
import configparser
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "work" / "mega"
ORIG = ROOT / "work" / "orig"
OUT = WORK / "out"
LINK_FILE = ROOT / "tools" / "mega_link.txt"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
RETRO = Path.home() / "Desktop" / "RetroFire" / "tools" / "rclone"
RCLONE_EXE = Path(os.environ.get("FM_RCLONE", RETRO / "rclone.exe"))
RCLONE_CONF = Path(os.environ.get("FM_RCLONE_CONFIG", RETRO / "rclone.conf"))
REMOTE = os.environ.get("FM_MEGA_REMOTE", "mega:FireMuseum/archivio")
UA = "FireMuseum-pack/0.2 (https://github.com/mefistofelix/FireMuseum; one file at a time)"
TILE, OVERLAP, Q = 510, 1, 82
MAX_THUMB = 3840          # anteprima standard più grande di Wikimedia
BUDGET = int(float(os.environ.get("FM_BUDGET_GB", "12")) * 1e9)

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------ rete educata

def http(url: str, data: bytes | None = None, headers: dict | None = None, timeout=60) -> bytes:
    h = {"User-Agent": UA, **(headers or {})}
    for attempt in range(8):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data, h), timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code in (429, 503, 502, 504) and attempt < 7:
                wait = int(e.headers.get("Retry-After") or 0) or 15 * (attempt + 1)
                log(f"  HTTP {e.code}: attendo {wait}s")
                time.sleep(min(wait, 300))
                continue
            raise
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt < 7:
                time.sleep(5 * (attempt + 1)); continue
            raise
    raise RuntimeError("unreachable")


def download(url: str, dst: Path, size: int | None) -> None:
    """Un file alla volta, con ripresa (Range) e attese su 429."""
    part = dst.with_suffix(dst.suffix + ".part")
    for attempt in range(10):
        have = part.stat().st_size if part.exists() else 0
        if size and have >= size:
            break
        h = {"User-Agent": UA}
        if have:
            h["Range"] = f"bytes={have}-"
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=120) as r:
                mode = "ab" if have and r.status == 206 else "wb"
                with open(part, mode) as f:
                    shutil.copyfileobj(r, f, 1 << 20)
            if not size:
                break
        except urllib.error.HTTPError as e:
            if e.code in (429, 503) or e.code >= 500:
                wait = int(e.headers.get("Retry-After") or 0) or 30 * (attempt + 1)
                log(f"  HTTP {e.code} su {dst.name}: attendo {wait}s")
                time.sleep(min(wait, 600)); continue
            raise
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            log(f"  rete: {e}; riprendo"); time.sleep(10 * (attempt + 1))
    if size and part.stat().st_size != size:
        raise RuntimeError(f"{dst.name}: {part.stat().st_size} byte invece di {size}")
    part.replace(dst)


# ------------------------------------------------------------------ catalogo

def load_catalog() -> list[dict]:
    by_id: dict[str, dict] = {}
    for f in sorted(ASSETS.glob("catalog*.json")):
        for a in json.loads(f.read_text(encoding="utf-8"))["artworks"]:
            by_id[a["id"]] = a
    return list(by_id.values())


def p18(qid: str) -> str | None:
    j = json.loads(http(f"https://www.wikidata.org/w/api.php?action=wbgetclaims&entity={qid}&property=P18&format=json"))
    c = j.get("claims", {}).get("P18") or []
    v = c[0]["mainsnak"].get("datavalue", {}).get("value") if c else None
    return v.replace(" ", "_") if v else None


def imageinfo(files: list[str]) -> dict[str, dict]:
    out = {}
    for i in range(0, len(files), 40):
        chunk = files[i:i + 40]
        q = urllib.parse.urlencode({"action": "query", "format": "json", "formatversion": 2, "prop": "imageinfo",
                                    "iiprop": "url|size|mime", "titles": "|".join("File:" + f for f in chunk)})
        j = json.loads(http("https://commons.wikimedia.org/w/api.php?" + q))
        norm = {n["to"]: n["from"] for n in j["query"].get("normalized", [])}
        for p in j["query"]["pages"]:
            if "imageinfo" not in p:
                continue
            t = norm.get(p["title"], p["title"])
            name = t.split(":", 1)[1].replace(" ", "_")
            out[name] = p["imageinfo"][0]
        time.sleep(1)
    return out


def ext_of(url: str) -> str:
    e = Path(urllib.parse.urlparse(url).path).suffix.lower()
    return e if e in (".jpg", ".jpeg", ".png", ".tif", ".tiff", ".webp") else ".img"


# ------------------------------------------------------------------ build

def wants_mega(a: dict, ii: dict | None) -> bool:
    """Opera per opera: MEGA solo se la sorgente originale non basta.

    Il campo di catalogo "host" ("mega" | "origin") forza la scelta. Altrimenti vanno su MEGA le
    opere Commons più larghe di 3840 px, che senza l'originale (limitato da Wikimedia) non
    arriverebbero a piena risoluzione. IIIF, DZI e le immagini piccole restano sulla sorgente
    originale: le anteprime standard di Commons e i server dei musei rispondono bene.
    """
    if a.get("host") in ("mega", "origin"):
        return a["host"] == "mega"
    return bool(ii) and ii.get("width", 0) > MAX_THUMB


def cmd_build(only: list[str]) -> None:
    import pyvips
    OUT.mkdir(parents=True, exist_ok=True); ORIG.mkdir(parents=True, exist_ok=True)
    arts = load_catalog()
    state_f = WORK / "state.json"
    state = json.loads(state_f.read_text(encoding="utf-8")) if state_f.exists() else {}
    for a in arts:                                            # wikidata{qid} -> file Commons
        s = a["source"]
        if s["type"] == "wikidata" and a["id"] not in state.get("p18", {}):
            state.setdefault("p18", {})[a["id"]] = p18(s["qid"]); time.sleep(0.5)
    files = {}
    for a in arts:
        s = a["source"]
        f = s.get("file") if s["type"] == "commons" else state.get("p18", {}).get(a["id"]) if s["type"] == "wikidata" else None
        if f and (not only or a["id"] in only):
            files[a["id"]] = f
    if "info" not in state or set(files.values()) - set(state["info"]):
        state["info"] = {**state.get("info", {}), **imageinfo(sorted(set(files.values())))}
    state_f.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
    info = state["info"]

    by_id = {a["id"]: a for a in arts}
    files = {i: f for i, f in files.items() if wants_mega(by_id[i], info.get(f))}
    unused = WORK / "unused"; unused.mkdir(exist_ok=True)        # piramidi non più su MEGA: tolte da out/
    for z in OUT.glob("*.zip"):
        if z.stem not in files and z.name != "thumbs.zip" and not only:
            z.replace(unused / z.name)
            (OUT / "thumbs" / f"{z.stem}.jpg").unlink(missing_ok=True)
    log(f"{len(files)} opere vanno su MEGA, le altre restano sulla sorgente originale")
    total = sum(p.stat().st_size for p in OUT.glob("*.zip"))
    thumbs = OUT / "thumbs"; thumbs.mkdir(exist_ok=True)
    order = sorted(files, key=lambda i: info.get(files[i], {}).get("size", 0))   # piccoli prima
    for n, aid in enumerate(order, 1):
        f = files[aid]; ii = info.get(f)
        if not ii:
            log(f"[{n}/{len(order)}] {aid}: file assente su Commons ({f})"); continue
        pyr = OUT / f"{aid}.zip"
        if pyr.exists() and (thumbs / f"{aid}.jpg").exists():
            continue
        if total > BUDGET:
            log(f"budget di {BUDGET / 1e9:.0f} GB raggiunto: mi fermo"); break
        src = ORIG / (re.sub(r"[^\w.-]", "_", aid) + ext_of(ii["url"]))
        if not src.exists():
            log(f"[{n}/{len(order)}] {aid}: scarico {ii['size'] / 1e6:.0f} MB ({ii['width']}×{ii['height']})")
            download(ii["url"], src, ii["size"])
            time.sleep(3)                                     # una richiesta alla volta, con calma
        log(f"[{n}/{len(order)}] {aid}: piramide")
        tmp = OUT / f"{aid}.tmp"
        shutil.rmtree(tmp, ignore_errors=True); tmp.mkdir()
        img = pyvips.Image.new_from_file(str(src), access="sequential")
        if img.hasalpha():
            img = img.flatten(background=[255, 255, 255])
        if img.interpretation not in ("srgb", "b-w"):
            img = img.colourspace("srgb")
        if img.bands > 3:
            img = img.extract_band(0, n=3)
        img.dzsave(str(tmp / f"{aid}.zip"), tile_size=TILE, overlap=OVERLAP, suffix=f".jpg[Q={Q},strip]",
                   container="zip", compression=0, depth="onepixel")
        (tmp / f"{aid}.zip").replace(pyr)
        shutil.rmtree(tmp, ignore_errors=True)
        pyvips.Image.thumbnail(str(src), 400).write_to_file(str(thumbs / f"{aid}.jpg"), Q=80, strip=True)
        total += pyr.stat().st_size
        log(f"   {pyr.stat().st_size / 1e6:.0f} MB · totale {total / 1e9:.2f} GB")
    write_index(arts, files, state)


def write_index(arts: list[dict], files: dict, state: dict) -> None:
    """Ogni voce ha sempre la sorgente originale ("source"); "host" dice da dove leggerla:
    "mega" (con "mirror" = zip nella cartella) oppure "origin"."""
    info = state.get("info", {})
    idx = []
    for a in arts:
        a = dict(a)
        s = a["source"]
        f = s.get("file") if s["type"] == "commons" else state.get("p18", {}).get(a["id"]) if s["type"] == "wikidata" else None
        ii = info.get(f or "", {})
        if ii and not a.get("width"):
            a["width"], a["height"], a["bytes"] = ii["width"], ii["height"], ii["size"]
        pyr = OUT / f"{a['id']}.zip"
        if a["id"] in files and pyr.exists():
            a["host"], a["mirror"], a["mirrorBytes"] = "mega", pyr.name, pyr.stat().st_size
            if ii:
                a["width"], a["height"], a["bytes"] = ii["width"], ii["height"], ii["size"]
        else:
            a["host"] = "origin"
            a.pop("mirror", None); a.pop("mirrorBytes", None)
        idx.append(a)
    with zipfile.ZipFile(OUT / "thumbs.zip", "w", zipfile.ZIP_STORED) as z:
        for t in sorted((OUT / "thumbs").glob("*.jpg")):
            z.write(t, t.name)
    doc = {"version": 1, "name": "Archivio FireMuseum", "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
           "thumbs": "thumbs.zip", "artworks": idx}
    (OUT / "index.json").write_text(json.dumps(doc, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    n = sum(1 for a in idx if a["host"] == "mega")
    log(f"index.json: {len(idx)} opere, {n} su MEGA, {len(idx) - n} dalla sorgente originale")


# ------------------------------------------------------------------ MEGA

def _b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _b64e(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def _aes(key: bytes):
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    return Cipher(algorithms.AES(key), modes.ECB())


def ecb_enc(key, data): e = _aes(key).encryptor(); return e.update(data) + e.finalize()
def ecb_dec(key, data): d = _aes(key).decryptor(); return d.update(data) + d.finalize()


def session() -> dict:
    c = configparser.ConfigParser(); c.read(RCLONE_CONF, encoding="utf-8")
    m = c["mega"]
    return {"session_id": m["session_id"], "master_key": base64.b64decode(m["master_key"])}


def api(cmd: dict, sid: str | None = None, folder: str | None = None):
    q = f"id={secrets.randbelow(10**9)}" + (f"&sid={sid}" if sid else "") + (f"&n={folder}" if folder else "")
    for attempt in range(6):
        res = json.loads(http(f"https://g.api.mega.co.nz/cs?{q}", json.dumps([cmd]).encode(),
                              {"Content-Type": "application/json"}, timeout=120))
        r = res if isinstance(res, int) else res[0]
        if r == -3:                                           # EAGAIN
            time.sleep(2 * (attempt + 1)); continue
        if isinstance(r, int) and r < 0:
            raise RuntimeError(f"MEGA API {cmd.get('a')}: errore {r}")
        return r
    raise RuntimeError("MEGA API: troppi EAGAIN")


def parse_link(link: str) -> tuple[str, bytes]:
    m = re.search(r"mega\.nz/folder/([^#/?]+)#([^/?]+)", link)
    if not m:
        raise SystemExit(f"non è un link di cartella MEGA: {link}")
    return m.group(1), _b64d(m.group(2))


def account_nodes(s: dict) -> list[dict]:
    return api({"a": "f", "c": 1}, sid=s["session_id"])["f"]


def node_key(n: dict, master: bytes) -> bytes | None:
    pieces = dict(x.split(":", 1) for x in n.get("k", "").split("/") if ":" in x)
    own = pieces.get(n.get("u"))
    return ecb_dec(master, _b64d(own)) if own else None


def fix_share_keys(s: dict, link: str) -> None:
    """rclone carica i file con la sola chiave del proprietario: aggiunge quella della condivisione."""
    folder_id, share = parse_link(link)
    pub = api({"a": "f", "c": 1, "ca": 1, "r": 1}, folder=folder_id)["f"]
    handles = {n["h"] for n in pub}
    root = next(n["h"] for n in pub if n.get("p") not in handles)
    nodes = account_nodes(s)
    kids: dict[str, list] = {}
    for n in nodes:
        kids.setdefault(n.get("p"), []).append(n)
    sub, todo = [], [root]
    while todo:
        for n in kids.get(todo.pop(), []):
            sub.append(n); todo.append(n["h"])
    fix = []
    for n in sub:
        pieces = dict(x.split(":", 1) for x in n.get("k", "").split("/") if ":" in x)
        if root in pieces:
            continue
        k = node_key(n, s["master_key"])
        if k:
            fix.append((n["h"], _b64e(ecb_enc(share, k))))
    for i in range(0, len(fix), 200):
        part = fix[i:i + 200]
        triples = []
        for j, (_, k) in enumerate(part):
            triples += [0, j, k]
        api({"a": "k", "cr": [[root], [h for h, _ in part], triples]}, sid=s["session_id"])
    log(f"chiavi di condivisione: {len(fix)} file sistemati")


def rclone(*args) -> None:
    subprocess.run([str(RCLONE_EXE), "--config", str(RCLONE_CONF), *args], check=True)


def cmd_upload() -> None:
    if not (OUT / "index.json").exists():
        raise SystemExit("prima: mega_pack.py build")
    rclone("sync", str(OUT), REMOTE, "--exclude", "thumbs/**", "--exclude", "*.tmp/**",
           "--transfers", "3", "--stats", "30s", "--stats-one-line")
    s = session()
    if LINK_FILE.exists():
        link = LINK_FILE.read_text(encoding="utf-8").strip()
    else:
        # rclone e l'API legacy (s2 -> EAGAIN con la gestione chiavi nuova di MEGA) non creano link
        # di cartella: si crea una volta dall'app MEGA, come per RetroFire.
        log(f"Crea il link pubblico di {REMOTE.split(':', 1)[1]} su MEGA (Condividi -> Copia link, chiave inclusa),\n"
            f"  salvalo in {LINK_FILE} e rilancia: mega_pack.py upload")
        return
    (ASSETS / "mega_link.txt").write_text(link + "\n", encoding="utf-8")   # link predefinito dell'app
    fix_share_keys(s, link)
    cmd_check(link)


def cmd_check(link: str | None = None) -> None:
    """Rilegge dal link pubblico come fa il Fire TV: elenco, index.json, un tassello da uno zip."""
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    import struct
    link = link or LINK_FILE.read_text(encoding="utf-8").strip()
    folder_id, share = parse_link(link)
    res = api({"a": "f", "c": 1, "ca": 1, "r": 1}, folder=folder_id)["f"]
    handles = {n["h"] for n in res}
    roots = {n["h"] for n in res if n.get("p") not in handles}
    files = {}
    for n in res:
        if n.get("t") != 0:
            continue
        pieces = dict(x.split(":", 1) for x in n.get("k", "").split("/") if ":" in x)
        piece = next((v for h, v in pieces.items() if h in roots), None)
        if not piece:
            continue
        k = ecb_dec(share, _b64d(piece))
        key = bytes(x ^ y for x, y in zip(k[:16], k[16:32]))
        dec = Cipher(algorithms.AES(key), modes.CBC(b"\0" * 16)).decryptor()
        attr = (dec.update(_b64d(n["a"])) + dec.finalize()).rstrip(b"\0")
        if attr.startswith(b"MEGA"):
            files[json.loads(attr[4:].decode("utf-8", "replace"))["n"]] = (n["h"], n["s"], key, k[16:24])
    log(f"cartella pubblica: {len(files)} file leggibili")

    def read(name, start, end):
        h, size, key, iv = files[name]
        g = api({"a": "g", "g": 1, "n": h}, folder=folder_id)["g"]
        a = start - start % 16
        data = http(f"{g}/{a}-{min(end, size) - 1}", timeout=120)
        ctr = iv + (a // 16).to_bytes(8, "big")
        d = Cipher(algorithms.AES(key), modes.CTR(ctr)).decryptor()
        return (d.update(data) + d.finalize())[start - a:]

    idx = json.loads(read("index.json", 0, files["index.json"][1]))
    mir = [a for a in idx["artworks"] if a.get("mirror")]
    missing = [a["mirror"] for a in mir if a["mirror"] not in files]
    log(f"index.json: {len(idx['artworks'])} opere, {len(mir)} piramidi, mancanti {len(missing)}")
    if mir:
        z = min(mir, key=lambda a: a["mirrorBytes"])["mirror"]
        size = files[z][1]
        tail = read(z, max(0, size - 65536), size)
        e = tail.rfind(b"PK\x05\x06")
        cd_size, cd_off = struct.unpack("<II", tail[e + 12:e + 20])
        log(f"{z}: zip leggibile a intervalli, directory centrale {cd_size} byte a {cd_off}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = p.add_subparsers(dest="cmd", required=True)
    b = sp.add_parser("build"); b.add_argument("ids", nargs="*")
    sp.add_parser("upload")
    c = sp.add_parser("check"); c.add_argument("--link")
    a = p.parse_args()
    if a.cmd == "build":
        cmd_build(a.ids)
    elif a.cmd == "upload":
        cmd_upload()
    else:
        cmd_check(a.link)


if __name__ == "__main__":
    main()
