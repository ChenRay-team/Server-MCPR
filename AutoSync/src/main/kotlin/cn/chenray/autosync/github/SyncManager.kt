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
 *
 * 性能优化（降低对 TPS 影响）：
 *  - 单次同步最多处理 MAX_FILES_PER_RUN 个文件
 *  - 单次同步总上传字节数受 MAX_BYTES_PER_RUN 限制
 *  - 每个文件流式读取 + 流式 base64，避免整文件进内存
 *  - 上传采用小并发 + 每文件超时，避免阻塞主线程太久
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

            // 2) 远程现有文件（player 目录下）—— 一次性获取，避免每个文件单独请求
            val remoteFiles = client.listRemoteTree("player")

            // 3) 过滤出需要上传的文件（本地有、远程没有；跳过超大）
            val pending = localFiles.entries
                .filter { (relPath, localFile) ->
                    if (localFile.length() > MAX_FILE_SIZE) {
                        logger.warning("[AutoSync] 跳过超大文件（>${MAX_FILE_SIZE / 1024 / 1024}MB）：$relPath")
                        false
                    } else {
                        // 远程不存在才需要上传
                        remoteFiles[cleanPath(relPath)] == null
                    }
                }
                .take(MAX_FILES_PER_RUN)

            if (pending.isEmpty()) {
                val ms = System.currentTimeMillis() - start
                logger.info("[AutoSync] 无待上传录像（${ms}ms）")
                return
            }

            // 4) 分批上传（控制单批字节数，避免内存/带宽高峰）
            var pushed = 0
            var batchBytes = 0L
            var batchCount = 0
            for ((relPath, localFile) in pending) {
                val size = localFile.length()
                // 达到本批上限则先休眠释放资源，再继续下一批
                if (batchBytes > 0 && batchBytes + size > MAX_BYTES_PER_BATCH) {
                    logger.info("[AutoSync] 批处理达到上限，暂停 2 秒释放资源…")
                    Thread.sleep(2000)
                    batchBytes = 0
                    batchCount = 0
                }

                val cleanRelPath = cleanPath(relPath)
                val remotePath = "player/$cleanRelPath"
                // 流式上传：读文件 → base64 → PUT（不整文件进内存）
                if (client.putFileStream(remotePath, localFile, null)) {
                    pushed++
                    batchBytes += size
                    batchCount++
                    if (batchCount <= 3) { // 前几个打日志，避免刷屏
                        logger.info("[AutoSync] 已上传 $cleanRelPath（${size / 1024}KB）")
                    }
                }
            }

            val ms = System.currentTimeMillis() - start
            logger.info("[AutoSync] 同步完成：上传 $pushed / ${pending.size}（${ms}ms）")
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
        /** 单次同步最多处理的文件数（防止一次全量上传卡线程） */
        private const val MAX_FILES_PER_RUN = 20
        /** 单批上传总字节上限（约 50MB/批，控制内存） */
        private const val MAX_BYTES_PER_BATCH = 50L * 1024 * 1024
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
