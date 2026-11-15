package com.example.fcldsh

import android.content.Context
import com.DSHRunner
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * SkillHub 市场客户端与安装器 —— 移植自参考实现 `dsh-skill-market` 的宿主侧逻辑。
 *
 * ## 为什么可以直接调 `api.skillhub.cn`
 *
 * 参考实现里那半个宿主代码存在的首要理由是**绕开 CORS**：`api.skillhub.cn`
 * 不给浏览器回 `Access-Control-Allow-Origin`，页面里的 `fetch` 读不到。
 * 但我们是原生 Kotlin 发的 HTTP 请求，根本没有同源策略 ——
 * 因此不需要那个 `/skill-market/api` 代理转发，直接用官方接口即可。
 *
 * ## 用到的三个上游接口（已逐个实测）
 *
 * | 用途 | 请求 |
 * |---|---|
 * | 列表 / 搜索 | `GET /api/skills?page=&pageSize=&sortBy=&order=&keyword=` |
 * | 文件清单 | `GET /api/v1/skills/{slug}/files?namespace={handle}&version={ver}` |
 * | 单个文件 | `GET /api/v1/skills/{slug}/file?namespace={handle}&version={ver}&path={path}` |
 *
 * **没有**「列全部类目」的接口（`/api/skills/categories`、`/api/categories` 都返回
 * 405），所以界面上不做类目筛选 —— 硬编一批类目键的话，键猜错就是一片空列表，
 * 那比没有这个功能更糟。
 *
 * ## 装到哪
 *
 * `$DSH_HOME/skills/<handle>--<slug>/`，与参考实现一致。用 `handle--slug` 而不是
 * 中文显示名，是因为技能名必须是 kebab-case；这个名字由发布者与 slug 决定，
 * 显示名改了也不会漂移。
 *
 * ## 安全边界（每一条都是给「社区站点的任意包」兜底）
 *
 * - 每个路径都校验：拒绝绝对路径、`..`、反斜杠、空段、超长；
 * - 单文件 8 MB、总量 64 MB、最多 2000 个文件；
 * - 上游给了 `sha256` 就校验，对不上直接判失败（宁可装不上，也不要装进半个坏文件）。
 */
internal object SkillHub {

    private const val BASE = "https://api.skillhub.cn"

    private const val UA = "fcl-dsh"

    /** 单文件上限 8 MB。 */
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 整包上限 64 MB。 */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** 文件数上限 2000。 */
    private const val MAX_FILES = 2000

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    // ────────────────────────── 数据 ──────────────────────────

    /** 排序方式。`api` 是上游的参数值。 */
    enum class Sort(val api: String, val label: String) {
        SCORE("score", "综合评分"),
        DOWNLOADS("downloads", "下载量")
    }

    /**
     * 列表里的一个技能。
     *
     * 只保留界面与安装真正用得到的字段 —— 上游一条记录有三十多个字段，
     * 全留除了占内存没有意义。
     */
    data class Skill(
        /** 发布者 handle，如 `ake`。安装时作为 `namespace` 参数。 */
        val handle: String,
        /** 上游 slug，如 `dev-god`。 */
        val slug: String,
        /** 中文显示名，如 `编程大神.Skill`。 */
        val name: String,
        val description: String,
        val downloads: Long,
        val stars: Long,
        val category: String,
        val version: String,
        val publisher: String,
        val publisherVerified: Boolean,
        val homepage: String,
        /** 上游声明「需要 API Key」时为 true，界面上标出来。 */
        val needsApiKey: Boolean
    ) {
        /** 落盘目录名，与参考实现一致：`handle--slug`。 */
        val installName: String get() = "$handle--$slug"

        /** 唯一标识，用于界面上的「正在安装哪一项」。 */
        val key: String get() = installName
    }

    /** 一页结果。 */
    data class Page(val skills: List<Skill>, val total: Int)

    // ────────────────────────── 列表 ──────────────────────────

