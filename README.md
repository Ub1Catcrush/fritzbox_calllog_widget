# FritzBox CallLog Widget für Android

Ein anpassbares Android-Homescreen-Widget, das das Anrufprotokoll deiner FRITZ!Box über TR-064 oder die MyFRITZ-Session-API abruft und als scrollbare Tabelle anzeigt. Drei unabhängige Verbindungsprofile (LAN, Internet TR-064, Internet MyFRITZ) lassen sich priorisieren und einzeln ein- oder ausschalten – das Widget bleibt auch beim Wechsel zwischen WLAN und mobilem Netz erreichbar.

<img width="681" height="1308" alt="FritzBox CallLog Widget Screenshot" src="https://github.com/user-attachments/assets/4a0bb655-7b3f-432c-8865-93635f1e531d" />

---

## Features

### Widget
- 📋 **Scrollbare Anrufliste** mit vier Spalten: Datum, Uhrzeit, Anruf-Typ-Icon, Name/Nummer
- 📞 **Tap auf einen Eintrag** öffnet direkt die Telefon-App mit der vorgewählten Nummer
- 🟢🔵🔴 **Anruf-Typ-Icons**: grüner Pfeil (ausgehend), blauer Pfeil (eingehend), rotes Quadrat (verpasst)
- 📐 **Frei skalierbar** – von kleiner Kachel bis Vollbild; reagiert dynamisch auf Größenänderungen
- 🔄 **Manuelle Aktualisierung** per Refresh-Button in der Widget-Kopfzeile
- ⚙️ **Direkter Zugriff zu den Einstellungen** über den Zahnrad-Button in der Kopfzeile
- 💾 **Gecachte Daten immer sichtbar** – zuletzt geladene Liste wird sofort angezeigt, auch während einer Aktualisierung im Hintergrund
- ⚠️ **Fehler als dezentes Overlay** – Verbindungsfehler ersetzen die Anrufliste nicht, sondern erscheinen als schmaler roter Streifen am unteren Rand
- 🌙 **Hell- und Dunkel-Modus** – automatisch nach System oder manuell erzwingbar

### Verbindung
- 🔌 **Drei unabhängige Verbindungsprofile**:
  - **LAN TR-064** – direkter Zugriff im Heimnetz (Standard: `fritz.box:49000`)
  - **Internet TR-064** – TR-064 SOAP über öffentliche IP oder Hostnamen
  - **Internet MyFRITZ** – MyFRITZ Session-API (Port 80/443), unterstützt Protokoll v2 (PBKDF2-SHA256) und v1 (MD5) automatisch
- 🔀 **Sortierbare Prioritätsliste** – Reihenfolge per Drag & Drop festlegen; App versucht Profile von oben nach unten
- ✅ **Einzeln aktivierbar/deaktivierbar** – mindestens ein Profil muss aktiv bleiben
- 🔁 **Automatischer Fallback** mit exponentiellem Backoff-Retry (2 s / 4 s / 8 s) bei Verbindungsfehlern
- 🔍 **Eingebauter Verbindungstest** – schrittweise Live-Diagnose pro Profil (DNS → TCP → HTTP → Dienst → Auth → Anrufliste)

### Einstellungen
- 🔒 **Zugangsdaten**: Benutzername und Passwort (geteilt über alle Profile)
- ⏱️ **Konfigurierbarer Refresh-Zyklus** in Sekunden (AlarmManager; fällt bei fehlender Berechtigung auf ungenauere Variante zurück)
- 🔢 **Maximale Eintragsanzahl** einstellbar
- 📱 **Telefonnummern-Präfix**: führende `0` wird automatisch durch den konfigurierten Präfix ersetzt (z.B. `0621…` → `+49621…`)
- 🎨 **Vollständig anpassbare Farben** – separate Farbsätze für Hell- und Dunkel-Modus, jeweils 11 Farben, mit ARGB-Farbwähler (Schieberegler + Hex-Eingabe + Echtzeit-Vorschau)
- 🔤 **Schriftgröße** einstellbar (8–16 sp)
- 🌍 **Mehrsprachig**: Deutsch, Englisch, Französisch, Spanisch

---

## Einrichtung

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

