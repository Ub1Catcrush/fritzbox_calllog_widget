# FritzBox CallLog Widget für Android

Ein anpassbares Android-Homescreen-Widget, das das Anrufprotokoll deiner FritzBox über die TR-064 API abruft und als scrollbare Tabelle anzeigt. Vollständig konfigurierbar – von Farben über Schriftgröße bis zur Sprache.

<img width="681" height="1308" alt="image" src="https://github.com/user-attachments/assets/4a0bb655-7b3f-432c-8865-93635f1e531d" />

---

## Features

### Widget
- 📋 **Scrollbare Anrufliste** mit vier Spalten: Datum, Uhrzeit, Anruf-Typ-Icon und Name/Nummer
- 📞 **Tap auf einen Eintrag** öffnet direkt die Telefon-App mit der vorgewählten Nummer
- 🟢🔵🔴 **Anruf-Typ-Icons**: grüner Pfeil (ausgehend), blauer Pfeil (eingehend), rotes Quadrat (verpasst)
- 📐 **Frei skalierbar** – von einer kleinen Kachel bis zum Vollbild; reagiert dynamisch auf Größenänderungen
- 🔄 **Manuelle Aktualisierung** per Refresh-Button in der Widget-Kopfzeile
- ⚙️ **Direkter Zugriff zu den Einstellungen** über den Zahnrad-Button in der Kopfzeile
- 🌙 **Hell- und Dunkel-Modus** – automatisch nach System oder manuell erzwingbar

### Einstellungen
- 🔒 **Verbindungsparameter**: Adresse, Port, Benutzername, Passwort, HTTP/HTTPS-Auswahl
- ⏱️ **Konfigurierbarer Refresh-Zyklus** in Sekunden (AlarmManager, fällt bei fehlender Berechtigung auf ungenauere Variante zurück)
- 🔢 **Maximale Anzahl** der angezeigten Einträge einstellbar
- 📱 **Telefonnummern-Präfix**: führende `0` einer nationalen Nummer wird automatisch durch den konfigurierten Präfix ersetzt (z.B. `0621…` → `+49621…`)
- 🎨 **Vollständig anpassbare Farben** – separate Farbsätze für Hell- und Dunkel-Modus, jeweils 11 Farben (Header, Spaltenheader, Hintergrund, gerade/ungerade Zeilen, Primär-/Sekundärtext, Trennlinie, Fehlertext) – mit visuellem ARGB-Farbwähler (Schieberegler + Hex-Eingabe + Echtzeit-Vorschau)
- 🔤 **Schriftgröße** einstellbar (8–16 sp)
- 🌍 **Mehrsprachig**: Deutsch, Englisch, Französisch, Spanisch – sowohl Einstellungen als auch Widget-Beschriftungen wechseln die Sprache
- 🔗 **Verbindungstest** direkt in den Einstellungen (zeigt Anzahl gefundener Anrufe)

---

## Einrichtung in Android Studio

### 1. Projekt importieren
1. Android Studio öffnen → „Open an Existing Project"
2. Das geklonte Repository auswählen
3. Gradle-Sync abwarten

### 2. Build & Install
```bash
./gradlew installDebug
```
oder über den grünen „Run"-Button in Android Studio.

---

## FritzBox Konfiguration

### TR-064 aktivieren
1. FritzBox-Oberfläche öffnen (`fritz.box`)
2. **Heimnetz → Netzwerk → Heimnetzfreigaben**
3. „Zugriff für Anwendungen zulassen" aktivieren
4. Bei Problemen: **Heimnetz → Netzwerk → DNS-Rebind-Schutz** deaktivieren (bei externem Hostnamen)

### Benutzer mit Berechtigungen anlegen
1. **System → FRITZ!Box-Benutzer → Benutzer hinzufügen**
2. Mindestberechtigung: **„Sprachnachrichten, Faxnachrichten, FRITZ!App Fon und Anrufliste"**

---

## Einstellungen im Überblick

### Verbindung

| Einstellung | Beschreibung | Standard |
|---|---|---|
| FritzBox Adresse | Hostname oder IP-Adresse | `fritz.box` |
| TR-064 Port | HTTP: 49000 · HTTPS: 49443 | `49000` |
| Benutzername | FritzBox-Benutzer (leer = kein Auth) | – |
| Passwort | FritzBox-Passwort | – |
| HTTPS | Verschlüsselte Verbindung (selbstsigniertes Zertifikat wird akzeptiert) | aus |

### Aktualisierung & Daten

| Einstellung | Beschreibung | Standard |
|---|---|---|
| Refresh-Intervall | Sekunden zwischen automatischen Abrufen (Minimum empfohlen: 60) | `300` |
| Maximale Einträge | Anzahl der im Widget angezeigten Anrufe | `20` |
| Telefonnummern-Präfix | Ersetzt führende `0` nationaler Nummern durch internationales Präfix | – |

**Präfix-Beispiele:**
- Präfix `+49` → `06211234567` wird zu `+496211234567`
- Präfix `+49621` → `1234567` wird zu `+496211234567`
- Nummern mit `+` oder `00` werden unverändert übernommen

