# URLVault
Store your Bookmarks in Bitwarden.

## Overview

URLVault is a **Kotlin Multiplatform** bookmark manager built with **Compose Multiplatform** that targets **Android**, **Desktop (JVM)**, and **iOS**.
Bookmarks are stored locally and can be synchronised across devices via your Bitwarden vault.

## Features

- **URL storage & retrieval** — save URLs with title, description, and tags
- **Tag-based filtering** — filter bookmarks by any tag; multi-tag chip UI
- **Full-text search** — search by URL, title, or description
- **Favourites** — star bookmarks for quick access
- **Encrypted local storage on Android** — Room + SQLCipher (AES-256), key wrapped by the Android Keystore
- **Bitwarden sync** — push/pull bookmarks to/from your Bitwarden vault as Login items with clickable URIs (password grant: email + master password, with `/accounts/prelogin` KDF parameters)
- **Share extension (Android)** — receive URLs shared from other apps via `ACTION_SEND`
- **Auto-tag suggestions** — heuristic keyword extraction from page metadata (all platforms)
- **On-device AI assistance (Android)** — Gemini Nano via ML Kit GenAI Prompt API for tag, title, and description generation *(experimental — see note below)*

## Auto-Tagging — Status & Roadmap

> The two auto-tagging implementations currently shipped are **research code** while we evaluate
> the right approach to bookmark tagging. Both will be removed once we settle on the
> "best" approach, where *best* is measured along three axes: **space** (binary size,
> downloaded model size, RAM), **time** (latency from "save" to "tagged"), and
> **quality** (relevance and stability of the tags produced).

Current experiments:

| Implementation                 | Where                  | How it works                                                                                  |
|--------------------------------|------------------------|-----------------------------------------------------------------------------------------------|
| `AutoTagService`               | `shared/src/commonMain`    | Fetches the page, extracts `<title>`, `<meta description/keywords>`, OG tags and `<h1-h3>`, then ranks tokens by frequency with a stop-word filter. Pure regex, no parser dependency. |
| `AICoreService` (Android only) | `androidApp/src/main`      | Uses ML Kit GenAI Prompt API (Gemini Nano on-device) to generate tags / titles / descriptions from the URL plus an extracted page summary. Includes a debug benchmarking pass over `STABLE/PREVIEW × FAST/FULL` model variants. |

Neither is the final answer; they exist to gather data on how the trade-offs play out in practice.

## Architecture

```
URLVault/
├── shared/                 ← Kotlin Multiplatform library
│   └── src/
│       ├── commonMain/     ← Compose UI, ViewModels, models, repository interface, Bitwarden sync, AutoTagService
│       ├── androidMain/    ← Android-specific Ktor engine, crypto, Logger
│       ├── desktopMain/    ← Desktop-specific Ktor engine, crypto, Logger
│       └── iosMain/        ← iOS-specific Ktor engine, crypto, Logger, in-memory repository
├── androidApp/             ← Android shell: Room + SQLCipher, Koin DI, share intent, ML Kit GenAI integration
├── desktopApp/             ← Desktop shell: SQLite via JDBC, manual DI, Compose for Desktop
└── iosApp/                 ← iOS shell: hosts the shared Compose framework via UIViewControllerRepresentable
```

Per-platform repository implementations:

| Platform | Repository                  | Storage                                              |
|----------|-----------------------------|------------------------------------------------------|
| Android  | `RoomBookmarkRepository`    | Room + SQLCipher, key in Android Keystore            |
| Desktop  | `DesktopBookmarkRepository` | SQLite (xerial JDBC) at `~/.urlvault/bookmarks.db` (unencrypted) |
| iOS      | `IosBookmarkRepository`     | In-memory only — **no persistence yet**              |

### Key Technologies

| Layer          | Technology                                                |
|----------------|-----------------------------------------------------------|
| UI             | Compose Multiplatform 1.8.2                               |
| Language       | Kotlin 2.1.0                                              |
| Android DB     | Room 2.6.1 + SQLCipher 4.14.1 (AES-256)                   |
| Desktop DB     | SQLite via xerial JDBC 3.47.1.0                           |
| HTTP / Sync    | Ktor Client 3.0.3                                         |
| Serialization  | kotlinx.serialization 1.7.3                               |
| DI             | Koin 4.1.1 (Android only — Desktop/iOS wire manually)     |
| Lifecycle      | JetBrains AndroidX Lifecycle 2.9.6                        |
| On-device AI   | ML Kit GenAI Prompt API 1.0.0-beta2 (Gemini Nano, Android) |

### SDK Configuration (Android)

| Property      | `:androidApp`     | `:shared` (androidMain)  |
|---------------|-------------------|--------------------------|
| `minSdk`      | 31 (Android 12)   | 29 (Android 10)          |
| `targetSdk`   | 36                | —                        |
| `compileSdk`  | 36                | 36                       |
| Java target   | 11                | 11                       |

> The shared KMP module compiles down to `minSdk` 29 so the library could be reused by other apps, but the released app (`:androidApp`) requires Android 12+ — primarily because the ML Kit GenAI Prompt API targets recent devices.

### Supported Configurations

- **Device types**: Phone and tablet (adaptive Compose layouts)
- **Orientations**: Portrait and landscape (Activity handles `orientation|screenSize|screenLayout|keyboardHidden`)

## Getting Started

### Prerequisites

- Android Studio Ladybug (2024.2.x) or later
- JDK 17+
- Xcode 16+ (iOS only)

### Build & Run

