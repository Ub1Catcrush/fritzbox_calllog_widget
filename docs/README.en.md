# FritzBox CallLog Widget for Android

[🇩🇪 Deutsch](../README.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md)

A customisable Android home screen widget that fetches the call log from your AVM FRITZ!Box via TR-064 or the MyFRITZ session API and displays it as a scrollable table. Three independent connection profiles (LAN, Internet TR-064, Internet MyFRITZ) can be prioritised and individually enabled or disabled — the widget stays functional when switching between Wi-Fi and mobile data.

<img width="681" height="1308" alt="FritzBox CallLog Widget Screenshot" src="https://github.com/user-attachments/assets/4a0bb655-7b3f-432c-8865-93635f1e531d" />

---

## Features

### Widget
- 📋 **Scrollable call list** with four columns: date, time, call-type icon, name/number
- 📞 **Tap any row** to open the system phone app with the number pre-filled
- 💾 **Cached data always visible** — last loaded list shown immediately, even during a background refresh
- ⚠️ **Errors as subtle overlay** — connection errors appear as a narrow strip at the bottom, without replacing the list
- 📐 **Freely resizable** — from a small tile to full screen; redraws automatically on resize
- 🔄 **Manual refresh** via the refresh button in the widget header
- ⚙️ **Direct settings access** via the gear button in the header
- 🌙 **Light and dark mode** — follows system setting or can be forced manually

### Call types with individual icons
| Icon colour | Type | FritzBox code |
|---|---|---|
| 🔵 Blue arrow ↙ | Incoming (answered) | 1, 4 |
| 🟢 Green arrow ↗ | Outgoing | 3 |
| 🔴 Red square | Missed | 2 |
| 🔴 Circle with slash | Blocked / call block | 10 |
| 🟠 Microphone | Voicemail — message left on AB | 1/4 on AB port |
| 🩵 Document ↓ | Fax received | 1/4, numbertype fax |
| 🩵 Document ↑ | Fax sent | 3, numbertype fax |
| 🔵 Arrow + dot | Active incoming call | 9 |
| 🟢 Arrow + dot | Active outgoing call | 11 |

### Connection
- 🔌 **Three independent connection profiles**:
  - **LAN TR-064** — direct access on the home network (default: `fritz.box:49000`)
  - **Internet TR-064** — TR-064 SOAP over public IP or hostname
  - **Internet MyFRITZ** — MyFRITZ session API (port 80/443), protocol v2 (PBKDF2-SHA256) and v1 (MD5)
- 🔀 **Sortable priority list** — drag and drop to reorder; app tries profiles top to bottom
- ✅ **Individually enable/disable** — at least one profile must remain active
- 🔁 **Automatic fallback** with exponential backoff retry (2 s / 4 s / 8 s)
- 📡 **Network state detection** — no network or battery/data saver mode detected and reported
- 🔍 **Built-in connectivity checker** — step-by-step live diagnosis per profile (DNS → TCP → HTTP → service → auth → call list)

### Settings
- 🔒 **Credentials**: username and password (shared across all profiles)
- ⏱️ **Configurable refresh interval** in seconds (AlarmManager; fallback without `SCHEDULE_EXACT_ALARM`)
- 🔢 **Maximum entry count** configurable
- 📱 **Phone number prefix**: replaces leading `0` with a country code (e.g. `0621…` → `+49621…`)
- 🎨 **Fully customisable colours** — separate sets for light and dark mode, 11 colours each, ARGB picker with sliders, hex input, and live swatch preview
- 🔤 **Font size** adjustable (8–16 sp)
- 🌍 **Multilingual**: German, English, French, Spanish

---

## Setup

### 1. Import project
1. Open Android Studio → "Open an Existing Project"
2. Select the cloned repository
3. Wait for Gradle sync

### 2. Build & Install
```bash
./gradlew installDebug
```

---

## FritzBox Configuration

### Enable TR-064
1. Open the FritzBox interface (`fritz.box`)
2. **Home Network → Network → Network Settings**
3. Enable "Allow access for applications"
4. If using an external hostname, optionally disable **DNS rebind protection**

### Create a user
1. **System → FRITZ!Box Users → Add User**
2. Minimum permission: **"Voice messages, fax messages, FRITZ!App Fon and call list"**

### MyFRITZ access (for remote access)
1. Enable **Internet → MyFRITZ! Account**
2. Configure the **Internet MyFRITZ** profile:
   - Host: `<your-id>.myfritz.net`
   - Port: `80` (HTTP) or `443` (HTTPS)
3. Protocol version is detected automatically (v2/PBKDF2 from FritzOS 7.24)

---

## Settings Reference

### Connection defaults

| Profile | Default host | Default port |
|---|---|---|
| LAN TR-064 | `fritz.box` | `49000` |
| Internet TR-064 | — (empty) | `49000` |
| Internet MyFRITZ | — (empty) | `80` |

### Refresh & Data

| Setting | Description | Default |
|---|---|---|
| Refresh interval | Seconds between fetches | `300` |
| Maximum entries | Number of calls shown in the widget | `20` |
| Phone number prefix | Replaces leading `0` with country code | — |

**Examples:**
- Prefix `+49` → `06211234567` becomes `+496211234567`
- Numbers starting with `+` or `00` are left unchanged

### Appearance

| Setting | Options |
|---|---|
| Language | System · Deutsch · English · Français · Español |
| Theme | System · Light · Dark |
| Font size | 8 · 9 · 10 · **11** · 12 · 13 · 14 · 16 sp |

---

## Technical Details

### Call type codes

| Code | Type | Detection |
|---|---|---|
| 1, 4 | Incoming | — |
| 1, 4 | Voicemail | Port ≥ 40 |
| 1, 4 | Fax received | Numbertype = "fax" |
| 2 | Missed | — |
| 3 | Outgoing | — |
| 3 | Fax sent | Numbertype = "fax" |
| 9 | Active incoming | — |
| 10 | Blocked | — |
| 11 | Active outgoing | — |

### Android version compatibility

| API | Behaviour |
|---|---|
| 31+ (Android 12+) | RemoteCollectionItems, no background service needed |
| 26–30 (Android 8–11) | RemoteViewsService fallback |
| 12+ | FLAG_MUTABLE / FLAG_IMMUTABLE correctly set |
| 14+ | DialActivity trampoline for explicit PendingIntents |

### Known limitations

- **Exact Alarms**: `SCHEDULE_EXACT_ALARM` required from Android 12; falls back to `setInexactRepeating`
- **Cleartext HTTP**: Permitted for local FritzBox connections; HTTPS available in settings
- **Font family**: Stored but has no effect in the widget (RemoteViews limitation)
- **CSV mode (MyFRITZ)**: Port and numbertype fields unavailable → voicemail/fax detection only via TR-064

---

## Licence

GNU General Public License Version 3 — see [LICENCE](../LICENCE)
