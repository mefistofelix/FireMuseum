# FireMuseum

App per **Amazon Fire TV** (e Android TV) che fa da museo a zoom infinito: puoi esplorare dipinti e immagini enormi, da 6.000 fino a 46.000 px, interamente con il telecomando. Include:

- **Zoom profondo fluido** su piramidi di tasselli. Nessuna immagine viene mai caricata per intero in RAM.
- **Slideshow "documentario"**: la telecamera vola sui dettagli con Ken Burns e didascalie.
- **Ricerca** per titolo, autore, anno, stile, genere, museo e tag (anche a voce, con il microfono della tastiera Fire TV).
- **Scheda dell'opera**: autore, anno, stile/movimento, genere, museo, tecnica, dimensioni e licenza, completate in automatico da Wikidata.
- **Inquadratura senza fasce vuote**: zoomando verso un angolo i bordi del quadro restano agganciati ai lati del televisore; le fasce della vista d'insieme prendono il colore medio di quello che si vede.
- **Galleria scelta per bambini**: scene affollate in stile fiammingo (paesaggi, vedute, mercati, feste), solo immagini da almeno 10.000 px.
- **Cache sul disco esterno USB**, mai sulla memoria interna, con eliminazione incrementale automatica quando lo spazio finisce.

> Progetto personale e non commerciale. Le opere antiche sono di pubblico dominio, ma le riproduzioni fotografiche possono avere condizioni d'uso proprie. «Gratis da vedere» non significa «libero da ripubblicare».

## Installazione (sideload)

1. Scarica l'APK dall'ultima [release](../../releases), oppure dagli artifact di Actions.
2. Su Fire TV: *Impostazioni → La mia Fire TV → Opzioni sviluppatore → Debug ADB* attivo.
3. Dal PC:
   ```
   adb connect <IP-del-Fire-TV>:5555
   adb install -r FireMuseum-x.y.z.apk
   ```
4. Collega un **disco o chiavetta USB** (via hub/OTG), formattato come *memoria portatile*. L'app lo usa da sola: `/<volume>/Android/data/it.firemuseum/files/`.

## Telecomando

| Tasto | Azione |
|---|---|
| ◀ ▲ ▼ ▶ | tocco = piccolo passo preciso · tenuto = scorre e **accelera** (fino a ~1,7 schermi/s) |
| OK | tocco = zoom ×2 animato · tenuto = **zoom continuo che accelera** |
| programma su / giù | zoom avanti / indietro (tocco ×2, tenuto continuo) |
| ⏩ / ⏪ | quadro successivo / precedente, con la didascalia in alto a sinistra |
| INDIETRO | zoom out → (×2) torna alla galleria |
| ⏯ | avvia / ferma lo slideshow |
| ≡ MENU | info, scarica originale, preferiti, quadro successivo/precedente |

Il volume resta al televisore.

Gamepad: levetta sinistra = spostamento analogico (risposta cubica); grilletti o levetta destra = zoom.

Le velocità sono in pixel *dello schermo*, quindi la sensazione è la stessa a ogni livello di zoom. Puoi regolarle in *Impostazioni → Velocità movimento*.

## Da dove arrivano i pixel

| Sorgente | Come | Dettaglio massimo |
|---|---|---|
| **Piramide `.zip` sul disco** (`pyramids/<id>.zip`) | preparata sul PC con `tools/make_pyramids.py` (libvips) | piena risoluzione, il più veloce |
| **Archivio MEGA** (predefinito) | piramidi già pronte di tutto il catalogo, lette un tassello alla volta dal link pubblico della cartella | piena risoluzione, nessun limite Wikimedia |
| **Piramide in LAN** | `tools/make_pyramids.py --serve` + *Impostazioni → Server LAN* | piena risoluzione |
| **IIIF** (Rijksmuseum/Micrio, National Gallery of Art, …) | il server dei musei taglia i tasselli | piena risoluzione |
| **Wikimedia Commons** | anteprime standard da 1280 e 3840 px, poi l'originale scaricato su richiesta (≡ → *Scarica l'originale*) | 3840 px; piena con l'originale |

### Archivio MEGA

Le sorgenti originali danno problemi di accesso: Wikimedia risponde 429 a chi scarica originali, LoC e AIC bloccano i client automatici. Per questo tutto il catalogo è preparato una volta sul PC e pubblicato in una cartella MEGA con link pubblico fisso, senza password:

