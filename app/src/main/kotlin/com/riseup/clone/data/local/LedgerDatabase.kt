package com.riseup.clone.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The on-device store for the household ledger. Small and single-household for
 * now; versioned from 1 so later schema changes ship as Room migrations.
 */
@Database(
    entities = [AccountEntity::class, TransactionEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun ledgerDao(): LedgerDao

    companion object {
        private const val DATABASE_NAME = "ledger.db"

        /** Builds the app-wide database instance for [context]. */
        fun build(context: Context): LedgerDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LedgerDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
