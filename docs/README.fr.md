# Widget Journal d'appels FritzBox pour Android

[🇩🇪 Deutsch](../README.md) · [🇬🇧 English](README.en.md) · [🇪🇸 Español](README.es.md)

Un widget d'écran d'accueil Android personnalisable qui récupère le journal d'appels de votre FRITZ!Box AVM via TR-064 ou l'API de session MyFRITZ, et l'affiche sous forme de tableau défilant. Trois profils de connexion indépendants (LAN, Internet TR-064, Internet MyFRITZ) peuvent être priorisés et activés ou désactivés individuellement.

<img width="681" height="1308" alt="Capture d'écran FritzBox CallLog Widget" src="https://github.com/user-attachments/assets/4a0bb655-7b3f-432c-8865-93635f1e531d" />

---

## Fonctionnalités

### Widget
- 📋 **Journal d'appels défilant** avec quatre colonnes : date, heure, icône de type d'appel, nom/numéro (cinquième colonne optionnelle pour la durée)
- 📞 **Appui sur une ligne** ouvre le composeur système avec le numéro pré-rempli
- 💾 **Données en cache toujours visibles** — dernière liste chargée affichée immédiatement, même pendant une actualisation en arrière-plan
- ⚠️ **Erreurs en superposition discrète** — les erreurs de connexion s'affichent en bas sans remplacer la liste
- 📐 **Librement redimensionnable** — redessiné automatiquement lors du redimensionnement
- 🔄 **Actualisation manuelle** via le bouton de rafraîchissement dans l'en-tête
- ⚙️ **Accès direct aux paramètres** via le bouton engrenage dans l'en-tête
- 🌙 **Modes clair et sombre** — suit le réglage système ou peut être forcé manuellement

### Types d'appels avec icônes individuelles
| Couleur de l'icône | Type | Code FritzBox |
|---|---|---|
| 🔵 Flèche bleue ↙ | Entrant (décroché) | 1, 4 |
| 🟢 Flèche verte ↗ | Sortant | 3 |
| 🔴 Carré rouge | Manqué | 2 |
| 🔴 Cercle barré | Bloqué / règle d'appel | 10 |
| 🟠 Microphone | Répondeur — message laissé | 1/4 sur port AB |
| 🩵 Document ↓ | Fax reçu | 1/4, numbertype fax |
| 🩵 Document ↑ | Fax envoyé | 3, numbertype fax |
| 🔵 Flèche + point | Appel entrant actif | 9 |
| 🟢 Flèche + point | Appel sortant actif | 11 |

### Connexion
- 🔌 **Trois profils de connexion indépendants** :
  - **LAN TR-064** — accès direct sur le réseau local (par défaut : `fritz.box:49000`)
  - **Internet TR-064** — TR-064 SOAP via IP publique ou nom d'hôte
  - **Internet MyFRITZ** — API de session MyFRITZ (port 80/443), protocole v2 (PBKDF2-SHA256) et v1 (MD5)
- 🔀 **Liste de priorité triable** — glisser-déposer pour réorganiser
- ✅ **Activation/désactivation individuelle** — au moins un profil doit rester actif
- 🔁 **Basculement automatique** avec réessai à rebond exponentiel (2 s / 4 s / 8 s)
- 📡 **Détection de l'état du réseau** — absence de réseau ou modes économie batterie/données détectés
- 🔍 **Vérificateur de connexion intégré** — diagnostic pas à pas en direct par profil

### Paramètres
- 🔒 **Identifiants** : nom d'utilisateur et mot de passe (partagés entre tous les profils)
- ⏱️ **Intervalle d'actualisation** configurable en secondes
- 🎨 **Couleurs entièrement personnalisables** — jeux séparés pour les modes clair et sombre, 11 couleurs chacun
- 🔤 **Taille de police** réglable (8–16 sp)
- ⏱️ **Durée des appels** affichable en option dans une colonne étroite à droite
- 🌍 **Multilingue** : allemand, anglais, français, espagnol

---

## Installation

### 1. Importer le projet
1. Ouvrir Android Studio → « Open an Existing Project »
2. Sélectionner le dépôt cloné
3. Attendre la synchronisation Gradle

### 2. Build & Install
```bash
./gradlew installDebug
```

---

## Configuration de la FRITZ!Box

### Activer TR-064
1. Ouvrir l'interface de la FRITZ!Box (`fritz.box`)
2. **Réseau local → Réseau → Partages réseau local**
3. Activer « Autoriser l'accès pour les applications »

### Créer un utilisateur
1. **Système → Utilisateurs FRITZ!Box → Ajouter un utilisateur**
2. Autorisation minimale : **« Messages vocaux, fax, FRITZ!App Fon et liste d'appels »**

### Accès MyFRITZ (accès à distance)
1. Activer **Internet → Compte MyFRITZ!**
2. Configurer le profil **Internet MyFRITZ** :
   - Hôte : `<votre-id>.myfritz.net`
   - Port : `80` (HTTP) ou `443` (HTTPS)

---

## Limitations connues

- **Exact Alarms** : `SCHEDULE_EXACT_ALARM` requis depuis Android 12 ; repli automatique
- **HTTP en clair** : Autorisé pour les connexions FRITZ!Box locales ; HTTPS disponible dans les paramètres
- **Mode CSV (MyFRITZ)** : Champs port et numbertype non disponibles → détection répondeur/fax uniquement via TR-064

---

## Licence

GNU General Public License Version 3 — voir [LICENCE](../LICENCE)
