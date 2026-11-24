# FireMuseum

App per **Amazon Fire TV** (e Android TV) che fa da museo a zoom infinito: puoi esplorare dipinti e immagini enormi, da 6.000 fino a 46.000 px, interamente con il telecomando. Include:

- **Zoom profondo fluido** su piramidi di tasselli. Nessuna immagine viene mai caricata per intero in RAM.
- **Slideshow "documentario"**: la telecamera vola sui dettagli con Ken Burns e didascalie.
- **Ricerca** per titolo, autore, anno, stile, genere, museo e tag (anche a voce, con il microfono della tastiera Fire TV).
- **Scheda dell'opera**: autore, anno, stile/movimento, genere, museo, tecnica, dimensioni e licenza, completate in automatico da Wikidata.
- **Gioco "cerca e trova"** sulle immagini: mirino, indizi progressivi, punteggio, obiettivi creati da te.
- **Finestre**: dentro un quadro che contiene altri quadri ci si *entra* zoomando, senza stacchi, e si continua all'infinito.
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

| Tasto | Esplorazione | Gioco |
|---|---|---|
| ◀ ▲ ▼ ▶ | tocco = piccolo passo preciso · tenuto = scorre e **accelera** (fino a ~1,7 schermi/s) | uguale |
| OK | tocco = zoom ×2 animato · tenuto = **zoom continuo che accelera** | tocco = «è qui!» · tenuto = zoom |
| ⏩ / ⏪ | zoom avanti / indietro (tocco ×2, tenuto continuo) | uguale |
| INDIETRO | zoom out → esce dalla finestra → (×2) torna alla galleria | uguale |
| ⏯ | avvia / ferma lo slideshow | |
| ≡ MENU | info, gioco, indizio, aggiungi obiettivo, scarica originale, preferiti, viaggio infinito | |

Gamepad: levetta sinistra = spostamento analogico (risposta cubica); grilletti o levetta destra = zoom.

Le velocità sono in pixel *dello schermo*, quindi la sensazione è la stessa a ogni livello di zoom. Puoi regolarle in *Impostazioni → Velocità movimento*.

## Da dove arrivano i pixel

| Sorgente | Come | Dettaglio massimo |
|---|---|---|
| **Piramide `.zip` sul disco** (`pyramids/<id>.zip`) | preparata sul PC con `tools/make_pyramids.py` (libvips) | piena risoluzione, il più veloce |
| **Piramide in LAN** | `tools/make_pyramids.py --serve` + *Impostazioni → Server LAN* | piena risoluzione |
| **IIIF** (Rijksmuseum/Micrio, National Gallery of Art, …) | il server dei musei taglia i tasselli | piena risoluzione |
| **Wikimedia Commons** | anteprime standard da 1280 e 3840 px, poi l'originale scaricato su richiesta (≡ → *Scarica l'originale*) | 3840 px; piena con l'originale |

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

## Finestre e "infinite picture"

Wikidata ha già, compilate a mano dalla comunità, **migliaia di regioni** "raffigura" (P180) con la posizione nel quadro (qualificatore P2677). L'app le usa in due modi:

- come **obiettivi del gioco** già pronti: nel catalogo incluso ci sono 52 dipinti e 1.219 obiettivi;
- come **finestre**, quando l'oggetto raffigurato è a sua volta un dipinto con immagine. Esempi: *Galleria del Louvre* di Morse, *Tribuna degli Uffizi* di Zoffany, Salon Carré, Gildemeester, van der Geest. Nel catalogo incluso le finestre sono 137.

Il *Viaggio infinito* (≡ o la galleria) vola da un quadro all'altro passando per queste finestre.

## Compilare

- Android Studio Ladybug o successivo (AGP 8.7, Kotlin 2.0, JDK 17), oppure `gradle :app:assembleRelease` con Gradle 8.10.
- La CI (`.github/workflows/android.yml`) compila a ogni push. Sui tag `v*` pubblica la release con l'APK firmato, usando i segreti `FM_KEYSTORE_BASE64` e `FM_KEYSTORE_PASSWORD`.

Struttura e convenzioni del codice: vedi [AGENTS.md](AGENTS.md). Fonti di immagini e dati: vedi [docs/FONTI.md](docs/FONTI.md).
