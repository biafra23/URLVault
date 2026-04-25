# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

URLVault is a Kotlin Multiplatform (KMP) bookmark manager that syncs with Bitwarden. It targets Android, Desktop (JVM), and iOS using Compose Multiplatform for shared UI.

## Build Commands

```bash
# Android
./gradlew :androidApp:installDebug        # Build and install on connected device/emulator
./gradlew :androidApp:assembleDebug        # Build APK only
./gradlew :androidApp:assembleRelease      # Build release APK (ProGuard enabled)

# Desktop
./gradlew :desktopApp:run                  # Run desktop app

# Shared module
./gradlew :shared:build                    # Build shared KMP module

# Full project
./gradlew build                            # Build all modules
```

There are no tests in the project yet. The Android module is configured with `AndroidJUnitRunner` for future instrumentation tests.

## Architecture

### Security
- Never store the master password in plaintext or predictable crypto. Only in hardware keystores secured by least biometry. On desktop ask for it on startup, and keep it in memory. But do not store it.
- Allow input of master password from Bitwarden app on android, ios and desktop

### Module Structure

- **`:shared`** — KMP library consumed by all platform modules. Contains all UI (Compose), ViewModels, domain models, repository interface, and Bitwarden sync logic in `commonMain`. Platform-specific source sets (`androidMain`, `desktopMain`, `iosMain`) provide Ktor engine implementations.
- **`:androidApp`** — Android app shell. Owns the Room+SQLCipher database layer, Koin DI wiring (`AppModule.kt`), and `MainActivity` (handles `ACTION_SEND` share intent).
- **`:desktopApp`** — Desktop app shell. Has its own `DesktopBookmarkRepository` using JDBC SQLite. DI is manual (no Koin).
- **`iosApp/`** — Xcode project hosting the shared Compose framework via `UIViewControllerRepresentable`. Not included in Gradle settings.

### Key Patterns

**Repository pattern with platform-specific implementations:**
- `BookmarkRepository` interface in `shared/commonMain`
- `RoomBookmarkRepository` in `androidApp` (Room + SQLCipher, encrypted via Android Keystore)
- `DesktopBookmarkRepository` in `desktopApp` (SQLite JDBC, stores DB at `~/.urlvault/bookmarks.db`)
- `IosBookmarkRepository` in `shared/iosMain` (in-memory placeholder — no persistence yet)

**Bitwarden sync flow:**
- `BitwardenSyncService` interface → `KtorBitwardenSyncService` implementation
- OAuth 2.0 client credentials flow against Bitwarden Identity API
- Bookmarks stored as Secure Notes (ciphers of type 2) in a dedicated "URLVault" folder
- Merge strategy: latest `updatedAt` timestamp wins per bookmark ID

**State management:**
- `BookmarkViewModel` uses Kotlin Flows with `flatMapLatest` for reactive filtering by tag/search query
- UI state exposed as `StateFlow<BookmarkListUiState>`
- Navigation handled via sealed `Screen` classes in each platform's main entry point (no navigation library)

### Dependency Injection

- **Android:** Koin — configured in `AppModule.kt`, started in `URLVaultApp.kt`
- **Desktop/iOS:** Manual instantiation in their respective `Main.kt`/`MainViewController.kt`

### Package Structure (shared module)

```
com.biafra23.urlvault/
├── model/          # Bookmark, Tag (both @Serializable)
├── repository/     # BookmarkRepository interface
├── sync/           # BitwardenSyncService, KtorBitwardenSyncService, BitwardenCredentials
├── ui/             # BookmarkListScreen, AddEditBookmarkScreen, SettingsScreen
├── ui/theme/       # Material 3 theme (AnchorBlue/AnchorTeal)
└── viewmodel/      # BookmarkViewModel
```

## Dependencies

Versions managed in `gradle/libs.versions.toml`. Key dependencies: Kotlin 2.1.0, Compose Multiplatform 1.7.3, Room 2.6.1, SQLCipher 4.5.4, Ktor 3.0.3, Koin 4.1.1, kotlinx-serialization 1.7.3. Android targets JVM 11, minSdk 29, compileSdk/targetSdk 36.

## Platform Maturity

- **Android:** Most complete — encrypted storage, DI, share extension, credential persistence
- **Desktop:** Functional but no DB encryption, no credential persistence
- **iOS:** UI works via Compose but storage is in-memory only (no persistence)