### Darstellung

| Einstellung | Optionen |
|---|---|
| Sprache | System-Standard · Deutsch · English · Français · Español |
| Erscheinungsbild | System-Standard · Hell · Dunkel |
| Schriftgröße | 8 · 9 · 10 · **11** · 12 · 13 · 14 · 16 sp |

### Widget-Farben (Hell- und Dunkel-Modus separat einstellbar)

Jede der folgenden Farben ist für Hell- und Dunkel-Modus unabhängig konfigurierbar. Ein visueller Farbwähler (ARGB-Schieberegler + Hex-Eingabe) zeigt die aktuelle Farbe als Swatch direkt in der Einstellungsliste.

| Farbe | Beschreibung |
|---|---|
| Header-Hintergrund | Hintergrund der Titelzeile |
| Header-Text | Schriftfarbe in der Titelzeile |
| Spaltenheader-Hintergrund | Hintergrund der Spaltenbeschriftungen |
| Spaltenheader-Text | Schriftfarbe der Spaltenbeschriftungen |
| Widget-Hintergrund | Gesamthintergrund des Widgets |
| Gerade Zeilen | Hintergrundfarbe für Zeilen 1, 3, 5, … |
| Ungerade Zeilen | Hintergrundfarbe für Zeilen 2, 4, 6, … |
| Primärtext | Name / Nummer und Datum |
| Sekundärtext | Uhrzeit und Statusmeldungen |
| Trennlinie | Linie zwischen den Zeilen |
| Fehlertext | Fehlermeldungen im Widget |

„Farben zurücksetzen" setzt alle Werte auf die Theme-Defaults zurück.

---

## Technische Details

### TR-064 API
Das Widget nutzt den SOAP-Dienst `X_AVM-DE_OnTel:1`, Aktion `GetCallList`.  
Endpunkt: `http://fritz.box:49000/upnp/control/x_contact`

Anruf-Typ-Codes der FritzBox:

| Code | Typ |
|---|---|
| 1, 4 | Eingehend (angenommen) |
| 2, 10 | Verpasst / Abgewiesen |
| 3 | Ausgehend |

### Authentifizierung
HTTP Digest Auth gemäß RFC 2617. Das Widget versucht zunächst ohne Auth; bei HTTP 401 wird der `WWW-Authenticate`-Header geparst und die Anfrage mit korrektem Digest-Credential wiederholt.

### Architektur

```
CallLogWidget (AppWidgetProvider)
    ├── CallRepository
    │       └── FritzBoxClient (TR-064 SOAP + Digest Auth)
    ├── buildCollectionItems()          ← API 31+: RemoteCollectionItems (kein Service)
    ├── CallLogRemoteViewsService       ← API 26–30 Fallback
    ├── DialActivity                    ← Trampolin für explizite PendingIntents (Android 14+)
    └── WidgetScheduler (AlarmManager, konfigurierbares Intervall)

AppPreferences (SharedPreferences)
    ├── Verbindungsparameter
    ├── Darstellungsoptionen (Theme, Sprache, Schriftgröße)
    └── 22 Farbwerte (je 11 für Hell- und Dunkel-Modus)

SettingsActivity
    └── ColorPickerPreference           ← Eigene Preference mit ARGB-Schiebereglern
```

### Android-Version-Kompatibilität

| API | Verhalten |
|---|---|
| 31+ (Android 12+) | `RemoteCollectionItems` – Zeilen direkt in `RemoteViews` eingebettet, kein separater Service nötig |
| 26–30 (Android 8–11) | `RemoteViewsService`-Fallback |
| 12+ | Launcher-seitige Abrundung der Widget-Ecken; das Widget selbst hat keinen eigenen abgerundeten Hintergrund |
| 12+ | `FLAG_MUTABLE` / `FLAG_IMMUTABLE` auf `PendingIntent` korrekt gesetzt |
| 14+ | Explizite `PendingIntent` über `DialActivity`-Trampolin (implizite Intents mit `FLAG_MUTABLE` verboten) |

---

## Bekannte Einschränkungen

- Exact Alarms benötigen ab Android 12 ggf. die Berechtigung `SCHEDULE_EXACT_ALARM`. Das Widget fällt bei fehlender Berechtigung automatisch auf `setInexactRepeating` zurück (Intervall kann leicht abweichen).
- Cleartext HTTP ist über `android:usesCleartextTraffic="true"` erlaubt, da FritzBox-Router im Heimnetz in der Regel kein HTTPS erzwingen. Für erhöhte Sicherheit kann HTTPS in den Einstellungen aktiviert werden (selbstsigniertes Zertifikat der FritzBox wird akzeptiert).
- Die Font-Family-Einstellung ist gespeichert, hat aber im Widget keine Wirkung: `RemoteViews` unterstützt keinen Typeface-Wechsel zur Laufzeit ohne separate Layout-Variante pro Schriftart.

---

## Lizenz

GNU General Public License Version 3 – siehe [LICENSE](LICENSE)
