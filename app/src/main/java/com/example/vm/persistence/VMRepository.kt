package com.example.vm.persistence

import com.example.vm.core.VMConfig
import kotlinx.coroutines.flow.Flow

class VMRepository(private val dao: VMConfigDao) {
    val allConfigs: Flow<List<VMConfig>> = dao.getAllConfigurations()

    suspend fun getConfigById(id: Long): VMConfig? {
        return dao.getConfigurationById(id)
    }

    suspend fun insertConfig(config: VMConfig): Long {
        return dao.insertConfiguration(config)
    }

    suspend fun updateConfig(config: VMConfig) {
        dao.updateConfiguration(config)
    }

    suspend fun deleteConfig(config: VMConfig) {
        dao.deleteConfiguration(config)
    }

    suspend fun deleteConfigById(id: Long) {
        dao.deleteConfigurationById(id)
    }
}
