#!/usr/bin/env python3
"""Costruisce un catalogo EXTRA per FireMuseum a partire da fonti aperte. Solo libreria standard.

Fonti:
  --commons-category "Gigapixel images from the Bosch Project"   (ripetibile)
        file di una categoria Wikimedia Commons, filtrati per lato minimo;
  --wikidata-regions N
        dipinti Wikidata con almeno N regioni annotate (P180 + qualificatore P2677): ogni regione
        diventa un obiettivo del gioco e, se l'oggetto raffigurato è un dipinto con immagine,
        una FINESTRA in cui entrare (quadri-nel-quadro → "infinite picture" automatico);
  --nga MIN_MEGAPIXEL
        National Gallery of Art (Washington), open access CC0 con server IIIF: opere sopra la soglia.

Uscita: catalog/extra.json → copialo in <disco>/Android/data/it.firemuseum/files/catalog/
oppure usa --push (adb). Esempio completo:
  python tools/build_catalog.py --commons-category "Gigapixel images from the Bosch Project" \
      --wikidata-regions 8 --nga 150 --contact tu@example.org --push
"""
import argparse
import csv
import io
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "catalog" / "extra.json"
CONTACT = ""


def get(url, data=None, accept=None):
    h = {"User-Agent": f"FireMuseumCatalog/0.1 (private non-commercial; {CONTACT or 'no contact set'})"}
    if accept:
        h["Accept"] = accept
    for attempt in range(6):
        try:
            req = urllib.request.Request(url, data=data, headers=h)
            return urllib.request.urlopen(req, timeout=120).read()
        except urllib.error.HTTPError as e:
            if e.code in (429, 503):
                wait = int(e.headers.get("Retry-After") or 0) or 20 * (attempt + 1)
                print(f"  HTTP {e.code}, attendo {wait}s")
                time.sleep(wait)
                continue
            raise
    raise RuntimeError("troppi tentativi: " + url)


def sparql(q):
    data = urllib.parse.urlencode({"query": q}).encode()
    return json.loads(get("https://query.wikidata.org/sparql", data, "application/sparql-results+json"))["results"]["bindings"]


def slug(s):
    s = re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")
    return s[:60] or "x"