## FritzBox-Konfiguration

### TR-064 aktivieren
1. FritzBox-Oberfläche öffnen (`fritz.box`)
2. **Heimnetz → Netzwerk → Heimnetzfreigaben**
3. „Zugriff für Anwendungen zulassen" aktivieren
4. Bei Problemen mit externem Hostnamen: **Heimnetz → Netzwerk → DNS-Rebind-Schutz** deaktivieren

### Benutzer anlegen
1. **System → FRITZ!Box-Benutzer → Benutzer hinzufügen**
2. Mindestberechtigung: **„Sprachnachrichten, Faxnachrichten, FRITZ!App Fon und Anrufliste"**

### MyFRITZ-Zugang einrichten (für Internetzugriff)
1. **Internet → MyFRITZ!-Konto** aktivieren
2. In der App das Profil **Internet MyFRITZ** konfigurieren:
   - Host: `<deine-id>.myfritz.net`
   - Port: `80` (HTTP) oder `443` (HTTPS)
3. Die App erkennt Protokollversion automatisch (v2/PBKDF2 auf FritzOS 7.24+, v1/MD5 auf älteren Versionen)

---

## Einstellungen im Überblick

### Verbindungsprofile

Über **Verbindungs-Priorität** in den Einstellungen öffnet sich eine sortierbare Liste der drei Profile. Jedes Profil lässt sich einzeln bearbeiten (Host, Port, HTTPS) und per Checkbox aktivieren oder deaktivieren. Der **Test-Button** führt eine 6-stufige Live-Diagnose durch:

| Schritt | Beschreibung |
|---|---|
| 1 | DNS-Auflösung / IP-Erreichbarkeit |
| 2 | TCP-Verbindung auf dem konfigurierten Port |
| 3 | HTTP(S)-Erreichbarkeit |
| 4 | TR-064: `/tr64desc.xml` vorhanden · MyFRITZ: Login-Seite + Protokollversion |
| 5 | Authentifizierung (Digest Auth / SID-Login) |
| 6 | Download der Anrufliste |

### Verbindungs-Standardwerte

| Profil | Standard-Host | Standard-Port |
|---|---|---|
| LAN TR-064 | `fritz.box` | `49000` |
| Internet TR-064 | – (leer) | `49000` |
| Internet MyFRITZ | – (leer) | `80` |

### Aktualisierung & Daten

| Einstellung | Beschreibung | Standard |
|---|---|---|
| Refresh-Intervall | Sekunden zwischen automatischen Abrufen | `300` |
| Maximale Einträge | Anzahl der im Widget angezeigten Anrufe | `20` |
| Telefonnummern-Präfix | Ersetzt führende `0` durch internationales Präfix | – |

**Präfix-Beispiele:**
- Präfix `+49` → `06211234567` wird zu `+496211234567`
- Präfix `+49621` → `1234567` wird zu `+496211234567`
- Nummern mit `+` oder `00` bleiben unverändert

### Darstellung

| Einstellung | Optionen |
|---|---|
| Sprache | System-Standard · Deutsch · English · Français · Español |
| Erscheinungsbild | System-Standard · Hell · Dunkel |
| Schriftgröße | 8 · 9 · 10 · **11** · 12 · 13 · 14 · 16 sp |

### Widget-Farben (Hell- und Dunkel-Modus separat)

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

### Verbindungsmodi

#### LAN TR-064 / Internet TR-064
- SOAP-Dienst `X_AVM-DE_OnTel:1`, Aktion `GetCallList`
- Endpunkt: `http[s]://<host>:<port>/upnp/control/x_contact`
- Authentifizierung: HTTP Digest Auth (RFC 2617) – erster Request ohne Auth, bei 401 wird `WWW-Authenticate`-Header geparst und die Anfrage mit Digest-Credentials wiederholt

#### Internet MyFRITZ
- Session-API: `GET /login_sid.lua?version=2` → Challenge → Antwort → SID
- **Protokoll v2** (FritzOS 7.24+): Challenge-Format `2$iter1$salt1$iter2$salt2` → PBKDF2-HMAC-SHA256
- **Protokoll v1** (Fallback): Challenge-MD5 UTF-16LE (legacy, alle FritzOS-Versionen)
- Version wird automatisch anhand des Challenge-Formats erkannt
- Anruflisten-Abruf: `GET /fon_num/foncalls_list.lua?sid=<sid>&csv=`

