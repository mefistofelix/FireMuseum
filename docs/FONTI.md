# Fonti di immagini e dati

Ultima verifica: 24 novembre 2026. Le dimensioni indicate sono quelle che risultano dalle API o dalle pagine delle fonti.

## Già nel catalogo incluso

| Gruppo | Opere | Risoluzione | Sorgente |
|---|---:|---|---|
| Joachim Beuckelaer (mercati, cucine, Quattro elementi) | 9 | 5.956–11.852 px | Commons |
| Bruegel, *Inside Bruegel Project* | 15 | 27.830–46.687 px (122–421 MB) | Commons |
| Bosch, *Bosch Project* (selezione) | 10 | 15.000–30.000 px | Commons |
| Avercamp, Canaletto, van der Heyden, Teniers, Panini, Steen | 7 | 4.610–16.952 px | Commons |
| Dipinti con regioni Wikidata (obiettivi + finestre) | 52 | variabile | Commons + Wikidata |
| Rijksmuseum IIIF (Micrio `PJEZO`, 14.645 × 12.158) | 1 | IIIF | Rijksmuseum |

Tutti i 41 nomi file Commons sono stati verificati via API: esistono e hanno le dimensioni dichiarate.

## Fonti nuove trovate (oltre al catalogo di partenza)

### 1. Wikidata: regioni annotate (P180 + P2677). È la fonte più utile per il gioco.
- Sono **1.728 dipinti con 4.494 regioni** "raffigura", con la posizione nel quadro scritta in formato IIIF `pct:x,y,w,h`.
- In circa **190 casi la regione è a sua volta un dipinto con immagine**. Queste regioni sono finestre già pronte per l'"infinite picture": Morse *Gallery of the Louvre* (38), Zoffany *Tribuna degli Uffizi* (29), *Salon Carré* (26 + 24), Panini *Roma antica* (25), Gildemeester (16), Panini *Roma moderna* (12), van der Geest (5).
- Strumento per aggiungere regioni: [Wikidata Image Positions](https://www.wikidata.org/wiki/User:Lucas_Werkmeister/Wikidata_Image_Positions). Le regioni aggiunte lì compaiono da sole nel gioco.
- Riferimenti: [P2677](https://www.wikidata.org/wiki/Property:P2677).

### 2. National Gallery of Art (Washington): open access CC0 con IIIF
- Il CSV [`published_images.csv`](https://github.com/NationalGalleryOfArt/opendata) contiene, per ogni immagine, URL IIIF, larghezza, altezza e flag openaccess. Basta filtrarlo per dimensione. `tools/build_catalog.py --nga 150` lo fa da solo.
- Server IIIF Image API 2 (`api.nga.gov/iiif/<uuid>/info.json`), verificato funzionante.

### 3. Rijksmuseum: IIIF tramite Micrio
- `https://iiif.micr.io/<id>/info.json` (IIIF v3, tasselli 1024). Gli id si ricavano dai record Linked Art di [data.rijksmuseum.nl](https://data.rijksmuseum.nl/). Esiste anche un'immagine della *Ronda di notte* da 717 gigapixel, consultabile su [rijksmuseum.nl/Nightwatch](https://www.rijksmuseum.nl/Nightwatch). Il suo `info.json` pubblico **non** è stato trovato: da verificare.

### 4. Wikimedia Commons: categorie da esplorare con `build_catalog.py --commons-category`
- `Gigapixel images from the Bosch Project` (52 file), `Gigapixel images from the Inside Bruegel Project` (16).
- `Paintings of markets by Joachim Beuckelaer`, `Google Art Project` (migliaia di dipinti in alta risoluzione: filtrare con `--min-side 8000`).
- Limite: dal 2026 Wikimedia accetta solo larghezze di anteprima standard (… 1280, 1920, 3840) e risponde **429** a chi scarica originali in modo automatico e veloce. Servono download sequenziali e un User-Agent con contatto.

### 5. Da valutare (accesso automatico non verificato)
- **Library of Congress**: [Panoramic maps](https://www.loc.gov/collections/panoramic-maps/), cioè vedute a volo d'uccello di città con migliaia di case e persone, molto alla Wimmelbild. Il server IIIF è `tile.loc.gov`, ma l'API di ricerca di loc.gov risponde con una verifica anti-bot: gli `info.json` vanno raccolti a mano dal browser e inseriti come `iiif`.
- **Art Institute of Chicago**: IIIF pubblico, ma l'API risponde con una verifica anti-bot ai client automatici.
- **David Rumsey Map Collection**, **Gallica (BnF)**, **Europeana**, **Getty**, **Yale Center for British Art**: tutti con IIIF. Vanno censiti per opera.
- **Wimmelbilder moderni** (MAMEI, Giu Calistro, ecc.): coperti da diritto d'autore, solo uso privato e solo come file locali (`source: local`).

## Strumenti

- `tools/build_catalog.py`: genera cataloghi extra da Commons, Wikidata e NGA.
- `tools/make_pyramids.py`: scarica gli originali in modo educato e li trasforma in piramidi `.zip` con libvips, poi li copia via adb o li serve in LAN.
- `tools/seed/make_seed.py`: rigenera il catalogo curato di serie.
