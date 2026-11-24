# AGENTS.md: guida per agenti e contributori

Questo file spiega cos'è il progetto, come è organizzato e quali regole non vanno violate. Leggilo prima di modificare il codice.

## Cos'è

App Android TV / **Fire TV** in Kotlin, a View classiche (niente Compose, niente Leanback), per esplorare con il telecomando immagini da 6k a 46k px. È fatta a strati:

```
ui/        MainActivity (galleria, ricerca, filtri, impostazioni) · ViewerActivity (overlay, menu, tasti)
viewer/    DeepZoomView (rendering a tasselli, fisica input, portali) · Camera/Flight (volo van Wijk) · Slideshow
game/      Game (cerca e trova: mirino, indizi, punteggio)
tiles/     TileSource (griglia uniforme 2^k) · IiifSource · DziSource (http/dir/zip) · LocalRegionSource
           CommonsSource (strati 1280/3840/originale) · SourceFactory (priorità piramidi) · TileLoader (coda a priorità)
cache/     SegmentCache (tasselli log-structured su USB) · BlobStore (file grandi) · SpaceGovernor (pulizia) · MemoryTileCache
storage/   StorageManager (sceglie il volume esterno, ascolta mount/unmount)
catalog/   CatalogRepository (assets + disco + obiettivi utente) · Wikidata (metadati, regioni P2677, finestre)
model/     Artwork, Source, Target, Portal, NRect (coordinate normalizzate 0..1)
tools/     script Python per il PC (solo stdlib): make_pyramids.py, build_catalog.py, seed/make_seed.py
```

## Regole da non violare

1. **Nessun dato pesante sulla memoria interna.** Tasselli, originali e piramidi vanno solo su `StorageManager.current`. Il ripiego interno è limitato (`internalFallbackBytes`) e lì non si scaricano originali (`ViewerActivity.downloadOriginal`).
2. **Mai un'immagine intera in RAM.** Si decodifica solo a regioni (`BitmapRegionDecoder`) o si leggono tasselli. Niente `BitmapFactory.decodeFile` su file grandi senza `inJustDecodeBounds`/`inSampleSize`.
3. **Pulizia incrementale, non totale.** Si elimina un segmento o un blob alla volta passando da `SpaceGovernor`. I blob `pinned` (preferiti) non si toccano.
4. **Velocità di pan e zoom in unità schermo** (px/s, raddoppi/s). I tasti si gestiscono con lo stato premuto/rilasciato e si ignora `repeatCount` (il Fire TV ripete i KEY_DOWN).
5. **Rete educata.** Al massimo 4 richieste per host, back-off su 429/503 (`Net`). Per Wikimedia si usano solo le larghezze di anteprima standard (20…330, 500, 960, 1280, 1920, 3840): le altre sono limitate dal 2026. Originali uno alla volta.
6. **Coordinate di annotazioni e portali normalizzate** (0..1, formato `NRect`, compatibile con IIIF `pct:` / Wikidata P2677).
7. **Contenuti per bambini**: ogni voce di catalogo ha `kidSafe` e `warning`. Le voci generate in automatico partono con `kidSafe=false`.
8. Testi dell'interfaccia in **italiano**.

## Griglia dei tasselli (contratto `TileSource`)

- Il livello `k` corrisponde a un sotto-campionamento `2^k`; `k=0` è la piena risoluzione. Al livello `k` l'immagine misura `ceil(W/2^k) × ceil(H/2^k)`.
- Il tassello `(x,y)` copre `[x·ts, (x+1)·ts)` in pixel di livello. DZI ha `level = maxLevel − k` e l'overlap viene escluso con `crop()`.
- `minK()` è il livello più fine disponibile *adesso*: può scendere, per esempio dopo il download dell'originale.
- Chiave cache: `"$id/$k/$x/$y"`. L'`id` della sorgente deve essere stabile tra le sessioni.

## Formato catalogo (`assets/catalog*.json`, `<volume>/catalog/*.json`)

```json
{ "version": 1, "artworks": [ {
  "id": "bruegel-childrens-games", "title": "Giochi di bambini", "author": "…", "year": "1560",
  "style": "…", "genre": "…", "museum": "…", "tags": ["…"], "kidSafe": true, "warning": null,
  "width": 41519, "height": 29944, "bytes": 255433754,
  "source": {"type": "commons", "file": "Children's_Games_(Bruegel).jpg"},
  "qid": "Q…", "targets": [{"label": "…", "x": 0.1, "y": 0.2, "w": 0.02, "h": 0.03, "hint": "…", "difficulty": 2}],
  "portals": [{"x": 0.3, "y": 0.1, "w": 0.05, "h": 0.06, "to": "wikidata:Q12418", "label": "…"}]
} ] }
```

I tipi di `source` sono `commons{file}`, `iiif{info}`, `dzi{url}` (http oppure un percorso relativo al volume, `.dzi` o `.zip`), `local{path}` e `wikidata{qid}` (risolto tramite P18). Il campo `portals[].to` accetta un id di catalogo, `commons:…`, `wikidata:Q…` o `iiif:…`. I file caricati dopo sovrascrivono quelli precedenti con lo stesso `id`.

## Build e CI

- Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, JDK 17, `minSdk 25` (Fire OS 6+), `targetSdk 34`.
- Nel repo non c'è il wrapper jar: la CI usa `gradle/actions/setup-gradle` con una versione fissata. In locale usa Android Studio.
- `.github/workflows/android.yml`: build + lint a ogni push e PR. Sui tag `v*` crea la release con l'APK. La firma arriva dai segreti `FM_KEYSTORE_BASE64` e `FM_KEYSTORE_PASSWORD` (PKCS12, alias `firemuseum`). Senza segreti si usa la chiave di debug.
- Rigenera il catalogo di serie con `python tools/seed/make_seed.py` e quello Wikidata con `python tools/build_catalog.py --wikidata-regions 10 --out app/src/main/assets/catalog_wikidata.json`.

## Dispositivi di riferimento

| Modello | Fire OS / API | RAM | Note |
|---|---|---|---|
| Fire TV Stick 4K Max (AFTKA) | 7.7 / 28 | 1,7 GB | ~1,9 GB liberi interni: non usarli |
| Fire TV Stick 4K 2ª gen (AFTKRT) | 8.1 / 30 | 1,7 GB | disco USB visto come `/storage/XXXX-XXXX` |

L'interfaccia è renderizzata a 1920×1080 anche sui modelli 4K.

## Idee aperte

- Rilevatore open-vocabulary (OWLv2 / Grounding DINO) eseguito sul PC sui tasselli, per proporre obiettivi automatici ("cane", "barca", "cappello rosso") da rivedere a mano.
- Download "offline fino al livello k" per le sorgenti IIIF.
- Prefetch del livello successivo lungo la traiettoria dei voli dello slideshow.
- Mosaici coerenti di più opere come livello unico (ricerca a livelli).
