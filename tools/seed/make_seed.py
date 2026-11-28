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

# ---------------- Paesaggi, vedute, marine (immagini Commons da almeno 10.000 px) ----------------
add('bruegel-harvesters', 'I mietitori', 'Pieter_Bruegel_the_Elder-_The_Harvesters_-_Google_Art_Project.jpg', 'Pieter Bruegel il Vecchio', '1565', 30000, 21851, 212.77,
    museum='Metropolitan Museum of Art, New York', style='Rinascimento fiammingo', genre='paesaggio', tags=['estate', 'campi', 'contadini', 'grano'])
add('bruegel-census-bethlehem', 'Il censimento di Betlemme', 'Pieter_Bruegel_the_Elder_-_The_Numbering_at_Bethlehem_-_Google_Art_Project.jpg', 'Pieter Bruegel il Vecchio', '1566', 15647, 10843, 39.68,
    museum='Musei reali di Belle Arti, Bruxelles', style='Rinascimento fiammingo', genre='paesaggio invernale', tags=['inverno', 'neve', 'villaggio', 'bambini'])
add('bruegel-winter-birdtrap', 'Paesaggio invernale con pattinatori e trappola per uccelli', 'Bruegel,_Pieter_(I)_-_Winterlandschap_met_schaatsers_en_vogelknip,_1565.jpg', 'Pieter Bruegel il Vecchio', '1565', 12406, 8224, 60.01,
    museum='Musei reali di Belle Arti, Bruxelles', style='Rinascimento fiammingo', genre='paesaggio invernale', tags=['inverno', 'ghiaccio', 'pattinatori', 'uccelli'])
add('bruegel-icarus', 'Paesaggio con la caduta di Icaro', 'Pieter_Bruegel_the_Elder_-_Landscape_with_the_Fall_of_Icarus_-_Brussels,_Royal_Museums_of_Fine_Arts_of_Belgium_-_Google_Arts_&_Culture.jpg', 'Pieter Bruegel il Vecchio (attr.)', '1560 ca.', 12265, 8000, 81.69,
    museum='Musei reali di Belle Arti, Bruxelles', style='Rinascimento fiammingo', genre='paesaggio', tags=['mare', 'navi', 'contadino', 'pecore'])
add('bruegel-naples', 'Veduta del golfo di Napoli con navi', 'Bruegel_—_Naval_Battle_in_the_Gulf_of_Naples.jpg', 'Pieter Bruegel il Vecchio', '1560 ca.', 10319, 5889, 41.45,
    museum='Galleria Doria Pamphilj, Roma', style='Rinascimento fiammingo', genre='marina', tags=['mare', 'navi', 'porto', 'Napoli'])
add('jan-brueghel-babel', 'La Torre di Babele', 'Jan_Brueghel_I_-_Toren_van_Babel_-_947_-_Royal_Museum_of_Fine_Arts_Antwerp.jpg', 'Jan Brueghel il Vecchio', '1595 ca.', 26566, 20394, 59.70,
    museum='KMSKA, Anversa', style='Rinascimento fiammingo', genre='paesaggio', tags=['torre', 'cantiere', 'paesaggio'])
add('avercamp-scene-ice', 'Scena sul ghiaccio', 'A_Scene_on_the_Ice.jpg', 'Hendrick Avercamp', '1625 ca.', 17273, 8767, 58.92,
    museum='National Gallery of Art, Washington', style="Secolo d'oro olandese", genre='paesaggio invernale', tags=['inverno', 'ghiaccio', 'pattinatori', 'slitte'])
add('canaletto-bucentaur', 'Il ritorno del Bucintoro al molo di Palazzo Ducale', "Canaletto_-_Bucentaur's_return_to_the_pier_by_the_Palazzo_Ducale_-_Google_Art_Project.jpg", 'Canaletto', '1730 ca.', 30000, 20857, 190.21,
    museum='Pushkin Museum, Mosca', style='Vedutismo veneziano', genre='veduta', tags=['Venezia', 'barche', 'gondole', 'festa'])
