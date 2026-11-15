package com.example.fcldsh

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.DSHRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 插件与会话的单独导入 / 导出。
 *
 * ## 为什么不复用「导出整合包」
 *
 * [EngineExporter] 打的是**整套环境**（引擎 + 数据），适合作备份或迁移；
 * 但日常需求往往是「把这台机器上的两个插件搬到另一台」或者「只备份会话」，
 * 打一整个引擎既慢又几十上百 MB。这里因此提供两条独立的、只含数据的通道。
 *
 * ## 包结构
 *
 * 两种包都以 `manifest.json` 开头，用 `kind` 区分，导入时据它判断包类型：
 *
 * ```
 * manifest.json                                   ← kind / 版本 / 插件清单
 * node_modules/<包名>/…                           ← 插件包（含依赖闭包）
 * ```
 *
 * ```
 * manifest.json
 * sessions/<相对路径>…                            ← 会话事件流
 * storages/session_projcache/sessions/<id>.json   ← 会话标题缓存
 * ```
 *
 * ## 导入是「直接写入 dsh」
 *
 * 插件导入不只是把文件解到 `node_modules`：那样 dsh 根本不会加载它。
 * 必须同时登记到 `profiles/web/package.json` 的 `dependencies` 与
 * `dsh.profile.bundles`（后者才是引擎实际加载的清单）。两处都写，插件
 * 重启实例后即可用。写之前会把原文件备份成 `package.json.bak-import`。
 *
 * 会话导入直接落进 `$DSH_HOME/sessions/` 与投影缓存目录，**已存在的文件跳过**
 * （按会话 id 天然幂等，重复导入不会覆盖正在使用的会话）。
 *
 * ## 依赖闭包
 *
 * 插件要能跑，它依赖的第三方包必须在同一个 `node_modules` 里。导出时从插件
 * 出发递归读各包 `package.json` 的 `dependencies`，把整条链一起打进去 ——
 * 只打插件本体的话，导入方会遇到 `Cannot find package 'hosted-git-info'`
 * 那类运行时失败。
 *
 * 注意：KDoc 里不能出现「斜杠+星号」，Kotlin 块注释可嵌套，会吞掉整个文件。
 */
object PluginSessionTransfer {

    private const val MANIFEST = "manifest.json"
    private const val KIND_PLUGINS = "plugins"
    private const val KIND_SESSIONS = "sessions"

    private const val NODE_MODULES = "node_modules/"
    private const val SESSIONS = "sessions/"
    private const val PROJ_CACHE = "storages/session_projcache/sessions/"

    /** skill 包：一个 skill 一个目录，条目名 `<目录名>/…`。 */
    private const val SKILLS = "skills/"

    /**
     * MCP 包：按**相对 `DSH_HOME` 的原始路径**存，而不是拍平成文件名。
     *
     * 因为 MCP 配置散落在好几处（`mcp.json`、`profiles/web/local-plugins/xxx/
     * cordis.patch.yml` …），导入时必须写回原位置才生效；
     * 拍平后就得靠猜位置，猜错等于没导。
     */
    private const val MCP = "mcp/"

    /** 单个包最多打包多少个文件，防止误把巨大的目录塞进包里。 */
    private const val MAX_FILES = 20000

    // ── 供 UI 订阅的状态 ────────────────────────────────────────────

    /** 是否有导入导出正在进行（面板据此禁用按钮）。 */
    var busy by mutableStateOf(false)
        private set

    /** 操作类别：各页共用同一份状态，靠它区分结果属于哪一页。 */
    enum class Kind { PLUGINS, SESSIONS, SKILLS, MCP }

    /**
     * 最近一次操作的结果。
     *
     * 必须和 [lastResultKind] 配对使用：两页共享这一个字段，若不区分，
     * 导出插件后切到会话页会看到「已导出 2 个插件」—— 用户会以为串台了。
     */
    var lastResult by mutableStateOf("")
        private set

    /** [lastResult] 属于哪一页；null 表示还没有任何操作。 */
    var lastResultKind by mutableStateOf<Kind?>(null)
        private set

    /** 当前正在执行的操作类别，由各入口在开始时设置，供 [done] 打标记。 */
    private var activeKind: Kind? = null

    fun clearLastResult() {
        lastResult = ""
        lastResultKind = null
    }

    // ==============================================================
    // 插件
    // ==============================================================

