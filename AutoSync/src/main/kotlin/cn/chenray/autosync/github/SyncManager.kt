package cn.chenray.autosync.github

import cn.chenray.autosync.AutoSyncPlugin
import java.io.File
import java.nio.file.Files

/**
 * 同步管理器（录像模式）：
 *  - 扫描服务器根目录 replay/player 下的 .mcpr 录像（含 玩家名/ 子目录）
 *  - 上传到仓库的 player/ 目录（仓库只显示 player 文件夹）
 *  - 单向上传：本地 → GitHub，不反向拉取（录像不回写服务器）
 *  - 自动跳过超过 100MB 的文件（GitHub Contents API 限制）
 *  - 保留本地文件（只上传不删除）
 */
class SyncManager(private val plugin: AutoSyncPlugin) {

    private val cfg get() = plugin.syncConfig
    private val client by lazy {
        GitHubClient(cfg.owner, cfg.repo, cfg.token, cfg.branch)
    }

    /** 服务器根目录（server.jar 同级，即翼龙 /home/container） */
    private val serverRoot: File
        get() {
            // 优先用 Bukkit 的世界容器目录作为服务器根（更可靠，避免 dataFolder.parentFile 为 null）
            val worldContainer = plugin.server.worldContainer
            return if (worldContainer.exists()) {
                worldContainer
            } else {
                // 兜底：dataFolder 的上级上级（plugins -> 根目录）
                val plugins = plugin.dataFolder.parentFile
                plugins?.parentFile ?: File(System.getProperty("user.dir", "."))
            }
        }

    /** replay/player 根目录：/home/container/replay/player */
    private val playerRoot: File
        get() = File(File(serverRoot, "replay"), "player")

    fun syncNow() {
        try {
            val start = System.currentTimeMillis()
            val logger = plugin.logger

            if (!playerRoot.exists()) {
                logger.warning("[AutoSync] replay/player 目录不存在：${playerRoot.absolutePath}")
                return
            }

            // 1) 收集本地 .mcpr 录像文件（相对 playerRoot 的路径，如 张三@uuid/2026-08-13.mcpr）
            val localFiles = collectMcprFiles(playerRoot, playerRoot)
            logger.info("[AutoSync] 发现 ${localFiles.size} 个录像文件")

            // 2) 远程现有文件（player 目录下）
            val remoteFiles = client.listRemoteTree("player")

            // 3) 上传本地新增/修改的录像（只推不拉）
            var pushed = 0
            var skipped = 0
            for ((relPath, localFile) in localFiles) {
                val size = localFile.length()

                // 跳过超大文件（GitHub Contents API 上限 100MB）
                if (size > MAX_FILE_SIZE) {
                    logger.warning("[AutoSync] 跳过超大文件（>${MAX_FILE_SIZE / 1024 / 1024}MB）：$relPath（${size / 1024 / 1024}MB）")
                    skipped++
                    continue
                }

                // 把相对路径里的 "玩家名@uuid" 目录名转换为 "玩家名"（去掉 @ 后乱码）
                val cleanRelPath = cleanPath(relPath)
                val remotePath = "player/$cleanRelPath"
                val remoteSha = remoteFiles[cleanRelPath]
                // 远程已存在同名文件则跳过（录像一旦生成不会修改）
                if (remoteSha != null) {
                    continue
                }
                if (client.putFileBytes(remotePath, Files.readAllBytes(localFile.toPath()), remoteSha)) {
                    pushed++
                    logger.info("[AutoSync] 已上传 $cleanRelPath（${size / 1024}KB）")
                }
            }

            val ms = System.currentTimeMillis() - start
            logger.info("[AutoSync] 同步完成：上传 $pushed，跳过 $skipped（${ms}ms）")
        } catch (e: Exception) {
            plugin.logger.warning("[AutoSync] 同步失败：${e.message}")
            if (plugin.config.getBoolean("debug", false)) {
                e.printStackTrace()
            }
        }
    }

    // ---------- 内部 ----------

    companion object {
        /** GitHub Contents API 单文件上限：100MB */
        private const val MAX_FILE_SIZE = 100L * 1024 * 1024
    }

    /**
     * 把相对路径中每个目录段的 "名字@uuid" 转换为 "名字"。
     * 例如：
     *   "张三@a1b2c3d4-e5f6/2026-08-13.mcpr"
     *     → "张三/2026-08-13.mcpr"
     */
    private fun cleanPath(relPath: String): String {
        return relPath.split("/").joinToString("/") { seg ->
            if (seg.contains("@")) seg.substringBefore("@") else seg
        }
    }

    /** 递归收集 replay 目录下所有 .mcpr 文件 */
    private fun collectMcprFiles(root: File, dir: File): Map<String, File> {
        val result = LinkedHashMap<String, File>()
        val children = dir.listFiles()?.sortedBy { it.name } ?: return result
        for (f in children) {
            val rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/')
            if (f.isDirectory) {
                result.putAll(collectMcprFiles(root, f))
            } else if (f.name.endsWith(".mcpr")) {
                result[rel] = f
            }
        }
        return result
    }
}
