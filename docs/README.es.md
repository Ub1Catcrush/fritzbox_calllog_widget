# Widget Registro de llamadas FritzBox para Android

[🇩🇪 Deutsch](../README.md) · [🇬🇧 English](README.en.md) · [🇫🇷 Français](README.fr.md)

Un widget de pantalla de inicio de Android personalizable que obtiene el registro de llamadas de tu AVM FRITZ!Box a través de TR-064 o la API de sesión MyFRITZ, y lo muestra como una tabla desplazable. Se pueden configurar y priorizar tres perfiles de conexión independientes (LAN, Internet TR-064, Internet MyFRITZ).

<img width="681" height="1308" alt="Captura de pantalla FritzBox CallLog Widget" src="https://github.com/user-attachments/assets/4a0bb655-7b3f-432c-8865-93635f1e531d" />

---

## Características

### Widget
- 📋 **Registro de llamadas desplazable** con cuatro columnas: fecha, hora, icono de tipo, nombre/número
- 📞 **Toca una fila** para abrir el marcador del sistema con el número prellenado
- 💾 **Datos en caché siempre visibles** — última lista cargada mostrada inmediatamente, incluso durante una actualización en segundo plano
- ⚠️ **Errores como superposición discreta** — los errores de conexión aparecen en la parte inferior sin reemplazar la lista
- 📐 **Libremente redimensionable** — se redibuja automáticamente al cambiar el tamaño
- 🔄 **Actualización manual** mediante el botón de actualización en el encabezado
- ⚙️ **Acceso directo a ajustes** mediante el botón de engranaje en el encabezado
- 🌙 **Modos claro y oscuro** — sigue la configuración del sistema o se puede forzar manualmente

### Tipos de llamada con iconos individuales
| Color del icono | Tipo | Código FritzBox |
|---|---|---|
| 🔵 Flecha azul ↙ | Entrante (contestada) | 1, 4 |
| 🟢 Flecha verde ↗ | Saliente | 3 |
| 🔴 Cuadrado rojo | Perdida | 2 |
| 🔴 Círculo tachado | Bloqueada / regla de bloqueo | 10 |
| 🟠 Micrófono | Buzón de voz — mensaje dejado | 1/4 en puerto AB |
| 🩵 Documento ↓ | Fax recibido | 1/4, numbertype fax |
| 🩵 Documento ↑ | Fax enviado | 3, numbertype fax |
| 🔵 Flecha + punto | Llamada entrante activa | 9 |
| 🟢 Flecha + punto | Llamada saliente activa | 11 |

### Conexión
- 🔌 **Tres perfiles de conexión independientes**:
  - **LAN TR-064** — acceso directo en la red local (por defecto: `fritz.box:49000`)
  - **Internet TR-064** — TR-064 SOAP a través de IP pública o nombre de host
  - **Internet MyFRITZ** — API de sesión MyFRITZ (puerto 80/443), protocolo v2 (PBKDF2-SHA256) y v1 (MD5)
- 🔀 **Lista de prioridad ordenable** — arrastrar y soltar para reordenar
- ✅ **Activación/desactivación individual** — al menos un perfil debe permanecer activo
- 🔁 **Conmutación automática** con reintento de retroceso exponencial (2 s / 4 s / 8 s)
- 📡 **Detección del estado de red** — sin red o modos ahorro de batería/datos detectados
- 🔍 **Comprobador de conexión integrado** — diagnóstico paso a paso en vivo por perfil

### Ajustes
- 🔒 **Credenciales**: nombre de usuario y contraseña (compartidos entre todos los perfiles)
- ⏱️ **Intervalo de actualización** configurable en segundos
- 🎨 **Colores totalmente personalizables** — conjuntos separados para modos claro y oscuro, 11 colores cada uno
- 🔤 **Tamaño de fuente** ajustable (8–16 sp)
- 🌍 **Multilingüe**: alemán, inglés, francés, español

---

## Configuración

### 1. Importar el proyecto
1. Abrir Android Studio → "Open an Existing Project"
2. Seleccionar el repositorio clonado
3. Esperar la sincronización de Gradle

### 2. Build & Install
```bash
./gradlew installDebug
```

---

## Configuración de la FRITZ!Box

### Activar TR-064
1. Abrir la interfaz de la FRITZ!Box (`fritz.box`)
2. **Red doméstica → Red → Configuración de red**
3. Activar "Permitir acceso para aplicaciones"

### Crear un usuario
1. **Sistema → Usuarios FRITZ!Box → Añadir usuario**
2. Permiso mínimo: **"Mensajes de voz, fax, FRITZ!App Fon y lista de llamadas"**

### Acceso MyFRITZ (acceso remoto)
1. Activar **Internet → Cuenta MyFRITZ!**
2. Configurar el perfil **Internet MyFRITZ**:
   - Host: `<tu-id>.myfritz.net`
   - Puerto: `80` (HTTP) o `443` (HTTPS)

---

## Limitaciones conocidas

- **Exact Alarms**: `SCHEDULE_EXACT_ALARM` requerido desde Android 12; retroceso automático
- **HTTP sin cifrar**: Permitido para conexiones FRITZ!Box locales; HTTPS disponible en ajustes
- **Modo CSV (MyFRITZ)**: Campos puerto y numbertype no disponibles → detección de buzón/fax solo via TR-064

---

## Licencia

GNU General Public License Versión 3 — ver [LICENCE](../LICENCE)
