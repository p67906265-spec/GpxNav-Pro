#!/data/data/com.termux/files/usr/bin/bash
set -e

REPO="$HOME/GpxNav-Pro"
URL="https://github.com/p67906265-spec/GpxNav-Pro.git"

cd "$HOME"
if [ ! -d "$REPO/.git" ]; then
  git clone "$URL" "$REPO"
fi
cd "$REPO"
git pull --ff-only

python - <<'PY'
from pathlib import Path

# ---------- RouteAppearance.kt ----------
p = Path("app/src/main/java/com/example/gpxnavpro/RouteAppearance.kt")
s = p.read_text()
old = '''data class RouteAppearance(
    val color: String = "#005BBB",
    val width: Float = 7f,
    val showDirectionArrows: Boolean = true,
    val arrowSpacingMeters: Int = 300
)'''
new = '''data class RouteAppearance(
    val color: String = "#005BBB",
    val width: Float = 7f,
    val showDirectionArrows: Boolean = true,
    val arrowSpacingMeters: Int = 300,
    val useSlopeColors: Boolean = false
)'''
if old in s:
    s = s.replace(old, new, 1)
elif 'val useSlopeColors: Boolean' not in s:
    raise SystemExit("RouteAppearance.kt: struttura non riconosciuta")
p.write_text(s)

# ---------- MainActivity.kt ----------
p = Path("app/src/main/java/com/example/gpxnavpro/MainActivity.kt")
s = p.read_text()

# 1) Disegno: uniforme oppure per pendenza
old = '''        val coloredRouteFeatures = buildSlopeColoredRouteFeatures(route, appearance.color)

        style.addSource(
            GeoJsonSource(
                GPX_ROUTE_SOURCE_ID,
                org.maplibre.geojson.FeatureCollection.fromFeatures(coloredRouteFeatures)
            )
        )'''
new = '''        val routeFeatures = if (appearance.useSlopeColors) {
            buildSlopeColoredRouteFeatures(route, appearance.color)
        } else {
            buildUniformRouteFeatures(route, appearance.color)
        }

        style.addSource(
            GeoJsonSource(
                GPX_ROUTE_SOURCE_ID,
                org.maplibre.geojson.FeatureCollection.fromFeatures(routeFeatures)
            )
        )'''
if old in s:
    s = s.replace(old, new, 1)
elif 'buildUniformRouteFeatures(route, appearance.color)' not in s:
    raise SystemExit("MainActivity.kt: blocco drawRoute non trovato")

# 2) Funzione traccia uniforme
marker = '''    private fun buildSlopeColoredRouteFeatures(
        route: GpxRoute,
        fallbackColor: String
    ): List<Feature> {'''
uniform = '''    private fun buildUniformRouteFeatures(route: GpxRoute, color: String): List<Feature> {
        if (route.points.size < 2) return emptyList()
        val coordinates = route.points.map { point ->
            Point.fromLngLat(point.longitude, point.latitude)
        }
        return listOf(
            Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply {
                addStringProperty(SLOPE_COLOR_PROPERTY, color)
            }
        )
    }

'''
if 'private fun buildUniformRouteFeatures' not in s:
    if marker not in s:
        raise SystemExit("MainActivity.kt: funzione pendenza non trovata")
    s = s.replace(marker, uniform + marker, 1)

# 3) Pannello colore: aggiunge voce Pendenza
old = '''        val colorLabel = TextView(this).apply {
            text = "Colore traccia: ${colorName(current.color)}"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 12)
        }
        var selectedColor = current.color
        colorLabel.setOnClickListener {
            val names = ROUTE_COLORS.map { colorName(it) }.toTypedArray()
            val selectedIndex = ROUTE_COLORS.indexOf(selectedColor).coerceAtLeast(0)
            val colorDialog = AlertDialog.Builder(this)
                .setTitle("Colore traccia")
                .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                    selectedColor = ROUTE_COLORS[which]
                    colorLabel.text = "Colore traccia: ${colorName(selectedColor)}"
                    colorLabel.setTextColor(android.graphics.Color.parseColor(selectedColor))
                    dialog.dismiss()
                }
                .create()
            colorDialog.setOnShowListener { styleBlueDialog(colorDialog) }
            colorDialog.show()
        }
        colorLabel.setTextColor(android.graphics.Color.parseColor(selectedColor))
        container.addView(colorLabel)'''