add('canaletto-brenta', 'Il canale del Brenta a Padova', 'Giovanni_Antonio_Canal,_il_Canaletto_-_The_Brenta_Canal_at_Padua_-_WGA03914.jpg', 'Canaletto', '1740 ca.', 19535, 11085, 98.68,
    museum='National Gallery of Art, Washington', style='Vedutismo veneziano', genre='veduta', tags=['canale', 'barche', 'città'])
add('canaletto-regatta', 'Una regata sul Canal Grande', 'Canal,_Giovanni_Antonio_-_Venice-_A_Regatta_on_the_Grand_Canal_-_NG.jpeg', 'Canaletto', '1740 ca.', 11180, 6980, 35.90,
    museum='National Gallery, Londra', style='Vedutismo veneziano', genre='veduta', tags=['Venezia', 'gondole', 'regata', 'folla'])
add('bellotto-mendicanti', 'Il rio dei Mendicanti e la Scuola Grande di San Marco', '(Venice)_Il_rio_dei_Mendicanti_e_la_Scuola_Grande_di_San_Marco_-_Bernardo_Bellotto_-_Gallerie_Accademia.jpg', 'Bernardo Bellotto', '1740 ca.', 10030, 7014, 37.62,
    museum="Gallerie dell'Accademia, Venezia", style='Vedutismo veneziano', genre='veduta', tags=['Venezia', 'canale', 'gondole'])
add('bellotto-warsaw', 'Veduta di Varsavia dal Castello Reale', 'Bernardo_Bellotto_-_View_of_Warsaw_from_the_Royal_Castle_-_Google_Art_Project.jpg', 'Bernardo Bellotto', '1773', 19382, 11838, 45.59,
    museum='Castello Reale, Varsavia', style='Vedutismo veneziano', genre='veduta', tags=['città', 'fiume', 'tetti'])
add('guardi-salute', 'Santa Maria della Salute', 'Francesco_Guardi_-_Santa_Maria_della_Salute_(c._1770)_-_NGA.jpg', 'Francesco Guardi', '1770 ca.', 12251, 7372, 43.70,
    museum='National Gallery of Art, Washington', style='Vedutismo veneziano', genre='veduta', tags=['Venezia', 'barche', 'chiesa'])
add('bakhuizen-ships-rocks', 'Navi in difficoltà davanti a una costa rocciosa', 'Ludolf_Bakhuizen_-_Ships_in_Distress_off_a_Rocky_Coast_-_WGA01128.jpg', 'Ludolf Bakhuizen', '1667', 15918, 10823, 85.78,
    museum='National Gallery of Art, Washington', style="Secolo d'oro olandese", genre='marina', tags=['mare', 'navi', 'tempesta', 'scogli'])
add('vandevelde-before-storm', 'Prima della tempesta', 'Willem_van_de_Velde_(II)_-_Voor_de_storm_(NGA).jpg', 'Willem van de Velde il Giovane', '1700 ca.', 11691, 6872, 32.74,
    museum='National Gallery of Art, Washington', style="Secolo d'oro olandese", genre='marina', tags=['mare', 'barche', 'vento'])
add('vroom-arrival-flushing', "L'arrivo dell'elettore Federico V a Flessinga", 'Hendrik_Cornelisz._Vroom_-_The_Arrival_of_Elector_Frederick_V_of_the_Palatinate_and_Elizabeth_Stuart.jpg', 'Hendrick Cornelisz. Vroom', '1623', 14000, 6766, 39.90,
    museum='Frans Hals Museum, Haarlem', style="Secolo d'oro olandese", genre='marina', tags=['navi', 'porto', 'bandiere', 'folla'])
