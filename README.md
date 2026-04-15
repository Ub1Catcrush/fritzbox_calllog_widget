# FritzBox Anruflisten-Widget für Android

[🇬🇧 English](docs/README.en.md) · [🇫🇷 Français](docs/README.fr.md) · [🇪🇸 Español](docs/README.es.md)

Ein anpassbares Android-Homescreen-Widget, das das Anrufprotokoll deiner FRITZ!Box über TR-064 oder die MyFRITZ-Session-API abruft und als scrollbare Tabelle anzeigt. Drei unabhängige Verbindungsprofile (LAN, Internet TR-064, Internet MyFRITZ) lassen sich priorisieren und einzeln ein- oder ausschalten – das Widget bleibt auch beim Wechsel zwischen WLAN und mobilem Netz erreichbar.

<img width="681" height="1308" alt="FritzBox CallLog Widget Screenshot" src="https://github.com/user-attachments/assets/4a0bb655-7b3f-432c-8865-93635f1e531d" />

---

## Features

### Widget
- 📋 **Scrollbare Anrufliste** mit vier Spalten: Datum, Uhrzeit, Anruf-Typ-Icon, Name/Nummer
- 📞 **Tap auf einen Eintrag** öffnet direkt die Telefon-App mit der vorgewählten Nummer
- 💾 **Gecachte Daten immer sichtbar** – zuletzt geladene Liste wird sofort angezeigt, auch während einer Aktualisierung im Hintergrund
- ⚠️ **Fehler als dezentes Overlay** – Verbindungsfehler erscheinen als schmaler Streifen am unteren Rand, ohne die Liste zu ersetzen
- 📐 **Frei skalierbar** – von kleiner Kachel bis Vollbild; reagiert dynamisch auf Größenänderungen
- 🔄 **Manuelle Aktualisierung** per Refresh-Button in der Widget-Kopfzeile
- ⚙️ **Direkter Zugriff zu den Einstellungen** über den Zahnrad-Button in der Kopfzeile
- 🌙 **Hell- und Dunkel-Modus** – automatisch nach System oder manuell erzwingbar

### Anruf-Typen mit eigenen Icons
| Icon-Farbe | Typ | FritzBox-Code |
|---|---|---|
| 🔵 Blauer Pfeil ↙ | Eingehend (angenommen) | 1, 4 |
| 🟢 Grüner Pfeil ↗ | Ausgehend | 3 |
| 🔴 Rotes Quadrat | Verpasst | 2 |
| 🔴 Kreis mit Strich | Blockiert / Rufsperre | 10 |
| 🟠 Mikrofon | AB – Nachricht hinterlassen | 1/4 auf AB-Port |
| 🩵 Dokument ↓ | Fax empfangen | 1/4, Numbertype fax |
| 🩵 Dokument ↑ | Fax gesendet | 3, Numbertype fax |
| 🔵 Pfeil + Punkt | Aktiver eingehender Anruf | 9 |
| 🟢 Pfeil + Punkt | Aktiver ausgehender Anruf | 11 |

### Verbindung
- 🔌 **Drei unabhängige Verbindungsprofile**:
  - **LAN TR-064** – direkter Zugriff im Heimnetz (Standard: `fritz.box:49000`)
  - **Internet TR-064** – TR-064 SOAP über öffentliche IP oder Hostnamen
  - **Internet MyFRITZ** – MyFRITZ Session-API (Port 80/443), Protokoll v2 (PBKDF2-SHA256) und v1 (MD5)
- 🔀 **Sortierbare Prioritätsliste** – Reihenfolge per Drag & Drop; App versucht Profile von oben nach unten
- ✅ **Einzeln aktivierbar/deaktivierbar** – mindestens ein Profil muss aktiv bleiben
- 🔁 **Automatischer Fallback** mit exponentiellem Backoff-Retry (2 s / 4 s / 8 s)
- 📡 **Netzwerkstatus-Erkennung** – kein Netz oder Energie-/Datensparmodus werden erkannt und gemeldet
- 🔍 **Eingebauter Verbindungstest** – schrittweise Live-Diagnose pro Profil (DNS → TCP → HTTP → Dienst → Auth → Anrufliste)

### Einstellungen
- 🔒 **Zugangsdaten**: Benutzername und Passwort (geteilt über alle Profile)
- ⏱️ **Konfigurierbarer Refresh-Zyklus** in Sekunden (AlarmManager; Fallback auf ungenaueres Intervall ohne `SCHEDULE_EXACT_ALARM`)
- 🔢 **Maximale Eintragsanzahl** einstellbar
- 📱 **Telefonnummern-Präfix**: führende `0` wird automatisch durch Ländervorwahl ersetzt (z.B. `0621…` → `+49621…`)
- 🎨 **Vollständig anpassbare Farben** – separate Farbsätze für Hell- und Dunkel-Modus, 11 Farben, ARGB-Farbwähler mit Schiebereglern + Hex-Eingabe + Echtzeit-Vorschau
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

---

## FritzBox-Konfiguration

### TR-064 aktivieren
1. FritzBox-Oberfläche öffnen (`fritz.box`)
2. **Heimnetz → Netzwerk → Heimnetzfreigaben**
3. „Zugriff für Anwendungen zulassen" aktivieren
4. Bei externem Hostnamen ggf. **DNS-Rebind-Schutz** deaktivieren

### Benutzer anlegen
1. **System → FRITZ!Box-Benutzer → Benutzer hinzufügen**
2. Mindestberechtigung: **„Sprachnachrichten, Faxnachrichten, FRITZ!App Fon und Anrufliste"**

