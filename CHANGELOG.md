# Changelog

## 1.14
- Gli avvisi GPX vengono generati per ogni passaggio entro 30 m dal waypoint, quindi funzionano su tutti i giri ripetuti.
- I passaggi consecutivi sullo stesso waypoint vengono deduplicati per evitare doppi avvisi.
- Il NavigationLocationService è la fonte autorevole del progresso durante la navigazione.
- L'Activity usa il NavigationFix prodotto dal servizio invece di ricalcolare il matching con un secondo motore.
- Il progresso viene salvato periodicamente e ripristinato dopo riapertura dell'app o ricreazione del servizio.
- Aggiunti test per avvisi su 6 giri e ripristino del progresso su tracce ripetute.

## 1.13
- Zoom completamente variabile durante la simulazione.
- Rimossa la soglia minima che riportava automaticamente la mappa allo zoom precedente.
- Pinch libero: durante il gesto la camera non viene ricentrata forzatamente.
- Al termine del pinch il pallino torna al centro mantenendo esattamente lo zoom scelto.
- I pulsanti + e - cambiano immediatamente lo zoom anche durante la simulazione.

## 1.12
- In simulazione il pallino arancione resta centrato sullo schermo.
- È la mappa a scorrere sotto al pallino durante l'avanzamento.
- La simulazione mantiene zoom, inclinazione e orientamento correnti.
- Impostato uno zoom minimo per evitare una simulazione troppo distante.

## 1.11
- Aggiunta voce "Guida comandi" nel menu laterale con spiegazione dei pulsanti di modifica.
- Aggiunta "Simula giro" alle azioni di ogni traccia GPX.
- Pallino arancione dedicato alla simulazione, separato dal GPS reale.
- Comandi simulazione: Pausa/Riprendi, 1×, 2×, 5×, 10× e Stop.
- Durante la simulazione vengono aggiornati km percorsi, km alla fine e pannello avvisi.
- Gli avvisi TV e Pericolo possono produrre vibrazione/bip anche durante la simulazione.
- La simulazione viene fermata automaticamente prima di avviare la navigazione reale o cambiare traccia.

## 1.10
- Aggiunta funzione "Unisci con un'altra traccia" dentro Modifica traccia.
- La seconda traccia viene invertita automaticamente se il suo punto finale è più vicino alla fine della prima.
- Aggiunta conferma con distanza del collegamento tra le due tracce.
- Aggiunta funzione "Ripeti traccia" da 2 a 30 giri, con valore iniziale 6.
- Le tracce originali non vengono modificate: viene sempre creato un nuovo GPX.
- Aggiunti test JVM per unione, inversione automatica e ripetizione dei giri.

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
