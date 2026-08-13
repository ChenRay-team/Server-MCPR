package cn.chenray.autosync

import cn.chenray.autosync.config.AutoSyncConfig
import cn.chenray.autosync.github.SyncManager
import org.bukkit.plugin.java.JavaPlugin

/**
 * AutoSync —— 服务器配置自动同步到 GitHub 仓库的插件。
 *
 * 在翼龙面板（禁 Linux 指令）环境下，通过 GitHub Contents API 实现
 * 配置文件的双向同步，无需 shell / git / cron。
 */
class AutoSyncPlugin : JavaPlugin() {

    lateinit var syncConfig: AutoSyncConfig
        private set
    lateinit var syncManager: SyncManager
        private set

    override fun onEnable() {
        saveDefaultConfig()

        syncConfig = AutoSyncConfig.fromConfig(config)
        if (!syncConfig.isValid()) {
            logger.severe("AutoSync 配置无效：请检查 config.yml 中的 owner/repo/token 是否填写正确")
            isEnabled = false
            return
        }

        syncManager = SyncManager(this)

        // 定时同步（Bukkit scheduler，不受翼龙面板 Linux 限制）
        val intervalTicks = (syncConfig.intervalMinutes * 60 * 20L).toLong().coerceAtLeast(20)
        if (syncConfig.syncOnEnable) {
            server.scheduler.runTaskAsynchronously(this, Runnable { syncManager.syncNow() })
        }
        syncTaskId = server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable { syncManager.syncNow() },
            intervalTicks,
            intervalTicks,
        ).taskId

        logger.info("AutoSync 已启用：每 ${syncConfig.intervalMinutes} 分钟同步 ${syncConfig.owner}/${syncConfig.repo} 仓库的 ${syncConfig.remoteFolder}")
        getCommand("autosync")?.setExecutor { _, _, _, args ->
            when (args.firstOrNull()?.lowercase()) {
                "now" -> {
                    server.scheduler.runTaskAsynchronously(this, Runnable { syncManager.syncNow() })
                    true
                }
                "reload" -> {
                    reloadConfig()
                    syncConfig = AutoSyncConfig.fromConfig(config)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDisable() {
        if (syncConfig.syncOnDisable) {
            server.scheduler.runTaskAsynchronously(this, Runnable { syncManager.syncNow() })
        }
    }

    private var syncTaskId = -1
}
