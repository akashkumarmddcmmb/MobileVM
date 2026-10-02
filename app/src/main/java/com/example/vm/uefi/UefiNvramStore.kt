package com.example.vm.uefi

import android.content.Context
import android.util.Log
import com.example.vm.cpu.GuestArchitecture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * UefiBootVariable: Model for individual UEFI Boot Entry in NVRAM (Boot0000, Boot0001...).
 */
data class UefiBootVariable(
    val id: String, // e.g. "Boot0000"
    val displayName: String,
    val type: String, // "WINDOWS_BOOT_MANAGER", "LINUX_EFI_LOADER", "INSTALLATION_ISO", "VIRTUAL_DISK", "DIRECT_KERNEL", "RECOVERY"
    val diskId: String = "",
    val espPartition: Int = 1,
    val efiPath: String = "\\EFI\\Microsoft\\Boot\\bootmgfw.efi",
    val bcdPath: String = "\\EFI\\Microsoft\\Boot\\BCD",
    val enabled: Boolean = true,
    val architecture: String = "ARM64",
    val bootArguments: String = "",
    val priority: Int = 1
)

/**
 * UefiNvramStore: Persistent UEFI-style NVRAM variable store abstraction.
 * Preserves Boot0000..BootFFFF entries, BootOrder list, BootNext variable, and Timeout across VM restarts.
 */
class UefiNvramStore private constructor(private val vmId: Long) {

    companion object {
        private const val TAG = "UefiNvramStore"
        private val instances = mutableMapOf<Long, UefiNvramStore>()

        fun getInstance(vmId: Long): UefiNvramStore {
            return instances.getOrPut(vmId) { UefiNvramStore(vmId) }
        }
    }

    private val entries = mutableMapOf<String, UefiBootVariable>()
    private var bootOrder = mutableListOf<String>()
    private var bootNext: String? = null
    private var timeoutSeconds: Int = 5
    private var defaultEntryId: String = "Boot0000"

    fun createEntry(entry: UefiBootVariable): String {
        var id = entry.id
        if (id.isBlank() || !id.startsWith("Boot")) {
            var counter = 0
            while (entries.containsKey(String.format("Boot%04D", counter))) {
                counter++
            }
            id = String.format("Boot%04D", counter)
        }
        val newEntry = entry.copy(id = id)
        entries[id] = newEntry
        if (!bootOrder.contains(id)) {
            bootOrder.add(id)
        }
        if (defaultEntryId.isBlank()) {
            defaultEntryId = id
        }
        return id
    }

    fun updateEntry(entry: UefiBootVariable): Boolean {
        if (!entries.containsKey(entry.id)) return false
        entries[entry.id] = entry
        return true
    }

    fun deleteEntry(id: String): Boolean {
        if (!entries.containsKey(id)) return false
        entries.remove(id)
        bootOrder.remove(id)
        if (bootNext == id) bootNext = null
        if (defaultEntryId == id) defaultEntryId = bootOrder.firstOrNull() ?: ""
        return true
    }

    fun getEntry(id: String): UefiBootVariable? = entries[id]

    fun getEntries(): List<UefiBootVariable> {
        return bootOrder.mapNotNull { entries[it] } + entries.values.filter { !bootOrder.contains(it.id) }
    }

    fun setBootOrder(order: List<String>) {
        bootOrder = order.filter { entries.containsKey(it) }.toMutableList()
    }

    fun getBootOrder(): List<String> = bootOrder.toList()

    fun setBootNext(id: String?) {
        if (id == null || entries.containsKey(id)) {
            bootNext = id
        }
    }

    fun getBootNext(): String? = bootNext

    fun clearBootNext() {
        bootNext = null
    }

    fun setDefaultEntry(id: String) {
        if (entries.containsKey(id)) {
            defaultEntryId = id
        }
    }

    fun getDefaultEntry(): UefiBootVariable? {
        return entries[defaultEntryId] ?: entries[bootOrder.firstOrNull()] ?: entries.values.firstOrNull()
    }

    fun setTimeout(seconds: Int) {
        timeoutSeconds = seconds.coerceIn(0, 60)
    }

    fun getTimeout(): Int = timeoutSeconds

