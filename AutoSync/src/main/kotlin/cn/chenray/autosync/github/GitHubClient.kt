package cn.chenray.autosync.github

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * GitHub REST API 客户端（仅用 Contents API，免 git/curl，纯 Java HTTP）。
 */
class GitHubClient(
    private val owner: String,
    private val repo: String,
    private val token: String,
    private val branch: String,
) {
    companion object {
        private const val API_BASE = "https://api.github.com"
        private const val USER_AGENT = "AutoSync-Minecraft-Plugin"
    }

    /**
     * 列出远程目录下所有文件（递归），返回 {相对路径 -> sha}。
     */
    fun listRemoteTree(folder: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        // 用 git trees API 一次性拿整棵树的 sha，比逐个 contents 高效
        val ref = getBranchHeadSha() ?: return result
        val url = "$API_BASE/repos/$owner/$repo/git/trees/$ref?recursive=1"
        val resp = getJson(url)
        val tree = resp?.getAsJsonArray("tree") ?: return result
        val prefix = folder.trimEnd('/') + "/"
        for (el in tree) {
            val obj = el.asJsonObject
            if (obj.get("type").asString != "blob") continue
            val path = obj.get("path").asString
            if (path.startsWith(prefix)) {
                result[path.removePrefix(prefix)] = obj.get("sha").asString
            }
        }
        return result
    }

    /**
     * 读取远程文件内容（base64 解码后返回原始字符串）。
     */
    fun getFileContent(path: String): String? {
        val url = "$API_BASE/repos/$owner/$repo/contents/${encodePath(path)}?ref=$branch"
        val resp = getJson(url)
        val b64 = resp?.get("content")?.asString ?: return null
        return String(Base64.getMimeDecoder().decode(b64), StandardCharsets.UTF_8)
    }

    /**
     * 创建或更新远程文件。传 sha 则更新，不传则新建。
     */
    fun putFile(path: String, content: String, sha: String?): Boolean {
        val body = JsonObject().apply {
            addProperty("message", "[AutoSync] update $path")
            addProperty("content", Base64.getEncoder().encodeToString(content.toByteArray(StandardCharsets.UTF_8)))
            addProperty("branch", branch)
            if (sha != null) addProperty("sha", sha)
        }
        val url = "$API_BASE/repos/$owner/$repo/contents/${encodePath(path)}"
        val resp = sendJson("PUT", url, body.toString())
        return resp != null && (resp.has("content") || resp.has("commit"))
    }

    /**
     * 上传二进制文件（.mcpr 录像等）。
     * GitHub Contents API 要求 base64 编码，单文件最大 100MB。
     */
    fun putFileBytes(path: String, bytes: ByteArray, sha: String?): Boolean {
        val body = JsonObject().apply {
            addProperty("message", "[AutoSync] upload $path")
            addProperty("content", Base64.getEncoder().encodeToString(bytes))
            addProperty("branch", branch)
            if (sha != null) addProperty("sha", sha)
        }
        val url = "$API_BASE/repos/$owner/$repo/contents/${encodePath(path)}"
        val resp = sendJson("PUT", url, body.toString())
        return resp != null && (resp.has("content") || resp.has("commit"))
    }

    /**
     * 流式上传本地文件（内存友好，避免大文件整读进堆）。
     * 流程：文件 → base64（流式）→ JSON 请求体 → PUT。
     * 相比 putFileBytes 不会同时持有 [原始bytes + base64字符串 + JSON串] 三份大内存。
     */
    fun putFileStream(path: String, file: File, sha: String?): Boolean {
        val url = "$API_BASE/repos/$owner/$repo/contents/${encodePath(path)}"
        val conn = openConn(url)
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setChunkedStreamingMode(8192) // 流式发送，避免整包缓冲

            // 手动构造 JSON：message / content / branch / sha
            val body = StringBuilder(4096).apply {
                append("{\"message\":\"[AutoSync] upload ")
                append(escapeJson(path))
                append("\",\"content\":\"")
            }
            conn.outputStream.use { os ->
                // 先写 JSON 前半段
                os.write(body.toString().toByteArray(StandardCharsets.UTF_8))

                // 流式 base64 编码文件内容，边读边写
                val encoder = Base64.getEncoder()
                var buffer = ByteArray(64 * 1024)
                file.inputStream().use { ins ->
                    var read: Int
                    while (ins.read(buffer).also { read = it } != -1) {
                        if (read < buffer.size) buffer = buffer.copyOf(read)
                        val encoded = encoder.encode(buffer)
                        os.write(encoded)
                    }
                }

                // 写 JSON 后半段
                val tail = StringBuilder(256).apply {
                    append("\",\"branch\":\"")
                    append(escapeJson(branch))
                    append("\"")
                    if (sha != null) {
                        append(",\"sha\":\"")
                        append(escapeJson(sha))
                        append("\"")
                    }
                    append("}")
                }
                os.write(tail.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: ""
            } else {
                conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: ""
            }
            if (code !in 200..299) {
                System.err.println("[AutoSync] GitHub API $code: $text")
                return false
            }
            val resp = if (text.isBlank()) JsonObject() else JsonParser.parseString(text).asJsonObject
            return resp.has("content") || resp.has("commit")
        } finally {
            conn.disconnect()
        }
    }

    /** 转义 JSON 字符串（用于手动构造请求体） */
    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /**
     * 删除远程文件。
     */
    fun deleteFile(path: String, sha: String): Boolean {
        val body = JsonObject().apply {
            addProperty("message", "[AutoSync] delete $path")
            addProperty("sha", sha)
            addProperty("branch", branch)
        }
        val url = "$API_BASE/repos/$owner/$repo/contents/${encodePath(path)}"
        val resp = sendJson("DELETE", url, body.toString())
        return resp != null && resp.has("commit")
    }

    // ---------- 内部工具 ----------

    private fun getBranchHeadSha(): String? {
        val url = "$API_BASE/repos/$owner/$repo/git/ref/heads/$branch"
        val resp = getJson(url)
        return resp?.getAsJsonObject("object")?.get("sha")?.asString
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    private fun getJson(url: String): JsonObject? {
        val conn = openConn(url)
        try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) return null
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            return JsonParser.parseString(text).asJsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun sendJson(method: String, url: String, body: String): JsonObject? {
        val conn = openConn(url)
        try {
            conn.requestMethod = method
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            } else {
                conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: ""
            }
            if (code !in 200..299) {
                System.err.println("[AutoSync] GitHub API $code: $text")
                return null
            }
            return if (text.isBlank()) JsonObject() else JsonParser.parseString(text).asJsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun openConn(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }
}
