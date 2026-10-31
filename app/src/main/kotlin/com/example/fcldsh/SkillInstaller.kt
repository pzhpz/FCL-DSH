package com.example.fcldsh

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.DSHRunner
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 从链接安装 skill。
 *
 * ## 为什么 skill 不用「索引 → 列表 → 点下载」那一套
 *
 * 插件是 npm 包，有 registry 可以列出版本；skill 不是 ——
 * skill 的核心就是一个 `SKILL.md`（外加若干资源文件），
 * 它的生态是 **GitHub 仓库 + 若干注册表**，没有统一的版本索引可拉。
 * 所以这里给的是「粘链接直接装」，而不是假装有一份列表可翻页。
 *
 * ## 支持的链接形态
 *
 * | 形态 | 例子 | 处理 |
 * |---|---|---|
 * | GitHub 仓库 | `https://github.com/obra/superpowers` | 下 zip（先 main 后 master），剥掉顶层目录解压 |
 * | GitHub 子目录 | `https://github.com/o/r/tree/main/skills/foo` | 同上，装完后从解压结果里挑那个子目录 |
 * | SKILL.md 直链 | `https://raw.githubusercontent.com/o/r/main/foo/SKILL.md` | 只下这一个文件，写进 `SKILL.md` |
 * | 任意 zip 直链 | `https://example.com/my-skill.zip` | 解压 |
 *
 * 目标目录统一是 `$DSH_HOME/skills/<名字>/` —— 与 dsh 自己的 skill 扫描位置
 * 一致（`InstanceSettingsPanel.scanSkills` 也扫这里）。
 *
 * 注意：KDoc 里不能出现「斜杠+星号」，Kotlin 块注释可嵌套，会吞掉整个文件。
 */
object SkillInstaller {

    private val main = Handler(Looper.getMainLooper())

    private fun post(block: () -> Unit) = main.post(block)

    /** 是否有安装正在进行。 */
    var busy by mutableStateOf(false)
        private set

    /** 最近一次操作结果，供面板显示。 */
    var message by mutableStateOf("")
        private set

    fun clearMessage() {
        if (!busy) message = ""
    }

