# Changelog

## 1.9
- ImportService entra immediatamente in foreground in ogni ramo di avvio.
- Matching e feedback acustico/vibrazione restano attivi nel servizio GPS anche senza UI.
- Reset del progresso a ogni nuova navigazione e recupero dopo fuori-percorso prolungato.
- Servizio posizione `START_NOT_STICKY`; eventuale sessione viene ripristinata dall'Activity.
- Richiesta runtime di `POST_NOTIFICATIONS` su Android 13+ prima della navigazione background.
- `ToneGenerator` protetto da `runCatching` per evitare crash su dispositivi incompatibili.

 — GPX NAV Pro

## 1.8
- NavigationEngine reso testabile su JVM con `NavigationSample` e distanza Haversine.
- Segmenti della traccia precalcolati per velocizzare il matching.
- Matching con progresso monotono, bearing GPS e accuracy.
- Test automatici per anelli, fuori-percorso e avvisi.
- Navigazione GPS spostata in foreground service per continuare durante chiamate e cambio app.
- Import GPX e PMTiles spostati in foreground service, indipendente dal ciclo di vita della Activity.
- Validazione PMTiles v3 e lettura dei bounds direttamente dall'header.
- Avvisi acustici/vibrazione per TV, pericolo e ingresso fuori-percorso.
- Release solo `arm64-v8a`, R8/minify e shrink resources attivi con regole MapLibre.
- Workflow GitHub Actions legge automaticamente `versionName` dal Gradle.

## 1.7
- Import GPX più sicuro e compatibilità LocationListener Android 7–10.
- Controllo spazio e validazione iniziale PMTiles.

## 1.6
- Salvataggio impostazioni traccia torna direttamente alla navigazione.

## 1.5
- Info traccia, colore per pendenza, firma e versione nel menu.

## 1.4
- Informazioni altimetriche e colorazione pendenza.

## 1.3
- Apertura diretta dei file GPX da Android.
