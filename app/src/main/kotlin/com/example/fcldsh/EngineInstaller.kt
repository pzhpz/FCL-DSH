package com.example.fcldsh

import com.DSHRunner
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 官方版本中心：从 npm registry 拉取 @deepseek-ai/dsh 的可用版本，
 * 并用容器自带的 npm 在设备上完成安装。
 *
 * ## 为什么不能直接下载 registry 的 tarball
 *
 * npm 的 tarball 只含 `dsh` 这一个包本身，**不含它依赖的几十个同级
 * `@deepseek-ai/` 包**（dsh-app-boot、dsh-base、dsh-web-app、cordis…）。
 * `lib/bin.js` 第一行就 import `@deepseek-ai/dsh-app-boot`，
 * 只解压 tarball 必然在启动瞬间失败。
 *
 * 所以这里的做法是：**在设备上让 npm 自己解析依赖树**。容器里已经带了
 * node + npm，具备这个能力，不需要借助 PC。
 *
 * ## 三个关键取舍
 *
 * - `--ignore-scripts`：不执行 postinstall。否则 node-pty / sharp 会去拉
 *   **Linux x64** 的原生二进制，在 Android 上根本加载不了。
 * - `--omit=optional`：跳过平台相关的可选依赖（如 sharp 的
 *   `@img/sharp-linux-x64`、ripgrep 的 linux 包）。容器里已有 bionic 版本，
 *   不装这些反而避免它们抢占模块解析优先级。
 * - 先装到 staging 目录，成功后再把 `@deepseek-ai` 整体搬到 `usr/lib/node_modules`。
 *   中途失败不会污染已经能用的容器。
 */
object EngineInstaller {

    /** 官方版本索引。取 latest/next/alpha 三个 dist-tag 与全部版本号。 */
    private const val REGISTRY_URL = "https://registry.npmjs.org/@deepseek-ai/dsh"

    private val main = Handler(Looper.getMainLooper())

    private fun post(block: () -> Unit) = main.post(block)

    // ------------------------------------------------------------------
    // 版本列表
    // ------------------------------------------------------------------

    enum class CatalogPhase { IDLE, LOADING, READY, ERROR }

    var catalogPhase by mutableStateOf(CatalogPhase.IDLE)
        private set

    /** 版本号，按 registry 返回顺序（通常是从旧到新）反转，最新的在最前。 */
    val versions = mutableStateListOf<String>()

    /** dist-tag 到版本号的映射，用于在列表里标注 latest / next。 */
    var distTags by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var catalogMessage by mutableStateOf("")
        private set

    /** 当前选中的版本。 */
    var selectedVersion by mutableStateOf<String?>(null)

    /**
     * 拉取版本列表。
     *
     * 失败时把原因写进 [catalogMessage]，UI 直接显示，
     * 不再走"网络错误"这种含糊说法。
     */
    fun fetchCatalog() {
        if (catalogPhase == CatalogPhase.LOADING) return
        catalogPhase = CatalogPhase.LOADING
        catalogMessage = "正在获取版本列表…"

        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(REGISTRY_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "fcl-dsh/1.0 (Android)")
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)

                // dist-tags：{"latest":"0.2.1-rc.2","next":"0.2.1-rc.4",...}
                val tags = LinkedHashMap<String, String>()
                json.optJSONObject("dist-tags")?.let { t ->
                    t.keys().forEach { k -> tags[k] = t.optString(k) }
                }

                // versions：对象键即版本号
                val all = ArrayList<String>()
                json.optJSONObject("versions")?.let { v ->
                    v.keys().forEach { all.add(it) }
                }
                // registry 的键顺序不保证，按字符串排序再反转，让新版本在前
                all.sort()
                all.reverse()