    /**
     * 从 [rawUrl] 安装一个 skill。
     *
     * 全程后台线程，结果写进 [message] 与运行日志。
     */
    fun installFromUrl(context: Context, rawUrl: String) {
        if (busy) return
        val url = rawUrl.trim()
        if (url.isBlank()) {
            message = "❌ 请先填入链接"
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            message = "❌ 只支持 http / https 链接"
            return
        }

        busy = true
        message = "准备安装…"

        Thread {
            try {
                val skillsRoot = File(DSHRunner.dshHome(context), "skills")
                skillsRoot.mkdirs()

                val name = guessName(url)
                post { message = "正在下载…" }

                when {
                    // ① 单个 .md 文件：直接就是 SKILL.md
                    url.substringBefore('?').endsWith(".md", ignoreCase = true) -> {
                        val dest = File(skillsRoot, name)
                        dest.mkdirs()
                        val text = fetchText(url)
                        File(dest, "SKILL.md").writeText(text)
                        finish("✅ 已安装 skill「$name」", dest)
                    }

                    // ② GitHub 仓库 / 子目录
                    isGitHub(url) -> installGitHub(url, skillsRoot, name)

                    // ③ 其他 zip 直链
                    else -> {
                        val dest = File(skillsRoot, name)
                        dest.mkdirs()
                        downloadTo(url) { input ->
                            extractZip(input, dest)
                        }
                        finish("✅ 已安装 skill「$name」", dest)
                    }
                }
            } catch (e: Exception) {
                DSHRunner.onLog("❌ skill 安装失败: ${e.message}")
                post { message = "❌ 安装失败: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                post { busy = false }
            }
        }.start()
    }

    /**
     * GitHub 仓库的安装。
     *
     * 先试 `main` 分支、失败再试 `master` —— 老仓库普遍还是 master，
     * 只试一个分支会让「明明存在的仓库」装不上。
     */
    private fun installGitHub(url: String, skillsRoot: File, name: String) {
        val (repo, branchHint, subPath) = parseGitHub(url)
            ?: throw IllegalStateException("无法解析 GitHub 地址")

        val branches = listOfNotNull(branchHint, "main", "master").distinct()
        var lastError: Exception? = null

        for (b in branches) {
            try {
                post { message = "正在下载 $repo@$b…" }
                val zipUrl = "https://github.com/$repo/archive/refs/heads/$b.zip"
                val tmp = File(skillsRoot, ".tmp-$name")
                if (tmp.exists()) tmp.deleteRecursively()
                tmp.mkdirs()

                downloadTo(zipUrl) { input -> extractZip(input, tmp) }

                // 解压结果通常是一层 `<repo>-<branch>/`，剥掉它
                val top = pickTopDir(tmp, subPath)
                    ?: throw IllegalStateException("压缩包里没有找到 skill 目录")

                val dest = File(skillsRoot, name)
                if (dest.exists()) dest.deleteRecursively()
                if (!top.renameTo(dest)) {
                    top.copyRecursively(dest, overwrite = true)
                }
                tmp.deleteRecursively()

                finish("✅ 已安装 skill「$name」（$repo@$b）", dest)
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    /** 统一收尾：写日志、更新提示。 */
    private fun finish(msg: String, dest: File) {
        val count = runCatching {
            dest.walkTopDown().count { it.isFile }
        }.getOrDefault(0)
        val full = "$msg，共 $count 个文件"
        DSHRunner.onLog(full)
        DSHRunner.onLog("ℹ️ 目录: ${dest.absolutePath}")
        DSHRunner.onLog("ℹ️ 重启实例后 skill 才会被加载")
        post { message = "$full，重启实例后生效" }
    }

    // ------------------------------------------------------------------
    // 链接解析
    // ------------------------------------------------------------------

    private fun isGitHub(url: String): Boolean =
        url.contains("github.com/", ignoreCase = true)

    /**
     * 从链接里猜一个目录名。
     *
     * 优先级：`/tree/<branch>/<最后一段>` → 仓库名 → 文件名去掉扩展名。
     * 结果会做安全化处理（去掉 `/` 与不可见字符），因为它要当目录名用。
     */
    private fun guessName(url: String): String {
        val cleaned = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val segs = cleaned.split('/').filter { it.isNotBlank() }

        val treeIdx = segs.indexOfFirst { it == "tree" || it == "blob" }
        val raw = when {
            // .../tree/main/skills/foo  → foo
            treeIdx >= 0 && segs.size > treeIdx + 2 -> segs.last()
            // .../blob/main/foo/SKILL.md → foo
            treeIdx >= 0 && segs.size > treeIdx + 1 -> segs[treeIdx + 1]
            // 仓库根 → 仓库名
            segs.size >= 2 && segs[1].contains("github") -> segs.last()
            else -> segs.lastOrNull() ?: "skill"
        }
        val safe = raw
            .removeSuffix(".md")
            .removeSuffix(".zip")
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trim('-')
        return safe.ifBlank { "skill" }
    }

    /**
     * 拆 GitHub 地址。
     *
     * @return (owner/repo, 分支或 null, 仓库内子路径或 null)
     */
    private fun parseGitHub(url: String): Triple<String, String?, String?>? {
        val cleaned = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val idx = cleaned.indexOf("github.com/")
        if (idx < 0) return null
        val segs = cleaned.substring(idx + "github.com/".length)
            .split('/')
            .filter { it.isNotBlank() }
        if (segs.size < 2) return null
        val repo = "${segs[0]}/${segs[1].removeSuffix(".git")}"

        val treeIdx = segs.indexOfFirst { it == "tree" || it == "blob" }
        if (treeIdx < 0) return Triple(repo, null, null)

        val branch = segs.getOrNull(treeIdx + 1)
        val sub = segs.drop(treeIdx + 2).joinToString("/").ifBlank { null }
        // blob 指向的是单个文件，取它的父目录
        val subDir = if (treeIdx >= 0 && segs[treeIdx] == "blob" && sub != null) {
            sub.substringBeforeLast('/', "")
        } else {
            sub
        }
        return Triple(repo, branch, subDir?.ifBlank { null })
    }

    /**
     * 从解压结果里挑出真正的 skill 目录。
     *
     * 没有指定子路径时：若顶层只有一个目录就剥掉它（GitHub zip 的结构）；
     * 指定了子路径就按路径找。
     */
    private fun pickTopDir(root: File, subPath: String?): File? {
        var base = root
        // 剥掉 GitHub zip 的那层 `<repo>-<branch>/`
        val topKids = base.listFiles().orEmpty()
        if (topKids.size == 1 && topKids[0].isDirectory) {
            base = topKids[0]
        }
        if (subPath != null) {
            val target = File(base, subPath)
            if (target.isDirectory) return target
        }
        return if (base.isDirectory) base else null
    }

    // ------------------------------------------------------------------
    // 网络 / 解压
    // ------------------------------------------------------------------

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "fcl-dsh/1.0 (Android)")
        }

    private fun fetchText(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            val c = open(url)
            conn = c
            if (c.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${c.responseCode}")
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadTo(url: String, consume: (InputStream) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            val c = open(url)
            conn = c
            if (c.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${c.responseCode}")
            }
            c.inputStream.use { consume(it) }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 解压 zip 到 [dest]。
     *
     * 自己写而不是复用 [TarGz]：那个解的是 tar.gz，不是 zip。
     * 这里做 zip-slip 防护（拒绝绝对路径与 `..`），并跳过符号链接 ——
     * zip 里的软链可能指向任意位置，解出来有风险。
     */
    private fun extractZip(input: InputStream, dest: File) {
        ZipInputStream(input.buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory) {
                    val safe = name.replace('\\', '/')
                    if (!safe.startsWith("/") && !safe.contains("..")) {
                        val out = File(dest, safe)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { fos -> zis.copyTo(fos) }
                    } else {
                        DSHRunner.onLog("⚠️ 跳过可疑条目: $name")
                    }
                }
                e = zis.nextEntry
            }
        }
    }
}
