GPX NAV PRO

Navigatore Android per tracce GPX con MapLibre, mappe online e PMTiles offline.

FUNZIONI PRINCIPALI
- Apertura e importazione GPX.
- Navigazione con progresso, distanza da partenza/arrivo e avvisi.
- Simulazione giro con pallino centrato e zoom libero.
- Modifica GPX: taglio, inversione, spostamento punti, unione tracce e ripetizione giri.
- Avvisi TV, GPM, Pericolo, ristoro e altri waypoint.
- Navigazione in foreground service per continuare durante cambio app/chiamate.
- Avvicinamento alla partenza con BRouter.
- Profilo BRouter configurabile: car-fast, trekking, fastbike.
- Mappe online oppure una mappa PMTiles offline selezionata.
- Opzione per mantenere lo schermo acceso mentre l'app è visibile.

MAPPA OFFLINE
La mappa PMTiles viene copiata nello spazio privato dell'app.
La versione attuale visualizza la cartografia vettoriale offline senza etichette
testuali, perché i glyph/font PBF non sono ancora inclusi nel pacchetto offline.

NOTA MAPPE MULTIPLE
La gestione di più mappe PMTiles contemporaneamente è prevista per la prossima
revisione strutturale. La versione corrente mantiene una sola mappa offline attiva.

BACKUP
Il backup Android dell'app è disabilitato per evitare di salvare preferenze con
percorsi locali non validi su un altro dispositivo.

BUILD
GitHub Actions esegue test JVM e lint prima della build release firmata.
La versione e il nome dell'APK vengono letti direttamente dal Gradle.
