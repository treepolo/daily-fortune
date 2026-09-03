package com.treepolo.dailyfortune.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        LocalFortuneDrawEntity::class,
        LocalDailyFortuneStateEntity::class,
        LocalAnalyticsEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DailyFortuneDatabase : RoomDatabase() {
    abstract fun fortuneDao(): LocalFortuneDao

    companion object {
        @Volatile private var instance: DailyFortuneDatabase? = null

        fun get(context: Context): DailyFortuneDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                DailyFortuneDatabase::class.java,
                "daily-fortune-v2.db",
            )
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
                .also { instance = it }
        }
    }
}