    fun persist(context: Context) {
        try {
            val nvramDir = File(context.filesDir, "nvram").apply { mkdirs() }
            val file = File(nvramDir, "nvram_vm_${vmId}.json")

            val root = JSONObject()
            root.put("timeoutSeconds", timeoutSeconds)
            root.put("defaultEntryId", defaultEntryId)
            root.put("bootNext", bootNext ?: "")

            val orderArray = JSONArray()
            bootOrder.forEach { orderArray.put(it) }
            root.put("bootOrder", orderArray)

            val entriesArray = JSONArray()
            entries.values.forEach { varObj ->
                val obj = JSONObject()
                obj.put("id", varObj.id)
                obj.put("displayName", varObj.displayName)
                obj.put("type", varObj.type)
                obj.put("diskId", varObj.diskId)
                obj.put("espPartition", varObj.espPartition)
                obj.put("efiPath", varObj.efiPath)
                obj.put("bcdPath", varObj.bcdPath)
                obj.put("enabled", varObj.enabled)
                obj.put("architecture", varObj.architecture)
                obj.put("bootArguments", varObj.bootArguments)
                obj.put("priority", varObj.priority)
                entriesArray.put(obj)
            }
            root.put("entries", entriesArray)

            file.writeText(root.toString(2))
            Log.i(TAG, "Persisted NVRAM for VM $vmId to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist NVRAM for VM $vmId: ${e.message}")
        }
    }

    fun restore(context: Context) {
        try {
            val nvramDir = File(context.filesDir, "nvram")
            val file = File(nvramDir, "nvram_vm_${vmId}.json")
            if (!file.exists()) {
                initializeDefaultEntries()
                persist(context)
                return
            }

            val text = file.readText()
            val root = JSONObject(text)
            timeoutSeconds = root.optInt("timeoutSeconds", 5)
            defaultEntryId = root.optString("defaultEntryId", "Boot0000")
            val bn = root.optString("bootNext", "")
            bootNext = if (bn.isNotBlank()) bn else null

            val orderArr = root.optJSONArray("bootOrder")
            bootOrder.clear()
            if (orderArr != null) {
                for (i in 0 until orderArr.length()) {
                    bootOrder.add(orderArr.getString(i))
                }
            }

            entries.clear()
            val entriesArr = root.optJSONArray("entries")
            if (entriesArr != null) {
                for (i in 0 until entriesArr.length()) {
                    val obj = entriesArr.getJSONObject(i)
                    val varObj = UefiBootVariable(
                        id = obj.getString("id"),
                        displayName = obj.getString("displayName"),
                        type = obj.getString("type"),
                        diskId = obj.optString("diskId", ""),
                        espPartition = obj.optInt("espPartition", 1),
                        efiPath = obj.optString("efiPath", "\\EFI\\Microsoft\\Boot\\bootmgfw.efi"),
                        bcdPath = obj.optString("bcdPath", "\\EFI\\Microsoft\\Boot\\BCD"),
                        enabled = obj.optBoolean("enabled", true),
                        architecture = obj.optString("architecture", "ARM64"),
                        bootArguments = obj.optString("bootArguments", ""),
                        priority = obj.optInt("priority", i + 1)
                    )
                    entries[varObj.id] = varObj
                }
            }

            if (entries.isEmpty()) {
                initializeDefaultEntries()
            }
            Log.i(TAG, "Restored NVRAM for VM $vmId (${entries.size} entries loaded)")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring NVRAM for VM $vmId: ${e.message}")
            initializeDefaultEntries()
        }
    }

    private fun initializeDefaultEntries() {
        entries.clear()
        bootOrder.clear()

        val win = UefiBootVariable(
            id = "Boot0000",
            displayName = "Windows Boot Manager",
            type = "WINDOWS_BOOT_MANAGER",
            efiPath = "\\EFI\\Microsoft\\Boot\\bootmgfw.efi",
            bcdPath = "\\EFI\\Microsoft\\Boot\\BCD",
            priority = 1
        )
        val linux = UefiBootVariable(
            id = "Boot0001",
            displayName = "Linux EFI Bootloader",
            type = "LINUX_EFI_LOADER",
            efiPath = "\\EFI\\BOOT\\BOOTAA64.EFI",
            priority = 2
        )
        val iso = UefiBootVariable(
            id = "Boot0002",
            displayName = "EFI Optical ISO Installer",
            type = "INSTALLATION_ISO",
            efiPath = "\\EFI\\BOOT\\BOOTAA64.EFI",
            priority = 3
        )
        val recovery = UefiBootVariable(
            id = "Boot0003",
            displayName = "UEFI Diagnostics & Recovery Shell",
            type = "RECOVERY",
            efiPath = "\\EFI\\Boot\\boot recovery.efi",
            priority = 4
        )

        entries[win.id] = win
        entries[linux.id] = linux
        entries[iso.id] = iso
        entries[recovery.id] = recovery

        bootOrder.addAll(listOf("Boot0000", "Boot0001", "Boot0002", "Boot0003"))
        defaultEntryId = "Boot0000"
    }
}
