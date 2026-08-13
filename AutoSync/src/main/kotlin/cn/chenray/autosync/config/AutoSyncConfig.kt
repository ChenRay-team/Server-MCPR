package cn.chenray.autosync.config

import org.bukkit.configuration.file.FileConfiguration

/**
 * AutoSync 配置，对应 config.yml。
 * 用于把服务器 replay/ 目录下的 .mcpr 录像上传到 GitHub。
 */
data class AutoSyncConfig(
    val owner: String,
    val repo: String,
    val token: String,
    val branch: String,
    val intervalMinutes: Int,
    val syncOnEnable: Boolean,
    val syncOnDisable: Boolean,
    val maxFileSizeMB: Int,
) {
    fun isValid(): Boolean =
        owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank()

    companion object {
        fun fromConfig(cfg: FileConfiguration): AutoSyncConfig {
            val github = cfg.getConfigurationSection("github")
            val sync = cfg.getConfigurationSection("sync")
            return AutoSyncConfig(
                owner = github?.getString("owner") ?: "",
                repo = github?.getString("repo") ?: "",
                token = github?.getString("token") ?: "",
                branch = github?.getString("branch") ?: "main",
                intervalMinutes = sync?.getInt("interval-minutes") ?: 5,
                syncOnEnable = sync?.getBoolean("sync-on-enable") ?: true,
                syncOnDisable = sync?.getBoolean("sync-on-disable") ?: true,
                maxFileSizeMB = sync?.getInt("max-file-size-mb") ?: 100,
            )
        }
    }
}
