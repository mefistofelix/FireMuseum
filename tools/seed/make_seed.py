"""Genera app/src/main/assets/catalog.json (catalogo di serie) da una tabella compatta.

Dimensioni/pesi: quelli riportati nelle pagine Wikimedia Commons (categoria Inside Bruegel,
Bosch Project, singoli file). Autore/anno/museo mancanti vengono completati dall'app via Wikidata.
Uso:  python tools/seed/make_seed.py
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "app" / "src" / "main" / "assets" / "catalog.json"


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")[:60]


A = []


def add(id, title, file=None, author=None, year=None, w=0, h=0, mb=0.0, museum=None, style=None,
        genre=None, tags=(), kid=True, warning=None, orig=None, qid=None, iiif=None):
    if iiif:
        src = {"type": "iiif", "info": iiif}
    elif file:
        src = {"type": "commons", "file": file}
    else:
        src = {"type": "wikidata", "qid": qid}
    A.append({
        "id": id, "title": title, "titleOrig": orig, "author": author, "year": year, "style": style,
        "genre": genre, "museum": museum, "tags": list(tags), "kidSafe": kid, "warning": warning,
        "width": w, "height": h, "bytes": int(mb * 1024 * 1024), "source": src, "qid": qid,
        "license": "Pubblico dominio (opera); verificare i diritti della riproduzione",
        "pageUrl": f"https://commons.wikimedia.org/wiki/File:{file}" if file else (f"https://www.wikidata.org/wiki/{qid}" if qid else None),
        "targets": [], "portals": [],
    })


FL = "Rinascimento fiammingo"
# ---------------- Beuckelaer (stile preferito) ----------------
B = "Joachim Beuckelaer"
add("beuckelaer-market-square", "Mercato nella piazza", "Joachim_Beuckelaer_-_Market_in_the_Square.jpg", B, None, 10699, 8777, 47.45,
    style=FL, genre="scena di mercato", tags=["mercato", "cibo", "bancarelle", "piazza"], orig="Market in the Square")
add("beuckelaer-country-market", "Mercato di campagna", "Joachim_Beuckelaer_-_Country_Market.jpg", B, None, 11852, 8337, 57.64,
    style=FL, genre="scena di mercato", tags=["mercato", "cibo", "animali"], orig="Country Market")
add("beuckelaer-earth", "La Terra (Quattro elementi)", "Joachim_Beuckelaer_-_Earth.jpg", B, "1569", 6000, 4368, 17.63,
    museum="National Gallery, Londra", style=FL, genre="scena di mercato", tags=["frutta", "verdura", "cesti", "quattro elementi"], orig="Earth")
add("beuckelaer-water", "L'Acqua (Quattro elementi)", "Joachim_Beuckelaer_-_Water.jpg", B, "1569", 6000, 4436, 16.76,
    museum="National Gallery, Londra", style=FL, genre="pescheria", tags=["pesci", "pescheria", "quattro elementi"], orig="Water")
add("beuckelaer-air", "L'Aria (Quattro elementi)", "Joachim_Beuckelaer_-_Air.jpg", B, "1570", 6000, 4376, 16.31,
    museum="National Gallery, Londra", style=FL, genre="scena di mercato", tags=["pollame", "uova", "strada", "quattro elementi"], orig="Air")
add("beuckelaer-fire", "Il Fuoco (Quattro elementi)", "Joachim_Beuckelaer_-_Fire.jpg", B, "1570", 6000, 4362, 15.45,
    museum="National Gallery, Londra", style=FL, genre="scena di cucina", tags=["cucina", "pentole", "utensili", "quattro elementi"], orig="Fire")
add("beuckelaer-groentemarkt", "Mercato delle verdure", "Groentemarkt,_Joachim_Beuckelaer,_1567,_Koninklijk_Museum_voor_Schone_Kunsten_Antwerpen,_5045.jpg",
    B, "1567", 6920, 4780, 5.88, museum="KMSKA, Anversa", style=FL, genre="scena di mercato", tags=["verdura", "frutta", "animali"], orig="Groentemarkt")
add("beuckelaer-keuken", "La cucina ben fornita", "De_welvoorziene_keuken_Rijksmuseum_SK-A-1451.jpeg", B, "1566", 5956, 4090, 3.91,
    museum="Rijksmuseum, Amsterdam", style=FL, genre="scena di cucina", tags=["cucina", "cibo", "stoviglie", "animali"], orig="De welvoorziene keuken")
add("beuckelaer-emmaus", "Scena di cucina con Cristo a Emmaus", "Joachim_Beuckelaer_-_Kitchen_Scene_with_Christ_at_Emmaus.jpg", B, None, 6000, 3887, 7.33,
    style=FL, genre="scena di cucina", tags=["cucina", "natura morta", "recipienti"])

# ---------------- Altri pittori di scene dense ----------------
add("avercamp-winter", "Paesaggio invernale con pattinatori", "Hendrick_Avercamp_-_Winterlandschap_met_ijsvermaak.jpg", "Hendrick Avercamp", "1608",
    6278, 3626, 3.8, museum="Rijksmuseum, Amsterdam", style="Secolo d'oro olandese", genre="paesaggio invernale",
    tags=["inverno", "ghiaccio", "pattinatori", "bambini", "barche"], orig="Winterlandschap met ijsvermaak")
add("canaletto-san-vio", "Il Canal Grande da Campo San Vio", "Canaletto_-_The_Grand_Canal_from_the_Campo_San_Vio,_c._1730-35,_K2173,_Memphis_Brooks_Museum_of_Art.jpg",
    "Canaletto", "1730", 12026, 8446, 54.64, museum="Memphis Brooks Museum of Art", style="Vedutismo", genre="veduta",
    tags=["venezia", "gondole", "barche", "città", "facciate"])
add("canaletto-palazzo-corner", "Il Canal Grande con Palazzo Corner", "Canaletto_-_The_Grand_Canal_in_Venice_with_the_Palazzo_Corner_Ca'Grande_-_Google_Art_Project.jpg",
    "Canaletto", None, 11455, 7494, 20.71, style="Vedutismo", genre="veduta", tags=["venezia", "gondole", "finestre", "città"])
add("vanderheyden-fantasy", "Fantasia architettonica", "Jan_van_der_Heyden_-_An_Architectural_Fantasy.jpg", "Jan van der Heyden", None, 16952, 11830, 67.31,
    style="Secolo d'oro olandese", genre="capriccio architettonico", tags=["architettura", "città", "facciate"])
add("teniers-gallery", "L'arciduca Leopoldo Guglielmo nella sua galleria a Bruxelles",
    "David_Teniers_the_Younger_-_Archduke_Leopold_William_in_his_Gallery_at_Brussels_-_Google_Art_Project.jpg",
    "David Teniers il Giovane", "1651", 6558, 4885, 11.89, style="Barocco fiammingo", genre="pittura di galleria",
    tags=["quadri nel quadro", "interno", "galleria", "collezione"])
add("panini-modern-rome-boston", "Galleria di vedute della Roma moderna (Boston)",
    "Giovanni_Paolo_Pannini_-_Picture_Gallery_with_Views_of_Modern_Rome_-_Google_Art_Project.jpg",
    "Giovanni Paolo Panini", "1757", 6001, 4144, 9.8, museum="Museum of Fine Arts, Boston", style="Vedutismo", genre="pittura di galleria",
    tags=["quadri nel quadro", "interno", "roma", "sculture"])
add("steen-sint-nicolaas", "La festa di San Nicola", "Jan_Steen_–_Het_Sint-Nicolaasfeest.jpg", "Jan Steen", "1665", 4610, 5382, 12.38,
    museum="Rijksmuseum, Amsterdam", style="Secolo d'oro olandese", genre="scena di genere", tags=["interno", "famiglia", "bambini", "giocattoli", "dolci"])

# ---------------- Bruegel — Inside Bruegel (KHM e altri) ----------------
BR = "Pieter Bruegel il Vecchio"
KHM = "Kunsthistorisches Museum, Vienna"
for (id, t, f, y, w, h, mb, mus, tags, kid, warn) in [
    ("bruegel-childrens-games", "Giochi di bambini", "Children's_Games_(Bruegel).jpg", "1560", 41519, 29944, 243.60, KHM, ["bambini", "giochi", "folla", "piazza"], True, None),
    ("bruegel-carnival-lent", "Lotta tra Carnevale e Quaresima", "The_Fight_Between_Carnival_and_Lent.jpg", "1559", 41472, 30219, 219.98, KHM, ["festa", "folla", "piazza", "mercato"], True, None),
    ("bruegel-babel", "La Torre di Babele", "The_Tower_of_Babel_(Bruegel).jpg", "1563", 39683, 29617, 302.50, KHM, ["architettura", "operai", "porto", "città"], True, None),
    ("bruegel-hunters", "Cacciatori nella neve", "The_Hunters_in_the_Snow.jpg", "1565", 40940, 29761, 288.49, KHM, ["inverno", "neve", "villaggio", "pattinatori", "cani"], True, None),
    ("bruegel-gloomy-day", "Il giorno cupo", "The_Gloomy_Day_(Bruegel).jpg", "1565", 41296, 30072, 236.40, KHM, ["paesaggio", "villaggio", "mesi"], True, None),
    ("bruegel-return-herd", "Il ritorno della mandria", "The_Return_of_the_Herd.jpg", "1565", 40360, 29816, 245.24, KHM, ["mandria", "mucche", "paesaggio", "mesi"], True, None),
    ("bruegel-haymaking", "La fienagione", "Haymaking,_1565.jpg", "1565", 39114, 28084, 395.64, "Palazzo Lobkowicz, Praga", ["campagna", "attrezzi", "mesi"], True, None),
    ("bruegel-peasant-dance", "Danza contadina", "The_Peasant_Dance_(Bruegel).jpg", "1567", 41945, 29385, 222.24, KHM, ["festa", "villaggio", "musica"], True, None),
    ("bruegel-peasant-wedding", "Nozze contadine", "The_Peasant_Wedding_(Bruegel).jpg", "1567", 43283, 29567, 248.48, KHM, ["interno", "banchetto", "cibo"], True, None),
    ("bruegel-adoration-snow", "Adorazione dei Magi nella neve", "Adoration_of_the_Magi_in_the_Snow,_1563.jpg", "1563", 27830, 17702, 122.19, "Collezione Oskar Reinhart, Winterthur", ["neve", "villaggio", "folla"], True, None),
    ("bruegel-nest-robber", "Il contadino e il ladro di nidi", "The_Peasant_and_the_Nest_Robber.jpg", "1568", 36697, 30889, 165.62, KHM, ["bosco", "paesaggio"], True, None),
    ("bruegel-conversion-paul", "La conversione di san Paolo", "Conversion_of_Paul_(Bruegel).jpg", "1567", 39255, 27482, 218.39, KHM, ["montagne", "soldati", "cavalli"], True, None),
    ("bruegel-calvary", "La salita al Calvario", "The_Procession_to_Calvary_(Bruegel).jpg", "1564", 44547, 32374, 283.05, KHM, ["folla", "paesaggio"], False, "Soggetto religioso con scene di supplizio sullo sfondo"),
    ("bruegel-dulle-griet", "Dulle Griet (Margherita la pazza)", "Dull_Gret,_1563.jpg", "1563", 46687, 33598, 421.48, "Museum Mayer van den Bergh, Anversa", ["fantastico", "mostri"], False, "Scena infernale, inquietante per i più piccoli"),
    ("bruegel-saul", "Il suicidio di Saul", "The_Suicide_of_Saul,_1562.jpg", "1562", 46391, 28015, 408.90, KHM, ["battaglia", "soldati", "montagne"], False, "Battaglia, tema cupo"),
]:
    add(id, t, f, BR, y, w, h, mb, museum=mus, style=FL, genre="scena di genere / paesaggio", tags=["gigapixel"] + tags, kid=kid, warning=warn)

# ---------------- Bosch — Bosch Project (selezione) ----------------
BO = "Hieronymus Bosch"
BW = "Scene fantastiche/infernali con nudità e mostri: esplorare prima di mostrarlo ai bambini"
for (id, t, f, w, h, mb, mus, kid) in [
    ("bosch-garden", "Il Giardino delle delizie", "The_Garden_of_Earthly_Delights,_ca._1480-1505.jpg", 30000, 16927, 237.57, "Museo del Prado, Madrid", False),
    ("bosch-temptation-lisbon", "Le tentazioni di sant'Antonio", "The_Temptation_of_Saint_Anthony,_ca._1495-1515.jpg", 30000, 17557, 205.39, "Museu Nacional de Arte Antiga, Lisbona", False),
    ("bosch-haywain", "Il trittico del carro di fieno", "The_Haywain_Triptych,_ca._1516.jpg", 30000, 21037, 211.70, "Museo del Prado, Madrid", False),
    ("bosch-last-judgment-vienna", "Il Giudizio universale", "The_Last_Judgment,_ca._1486.jpg", 30000, 25277, 233.18, "Akademie der bildenden Künste, Vienna", False),
    ("bosch-job", "Trittico di Giobbe", "Job_Triptych,_ca._1500-1524.jpg", 30000, 22016, 232.37, "Groeningemuseum, Bruges", False),
    ("bosch-pedlar", "Il venditore ambulante", "The_Pedlar,_ca._1500.jpg", 17222, 17444, 73.95, "Museum Boijmans Van Beuningen, Rotterdam", True),
    ("bosch-conjurer", "Il prestigiatore", "The_Conjurer,_ca._1502.jpg", 29280, 23970, 96.74, None, True),
    ("bosch-ship-fools", "La nave dei folli", "Ship_of_Fools,_ca._1490-1500.jpg", 16112, 30000, 103.66, "Louvre, Parigi", True),
    ("bosch-cure-folly", "La cura della follia", "The_Cure_of_Folly,_ca._1501-05.jpg", 15264, 21424, 55.37, "Museo del Prado, Madrid", True),
    ("bosch-st-christopher", "San Cristoforo con Gesù Bambino", "Saint_Christopher_Carrying_the_Christ_Child,_ca._1490-1500.jpg", 18617, 30000, 206.14, "Museum Boijmans Van Beuningen, Rotterdam", True),
]:
    add(id, t, f, BO, None, w, h, mb, museum=mus, style="Primitivi fiamminghi", genre="fantastico / religioso",
        tags=["gigapixel", "fantastico"], kid=kid, warning=None if kid else BW)

# ---------------- Quadri-galleria con FINESTRE (regioni Wikidata P2677) ----------------
for (qid, t, author, tags) in [
    ("Q28778382", "Galleria del Louvre", "Samuel Morse", ["quadri nel quadro", "galleria", "louvre"]),
    ("Q7840471", "La Tribuna degli Uffizi", "Johann Zoffany", ["quadri nel quadro", "galleria", "uffizi", "firenze"]),
    ("Q14619165", "Galleria di vedute della Roma antica", "Giovanni Paolo Panini", ["quadri nel quadro", "roma", "rovine"]),
    ("Q18611883", "Galleria di vedute della Roma moderna", "Giovanni Paolo Panini", ["quadri nel quadro", "roma"]),
    ("Q19939091", "Veduta del Salon Carré al Louvre", "Alexandre Jean-Baptiste Brun", ["quadri nel quadro", "louvre"]),
    ("Q132172839", "Il Salon Carré del Louvre, verso il 1870", None, ["quadri nel quadro", "louvre"]),
    ("Q17337965", "La galleria d'arte di Jan Gildemeester", "Adriaan de Lelie", ["quadri nel quadro", "amsterdam"]),
    ("Q19960951", "La galleria di Cornelis van der Geest", "Willem van Haecht", ["quadri nel quadro", "anversa"]),
    ("Q17484654", "Il museo di Rennes verso il 1900", None, ["quadri nel quadro", "museo"]),
    ("Q1231009", "L'incoronazione di Napoleone", "Jacques-Louis David", ["folla", "personaggi storici", "louvre"]),
    ("Q3202169", "L'apoteosi di Omero", "Jean-Auguste-Dominique Ingres", ["personaggi storici", "louvre"]),
]:
    add("wd-" + qid.lower(), t, None, author, None, qid=qid, tags=tags + ["wikidata regioni"])

# ---------------- IIIF museali (tasselli dal server, nessun file enorme) ----------------
add("rijks-pjezo", "Rijksmuseum — esempio IIIF Micrio (probabilmente La ronda di notte)", None, "Rembrandt van Rijn (da verificare)", "1642",
    14645, 12158, 0, museum="Rijksmuseum, Amsterdam", style="Secolo d'oro olandese", genre="ritratto di gruppo",
    tags=["iiif", "rijksmuseum"], iiif="https://iiif.micr.io/PJEZO/info.json")

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps({"version": 1, "artworks": A}, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"{len(A)} opere -> {OUT}")