add('cuyp-pier-dordrecht', 'Un molo davanti a Dordrecht', 'A_Pier_Overlooking_Dordrecht_A22567.jpg', 'Aelbert Cuyp', '1645 ca.', 18051, 10417, 91.99,
    museum=None, style="Secolo d'oro olandese", genre='paesaggio fluviale', tags=['fiume', 'barche', 'vele'])
add('goyen-dordrecht-kil', 'Veduta di Dordrecht dalla Dordtse Kil', 'Goyen_1644_View_of_Dordrecht_from_the_Dordtse_Kil.jpg', 'Jan van Goyen', '1644', 13993, 9404, 45.08,
    museum=None, style="Secolo d'oro olandese", genre='paesaggio fluviale', tags=['fiume', 'barche', 'città'])
add('goyen-dordrecht-papendrecht', 'Veduta di Dordrecht da Papendrecht', 'Jan_van_Goyen_-_View_of_Dordrecht_from_Papendrecht_-_551_-_Mauritshuis.jpg', 'Jan van Goyen', '1633', 11194, 6928, 16.68,
    museum="Mauritshuis, L'Aia", style="Secolo d'oro olandese", genre='paesaggio fluviale', tags=['fiume', 'barche', 'nuvole'])
add('goyen-arnhem', 'Veduta di Arnhem', 'Jan_Josefsz_van_Goyen_-_View_of_the_City_of_Arnhem_-_Google_Art_Project.jpg', 'Jan van Goyen', '1646', 12998, 8609, 35.02,
    museum=None, style="Secolo d'oro olandese", genre='paesaggio fluviale', tags=['fiume', 'città', 'barche'])
add('goyen-rhenen', 'Veduta di Rhenen', 'Jan_van_Goyen_-_View_of_Rhenen.jpg', 'Jan van Goyen', '1646', 17842, 13351, 99.27,
    museum='Corcoran Collection, Washington', style="Secolo d'oro olandese", genre='paesaggio', tags=['campagna', 'chiesa', 'carri'])
add('sruysdael-ferry', 'Paesaggio fluviale con traghetto', 'Salomon_van_Ruysdael_-_Rivierlandschap_met_veerpont_(1649).jpg', 'Salomon van Ruysdael', '1649', 18587, 13915, 98.61,
    museum='Rijksmuseum, Amsterdam', style="Secolo d'oro olandese", genre='paesaggio fluviale', tags=['fiume', 'traghetto', 'mucche', 'alberi'])
add('jruisdael-oak-road', 'Strada in un bosco di querce', 'Jacob_Isaacksz._van_Ruisdael_-_Road_through_an_Oak_Forest_-_Google_Art_Project.jpg', 'Jacob van Ruisdael', '1646 ca.', 16189, 12616, 83.85,
    museum='Getty Center, Los Angeles', style="Secolo d'oro olandese", genre='paesaggio', tags=['bosco', 'alberi', 'viandanti'])
add('vanderneer-village-canal', 'Villaggio olandese lungo un canale', "Beaux-Arts_de_Carcassonne_-_Village_hollandais_au_bord_d'un_canal_-_Aert_Van_Der_Neer_-_Joconde04400000297.jpg", 'Aert van der Neer', '1650 ca.', 12220, 6697, 39.62,
    museum='Musée des Beaux-Arts, Carcassonne', style="Secolo d'oro olandese", genre='paesaggio fluviale', tags=['canale', 'villaggio', 'alberi'])
add('asselijn-ponte-molle', 'Il Tevere con Ponte Milvio al tramonto', "'The_Tiber_River_with_the_Ponte_Molle_at_Sunset'_by_Jan_Asselijn,_c._1650.jpg", 'Jan Asselijn', '1650 ca.', 10682, 8030, 46.22,
    museum='Getty Center, Los Angeles', style="Secolo d'oro olandese", genre='paesaggio', tags=['fiume', 'ponte', 'tramonto', 'animali'])