                post {
                    versions.clear()
                    versions.addAll(all)
                    distTags = tags
                    selectedVersion = tags["latest"] ?: all.firstOrNull()
                    if (all.isEmpty()) {
                        catalogPhase = CatalogPhase.ERROR
                        catalogMessage = "registry 返回了空的版本列表"
                    } else {
                        catalogPhase = CatalogPhase.READY
                        catalogMessage = "共 ${all.size} 个版本"
                    }
                }
            } catch (e: Exception) {
                post {
                    catalogPhase = CatalogPhase.ERROR
                    catalogMessage = "获取版本列表失败: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    enum class InstallPhase(val label: String) {
        IDLE("就绪"),
        INSTALLING("安装中"),
        DONE("完成"),
        ERROR("失败")
    }

    var installPhase by mutableStateOf(InstallPhase.IDLE)
        private set

    var installMessage by mutableStateOf("")
        private set

    /** 已写入 npm 缓存的字节数（即"已下载"的真实量）。 */
    var downloadedBytes by mutableStateOf(0L)
        private set

    /**
     * 总字节数的**估算值**。
     *
     * npm 在安装前不报告依赖树总大小，所以拿不到精确总量。这里用一个
     * 会自适应的估计：初值给一个保守基线，一旦实际下载量超过它，
     * 就把估计抬到 `实际 / 0.9`，让进度条始终停在 90% 附近而不会满格。
     * 安装真正结束时才置 100%。这样进度条是**跟着真实字节走**的，
     * 不是原地循环的动画。
     */
    var totalBytes by mutableStateOf(0L)
        private set

    /** 当前下载速度（字节/秒），由缓存目录的增量算出。 */
    var bytesPerSecond by mutableStateOf(0L)
        private set

    /** 0f..1f 为确定进度；-1f 表示阶段未定（交接瞬间）。 */
    var progress by mutableStateOf(-1f)
        private set

    @Volatile
    private var busy = false

    /** 当前 npm 进程，供取消使用。 */
    @Volatile
    private var currentProc: Process? = null

    @Volatile
    private var cancelled = false

    fun isBusy(): Boolean = busy

    fun resetInstall() {
        if (busy) return
        installPhase = InstallPhase.IDLE
        installMessage = ""
        downloadedBytes = 0L
        totalBytes = 0L
        bytesPerSecond = 0L
        progress = -1f
    }

    /** 取消正在进行的安装。npm 可能卡在网络上，必须能主动中止。 */
    fun cancelInstall() {
        if (!busy) return
        cancelled = true
        runCatching { currentProc?.destroy() }
        DSHRunner.onLog("⏹ 已请求取消安装")
    }

    /**
     * 用容器自带的 npm 安装指定版本。
     *
     * 流程：
     * 1. 在 cache 建 staging 目录
     * 2. `npm install @deepseek-ai/dsh@<ver> --prefix staging ...`
     * 3. 成功后把**整个** `staging/node_modules` 搬到
     *    `usr/lib/versions/<ver>/node_modules/`
     * 4. 清理 staging
     *
     * ## ★ 为什么必须整棵搬，而不是只搬 @deepseek-ai ★
     *
     * 早期版本只搬作用域目录，理由是「复用容器里已装好的第三方依赖、
     * 省几百 MB」。那个理由当时看着成立，实际是错的，并且直接造成了
     * 「引擎能启动、但内置插件全废」：
     *
     * ```
     * ERR_MODULE_NOT_FOUND: Cannot find package 'hosted-git-info'
     * ERR_MODULE_NOT_FOUND: Cannot find package 'birpc'
     *  → 本部署没有可管理的 profile，无法安装或启停插件
     *  → 新增会话失败：waiting for pluginManager → workflowEngine
     * ```
     *
     * npm 把引擎和它的传递依赖一起装进 `staging/node_modules`。
     * 只搬 `@deepseek-ai` 就等于扔掉依赖闭包，而容器只固化了很少几个包
     * （zod、commander 等），新版本引擎需要的 hosted-git-info / birpc
     * 根本不在里面。Node 从 `versions/<ver>/node_modules` 往上找，
     * 下一层就是容器的 `usr/lib/node_modules` —— 找不到，然后整个插件链崩掉。
     *
     * 整棵搬过来后每个版本自带完整闭包，容器里的包仍作为更上层的兜底存在。
     * 代价是每个版本多占一百多 MB；这个交换是值得的 ——
     * 「装得上、插件能用」比省磁盘重要得多。
     */
    fun installVersion(context: Context, version: String) {
        if (busy) return

        busy = true
        cancelled = false
        installPhase = InstallPhase.INSTALLING
        installMessage = "准备安装 $version…"
        // 前台保活：npm 安装是几万个小请求，熄屏后若被冻结会全线超时。
        // 服务自己轮询 isBusy()，安装结束时自动退出，这里不需要配对调用 stop。
        EngineTaskService.start(context, "正在安装 dsh 引擎 $version")
        // 每次安装都从干净进度开始，避免上一次的字节数残留
        downloadedBytes = 0L
        totalBytes = 0L
        bytesPerSecond = 0L
        progress = -1f

        Thread {
            val usr = File(context.filesDir, "usr")
            val staging = File(context.cacheDir, "engine-staging")
            var ok = false
            try {
                // 每次从干净目录开始，避免上一次的残留影响依赖解析
                runCatching { staging.deleteRecursively() }
                staging.mkdirs()

                // ── 容器自动准备 ────────────────────────────────────────────
                // npm 本身就跑在容器里（usr/bin/node + usr/lib/node_modules/npm），
                // 没容器装不了引擎。但用户不该为了装引擎先去别处点一次「启动」
                // 才能把容器解压出来 —— 那是个纯粹的内部实现细节。
                //
                // 所以这里顺手准备好：首次下载会多花一次解压的时间（进度窗有文案），
                // 之后每次都命中「已就绪」直接跳过，不影响体验。
                // ensureRuntime 内部加了锁，和启动线程并发进来也不会互相破坏。
                if (!DSHRunner.runtimeInstalled(context)) {
                    post { installMessage = "首次安装：正在解压内置容器…" }
                }
                if (!DSHRunner.ensureRuntime(context)) {
                    post {
                        installPhase = InstallPhase.ERROR
                        installMessage = "容器解压失败，无法安装引擎"
                    }
                    return@Thread
                }
                post { installMessage = "容器就绪，开始安装 $version…" }

                ok = runNpmInstall(context, usr, staging, version)
                if (!ok) {
                    post {
                        // 用户主动取消不该显示成"失败"
                        if (cancelled) {
                            installPhase = InstallPhase.IDLE
                            installMessage = "已取消"
                        } else {
                            installPhase = InstallPhase.ERROR
                            installMessage = "npm 安装失败，详见运行日志"
                        }
                    }
                    return@Thread
                }

                post { installMessage = "正在部署到版本目录…" }
                val from = File(staging, "node_modules/@deepseek-ai")
                if (!from.isDirectory) {
                    post {
                        installPhase = InstallPhase.ERROR
                        installMessage = "安装结果里没有 @deepseek-ai 目录"
                    }
                    return@Thread
                }

                // 多版本共存的落盘位置：lib/versions/<ver>/node_modules/。
                //
                // 目录名必须是**真实版本号**（不是用户传进来的字符串，虽然通常一致）：
                // 用户可能装 dist-tag（latest/next），而磁盘上要按具体版本归类。
                val realVer = readVersionFrom(from) ?: version

                // 先装到 __incoming__，整棵树就位后再一次性改名成正式版本号。
                // 直接写 lib/versions/<ver> 的话，中途失败会留下一个
                // 「看起来存在、其实残缺」的版本目录，之后每次启动都失败却查不出原因。
                val incoming = File(usr, "lib/versions/__incoming__")
                runCatching { incoming.deleteRecursively() }
                val incomingScope = File(incoming, "node_modules")
                incomingScope.mkdirs()

                // ★ 必须搬**整个 node_modules**，不能只搬 @deepseek-ai ★
                //
                // 旧实现只把 staging/node_modules/@deepseek-ai 挪过去，剩下的
                // 第三方传递依赖（hosted-git-info、birpc、…）随 staging 一起被删掉。
                // 后果是引擎能起来、网页能开，但内置插件全部 import 失败：
                //
                //   ERR_MODULE_NOT_FOUND: Cannot find package 'hosted-git-info'
                //     imported from .../dsh-plugin-manager/lib/index.js
                //   → 「本部署没有可管理的 profile」
                //   → 「新增会话失败：waiting for pluginManager → workflowEngine」
                //
                // Node 的解析链在 `versions/<ver>/node_modules` 这一层只能看到
                // @deepseek-ai，再往上就跳到容器的 `usr/lib/node_modules`，
                // 而容器只固化了少数几个包，没有这些新依赖 —— 于是永远找不到。
                //
                // 改成整棵搬过来后，每个版本自带完整的依赖闭包；
                // 容器里的包仍然作为兜底后代存在（解析链更靠上，不会冲突）。
                // 代价是每个版本多占约一百多 MB，但「插件能装能启」远比这重要。
                val stagingModules = File(staging, "node_modules")
                val dest = incomingScope
                runCatching { dest.deleteRecursively() }
                val movedAll = runCatching {
                    if (stagingModules.renameTo(dest)) {
                        true
                    } else {
                        stagingModules.copyRecursively(dest, overwrite = true)
                        true
                    }
                }.getOrDefault(false)
                if (!movedAll || !File(dest, "@deepseek-ai").isDirectory) {
                    post {
                        installPhase = InstallPhase.ERROR
                        installMessage = "部署 node_modules 失败（依赖闭包不完整）"
                    }
                    return@Thread
                }

                // 不再部署 linux 平台可选包。
                // 那些是 glibc/musl 构建，Android 的 bionic 缺 __errno_location，
                // dlopen 必然失败（实测报错原文见 EngineInstaller 的 npm 参数注释）。
                // 容器自带的 bionic 版本（@vscode/ripgrep-android-arm64 等）才是能跑的；
                // 缺少的原生能力（flock）由 DSHRunner.ensureFlockShim 用纯 JS 替代。

                val target = File(usr, "lib/versions/$realVer")
                // 同版本重装＝覆盖，先清掉旧的，避免两代文件混在一起
                runCatching { target.deleteRecursively() }
                target.parentFile?.mkdirs()
                val moved = incoming.renameTo(target) || runCatching {
                    incoming.copyRecursively(target, overwrite = true)
                    incoming.deleteRecursively()
                }.isSuccess

                if (!moved) {
                    runCatching { incoming.deleteRecursively() }
                    post {
                        installPhase = InstallPhase.ERROR
                        installMessage = "无法落盘到 lib/versions/$realVer"
                    }
                    return@Thread
                }

                // 新装好的版本直接设为当前 —— 用户点「下载」就是为了用它
                DSHRunner.setCurrentVersion(context, realVer)
                // 新版本里也有一份 node-addon-system，flock 需要一并替换
                DSHRunner.ensureFlockShim(context)

                val installed = DSHRunner.dshInstalled(context)
                post {
                    // 安装结束：进度置满、速度归零，避免停在 97% 让人以为没完成
                    bytesPerSecond = 0L
                    if (installed) {
                        installPhase = InstallPhase.DONE
                        progress = 1f
                        installMessage = "已安装 dsh $realVer"
                    } else {
                        installPhase = InstallPhase.ERROR
                        installMessage = "部署完成但未找到 bin.js，版本可能异常"
                    }
                }
            } catch (e: Exception) {
                post {
                    installPhase = InstallPhase.ERROR
                    installMessage = "安装异常: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                runCatching { staging.deleteRecursively() }
                busy = false
            }
        }.start()
    }

    /**
     * 从 `@deepseek-ai` 作用域目录里读出 dsh 的真实版本号。
     *
     * 装 dist-tag（latest / next）时，用户传给 [installVersion] 的是标签而非版本号，
     * 而磁盘上的目录名必须是具体版本 —— 否则「切换版本」会指到一个查不到的名字。
     */
    private fun readVersionFrom(scopeDir: File): String? = runCatching {
        val pkg = File(scopeDir, "dsh/package.json")
        if (!pkg.isFile || pkg.length() == 0L) return null
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
            .find(pkg.readText())?.groupValues?.get(1)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** registry 上 npm 的最新版本描述（只取 version 字段）。 */
    private const val NPM_LATEST_URL = "https://registry.npmjs.org/npm/latest"

    /** npm 的 tarball 直链模板。 */
    private const val NPM_TGZ_TEMPLATE = "https://registry.npmjs.org/npm/-/npm-%s.tgz"

    /**
     * 找一个可用的包管理器入口。
     *
     * 容器的 `bin/` 下有 npm / pnpm 可执行文件，但**包目录**未必完整 ——
     * 实测该容器里 `lib/node_modules/` 只有 corepack，没有 npm/pnpm，
     * 所以 `bin/npm` 是个指向不存在目标的坏入口，只能靠下面探测包目录。
     */
    private fun findPackageManager(usr: File): Pair<String, File>? {
        val candidates = listOf(
            "npm" to "lib/node_modules/npm/bin/npm-cli.js",
            "npm" to "lib/node_modules/npm/bin/npm-cli.cjs",
            "pnpm" to "lib/node_modules/pnpm/bin/pnpm.cjs",
            "pnpm" to "lib/node_modules/pnpm/bin/pnpm.js"
        )
        for ((name, rel) in candidates) {
            val f = File(usr, rel)
            if (f.isFile && f.length() > 0) return name to f
        }
        return null
    }

    /**
     * 确保有包管理器可用：找不到就从官方 registry 补装 npm。
     *
     * ## 为什么可以这样做
     *
     * npm 自身发布成 `.tgz`，且**把全部依赖 vendored 进包内**
     * （`package/node_modules/...`），所以解压出来就是可用的，
     * 不需要再解析一次依赖树。这也是唯一不依赖任何外部工具的自举方式。
     *
     * 解压目标固定为 `lib/node_modules/npm`，正好落在
     * [findPackageManager] 第一条候选路径上。
     */
    private fun ensurePackageManager(context: Context, usr: File): Pair<String, File>? {
        findPackageManager(usr)?.let { return it }

        DSHRunner.onLog("⚠️ 容器内没有 npm，尝试从 registry 补装")
        post { installMessage = "正在补装 npm…" }

        return try {
            val ver = fetchNpmVersion()
            DSHRunner.onLog("npm 版本: $ver")

            val dest = File(usr, "lib/node_modules/npm")
            runCatching { dest.deleteRecursively() }
            dest.mkdirs()

            val url = String.format(java.util.Locale.US, NPM_TGZ_TEMPLATE, ver)
            DSHRunner.onLog("下载 $url")
            openStream(url).use { input ->
                // npm tarball 的条目形如 package/bin/npm-cli.js，剥掉首层
                val n = TarGz.extract(input, dest, stripComponents = 1)
                DSHRunner.onLog("✅ npm 解压完成：$n 个文件")
            }

            val cli = File(dest, "bin/npm-cli.js")
            if (cli.isFile && cli.length() > 0) {
                "npm" to cli
            } else {
                DSHRunner.onLog("❌ npm 解压后缺少 bin/npm-cli.js")
                null
            }
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 补装 npm 失败: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** 查 registry 上 npm 的最新版本号。 */
    private fun fetchNpmVersion(): String {
        val body = openStream(NPM_LATEST_URL).bufferedReader().use { it.readText() }
        val ver = JSONObject(body).optString("version")
        if (ver.isBlank()) throw IllegalStateException("registry 未返回 npm 版本号")
        return ver
    }

    /** 打开一个 http(s) 流，校验状态码。 */
    private fun openStream(url: String): java.io.InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 40_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "fcl-dsh/1.0 (Android)")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code")
        }
        return conn.inputStream
    }

    /** 执行依赖安装，把输出逐行送进运行日志。 */
    private fun runNpmInstall(
        context: Context,
        usr: File,
        staging: File,
        version: String
    ): Boolean {
        val nodePath = File(usr, "bin/node")
        if (!nodePath.exists()) {
            post { installMessage = "容器缺少 bin/node" }
            return false
        }

        // 先确认容器里的 node 真能跑起来，并记下版本。
        // npm 对 Node 主版本有硬要求（新版本 npm 常要求 >= 22），
        // 版本不符时 npm 会直接拒绝启动，没有这行日志很难判断。
        val nodeVer = probeNodeVersion(context, usr, nodePath)
        DSHRunner.onLog("node 版本: ${nodeVer ?: "未知（--version 未能返回）"}")

        // 容器里可能本来就没有 npm（实测只有 corepack），
        // 这里会自动从 registry 补装一份纯 JS 的 npm。
        val pm = ensurePackageManager(context, usr)
        if (pm == null) {
            DSHRunner.onLog("❌ 补装 npm 后仍不可用")
            DSHRunner.onLog("   已尝试 lib/node_modules/{npm,pnpm}/bin/*")
            post { installMessage = "无法准备包管理器，请改用「本地 zip」" }
            return false
        }
        val (pmName, cli) = pm
        DSHRunner.onLog("使用 $pmName: ${cli.absolutePath}")

        // ── npm 预检 ────────────────────────────────────────────────────
        // 实测现象：npm 启动后零输出、零网络流量，一直挂着。
        // 那说明它卡在 npm 自身初始化，而不是在下载依赖。
        // 先跑 `npm --version`（不联网）把两种情况分开：
        //   · 预检失败/超时  → npm 在这个环境下根本起不来
        //   · 预检成功但安装卡 → 问题在依赖解析或网络
        val npmVer = probeNpmVersion(context, usr, nodePath, cli, pmName)
        if (npmVer == null) {
            DSHRunner.onLog("❌ $pmName --version 未能正常返回")
            DSHRunner.onLog("   说明包管理器自身无法在此环境启动，与网络无关")
            post { installMessage = "npm 无法启动，请查看日志或改用「本地 zip」" }
            return false
        }
        DSHRunner.onLog("$pmName 版本: $npmVer")

        // npm 与 pnpm 的参数名不同，这里按实际探测结果拼装
        val installArgs = if (pmName == "pnpm") {
            listOf(
                "add", "@deepseek-ai/dsh@$version",
                "--dir", staging.absolutePath,
                "--ignore-scripts",
                "--config.optional=false",
                "--reporter=append-only"
            )
        } else {
            listOf(
                "install", "@deepseek-ai/dsh@$version",
                "--prefix", staging.absolutePath,
                // 不跑 postinstall：否则 node-pty / sharp 会去下 Linux x64 原生包
                "--ignore-scripts",
                // 跳过平台相关可选依赖。
                //
                // 这里曾改成 `--os=linux --cpu=<arch>` 想把 linux 平台包补齐，
                // 但那条路已被证伪：那些包是 glibc/musl 构建，Android 的 bionic
                // 缺少 __errno_location 符号，dlopen 必然失败：
                //   dlopen failed: cannot locate symbol "__errno_location"
                // 所以装进来只会制造新的报错，不能解决问题。
                //
                // 真正缺失的原生功能（如 flock）改为用纯 JS 替代实现，
                // 见 DSHRunner.ensureFlockShim()。容器自带的 bionic 版本
                // （如 @vscode/ripgrep-android-arm64）才是能在 Android 上跑的。
                "--omit=optional",
                "--no-audit",
                "--no-fund",
                // notice 而不是 warn：warn 会把「added N packages」这类收尾统计
                // 一并吞掉，日志里看起来像卡死。notice 足够安静又能看到阶段小结。
                "--loglevel=notice",
                // 关掉 ANSI 进度条：它不是逐行输出，捕获后只会得到一堆乱码
                "--no-progress",
                // ── 网络行为显式化 ────────────────────────────────────
                // 这些默认值本来就会被 npm 采用，但显式写出来有两个好处：
                // 1. 排除用户级 .npmrc 里可能存在的代理/镜像配置干扰；
                // 2. 给 fetch 明确超时，避免在 Android 上无限期挂起。
                "--registry=https://registry.npmjs.org/",
                "--fetch-timeout=60000",
                "--fetch-retries=2",
                "--fetch-retry-mintimeout=2000",
                // 强制走 HTTPS 且不做严格审计，减少一次额外往返
                "--prefer-online"
            )
        }

        val args = listOf(
            "/system/bin/linker64",
            nodePath.absolutePath,
            "--expose-internals",
            cli.absolutePath
        ) + installArgs

        post { installMessage = "正在解析依赖树（可能需要几分钟）…" }
        // 把完整命令打出来：失败时这是最关键的诊断信息
        DSHRunner.onLog("执行: ${args.joinToString(" ")}")

        val pb = ProcessBuilder(args)
        pb.directory(staging)
        pb.redirectErrorStream(true)

        // ── 关键：把子进程 stdin 接到 /dev/null ────────────────────────────
        // Java 的 ProcessBuilder 默认给子进程一个**永不关闭的 stdin 管道**。
        // 只要 npm 或它的某个依赖去读 stdin（例如探测是否 TTY、等待确认），
        // 就会永久阻塞 —— 现象正好是「零输出、零网络流量的假死」。
        // 接 /dev/null 后任何读操作立即返回 EOF，彻底消除这个死锁点。
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        val env = pb.environment()
        env["PREFIX"] = usr.absolutePath
        env["HOME"] = com.appHomeDir(context).absolutePath
        env["TMPDIR"] = File(context.cacheDir, "tmp").apply { mkdirs() }.absolutePath
        env["LD_LIBRARY_PATH"] = File(usr, "lib").absolutePath
        env["PATH"] = "${File(usr, "bin").absolutePath}:/system/bin"
        // 关掉 npm 自身的更新检查，省掉一次网络往返
        env["npm_config_update_notifier"] = "false"
        env["npm_config_fund"] = "false"
        env["npm_config_audit"] = "false"
        // 明确告诉 npm：非交互环境，不要尝试任何交互/提示
        env["npm_config_yes"] = "true"
        env["CI"] = "1"
        // 强制非 TTY 输出模式，避免它去等终端能力探测
        env["TERM"] = "dumb"

        val proc = pb.start()
        currentProc = proc

        // ── 进度采样 ────────────────────────────────────────────────────
        // npm 在 `--no-progress` + `--loglevel=notice` 下，下载阶段完全静默：
        // 要等几百个包全下完才打印 "added N packages"。所以进度不能靠日志，
        // 只能直接量磁盘：
        //   · `$HOME/.npm/_cacache` 的**字节数** → 已下载量（决定进度条 + 速度）
        //   · `staging/node_modules` 的包目录数 → 解压阶段进展
        //
        // 用字节数而不是文件数：字节能算出下载速度，文件数不能。
        val startedAt = System.currentTimeMillis()
        val lastActivityAt = java.util.concurrent.atomic.AtomicLong(startedAt)
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)

        // 静默上限：文件系统整整这么久没有任何新增，才判定卡死。
        //
        // 从 5 分钟提到 10 分钟，因为真机实测「成功的安装」耗时 278 秒
        // （`npm: added 610 packages in 5m`），离 5 分钟上限只差 22 秒。
        // 设备更慢或网络更差时，合法的安装会被误杀 —— 而误杀比多等几分钟
        // 代价高得多（用户要重新下几百个包）。
        //
        // 注意这里判的是「文件系统无增长」而不是「无输出」：
        // npm 在 `--no-progress --loglevel=notice` 下下载阶段完全静默，
        // 只要缓存目录还在变大就视为正常。
        val silenceLimitMs = 10 * 60_000L
        val homeDir = com.appHomeDir(context)
        val cacheDir = File(homeDir, ".npm")

        // 总字节数的自适应估计：基线取 60MB（dsh 依赖树的经验量级）。
        // 实际下载量一旦超过它的 90%，就把估计抬到「实际 / 0.9」，
        // 保证进度条始终停在 90% 出头不会满格，安装真正结束才置 100%。
        val baselineTotal = 60L * 1024 * 1024

        // ★★ 关键：先给 npm 缓存拍一张快照 ★★
        //
        // npm 的缓存 `$HOME/.npm/_cacache` 是**跨安装复用**的，不是每次清空。
        // 装完第一个版本后，它已经堆了几百 MB。若直接拿它的绝对大小当
        // 「本次已下载量」，第二次安装一进来就会被算成「已经下了这么多」，
        // 而 estimatedTotal 的逻辑是「实际超过估计的 90% 就把估计抬到 实际/0.9」，
        // 于是第一秒就得出 progress = 90% —— 这正是「装第二个版本从 90% 开始」。
        //
        // 取快照后只用**增量**，冷缓存（第一次安装）行为不变，
        // 热缓存（第二次起）则是诚实的小增量 —— 实际上热缓存下几乎不用下载，
        // 进度会停在不定态直到解压阶段开始，那才是真实情况。
        val cacheBaseline = dirSize(cacheDir)

        Thread {
            var prevPkg = -1
            var prevBytes = -1L
            var prevSampleAt = startedAt
            var estimatedTotal = baselineTotal

            while (alive.get()) {
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!alive.get()) break

                val pkg = countPackageDirs(staging)
                // 只取增量：缓存是跨安装复用的，绝对值会把上一次的下载量也算进来
                val bytes = (dirSize(cacheDir) - cacheBaseline).coerceAtLeast(0L)
                val now = System.currentTimeMillis()

                // 有增长 = 有真实进展，重置静默计时
                val grew = bytes > prevBytes || pkg > prevPkg
                if (grew) lastActivityAt.set(now)

                // 速度：用两次采样的字节差除以时间差，避免抖动
                val dt = (now - prevSampleAt).coerceAtLeast(1L)
                val speed = if (prevBytes >= 0 && bytes > prevBytes) {
                    (bytes - prevBytes) * 1000 / dt
                } else {
                    0L
                }

                // 总量估计随实际下载量上抬（每超过 90% 就抬高一次）
                if (bytes > estimatedTotal * 0.9) {
                    estimatedTotal = (bytes * 10 / 9).coerceAtLeast(estimatedTotal)
                }

                val elapsed = (now - startedAt) / 1000
                val idle = (now - lastActivityAt.get()) / 1000

                if (now - lastActivityAt.get() > silenceLimitMs) {
                    DSHRunner.onLog("⏱ 文件系统 ${idle}s 无任何新增，判定为卡死")
                    DSHRunner.onLog("   缓存 ${fmtBytes(bytes)}，包数 $pkg")
                    runCatching { proc.destroyForcibly() }
                    break
                }

                post {
                    downloadedBytes = bytes
                    totalBytes = estimatedTotal
                    bytesPerSecond = speed
                    progress = when {
                        // 解压阶段：按包数推进，200 个包视为走完（经验值）
                        pkg > 0 -> (pkg / 200f).coerceIn(0f, 0.97f)
                        // 下载阶段：按真实字节推进
                        bytes > 0 -> (bytes.toFloat() / estimatedTotal)
                            .coerceIn(0f, 0.97f)
                        else -> -1f
                    }
                    installMessage = when {
                        bytes > 0 && pkg == 0 ->
                            "${fmtBytes(bytes)} / ~${fmtBytes(estimatedTotal)}" +
                                speedSuffix(speed) + " · 已运行 ${elapsed}s"
                        pkg > 0 ->
                            "解压中 · 已就位 $pkg 个包 · 已运行 ${elapsed}s"
                        idle > 20 ->
                            "已运行 ${elapsed}s（${idle}s 无新增，可能仍在解析依赖）"
                        else ->
                            "解析依赖树 · 已运行 ${elapsed}s"
                    }
                }

                prevBytes = bytes
                prevPkg = pkg
                prevSampleAt = now
            }
        }.apply { isDaemon = true }.start()

        val tail = ArrayList<String>(6)
        proc.inputStream.bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            // npm 有输出 = 有真实活动，重置静默计时
            lastActivityAt.set(System.currentTimeMillis())
            DSHRunner.onLog("npm: $line")
            if (tail.size >= 6) tail.removeAt(0)
            tail.add(line)
            // 最后一行同步到 UI，让人知道确实在动
            post { installMessage = line.take(80) }
        }
        val code = proc.waitFor()
        alive.set(false)
        currentProc = null
        val seconds = (System.currentTimeMillis() - startedAt) / 1000

        if (cancelled) {
            DSHRunner.onLog("⏹ 安装已取消（耗时 ${seconds}s）")
            return false
        }

        DSHRunner.onLog("npm 结束：退出码 $code，耗时 ${seconds}s")
        if (code != 0) {
            tail.forEach { DSHRunner.onLog("   $it") }
            return false
        }
        return true
    }

    /**
     * 统计 staging/node_modules 下已落地的包目录数。
     *
     * 只扫一层（`@scope` 再展开一层），不递归进包内部 ——
     * 几万个文件的完整统计会拖慢 npm 的 IO，反而影响安装速度。
     * 这个数字只用于让用户看到「有东西在增加」，不需要精确。
     */
    private fun countPackageDirs(staging: File): Int {
        val nm = File(staging, "node_modules")
        if (!nm.isDirectory) return 0
        var n = 0
        nm.listFiles()?.forEach { f ->
            if (!f.isDirectory) return@forEach
            when {
                f.name.startsWith("@") ->
                    n += f.listFiles()?.count { it.isDirectory } ?: 0
                // .bin 是软链目录，不是包
                f.name == ".bin" -> Unit
                else -> n++
            }
        }
        return n
    }

    /**
     * 递归统计目录占用的总字节数。
     *
     * 用于把 npm 缓存（`$HOME/.npm/_cacache`）的大小当作"已下载量"。
     * 用字节而不是文件数，是因为字节能进一步算出下载速度。
     *
     * 这里不限深度：缓存内容是 `_cacache/content-v2/sha512/ab/cd/<hash>`，
     * 必须走到最里层才能拿到文件大小。但只统计文件、跳过目录项，
     * 且失败的文件（读长度时被删）直接忽略，不会抛异常。
     */
    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        var total = 0L
        fun walk(d: File) {
            val items = runCatching { d.listFiles() }.getOrNull() ?: return
            for (f in items) {
                if (f.isDirectory) {
                    walk(f)
                } else {
                    total += runCatching { f.length() }.getOrDefault(0L)
                }
            }
        }
        walk(dir)
        return total
    }

    /** 把字节数格式化成人类可读形式。 */
    private fun fmtBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 ->
            String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L ->
            String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 速度文案；没有速度时返回空串，避免显示「0 B/s」这种噪音。 */
    private fun speedSuffix(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "" else " · ${fmtBytes(bytesPerSecond)}/s"

    /**
     * 推断 npm `--cpu` 的取值。
     *
     * 设备的 ABI 名字和 npm 的 cpu 命名不一样：
     * `arm64-v8a` → `arm64`，`x86_64` → `x64`，必须做这层映射，
     * 否则 `--cpu=arm64-v8a` 会让 npm 找不到任何匹配的平台包。
     */
    private fun npmCpuForDevice(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi.contains("arm64") -> "arm64"
            abi.contains("x86_64") -> "x64"
            abi.contains("armeabi") -> "arm"
            abi.contains("x86") -> "ia32"
            // 兜底走 arm64：绝大多数 Android 设备是这个
            else -> "arm64"
        }
    }

    /**
     * 把 staging 里的**平台可选包**补充进容器。
     *
     * ## 为什么需要这一步
     *
     * 安装时用 `--os=linux --cpu=<arch>` 让 npm 装了平台专属包，
     * 但这些包多数不在 `@deepseek-ai` 作用域下（`@img/`、`@vscode/`…），
     * 而部署阶段只搬 `@deepseek-ai`，于是它们随 staging 一起被删掉，
     * dsh 在运行期仍然找不到原生实现 —— flock 这类报错就是这么来的。
     *
     * ## 只补缺的，不覆盖已有的
     *
     * 容器里已自带 bionic 版本的包（如 `@vscode/ripgrep-android-arm64`），
     * 那是能在 Android 上真正跑起来的实现，优先级必须高于 npm 装来的
     * linux 版本。所以目标位置已存在时直接跳过。
     *
     * ## ★ 有 android 版就跳过 linux 版
     *
     * 这条最关键。平台补丁把 `process.platform` 改成 `linux` 之后，
     * 任何按 `${名字}-${platform}-${arch}` 解析的库都会去找 **linux** 变体。
     * 而 linux 变体是 glibc/musl 二进制，在 bionic 上根本加载不了。
     *
     * 实例：`@vscode/ripgrep` 同时存在 `ripgrep-android-arm64`（容器自带，能跑）
     * 和 `ripgrep-linux-arm64`（npm 装的，跑不了）。若把后者也搬进去，
     * 平台伪装后它会赢，`rg` 直接崩，glob/grep 全废。
     *
     * 所以规则是：**容器里已有同名包的 android 变体时，跳过 linux 变体。**
     * 只有像 `koffi`（没有 android 版）这种才需要 linux 版兜底。
     *
     * @return 实际补充的包数量。
     */
    private fun deployPlatformPackages(staging: File, destRoot: File, cpu: String): Int {
        val nm = File(staging, "node_modules")
        if (!nm.isDirectory) return 0
        val marker = "linux-$cpu"
        val androidMarker = "android-$cpu"
        var copied = 0

        fun copyIfAbsent(src: File, dest: File) {
            if (dest.exists()) return
            runCatching {
                if (!src.renameTo(dest)) {
                    src.copyRecursively(dest, overwrite = false)
                }
                copied++
                DSHRunner.onLog("   + ${dest.name}")
            }
        }

        /**
         * 判断某个 linux 平台包是否该跳过。
         *
         * 把包名里的 `linux-<cpu>` 换成 `android-<cpu>`，看目标作用域里
         * 是否已存在该名字 —— 存在说明容器自带可用实现，linux 版纯属干扰。
         */
        fun hasAndroidVariant(scopeDir: File, pkgName: String): Boolean {
            if (!pkgName.contains(marker)) return false
            val androidName = pkgName.replace(marker, androidMarker)
            return File(scopeDir, androidName).isDirectory
        }

        nm.listFiles()?.forEach { f ->
            if (!f.isDirectory) return@forEach
            if (f.name.startsWith("@")) {
                val destScope = File(destRoot, f.name).apply { mkdirs() }
                f.listFiles()?.forEach { pkg ->
                    if (!pkg.isDirectory || !pkg.name.contains(marker)) return@forEach
                    if (hasAndroidVariant(destScope, pkg.name)) {
                        DSHRunner.onLog("   - 跳过 ${f.name}/${pkg.name}（容器已有 android 版）")
                        return@forEach
                    }
                    copyIfAbsent(pkg, File(destScope, pkg.name))
                }
            } else if (f.name.contains(marker)) {
                if (hasAndroidVariant(destRoot, f.name)) {
                    DSHRunner.onLog("   - 跳过 ${f.name}（容器已有 android 版）")
                    return@forEach
                }
                copyIfAbsent(f, File(destRoot, f.name))
            }
        }
        return copied
    }

    /**
     * 执行 `node --version`，确认容器里的 node 真能跑。
     *
     * npm 对 Node 主版本有硬要求（新版 npm 常要求 >= 22）。版本不符时
     * npm 会直接拒绝启动且输出很短，没有这行日志很难判断是版本问题
     * 还是网络/依赖问题。
     */
    private fun probeNodeVersion(context: Context, usr: File, nodePath: File): String? {
        return runCatching {
            val pb = ProcessBuilder("/system/bin/linker64", nodePath.absolutePath, "--version")
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", File(usr, "lib").absolutePath)
                put("HOME", com.appHomeDir(context).absolutePath)
                put("TMPDIR", File(context.cacheDir, "tmp").apply { mkdirs() }.absolutePath)
                put("PATH", "${File(usr, "bin").absolutePath}:/system/bin")
            }
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            if (code == 0) {
                out.ifBlank { null }
            } else {
                DSHRunner.onLog("node --version 退出码 $code: ${out.take(200)}")
                null
            }
        }.onFailure {
            DSHRunner.onLog("node 探测失败: ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull()
    }

    /**
     * 跑 `<pm> --version` 确认包管理器自身能启动，**带超时**。
     *
     * 这个检查不联网，所以能干净地把两类问题分开：
     * - 返回 null：npm 在此环境下起不来（TLS 库缺失、写缓存失败、参数被拒…）
     * - 返回版本号：npm 是好的，后续卡住就是依赖解析或网络问题
     *
     * 超时是必需的：实测 npm 会**零输出地无限挂起**，
     * 不给超时这个探测本身就会变成第二个卡死点。
     */
    private fun probeNpmVersion(
        context: Context,
        usr: File,
        nodePath: File,
        cli: File,
        pmName: String
    ): String? {
        val timeoutMs = 30_000L
        return runCatching {
            val pb = ProcessBuilder(
                "/system/bin/linker64",
                nodePath.absolutePath,
                "--expose-internals",
                cli.absolutePath,
                "--version"
            )
            pb.redirectErrorStream(true)
            // 与正式安装保持一致：stdin 接 /dev/null，避免读 stdin 时永久阻塞。
            // 预检若因同一个死锁而超时，会误报成「npm 起不来」，把结论带偏。
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            pb.environment().apply {
                put("PREFIX", usr.absolutePath)
                put("HOME", com.appHomeDir(context).absolutePath)
                put("TMPDIR", File(context.cacheDir, "tmp").apply { mkdirs() }.absolutePath)
                put("LD_LIBRARY_PATH", File(usr, "lib").absolutePath)
                put("PATH", "${File(usr, "bin").absolutePath}:/system/bin")
                put("npm_config_update_notifier", "false")
                put("npm_config_yes", "true")
                put("CI", "1")
                put("TERM", "dumb")
            }

            val p = pb.start()
            currentProc = p

            // 后台读输出，主流程只等超时
            val out = StringBuilder()
            val reader = Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (out.length < 2000) out.append(line).append('\n')
                    }
                }
            }.apply { isDaemon = true }
            reader.start()

            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                DSHRunner.onLog("⏱ $pmName --version 超过 ${timeoutMs / 1000}s 无响应，强制结束")
                if (out.isNotBlank()) {
                    DSHRunner.onLog("   已捕获输出: ${out.toString().trim().take(300)}")
                } else {
                    DSHRunner.onLog("   未捕获任何输出（进程在初始化阶段就挂住了）")
                }
                runCatching { p.destroyForcibly() }
                currentProc = null
                return null
            }

            val code = p.exitValue()
            currentProc = null
            val text = out.toString().trim()
            if (code == 0 && text.isNotBlank()) {
                text.lineSequence().last().trim()
            } else {
                DSHRunner.onLog("$pmName --version 退出码 $code")
                if (text.isNotBlank()) DSHRunner.onLog("   输出: ${text.take(300)}")
                null
            }
        }.onFailure {
            DSHRunner.onLog("$pmName 预检异常: ${it.message ?: it.javaClass.simpleName}")
            currentProc = null
        }.getOrNull()
    }
}