- `index.json`: il catalogo completo. Per ogni opera indica da dove leggerla: `host: "mega"` con la piramide (`mirror`), oppure `host: "origin"` con la sorgente originale. Su MEGA vanno solo le opere Commons più larghe di 3840 px, che senza l'originale non arriverebbero a piena risoluzione. IIIF dei musei e immagini piccole restano sulla sorgente originale. Il campo `host` nel catalogo forza la scelta, e se MEGA non risponde l'app ripiega sull'originale;
- `thumbs.zip`: le anteprime da 400 px;
- `<id>.zip`: la piramide Deep Zoom di ogni opera (libvips `dzsave`, zip non compresso).

L'app legge la cartella senza account. MEGA cifra i file in AES-CTR, quindi l'app può decifrare a partire da qualsiasi posizione e chiede solo gli intervalli che servono: la directory dello zip e poi un tassello alla volta. Indice, anteprime e tasselli già visti restano sul disco USB. Il link si cambia da *Impostazioni → Archivio MEGA*; con il campo vuoto l'archivio è spento.

```
# sul PC: scarica gli originali (uno alla volta), crea piramidi e anteprime, poi carica
uv run --python 3.13 --with "pyvips[binary]" --with cryptography tools/mega_pack.py build
uv run --python 3.13 --with cryptography tools/mega_pack.py upload
uv run --python 3.13 --with cryptography tools/mega_pack.py check
```

`upload` usa rclone con il remote `mega:` (configurazione di RetroFire, oppure `FM_RCLONE` e `FM_RCLONE_CONFIG`) e carica in `mega:FireMuseum/archivio`. Il link della cartella va creato una sola volta dall'app MEGA (*Condividi → Copia link*) e salvato in `tools/mega_link.txt`. Dopo ogni caricamento lo script aggiunge ai file la chiave della condivisione, che rclone non mette, e rilegge tutto dal link come fa il Fire TV.

### Piramidi sul PC

Per i file giga di Commons (Bruegel 40.000 px, Bosch 30.000 px) conviene la piramide preparata sul PC. Il Fire TV ha 1,7 GB di RAM e non decodifica bene a regioni i JPEG *progressive* o da 400 MB. Inoltre Wikimedia limita (HTTP 429) chi scarica molti originali in fretta.

```
# sul PC (serve libvips nel PATH)
python tools/make_pyramids.py --list
python tools/make_pyramids.py bruegel-childrens-games bruegel-babel --contact tua@email --push
python tools/build_catalog.py --commons-category "Gigapixel images from the Bosch Project" --wikidata-regions 8 --nga 150 --push
```

## Cache e spazio

- **Tasselli**: segmenti *log-structured* da 32 MB con indice SQLite sul disco USB. Niente centinaia di migliaia di file piccoli (su exFAT sprecherebbero fino al 50% di spazio). Scrittura sequenziale, tasselli usati spesso ricopiati in testa (LRU approssimato).
- **Pulizia incrementale** (`SpaceGovernor`): ogni 16 MB scritti, o subito se il disco è pieno (ENOSPC), elimina un segmento alla volta, dal più vecchio, finché l'uso rientra nel budget e restano liberi max(1 GB, 5%) del volume. Solo dopo elimina gli originali non fissati, dal meno usato. I preferiti non vengono mai toccati.
- **Senza disco esterno**: c'è al massimo una piccola cache interna (200 MB, disattivabile) e gli originali **non** vengono scaricati.
- **Estrazione del disco**: l'app se ne accorge (broadcast `MEDIA_*`) e riapre la cache sul volume giusto. I record troncati vengono trattati come mancanti.

## Catalogo di serie

`tools/seed/make_seed.py` genera `catalog.json` e tiene solo le voci adatte ai bambini, con il lato lungo da almeno 10.000 px, escluse le scene fantastiche/infernali e i ritratti. L'indice dell'archivio MEGA aggiorna solo le voci già presenti: le altre opere dell'archivio non compaiono in galleria.

Il gioco "cerca e trova" e le finestre (quadri dentro i quadri) per ora sono stati tolti.

## Compilare

- Android Studio Ladybug o successivo (AGP 8.7, Kotlin 2.0, JDK 17), oppure `gradle :app:assembleRelease` con Gradle 8.10.
- La CI (`.github/workflows/android.yml`) compila a ogni push. Sui tag `v*` pubblica la release con l'APK firmato, usando i segreti `FM_KEYSTORE_BASE64` e `FM_KEYSTORE_PASSWORD`.

Struttura e convenzioni del codice: vedi [AGENTS.md](AGENTS.md). Fonti di immagini e dati: vedi [docs/FONTI.md](docs/FONTI.md).