new = '''        var selectedColor = current.color
        var useSlopeColors = current.useSlopeColors
        val colorLabel = TextView(this).apply {
            text = if (useSlopeColors) "Colore traccia: Pendenza" else "Colore traccia: ${colorName(selectedColor)}"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 12)
            setTextColor(
                if (useSlopeColors) android.graphics.Color.WHITE
                else android.graphics.Color.parseColor(selectedColor)
            )
        }
        colorLabel.setOnClickListener {
            val names = (ROUTE_COLORS.map { colorName(it) } + "Pendenza").toTypedArray()
            val selectedIndex = if (useSlopeColors) {
                ROUTE_COLORS.size
            } else {
                ROUTE_COLORS.indexOf(selectedColor).coerceAtLeast(0)
            }
            val colorDialog = AlertDialog.Builder(this)
                .setTitle("Colore traccia")
                .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                    if (which == ROUTE_COLORS.size) {
                        useSlopeColors = true
                        colorLabel.text = "Colore traccia: Pendenza"
                        colorLabel.setTextColor(android.graphics.Color.WHITE)
                    } else {
                        useSlopeColors = false
                        selectedColor = ROUTE_COLORS[which]
                        colorLabel.text = "Colore traccia: ${colorName(selectedColor)}"
                        colorLabel.setTextColor(android.graphics.Color.parseColor(selectedColor))
                    }
                    dialog.dismiss()
                }
                .create()
            colorDialog.setOnShowListener { styleBlueDialog(colorDialog) }
            colorDialog.show()
        }
        container.addView(colorLabel)'''
if old in s:
    s = s.replace(old, new, 1)
elif 'var useSlopeColors = current.useSlopeColors' not in s:
    raise SystemExit("MainActivity.kt: pannello colore non trovato")

# 4) Salvataggio impostazione nel data class
old = '''                val appearance = RouteAppearance(
                    color = selectedColor,
                    width = selectedWidth.toFloat(),
                    showDirectionArrows = arrowsCheck.isChecked,
                    arrowSpacingMeters = selectedSpacing
                )'''
new = '''                val appearance = RouteAppearance(
                    color = selectedColor,
                    width = selectedWidth.toFloat(),
                    showDirectionArrows = arrowsCheck.isChecked,
                    arrowSpacingMeters = selectedSpacing,
                    useSlopeColors = useSlopeColors
                )'''
if old in s:
    s = s.replace(old, new, 1)
elif 'useSlopeColors = useSlopeColors' not in s:
    raise SystemExit("MainActivity.kt: salvataggio appearance non trovato")

# 5) Lettura preferenza pendenza
old = '''            color = preferences.getString("${prefix}_color", "#005BBB") ?: "#005BBB",
            width = preferences.getFloat("${prefix}_width", 7f),
            showDirectionArrows = preferences.getBoolean("${prefix}_arrows", true),
            arrowSpacingMeters = preferences.getInt("${prefix}_spacing", 300)
        )'''
new = '''            color = preferences.getString("${prefix}_color", "#005BBB") ?: "#005BBB",
            width = preferences.getFloat("${prefix}_width", 7f),
            showDirectionArrows = preferences.getBoolean("${prefix}_arrows", true),
            arrowSpacingMeters = preferences.getInt("${prefix}_spacing", 300),
            useSlopeColors = preferences.getBoolean("${prefix}_slope_colors", false)
        )'''
if old in s:
    s = s.replace(old, new, 1)
elif '"${prefix}_slope_colors"' not in s:
    raise SystemExit("MainActivity.kt: loadRouteAppearance non trovato")

