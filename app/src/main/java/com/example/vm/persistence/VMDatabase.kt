package com.example.vm.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.vm.core.VMConfig

@Database(entities = [VMConfig::class], version = 3, exportSchema = false)
abstract class VMDatabase : RoomDatabase() {
    abstract fun vmConfigDao(): VMConfigDao

    companion object {
        @Volatile
        private var INSTANCE: VMDatabase? = null

        fun getDatabase(context: Context): VMDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VMDatabase::class.java,
                    "mobile_vm_database"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