add('rubens-het-steen', 'Veduta di Het Steen al mattino', 'Peter_Paul_Rubens_-_View_of_Het_Steen_in_the_Early_Morning.jpg', 'Pieter Paul Rubens', '1636 ca.', 21256, 12628, 97.27,
    museum='National Gallery, Londra', style='Barocco fiammingo', genre='paesaggio', tags=['campagna', 'castello', 'carro', 'cacciatore'])

# ---------------- Montagne, prati, città, interni (immagini Commons da almeno 10.000 px) ----------------
add('bierstadt-buffalo-trail', 'Il sentiero dei bisonti, tempesta in arrivo', 'Albert_Bierstadt_-_Buffalo_Trail,_The_Impending_Storm_(1869)_-_NGA.jpg', 'Albert Bierstadt', '1869', 21225, 12646, 95.81,
    museum='National Gallery of Art, Washington', style='Hudson River School', genre='paesaggio', tags=['bisonti', 'prateria', 'nuvole', 'animali'])
add('bierstadt-sunrise-yosemite', 'Alba nella valle di Yosemite', 'Albert_Bierstadt_-_Sunrise,_Yosemite_Valley_(c._1870).jpg', 'Albert Bierstadt', '1870 ca.', 12732, 8815, 48.17,
    museum='Amon Carter Museum, Fort Worth', style='Hudson River School', genre='paesaggio di montagna', tags=['montagne', 'valle', 'alberi'])
add('bierstadt-yosemite-view', 'Veduta della valle di Yosemite', 'Albert_Bierstadt_-_View_in_the_Yosemite_Valley.jpg', 'Albert Bierstadt', '1865 ca.', 10173, 6596, 36.58,
    museum=None, style='Hudson River School', genre='paesaggio di montagna', tags=['montagne', 'rocce', 'bosco'])
add('bierstadt-sierra-nevada', 'Tra le montagne della Sierra Nevada', 'Bierstadt_-_Among_the_Sierra_Nevada_Mountains_-_1868.jpg', 'Albert Bierstadt', '1868', 10500, 6298, 23.11,
    museum='Smithsonian American Art Museum, Washington', style='Hudson River School', genre='paesaggio di montagna', tags=['montagne', 'lago', 'cervi', 'cascata'])
add('cole-notch', 'Il passo della Notch nelle White Mountains', 'Thomas_Cole_-_A_View_of_the_Mountain_Pass_Called_the_Notch_of_the_White_Mountains.jpg', 'Thomas Cole', '1839', 10152, 6610, 29.49,
    museum='National Gallery of Art, Washington', style='Hudson River School', genre='paesaggio di montagna', tags=['montagne', 'autunno', 'bosco', 'diligenza'])
add('cole-tivoli', 'Le cascatelle di Tivoli verso Roma', 'Thomas_Cole_-_The_Cascatelli,_Tivoli,_Looking_Towards_Rome_(c._1832).jpg', 'Thomas Cole', '1832 ca.', 10144, 7372, 51.71,
    museum='Columbus Museum of Art', style='Hudson River School', genre='paesaggio', tags=['cascate', 'paese', 'campagna'])
add('church-niagara', 'Le cascate del Niagara', 'Frederic_Edwin_Church_-_Niagara_Falls_-_WGA04867.jpg', 'Frederic Edwin Church', '1857', 27911, 12955, 80.47,
    museum='National Gallery of Art, Washington', style='Hudson River School', genre='paesaggio', tags=['cascate', 'acqua', 'arcobaleno'])
add('friedrich-sturmhaube', 'Veduta di Warmbrunn verso la Kleine Sturmhaube', 'Caspar_David_Friedrich_-_Blick_von_Warmbrunn_auf_die_Kleine_Sturmhaube.jpg', 'Caspar David Friedrich', '1810 ca.', 10544, 8112, 26.22,
    museum=None, style='Romanticismo', genre='paesaggio di montagna', tags=['montagne', 'campi', 'villaggio'])