```bash
# Android
./gradlew :androidApp:installDebug      # Build and install on connected device/emulator
./gradlew :androidApp:assembleDebug     # APK only
./gradlew :androidApp:assembleRelease   # Release APK (ProGuard enabled)

# Desktop
./gradlew :desktopApp:run               # Run desktop app

# Shared module
./gradlew :shared:build                 # Build the KMP library
```

### Hugging Face Token (gated models)

Most LiteRT-LM models in the catalog (Gemma 3, Gemma 4, FunctionGemma) are *gated* on Hugging Face — the API will reject downloads with HTTP 401 until two things are true:

1. You hold a Hugging Face access token with **read** scope. Create one at <https://huggingface.co/settings/tokens>.
2. You've accepted each model's licence on its HF page (e.g. <https://huggingface.co/google/gemma-3-1b-it>). Acceptance is per-repo and is a one-time click on the web UI.

URLVault accepts the token from three sources, in this order of precedence:

1. **User-entered** — Settings → Local AI Models → "Hugging Face token". Stored in `EncryptedSharedPreferences` on the device. Best for personal builds.
2. **Build-time `HF_TOKEN` env var** — read by `androidApp/build.gradle.kts` and exposed as `BuildConfig.HF_TOKEN_DEFAULT`. Used by CI.
3. **Build-time `hfToken` in `local.properties`** — same destination, fallback when the env var is absent. Used by local developer builds.

The Settings row reads "Using token bundled with this build" when sources 2 or 3 are present and the user hasn't entered one of their own.

#### Local developer builds

Add a single line to `local.properties` at the repo root (already in `.gitignore` — the token never leaves your machine):

```properties
hfToken=hf_xxxxxxxxxxxxxxxxxxxx
```

After that, `./gradlew :androidApp:assembleDebug` and `./gradlew :androidApp:installDebug` will pick the token up automatically. Or set the env var per-invocation:

```bash
HF_TOKEN=hf_xxxxxxxxxxxxxxxxxxxx ./gradlew :androidApp:assembleDebug
```

#### CI builds

Add `HF_TOKEN` as a repository secret on GitHub:

- *Settings → Secrets and variables → Actions → New repository secret*, name `HF_TOKEN`.
- The existing `build.yml` and `release.yml` workflows already read it. With the secret absent, builds still succeed and the APK ships with the field empty (the user is prompted in Settings).

> **Security note.** Anything baked into the APK can be recovered by reverse-engineering. Only ship a *read-only* token that is acceptable for the people who will install the build. The user-entered path stores the token in EncryptedSharedPreferences (Android Keystore-wrapped) and is the safer default for shared / public builds.

The downloader scrubs the `Authorization` header on cross-origin redirects (HF 302s gated downloads to a pre-signed CDN URL on `cas-bridge.xethub.hf.co`, which would otherwise reject the extra header with 401), so the token only travels to `huggingface.co` itself.

### iOS

1. Open `iosApp/iosApp.xcodeproj` in Xcode
2. Select a simulator or device
3. Build and run

> The first build compiles the shared Kotlin framework as a static library. iOS is **not** part of `settings.gradle.kts` — it is driven from Xcode.

There are no automated tests yet. The Android module is wired with `AndroidJUnitRunner` for future instrumentation tests.

## Bitwarden Sync Setup

1. Open **URLVault → Settings**
2. Enter your Bitwarden **email** and **master password** (optionally adjust the API/Identity URLs for self-hosted Vaultwarden / Bitwarden EU)
3. Tap **Validate** — this performs a `/accounts/prelogin` call, derives the master-password hash, and exchanges it for an access token
4. Tap the sync button to push/pull bookmarks

Bookmarks are stored as **Login items** (cipher type `1`) inside a dedicated `URLVault` folder in your vault, so each entry has a clickable URI in the Bitwarden UI. The bookmark's full JSON is serialised into the item's notes field. Conflicts are resolved per-bookmark by latest `updatedAt` timestamp.

## Security

- The master password is **never** persisted to disk. On Desktop it is requested at startup and held only in memory; on Android it is held in memory while sync is active and not written to `EncryptedSharedPreferences`.
- Android database is encrypted with **SQLCipher (AES-256)**; the passphrase is generated with `SecureRandom` and stored in `EncryptedSharedPreferences` whose master key lives in the Android Keystore. The current keys are **not** configured with `setUserAuthenticationRequired(true)`, so they are not biometry-gated yet — that is on the roadmap.
- Bitwarden credentials on Android (everything except the master password) are persisted in **`EncryptedSharedPreferences`** (AES-256 SIV / AES-256 GCM) backed by a Keystore-resident master key.
- Desktop persists Bitwarden credentials (everything except the master password) in `~/.urlvault/credentials.enc`, encrypted with AES-256-GCM. The AES key is stored using the best available OS secret store: **macOS Keychain**, **Linux Secret Service** (GNOME Keyring / KDE Wallet via `secret-tool`), or a PKCS12 Java KeyStore fallback.
- The Desktop SQLite database itself is **not** encrypted yet.
- iOS does not persist anything yet (in-memory repository).
- No cloud backup of database or credentials on Android (`dataExtractionRules.xml` excludes them).

## Platform Maturity

| Platform | Status        | Notes                                                                                            |
|----------|---------------|--------------------------------------------------------------------------------------------------|
| Android  | Most complete | Encrypted DB, DI, share extension, encrypted credential persistence, on-device AI                |
| Desktop  | Functional    | No DB encryption; encrypted credential persistence via OS keychain (master password in-memory only) |
| iOS      | Early         | Compose UI works; storage is in-memory only, no credential persistence                           |
