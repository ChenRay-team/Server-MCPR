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