add('friedrich-garden-terrace', 'La terrazza del giardino', 'Friedrich,_Caspar_David_-_Gartenterrasse.jpeg', 'Caspar David Friedrich', '1811', 13788, 9818, 41.07,
    museum='Schloss Charlottenhof, Potsdam', style='Romanticismo', genre='paesaggio', tags=['giardino', 'alberi', 'prato'])
add('lorrain-river-shepherds', 'Paesaggio fluviale con pastori', 'Claude_Lorrain,_Flodlandskab_med_hyrder,_,_KMS4898,_Statens_Museum_for_Kunst.jpg', 'Claude Lorrain', '1640 ca.', 11924, 8083, 73.15,
    museum='Statens Museum for Kunst, Copenaghen', style='Barocco', genre='paesaggio', tags=['fiume', 'castello', 'pastori', 'mucche'])
add('lorrain-seaport-sunset', 'Porto di mare al tramonto', 'Port_de_mer_au_soleil_couchant_-_Claude_Gellée_dit_Le_Lorrain_-_Musée_du_Louvre_Peintures_INV_4715_;_MR_1740.jpg', 'Claude Lorrain', '1639', 10869, 7926, 28.41,
    museum='Louvre, Parigi', style='Barocco', genre='marina', tags=['porto', 'navi', 'tramonto', 'palazzi'])
add('avdvelde-pasture', 'Pascolo con cavalli e mucche', 'Adriaen_van_de_Velde,_Græsgang_med_heste_og_kvæg,_1660,_KMS3608,_Statens_Museum_for_Kunst.jpg', 'Adriaen van de Velde', '1660', 9330, 10043, 55.24,
    museum='Statens Museum for Kunst, Copenaghen', style="Secolo d'oro olandese", genre='paesaggio', tags=['prato', 'cavalli', 'mucche', 'animali'])
add('vanwittel-navona', 'Piazza Navona', 'Piazza_Navona,_Rome_(by_Gaspar_van_Wittel)_-_Thyssen-Bornemisza_Museum,_Madrid.jpg', 'Gaspar van Wittel', '1699', 14167, 6240, 28.16,
    museum='Museo Thyssen-Bornemisza, Madrid', style='Barocco', genre='veduta', tags=['Roma', 'piazza', 'mercato', 'fontane'])
add('panini-navona-fireworks', "Preparativi per i fuochi d'artificio in piazza Navona", "Préparation_du_feu_d'artifice_et_de_la_décoration_de_la_fête_donnée_sur_la_place_Navone_à_l'occasion_de_la_naissance_du_Dauphin_-_Paolo_Panini_-_Musée_du_Louvre_Peintures_INV_415_;_MR_375.jpg", 'Giovanni Paolo Panini', '1729', 10893, 4662, 20.99,
    museum='Louvre, Parigi', style='Barocco', genre='veduta', tags=['Roma', 'piazza', 'festa', 'folla'])
add('panini-teatro-argentina', 'Festa musicale al Teatro Argentina', 'Fête_musicale_donnée_par_le_cardinal_de_La_Rochefoucauld_au_théâtre_Argentina_de_Rome_en_1747_-_Paolo_Panini_-_Musée_du_Louvre_Peintures_INV_414.jpg', 'Giovanni Paolo Panini', '1747', 10475, 8326, 35.83,
    museum='Louvre, Parigi', style='Barocco', genre='interno', tags=['teatro', 'musica', 'folla', 'palchi'])
add('panini-ancient-rome', 'Galleria di vedute della Roma antica', 'Galerie_de_vues_de_la_Rome_antique_-_Giovanni_Paolo_Pannini_-_Musée_du_Louvre_Peintures_RF_1944_21.jpg', 'Giovanni Paolo Panini', '1758', 10688, 7804, 26.19,
    museum='Louvre, Parigi', style='Barocco', genre='interno', tags=['galleria', 'quadri', 'statue', 'Roma'])