# 6) Scrittura preferenza pendenza
old = '''            .putString("${prefix}_color", appearance.color)
            .putFloat("${prefix}_width", appearance.width)
            .putBoolean("${prefix}_arrows", appearance.showDirectionArrows)
            .putInt("${prefix}_spacing", appearance.arrowSpacingMeters)
            .apply()'''
new = '''            .putString("${prefix}_color", appearance.color)
            .putFloat("${prefix}_width", appearance.width)
            .putBoolean("${prefix}_arrows", appearance.showDirectionArrows)
            .putInt("${prefix}_spacing", appearance.arrowSpacingMeters)
            .putBoolean("${prefix}_slope_colors", appearance.useSlopeColors)
            .apply()'''
if old in s:
    s = s.replace(old, new, 1)
elif '.putBoolean("${prefix}_slope_colors", appearance.useSlopeColors)' not in s:
    raise SystemExit("MainActivity.kt: saveRouteAppearance non trovato")

# 7) Apertura diretta GPX (richiesta precedente)
marker = '''            loadInstalledMap()
            updateScaleBar()
        }
    }

    // ========================================================='''
replacement = '''            loadInstalledMap()
            updateScaleBar()
        }

        handleIncomingGpxIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingGpxIntent(intent)
    }

    private fun handleIncomingGpxIntent(incomingIntent: Intent?) {
        if (incomingIntent?.action != Intent.ACTION_VIEW) return
        val uri = incomingIntent.data ?: return
        importGpx(uri)
    }

    // ========================================================='''
if 'private fun handleIncomingGpxIntent' not in s:
    if marker not in s:
        raise SystemExit("MainActivity.kt: punto onCreate per GPX esterno non trovato")
    s = s.replace(marker, replacement, 1)

p.write_text(s)

# ---------- AndroidManifest.xml ----------
p = Path("app/src/main/AndroidManifest.xml")
s = p.read_text()
if 'android:launchMode="singleTop"' not in s:
    s = s.replace(
        'android:exported="true"\n            android:screenOrientation="portrait"',
        'android:exported="true"\n            android:launchMode="singleTop"\n            android:screenOrientation="portrait"',
        1
    )

launcher = '''            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>'''
filters = launcher + '''

            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" android:mimeType="application/gpx+xml" />
                <data android:scheme="file" android:mimeType="application/gpx+xml" />
            </intent-filter>

            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" android:mimeType="application/xml" android:pathPattern=".*\\.gpx" />
                <data android:scheme="content" android:mimeType="text/xml" android:pathPattern=".*\\.gpx" />
                <data android:scheme="content" android:mimeType="application/octet-stream" android:pathPattern=".*\\.gpx" />
                <data android:scheme="file" android:mimeType="application/xml" android:pathPattern=".*\\.gpx" />
                <data android:scheme="file" android:mimeType="text/xml" android:pathPattern=".*\\.gpx" />
                <data android:scheme="file" android:mimeType="application/octet-stream" android:pathPattern=".*\\.gpx" />
            </intent-filter>'''
if 'application/gpx+xml' not in s:
    if launcher not in s:
        raise SystemExit("AndroidManifest.xml: launcher non trovato")
    s = s.replace(launcher, filters, 1)
p.write_text(s)

# ---------- Versione ----------
p = Path("app/build.gradle.kts")
s = p.read_text()
s = s.replace('versionCode = 3', 'versionCode = 4')
s = s.replace('versionName = "1.2"', 'versionName = "1.3"')
p.write_text(s)

print("OK: aggiornamento GpxNav Pro applicato")
PY

git diff --check
git add app/src/main/AndroidManifest.xml app/src/main/java/com/example/gpxnavpro/MainActivity.kt app/src/main/java/com/example/gpxnavpro/RouteAppearance.kt app/build.gradle.kts

git commit -m "Opzione colore pendenze e apertura GPX esterni" || true
git push origin main

echo
echo "Aggiornamento caricato. GitHub Actions può ora compilare l'APK."
