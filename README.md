# URLVault
Store your Bookmarks in Bitwarden

## Overview

URLVault is a **Compose Multiplatform** bookmark manager that supports **Android**, **iOS**, and **Desktop** (JVM) clients.
Bookmarks are stored locally in an encrypted database and can be synced across devices via the Bitwarden vault API.

## Features

- 🔖 **URL Storage & Retrieval** — save URLs with titles, descriptions, and tags
- 🏷️ **Tag-Based Filtering** — filter bookmarks by any tag; multi-tag chip UI
- 🔍 **Full-Text Search** — search by URL, title, or description
- ⭐ **Favourites** — star bookmarks for quick access
- 🔐 **Encrypted Local Storage** — Android uses Room + SQLCipher (AES-256); Desktop uses SQLite
- ☁️ **Bitwarden Sync** — push/pull bookmarks to/from your Bitwarden vault as Secure Notes
- 📤 **Share Extension (Android)** — receive URLs shared from other apps

## Architecture

```
URLVault/
├── shared/               ← Kotlin Multiplatform library (commonMain, androidMain, desktopMain, iosMain)
│   └── src/commonMain/  ← Compose UI, ViewModels, Repository interface, Models, Bitwarden sync
├── androidApp/           ← Android application (Room + SQLCipher, Koin DI)
├── desktopApp/           ← Desktop application (SQLite via JDBC, Compose Desktop)
└── iosApp/               ← iOS application (Compose Multiplatform via UIViewController)
```

### Key Technologies

| Layer          | Technology                              |
|----------------|-----------------------------------------|
| UI             | Compose Multiplatform 1.7.3             |
| Language       | Kotlin 2.1.0                            |
| Android DB     | Room 2.6.1 + SQLCipher 4.5.4 (AES-256) |
| Desktop DB     | SQLite via xerial JDBC                  |
| HTTP / Sync    | Ktor Client 3.0.3                       |
| Serialization  | kotlinx.serialization 1.7.3            |
| DI             | Koin 4.1.1                              |
| Lifecycle      | JetBrains AndroidX Lifecycle 2.9.6     |

## SDK Configuration (Android)

| Property    | Value          |
|-------------|----------------|
| `minSdk`    | 29 (Android 10)|
| `targetSdk` | 36 (Android 16)|
| `compileSdk`| 36             |

## Supported Configurations

- **Device Types**: Phone and Tablet (adaptive layouts via Compose)
- **Orientations**: Portrait and Landscape (Activity handles `orientation|screenSize|screenLayout|keyboardHidden`)

## Getting Started

### Prerequisites

- Android Studio Ladybug (2024.2.x) or later
- JDK 17+
- Xcode 16+ (iOS only)

### Android / Desktop

```bash
# Build and run on Android
./gradlew :androidApp:installDebug

# Build and run on Desktop
./gradlew :desktopApp:run
```

### iOS

1. Open `iosApp/iosApp.xcodeproj` in Xcode
2. Select a simulator or device
3. Build and run

> **Note:** The first build will compile the shared Kotlin framework as a static library.

## Bitwarden Sync Setup

1. Log in to your Bitwarden vault at [bitwarden.com](https://bitwarden.com)
2. Navigate to **Settings → Security → API Key** to obtain your `clientId` and `clientSecret`
3. Open **URLVault → Settings** and enter your credentials
4. Tap the sync button (🔄) to push/pull bookmarks

Bookmarks are stored as **Secure Notes** in a dedicated `URLVault` folder in your vault.
The JSON structure of each bookmark is stored in the note body for easy round-tripping.

## Security

- Android database encrypted with **SQLCipher AES-256**
- Encryption key generated with `SecureRandom`, stored encrypted in **Android Keystore** (hardware-backed where available)
- Bitwarden credentials stored in `SharedPreferences` (private mode); production apps should use `EncryptedSharedPreferences`
- No cloud backup of database or credentials (`dataExtractionRules.xml` excludes them)