add('panini-modern-rome', 'Galleria di vedute della Roma moderna', 'Galerie_de_vues_de_la_Rome_moderne_-_Giovanni_Paolo_Pannini_-_Musée_du_Louvre_Peintures_RF_1944_22.jpg', 'Giovanni Paolo Panini', '1759', 10587, 7882, 31.49,
    museum='Louvre, Parigi', style='Barocco', genre='interno', tags=['galleria', 'quadri', 'statue', 'Roma'])
add('panini-roman-ruins', 'Rovine romane con figure', 'Roman_Ruins_with_Figures_by_Giovanni_Paolo_Panini.jpg', 'Giovanni Paolo Panini', '1730 ca.', 14813, 11491, 59.65,
    museum=None, style='Barocco', genre='capriccio architettonico', tags=['rovine', 'piramide', 'colonne'])
add('vanhaecht-van-der-geest', 'La galleria di Cornelis van der Geest', 'Willem_van_Haecht_-_The_Gallery_of_Cornelis_van_der_Geest,_1628.jpg', 'Willem van Haecht', '1628', 30000, 21806, 508.69,
    museum='Rubenshuis, Anversa', style='Barocco fiammingo', genre='interno', tags=['galleria', 'quadri', 'collezione', 'folla'])
add('dehooch-delft-courtyard', 'Cortile a Delft', 'Pieter_de_Hooch_012.jpg', 'Pieter de Hooch', '1658 ca.', 16193, 18755, 98.67,
    museum=None, style="Secolo d'oro olandese", genre='interno', tags=['cortile', 'casa', 'bambina', 'Delft'])
add('vernet-ponte-rotto', 'Il Ponte Rotto a Roma', 'Le_Ponte_Rotto_à_Rome_-_Claude-Joseph_Vernet_-_Musée_du_Louvre_Peintures_INV_8348_;_MR_2625.jpg', 'Claude-Joseph Vernet', '1745', 10330, 5022, 25.23,
    museum='Louvre, Parigi', style='Barocco', genre='veduta', tags=['Roma', 'fiume', 'ponte'])
add('turner-nantes', "Nantes dall'Île Feydeau", 'J._M._W._Turner_-_Nantes_from_the_Ile_Feydeau_(1830).jpg', 'J. M. W. Turner', '1830', 33245, 22133, 182.53,
    museum='Château des ducs de Bretagne, Nantes', style='Romanticismo', genre='veduta', tags=['città', 'fiume', 'mercato', 'barche'])

# ---------------- Capolavori scelti (alla risoluzione più alta su Commons, anche sotto la soglia) ----------------
add('leonardo-lady-ermine', "La dama con l'ermellino", 'Lady_with_an_Ermine_-_Leonardo_da_Vinci_-_Google_Art_Project.jpg', 'Leonardo da Vinci', '1489-1491', 30894, 41545, 102.16,
    museum='Museo Czartoryski, Cracovia', style='Rinascimento', genre='ritratto', tags=['ermellino', 'animali', 'Leonardo', 'capolavoro'])
add('leonardo-mona-lisa', 'La Gioconda', 'Mona_Lisa,_by_Leonardo_da_Vinci,_from_C2RMF_retouched.jpg', 'Leonardo da Vinci', '1503-1519', 7479, 11146, 89.94,
    museum='Louvre, Parigi', style='Rinascimento', genre='ritratto', tags=['Monna Lisa', 'paesaggio', 'Leonardo', 'capolavoro'])
add('leonardo-last-supper', "L'Ultima Cena", 'La_última_cena_(1494-1498)_de_Leonardo_da_Vinci_-_Basilica_di_Santa_Maria_delle_Grazie_(Milán).jpg', 'Leonardo da Vinci', '1494-1498', 43047, 23404, 649.33,
    museum='Santa Maria delle Grazie, Milano', style='Rinascimento', genre='affresco', tags=['affresco', 'Milano', 'Leonardo', 'capolavoro'])
