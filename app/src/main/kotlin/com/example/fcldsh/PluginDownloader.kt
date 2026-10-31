package com.example.fcldsh

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.DSHRunner
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 插件下载：从插件市场索引拉列表，再装进**当前实例**。
 *
 * ## 数据从哪来
 *
 * 索引是一个 `index.json`（默认 [DSHOPEN_INDEX]），形状是：
 *
 * ```
 * {
 *   "format": 1,
 *   "plugins": [
 *     {
 *       "name": "dsh-ops",
 *       "title": { "en": "DSH Ops", "zh": "DSH 命令工具" },
 *       "description": { "en": "...", "zh": "..." },
 *       "metadata": { "category": "development" },
 *       "versions": [
 *         {
 *           "version": "0.4.4",
 *           "source": { "type": "npm", "registry": "https://registry.npmjs.org/" }
 *         }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * 所以条目名直接就是 npm 包名，版本取 `versions[0].version`，仓库地址取同一项
 * 的 `source.registry`。第三方索引只要遵循这个形状就能用 —— 这正是「可以选填
 * 第三方地址」能成立的前提。
 *
 * ## 装到哪
 *
 * `$DSH_HOME/profiles/web/node_modules/<包名>` —— 与 dsh 自己的插件管理器同
 * 一个位置。装完还要登记到 `profiles/web/package.json`，交给
 * [PluginSessionTransfer.registerPlugins]（与「导入插件包」共用同一份逻辑），
 * 否则文件在磁盘上但引擎不会加载。
 *
 * 目标实例就是**当前使用中的实例**（[DSHRunner.dshHome] 指向的那份数据目录）。
 *
 * ## 为什么不调 npm
 *
 * 容器里的 npm 能起来但很慢，而且会顺带解析整个依赖树、写入 lockfile，
 * 对「装一个插件」是杀鸡用牛刀。这里直接按 registry 元数据走：
 * 取 `dist.tarball` 下载 .tgz，用 [TarGz] 解压，再递归处理 `dependencies`。
 *
 * ## 依赖处理的取舍
 *
 * · **只走 `dependencies`**，完全不碰 `peerDependencies` —— 插件把
 *   `@deepseek-ai/` 作用域的包声明成 peer，由引擎提供，装一份进 profile
 *   只会和正在跑的引擎版本打架。
 * · `optionalDependencies` 也跳过：那里面基本是各平台的原生二进制，
 *   拉一堆跑不了的 linux 构建没有意义。
 * · 引擎自带的包一律不装（见 [providedByEngine]）。
 *
 * 注意：KDoc 里不能出现「斜杠+星号」，Kotlin 块注释可嵌套，会吞掉整个文件。
 */
object PluginDownloader {

    /** 官方 npm registry，索引条目不写 source 时的兜底。 */
    private const val NPM_REGISTRY = "https://registry.npmjs.org/"

    /**
     * 一次安装最多处理多少个包。
     *
     * 依赖图理论上是有限集，但索引若写错（自引用、环形依赖的变体）
     * 会让队列停不下来。给个硬上限，宁可少装也不要挂死。
     */
    private const val MAX_PACKAGES = 60

    private val main = Handler(Looper.getMainLooper())

    private fun post(block: () -> Unit) = main.post(block)

    // ==============================================================
    // UI 直接订阅的状态
    // ==============================================================

    enum class Phase { IDLE, LOADING, READY, ERROR }

    /** 列表加载阶段。 */
    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** 列表加载的一句话说明（条数 / 失败原因）。 */
    var message by mutableStateOf("")
        private set

    /** 当前列表来自哪个地址，供界面显示「现在用的是哪个源」。 */
    var loadedFrom by mutableStateOf("")
        private set

    /** 索引里解析出来的插件条目。 */
    val entries = mutableStateListOf<Entry>()

    /** 是否有安装正在进行（界面据此禁用所有「下载」按钮）。 */
    var installBusy by mutableStateOf(false)
        private set

    /** 安装进度说明。 */
    var installMessage by mutableStateOf("")
        private set

    /** 正在安装的包名，用于在列表里标出是哪一项在装。 */
    var installingPkg by mutableStateOf<String?>(null)
        private set

    /**
     * 索引里一个具体版本的元信息。
     *
     * @param version  版本号
     * @param dshRange `engines.dsh` 字段：哪些 dsh 引擎版本能用这个插件版本。
     *   形如 `0.1.5-rc.2 || 0.2.1-rc.1`（`||` 分隔的**精确版本**列表，不是 semver 范围）。
     *   为空表示索引没声明，一律视为可用 —— 不能因为缺字段就把插件藏起来。
     * @param registry 这个版本所在的 registry
     */
    data class VersionInfo(
        val version: String,
        val dshRange: String,
        val registry: String
    ) {
        /** 索引里有没有声明适配的引擎版本。 */
        val hasConstraint: Boolean get() = dshRange.isNotBlank()

        /** 给界面看的一句话适配说明。 */
        val engineLabel: String get() = if (hasConstraint) dshRange else "未声明"
    }

    /**
     * 一条市场条目。
     *
     * @param pkg      npm 包名（含 scope），也是安装目录名
     * @param title    显示名（中文优先），空时界面回落到 [pkg]
     * @param version  索引里标注的最新版本；空表示装 registry 的 latest
     * @param registry 该条目所在的 registry，空时用官方源
     * @param versions 该插件的全部可用版本（索引里 `versions` 数组的原样内容）
     */
    data class Entry(
        val pkg: String,
        val title: String,
        val desc: String,
        val version: String,
        val registry: String,
        val category: String,
        val versions: List<VersionInfo> = emptyList()
    ) {
        /** 界面上第一眼看到的名字。 */
        val display: String get() = title.ifBlank { pkg }
    }

    // ==============================================================
    // 拉列表
    // ==============================================================

    /**
     * 拉取索引并解析成条目。
     *
     * @param url 索引地址；空串会回落到 [DSHOPEN_INDEX]
     */
    fun fetchIndex(context: Context, url: String) {
        if (phase == Phase.LOADING || installBusy) return
        val target = url.trim().ifBlank { DSHOPEN_INDEX }
        phase = Phase.LOADING
        message = "正在拉取插件列表…"
        loadedFrom = target

        Thread {
            try {
                val json = fetchJson(target)
                    ?: throw IllegalStateException("无法下载或解析索引")
                val arr = json.optJSONArray("plugins")
                    ?: throw IllegalStateException("索引里没有 plugins 数组")

                val parsed = ArrayList<Entry>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    if (name.isBlank()) continue
                    // versions 数组里每一项都带 version / engines.dsh / source.registry。
                    // 全部收下来：用户要能自己挑版本，自动匹配也靠这里的
                    // engines.dsh 判断哪个版本能配当前实例。
                    val vers = ArrayList<VersionInfo>(4)
                    val vArr = o.optJSONArray("versions")
                    if (vArr != null) {
                        for (j in 0 until vArr.length()) {
                            val v = vArr.optJSONObject(j) ?: continue
                            val vn = v.optString("version").trim()
                            if (vn.isBlank()) continue
                            vers.add(
                                VersionInfo(
                                    version = vn,
                                    dshRange = v.optJSONObject("engines")
                                        ?.optString("dsh").orEmpty(),
                                    registry = v.optJSONObject("source")
                                        ?.optString("registry").orEmpty()
                                )
                            )
                        }
                    }
                    // 索引里 versions 一般是最新的在前；统一按版本号倒序，
                    // 免得不同源排序不一致，用户看到的第一项时新时旧。
                    //
                    // 注意不能直接 sortByDescending { semverOf(...) }：
                    // Triple 没有实现 Comparable，编译器推断不出 R 会直接报错。
                    // 折算成一个 Long 当排序键（版本号三段各占固定位宽），
                    // 解析不出来的（如 `nightly`）排到最后。
                    vers.sortByDescending { v ->
                        semverOf(v.version)?.let { (a, b, c) ->
                            a * 1_000_000L + b * 1_000L + c
                        } ?: -1L
                    }

                    val first = vers.firstOrNull()
                    parsed.add(
                        Entry(
                            pkg = name,
                            title = pickLocalized(o.opt("title")),
                            desc = pickLocalized(o.opt("description")),
                            version = first?.version.orEmpty(),
                            registry = first?.registry.orEmpty(),
                            category = o.optJSONObject("metadata")
                                ?.optString("category").orEmpty(),
                            versions = vers
                        )
                    )
                }
                parsed.sortBy { it.display.lowercase() }

                // 顺手把「包名 → 显示名」存一份到磁盘。
                //
                // ★ 这份缓存是给 dsh 设置页用的 ★
                //
                // 插件自己的 package.json 里**没有**显示名字段（实测
                // dsh-whale-widget 只有 name / description / keywords），
                // 所以设置页只能显示 `dsh-whale-widget` 这种机器名；
                // 而市场索引里有 `title: { zh, en }`。把索引里的名字缓存下来，
                // 设置页就能离线复用它，不必自己再发一次网络请求。
                writeTitleCache(context, parsed)

                post {
                    entries.clear()
                    entries.addAll(parsed)
                    if (parsed.isEmpty()) {
                        phase = Phase.ERROR
                        message = "索引里没有可用插件"
                    } else {
                        phase = Phase.READY
                        message = "共 ${parsed.size} 个插件"
                    }
                }
            } catch (e: Exception) {
                post {
                    phase = Phase.ERROR
                    message = "拉取失败: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    /**
     * 从双语对象里挑一个字符串（`zh` 优先，其次 `en`，再其次纯字符串）。
     *
     * 索引里的 title / description 都是这种形状；直接 `optString` 会拿到
     * `{"en":...}` 这种原始 JSON 文本，显示出来就是一团乱码。
     */
    private fun pickLocalized(v: Any?): String = when (v) {
        null -> ""
        is JSONObject -> v.optString("zh").ifBlank { v.optString("en") }
        is String -> v
        else -> v.toString()
    }

    // ==============================================================
    // 显示名缓存（给 dsh 设置页复用）
    // ==============================================================

    /** 缓存文件名。放在 filesDir 下，跟着应用数据走，卸载才清。 */
    private const val TITLE_CACHE = "plugin-titles.json"

    private fun cacheFile(context: Context): File =
        File(context.filesDir, TITLE_CACHE)

    /**
     * 把「包名 → 显示名」写进缓存。
     *
     * 只存有名字的条目（`title` 为空的跳过），否则缓存里会塞一堆
     * 「包名映射到空字符串」的无用项，读出来还要再判一次。
     *
     * 整个写入包在 runCatching 里：缓存是锦上添花，
     * 磁盘满 / 权限异常都不该让「拉列表」这个主流程失败。
     */
    private fun writeTitleCache(context: Context, entries: List<Entry>) {
        runCatching {
            val o = JSONObject()
            entries.forEach { e ->
                val name = e.title.trim()
                if (name.isNotBlank()) o.put(e.pkg, name)
            }
            if (o.length() > 0) cacheFile(context).writeText(o.toString())
        }
    }

    /**
     * 读出缓存的「包名 → 显示名」，供 dsh 设置页的插件列表使用。
     *
     * 没有缓存（从没打开过插件下载页）时返回空表，调用方回落到包名 ——
     * 这是正常的降级路径，不是错误。
     */
    fun cachedTitles(context: Context): Map<String, String> {
        val f = cacheFile(context)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            val out = HashMap<String, String>(o.length() * 2)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.optString(k)
                if (v.isNotBlank()) out[k] = v
            }
            out
        }.getOrDefault(emptyMap())
    }

    // ==============================================================
    // 安装
    // ==============================================================

    /**
     * 把一个插件装进**当前实例**。
     *
     * 步骤：解析版本 → 下载 tarball → 解压进 profile 的 node_modules →
     * 递归处理 dependencies → 登记到 profile 清单。
     *
     * @param wantVersion 指定要装的版本；传 null 表示按当前实例的引擎版本
     *   [bestVersion] 自动挑一个（挑不出来就退回索引里的最新版）。
     *   用户在版本列表里点某一项时把这个值传进来。
     *
     * 全程在后台线程，进度写进 [installMessage]。
     */
    fun install(context: Context, entry: Entry, wantVersion: String? = null) {
        if (installBusy) return
        installBusy = true
        installingPkg = entry.pkg
        installMessage = "准备安装 ${entry.display}…"

        Thread {
            var ok = false
            try {
                val profile = File(DSHRunner.dshHome(context), "profiles/web")
                val nodeModules = File(profile, "node_modules")
                if (!nodeModules.isDirectory && !nodeModules.mkdirs()) {
                    throw IllegalStateException("无法创建 node_modules 目录")
                }

                // pkg → 实际装下的版本
                val done = LinkedHashMap<String, String>()
                // pkg → 版本范围。根条目用「用户点的那个版本」，没指定就用
                // 索引标注的最新版；依赖则按各自 package.json 声明的范围解析。
                val rootVersion = wantVersion?.takeIf { it.isNotBlank() }
                    ?: entry.version.takeIf { it.isNotBlank() }
                val queue = ArrayDeque<Pair<String, String>>()
                queue.addLast(entry.pkg to (rootVersion ?: ""))

                var steps = 0
                while (queue.isNotEmpty() && steps < MAX_PACKAGES) {
                    val (pkg, range) = queue.removeFirst()
                    if (done.containsKey(pkg)) continue
                    if (providedByEngine(pkg)) continue
                    steps++

                    val idx = steps
                    post { installMessage = "正在安装 $pkg（$idx）…" }

                    val registry = entry.registry.trim().ifBlank { NPM_REGISTRY }
                    val meta = fetchJson(metaUrl(registry, pkg))
                    if (meta == null) {
                        DSHRunner.onLog("⚠️ 取不到 $pkg 的元数据，跳过")
                        continue
                    }
                    val ver = pickVersion(meta, range)
                    val verNode = ver?.let { meta.optJSONObject("versions")?.optJSONObject(it) }
                    val tarball = verNode?.optJSONObject("dist")
                        ?.optString("tarball").orEmpty()
                    if (ver == null || tarball.isBlank()) {
                        DSHRunner.onLog("⚠️ $pkg 找不到可用版本，跳过")
                        continue
                    }

                    val dest = File(nodeModules, pkg)
                    // 覆盖安装：旧目录先清掉，否则残留文件会和新版本混在一起。
                    // 软链只能用 delete()，顺着链接 deleteRecursively 会删到外面去。
                    if (com.isLink(dest)) {
                        dest.delete()
                    } else if (dest.isDirectory) {
                        dest.deleteRecursively()
                    }
                    dest.mkdirs()
                    download(tarball) { gz -> TarGz.extract(gz, dest, 1) }
                    done[pkg] = ver
                    DSHRunner.onLog("✅ $pkg@$ver")

                    // 只跟随 dependencies（不含 peer / optional）。
                    // verNode 是 JSONObject?（上面已经判过 ver != null，但
                    // 编译器不知道 optJSONObject 的结果非空），所以用安全调用。
                    val deps = verNode?.optJSONObject("dependencies")
                    if (deps != null) {
                        val keys = deps.keys()
                        while (keys.hasNext()) {
                            val d = keys.next()
                            if (done.containsKey(d)) continue
                            queue.addLast(d to deps.optString(d))
                        }
                    }
                }

                if (done.isEmpty()) {
                    post { installMessage = "❌ 没有装下任何内容" }
                    return@Thread
                }

                // 登记到 profile 清单：少了这一步，文件在磁盘上但引擎不会加载。
                // 与「导入插件包」共用同一份实现，避免两处规则分叉。
                val pairs = done.map { it.key to it.value }
                val registered = PluginSessionTransfer.registerPlugins(context, pairs)
                ok = true

                val deps = done.size - 1
                val msg = buildString {
                    append("✅ 已安装 ${entry.display}")
                    if (!done[entry.pkg].isNullOrBlank()) append(" ${done[entry.pkg]}")
                    if (deps > 0) append("（含 $deps 个依赖）")
                    if (registered > 0) append("，已登记 $registered 个")
                }
                DSHRunner.onLog(msg)
                DSHRunner.onLog("ℹ️ 重启实例后插件才会加载")
                post { installMessage = "$msg，重启实例后生效" }
            } catch (e: Exception) {
                DSHRunner.onLog("❌ 插件安装失败: ${e.message}")
                post {
                    installMessage = "❌ 安装失败: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                post {
                    installBusy = false
                    installingPkg = null
                    // 失败时别把错误信息清掉，成功才需要后续刷新由界面自己做
                    if (!ok && installMessage.isBlank()) {
                        installMessage = "❌ 安装未完成"
                    }
                }
            }
        }.start()
    }

    /** 清掉上一次安装结果提示（换源或重新拉列表时调）。 */
    fun clearInstallMessage() {
        if (!installBusy) installMessage = ""
    }

    // ==============================================================
    // 小工具
    // ==============================================================

    /**
     * 引擎已经提供的包，不要再装一份进 profile。
     *
     * 插件把 `@deepseek-ai/` 作用域的包声明成 peerDependency，由引擎提供；
     * cordis 是 dsh 的插件框架本体，也随引擎。装进 profile 会让 Node 从
     * 更近的 `node_modules` 解析到一份不同版本的副本，表现为插件加载时
     * 出现两个不同的框架实例、状态互不可见。
     */
    private fun providedByEngine(pkg: String): Boolean =
        pkg.startsWith("@deepseek-ai/") ||
            pkg == "cordis" ||
            pkg.startsWith("@cordisjs/")

    /** 包在 registry 里的元数据地址。scope 包的分隔符要转义成 `%2F`。 */
    private fun metaUrl(registry: String, pkg: String): String {
        val base = registry.trimEnd('/')
        val encoded = pkg.replace("/", "%2F")
        return "$base/$encoded"
    }

    /**
     * 从 registry 元数据里挑一个版本。
     *
     * 这是一个**够用的**解析器，不是完整的 semver：插件的依赖基本都是
     * `^x.y.z` / `~x.y.z` / 精确版本 / `*` 这几种形态，都能正确处理。
     * 认不出的范围一律退回 `latest` —— 装一个稍新的版本，好过装不上。
     */
    internal fun pickVersion(meta: JSONObject, range: String): String? {
        val tags = meta.optJSONObject("dist-tags")
        val latest = tags?.optString("latest")?.takeIf { it.isNotBlank() }
        val all = ArrayList<String>()
        meta.optJSONObject("versions")?.let { v -> v.keys().forEach { all.add(it) } }
        if (all.isEmpty()) return latest

        val r = range.trim()
        if (r.isEmpty() || r == "*" || r == "latest" || r == "x") {
            return latest ?: all.lastOrNull()
        }
        // 精确版本，且 registry 里确实有这个版本
        if (all.contains(r)) return r

        val m = Regex("^[\\^~]?[>=\\s]*(\\d+)\\.(\\d+)\\.(\\d+)").find(r)
        if (m != null) {
            val want = Triple(
                m.groupValues[1].toIntOrNull() ?: 0,
                m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].toIntOrNull() ?: 0
            )
            val tilde = r.startsWith("~")
            val caret = r.startsWith("^")
            val best = all.asSequence()
                .mapNotNull { v -> semverOf(v)?.let { v to it } }
                .filter { (_, t) ->
                    if (cmp(t, want) < 0) return@filter false
                    when {
                        tilde -> t.first == want.first && t.second == want.second
                        caret -> t.first == want.first
                        else -> true
                    }
                }
                .maxByOrNull { (_, t) -> t.first * 1_000_000 + t.second * 1_000 + t.third }
            if (best != null) return best.first
        }
        return latest ?: all.lastOrNull()
    }

    private val SEMVER = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)")

    private fun semverOf(v: String): Triple<Int, Int, Int>? {
        val m = SEMVER.find(v) ?: return null
        return Triple(
            m.groupValues[1].toIntOrNull() ?: return null,
            m.groupValues[2].toIntOrNull() ?: return null,
            m.groupValues[3].toIntOrNull() ?: return null
        )
    }

    private fun cmp(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        if (a.first != b.first) return a.first - b.first
        if (a.second != b.second) return a.second - b.second
        return a.third - b.third
    }

    /** GET 一个 JSON。失败返回 null，由调用方决定是跳过还是报错。 */
    private fun fetchJson(url: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "fcl-dsh/1.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            conn = c
            if (c.responseCode !in 200..299) {
                null
            } else {
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 下载到 [consume] 提供的消费者里（通常是解压器），读超时给得宽一些。 */
    private fun download(url: String, consume: (InputStream) -> Int) {
        var conn: HttpURLConnection? = null
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "fcl-dsh/1.0 (Android)")
            }
            conn = c
            if (c.responseCode !in 200..299) {
                throw IllegalStateException("下载失败 HTTP ${c.responseCode}")
            }
            c.inputStream.use { consume(it) }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 某个包在当前实例里已装的版本；没装返回 null。
     *
     * 界面据此把按钮显示成「已装 / 重装」，用户不用点进去才发现是重复安装。
     */
    fun installedVersion(context: Context, pkg: String): String? {
        val dir = File(DSHRunner.dshHome(context), "profiles/web/node_modules/$pkg")
        if (!dir.isDirectory) return null
        return readPackageVersion(dir).ifBlank { "已装" }
    }

    // ==============================================================
    // 引擎版本适配
    // ==============================================================

    /**
     * `engines.dsh` 与某个引擎版本是否匹配。
     *
     * ## 这不是 semver 范围
     *
     * 索引里的值形如 `0.1.5-rc.2 || 0.2.1-rc.1`，是 `||` 分隔的
     * **精确版本枚举**（注意 `-rc.2` 这种预发布后缀）。套用 semver 范围解析
     * 会把 `-rc` 当成构建元数据或直接判错，所以这里只做逐项字符串相等比较。
     *
     * 空字符串表示索引没声明限制 —— **视为可用**。缺字段不等于不兼容，
     * 因为这个把插件藏起来，用户只会以为列表拉失败了。
     */
    fun enginesMatch(range: String, engineVersion: String): Boolean {
        if (range.isBlank()) return true
        val want = engineVersion.trim()
        if (want.isEmpty()) return true
        return range.split("||").any { it.trim() == want }
    }

    /**
     * 按当前实例的引擎版本挑一个最该装的插件版本。
     *
     * 优先级（顺序很重要，不能只用一个 `firstOrNull` 扫一遍）：
     *
     *  1. **明确声明适配当前引擎**的版本里最新的那个 —— 这是唯一能保证
     *     插件正常加载的情况；
     *  2. 没有明确适配的，退而取**没声明限制**的版本（索引缺字段），
     *     装上去大概率也能跑；
     *  3. 全都不行返回 null —— 界面要如实说「没有适配当前实例的版本」，
     *     让用户自己决定。装错版本可能导致引擎启动失败，不能替他猜。
     *
     * @param engineVersion 当前实例的引擎版本（`DSHRunner.currentVersion`）
     */
    fun bestVersion(entry: Entry, engineVersion: String?): VersionInfo? {
        val all = entry.versions
        if (all.isEmpty()) return null
        val engine = engineVersion?.trim().orEmpty()
        if (engine.isEmpty()) return null

        // ① 明确适配当前引擎的（versions 已按版本号倒序，first 即最新）
        all.firstOrNull { it.hasConstraint && enginesMatch(it.dshRange, engine) }
            ?.let { return it }

        // ② 没声明限制的
        all.firstOrNull { !it.hasConstraint }?.let { return it }

        // ③ 有声明但都不匹配 —— 不猜
        return null
    }

    /** 这个条目有没有任何一个版本能配当前引擎（界面用来标黄/标灰）。 */
    fun hasCompatible(entry: Entry, engineVersion: String?): Boolean =
        bestVersion(entry, engineVersion) != null
}
