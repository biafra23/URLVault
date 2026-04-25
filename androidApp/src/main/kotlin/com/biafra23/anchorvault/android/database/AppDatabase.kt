package com.biafra23.anchorvault.android.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Room database with SQLCipher encryption for local bookmark storage.
 */
@Database(
    entities = [BookmarkEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        private const val DATABASE_NAME = "anchorvault.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns (or lazily creates) the encrypted Room database instance.
         *
         * @param context Application context.
         * @param passphrase The encryption key used by SQLCipher. The caller is responsible
         *                   for securely storing this key (e.g., via Android Keystore).
         */
        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, passphrase).also { INSTANCE = it }
            }

        private fun buildDatabase(context: Context, passphrase: ByteArray): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
    }
}