add('leonardo-st-anne', "Sant'Anna, la Madonna e il Bambino con l'agnellino", 'Leonardo_da_Vinci_-_Virgin_and_Child_with_St_Anne_C2RMF_retouched.jpg', 'Leonardo da Vinci', '1503-1519', 13295, 17828, 71.71,
    museum='Louvre, Parigi', style='Rinascimento', genre='pittura religiosa', tags=['agnello', 'montagne', 'Leonardo', 'capolavoro'])
add('leonardo-st-john', 'San Giovanni Battista', 'Leonardo_da_Vinci_-_Saint_John_the_Baptist_C2RMF_retouched.jpg', 'Leonardo da Vinci', '1513-1516', 10446, 13522, 90.13,
    museum='Louvre, Parigi', style='Rinascimento', genre='pittura religiosa', tags=['Leonardo', 'capolavoro'])
add('leonardo-annunciation', "L'Annunciazione", 'Annunciation_(Leonardo_c._1472–1476).jpg', 'Leonardo da Vinci', '1472-1476', 7776, 3569, 26.20,
    museum='Galleria degli Uffizi, Firenze', style='Rinascimento', genre='pittura religiosa', tags=['angelo', 'giardino', 'Leonardo', 'capolavoro'])
add('vangogh-sunflowers-munich', 'Vaso con dodici girasoli', 'Sunflowers_-_Vincent_van_Gogh_(Munich_version).jpg', 'Vincent van Gogh', '1888', 5388, 6828, 17.01,
    museum='Neue Pinakothek, Monaco di Baviera', style='Post-impressionismo', genre='natura morta', tags=['girasoli', 'fiori', 'vaso', 'van Gogh', 'capolavoro'])
add('vangogh-sunflowers-london', 'I girasoli', 'Vincent_van_Gogh_-_Sunflowers_(1888,_National_Gallery_London).jpg', 'Vincent van Gogh', '1888', 4719, 6000, 11.49,
    museum='National Gallery, Londra', style='Post-impressionismo', genre='natura morta', tags=['girasoli', 'fiori', 'vaso', 'van Gogh', 'capolavoro'])

# ---------------- Van Gogh (immagini Commons da almeno 10.000 px) ----------------
add('vangogh-starry-night', 'Notte stellata', 'Van_Gogh_-_Starry_Night_-_Google_Art_Project.jpg', 'Vincent van Gogh', '1889', 44567, 35291, 663.94,
    museum='MoMA, New York', style='Post-impressionismo', genre='paesaggio notturno', tags=['stelle', 'notte', 'cipresso', 'villaggio', 'van Gogh'])
add('vangogh-bedroom', 'La camera da letto', 'Vincent_van_Gogh_-_De_slaapkamer_-_Google_Art_Project.jpg', 'Vincent van Gogh', '1888', 30000, 23803, 252.37,
    museum='Van Gogh Museum, Amsterdam', style='Post-impressionismo', genre='interno', tags=['camera', 'letto', 'sedie', 'Arles', 'van Gogh'])
add('vangogh-irises', 'Iris', 'Irises-Vincent_van_Gogh.jpg', 'Vincent van Gogh', '1889', 11516, 8801, 33.74,
    museum='Getty Center, Los Angeles', style='Post-impressionismo', genre='paesaggio', tags=['fiori', 'iris', 'giardino', 'van Gogh'])
add('vangogh-roses', 'Rose', 'Roses_-_Vincent_van_Gogh.JPG', 'Vincent van Gogh', '1890', 18086, 14324, 97.14,
    museum='National Gallery of Art, Washington', style='Post-impressionismo', genre='natura morta', tags=['fiori', 'rose', 'vaso', 'van Gogh'])
add('vangogh-farmhouse-provence', 'Casa colonica in Provenza', 'Farmhouse_in_Provence,_1888,_Vincent_van_Gogh,_NGA.jpg', 'Vincent van Gogh', '1888', 16428, 12392, 97.23,
    museum='National Gallery of Art, Washington', style='Post-impressionismo', genre='paesaggio', tags=['campagna', 'casa', 'papaveri', 'estate', 'van Gogh'])