### MyFRITZ-Zugang (für Internetzugriff)
1. **Internet → MyFRITZ!-Konto** aktivieren
2. Profil **Internet MyFRITZ** konfigurieren:
   - Host: `<deine-id>.myfritz.net`
   - Port: `80` (HTTP) oder `443` (HTTPS)
3. Protokollversion wird automatisch erkannt (v2/PBKDF2 ab FritzOS 7.24)

---

## Einstellungen im Überblick

### Verbindungs-Standardwerte

| Profil | Standard-Host | Standard-Port |
|---|---|---|
| LAN TR-064 | `fritz.box` | `49000` |
| Internet TR-064 | – (leer) | `49000` |
| Internet MyFRITZ | – (leer) | `80` |

### Aktualisierung & Daten

| Einstellung | Beschreibung | Standard |
|---|---|---|
| Refresh-Intervall | Sekunden zwischen Abrufen | `300` |
| Maximale Einträge | Anzahl der im Widget angezeigten Anrufe | `20` |
| Telefonnummern-Präfix | Ersetzt führende `0` durch Ländervorwahl | – |

**Beispiele:**
- Präfix `+49` → `06211234567` wird zu `+496211234567`
- Nummern mit `+` oder `00` bleiben unverändert

### Darstellung

| Einstellung | Optionen |
|---|---|
| Sprache | System · Deutsch · English · Français · Español |
| Erscheinungsbild | System · Hell · Dunkel |
| Schriftgröße | 8 · 9 · 10 · **11** · 12 · 13 · 14 · 16 sp |

### Widget-Farben (je 11 für Hell- und Dunkel-Modus)

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

---

## Technische Details

### Verbindungsmodi

**LAN / Internet TR-064**
- SOAP-Dienst `X_AVM-DE_OnTel:1`, Aktion `GetCallList`
- HTTP Digest Auth (RFC 2617)
- Endpunkt: `http[s]://<host>:<port>/upnp/control/x_contact`

**Internet MyFRITZ**
- `GET /login_sid.lua?version=2` → Challenge → SID
- v2 (FritzOS 7.24+): PBKDF2-HMAC-SHA256, Challenge-Format `2$iter1$salt1$iter2$salt2`
- v1 (Fallback): MD5 UTF-16LE

### Anruf-Typ-Codes

| Code | Typ | Erkennung |
|---|---|---|
| 1, 4 | Eingehend | — |
| 1, 4 | Anrufbeantworter | Port ≥ 40 |
| 1, 4 | Fax empfangen | Numbertype = "fax" |
| 2 | Verpasst | — |
| 3 | Ausgehend | — |
| 3 | Fax gesendet | Numbertype = "fax" |
| 9 | Aktiv eingehend | — |
| 10 | Blockiert | — |
| 11 | Aktiv ausgehend | — |

### Architektur

```
CallLogWidget (AppWidgetProvider)
    ├── Singleton CoroutineScope (companion object)
    ├── State: Loading | Error | Success | SuccessWithError
    ├── CallRepository
    │       ├── NetworkChecker (kein Netz / Energiesparmodus / Datensparmodus)
    │       ├── getOrderedProfiles() → enabled profiles in priority order
    │       ├── fetchWithRetry() — exponentieller Backoff (2s/4s/8s)
    │       ├── AtomicReference<List<CallEntry>?> — thread-sicherer Cache
    │       └── FritzBoxClient (TR-064 SOAP + MyFRITZ Session API)
    ├── buildCollectionItems()       ← API 31+: RemoteCollectionItems
    ├── CallLogRemoteViewsService    ← API 26–30 Fallback
    ├── DialActivity                 ← Trampolin für PendingIntents (Android 14+)
    └── WidgetScheduler (AlarmManager)

AppPreferences (SharedPreferences)
    ├── Verbindungsprofile als JSON-Array (pref_connection_profiles)
    ├── Zugangsdaten (username · password)
    ├── Darstellungsoptionen
    └── 22 Farbwerte (je 11 für Hell- und Dunkel-Modus)

ConnectionProfilesActivity
    ├── RecyclerView mit ItemTouchHelper (Drag & Drop)
    ├── Edit-Dialog pro Profil
    └── ConnectivityChecker — 7 Schritte (Netzwerkstatus + 6 Protokollschritte)
```

### Android-Version-Kompatibilität

| API | Verhalten |
|---|---|
| 31+ (Android 12+) | RemoteCollectionItems, kein separater Service |
| 26–30 (Android 8–11) | RemoteViewsService-Fallback |
| 12+ | FLAG_MUTABLE / FLAG_IMMUTABLE korrekt gesetzt |
| 14+ | DialActivity-Trampolin für explizite PendingIntents |

### Sicherheit
- XML-Parser mit XXE-Schutz
- `SSLContext.getInstance("TLS")` (nicht deprecated "SSL")
- Selbstsignierte Zertifikate werden bei HTTPS akzeptiert

---

## Bekannte Einschränkungen

- **Exact Alarms**: Ab Android 12 wird `SCHEDULE_EXACT_ALARM` benötigt; Fallback auf `setInexactRepeating`
- **Cleartext HTTP**: Erlaubt für lokale FritzBox-Verbindungen; HTTPS in den Einstellungen aktivierbar
- **Font-Family**: Gespeichert, hat im Widget aber keine Wirkung (RemoteViews-Einschränkung)
- **CSV-Modus (MyFRITZ)**: Port- und Numbertype-Felder nicht verfügbar → Voicemail/Fax-Erkennung nur über TR-064

---

## Lizenz

GNU General Public License Version 3 – siehe [LICENCE](LICENCE)
