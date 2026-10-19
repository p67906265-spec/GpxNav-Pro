# MapLibre usa JNI, reflection e classi referenziate da JSON/style runtime.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Componenti Android dichiarati nel manifest.
-keep class com.example.gpxnavpro.NavigationLocationService { *; }
-keep class com.example.gpxnavpro.ImportService { *; }

# Mantiene i model GPX usati dal parser e dalla navigazione.
-keep class com.example.gpxnavpro.GpxPoint { *; }
-keep class com.example.gpxnavpro.GpxWaypoint { *; }
-keep class com.example.gpxnavpro.GpxRoute { *; }

# Android Auto (Car App Library): classi create dal sistema via manifest/riflessione.
-keep class androidx.car.app.** { *; }
-dontwarn androidx.car.app.**
-keep class com.example.gpxnavpro.car.** { *; }
-keep class com.example.gpxnavpro.TrackRecorderService { *; }