add('vangogh-green-wheat', 'Campi di grano verde ad Auvers', 'Vincent_van_Gogh_-_Green_Wheat_Fields,_Auvers_(1890).jpg', 'Vincent van Gogh', '1890', 16266, 12770, 88.56,
    museum='National Gallery of Art, Washington', style='Post-impressionismo', genre='paesaggio', tags=['grano', 'campi', 'nuvole', 'van Gogh'])
add('vangogh-wheat-cypresses', 'Campo di grano con cipressi', 'Vincent_van_Gogh_-_Wheat_Field_with_Cypresses_(National_Gallery_version).jpg', 'Vincent van Gogh', '1889', 10882, 8653, 55.80,
    museum='National Gallery, Londra', style='Post-impressionismo', genre='paesaggio', tags=['grano', 'cipressi', 'montagne', 'nuvole', 'van Gogh'])
add('vangogh-plane-trees', 'I grandi platani (gli stradini a Saint-Rémy)', 'The_Large_Plane_Trees_(Road_Menders_at_Saint-Rémy),_by_Vincent_van_Gogh,_Cleveland_Museum_of_Art,_1947.209.jpg', 'Vincent van Gogh', '1889', 14395, 11486, 51.73,
    museum='Cleveland Museum of Art', style='Post-impressionismo', genre='veduta', tags=['alberi', 'strada', 'paese', 'van Gogh'])
add('vangogh-two-poplars', 'Due pioppi sulle Alpilles', 'Two_Poplars_in_the_Alpilles_near_Saint-Rémy,_by_Vincent_Van_Gogh,_Cleveland_Museum_of_Art,_1958.32.jpg', 'Vincent van Gogh', '1889', 10950, 14640, 45.71,
    museum='Cleveland Museum of Art', style='Post-impressionismo', genre='paesaggio di montagna', tags=['pioppi', 'montagne', 'autunno', 'van Gogh'])
add('vangogh-olive-cabins', 'Capanne tra ulivi e cipressi', 'Vincent_van_Gogh_-_Cabanes_de_bois_parmi_les_oliviers_et_cyprés_(F_623).jpg', 'Vincent van Gogh', '1889', 14687, 10915, 43.90,
    museum=None, style='Post-impressionismo', genre='paesaggio', tags=['ulivi', 'cipressi', 'capanne', 'van Gogh'])
add('vangogh-boats-saintes-maries', 'Barche da pesca sulla spiaggia di Saintes-Maries', 'Vincent_van_Gogh_-_Vissersboten_op_het_strand_van_Les_Saintes-Maries-de-la-Mer_(1888).jpg', 'Vincent van Gogh', '1888', 11733, 9435, 49.83,
    museum='Van Gogh Museum, Amsterdam', style='Post-impressionismo', genre='marina', tags=['barche', 'spiaggia', 'mare', 'van Gogh'])

# ---------------- Selezione di serie ----------------
# Solo scene affollate adatte ai bambini (paesaggi, vedute, mercati, feste, interni: stile fiammingo)
# e solo immagini abbastanza grandi da regalare dettagli nuovi a ogni zoom sul televisore.
# Le voci sopra restano come riferimento; qui si tengono quelle che vanno nella galleria.
MIN_SIDE = 10000                      # px sul lato lungo: almeno ~5 raddoppi di zoom su 1920×1080
EXCLUDE_GENRES = {"fantastico / religioso", "ritratto di gruppo"}
A = [a for a in A
     if "capolavoro" in a["tags"]          # scelti uno per uno: alla risoluzione migliore che c'è
     or (a["kidSafe"]
         and max(a["width"], a["height"]) >= MIN_SIDE
         and a["genre"] not in EXCLUDE_GENRES
         and not a["id"].startswith("wd-"))]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps({"version": 1, "artworks": A}, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"{len(A)} opere -> {OUT}")
