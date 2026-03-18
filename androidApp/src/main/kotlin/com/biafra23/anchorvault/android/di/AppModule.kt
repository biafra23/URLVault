package com.biafra23.anchorvault.android.di

import android.content.Context
import com.biafra23.anchorvault.android.database.AppDatabase
import com.biafra23.anchorvault.android.database.DatabaseKeyManager
import com.biafra23.anchorvault.android.database.RoomBookmarkRepository
import com.biafra23.anchorvault.android.sync.AndroidBitwardenPreferences
import com.biafra23.anchorvault.repository.BookmarkRepository
import com.biafra23.anchorvault.sync.BitwardenSyncService
import com.biafra23.anchorvault.sync.createBitwardenSyncService
import com.biafra23.anchorvault.viewmodel.BookmarkViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // Application-level coroutine scope
    single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // Database
    single<AppDatabase> {
        val context: Context = androidContext()
        val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
        AppDatabase.getInstance(context, passphrase)
    }

    single { get<AppDatabase>().bookmarkDao() }

    // Repository
    single<BookmarkRepository> {
        RoomBookmarkRepository(get())
    }

    // Bitwarden sync
    single<BitwardenSyncService> {
        val service = createBitwardenSyncService()
        // Restore previously saved credentials on app start
        val prefs = AndroidBitwardenPreferences(androidContext())
        val savedCredentials = prefs.loadCredentials()
        if (savedCredentials != null) {
            get<CoroutineScope>().launch {
                service.configure(savedCredentials)
            }
        }
        service
    }

    // ViewModel
    viewModel {
        BookmarkViewModel(get(), get())
    }
}