def commons_category(cat, min_side):
    out, cont = [], {}
    while True:
        params = {"action": "query", "format": "json", "generator": "categorymembers", "gcmtitle": "Category:" + cat,
                  "gcmtype": "file", "gcmlimit": "50", "prop": "imageinfo", "iiprop": "size|mime|extmetadata",
                  "iiextmetadatafilter": "Artist|DateTimeOriginal|ObjectName|LicenseShortName", **cont}
        d = json.loads(get("https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)))
        for p in d.get("query", {}).get("pages", {}).values():
            ii = (p.get("imageinfo") or [{}])[0]
            w, h = ii.get("width", 0), ii.get("height", 0)
            if min(w, h) < min_side or ii.get("mime") not in ("image/jpeg", "image/png", "image/tiff"):
                continue
            em = ii.get("extmetadata", {})
            strip = lambda k: re.sub("<[^>]+>", "", em.get(k, {}).get("value", "")).strip() or None
            file = p["title"].removeprefix("File:").replace(" ", "_")
            title = strip("ObjectName") or re.sub(r"\.\w+$", "", p["title"].removeprefix("File:"))
            out.append({
                "id": "c-" + slug(title), "title": title, "author": strip("Artist"), "year": (strip("DateTimeOriginal") or "")[:4] or None,
                "width": w, "height": h, "bytes": ii.get("size", 0), "license": strip("LicenseShortName"),
                "source": {"type": "commons", "file": file}, "tags": [cat], "kidSafe": "Bosch" not in cat,
                "pageUrl": "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(p["title"].replace(" ", "_")),
                "targets": [], "portals": [],
            })
        if "continue" not in d:
            break
        cont = d["continue"]
    print(f"  {cat}: {len(out)} file ≥ {min_side}px")
    return out


def wikidata_regions(min_regions):
    # 1) elenco dei dipinti con abbastanza regioni (query leggera)
    items = [b["item"]["value"].rsplit("/", 1)[1] for b in sparql(f"""
    SELECT ?item WHERE {{ ?item wdt:P31 wd:Q3305213; p:P180 ?st . ?st pq:P2677 ?x . }}
    GROUP BY ?item HAVING (COUNT(?x) >= {min_regions})""")]
    print(f"  Wikidata: {len(items)} dipinti con ≥ {min_regions} regioni")
    # 2) dettagli a lotti di 25
    rows = []
    for i in range(0, len(items), 25):
        values = " ".join("wd:" + q for q in items[i:i + 25])
        rows += sparql(f"""
        SELECT ?item ?itemLabel ?creatorLabel ?img ?d ?dLabel ?pos ?dimg ?isArt WHERE {{
          VALUES ?item {{ {values} }}
          ?item wdt:P18 ?img ; p:P180 ?st . ?st ps:P180 ?d ; pq:P2677 ?pos .
          OPTIONAL {{ ?item wdt:P170 ?creator }}
          OPTIONAL {{ ?d wdt:P18 ?dimg }}
          BIND(EXISTS {{ ?d wdt:P31 wd:Q3305213 }} AS ?isArt)
          SERVICE wikibase:label {{ bd:serviceParam wikibase:language "it,en,fr,nl,de". }}
        }}""")
        time.sleep(1)
    arts = {}
    for r in rows:
        qid = r["item"]["value"].rsplit("/", 1)[1]
        file = urllib.parse.unquote(r["img"]["value"].rsplit("/", 1)[1]).replace(" ", "_")
        a = arts.setdefault(qid, {
            "id": "wd-" + qid.lower(), "title": r.get("itemLabel", {}).get("value", qid), "author": r.get("creatorLabel", {}).get("value"),
            "qid": qid, "source": {"type": "commons", "file": file}, "tags": ["wikidata regioni"],
            "kidSafe": False, "warning": "Opera aggiunta automaticamente: non ancora verificata per i bambini", "targets": [], "portals": [],
            "pageUrl": "https://www.wikidata.org/wiki/" + qid,
        })
        m = re.match(r"pct:([\d.]+),([\d.]+),([\d.]+),([\d.]+)", r["pos"]["value"])
        if not m:
            continue
        x, y, w, h = (float(v) / 100 for v in m.groups())
        label = r.get("dLabel", {}).get("value", "")
        if re.fullmatch(r"Q\d+", label):
            continue
        a["targets"].append({"label": label, "x": x, "y": y, "w": w, "h": h, "origin": "wikidata"})
        if r.get("isArt", {}).get("value") == "true" and "dimg" in r:
            dq = r["d"]["value"].rsplit("/", 1)[1]
            a["portals"].append({"x": x, "y": y, "w": w, "h": h, "to": "wikidata:" + dq, "label": label})
            if "quadri nel quadro" not in a["tags"]:
                a["tags"].append("quadri nel quadro")
    print(f"  Wikidata: {len(arts)} dipinti, {sum(len(a['targets']) for a in arts.values())} obiettivi, "
          f"{sum(len(a['portals']) for a in arts.values())} finestre")
    return list(arts.values())


def nga(min_mpx):
    base = "https://raw.githubusercontent.com/NationalGalleryOfArt/opendata/main/data/"
    imgs = csv.DictReader(io.StringIO(get(base + "published_images.csv").decode("utf-8")))
    big = {}
    for r in imgs:
        try:
            w, h = int(r["width"]), int(r["height"])
        except ValueError:
            continue
        if r["openaccess"] == "1" and r["viewtype"] == "primary" and w * h >= min_mpx * 1e6:
            big[r["depictstmsobjectid"]] = (r["iiifurl"], w, h)
    print(f"  NGA: {len(big)} immagini ≥ {min_mpx} Mpx; leggo objects.csv (grande)…")
    objs = csv.DictReader(io.StringIO(get(base + "objects.csv").decode("utf-8")))
    out = []
    for o in objs:
        hit = big.get(o["objectid"])
        if not hit or o.get("classification") not in ("Painting", "Drawing", "Print"):
            continue
        iiif, w, h = hit
        out.append({
            "id": "nga-" + o["objectid"], "title": o.get("title") or o["objectid"], "author": o.get("attribution"),
            "year": o.get("displaydate"), "museum": "National Gallery of Art, Washington", "license": "CC0",
            "genre": o.get("classification"), "width": w, "height": h, "source": {"type": "iiif", "info": iiif + "/info.json"},
            "tags": ["nga", "iiif"], "pageUrl": f"https://www.nga.gov/collection/art-object-page.{o['objectid']}.html",
            "targets": [], "portals": [],
        })
    print(f"  NGA: {len(out)} opere")
    return out


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    global CONTACT
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--commons-category", action="append", default=[])
    ap.add_argument("--min-side", type=int, default=6000)
    ap.add_argument("--wikidata-regions", type=int, default=0)
    ap.add_argument("--nga", type=float, default=0, help="soglia in megapixel")
    ap.add_argument("--contact", default=os.environ.get("FIREMUSEUM_CONTACT", ""))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--push", action="store_true")
    a = ap.parse_args()
    CONTACT = a.contact
    arts = []
    for c in a.commons_category:
        arts += commons_category(c, a.min_side)
    if a.wikidata_regions:
        arts += wikidata_regions(a.wikidata_regions)
    if a.nga:
        arts += nga(a.nga)
    seen, uniq = set(), []
    for x in arts:
        if x["id"] not in seen:
            seen.add(x["id"]); uniq.append(x)
    out = pathlib.Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"version": 1, "artworks": uniq}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(uniq)} opere → {out}")
    if a.push:
        vols = [v for v in subprocess.run(["adb", "shell", "ls", "/storage"], capture_output=True, text=True).stdout.split()
                if v not in ("emulated", "self") and "-" in v]
        if not vols:
            print("Nessun volume USB via adb: copia il file a mano in <disco>/Android/data/it.firemuseum/files/catalog/")
            return
        dest = f"/storage/{vols[0]}/Android/data/it.firemuseum/files/catalog/"
        subprocess.run(["adb", "shell", "mkdir", "-p", dest], check=True)
        subprocess.run(["adb", "push", str(out), dest + out.name], check=True)


if __name__ == "__main__":
    main()