### Anruf-Typ-Codes der FritzBox

| Code | Typ |
|---|---|
| 1, 4 | Eingehend (angenommen) |
| 2, 10 | Verpasst / Abgewiesen |
| 3 | Ausgehend |
| 9 | Aktiver Anruf (wird ignoriert) |

### Architektur

```
CallLogWidget (AppWidgetProvider)
    ├── Singleton CoroutineScope (companion object, kein Scope-Leak bei Provider-Neuinstanziierung)
    ├── State: Loading | Error | Success | SuccessWithError
    │       └── SuccessWithError: zeigt gecachte Liste + rotes Fehler-Overlay
    ├── CallRepository
    │       ├── getOrderedProfiles() → enabled profiles in priority order
    │       ├── fetchWithRetry() mit exponentiellem Backoff (2s/4s/8s)
    │       ├── AtomicReference<List<CallEntry>?> als thread-sicherer Cache
    │       └── FritzBoxClient (TR-064 SOAP + MyFRITZ Session API)
    ├── buildCollectionItems()       ← API 31+: RemoteCollectionItems (kein Service)
    ├── CallLogRemoteViewsService    ← API 26–30 Fallback
    ├── DialActivity                 ← Trampolin für explizite PendingIntents (Android 14+)
    └── WidgetScheduler (AlarmManager)

AppPreferences (SharedPreferences)
    ├── Verbindungsprofile als JSON-Array (pref_connection_profiles)
    │       └── ConnectionProfile: type · host · port · useHttps · enabled
    ├── Zugangsdaten (username · password)
    ├── Darstellungsoptionen (theme · language · fontSizeSp)
    └── 22 Farbwerte (je 11 für Hell- und Dunkel-Modus)

ConnectionProfilesActivity
    ├── RecyclerView mit ItemTouchHelper (Drag & Drop)
    ├── Pro Profil: Checkbox · Edit-Dialog · Test-Button
    └── ConnectivityChecker (6 Schritte, Live-Status via Coroutine-Callback)

SettingsActivity
    └── ColorPickerPreference (ARGB-Schieberegler + Hex + Echtzeit-Swatch)
```

### Android-Version-Kompatibilität

| API | Verhalten |
|---|---|
| 31+ (Android 12+) | `RemoteCollectionItems` – Zeilen direkt in `RemoteViews`, kein separater Service |
| 26–30 (Android 8–11) | `RemoteViewsService`-Fallback |
| 12+ | Launcher-seitige Abrundung der Widget-Ecken |
| 12+ | `FLAG_MUTABLE` / `FLAG_IMMUTABLE` auf `PendingIntent` korrekt gesetzt |
| 14+ | Explizite `PendingIntent` über `DialActivity`-Trampolin |

### Sicherheit
- XML-Parser mit XXE-Schutz (`disallow-doctype-decl` + externe Entities deaktiviert)
- `SSLContext.getInstance("TLS")` (nicht das veraltete `"SSL"`)
- Selbstsignierte FritzBox-Zertifikate werden bei aktiviertem HTTPS akzeptiert

---

## Bekannte Einschränkungen

- **Exact Alarms**: Ab Android 12 wird `SCHEDULE_EXACT_ALARM` benötigt. Das Widget fällt bei fehlender Berechtigung automatisch auf `setInexactRepeating` zurück (Intervall kann leicht abweichen).
- **Cleartext HTTP**: `android:usesCleartextTraffic="true"` ist gesetzt, da FritzBox-Router im Heimnetz meist kein HTTPS erzwingen. Für erhöhte Sicherheit kann HTTPS in den Einstellungen aktiviert werden.
- **Font-Family**: Die Einstellung wird gespeichert, hat im Widget aber keine Wirkung – `RemoteViews` unterstützt keinen Typeface-Wechsel zur Laufzeit ohne separate Layout-Variante pro Schriftart.

---

## Lizenz

GNU General Public License Version 3 – siehe [LICENCE](LICENCE)
