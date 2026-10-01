package com.example.vm.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.vm.core.VMConfig
import com.example.vm.storage.StorageOperationLog
import com.example.vm.storage.StorageOperationLogDao
import com.example.vm.storage.VmBackup
import com.example.vm.storage.VmBackupDao
import com.example.vm.storage.VmDisk
import com.example.vm.storage.VmDiskDao
import com.example.vm.storage.VmSnapshot
import com.example.vm.storage.VmSnapshotDao

@Database(
    entities = [
        VMConfig::class,
        VmDisk::class,
        VmSnapshot::class,
        VmBackup::class,
        StorageOperationLog::class
    ],
    version = 6,
    exportSchema = false
)
abstract class VMDatabase : RoomDatabase() {
    abstract fun vmConfigDao(): VMConfigDao
    abstract fun vmDiskDao(): VmDiskDao
    abstract fun vmSnapshotDao(): VmSnapshotDao
    abstract fun vmBackupDao(): VmBackupDao
    abstract fun storageOperationLogDao(): StorageOperationLogDao

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