    /**
     * 导出当前实例的全部第三方插件（连同依赖闭包）。
     *
     * @return 给人看的结果说明，同时写进日志面板。
     */
    fun exportPlugins(context: Context, output: OutputStream): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.PLUGINS
        try {
            val profile = profileDir(context)
            val manifestFile = File(profile, "package.json")
            if (!manifestFile.isFile) {
                return done("❌ 找不到 profile 清单，无法确定要导出哪些插件")
            }
            val plugins = readBundles(manifestFile).filter { !isBuiltinBundle(it) }
            if (plugins.isEmpty()) {
                return done("当前实例没有第三方插件可导出")
            }

            val nodeModules = File(profile, "node_modules")
            val needed = closure(nodeModules, plugins)
            if (needed.isEmpty()) {
                return done("❌ 清单里有插件，但 node_modules 里找不到它们的目录")
            }

            var files = 0
            ZipOutputStream(BufferedOutputStream(output)).use { zos ->
                val meta = JSONObject().apply {
                    put("format", 1)
                    put("kind", KIND_PLUGINS)
                    put("createdAt", System.currentTimeMillis())
                    put("engineVersion", DSHRunner.currentVersion(context) ?: "")
                    put(
                        "plugins",
                        JSONArray().apply {
                            plugins.forEach { p ->
                                put(
                                    JSONObject().apply {
                                        put("name", p)
                                        put("version", readPackageVersion(File(nodeModules, p)))
                                    }
                                )
                            }
                        }
                    )
                }
                zos.putNextEntry(ZipEntry(MANIFEST))
                zos.write(meta.toString(2).toByteArray())
                zos.closeEntry()

                for (name in needed) {
                    val dir = File(nodeModules, name)
                    if (!dir.isDirectory) continue
                    files += writeTree(zos, dir, NODE_MODULES + name)
                }
            }
            val msg = "✅ 已导出 ${plugins.size} 个插件、$files 个文件（含依赖）"
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 插件导出失败: ${e.message}")
            return done("❌ 插件导出失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 导出**单个**插件（连同它的依赖闭包）。
     *
     * 与 [exportPlugins] 只差「取哪些根」：这里只取一个包名。依赖闭包照旧
     * 一起打 —— 只打插件本体的包在另一台机器上装回去必然 import 失败。
     *
     * @param name 包名（含 scope），即插件页那一行对应的 `PluginInfo.name`
     */
    fun exportPlugin(context: Context, output: OutputStream, name: String): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.PLUGINS
        try {
            val profile = profileDir(context)
            val nodeModules = File(profile, "node_modules")
            val dir = File(nodeModules, name)
            if (!dir.isDirectory) {
                return done("❌ 找不到插件目录: $name")
            }
            val needed = closure(nodeModules, listOf(name))
            var files = 0
            ZipOutputStream(BufferedOutputStream(output)).use { zos ->
                val meta = JSONObject().apply {
                    put("format", 1)
                    put("kind", KIND_PLUGINS)
                    put("createdAt", System.currentTimeMillis())
                    put("engineVersion", DSHRunner.currentVersion(context) ?: "")
                    put(
                        "plugins",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("name", name)
                                    put("version", readPackageVersion(dir))
                                }
                            )
                        }
                    )
                }
                zos.putNextEntry(ZipEntry(MANIFEST))
                zos.write(meta.toString(2).toByteArray())
                zos.closeEntry()

                for (pkg in needed) {
                    val d = File(nodeModules, pkg)
                    if (!d.isDirectory) continue
                    files += writeTree(zos, d, NODE_MODULES + pkg)
                }
            }
            val deps = needed.size - 1
            val msg = "✅ 已导出 $name（$files 个文件" +
                (if (deps > 0) "，含 $deps 个依赖" else "") + "）"
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 插件导出失败: ${e.message}")
            return done("❌ 插件导出失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 导入插件包，**直接写入 dsh**。
     *
     * 两个动作缺一不可：
     *  1. 把包内 `node_modules` 下的内容解到 profile 的 `node_modules`；
     *  2. 把插件登记进 `package.json` 的 `dependencies` 与 `dsh.profile.bundles`。
     *
     * 少了第 2 步，文件在磁盘上但引擎不会加载，表现为「导入成功却看不到插件」。
     */
    fun importPlugins(context: Context, input: InputStream): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.PLUGINS
        try {
            val profile = profileDir(context)
            val nodeModules = File(profile, "node_modules")
            if (!nodeModules.isDirectory && !nodeModules.mkdirs()) {
                return done("❌ 无法创建 node_modules 目录")
            }

            var version: String? = null
            var extracted = 0
            var declared = ArrayList<Pair<String, String>>(8)
            val firstEntries = ArrayList<String>(8)

            ZipInputStream(BufferedInputStream(input)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val name = e.name
                    if (name == MANIFEST) {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        val o = runCatching { JSONObject(text) }.getOrNull()
                        if (o != null) {
                            val kind = o.optString("kind")
                            if (kind.isNotBlank() && kind != KIND_PLUGINS) {
                                return done("❌ 这不是插件包（kind=$kind）")
                            }
                            version = o.optString("engineVersion").ifBlank { null }
                            val arr = o.optJSONArray("plugins")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val p = arr.optJSONObject(i) ?: continue
                                    val pkg = p.optString("name")
                                    if (pkg.isNotBlank()) {
                                        declared.add(pkg to p.optString("version"))
                                    }
                                }
                            }
                        }
                    } else if (name.startsWith(NODE_MODULES) && !e.isDirectory) {
                        val rel = name.substring(NODE_MODULES.length)
                        if (firstEntries.size < 8) firstEntries.add(rel)
                        val out = resolveInside(nodeModules, rel)
                        if (out == null) {
                            DSHRunner.onLog("⚠️ 跳过可疑条目: $name")
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { fos -> zis.copyTo(fos) }
                            extracted++
                        }
                    }
                    e = zis.nextEntry
                }
            }

            if (extracted == 0) {
                return done("❌ 包里没有可导入的插件文件")
            }
            if (declared.isEmpty()) {
                // 清单缺失时按磁盘上出现过的顶层包名兜底，至少让插件能被加载
                val guess = ArrayList<Pair<String, String>>(4)
                for (rel in firstEntries) {
                    val head = if (rel.startsWith("@")) {
                        rel.split('/').take(2).joinToString("/")
                    } else {
                        rel.substringBefore('/')
                    }
                    if (head.isBlank()) continue
                    if (guess.any { it.first == head }) continue
                    if (isBuiltinBundle(head)) continue
                    guess.add(head to readPackageVersion(File(nodeModules, head)))
                }
                declared = guess
            }

            val registered = registerPlugins(context, declared)
            val msg = buildString {
                append("✅ 已导入 ${declared.size} 个插件（$extracted 个文件）")
                if (registered > 0) append("，已登记 $registered 个")
                if (version != null) append(" · 来自 $version")
            }
            DSHRunner.onLog(msg)
            DSHRunner.onLog("ℹ️ 重启实例后插件才会加载")
            return done("$msg，重启实例后生效")
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 插件导入失败: ${e.message}")
            return done("❌ 插件导入失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 把插件登记进 profile 清单。
     *
     * ## 只改两处
     *
     * · `dependencies[name] = version` —— 让 dsh 认为这个包是已安装的；
     * · `dsh.profile.bundles` 追加 `name` —— 这是引擎**实际加载**的顺序清单。
     *
     * 写之前先备份原文件。解析失败（带注释的 JSONC、文件半写）直接放弃并报错，
     * 绝不半途覆盖一份读不懂的清单 —— 那会让整个 profile 起不来。
     *
     * `internal` 而非 `private`：[PluginDownloader] 装完插件后要复用同一份
     * 登记逻辑 —— 两处各写一份，迟早会有一边忘了改。
     *
     * @return 实际新登记的插件数（已在清单里的不重复计数）
     */
    internal fun registerPlugins(
        context: Context,
        plugins: List<Pair<String, String>>
    ): Int {
        if (plugins.isEmpty()) return 0
        val manifestFile = File(profileDir(context), "package.json")
        if (!manifestFile.isFile) {
            DSHRunner.onLog("⚠️ 没有 profile 清单，插件文件已就位但需要在 dsh 里手动启用")
            return 0
        }
        val raw = manifestFile.readText().removePrefix("\uFEFF")
        val o = runCatching { JSONObject(raw) }.getOrNull()
        if (o == null) {
            DSHRunner.onLog("❌ profile 清单无法解析，未做任何改动")
            return 0
        }
        // 备份：用户改坏了能自己还原，我们也留一条退路
        runCatching {
            File(manifestFile.parentFile, "package.json.bak-import").writeText(raw)
        }

        val deps = o.optJSONObject("dependencies")
            ?: JSONObject().also { o.put("dependencies", it) }
        val dsh = o.optJSONObject("dsh") ?: JSONObject().also { o.put("dsh", it) }
        val profileNode = dsh.optJSONObject("profile")
            ?: JSONObject().also { dsh.put("profile", it) }
        val arr = profileNode.optJSONArray("bundles")
            ?: JSONArray().also { profileNode.put("bundles", it) }

        val present = HashSet<String>(arr.length() * 2)
        for (i in 0 until arr.length()) present.add(arr.optString(i))

        var added = 0
        for ((name, ver) in plugins) {
            if (ver.isNotBlank()) deps.put(name, ver)
            if (present.add(name)) {
                arr.put(name)
                added++
            }
        }
        val written = runCatching {
            manifestFile.writeText(o.toString(2) + "\n")
            true
        }.getOrDefault(false)
        if (!written) {
            DSHRunner.onLog("❌ 写回 profile 清单失败，插件文件在但不会被加载")
            return 0
        }
        return added
    }

    /**
     * 从 profile 清单里摘掉若干插件（`dependencies` 与 `dsh.profile.bundles` 都删）。
     *
     * 与 [registerPlugins] 完全对称：那边往两处加，这边从两处删。
     * 只删目录不动清单的话，引擎启动时解析不到包会直接报错；
     * 两者必须成对，见 [deletePlugin]。
     *
     * @return 是否真的改写了清单
     */
    internal fun unregisterPlugins(context: Context, names: List<String>): Boolean {
        if (names.isEmpty()) return false
        val manifestFile = File(profileDir(context), "package.json")
        if (!manifestFile.isFile) return false
        val raw = manifestFile.readText().removePrefix("\uFEFF")
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        // 删除前备份：改坏了用户还能自己还原
        runCatching {
            File(manifestFile.parentFile, "package.json.bak-delete").writeText(raw)
        }

        o.optJSONObject("dependencies")?.let { deps ->
            names.forEach { deps.remove(it) }
        }

        val bundles = o.optJSONObject("dsh")
            ?.optJSONObject("profile")
            ?.optJSONArray("bundles")
        if (bundles != null) {
            // JSONArray 没有 remove，只能重建一份
            val kept = JSONArray()
            for (i in 0 until bundles.length()) {
                val n = bundles.optString(i)
                if (n.isBlank()) continue
                if (names.any { it == n }) continue
                kept.put(n)
            }
            o.optJSONObject("dsh")?.optJSONObject("profile")?.put("bundles", kept)
        }

        return runCatching {
            manifestFile.writeText(o.toString(2) + "\n")
            true
        }.getOrDefault(false)
    }

    /**
     * 删除一个已安装的插件：**目录与清单登记一起去掉**。
     *
     * ## 为什么必须成对
     *
     * · 只删目录：「profile.bundles」里还留着包名，引擎启动时解析不到会报错；
     * · 只删清单：文件还在磁盘上，引擎不再加载它，看起来像「删了没生效」。
     *
     * ## 为什么要防软链（这条最要紧）
     *
     * 插件的 `node_modules/<包名>` 可能是软链（某些插件管理器就是这么做的）。
     * 对它 `deleteRecursively()` 会顺着链接把**外部目录**里的内容一起删掉，
     * 而且是不可恢复的。所以链接只 `delete()` 链接本身，绝不递归进去。
     *
     * ## 运行中的引擎
     *
     * 引擎正在跑时删插件是安全的（文件已加载进内存，只是下次启动才会消失），
     * 但需要重启实例才真正生效 —— 返回值里会写明这一点。
     */
    fun deletePlugin(context: Context, pkg: String): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.PLUGINS
        try {
            val profile = profileDir(context)
            val dir = File(profile, "node_modules/$pkg")

            var removed: Boolean
            if (com.isLink(dir)) {
                // 只删链接本身，不跟进去
                removed = runCatching { dir.delete() }.getOrDefault(false)
            } else if (dir.isDirectory) {
                removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
            } else {
                removed = false
            }

            val unregistered = unregisterPlugins(context, listOf(pkg))

            if (!removed && !unregistered) {
                return done("❌ 找不到插件 $pkg，也没有可清理的清单登记")
            }
            val msg = buildString {
                append("✅ 已删除 $pkg")
                if (removed) append("（含目录）")
                if (unregistered) append("，已从 profile 清单摘除")
                append("，重启实例后生效")
            }
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 删除插件失败: ${e.message}")
            return done("❌ 删除插件失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    // ==============================================================
    // 会话
    // ==============================================================

    /**
     * 导出全部会话。
     *
     * 同时打两份数据：
     *  · `sessions/` —— 会话本体（事件流，开着对话才有内容）；
     *  · 投影缓存 —— 会话标题与活跃时间就在里面，少了它导回去全是 uuid，
     *    用户根本认不出哪个是哪个。
     */
    fun exportSessions(context: Context, output: OutputStream): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.SESSIONS
        try {
            val home = DSHRunner.dshHome(context)
            val sessionsDir = File(home, "sessions")
            val cacheDir = File(home, PROJ_CACHE.trimEnd('/'))
            if (!sessionsDir.isDirectory && !cacheDir.isDirectory) {
                return done("当前实例还没有会话记录可导出")
            }

            var files = 0
            ZipOutputStream(BufferedOutputStream(output)).use { zos ->
                val meta = JSONObject().apply {
                    put("format", 1)
                    put("kind", KIND_SESSIONS)
                    put("createdAt", System.currentTimeMillis())
                    put("engineVersion", DSHRunner.currentVersion(context) ?: "")
                }
                zos.putNextEntry(ZipEntry(MANIFEST))
                zos.write(meta.toString(2).toByteArray())
                zos.closeEntry()

                if (sessionsDir.isDirectory) {
                    files += writeTree(zos, sessionsDir, SESSIONS)
                }
                if (cacheDir.isDirectory) {
                    files += writeTree(zos, cacheDir, PROJ_CACHE)
                }
            }
            val msg = "✅ 已导出 $files 个会话文件"
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 会话导出失败: ${e.message}")
            return done("❌ 会话导出失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 导出**单个**会话。
     *
     * 会话身份用 [sessionKeyOf] 判定 —— 与列表同一套规则，否则会出现
     * 「列表里能选、导出却说找不到」。会话本体与它的标题缓存一起打包，
     * 否则导入回去只有一个 uuid，认不出是哪个。
     *
     * @param key [SessionTab] 列表里那一行的会话标识（目录名或 uuid）
     */
    fun exportSession(context: Context, output: OutputStream, key: String): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.SESSIONS
        try {
            val home = DSHRunner.dshHome(context)
            val sessionsDir = File(home, "sessions")
            val cacheDir = File(home, PROJ_CACHE.trimEnd('/'))
            // 与 scanSessions 同一规则：sessions/ 存在就用它当扫描根，
            // 否则退回数据根。两边不一致就会算出不同的会话 key。
            val scanFrom = if (sessionsDir.isDirectory) sessionsDir else home

            val picked = ArrayList<File>(4)
            if (scanFrom.isDirectory) {
                val stack = ArrayDeque<File>()
                stack.addLast(scanFrom)
                while (stack.isNotEmpty()) {
                    val dir = stack.removeLast()
                    val kids = runCatching { dir.listFiles() }.getOrNull() ?: continue
                    for (f in kids) {
                        if (com.isLink(f)) continue
                        if (f.isDirectory) {
                            stack.addLast(f)
                            continue
                        }
                        if (isNotSessionPayload(f.name)) continue
                        if (!f.name.contains("session", ignoreCase = true)) continue
                        if (sessionKeyOf(f, scanFrom) == key) picked.add(f)
                    }
                }
            }
            val cacheFile = projCacheFileFor(cacheDir, key)

            if (picked.isEmpty() && cacheFile == null) {
                return done("❌ 找不到会话: $key")
            }

            var files = 0
            ZipOutputStream(BufferedOutputStream(output)).use { zos ->
                val meta = JSONObject().apply {
                    put("format", 1)
                    put("kind", KIND_SESSIONS)
                    put("createdAt", System.currentTimeMillis())
                    put("engineVersion", DSHRunner.currentVersion(context) ?: "")
                    put("session", key)
                }
                zos.putNextEntry(ZipEntry(MANIFEST))
                zos.write(meta.toString(2).toByteArray())
                zos.closeEntry()

                val base = scanFrom.absolutePath
                for (f in picked) {
                    if (files >= MAX_FILES) break
                    val rel = if (f.absolutePath.startsWith(base + File.separator)) {
                        f.absolutePath.substring(base.length + 1)
                    } else {
                        f.name
                    }
                    if (writeFile(zos, f, SESSIONS + rel)) files++
                }
                if (cacheFile != null && writeFile(zos, cacheFile, PROJ_CACHE + cacheFile.name)) {
                    files++
                }
            }
            val msg = "✅ 已导出会话 $key（$files 个文件）"
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 会话导出失败: ${e.message}")
            return done("❌ 会话导出失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 导入会话，**直接写入 dsh**。
     *
     * 已存在的文件跳过：会话靠 id 区分，重复导入是幂等的；
     * 覆盖正在使用的会话反而可能把引擎写到一半的状态踩坏。
     */
    fun importSessions(context: Context, input: InputStream): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.SESSIONS
        try {
            val home = DSHRunner.dshHome(context)
            val sessionsDir = File(home, "sessions")
            val cacheDir = File(home, PROJ_CACHE.trimEnd('/'))
            sessionsDir.mkdirs()
            cacheDir.mkdirs()

            var written = 0
            var skipped = 0
            var touchedCache = false

            ZipInputStream(BufferedInputStream(input)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val name = e.name
                    if (name == MANIFEST) {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        val kind = runCatching {
                            JSONObject(text).optString("kind")
                        }.getOrNull()
                        if (!kind.isNullOrBlank() && kind != KIND_SESSIONS) {
                            return done("❌ 这不是会话包（kind=$kind）")
                        }
                    } else if (name.startsWith(SESSIONS) && !e.isDirectory) {
                        val rel = name.substring(SESSIONS.length)
                        val out = resolveInside(sessionsDir, rel)
                        if (out == null) {
                            DSHRunner.onLog("⚠️ 跳过可疑条目: $name")
                        } else if (out.isFile) {
                            skipped++
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { fos -> zis.copyTo(fos) }
                            written++
                        }
                    } else if (name.startsWith(PROJ_CACHE) && !e.isDirectory) {
                        val rel = name.substring(PROJ_CACHE.length)
                        val out = resolveInside(cacheDir, rel)
                        if (out == null) {
                            DSHRunner.onLog("⚠️ 跳过可疑条目: $name")
                        } else if (out.isFile) {
                            skipped++
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { fos -> zis.copyTo(fos) }
                            written++
                            touchedCache = true
                        }
                    }
                    e = zis.nextEntry
                }
            }

            if (written == 0) {
                return done(
                    if (skipped > 0) "会话都已存在，无需导入（跳过 $skipped 个文件）"
                    else "❌ 包里没有可导入的会话文件"
                )
            }
            val msg = buildString {
                append("✅ 已导入 $written 个会话文件")
                if (skipped > 0) append("，跳过 $skipped 个已存在")
                if (!touchedCache) append("（不含标题缓存，列表可能只显示编号）")
            }
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 会话导入失败: ${e.message}")
            return done("❌ 会话导入失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 删除一个会话：本体文件 + 投影缓存一起删。
     *
     * ## 身份判定必须与列表一致
     *
     * 复用 [sessionKeyOf]，和「会话列表」「单项导出」是同一个函数。
     * 三处各写一份的话，会出现「列表里能选、点删除却说找不到」。
     *
     * ## 为什么删完还要清空目录
     *
     * 会话可能是一个目录（`sessions/<id>/`）。逐文件删完会留下一个空目录，
     * 下次扫描仍会把它当成一个会话（文件数 0），表现为「删了还在」。
     * 所以最后把同名目录也清掉。
     *
     * ## 运行中的引擎
     *
     * 引擎正在跑时删会话文件不会崩，但 dsh 内存里仍持有该会话；
     * 重启实例后才彻底消失。返回值里写明这一点，避免用户以为没生效。
     */
    fun deleteSession(context: Context, key: String): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.SESSIONS
        try {
            val home = DSHRunner.dshHome(context)
            val sessionsDir = File(home, "sessions")
            val cacheDir = File(home, PROJ_CACHE.trimEnd('/'))
            // 与 scanSessions 同一规则：sessions/ 存在就用它当扫描根
            val scanFrom = if (sessionsDir.isDirectory) sessionsDir else home

            var deleted = 0
            if (scanFrom.isDirectory) {
                val stack = ArrayDeque<File>()
                stack.addLast(scanFrom)
                while (stack.isNotEmpty()) {
                    val dir = stack.removeLast()
                    val kids = runCatching { dir.listFiles() }.getOrNull() ?: continue
                    for (f in kids) {
                        if (com.isLink(f)) continue
                        if (f.isDirectory) {
                            stack.addLast(f)
                            continue
                        }
                        if (isNotSessionPayload(f.name)) continue
                        if (!f.name.contains("session", ignoreCase = true)) continue
                        if (sessionKeyOf(f, scanFrom) != key) continue
                        if (runCatching { f.delete() }.getOrDefault(false)) deleted++
                    }
                }

                // 清掉同名空目录（一个会话常以目录形式存在）
                val asDir = File(scanFrom, key)
                if (asDir.isDirectory && !com.isLink(asDir)) {
                    val left = runCatching { asDir.listFiles()?.size ?: 0 }.getOrDefault(0)
                    // 还有别的东西就只删空壳，不递归 —— 避免误删用户放的其它文件
                    if (left == 0) runCatching { asDir.delete() }
                }
            }

            // 投影缓存：少了它，别的会话的标题缓存不受影响
            projCacheFileFor(cacheDir, key)?.let { f ->
                if (runCatching { f.delete() }.getOrDefault(false)) deleted++
            }

            if (deleted == 0) {
                return done("❌ 找不到会话 $key 的文件")
            }
            val msg = "✅ 已删除会话 $key（$deleted 个文件），重启实例后彻底消失"
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 删除会话失败: ${e.message}")
            return done("❌ 删除会话失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    // ==============================================================
    // skill
    // ==============================================================

    /**
     * 导出全部 skill。
     *
     * skill 是**目录式**的（`$DSH_HOME/skills/<名字>/SKILL.md`），
     * 所以整棵目录一起打包，保持目录结构 —— 拍平会让导入方还原不出 skill。
     *
     * 同时扫 `profiles/<p>/skills` 与 `.claude/skills`，与 [scanSkills]
     * （UI 侧）的候选路径保持一致：能显示出来的就能导出。
     */
    fun exportSkills(context: Context, output: OutputStream): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.SKILLS
        try {
            val home = DSHRunner.dshHome(context)
            val bases = skillBases(home)

            var files = 0
            var dirs = 0
            ZipOutputStream(BufferedOutputStream(output)).use { zos ->
                val meta = JSONObject().apply {
                    put("format", 1)
                    put("kind", "skills")
                    put("createdAt", System.currentTimeMillis())
                    put("engineVersion", DSHRunner.currentVersion(context) ?: "")
                }
                zos.putNextEntry(ZipEntry(MANIFEST))
                zos.write(meta.toString(2).toByteArray())
                zos.closeEntry()

                for ((base, label) in bases) {
                    if (!base.isDirectory) continue
                    val kids = runCatching { base.listFiles() }.getOrNull() ?: continue
                    for (d in kids) {
                        if (!d.isDirectory) continue
                        if (d.name.startsWith(".")) continue
                        if (d.name == "node_modules") continue
                        dirs++
                        // 条目名带上 label，导入时能还原到同一个位置
                        files += writeTree(zos, d, "$label/${d.name}")
                    }
                }
            }
            if (dirs == 0) {
                return done("当前实例没有 skill 可导出")
            }
            val msg = "✅ 已导出 $dirs 个 skill（$files 个文件）"
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ skill 导出失败: ${e.message}")
            return done("❌ skill 导出失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 导出**单个** skill。
     *
     * @param id     skill 目录名
     * @param label  UI 侧记录的原始位置（`skills` / `profiles/web/skills` /
     *   `.claude/skills`），用于精确找回同一个目录，避免同名 skill
     *   在多处时打错那个。
     */
    fun exportSkill(context: Context, output: OutputStream, id: String, label: String): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.SKILLS
        try {
            val home = DSHRunner.dshHome(context)
            val dir = File(home, if (label.isBlank()) "skills/$id" else "$label/$id")
            if (!dir.isDirectory) {
                return done("❌ 找不到 skill 目录: $id")
            }
            var files = 0
            ZipOutputStream(BufferedOutputStream(output)).use { zos ->
                val meta = JSONObject().apply {
                    put("format", 1)
                    put("kind", "skills")
                    put("createdAt", System.currentTimeMillis())
                    put("engineVersion", DSHRunner.currentVersion(context) ?: "")
                }
                zos.putNextEntry(ZipEntry(MANIFEST))
                zos.write(meta.toString(2).toByteArray())
                zos.closeEntry()
                val prefix = if (label.isBlank()) "skills/$id" else "$label/$id"
                files += writeTree(zos, dir, prefix)
            }
            val msg = "✅ 已导出 skill $id（$files 个文件）"
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ skill 导出失败: ${e.message}")
            return done("❌ skill 导出失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 导入 skill 包，**直接写入 dsh**。
     *
     * 条目名里带着原始的相对位置（`skills/xxx`、`profiles/web/skills/xxx`），
     * 所以直接按条目名落到 `$DSH_HOME` 下即可 —— 不需要再猜该放哪。
     *
     * 已存在的文件跳过（与导入会话同一策略）：skill 目录里可能有用户
     * 改过的 SKILL.md，覆盖会丢改动。
     */
    fun importSkills(context: Context, input: InputStream): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.SKILLS
        try {
            val home = DSHRunner.dshHome(context)
            var written = 0
            var skipped = 0

            ZipInputStream(BufferedInputStream(input)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val name = e.name
                    if (name == MANIFEST) {
                        val kind = runCatching {
                            JSONObject(zis.readBytes().toString(Charsets.UTF_8)).optString("kind")
                        }.getOrNull()
                        if (!kind.isNullOrBlank() && kind != "skills") {
                            return done("❌ 这不是 skill 包（kind=$kind）")
                        }
                    } else if (!e.isDirectory) {
                        // 只接受落在允许的几处根目录下的条目
                        if (skillRootOf(name) == null) {
                            DSHRunner.onLog("⚠️ 跳过不在 skill 目录下的条目: $name")
                        } else {
                            val out = resolveInside(home, name)
                            if (out == null) {
                                DSHRunner.onLog("⚠️ 跳过可疑条目: $name")
                            } else if (out.isFile) {
                                skipped++
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { fos -> zis.copyTo(fos) }
                                written++
                            }
                        }
                    }
                    e = zis.nextEntry
                }
            }

            if (written == 0) {
                return done(
                    if (skipped > 0) "skill 都已存在，无需导入（跳过 $skipped 个文件）"
                    else "❌ 包里没有可导入的 skill 文件"
                )
            }
            val msg = buildString {
                append("✅ 已导入 $written 个 skill 文件")
                if (skipped > 0) append("，跳过 $skipped 个已存在")
            }
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ skill 导入失败: ${e.message}")
            return done("❌ skill 导入失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /** skill 的几条根目录（与 UI 侧扫描保持一致），返回「目录 → 相对标签」。 */
    internal fun skillBases(home: File): List<Pair<File, String>> = listOf(
        File(home, "skills") to "skills",
        File(home, ".claude/skills") to ".claude/skills"
    ) + runCatching { File(home, "profiles").listFiles() }
        .getOrNull()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.map { File(it, "skills") to "profiles/${it.name}/skills" }
        .orEmpty()

    /**
     * 条目名是否落在某条 skill 根目录下；是则返回该根的前缀。
     *
     * ⚠️ 必须是**块体**函数：早先写成表达式体 + `run { return ... }`，
     * 而 Kotlin 禁止在表达式体函数的 lambda 里 `return` —— 那不是语义错误
     * 而是解析失败，会把整个 object 的花括号配对搞乱，导致后面所有私有成员
     * 全部「未解析」。
     */
    private fun skillRootOf(entry: String): String? {
        for (root in listOf("skills/", ".claude/skills/")) {
            if (entry.startsWith(root)) return root
        }
        // profiles/<p>/skills/<name>/… 这种四段前缀
        if (entry.startsWith("profiles/")) {
            val parts = entry.split('/')
            if (parts.size >= 4 && parts[2] == "skills" && !parts[1].startsWith(".")) {
                return "profiles/${parts[1]}/skills/"
            }
        }
        return null
    }

    // ==============================================================
    // MCP
    // ==============================================================

    /**
     * 导出全部 MCP 配置。
     *
     * ## 为什么按「相对 DSH_HOME 的原始路径」存
     *
     * MCP 配置散落在好几处，而且**不同形态差别很大**：
     *
     * · `$DSH_HOME/mcp.json`（经典写法，`mcpServers` 对象）
     * · `$DSH_HOME/profiles/web/local-plugins/<包>/cordis.patch.yml`
     *   （bundle 形式：插入 `@deepseek-ai/dsh-mcp-client` + `serverName`）
     *
     * 后者常伴随同目录的 `mcp-server.mjs`、`launch-mcp.sh` ——
     * **只导出 yml 而不导出脚本，导到别的机器上就是个空壳**。
     * 所以整棵 local-plugins 目录一起打，并且保留原始路径。
     */
    fun exportMcp(context: Context, output: OutputStream): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.MCP
        try {
            val home = DSHRunner.dshHome(context)
            val filesToPack = collectMcpFiles(home)
            if (filesToPack.isEmpty()) {
                return done("当前实例没有 MCP 配置可导出")
            }

            var files = 0
            ZipOutputStream(BufferedOutputStream(output)).use { zos ->
                val meta = JSONObject().apply {
                    put("format", 1)
                    put("kind", "mcp")
                    put("createdAt", System.currentTimeMillis())
                    put("engineVersion", DSHRunner.currentVersion(context) ?: "")
                }
                zos.putNextEntry(ZipEntry(MANIFEST))
                zos.write(meta.toString(2).toByteArray())
                zos.closeEntry()

                for (f in filesToPack) {
                    if (files >= MAX_FILES) break
                    val rel = relTo(home, f) ?: continue
                    if (writeFile(zos, f, MCP + rel)) files++
                }
            }
            val msg = "✅ 已导出 $files 个 MCP 相关文件"
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ MCP 导出失败: ${e.message}")
            return done("❌ MCP 导出失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 导出**单个** MCP 配置。
     *
     * @param rel 相对 `DSH_HOME` 的路径。
     *
     * ⚠️ UI 侧的 MCP 扫描（`McpConfig` / `scanMcpConfigs`）已在 1.4.x 删除 ——
     * 「mcp 管理」改成与实例无关的配置便签（[ConfigClipboard]）后，界面不再
     * 列实例里的配置文件。这两个导出入口因此**当前没有调用点**，
     * 保留只是为了以后接「从实例里挑一个配置导出」。不要再往它们上面加新功能。
     *
     * 若这个配置在 `local-plugins/<包>/` 里，会把**同目录下的所有文件**
     * 一起打包（`cordis.patch.yml` + `mcp-server.mjs` + `launch-mcp.sh`）——
     * 只打 yml 等于导出一个空壳。
     */
    fun exportMcpConfig(context: Context, output: OutputStream, rel: String): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.MCP
        try {
            val home = DSHRunner.dshHome(context)
            val f = File(home, rel)
            if (!f.isFile) return done("❌ 找不到 MCP 配置: $rel")

            val toPack = ArrayList<File>(8)
            // local-plugins/<包>/ 里的配置：整个包目录一起打
            val idx = rel.indexOf("local-plugins/")
            if (idx >= 0) {
                val after = rel.substring(idx + "local-plugins/".length)
                val pkg = after.substringBefore('/')
                if (pkg.isNotBlank()) {
                    val dir = File(home, "profiles/web/local-plugins/$pkg")
                    if (dir.isDirectory) {
                        val stack = ArrayDeque<File>()
                        stack.addLast(dir)
                        while (stack.isNotEmpty()) {
                            val cur = stack.removeLast()
                            val kids = runCatching { cur.listFiles() }.getOrNull() ?: continue
                            for (k in kids) {
                                if (com.isLink(k)) continue
                                if (k.isDirectory) stack.addLast(k) else toPack.add(k)
                            }
                        }
                    }
                }
            }
            if (toPack.isEmpty()) toPack.add(f)

            var files = 0
            ZipOutputStream(BufferedOutputStream(output)).use { zos ->
                val meta = JSONObject().apply {
                    put("format", 1)
                    put("kind", "mcp")
                    put("createdAt", System.currentTimeMillis())
                    put("engineVersion", DSHRunner.currentVersion(context) ?: "")
                }
                zos.putNextEntry(ZipEntry(MANIFEST))
                zos.write(meta.toString(2).toByteArray())
                zos.closeEntry()
                for (x in toPack) {
                    if (files >= MAX_FILES) break
                    val r = relTo(home, x) ?: continue
                    if (writeFile(zos, x, MCP + r)) files++
                }
            }
            val msg = "✅ 已导出 $rel（$files 个文件）"
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ MCP 导出失败: ${e.message}")
            return done("❌ MCP 导出失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 导入 MCP 包，**直接写入 dsh**。
     *
     * 条目名是相对 `DSH_HOME` 的原始路径，直接落盘即可还原。
     * 已存在的文件跳过 —— `local-plugins` 里可能是用户改过的配置。
     */
    fun importMcp(context: Context, input: InputStream): String {
        if (busy) return "已有导入导出任务在进行"
        busy = true
        activeKind = Kind.MCP
        try {
            val home = DSHRunner.dshHome(context)
            var written = 0
            var skipped = 0

            ZipInputStream(BufferedInputStream(input)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val name = e.name
                    if (name == MANIFEST) {
                        val kind = runCatching {
                            JSONObject(zis.readBytes().toString(Charsets.UTF_8)).optString("kind")
                        }.getOrNull()
                        if (!kind.isNullOrBlank() && kind != "mcp") {
                            return done("❌ 这不是 MCP 包（kind=$kind）")
                        }
                    } else if (name.startsWith(MCP) && !e.isDirectory) {
                        val rel = name.substring(MCP.length)
                        val out = resolveInside(home, rel)
                        if (out == null) {
                            DSHRunner.onLog("⚠️ 跳过可疑条目: $name")
                        } else if (out.isFile) {
                            skipped++
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { fos -> zis.copyTo(fos) }
                            written++
                        }
                    }
                    e = zis.nextEntry
                }
            }

            if (written == 0) {
                return done(
                    if (skipped > 0) "MCP 配置都已存在，无需导入（跳过 $skipped 个文件）"
                    else "❌ 包里没有可导入的 MCP 文件"
                )
            }
            val msg = buildString {
                append("✅ 已导入 $written 个 MCP 文件")
                if (skipped > 0) append("，跳过 $skipped 个已存在")
                append("。若是 bundle 形式，需重启实例才加载")
            }
            DSHRunner.onLog(msg)
            return done(msg)
        } catch (e: Exception) {
            DSHRunner.onLog("❌ MCP 导入失败: ${e.message}")
            return done("❌ MCP 导入失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * 收集所有 MCP 相关文件。
     *
     * 三类来源：
     *  1. `$DSH_HOME` 顶层的 `mcp*.json` / `mcp*.jsonc`；
     *  2. 各 profile 下的同名文件；
     *  3. `profiles/<profile>/local-plugins/<包>/` 里带 `dsh-mcp-client` 的包 ——
     *     **整个包目录的所有文件**都收（yml + mjs + sh），
     *     否则导出去只有配置没有实现。
     */
    private fun collectMcpFiles(home: File): List<File> {
        val out = LinkedHashSet<File>(16)
        val skip = setOf("node_modules", "sessions", "storages", "logs", ".git")

        fun addJsonLike(dir: File) {
            if (!dir.isDirectory) return
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in kids) {
                if (!f.isFile) continue
                val n = f.name.lowercase(Locale.ROOT)
                if (!n.startsWith("mcp")) continue
                if (!n.endsWith(".json") && !n.endsWith(".jsonc")) continue
                out.add(f)
            }
        }

        addJsonLike(home)

        val profiles = File(home, "profiles")
        val profileDirs = runCatching { profiles.listFiles() }
            .getOrNull()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            .orEmpty()
        for (p in profileDirs) addJsonLike(p)

        // local-plugins 下的 MCP bundle 包
        for (p in profileDirs) {
            val lp = File(p, "local-plugins")
            if (!lp.isDirectory) continue
            val pkgs = runCatching { lp.listFiles() }.getOrNull() ?: continue
            for (pkg in pkgs) {
                if (!pkg.isDirectory || pkg.name.startsWith(".")) continue
                if (!looksLikeMcpBundle(pkg)) continue
                val stack = ArrayDeque<File>()
                stack.addLast(pkg)
                while (stack.isNotEmpty()) {
                    val cur = stack.removeLast()
                    val kids = runCatching { cur.listFiles() }.getOrNull() ?: continue
                    for (k in kids) {
                        if (com.isLink(k)) continue
                        if (k.isDirectory) {
                            if (k.name in skip) continue
                            stack.addLast(k)
                        } else {
                            out.add(k)
                        }
                    }
                }
            }
        }
        return out.toList()
    }

    /** 目录里有没有指向 `dsh-mcp-client` 的配置（bundle 形式的 MCP 包）。 */
    private fun looksLikeMcpBundle(dir: File): Boolean {
        for (name in listOf("cordis.patch.yml", "cordis.yml", "package.json")) {
            val f = File(dir, name)
            if (!f.isFile) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text.contains("dsh-mcp-client")) return true
        }
        return false
    }

    /** 相对 [root] 的路径；不在 root 下返回 null（含大小写/规范化差异）。 */
    private fun relTo(root: File, f: File): String? {
        val base = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        val p = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        if (p == base) return null
        if (!p.startsWith(base + File.separator)) return null
        return p.substring(base.length + 1)
    }

    // ==============================================================
    // 小工具
    // ==============================================================

    private fun done(message: String): String {
        lastResult = message
        lastResultKind = activeKind
        return message
    }

    private fun profileDir(context: Context): File =
        File(DSHRunner.dshHome(context), "profiles/web")

    /**
     * 从根插件出发，递归收集它们 `dependencies` 声明到的包。
     *
     * 只按声明走、不靠名字猜：插件的依赖链可能很深（插件 → sharp →
     * 平台包），猜名字必然漏。磁盘上找不到的包跳过 —— 那是残缺安装，
     * 打包阶段报错没有意义，导入侧会遇到同样的缺失。
     */
    private fun closure(nodeModules: File, roots: List<String>): LinkedHashSet<String> {
        val out = LinkedHashSet<String>(32)
        val queue = ArrayDeque<String>()
        roots.forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!out.add(name)) continue
            val pkgDir = File(nodeModules, name)
            val text = runCatching { File(pkgDir, "package.json").readText() }
                .getOrNull() ?: continue
            val deps = runCatching {
                JSONObject(text.removePrefix("\uFEFF")).optJSONObject("dependencies")
            }.getOrNull() ?: continue
            val keys = deps.keys()
            while (keys.hasNext()) {
                val d = keys.next()
                if (out.contains(d)) continue
                if (File(nodeModules, d).isDirectory) queue.addLast(d)
            }
        }
        return out
    }

    /**
     * 把单个文件写进 zip。
     *
     * 失败只返回 false、不抛：一个文件读不了（权限/正被写入）不该让
     * 整个导出任务失败 —— 其余文件仍然有价值，日志里也有条数可对。
     */
    private fun writeFile(zos: ZipOutputStream, f: File, entry: String): Boolean = runCatching {
        zos.putNextEntry(ZipEntry(entry))
        f.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
        true
    }.getOrElse {
        runCatching { zos.closeEntry() }
        false
    }

    /**
     * 找出某个会话在投影缓存里的文件。
     *
     * 缓存文件名是 `<会话 id>.json`，而 key 可能是目录名、也可能是裸 uuid，
     * 所以两边都归一化（去 `session-` 前缀、小写）再比，另外允许
     * 「key 里的 uuid 等于缓存 id」。与读标题时的匹配规则保持一致 ——
     * 两边不一致的表现是「列表里有标题，导出的包里却没有缓存」。
     */
    private fun projCacheFileFor(cacheDir: File, key: String): File? {
        if (!cacheDir.isDirectory) return null
        val files = runCatching { cacheDir.listFiles() }.getOrNull() ?: return null
        val nk = key.lowercase(Locale.ROOT).removePrefix("session-")
        val uuid = SESSION_UUID_RE.find(nk)?.value
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(".json")) continue
            val id = f.name.removeSuffix(".json")
                .removePrefix("session-")
                .lowercase(Locale.ROOT)
            if (id == nk) return f
            if (uuid != null && id == uuid) return f
        }
        return null
    }

    /**
     * 把一个目录整棵写进 zip，条目名以 [prefix] 开头。
     *
     * 跳过符号链接：包里的软链可能指向 `node_modules` 之外的任意位置，
     * 打进去等于把本机路径结构泄露给接收方，解压时还会越界。
     */
    private fun writeTree(zos: ZipOutputStream, dir: File, prefix: String): Int {
        var count = 0
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(dir to prefix)
        while (stack.isNotEmpty()) {
            val (cur, entryPrefix) = stack.removeLast()
            val kids = runCatching { cur.listFiles() }.getOrNull() ?: continue
            for (f in kids) {
                if (com.isLink(f)) continue
                val entry = "$entryPrefix/${f.name}"
                if (f.isDirectory) {
                    stack.addLast(f to entry)
                } else {
                    if (count >= MAX_FILES) return count
                    val ok = runCatching {
                        zos.putNextEntry(ZipEntry(entry))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        true
                    }.getOrDefault(false)
                    if (!ok) {
                        runCatching { zos.closeEntry() }
                    } else {
                        count++
                    }
                }
            }
        }
        return count
    }

    /** 把相对路径解析到 [root] 内，越界返回 null（zip-slip 防护）。 */
    private fun resolveInside(root: File, rel: String): File? {
        if (rel.isBlank()) return null
        if (rel.startsWith("/") || rel.contains("..")) return null
        val f = File(root, rel)
        val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        val fPath = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        return if (fPath.startsWith(rootPath + File.separator)) f else null
    }
}
