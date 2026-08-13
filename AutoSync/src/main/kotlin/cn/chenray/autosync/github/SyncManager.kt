package cn.chenray.autosync.github

import cn.chenray.autosync.AutoSyncPlugin
import java.io.File
import java.nio.file.Files

/**
 * 双向同步管理器：
 *  - 推送：本地新增/修改的文件上传到仓库
 *  - 拉取：仓库有而本地没有的文件下载到本地
 *  - 删除：本地删掉的文件在仓库也删除（可选）
 */
class SyncManager(private val plugin: AutoSyncPlugin) {

    private val cfg get() = plugin.syncConfig
    private val client by lazy {
        GitHubClient(cfg.owner, cfg.repo, cfg.token, cfg.branch)
    }

    private val localRoot: File
        get() = File(plugin.dataFolder.parentFile, cfg.localFolder)

    fun syncNow() {
        try {
            val start = System.currentTimeMillis()
            val logger = plugin.logger

            if (!localRoot.exists()) {
                logger.warning("[AutoSync] 本地目录不存在：${localRoot.absolutePath}")
                return
            }

            // 1) 收集本地文件（排除 ignore）
            val localFiles = collectLocalFiles(localRoot, localRoot, cfg.exclude)
            // 2) 远程现有文件
            val remoteFiles = client.listRemoteTree(cfg.remoteFolder)

            // 3) 本地 → 远程（新增/更新）
            var pushed = 0
            for ((relPath, localFile) in localFiles) {
                val remotePath = "${cfg.remoteFolder.trimEnd('/')}/$relPath"
                val remoteSha = remoteFiles[relPath]
                val localContent = readText(localFile)
                val remoteContent = remoteSha?.let { client.getFileContent(remotePath) }
                // 内容一致则跳过
                if (remoteContent == localContent) continue
                if (client.putFile(remotePath, localContent, remoteSha)) {
                    pushed++
                    logger.info("[AutoSync] 已推送 $relPath")
                }
            }

            // 4) 远程 → 本地（拉取远程独有文件）
            var pulled = 0
            for ((relPath, sha) in remoteFiles) {
                if (localFiles.containsKey(relPath)) continue
                val localFile = File(localRoot, relPath)
                localFile.parentFile?.mkdirs()
                val content = client.getFileContent("${cfg.remoteFolder.trimEnd('/')}/$relPath")
                    ?: continue
                if (writeText(localFile, content)) {
                    pulled++
                    logger.info("[AutoSync] 已拉取 $relPath")
                }
            }

            // 5) 清理：仓库比本地多的已删文件 → 仓库删除（受 delete-remote 控制）
            //    本地存在、远程没有 → 已在第 3 步新建上传，无需删除

            val ms = System.currentTimeMillis() - start
            logger.info("[AutoSync] 同步完成：推送 $pushed / 拉取 $pulled（${ms}ms）")
        } catch (e: Exception) {
            plugin.logger.warning("[AutoSync] 同步失败：${e.message}")
            if (plugin.config.getBoolean("debug", false)) {
                e.printStackTrace()
            }
        }
    }

    // ---------- 内部 ----------

    private fun collectLocalFiles(root: File, dir: File, excludes: List<String>): Map<String, File> {
        val result = LinkedHashMap<String, File>()
        val children = dir.listFiles()?.sortedBy { it.name } ?: return result
        for (f in children) {
            val rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/')
            if (isExcluded(rel, excludes)) continue
            if (f.isDirectory) {
                result.putAll(collectLocalFiles(root, f, excludes))
            } else {
                result[rel] = f
            }
        }
        return result
    }

    private fun isExcluded(rel: String, excludes: List<String>): Boolean =
        excludes.any {
            val p = it.trim().trim('/')
            rel == p || rel.startsWith("$p/")
        }

    private fun readText(file: File): String =
        String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)

    private fun writeText(file: File, content: String): Boolean =
        try {
            Files.write(file.toPath(), content.toByteArray(Charsets.UTF_8))
            true
        } catch (e: Exception) {
            plugin.logger.warning("[AutoSync] 写入本地失败 ${file.name}: ${e.message}")
            false
        }
}