    /**
     * 拉一页技能。**阻塞**，只能在后台线程调用。
     *
     * @param keyword 关键词；空串表示不筛
     * @param sort    排序方式
     * @param page    1 起
     */
    fun list(keyword: String, sort: Sort, page: Int, pageSize: Int): Page {
        val q = StringBuilder("$BASE/api/skills")
            .append("?page=").append(page.coerceAtLeast(1))
            .append("&pageSize=").append(pageSize.coerceIn(1, 100))
            .append("&sortBy=").append(sort.api)
            .append("&order=desc")
        if (keyword.isNotBlank()) {
            q.append("&keyword=").append(URLEncoder.encode(keyword.trim(), "UTF-8"))
        }

        val text = getText(q.toString())
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw IllegalStateException("返回的不是 JSON")
        // 上游用 `code: 0` 表示成功
        val code = root.optInt("code", -1)
        if (code != 0) {
            throw IllegalStateException(
                root.optString("message").ifBlank { "上游返回 code=$code" }
            )
        }
        val data = root.optJSONObject("data")
            ?: return Page(emptyList(), 0)
        val arr = data.optJSONArray("skills") ?: return Page(emptyList(), 0)
        val total = data.optInt("total", arr.length())

        val out = ArrayList<Skill>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ns = o.optJSONObject("namespace")
            val handle = ns?.optString("handle").orEmpty().ifBlank {
                // 兜底：canonicalName 形如 `@ake/dev-god`，取 @ 之后那段
                ns?.optString("canonicalName").orEmpty().removePrefix("@")
                    .substringBefore('/')
            }
            val slug = o.optString("slug")
            if (handle.isBlank() || slug.isBlank()) continue
            val pub = o.optJSONObject("publisher")
            out.add(
                Skill(
                    handle = handle,
                    slug = slug,
                    name = o.optString("name").ifBlank { slug },
                    // 优先中文描述；没有就退回原始描述
                    description = o.optString("description_zh")
                        .ifBlank { o.optString("description") },
                    downloads = o.optLong("downloads", 0L),
                    stars = o.optLong("stars", 0L),
                    category = o.optString("category"),
                    version = o.optString("version"),
                    publisher = pub?.optString("name").orEmpty(),
                    publisherVerified = pub?.optBoolean("verified", false) ?: false,
                    homepage = o.optString("homepage"),
                    needsApiKey = o.optJSONObject("labels")
                        ?.optString("requires_api_key") == "true"
                )
            )
        }
        return Page(out, total)
    }

    // ────────────────────────── 安装 ──────────────────────────

    /**
     * 把一个技能装到 `$DSH_HOME/skills/<handle>--<slug>/`。**阻塞**，后台线程调用。
     *
     * @param onLog 与运行日志同一条通道
     * @return 写出的文件数；负数表示失败
     */
    fun install(context: Context, skill: Skill, onLog: (String) -> Unit): Int {
        val root = File(File(DSHRunner.dshHome(context), "skills"), skill.installName)

        // ① 拿文件清单
        val files = try {
            listFiles(skill)
        } catch (e: Exception) {
            onLog("❌ 「${skill.name}」取文件清单失败: ${e.message ?: e.javaClass.simpleName}")
            return -1
        }
        if (files.isEmpty()) {
            onLog("❌ 「${skill.name}」的上游清单是空的")
            return -1
        }
        if (files.size > MAX_FILES) {
            onLog("❌ 「${skill.name}」文件数 ${files.size} 超过上限 $MAX_FILES")
            return -1
        }
        val planned = files.sumOf { it.size }
        if (planned > MAX_TOTAL_BYTES) {
            onLog("❌ 「${skill.name}」体积 $planned 超过上限 $MAX_TOTAL_BYTES")
            return -1
        }

        // ② 重装：先清空旧目录（软链只删链接，不顺着删外面）
        if (com.isLink(root)) {
            runCatching { root.delete() }
        } else if (root.isDirectory) {
            runCatching { root.deleteRecursively() }
        }
        if (!root.mkdirs() && !root.isDirectory) {
            onLog("❌ 无法创建技能目录: ${root.absolutePath}")
            return -1
        }

        // ③ 逐文件下载并写入
        var done = 0
        var total = 0L
        for (f in files) {
            val dest = resolveInside(root, f.path)
            if (dest == null) {
                onLog("⚠️ 跳过可疑路径: ${f.path}")
                continue
            }
            val bytes = try {
                getBytes(fileUrl(skill, f.path), MAX_FILE_BYTES)
            } catch (e: Exception) {
                onLog("❌ 下载 ${f.path} 失败: ${e.message ?: e.javaClass.simpleName}")
                // 一个文件失败就整体失败：半个技能装进去与没装一样不能用，
                // 而「看起来装上了、调用时才报缺文件」更难查。
                return -1
            }
            // 上游给了校验和就核对；对不上说明内容坏了，不要落盘
            if (f.sha256.isNotBlank()) {
                val actual = sha256Hex(bytes)
                if (!actual.equals(f.sha256, ignoreCase = true)) {
                    onLog("❌ ${f.path} 校验和不匹配（上游 ${f.sha256.take(12)}… 实得 ${actual.take(12)}…）")
                    return -1
                }
            }
            val ok = runCatching {
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                true
            }.getOrDefault(false)
            if (!ok) {
                onLog("❌ 写入 ${f.path} 失败")
                return -1
            }
            done++
            total += bytes.size
        }

        onLog("✅ 已安装技能「${skill.name}」：$done 个文件，${humanSizeText(total)}")
        onLog("ℹ️ 新装的技能在**新会话**里才能调用（技能清单在会话开始时就已确定）")
        // 兼容：让启动器自己的技能扫描也立刻能看到（若有地方在读）
        DSHRunner.onLog("📁 ${root.absolutePath}")
        return done
    }

    /** 上游文件清单里的一项。 */
    private data class RemoteFile(val path: String, val sha256: String, val size: Long)

    private fun listFiles(skill: Skill): List<RemoteFile> {
        val url = buildString {
            append(BASE).append("/api/v1/skills/").append(enc(skill.slug)).append("/files")
            append("?namespace=").append(enc(skill.handle))
            if (skill.version.isNotBlank()) append("&version=").append(enc(skill.version))
        }
        val text = getText(url)
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw IllegalStateException("清单不是 JSON")
        val arr = root.optJSONArray("files") ?: return emptyList()
        val out = ArrayList<RemoteFile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optString("path")
            if (p.isBlank()) continue
            out.add(
                RemoteFile(
                    path = p,
                    sha256 = o.optString("sha256"),
                    size = o.optLong("size", 0L)
                )
            )
        }
        return out
    }

    private fun fileUrl(skill: Skill, path: String): String = buildString {
        append(BASE).append("/api/v1/skills/").append(enc(skill.slug)).append("/file")
        append("?namespace=").append(enc(skill.handle))
        if (skill.version.isNotBlank()) append("&version=").append(enc(skill.version))
        append("&path=").append(enc(path))
    }

    /**
     * 把上游给的文件路径解析成 [root] 内的目标文件，越界返回 null。
     *
     * 上游路径是外部输入，必须按「不可信」处理：绝对路径、`..`、反斜杠、
     * 空段、超长一律拒绝。
     */
    private fun resolveInside(root: File, rawPath: String): File? {
        if (rawPath.isBlank() || rawPath.length > 400) return null
        if (rawPath.startsWith("/") || rawPath.startsWith("\\")) return null
        if (rawPath.contains('\\') || rawPath.contains('\u0000')) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(rawPath)) return null
        val segments = rawPath.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        val rootPath = root.absolutePath.trimEnd(File.separatorChar)
        val target = File(rootPath + File.separator + segments.joinToString(File.separator))
        // 再核一次前缀：即便上面都放了行，也不让最终路径逃出技能目录
        if (!target.absolutePath.startsWith(rootPath + File.separator)) return null
        return target
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun humanSizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%d KB", bytes / 1024)
        else -> "$bytes B"
    }

    // ────────────────────────── HTTP ──────────────────────────

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, */*")
            instanceFollowRedirects = true
        }

    private fun getText(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 读一段字节，超过 [limit] 直接失败（而不是默默截断）。 */
    private fun getBytes(url: String, limit: Long): ByteArray {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            // ⚠️ 不能用 `conn.contentLengthLong`：它要求 API 24，而本项目 minSdk 是 23。
            // 直接读响应头字符串，任何 API 级别都能用。
            val declared = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (declared > limit) throw IllegalStateException("文件过大（$declared 字节）")
            val out = java.io.ByteArrayOutputStream(
                if (declared in 1..(1L shl 22)) declared.toInt() else 32 * 1024
            )
            conn.inputStream.use { input ->
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > limit) throw IllegalStateException("文件超过 $limit 字节")
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
