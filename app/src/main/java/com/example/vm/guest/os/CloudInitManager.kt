package com.example.vm.guest.os

import android.content.Context
import java.io.File
import java.io.FileOutputStream

data class CloudInitConfig(
    val hostname: String = "mobilevm-guest",
    val username: String = "ubuntu",
    val password: String = "ubuntu",
    val sshPublicKey: String = "",
    val networkEnabled: Boolean = true
)

object CloudInitManager {

    /**
     * Generates cloud-init user-data YAML file for cloud pre-installed images.
     */
    fun generateUserDataYaml(config: CloudInitConfig): String {
        val sb = StringBuilder()
        sb.append("#cloud-config\n")
        sb.append("hostname: ${config.hostname.ifBlank { "mobilevm-guest" }}\n")
        sb.append("fqdn: ${config.hostname.ifBlank { "mobilevm-guest" }}.local\n")
        sb.append("manage_etc_hosts: true\n\n")

        sb.append("users:\n")
        sb.append("  - name: ${config.username.ifBlank { "ubuntu" }}\n")
        sb.append("    gecos: MobileVM User\n")
        sb.append("    sudo: ALL=(ALL) NOPASSWD:ALL\n")
        sb.append("    groups: users, admin, sudo\n")
        sb.append("    shell: /bin/bash\n")
        sb.append("    lock_passwd: false\n")
        sb.append("    plain_text_passwd: '${config.password.ifBlank { "ubuntu" }}'\n")

        if (config.sshPublicKey.isNotBlank()) {
            sb.append("    ssh_authorized_keys:\n")
            sb.append("      - '${config.sshPublicKey.trim()}'\n")
        }

        sb.append("\nchpasswd:\n")
        sb.append("  list: |\n")
        sb.append("    ${config.username.ifBlank { "ubuntu" }}:${config.password.ifBlank { "ubuntu" }}\n")
        sb.append("  expire: false\n\n")

        sb.append("ssh_pwauth: true\n")
        sb.append("disable_root: false\n\n")

        sb.append("runcmd:\n")
        sb.append("  - echo 'MobileVM Guest OS initialized successfully.' > /etc/mobilevm-release\n")

        return sb.toString()
    }

    /**
     * Writes cloud-init configuration files into private VM directory.
     */
    fun writeCloudInitFiles(context: Context, vmId: Long, config: CloudInitConfig): File {
        val baseDir = File(context.filesDir, "guest_cloud_init")
        if (!baseDir.exists()) baseDir.mkdirs()

        val vmDir = File(baseDir, "vm_$vmId")
        if (!vmDir.exists()) vmDir.mkdirs()

        val userDataFile = File(vmDir, "user-data")
        val metaDataFile = File(vmDir, "meta-data")

        FileOutputStream(userDataFile).use { out ->
            out.write(generateUserDataYaml(config).toByteArray(Charsets.UTF_8))
        }

        val metaDataContent = "instance-id: mobilevm-$vmId\nlocal-hostname: ${config.hostname}\n"
        FileOutputStream(metaDataFile).use { out ->
            out.write(metaDataContent.toByteArray(Charsets.UTF_8))
        }

        return vmDir
    }
}
