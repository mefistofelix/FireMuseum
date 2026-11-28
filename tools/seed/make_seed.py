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

# ---------------- Selezione di serie ----------------
# Solo scene affollate adatte ai bambini (paesaggi, vedute, mercati, feste, interni: stile fiammingo)
# e solo immagini abbastanza grandi da regalare dettagli nuovi a ogni zoom sul televisore.
# Le voci sopra restano come riferimento; qui si tengono quelle che vanno nella galleria.
MIN_SIDE = 10000                      # px sul lato lungo: almeno ~5 raddoppi di zoom su 1920×1080
EXCLUDE_GENRES = {"fantastico / religioso", "ritratto di gruppo"}
A = [a for a in A
     if a["kidSafe"]
     and max(a["width"], a["height"]) >= MIN_SIDE
     and a["genre"] not in EXCLUDE_GENRES
     and not a["id"].startswith("wd-")]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps({"version": 1, "artworks": A}, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"{len(A)} opere -> {OUT}")
