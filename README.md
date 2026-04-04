# FritzBox CallLog Widget für Android

Ein anpassbares Android-Homescreen-Widget, das das Anrufprotokoll deiner FritzBox über die TR-064 API abruft und als scrollbare Tabelle anzeigt.

---

## Features

- 📋 **Scrollbare Anrufliste** mit Datum, Uhrzeit, Anruf-Typ-Icon und Name/Nummer
- 📞 **Tap auf Eintrag** öffnet direkt die Telefon-App mit der Nummer
- 🔄 **Konfigurierbarer Refresh-Zyklus** (in Sekunden)
- 🔒 **Digest-Authentifizierung** gegen die FritzBox TR-064 API
- 📐 **Frei skalierbar** in Größe und Breite
- ⚙️ **Einstellungsseite** mit Verbindungstest

---

## Einrichtung in Android Studio

### 1. Projekt importieren
1. Android Studio öffnen → „Open an Existing Project"
2. Den entpackten Ordner `FritzBoxCallWidget` auswählen
3. Warten bis Gradle sync abgeschlossen ist

### 2. Gradle Wrapper (falls nötig)
Falls die gradle-wrapper.jar fehlt, Android Studio lädt diese automatisch.
Alternativ: `gradle/wrapper/DOWNLOAD_WRAPPER.txt` lesen.

### 3. Build & Install
```
./gradlew installDebug
```
oder über den grünen „Run"-Button in Android Studio.

---

## FritzBox Konfiguration

### TR-064 aktivieren
1. FritzBox-Oberfläche öffnen (fritz.box)
2. **Heimnetz → Netzwerk → Heimnetzfreigaben**
3. „Zugriff für Anwendungen zulassen" aktivieren
4. **Heim­netz → Netzwerk → DNS-Rebind-Schutz** – ggf. deaktivieren für externe Hostnamen

### Benutzer mit Berechtigungen
1. **System → FRITZ!Box-Benutzer → Neuen Benutzer anlegen**
2. Berechtigung: **„FritzBox-Einstellungen"** oder mindestens **„Sprachnachrichten, Faxnachrichten, FRITZ!App Fon und Anrufliste"**

---

## Widget-Einstellungen

| Einstellung | Beschreibung | Standard |
|---|---|---|
| FritzBox Adresse | Hostname oder IP | fritz.box |
| TR-064 Port | HTTP: 49000, HTTPS: 49443 | 49000 |
| Benutzername | FritzBox-User (leer = kein Auth) | – |
| Passwort | FritzBox-Passwort | – |
| HTTPS | Verschlüsselte Verbindung (self-signed OK) | aus |
| Refresh-Intervall | Sekunden zwischen Abrufen (min. 60 empfohlen) | 300 |
| Maximale Einträge | Anzahl Zeilen im Widget | 20 |
| Telefon-Präfix | Automatisch vorangestellte Vorwahl | – |

### Telefon-Präfix Beispiel
Wenn die FritzBox interne Nummern ohne Vorwahl speichert:
- Präfix `+4962` → `1234567` wird zu `+49621234567`
- Präfix `0621` → `1234567` wird zu `06211234567`
Nummern die bereits mit `+`, `00` oder `0` beginnen werden nicht verändert.

---

## Technische Details

### TR-064 API
Das Widget nutzt den **SOAP-Dienst `X_AVM-DE_OnTel:1`**, Aktion `GetCallList`.
Endpunkt: `http://fritz.box:49000/upnp/control/x_contact`

Anruf-Typ-Codes der FritzBox:
| Code | Typ |
|---|---|
| 1, 4 | Eingehend (angenommen) |
| 2, 10 | Verpasst / Abgewiesen |
| 3 | Ausgehend |

### Authentifizierung
HTTP Digest Auth gemäß RFC 2617. Das Widget versucht zunächst ohne Auth,
parsed bei 401 den `WWW-Authenticate`-Header und wiederholt mit korrektem Digest.

### Architektur
```
CallLogWidget (AppWidgetProvider)
    └── CallRepository
            └── FritzBoxClient (TR-064 SOAP)
    └── CallLogRemoteViewsService (ListView in Widget)
    └── WidgetScheduler (AlarmManager, konfigurierbares Intervall)
```

---

## Bekannte Einschränkungen

- Android Widgets können keine normalen Views verwenden → ListView via `RemoteViewsService`
- Exact Alarms benötigen ab Android 12 ggf. die Berechtigung `SCHEDULE_EXACT_ALARM`
  (das Widget fällt automatisch auf `setInexactRepeating` zurück)
- `cleartext` Traffic (HTTP) ist via `android:usesCleartextTraffic="true"` erlaubt,
  da FritzBox im Heimnetz typisch kein HTTPS erzwingt

---

## Lizenz
MIT
