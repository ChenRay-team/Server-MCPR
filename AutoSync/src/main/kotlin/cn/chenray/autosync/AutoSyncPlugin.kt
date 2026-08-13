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

    /** onEnable 是否完整执行成功（用于防止 enable 失败禁用时触发多余的 onDisable 同步） */
    private var fullyEnabled = false

    override fun onEnable() {
        saveDefaultConfig()

        syncConfig = AutoSyncConfig.fromConfig(config)
        if (!syncConfig.isValid()) {
            logger.severe("AutoSync 配置无效：请检查 config.yml 中的 owner/repo/token 是否填写正确")
            server.pluginManager.disablePlugin(this)
            return
        }

        syncManager = SyncManager(this)

        // 定时同步（Bukkit scheduler，不受翼龙面板 Linux 限制）
        val intervalTicks = (syncConfig.intervalMinutes * 60 * 20L).toLong().coerceAtLeast(20)
        if (syncConfig.syncOnEnable) {
            server.scheduler.runTaskAsynchronously(this, Runnable { syncManager.syncNow() })
        }
        server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable { syncManager.syncNow() },
            intervalTicks,
            intervalTicks,
        )

        logger.info("AutoSync 已启用：每 ${syncConfig.intervalMinutes} 分钟上传 replay/player 录像到 ${syncConfig.owner}/${syncConfig.repo} 仓库的 ${syncConfig.branch} 分支")
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

        fullyEnabled = true
    }

    override fun onDisable() {
        // 注意：插件禁用/服务器关闭时 scheduler 已不可用，
        // 不能再用 runTaskAsynchronously 注册任务（会抛 IllegalPluginAccessException）。
        // 这里直接同步执行最后一次备份，并捕获所有异常避免中断关闭流程。
        if (fullyEnabled && syncConfig.syncOnDisable) {
            try {
                syncManager.syncNow()
            } catch (e: Exception) {
                logger.warning("[AutoSync] 关闭时同步失败：${e.message}")
            }
        }
    }
}
