package com

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.fcldsh.AccountStore
import com.example.fcldsh.AgentGuide
import com.example.fcldsh.DshProviderConfig
import com.example.fcldsh.FsLocalPatch
import com.example.fcldsh.Instances
import com.example.fcldsh.LauncherPrefs
import com.example.fcldsh.OpenSslEnv
import com.example.fcldsh.PermissionMode
import com.example.fcldsh.PrivMode
import com.example.fcldsh.Privilege
import com.example.fcldsh.PtySupport
import com.example.fcldsh.RequireBuiltinShim
import com.example.fcldsh.SandboxBackend
import com.example.fcldsh.ShellEnvFix
import com.example.fcldsh.ShizukuBridge
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

object DSHRunner {
    var onLog: (String) -> Unit = {}
    var onUrlReady: (String) -> Unit = {}

    /**
     * 进程退出回调。**在后台线程被调用**，调用方自己 post 到主线程。
     *
     * ## 为什么必须存在
     *
     * 之前启动失败的收场是这样的：stdout 循环读到流关闭退出，只写一行日志
     * 「Node 输出流已关闭（进程已退出）」，**没有任何状态回调**。
     * 而 runState 是 startInstance() 在起进程之前置成 STARTING 的 ——
     * 于是进程已经崩了，界面还停在「启动中」，永远不会自己恢复。
     *
     * 症状就是「点启动，一直转圈」，用户既看不到失败原因，也没法再点启动
     *（STARTING 被当成存活状态，按钮会挡掉重复点击）。
     *
     * @param intentional 是否由用户主动停止（[stopProcess]）导致。
     *   false 表示进程自己退了 —— 那就是崩溃，界面要切到 ERROR。
     * @param exitCode 退出码；拿不到时是 null。
     */
    var onExited: ((intentional: Boolean, exitCode: Int?) -> Unit)? = null

    /** 当前 Node 进程。阶段一只是个单例句柄，阶段三会随实例一起管理。 */
    @Volatile
    private var process: Process? = null

    /**
     * 是否正处于「用户主动停止」流程中。
     *
     * 用来区分两种退出：用户点了停止/重启/删实例，还是 Node 自己崩了。
     * 前者界面应回到「已停止」，后者应显示「异常」并给出原因 ——
     * 两者都用同一句「进程已退出」的话会让用户以为是自己点错了。
     *
     * 置位在 [stopProcess]，复位在 [startNode] 起新进程时：
     * 不能在这里一返回就复位，因为读流的线程是异步收尾的，
     * 复位太早会把「主动停止」误判成「崩溃」。
     */
    @Volatile
    private var stopping = false

    /** [recentOutput] 的条数上限。够放下一个堆栈的前几行即可。 */
    private const val RECENT_LIMIT = 60

    /** 最近若干行输出（stdout + stderr 合流），崩溃时用来告诉用户原因。 */
    private val recentOutput = ArrayList<String>(RECENT_LIMIT)

    /**
     * 记一行输出，环形丢弃最旧的。
     *
     * 名字刻意**不叫** `remember`：那会和 Compose 的
     * `androidx.compose.runtime.remember` 撞名，编译器会把它解析成
     * `@Composable` 那个版本，报「@Composable invocation can only happen
     * from the context of a @Composable function」。
     */
    private fun rememberOutput(line: String) {
        synchronized(recentOutput) {
            recentOutput.add(line)
            while (recentOutput.size > RECENT_LIMIT) recentOutput.removeAt(0)
        }
    }

    /**
     * 最近 [n] 行输出，最旧的在前。
     *
     * 给界面用：启动失败时把最后几行贴进日志，用户就不必自己去翻几百行 ERR。
     */
    fun recentLines(n: Int): List<String> = synchronized(recentOutput) {
        val from = (recentOutput.size - n).coerceAtLeast(0)
        recentOutput.subList(from, recentOutput.size).toList()
    }

    /** 当前实例实际监听的端口。0 表示未启动。 */
    @Volatile
    var currentPort: Int = 0
        private set

    /**
     * 停止 Node。
     *
     * 分两步：先 `destroy()` 给进程一个优雅退出的机会（让 dsh 写完会话文件），
     * 超时后若还活着就强制结束。
     *
     * 早期只调 `destroy()` 是不够的 —— 实测会出现端口 3080 仍被占用、
     * 下次启动报 `EADDRINUSE` 的情况，说明进程没有真正退出。
     *
     * 注意 `waitFor(timeout, unit)` 与 `destroyForcibly()` 都是 **API 26+**，
     * 而本项目 minSdk 是 23，所以两者都要版本守卫（见 [waitForExit] / [forceKill]）。
     */
    fun stopProcess() {
        val p = process
        process = null
        val port = currentPort
        currentPort = 0

        // ── 刻意**不**在这里关掉 Shizuku 通道 ────────────────────────────
        //
        // 早先这里会 `ShizukuBridge.stop()`，理由是「引擎停了就没人用 shz」。
        // 那造成了一个很隐蔽的坑：用户为了跑 `shz id` 去启动引擎，停止后
        // 通道被关、状态文件写成 `port: 0`，于是 `shz` 又变成「通道不可用」——
        // 一个本该与引擎无关的能力，被引擎生命周期牵着走。
        //
        // 现在桥的生命周期只跟 **Shizuku 是否可用** 绑定（见
        // [com.example.fcldsh.ShizukuBridge.watchState] / `reconcile`）：
        // 引擎停了也不关，引擎起来也不重绑（幂等）。
        if (p == null) {
            onLog("⏹ 没有正在运行的进程")
            if (port != 0) onLog("   （上次记录的端口 $port 将自动避开）")
            return
        }

        // 先置位再 destroy：读流的线程可能在我们 waitFor 期间就收尾了，
        // 那时它要能读到 true，才不会把「用户主动停止」误报成崩溃。
        stopping = true

        runCatching { p.destroy() }

        if (waitForExit(p, 1500)) {
            onLog("⏹ Node 进程已退出")
            return
        }

        onLog("⏹ Node 未在 1.5 秒内退出，强制结束")
        forceKill(p)
        // 再等一次，尽量确保端口释放后再返回
        waitForExit(p, 1000)
    }

    // ------------------------------------------------------------------
    // 引擎状态自检
    // ------------------------------------------------------------------

    /**
     * 启动时打印引擎的真实状态。
     *
     * ## 为什么需要它
     *
     * 排查 flock 时反复卡在同一个盲点：**看不到设备上引擎的实际组成**。
     * 之前所有关于"平台包有没有装上"的推断都无从验证。
     * 这里在每次启动时顺手打几行，不额外跑进程、只列目录，开销可忽略。
     *
     * 输出三样东西：
     * 1. dsh 版本 —— 确认装的是哪一版
     * 2. 平台专属包 —— `linux-arm64` / `android-arm64` 这类包是否存在，
     *    直接验证「`--os/--cpu` 到底生效没有」
     * 3. 原生模块（`.node`）—— 判断是否有需要编译的包
     */
    private fun logEngineState(context: Context) {
        // 多版本共存：先报「装了哪些、在用哪个」，再单独看当前版本的内容
        val vers = installedVersions(context)
        val cur = currentVersion(context)
        onLog("引擎版本: ${cur ?: "未安装"}")
        if (vers.size > 1) {
            onLog("已装版本(${vers.size}): ${vers.joinToString(", ")}")
        }
        if (cur == null) return

        val nm = File(engineRoot(context, cur), "node_modules")
        if (!nm.isDirectory) {
            onLog("引擎目录缺失: ${nm.absolutePath}")
            return
        }

        val markers = listOf("linux-arm64", "linux-x64", "android-arm64", "android-x64")
        val platformPkgs = ArrayList<String>(16)
        var nativeCount = 0

        fun walk(dir: File, depth: Int) {
            if (depth > 6) return
            val items = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in items) {
                if (f.isDirectory) {
                    if (f.name == "npm" || f.name == "corepack") continue
                    // 记录平台包的名字（只取包目录这一层）
                    if (markers.any { f.name.contains(it) }) {
                        val parent = f.parentFile?.name.orEmpty()
                        platformPkgs.add(if (parent.startsWith("@")) "$parent/${f.name}" else f.name)
                    }
                    walk(f, depth + 1)
                } else if (f.name.endsWith(".node")) {
                    nativeCount++
                }
            }
        }
        walk(nm, 0)

        if (platformPkgs.isEmpty()) {
            onLog("平台专属包: 无 ← 这正是 flock 报错的常见原因")
        } else {
            // 只打前 8 个，避免日志被刷屏
            onLog("平台专属包(${platformPkgs.size}): " + platformPkgs.take(8).joinToString(", "))
        }
        onLog("原生模块(.node): $nativeCount 个")

        checkEngineDeps(context, cur, nm)
    }

    /**
     * 引擎依赖闭包自检：找出「声明了但解析不到」的第三方包。
     *
     * ## 为什么需要这个检查
     *
     * 旧版安装器只把 `staging/node_modules/@deepseek-ai` 搬到版本目录，
     * 传递依赖（hosted-git-info、birpc…）随 staging 一起被删掉。
     * 结果引擎能启动、网页能打开，但内置插件全部 import 失败：
     *
     * ```
     * 本部署没有可管理的 profile，无法安装或启停插件
     * 新增会话失败：waiting for pluginManager → workflowEngine
     * ```
     *
     * 这两句报错完全看不出「少了个 npm 包」，排查成本极高。
     * 所以在启动时就地比对 package.json 的 dependencies 与实际目录，
     * 把「缺哪几个包」直接写进日志，并给出可执行的修复动作。
     *
     * 只读几十个小的 package.json，开销可以忽略。
     */
    private fun checkEngineDeps(context: Context, version: String, modulesDir: File) {
        val scope = File(modulesDir, "@deepseek-ai")
        if (!scope.isDirectory) return
        // 容器自带的包也算可解析（Node 会沿解析链往上找到 usr/lib/node_modules）
        val containerModules = File(usrDir(context), "lib/node_modules")

        val missing = LinkedHashSet<String>()
        val pkgs = runCatching { scope.listFiles() }.getOrNull() ?: return
        for (p in pkgs) {
            if (!p.isDirectory) continue
            val pkgJson = File(p, "package.json")
            if (!pkgJson.isFile) continue
            val text = runCatching { pkgJson.readText() }.getOrNull() ?: continue
            val deps = runCatching {
                org.json.JSONObject(text).optJSONObject("dependencies")
            }.getOrNull() ?: continue
            val keys = runCatching { deps.keys() }.getOrNull() ?: continue
            while (keys.hasNext()) {
                val name = keys.next()
                // 同作用域的包必然就在这一层，不需要检查
                if (name.startsWith("@deepseek-ai/")) continue
                val here = File(modulesDir, name).isDirectory
                val inContainer = File(containerModules, name).isDirectory
                if (!here && !inContainer) missing.add(name)
            }
        }

        if (missing.isEmpty()) {
            onLog("依赖闭包: 完整")
        } else {
            onLog("❌ 依赖缺失(${missing.size}): " + missing.take(8).joinToString(", "))
            if (missing.size > 8) onLog("   （其余 ${missing.size - 8} 个省略）")
            onLog("❌ 原因是旧版安装器只搬了 @deepseek-ai、丢掉了依赖闭包")
            onLog("❌ 到下载中心重新安装 $version 即可修复（新版会整棵搬迁）")
        }
    }

    // ------------------------------------------------------------------
    // 诊断工具
    // ------------------------------------------------------------------

    /**
     * 打印平台信息与 flock 实现的当前状态。
     *
     * 平台值保持真实的 `android`（不再伪装成 linux）——伪装会让 native
     * 模块去加载 linux 二进制，而 bionic 的 ABI 不兼容，只会把
     * 「平台不支持」换成「dlopen 失败」。
     *
     * 真正起作用的是 [ensureFlockShim] 是否已把 flock.js 替换掉，
     * 所以这里也顺带报告它的状态。
     */
    fun diagnosePlatform(context: Context) {
        val usr = usrDir(context)
        val nodePath = File(usr, "bin/node")
        if (!nodePath.exists()) {
            onLog("❌ 容器未就绪，无法诊断")
            return
        }

        onLog("──── 平台诊断 ────")
        val script = "console.log(JSON.stringify({" +
            "platform:process.platform," +
            "arch:process.arch," +
            "node:process.versions.node" +
            "}))"
        runNode(context, usr, nodePath, listOf("-e", script))?.let { out ->
            onLog("node 报告: $out")
            if (out.contains("\"platform\":\"android\"")) {
                onLog("→ 保持 android 是预期行为（伪装成 linux 会导致原生模块加载失败）")
            }
        }

        // flock 当前用的是原生模块还是我们的 JS 实现
        val flockJs = File(
            usr,
            "lib/node_modules/@deepseek-ai/node-addon-system/lib/flock.js"
        )
        when {
            !flockJs.isFile ->
                onLog("flock 实现: 未安装（引擎里没有 node-addon-system）")
            runCatching { flockJs.readText() }.getOrNull()
                ?.contains(FLOCK_SHIM_MARKER) == true ->
                onLog("flock 实现: 纯 JS 替代版（正常）")
            else ->
                onLog("flock 实现: 上游原生版 ← 在 Android 上会失败，下次启动会被替换")
        }
    }

    /** 跑一个 node 脚本并返回 stdout（同步，供诊断用）。 */
    private fun runNode(
        context: Context,
        usr: File,
        nodePath: File,
        args: List<String>
    ): String? = runCatching {
        val pb = ProcessBuilder(
            listOf("/system/bin/linker64", nodePath.absolutePath) + args
        )
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("PREFIX", usr.absolutePath)
            // 跟着实例走：npm / node 的 HOME 必须和它将要写入的那份数据同源
            put("HOME", instanceHome(context).absolutePath)
            put("TMPDIR", File(context.cacheDir, "tmp").apply { mkdirs() }.absolutePath)
            put("LD_LIBRARY_PATH", File(usr, "lib").absolutePath)
            put("PATH", "${File(usr, "bin").absolutePath}:/system/bin")
            // 与引擎进程同一份 OpenSSL 配置：诊断脚本里也可能 require('crypto')，
            // 环境不一致时会出现「引擎正常、诊断挂」的假象。
            putAll(OpenSslEnv.ensure(usr.absolutePath, onLog))
        }
        val p = pb.start()
        // 关闭子进程 stdin，让任何读 stdin 的操作立即拿到 EOF。
        // 不用 `ProcessBuilder.Redirect.from(File)`：那是 API 26+，
        // 而本项目 minSdk 23，老设备上会 NoSuchMethodError。
        // 直接关流在 API 1 就有，语义完全等价。
        runCatching { p.outputStream.close() }
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.ifBlank { null }
    }.getOrNull()

    /**
     * 带超时等待进程退出，返回是否已退出。
     *
     * API 26+ 有原生的 `waitFor(timeout, unit)`；23-25 没有，
     * 用守护线程跑阻塞版 `waitFor()` 再 `join(timeout)` 达到同样效果。
     */
    private fun waitForExit(p: Process, timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return runCatching { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
        }

        val finished = AtomicBoolean(false)
        val waiter = Thread {
            runCatching { p.waitFor() }
            finished.set(true)
        }
        waiter.isDaemon = true
        waiter.start()
        runCatching { waiter.join(timeoutMs) }
        return finished.get()
    }

    /**
     * 强制结束进程。
     *
     * API 26+ 用 `destroyForcibly()`（发 SIGKILL）；23-25 没有这个方法，
     * 只能再发一次 `destroy()`。真强杀需要进程 pid，而 `Process.pid()`
     * 同样是 API 26+，不做反射取巧 —— 老版本上若仍杀不掉，
     * 靠 [allocatePort] 换端口兜底，不影响下次启动。
     */
    private fun forceKill(p: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { p.destroyForcibly() }
        } else {
            runCatching { p.destroy() }
        }
    }

    // ------------------------------------------------------------------
    // 端口分配
    // ------------------------------------------------------------------

    /**
     * 判断端口是否空闲。
     *
     * 绑定 `127.0.0.1` 而不是 `0.0.0.0`：dsh 只监听回环，
     * 用通配地址探测会漏掉「仅回环被占用」的情况（正是 EADDRINUSE 的典型情形）。
     */
    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket().use { s ->
            s.reuseAddress = false
            s.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 从 [start] 起找一个可用端口。
     *
     * ## 为什么必须这么做
     *
     * 之前端口写死 3080。只要出现「上次的 Node 没被彻底杀掉」的情况
     * （App 被划掉、系统杀进程、stop 时 destroy 生效慢），
     * 下次启动就会撞上 `listen EADDRINUSE: address already in use 127.0.0.1:3080`，
     * dsh 直接 startup failed，用户看到的就是一片报错。
     *
     * 自动避让能把这个死结化开：新进程换个端口照常起来，
     * 那个残留进程用户重启手机后自然消失。
     *
     * @return 可用端口；连试 [maxTries] 个都占用时返回 0（调用方需处理）。
     */
    private fun allocatePort(start: Int = 3080, maxTries: Int = 20): Int {
        for (p in start until start + maxTries) {
            if (isPortFree(p)) return p
            onLog("   端口 $p 已被占用，尝试下一个")
        }
        return 0
    }

    // ------------------------------------------------------------------
    // 内置容器 / 后天下载引擎
    // ------------------------------------------------------------------

    /** 内置容器在 APK 里的路径。首次启动时从这里解压到 filesDir/usr。 */
    private const val ASSET_RUNTIME = "runtime.zip"

    /** 容器安装目录（相对 filesDir）。 */
    private const val USR = "usr"

    /** 引擎入口（相对**版本目录**）。存在即视为该版本可用。 */
    private const val ENGINE_MARKER = "node_modules/@deepseek-ai/dsh/lib/bin.js"

    /**
     * 多版本目录，相对 usr。
     *
     * ## 为什么是 `lib/versions/` 而不是 `usr/versions/`
     *
     * dsh 的第三方依赖（zod、commander、undici…）留在内置容器的
     * `usr/lib/node_modules` 里，引擎包只带 `@deepseek-ai` 作用域这一棵子树。
     * Node 解析模块时从 `bin.js` 所在目录**逐级往上**找 `node_modules`：
     *
     * ```
     * usr/lib/versions/<ver>/node_modules/@deepseek-ai/dsh/lib/bin.js
     *   →  …/versions/<ver>/node_modules   ✅ 引擎包自己
     *   →  …/versions/node_modules
     *   →  usr/lib/node_modules             ✅ 容器的第三方依赖
     * ```
     *
     * 若放在 `usr/versions/<ver>/node_modules/`，往上只会走到
     * `usr/node_modules`，**永远看不到 `usr/lib/node_modules`** ——
     * dsh 启动瞬间就报 `Cannot find package 'zod'`。
     * 位置不是随便挑的，是被模块解析路径钉死的。
     */
    private const val VERSIONS_REL = "lib/versions"

    /** 本地 zip 安装的临时目录名；读到版本号后才改成正式名字。 */
    private const val INCOMING = "__incoming__"

    /**
     * 整合包里「数据目录」条目的暂存目录名。
     *
     * 与 [INCOMING] 平级而不是嵌在里面：引擎暂存目录最后会被整体改名成
     * `lib/versions/<ver>`，数据若暂存在其中就会跟着进版本目录 —— 错位。
     */
    private const val INCOMING_DATA = "__incoming_data__"

    /**
     * 整合包里数据目录条目的前缀。
     *
     * 写入 zip 时形如 `dsh-data/plugins/foo/index.js`，
     * 导入时剥掉这个前缀落到 `home/.dsh-<版本>/plugins/foo/index.js`。
     *
     * 用独立前缀而不是把数据混进 `node_modules/` 里，是因为导入侧按
     * 「第一个 node_modules/」归一化引擎条目，混在一起会互相误判。
     */
    const val DATA_PREFIX = "dsh-data/"

    /**
     * 导入时必须丢弃的「密钥载体」文件名（相对 `home/.dsh-<版本>`）。
     *
     * ## 为什么导入端也要管
     *
     * 导出侧（[com.example.fcldsh.EngineExporter]）已经会剔除这两个文件，
     * 但**用户手里的包可能是旧版本应用导出的** —— 那时它们会原样进包。
     * 导入方如果照单全收，就会在毫无察觉的情况下用上别人的密钥：
     * 花钱的是对方的账号，出问题也查不到源头。
     *
     * | 文件 | 内容 |
     * |---|---|
     * | `.env` | `DEEPSEEK_API_KEY=sk-…` 这类明文密钥 |
     * | `android-account.json` | 全部账号，每个都带 apiKey 明文 |
     *
     * 删掉不影响可用性：[com.example.fcldsh.AccountStore] 下次启动会用
     * 本机自己的账号重新生成；本机没账号就等用户填。
     *
     * 显式标注 `List<String>` 是必需的：无类型推断时 `for (x in …)`
     * 会因为 `iterator()` 重载过多而报 ambiguous。
     */
    private val PACKAGE_SECRET_FILES: List<String> = listOf(
        ".env", "android-account.json"
    )

    private fun usrDir(context: Context) = File(context.filesDir, USR)

    /**
     * 引擎版本根目录：`filesDir/usr/lib/versions`。
     *
     * 不再按实例嵌套 —— 每个版本就是这里的一个子目录，目录名即「版本键」
     * （同版本并存时带 `-2` 后缀，见 [Instances.allocateVersion]）。
     */
    private fun versionsRoot(context: Context): File =
        File(usrDir(context), VERSIONS_REL)

    /**
     * 对外的版本根目录。
     *
     * [EngineInstaller] 也要往这里落盘；它不能自己拼 `filesDir/usr/lib/versions`，
     * 否则实例模式下会把引擎装进老路径 —— 那个目录当前实例根本不看。
     */
    fun versionsDir(context: Context): File {
        val root = versionsRoot(context)
        root.mkdirs()
        return root
    }

    /** 某个版本的根目录（其下是 `node_modules/`）。 */
    fun engineRoot(context: Context, version: String): File =
        File(versionsRoot(context), version)

    /**
     * 旧版（所有版本共用）的数据目录。
     *
     * 现在只用于**一次性迁移**：把老数据搬进当前版本的独立目录。
     * 见 [ensureVersionDataDir]。
     */
    private fun legacyDshHome(context: Context) =
        File(appHomeDir(context), ".dsh")

    /**
     * 当前版本自己的数据目录（进程里的 `DSH_HOME`）。
     *
     * ## 布局：每个版本一份，互不相通
     *
     * ```
     * home/.dsh-<版本>     ← 会话 / 凭据 / 插件 / MCP 配置都在这
     * ```
     *
     * 换版本就是换一整套数据，这正是「各个 dsh 不互通」的实现。
     *
     * ## 为什么放在 `.dsh` 的**兄弟**位置，而不是子目录
     *
     * dsh 会拿 `dirname(DSH_HOME)` 当 HOME 去推导 Documents 和插件根。
     * 早先试过 `home/.dsh/versions/<ver>`（嵌套），那个「HOME」变成了
     * `home/.dsh/versions` —— 工作区建不起来、插件列表全空。
     * 放在兄弟位置后，两种布局下 `dirname(DSH_HOME)` 都是同一个 `home`。
     *
     * ## ★ 一度误判，现已纠正 ★
     *
     * 中间有一版把「本部署没有可管理的 profile / waiting for workflowEngine」
     * 归罪于「切了 DSH_HOME」，于是回退成共用目录。真实原因在日志里：
     *
     * ```
     * Cannot find package 'hosted-git-info' imported from dsh-plugin-manager
     * Cannot find package 'birpc'            imported from dsh-ptc-runtime-node
     * ```
     *
     * 是安装器只搬了 `@deepseek-ai`、把传递依赖丢了，plugin-manager 连 import
     * 都失败 —— profile 页自然什么都列不出来。依赖闭包修好后（整棵 node_modules
     * 一起搬），按版本切目录是安全的。
     */
    fun dshHome(context: Context): File {
        val ver = currentVersion(context) ?: return legacyDshHome(context)
        return File(instanceHome(context), ".dsh-$ver")
    }

    /**
     * 进程的 HOME：`filesDir/home`。
     *
     * 所有版本共用同一个 HOME；「互不相通」由 `$DSH_HOME`
     * （`home/.dsh-<版本键>`）那一层区分，不靠切 HOME。
     *
     * **所有「HOME 下的某个路径」都必须经由这里推导** ——
     * 各自拼 `filesDir` 会让工作区与 `$DSH_HOME` 落到不同目录。
     */
    fun instanceHome(context: Context): File = appHomeDir(context)

    /**
     * 起进程前确保当前版本的数据目录就绪；老用户第一次升级时迁移一次旧数据。
     *
     * ## 为什么只迁移一次
     *
     * 「各版本不互通」是默认行为，但老用户的数据全在共用的 `home/.dsh` 里。
     * 直接换目录会表现为「会话和登录态全没了」，所以第一次启动时把老目录复制进
     * **当时正在用**的那个版本，然后置位 [LauncherPrefs.setDataMigrated]。
     * 之后新装的版本一律从空目录开始 —— 既没丢老数据，也不会把老会话带进新版本。
     *
     * 用复制而不是改名，是为了迁移失败时老目录仍然完好、下次还能重试。
     */
    fun ensureVersionDataDir(context: Context): File {
        val target = dshHome(context)
        val ver = currentVersion(context)

        // 没有可用版本时 dshHome 返回旧目录，直接建好即可，不涉及迁移
        if (ver == null || target == legacyDshHome(context)) {
            return target.apply { mkdirs() }
        }
        if (target.isDirectory && target.list()?.isNotEmpty() == true) return target

        val legacy = legacyDshHome(context)
        val shouldMigrate = !LauncherPrefs.isDataMigrated(context) &&
            legacy.isDirectory && (legacy.list()?.isNotEmpty() == true)

        if (!shouldMigrate) {
            target.mkdirs()
            return target
        }

        onLog("📦 首次启用按版本独立数据目录：正在迁移旧数据到 ${target.name} …")
        val copied = runCatching { copyTree(legacy, target) }.getOrElse { e ->
            onLog("⚠️ 迁移失败（${e.message ?: e.javaClass.simpleName}），本次回退到旧目录")
            // 不置迁移标记，下次启动再试
            return legacy.apply { mkdirs() }
        }
        LauncherPrefs.setDataMigrated(context, true)
        onLog("✅ 迁移完成：${copied / 1024} KB → ${target.name}")
        return target.apply { mkdirs() }
    }

    /**
     * 递归复制目录，返回复制的字节数。
     *
     * 符号链接直接跳过：数据目录里不该有链接，真遇到也不值得为它冒
     * 无限递归的风险（[isSymlink] 只判断链接本身、不跟随）。
     */
    private fun copyTree(src: File, dst: File): Long {
        var bytes = 0L
        val kids = src.listFiles() ?: return 0L
        if (!dst.isDirectory && !dst.mkdirs()) {
            throw java.io.IOException("无法创建 ${dst.absolutePath}")
        }
        for (f in kids) {
            if (isSymlink(f)) continue
            val target = File(dst, f.name)
            if (f.isDirectory) {
                bytes += copyTree(f, target)
            } else if (f.isFile) {
                f.inputStream().use { input ->
                    target.outputStream().use { output ->
                        bytes += input.copyTo(output, 64 * 1024)
                    }
                }
            }
        }
        return bytes
    }

    /**
     * 这一项**本身**是不是符号链接（不跟随）。
     *
     * 不能用 `canonicalPath != absolutePath` 判断：`filesDir` 给的是
     * `/data/user/0/<pkg>/…`，真实路径是 `/data/data/<pkg>/…`，
     * 那个条件对整个 App 里的每个条目都成立 —— 导出器被它坑成 0 字节包。
     */
    private fun isSymlink(f: File): Boolean = runCatching {
        val st = android.system.Os.lstat(f.absolutePath)
        android.system.OsConstants.S_ISLNK(st.st_mode)
    }.getOrDefault(false)

    /**
     * 工作区根目录（`Documents` 那一层）。dsh 的默认工作区落在
     * `<Documents>/deepseek-harness`。
     *
     * 挂在**实例的 HOME** 下：同一版本的两个实例连工作区都不该共用 ——
     * 否则在 A 里新建的会话，在 B 里会看到同一批项目文件，
     * 「完全不互通」就不成立了。没有实例时回落到老的 `filesDir/home/Documents`。
     */
    fun workspaceDir(context: Context): File =
        File(instanceHome(context), "Documents")

    /**
     * 删除一个引擎版本：引擎目录 + 它自己的数据目录。
     *
     * 「删除该版本」按钮的实现。三件事必须做对：
     *
     * 1. **绝不碰旧目录**：老布局下多个版本共用一个 `home/.dsh`，
     *    删其中一个版本时若连带删它，别的版本数据就一起没了。
     * 2. **删的是当前版本时清掉指针**，让「当前版本」明确变成另一个已装版本
     *    或「未安装」，而不是靠 `currentVersion` 的失效回落兜底。
     * 3. node_modules 有上万个文件，所以要按条目汇报进度，
     *    否则用户面对的是一个卡住不动的界面。
     *
     * @param onProgress 0f..1f 进度回调，可能在后台线程被调用。
     * @return 是否成功（目录本来就不存在也算成功）。
     */
    fun deleteVersion(context: Context, version: String, onProgress: (Float) -> Unit): Boolean {
        val engine = engineRoot(context, version)
        // 只删这个版本自己的数据目录，绝不碰 legacyDshHome
        val data = File(instanceHome(context), ".dsh-$version")

        var total = 0
        fun count(dir: File) {
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in kids) {
                total++
                if (f.isDirectory) count(f)
            }
        }
        count(engine)
        count(data)

        var done = 0
        fun remove(dir: File) {
            if (!dir.exists()) return
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in kids) {
                if (f.isDirectory && !isSymlink(f)) {
                    remove(f)
                    runCatching { f.delete() }
                } else {
                    runCatching { f.delete() }
                }
                done++
                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
            runCatching { dir.delete() }
        }

        onLog("🗑 正在删除引擎 $version …")
        remove(engine)
        remove(data)
        // 附件目录现在软链在公开位置（见 AttachmentsStorage.kt），
        // 不在 data 子树里，所以上面那个递归删不到它。这里单独清掉，
        // 只删本版本那一层，绝不碰共享根目录下的其它版本。
        runCatching { removeAttachmentsForVersion(context, version) }
        val gone = !engine.exists() && !data.exists()

        // ── 同步实例元数据 ★ 关键 ★ ────────────────────────────────────
        //
        // 「这个实例装了哪个版本」记在 instance.json 里，而上面删的是**目录**。
        // 不同步的话，版本管理面板仍会按旧元数据列出这个实例 ——
        // 看起来有这一项，选中却启动不了（引擎已经不在了）。
        //
        // 这里把版本号清空，实例本身保留：用户可能只是想换一个版本，
        // 直接删实例会把他的工作区与会话一起带走，那是另一种破坏。
        runCatching {
            val id = Instances.activeId(context)
            if (id != null) {
                val inst = Instances.byId(context, id)
                if (inst != null && inst.version == version) {
                    Instances.setVersion(context, id, "")
                    onLog("ℹ️ 实例「${inst.name}」已清空版本记录（引擎已删除，可重新安装）")
                }
            }
        }

        // 指针清理由 LauncherPrefs 负责，这里不硬编码 SharedPreferences 键名
        if (LauncherPrefs.getEngineVersion(context) == version) {
            val rest = installedVersions(context)
            if (rest.isNotEmpty()) {
                setCurrentVersion(context, rest.first())
                onLog("ℹ️ 当前版本已切换为 ${rest.first()}")
            } else {
                LauncherPrefs.clearEngineVersion(context)
                onLog("ℹ️ 已无可用引擎版本")
            }
        }

        if (gone) {
            onLog("✅ 已删除引擎 $version（含其数据目录）")
        } else {
            onLog("⚠️ 删除 $version 未完全成功，可能有文件被占用")
        }
        return gone
    }

    /**
     * 清掉迁移过程留下的临时目录（`.seed-tmp`）。
     *
     * ## ★ 绝不能再删 `.dsh-*` ★
     *
     * 上一版这个函数把 `home/.dsh-<ver>` 当垃圾删掉 —— 那时数据目录还是共用的，
     * 所以「删掉」只是清理残骸。但现在 `.dsh-<ver>` **就是**每个版本的活数据目录，
     * 再删它等于把用户的会话、凭据、插件配置一键清空。
     *
     * 所以现在只删明确是临时产物的 `.seed-tmp`，其余一概不碰。
     *
     * 返回删除的目录个数（供日志用）。
     */
    fun cleanupLegacyIsolation(context: Context): Int {
        val home = appHomeDir(context)
        val tmp = File(home, ".seed-tmp")
        if (!tmp.isDirectory) return 0
        return if (runCatching { tmp.deleteRecursively() }.getOrDefault(false)) 1 else 0
    }

    /** 引擎入口文件；版本为空时返回 null。 */
    private fun engineEntry(context: Context, version: String?): File? =
        if (version.isNullOrBlank()) null
        else File(engineRoot(context, version), ENGINE_MARKER)

    /** 内置容器（Node 运行时）是否已解压完成。 */
    fun runtimeInstalled(context: Context): Boolean {
        val node = File(usrDir(context), "bin/node")
        return node.exists() && node.length() > 0L
    }

    /**
     * 扫描磁盘上所有可用的引擎版本，新的排在前面。
     *
     * 只认「入口文件真实存在且非空」的目录 —— 安装中途失败、或被手动删掉一半
     * 的版本不该出现在列表里，否则用户选中它只会换回一次启动失败。
     */
    fun installedVersions(context: Context): List<String> {
        val dirs = runCatching { versionsRoot(context).listFiles() }
            .getOrNull() ?: return emptyList()
        val out = ArrayList<String>(4)
        for (d in dirs) {
            if (!d.isDirectory || d.name == INCOMING) continue
            val entry = File(d, ENGINE_MARKER)
            if (entry.isFile && entry.length() > 0L) out.add(d.name)
        }
        // 按语义排序，新的在前。
        //
        // ★ 不能再用字符串排序再 reverse ★
        // 字符串序会把 `0.2.1-rc.10` 排在 `0.2.1-rc.2` **之前**（'1' < '2'），
        // 于是「最新」被算错，反向再一翻就彻底乱了。
        // 下面的比较器逐段比数字，才是真正的版本序。
        out.sortWith(Comparator<String> { a, b -> compareVersion(b, a) })
        return out
    }

    /**
     * 把版本号拆成可比较的段：数字段转 Int，其余保持字符串。
     *
     * `0.2.1-rc.10` → [0, 2, 1, "rc", 10]，这样 10 > 2 而不是 '1' < '2'。
     *
     * 显式建 `ArrayList<Any>` 再逐个 add，不用 `map{}` ——
     * 后者在 `Int ?: String` 上会推断出很窄的元素类型，
     * 导致后面 `x is Int` 被判成「永远为真」并连带类型不匹配。
     */
    private fun versionKey(v: String): List<Any> {
        val out = ArrayList<Any>(8)
        Regex("""\d+|[A-Za-z]+""").findAll(v).forEach { m ->
            val s = m.value
            val n = s.toIntOrNull()
            if (n != null) out.add(n) else out.add(s)
        }
        return out
    }

    /** 版本序比较；返回正数表示 a 更新。 */
    private fun compareVersion(a: String, b: String): Int {
        val ka = versionKey(a)
        val kb = versionKey(b)
        for (i in 0 until minOf(ka.size, kb.size)) {
            val x = ka[i]
            val y = kb[i]
            val c = when {
                x is Int && y is Int -> x.compareTo(y)
                // 数字段排在文本段之后：`0.2.1` 视为比 `0.2.1-rc.1` 更新（正式版优先）
                x is Int -> 1
                y is Int -> -1
                else -> x.toString().compareTo(y.toString())
            }
            if (c != 0) return c
        }
        if (ka.size != kb.size) return ka.size.compareTo(kb.size)
        return a.compareTo(b)
    }

    /**
     * 当前使用中的版本号；一个都没装时返回 null。
     *
     * 以**磁盘为准**：偏好里记的值若已经不在了（被删、安装失败回滚），
     * 就回落到最新的已装版本。否则会出现「指针指向不存在的版本 →
     * 每次都启动失败，而界面显示一切正常」这种无从下手的状态。
     */
    fun currentVersion(context: Context): String? {
        val all = installedVersions(context)
        if (all.isEmpty()) return null
        val saved = LauncherPrefs.getEngineVersion(context)
        return if (saved != null && all.contains(saved)) saved else all.first()
    }

    /**
     * 切换当前版本。只写指针，不动磁盘 ——
     * 真正换进程发生在下次「启动」时（见 [startNode]）。
     */
    fun setCurrentVersion(context: Context, version: String) {
        LauncherPrefs.setEngineVersion(context, version)
    }

    /**
     * dsh 引擎是否已安装（任意版本）。
     *
     * 容器与引擎**分离**：容器（Node + bash + 依赖库）随 APK 内置，
     * dsh 引擎后天下载，便于单独升级而不必重装 APK。
     */
    fun dshInstalled(context: Context): Boolean {
        val v = currentVersion(context) ?: return false
        val entry = engineEntry(context, v) ?: return false
        return entry.isFile && entry.length() > 0L
    }

    /**
     * 当前使用中引擎的版本号，未安装返回 null。
     *
     * 多版本下「当前版本」由指针决定，而目录名就是版本号，
     * 直接返回 [currentVersion] 即可 —— 不需要再去解析 package.json。
     */
    fun installedDshVersion(context: Context): String? = currentVersion(context)

    /**
     * 把旧版「单份引擎」布局迁移到多版本布局。
     *
     * 旧布局是 `usr/lib/node_modules/@deepseek-ai`（只可能有一份）。
     * 本次改成 `usr/lib/versions/<ver>/`。若不迁移，老用户升级后会看到
     * 「引擎不见了」—— 而文件其实好端端躺在磁盘上，只是查找位置变了。
     *
     * 幂等：没有旧目录时什么都不做；目标已存在时只删掉旧的那份。
     * 由 [ensureRuntime] 在容器就绪后调用。
     */
    fun migrateLegacyEngine(context: Context) {
        val legacy = File(usrDir(context), "lib/node_modules/@deepseek-ai")
        if (!File(legacy, "dsh/lib/bin.js").isFile) return

        // 版本号只能从旧位置的 package.json 里读
        val ver = runCatching {
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                .find(File(legacy, "dsh/package.json").readText())
                ?.groupValues?.get(1)
        }.getOrNull()

        if (ver.isNullOrBlank()) {
            onLog("⚠️ 旧布局引擎读不出版本号，跳过迁移")
            return
        }

        val root = engineRoot(context, ver)
        val dest = File(root, "node_modules/@deepseek-ai")

        // ★ 迁移只负责「让版本可见」，绝不能覆盖用户已经选好的版本 ★
        //
        // 这段会在每次 ensureRuntime() 时跑（启动、下载引擎都会触发）。
        // 若无条件 setCurrentVersion(legacyVer)，就会出现：
        // 用户装了 A、B 两版并在版本管理里选了 B，之后随便触发一次
        // 启动/下载，指针就被改回旧布局那一版 —— 界面上的「当前使用中」
        // 与用户的选择对不上，正是「只显示最新版而不是选择的版本」的成因之一。
        val hadValidPointer = currentVersion(context) != null &&
            LauncherPrefs.getEngineVersion(context)?.let { ptr ->
                installedVersions(context).contains(ptr)
            } == true

        // 目标已经有这个版本 → 旧那份纯属多余，清掉即可
        if (File(dest, "dsh/lib/bin.js").isFile) {
            runCatching { legacy.deleteRecursively() }
            if (!hadValidPointer) setCurrentVersion(context, ver)
            onLog("已清理旧布局引擎（$ver 已在多版本目录中）")
            return
        }

        onLog("⏳ 正在迁移旧引擎到多版本目录（$ver）…")
        root.mkdirs()
        dest.parentFile?.mkdirs()

        // 同一文件系统下 rename 是原子的；失败再退化为递归复制
        val ok = legacy.renameTo(dest) || runCatching {
            legacy.copyRecursively(dest, overwrite = true)
            legacy.deleteRecursively()
        }.isSuccess

        if (ok) {
            if (!hadValidPointer) setCurrentVersion(context, ver)
            onLog("✅ 迁移完成：lib/versions/$ver")
        } else {
            onLog("❌ 迁移失败，请在下载中心重新安装引擎")
        }
    }

    /**
     * 确保内置容器可用：已装则直接返回，未装则从 assets 解压。
     *
     * 之所以内置而不是让用户选 zip：容器是每次启动都需要的固定基础，
     * 让用户手动挑文件既多余又容易选错版本。dsh 引擎才是需要单独升级的部分。
     */
    /**
     * 解压互斥锁。
     *
     * 解压容器现在有**两个触发点**：启动实例、以及下载引擎（见
     * [com.example.fcldsh.EngineInstaller.installVersion]）。两者跑在不同线程上，
     * 可能同时进来。若不加锁，两条线程会往同一个 `usr/` 里交错写同一个 zip，
     * 结果是两边都解到一半、互相覆盖 —— 表现为「容器文件残缺、node 起不来」。
     */
    private val runtimeLock = Any()

    fun ensureRuntime(context: Context): Boolean =
        synchronized(runtimeLock) { ensureRuntimeLocked(context) }

    private fun ensureRuntimeLocked(context: Context): Boolean {
        if (runtimeInstalled(context)) {
            // 老用户升级后，旧布局的引擎要搬进多版本目录，否则会「凭空消失」
            migrateLegacyEngine(context)
            onLog("✅ 内置容器已就绪，跳过解压")
            return true
        }

        onLog("⏳ 首次启动：正在解压内置容器…")
        val ok = runCatching {
            context.assets.open(ASSET_RUNTIME).use { input ->
                extractZip(input, usrDir(context), "容器")
            }
        }.getOrElse { e ->
            onLog("❌ 读取内置容器失败: ${e.message}")
            false
        }

        if (!ok) {
            onLog("❌ 内置容器不可用。请确认 app/src/main/assets/$ASSET_RUNTIME 存在")
            return false
        }

        // 解压后赋可执行位。zip 里虽然带着 Unix 权限位，但 Android 用
        // ZipInputStream 解压时不还原权限，必须手动设置，否则 linker64
        // 无法执行 bin/node，rg 也无法运行（glob/grep 会因此失效）。
        val usr = usrDir(context)
        grantExec(File(usr, "bin"))
        grantExec(File(usr, "lib"))
        // ripgrep 是平台包，可执行文件在深层目录里，单独处理
        File(usr, "lib/node_modules/@vscode").listFiles()?.forEach { pkg ->
            grantExec(File(pkg, "bin"))
        }

        if (!runtimeInstalled(context)) {
            onLog("❌ 解压完成但未找到 bin/node，容器包结构可能不对")
            return false
        }
        // 首次解压容器时也检查一次：容器包里可能自带 @deepseek-ai 的一部分
        migrateLegacyEngine(context)
        onLog("✅ 内置容器解压完成")
        return true
    }

    // ------------------------------------------------------------------
    // flock 纯 JS 实现（替换原生模块）
    // ------------------------------------------------------------------

    /** flock.js 里用来识别「已经是我们的实现」的标记。 */
    private const val FLOCK_SHIM_MARKER = "DSH_LAUNCHER_FLOCK_SHIM"

    /**
     * 把 `@deepseek-ai/node-addon-system/lib/flock.js` 换成纯 JS 实现。
     *
     * ## 为什么只能这么做
     *
     * 上游的 flock 走原生模块，链路是：
     * ```js
     * const manifest = require.resolve(
     *   `@deepseek-ai/node-addon-system-${platform}-${arch}/package.json`)
     * ```
     * 而该包的 `optionalDependencies` 只有 linux/darwin 四种，**没有 android**。
     *
     * 试过两条路都不通：
     * 1. **伪装 platform 为 linux** → 能命中 `linux-arm64` 那份，但那是
     *    musl/glibc 构建，Android 的 bionic 没有 `__errno_location` 符号，
     *    dlopen 直接失败。
     * 2. **补装 linux 包** → 同上，ABI 不兼容，不是配置能解决的。
     *
     * 报错依次演进为：
     *   flock is not supported on android-arm64
     *   → Cannot find module '@deepseek-ai/node-addon-system-linux-arm64'
     *   → dlopen failed: cannot locate symbol "__errno_location"
     * 到此可以确定：**任何依赖该原生模块的方案都不可能成功**。
     *
     * ## 替代实现
     *
     * flock 的语义是「对某个 fd 加排他建议锁」。这里用锁文件实现：
     * - 通过 `/proc/self/fd/<fd>` 反查 fd 指向的真实路径（Android 有 /proc）
     * - 在 `<path>.dsh-lock` 上用 `wx`（O_CREAT|O_EXCL）原子创建
     * - 已存在时读其中记录的 pid，进程已死则视为僵尸锁并回收
     * - 进程退出时清理自己创建的锁文件
     *
     * 与原生实现的差异：不支持跨机共享文件系统上的真实内核锁，
     * 但在「单机、单 dsh 进程」的实际场景下语义等价。
     *
     * 每次启动都会重新写入（引擎重装会覆盖该文件），已是我们实现时跳过。
     */
    fun ensureFlockShim(context: Context) {
        // 多版本共存：每个已装版本各有一份 node-addon-system。
        // 只刷当前那个的话，切到另一个版本就会重新撞上
        // 「flock is not supported on android-arm64」。
        // 容器自己也可能在 lib/node_modules 下带一份，一并处理。
        val targets = ArrayList<File>(4)
        File(
            usrDir(context),
            "lib/node_modules/@deepseek-ai/node-addon-system/lib/flock.js"
        ).let { if (it.parentFile?.isDirectory == true) targets.add(it) }
        for (v in installedVersions(context)) {
            File(
                engineRoot(context, v),
                "node_modules/@deepseek-ai/node-addon-system/lib/flock.js"
            ).let { if (it.parentFile?.isDirectory == true) targets.add(it) }
        }
        if (targets.isEmpty()) return

        // 只处理还没替换过的；全都替换过就什么都不做
        val pending = targets.filter { f ->
            !runCatching { f.readText() }.getOrNull().orEmpty()
                .contains(FLOCK_SHIM_MARKER)
        }
        if (pending.isEmpty()) return

        val shim = """
            // $FLOCK_SHIM_MARKER
            //
            // 由 DSH Launcher 自动生成，请勿手改。
            //
            // 上游实现依赖 @deepseek-ai/node-addon-system-<platform>-<arch>
            // 的原生模块，而它只发布了 linux/darwin 构建。Android 的 bionic
            // 缺少 __errno_location 符号，任何 linux 二进制都无法 dlopen。
            //
            // 这里用锁文件实现同等语义（排他、非阻塞），绕开原生模块。
            import fs from 'node:fs';

            /** 本进程持有的锁：fd -> 锁文件路径（null 表示无路径可锁） */
            const held = new Map();

            /** 通过 /proc 反查 fd 指向的路径；取不到返回 null。 */
            function pathOf(fd) {
              try {
                return fs.readlinkSync('/proc/self/fd/' + fd);
              } catch {
                return null;
              }
            }

            /** pid 是否还活着。EPERM 说明存在但无权信号，也算活着。 */
            function pidAlive(pid) {
              if (!Number.isInteger(pid) || pid <= 0) return false;
              try {
                process.kill(pid, 0);
                return true;
              } catch (e) {
                return !!(e && e.code === 'EPERM');
              }
            }

            /** 原子创建锁文件；成功返回 true。 */
            function createLock(lockPath) {
              try {
                const lfd = fs.openSync(lockPath, 'wx');
                fs.writeSync(lfd, String(process.pid));
                fs.closeSync(lfd);
                return true;
              } catch (e) {
                if (!e || e.code !== 'EEXIST') throw e;
                return false;
              }
            }

            /** 尝试获取锁；抢不到返回 false。僵尸锁会被回收后重试一次。 */
            function acquire(lockPath) {
              if (createLock(lockPath)) return true;

              let holder = NaN;
              try {
                holder = parseInt(fs.readFileSync(lockPath, 'utf8'), 10);
              } catch { /* 文件刚被删或读失败，按僵尸处理 */ }

              if (pidAlive(holder)) return false;

              // 持有者已死，清掉残留后重试一次
              try { fs.unlinkSync(lockPath); } catch { /* 可能已被别人删掉 */ }
              return createLock(lockPath);
            }

            /**
             * 对调用方的文件描述符加排他非阻塞锁。
             *
             * @param fd 已打开的 fd；所有权仍归调用方，本函数不关闭它。
             * @returns 获取成功后 resolve；争用时以 EAGAIN 拒绝。
             */
            export async function tryLockExclusive(fd) {
              if (held.has(fd)) {
                throw Object.assign(new Error('EAGAIN: flock failed'), {
                  code: 'EAGAIN',
                  errno: 11,
                  syscall: 'flock',
                });
              }

              const target = pathOf(fd);
              // 匿名 fd（管道、socket）没有路径可锁，退化为进程内互斥
              if (!target) {
                held.set(fd, null);
                return;
              }

              const lockPath = target + '.dsh-lock';
              if (!acquire(lockPath)) {
                throw Object.assign(new Error('EAGAIN: flock failed'), {
                  code: 'EAGAIN',
                  errno: 11,
                  syscall: 'flock',
                });
              }
              held.set(fd, lockPath);
            }
        """.trimIndent() + "\n"

        var done = 0
        for (f in pending) {
            if (runCatching { f.writeText(shim) }.isSuccess) done++
        }
        if (done > 0) {
            onLog(
                "🔧 已启用 flock 纯 JS 实现（原生模块在 Android 上不可用）" +
                    if (done > 1) " · 共 $done 处" else ""
            )
        } else {
            onLog("⚠️ 写入 flock 替代实现失败，文件锁相关功能可能报错")
        }
    }

    // ------------------------------------------------------------------
    // 截屏命令替身（让 agent 第一次尝试就成功）
    // ------------------------------------------------------------------

    /**
     * 在 `PREFIX/bin` 下生成 `screencap` / `screenrecord` 的替身脚本。
     *
     * ## 为什么需要替身，而不是修好真的 screencap
     *
     * agent 的实测诊断是准确的：
     * ```
     * screencap             → 退出码 1，写出 0 字节
     * screenrecord          → ERROR: no display
     * dumpsys SurfaceFlinger → Permission Denial: missing android.permission.DUMP
     * ```
     * 根因是它跑在 SELinux 的 `untrusted_app` 域，既没有 `CAPTURE_VIDEO_OUTPUT`
     * 也没有 `DUMP`。这不是配置问题，**在应用沙箱里修不了**。
     * 唯一的合法截屏通道是 MediaProjection，而它必须由用户在系统弹窗里
     * 点一次同意（见 [com.example.fcldsh.ScreenCaptureService]）。
     *
     * ## 为什么替换命令名，而不是只在文档里写清楚
     *
     * 经验是：agent 第一次要读屏时会**先按习惯试几个常用命令**。
     * 如果 `screencap` 返回 0 字节，它就继续试 `screenrecord`、`uiautomator`、
     * `dumpsys`……每一轮都是失败往返，还可能把结论错判成「这个环境不行」。
     *
     * 接管命令名等于把那几条「注定失败的路」直接接到已经能用的实现上：
     * 用户在应用里授权过投屏后，`screencap -p` 立刻输出真实 PNG。
     * 它**第一次尝试就成功**，不需要先读懂长文档。
     *
     * ## 为什么交给 /system/bin/sh 而不是 linker64
     *
     * linker64 只认 ELF，喂 shell 脚本必然失败。所以 spawn 补丁里专门加了
     * 一条 `fcl-shim` 分支（见 [ensureSpawnPatch] 的 v5），把带标记的脚本
     * 交给系统 sh 执行。这些脚本只调用 `/system/bin` 下的 cat/cp
     * （系统目录允许直接 exec），不会去 exec 应用数据目录里的东西，
     * 因此**没有** pnpm 那种「sh 内部再 exec 仍 EACCES」的问题。
     *
     * @return 是否全部写入成功（失败只记日志，不阻断启动）
     */
    private fun ensureScreenShims(context: Context, prefix: String): Boolean {
        val binDir = File(prefix, "bin").apply { mkdirs() }

        // 用绝对路径写死在脚本里，不依赖 $HOME：
        // spawn 时的环境变量未必与启动进程一致，靠 $HOME 拼路径有落空风险。
        val shot = com.example.fcldsh.ScreenCaptureState.screenshotFile(context).absolutePath

        // 第一行必须是 shebang，第二行必须含 fcl-shim 标记 —— spawn 补丁靠它识别。
        val screencap = """
            #!/system/bin/sh
            # fcl-shim screencap
            #
            # screencap 替身。真 screencap 在 untrusted_app 域里只能写出 0 字节
            # （缺 CAPTURE_VIDEO_OUTPUT），这里改为输出应用经 MediaProjection
            # 持续落盘的截图。
            #
            # 用法与真 screencap 一致：
            #   screencap -p > /tmp/x.png     输出到 stdout
            #   screencap -p /tmp/x.png       写入指定文件
            SRC="$shot"
            if [ ! -f "${'$'}SRC" ]; then
              echo "screencap: 截图尚未就绪" >&2
              echo "  请让用户在应用内开启: 设置 → 权限设置 → 屏幕截图" >&2
              echo "  或改用 HTTP 端点: GET /screenshot" >&2
              exit 1
            fi
            OUT=""
            for a in "${'$'}@"; do
              case "${'$'}a" in
                -*) ;;
                *) OUT="${'$'}a" ;;
              esac
            done
            if [ -n "${'$'}OUT" ]; then
              cp -f "${'$'}SRC" "${'$'}OUT" || exit 1
            else
              cat "${'$'}SRC" || exit 1
            fi
            exit 0
        """.trimIndent() + "\n"

        val screenrecord = """
            #!/system/bin/sh
            # fcl-shim screenrecord
            #
            # screenrecord 替身。真实现报 "ERROR: no display"（同样缺权限），
            # 本应用也没有做录屏。直接给出可行动的指引，避免反复重试。
            echo "screenrecord: 本环境不支持录屏" >&2
            echo "  读屏请改用 HTTP 端点 GET /screenshot" >&2
            echo "  凭据在 ~/a11y-bridge.json（cat 一下拿 port 与 token）" >&2
            exit 1
        """.trimIndent() + "\n"

        var ok = true
        val scripts = listOf("screencap" to screencap, "screenrecord" to screenrecord)
        for ((name, body) in scripts) {
            val f = File(binDir, name)
            val written = runCatching {
                f.writeText(body)
                // 解压/写文件不还原权限，必须显式设置，否则 linker64 与 sh 都打不开
                f.setExecutable(true, false)
                true
            }.getOrDefault(false)
            if (!written) {
                ok = false
                onLog("⚠️ 写入 $name 替身失败")
            }
        }
        if (ok) onLog("📸 已安装 screencap 替身（直接返回已授权的截图）")
        return ok
    }

    // ------------------------------------------------------------------
    // fs 兼容补丁（硬链接降级为复制）
    // ------------------------------------------------------------------

    /**
     * 生成 fs 兼容补丁，返回其绝对路径（失败返回 null）。
     *
     * ## 要解决的问题
     *
     * 真机报错：
     * ```
     * EACCES: permission denied, link
     *   '.../session.v4.jsonl.zstd.17ff81d804a8.tmp'
     * -> '.../session.v4.jsonl.zstd'
     * ```
     *
     * dsh 写会话文件时采用「写临时文件 → 硬链接到目标」的原子替换模式。
     * 在 Android 上，即便 App 拥有该目录，硬链接仍可能被拒绝
     * （文件系统挂载带 `protected_hardlinks`，或 SELinux 对 link 的额外限制）。
     * 结果是会话无法落盘，整个对话流程中断。
     *
     * ## 为什么用全局拦截而不是逐个改调用点
     *
     * 硬链接可能来自 dsh 本体、session-persistence 插件，或某个第三方库。
     * 逐个定位要读几十个包、还随版本变化失效。而 `fs.link` 是唯一的系统入口 ——
     * 在它上面做一次拦截，无论谁调用都能覆盖。
     *
     * ## 为什么降级为 copy 是安全的
     *
     * 上游用 link 是为了「原子替换且省空间」。降级成 copy 后：
     * - 原子性：`copyFileSync` 写到目标路径时覆盖，中间态窗口极短
     * - 空间：多占一份会话文件的体积（几十 KB 级），可接受
     * - 语义：调用方拿到的是「目标文件已存在且内容为新」，与 link 一致
     *
     * 只在 EACCES / EPERM / EXDEV 时降级，其余错误（EIO、ENOSPC）照旧抛 ——
     * 真故障不该被掩盖。
     */
    private fun ensureFsPatch(prefix: String): String? {
        val patchFile = File(prefix, "lib/dsh-fs-patch.mjs")

        // 按标记判断是否需要重写。
        //
        // ⚠️ 标记里带版本号，**改下面任何 JS 内容都必须 +1**。
        // 不带版本时，设备上已有的旧文件仍然包含旧标记 → 判定「已存在」→
        // 永不重写，新加的补丁静默失效，而且现场完全看不出来
        // （spawn 补丁的 `patchVersion` 是同一个坑，那边已经是 V5）。
        val marker = "DSH_LAUNCHER_FS_PATCH_V2"
        val existing = runCatching { patchFile.readText() }.getOrNull().orEmpty()
        if (!existing.contains(marker)) {
            val js = """
                // $marker
                //
                // 由 DSH Launcher 自动生成，请勿手改。
                //
                // Android 上 `fs.link()` 可能返回 EACCES（protected_hardlinks /
                // SELinux），而 dsh 用「临时文件 + 硬链接」做会话文件的原子替换，
                // 一旦失败整个对话流程就中断。
                //
                // 这里拦截 fs.link / linkSync / promises.link，失败时降级为复制。
                // 只对 EACCES / EPERM / EXDEV 降级，其余错误照旧抛出。
                import fs from 'node:fs';
                import { syncBuiltinESMExports } from 'node:module';

                const FALLBACK = new Set(['EACCES', 'EPERM', 'EXDEV']);
                const log = (how, from, to, code) => {
                  try {
                    console.error('[dsh-launcher] fs.' + how + ' ' + code +
                      '，已降级为复制: ' + from + ' -> ' + to);
                  } catch { /* 日志失败不影响主流程 */ }
                };

                const origLink = fs.link;
                fs.link = function (from, to, cb) {
                  return origLink.call(fs, from, to, (err) => {
                    if (err && FALLBACK.has(err.code)) {
                      log('link', from, to, err.code);
                      return fs.copyFile(from, to, (e2) => cb(e2 || null));
                    }
                    cb(err);
                  });
                };

                const origLinkSync = fs.linkSync;
                fs.linkSync = function (from, to) {
                  try {
                    return origLinkSync.call(fs, from, to);
                  } catch (err) {
                    if (err && FALLBACK.has(err.code)) {
                      log('linkSync', from, to, err.code);
                      return fs.copyFileSync(from, to);
                    }
                    throw err;
                  }
                };

                const origPLink = fs.promises.link;
                fs.promises.link = async function (from, to) {
                  try {
                    return await origPLink.call(fs.promises, from, to);
                  } catch (err) {
                    if (err && FALLBACK.has(err.code)) {
                      log('promises.link', from, to, err.code);
                      return fs.promises.copyFile(from, to);
                    }
                    throw err;
                  }
                };

                // 让 `import { link } from 'node:fs'` 这类具名导入也拿到补丁后的版本
                syncBuiltinESMExports();
            """.trimIndent() + "\n"

            val ok = runCatching { patchFile.writeText(js) }.isSuccess
            if (!ok) {
                onLog("⚠️ 写入 fs 兼容补丁失败，硬链接相关操作可能报错")
                return null
            }
        }
        return patchFile.absolutePath
    }

    // ------------------------------------------------------------------
    // 子进程 exec 补丁（走 linker64）
    // ------------------------------------------------------------------

    /**
     * 生成子进程 exec 补丁，返回其绝对路径（失败返回 null）。
     *
     * ## 要解决的问题
     *
     * dsh 自检报：
     * ```
     * bash  → spawn bash EACCES
     * rg    → ripgrep provider failure
     * ```
     *
     * 根因是 Android 10+ 的 **W^X 限制**：`filesDir` 下的文件被 SELinux 标记为
     * `app_data_file`，**不允许直接 exec**，`setExecutable(true)` 也绕不过。
     * 而 `bash`、`rg` 都在 `$PREFIX/bin` 里，dsh 直接 spawn 就被内核拒绝。
     *
     * Node 自己不受影响，因为我们一直用 `/system/bin/linker64` 去加载它 ——
     * linker64 是系统可执行文件，它把目标 ELF 当**数据**读进来再跳转执行，
     * 完全绕过 exec 权限检查。
     *
     * ## 修法
     *
     * 同样的办法可以套用到任何 ELF 上，问题只是那些 spawn 由 dsh 内部发起，
     * 改不了它的代码。所以拦截 `child_process` 的唯一入口：
     * `spawn` / `spawnSync` / `execFile` / `execFileSync`。
     *
     * 判定条件很保守：
     * - 命令解析到 `$PREFIX` 下的真实文件
     * - 且该文件是 **ELF**（读头 4 字节为 `\x7fELF`）
     *
     * ## 脚本入口（v2）
     *
     * `bin/pnpm`、`bin/npm` 这类入口是带 shebang 的**脚本**，不是 ELF，
     * 走 linker64 只会失败（linker64 只认 ELF）。但它们同样不能直接 exec：
     * dsh 取插件信息时报的就是
     * ```
     * pnpm view 'dsh-whale-widget@0.4.2' ... spawn pnpm EACCES
     * ```
     * 所以 v2 把脚本也纳入中转 —— 读首行 shebang 判断解释器：
     * - `node` 系 → `linker64 <PREFIX>/bin/node <script> …`
     * - `sh` 系包装脚本 → **不**交给 `/system/bin/sh` 跑（sh 里再 exec 数据
     *   目录的 node 依旧 EACCES，就是我们已知的第 4 层限制），而是按命令名
     *   认领它真正要跑的 JS 入口，仍走 `linker64 node <entry>`
     *
     * 这与「把入口换成带绝对 shebang 的静态 wrapper」是同一思路
     * （参考项目的对照做法是构建期把 bin/pnpm 换成带绝对 shebang 的 wrapper，
     * 见 scripts/collect-termux-runtime.sh；那条路依赖 targetSdk<=28 允许 exec
     * 应用数据目录，我们做不到，所以把解释器选择放在运行期做。）
     *
     * ⚠️ 改这里的内容必须把 `patchVersion` +1，否则设备上的旧补丁会一直命中
     * 缓存分支、永不重写。加载时的那行 `loaded V<n>` 日志就是用来核对这一点的。
     */
    private fun ensureSpawnPatch(prefix: String): String? {
        val patchFile = File(prefix, "lib/dsh-spawn-patch.mjs")

        // 补丁版本号。**改下面任何 JS 内容都必须 +1**，否则设备上的旧文件会
        // 一直命中 `existing.contains(marker)` 的缓存分支 —— 改了等于没改，
        // 而且现场完全看不出来。
        //
        // 这个号同时会被写进启动日志（`loaded V<n>`），用来在真机上直接确认
        // 「这次跑的到底是不是新补丁」。此前排查 pnpm EACCES 时，
        // 最大的障碍就是无从判断补丁有没有生效。
        // ★ v5：新增 fcl-shim 分支（自建的 screencap 替身交给 /system/bin/sh 执行）。
        // 改了 JS 内容就必须 +1 —— 否则设备上旧的 V4 文件一直命中缓存分支，
        // 新加的分支等于不存在，而且现场完全看不出来。
        val patchVersion = 5
        val marker = "DSH_LAUNCHER_SPAWN_PATCH_V$patchVersion"

        val existing = runCatching { patchFile.readText() }.getOrNull().orEmpty()
        if (!existing.contains(marker)) {
            val js = """
                // $marker
                //
                // 由 DSH Launcher 自动生成，请勿手改。
                //
                // Android 10+ 不允许从 App 数据目录直接 exec（SELinux 把
                // app_data_file 标为不可执行），所以 dsh spawn 的 bash / rg
                // 全部 EACCES。Node 自己不受影响，因为它由 /system/bin/linker64
                // 加载 —— linker64 把目标 ELF 当数据读入再跳转，绕过 exec 检查。
                //
                // 这里把同样的办法套到其余入口上：拦截 child_process 的
                // spawn / spawnSync / execFile / execFileSync，凡是解析到
                // 应用数据目录里的可执行文件，就改成 linker64 <path> 的形式；
                // 带 shebang 的脚本（bin/pnpm、bin/npm 之类）改用
                // linker64 <node> <script>，细节见下面的 resolveTarget()。
                import cp from 'node:child_process';
                import fs from 'node:fs';
                import path from 'node:path';
                import { syncBuiltinESMExports } from 'node:module';
                import { fileURLToPath } from 'node:url';

                const LINKER = '/system/bin/linker64';

                /**
                 * 本补丁自身的路径。
                 *
                 * 主进程是用命令行 `--import <本文件>` 加载的，**子进程不会继承**。
                 * 于是 `corepack/dist/pnpm.js` 拉起的子 node 里没有补丁，
                 * 它再 spawnSync(pnpm-native) 就又是裸 exec → EACCES。
                 * 解决办法是在把脚本改写成 `linker64 node <script>` 时，
                 * 一并把 `--import <本文件>` 插进 argv（见 makeWrapper）。
                 */
                const SELF = (function () {
                  try { return fileURLToPath(import.meta.url); } catch { return ''; }
                })();

                /**
                 * 统一日志出口。前缀固定为 [dsh-launcher]，便于在引擎日志里 grep。
                 * 日志本身绝不抛错 —— 它跑在每一次 spawn 的路径上。
                 */
                function logLine(msg) {
                  try {
                    console.error('[dsh-launcher] ' + msg);
                  } catch { /* 日志失败不影响主流程 */ }
                }

                // PREFIX 可能是 /data/user/0/<pkg>/files/usr，
                // 而 Node/库解析出的真实路径常是 /data/data/<pkg>/files/usr
                // —— 两者是同一个目录的两种写法（符号链接）。只做字符串
                // startsWith 会让 rg 这类走绝对路径的命令匹配不上。
                // 所以这里同时保留两种拼写，并在比较时做 realpath 归一化。
                const PREFIX_RAW = (process.env.PREFIX || '').replace(/\/+$/, '');
                const PREFIX_FORMS = [];
                function addForm(p) {
                  if (!p) return;
                  if (!PREFIX_FORMS.includes(p)) PREFIX_FORMS.push(p);
                }
                addForm(PREFIX_RAW);
                if (PREFIX_RAW.startsWith('/data/user/0/')) {
                  addForm(PREFIX_RAW.replace('/data/user/0/', '/data/data/'));
                }
                if (PREFIX_RAW.startsWith('/data/data/')) {
                  addForm(PREFIX_RAW.replace('/data/data/', '/data/user/0/'));
                }
                try {
                  // realpath 能同时处理上面两种情况，作为权威形式
                  addForm(fs.realpathSync(PREFIX_RAW));
                } catch { /* 目录不存在就跳过 */ }

                /** 把路径解析成真实路径；失败时原样返回。 */
                function real(p) {
                  try { return fs.realpathSync(p); } catch { return p; }
                }

                /** 这些目录下的 ELF 本来就允许 exec，不能改。 */
                const SYSTEM_ROOTS = [
                  '/system', '/system_ext', '/vendor', '/product', '/apex',
                  '/data/app', '/data/dalvik-cache', '/data/misc',
                  '/dev', '/proc', '/sys',
                ];

                function inSystemRoot(p) {
                  return SYSTEM_ROOTS.some(function (r) {
                    return p === r || p.startsWith(r + '/');
                  });
                }

                /** 读文件头（最多 4KB），用于判断 ELF 魔数或 shebang。失败返回 null。 */
                function readHead(p) {
                  try {
                    const fd = fs.openSync(p, 'r');
                    const buf = Buffer.alloc(4096);
                    const n = fs.readSync(fd, buf, 0, 4096, 0);
                    fs.closeSync(fd);
                    return buf.subarray(0, n);
                  } catch {
                    return null;
                  }
                }

                /** 从文件头判断是否 ELF。 */
                function isElf(head) {
                  return !!head && head.length >= 4 &&
                    head[0] === 0x7f && head[1] === 0x45 &&
                    head[2] === 0x4c && head[3] === 0x46;
                }

                /**
                 * 解析 #! 行，返回解释器的 basename（小写）；不是脚本返回 null。
                 * `#!/usr/bin/env node` 这类形式会自动跳过 env 与变量赋值。
                 */
                function shebangName(head) {
                  if (!head || head.length < 3) return null;
                  if (head[0] !== 0x23 || head[1] !== 0x21) return null; // '#!'
                  let end = head.indexOf(0x0a);
                  if (end < 0) end = head.length;
                  const parts = head.subarray(2, end).toString('utf8')
                    .trim().split(/\s+/).filter(function (s) { return !!s; });
                  if (parts.length === 0) return null;
                  let exe = parts[0];
                  if (path.basename(exe) === 'env') {
                    let i = 1;
                    while (i < parts.length && parts[i].indexOf('=') >= 0) i++;
                    if (i >= parts.length) return null;
                    exe = parts[i];
                  }
                  return path.basename(exe).toLowerCase();
                }

                /** 在 PREFIX/bin 里找一个命令；找不到返回 null。 */
                function findBin(name) {
                  for (let i = 0; i < PREFIX_FORMS.length; i++) {
                    const cand = path.join(PREFIX_FORMS[i], 'bin', name);
                    if (fs.existsSync(cand)) return cand;
                  }
                  return null;
                }

                /**
                 * 容器里的 node 可执行文件（ELF）。
                 *
                 * 优先 PREFIX/bin/node（与 PATH 一致）；退到 process.execPath ——
                 * 后者本身就是当前这个 node 的真实路径，一定可加载。
                 */
                let NODE_BIN;
                function nodeBinary() {
                  if (NODE_BIN !== undefined) return NODE_BIN;
                  NODE_BIN = findBin('node') || null;
                  if (!NODE_BIN) {
                    const self = process.execPath;
                    NODE_BIN = (self && isElf(readHead(self))) ? self : null;
                  }
                  return NODE_BIN;
                }

                /**
                 * shell 入口脚本背后的 node 入口文件。
                 *
                 * corepack 的 `bin/pnpm`、`bin/npm` 是 `#!/bin/sh` 包装脚本，内部
                 * `exec node .../dist/pnpm.js`。**不能**改由 /system/bin/sh 执行 ——
                 * sh 里再 exec 数据目录的 node 仍然 EACCES（Android 硬限制，
                 * 与第 3 层同源）。所以这里按命令名直接认领真正的 JS 入口。
                 */
                const NODE_ENTRIES = [
                  ['pnpm', 'lib/node_modules/pnpm/bin/pnpm.cjs'],
                  ['pnpm', 'lib/node_modules/pnpm/bin/pnpm.js'],
                  ['pnpm', 'lib/node_modules/corepack/dist/pnpm.js'],
                  ['npm', 'lib/node_modules/npm/bin/npm-cli.js'],
                  ['npm', 'lib/node_modules/corepack/dist/npm.js'],
                  ['npx', 'lib/node_modules/npm/bin/npx-cli.js'],
                  ['npx', 'lib/node_modules/corepack/dist/npx.js'],
                  ['yarn', 'lib/node_modules/corepack/dist/yarn.js'],
                ];

                /** 按命令名找对应的 node 入口；找不到返回 null。 */
                function nodeEntryFor(name) {
                  const want = String(name || '').split('/').pop().toLowerCase();
                  for (let i = 0; i < NODE_ENTRIES.length; i++) {
                    if (NODE_ENTRIES[i][0] !== want) continue;
                    const rel = NODE_ENTRIES[i][1];
                    for (let j = 0; j < PREFIX_FORMS.length; j++) {
                      const cand = path.join(PREFIX_FORMS[j], rel);
                      if (fs.existsSync(cand)) return cand;
                    }
                  }
                  return null;
                }

                /**
                 * 兜底：在几个固定目录里按文件名找 node 入口。
                 *
                 * 只在 nodeEntryFor 的显式清单落空时用（容器换了 pnpm 的安装位置、
                 * 或 corepack 改了 dist 下的文件名）。**只 readdir 两三个目录**，
                 * 绝不遍历整个 node_modules —— 手机上一次遍历几十万文件会把引擎卡死。
                 */
                const SCAN_DIRS = [
                  'lib/node_modules/corepack/dist',
                  'lib/node_modules/@pnpm/exe',
                  'lib/node_modules/pnpm/bin',
                ];

                function scanEntry(name) {
                  const want = String(name || '').split('/').pop().toLowerCase();
                  if (!want) return null;
                  for (let i = 0; i < PREFIX_FORMS.length; i++) {
                    for (let j = 0; j < SCAN_DIRS.length; j++) {
                      const dir = path.join(PREFIX_FORMS[i], SCAN_DIRS[j]);
                      let files = null;
                      try { files = fs.readdirSync(dir); } catch { continue; }
                      for (let k = 0; k < files.length; k++) {
                        const f = files[k];
                        if (!/\.(c|m)?js/.test(f)) continue;
                        const dot = f.lastIndexOf('.');
                        if (dot <= 0) continue;
                        if (f.slice(0, dot) !== want) continue;
                        return path.join(dir, f);
                      }
                    }
                  }
                  return null;
                }

                /**
                 * 把命令解析成「怎么执行」的描述；不需要中转时返回 null。
                 *
                 * 判定顺序：
                 * 1. 已经是 linker64 / /system/bin/sh 本身 → 不动
                 * 2. 系统目录下的 → 不动（本来就允许 exec）
                 * 3. 带路径的（绝对或相对）或裸命令名（PREFIX/bin）→ 解析成文件
                 * 4. 按文件头分派：
                 *    - ELF            → { kind: 'elf',  path }  走 linker64
                 *    - node shebang   → { kind: 'node', path }  linker64 node <script>
                 *    - sh   shebang   → 认领它真正要跑的 node 入口，同上
                 *    - 其余           → null（保持原样，至少不会更糟）
                 *
                 * 只要不在系统目录、又是 ELF / 可识别的脚本，就说明它需要中转 ——
                 * 这比「必须匹配 PREFIX 前缀」更稳，能同时覆盖
                 * /data/user/0 与 /data/data 两种拼写，以及软链后的真实路径。
                 */
                function resolveTarget(cmd) {
                  if (typeof cmd !== 'string' || !cmd) return null;
                  if (cmd === LINKER || cmd === '/system/bin/sh') return null;

                  let p = null;
                  if (cmd.indexOf('/') >= 0) {
                    p = cmd.startsWith('/') ? cmd : path.resolve(cmd);
                  } else {
                    p = findBin(cmd);
                  }
                  if (!p || !fs.existsSync(p)) return null;

                  // 系统目录下的二进制允许直接 exec，不要动它
                  if (inSystemRoot(p) || inSystemRoot(real(p))) return null;

                  const head = readHead(p);
                  if (!head) return null;

                  if (isElf(head)) return { kind: 'elf', path: p };

                  const interp = shebangName(head);
                  if (!interp) return null;

                  // 本应用自己写的 shim（首行带 fcl-shim 标记）→ 交给 /system/bin/sh。
                  //
                  // 这些脚本只调用 /system/bin 下的 cat/cp（系统目录，允许 exec），
                  // 不会去 exec 应用数据目录里的东西，所以**没有** pnpm 那种
                  // 「sh 内部再 exec node 仍 EACCES」的问题。
                  // 这正是让 `screencap` 等常用命令第一次就成功的关键：
                  // 它们被替换成读取应用持续落盘的截图，而不是去碰需要
                  // CAPTURE_VIDEO_OUTPUT 的真 screencap。
                  if (head.indexOf('fcl-shim') >= 0) return { kind: 'sh', path: p };

                  const node = nodeBinary();

                  // 直接以 node 为解释器：bin/pnpm 软链到 *.cjs 的常见形态
                  if (interp === 'node' || interp === 'nodejs' || interp === 'node.exe') {
                    return node ? { kind: 'node', path: p, node: node } : null;
                  }

                  // shell 包装脚本：不能交给 /system/bin/sh 跑 —— 它内部 exec
                  // 数据目录里的 node 依旧 EACCES。改认领真正的 JS 入口。
                  //
                  // 先查显式清单，落空再 scanEntry 兜底（只 readdir 三个固定目录）。
                  if (interp === 'sh' || interp === 'bash' ||
                      interp === 'dash' || interp === 'ash') {
                    const base = path.basename(p);
                    const entry = nodeEntryFor(base) || scanEntry(base);
                    if (entry && node) {
                      return { kind: 'node', path: entry, node: node };
                    }
                    logLine('无法中转 shell 入口 ' + p +
                      '（清单与兜底扫描都没找到对应 node 入口，node=' +
                      (node || 'null') + '），保持原样');
                    return null;
                  }

                  return null;
                }

                /**
                 * 包一层，把目标改走 linker64。
                 *
                 * 两种形态：
                 * - ELF    → [linker64, <elf>, ...args]
                 * - 脚本   → [linker64, <node>, <script>, ...args]
                 *
                 * **每一次调用都会打一行日志**（不管有没有拦到），
                 * 因为排查时最需要的信息是「这次 pnpm 调用到底有没有被补丁看见」。
                 * 只有拦截成功的才带 `-> linker64 ...` 的落地命令。
                 *
                 * @param orig 原函数
                 * @param hasCallback execFile 这类最后一个参数是回调
                 * @param label 日志里的调用方式（spawn / spawnSync / execFile …）
                 */
                function makeWrapper(orig, hasCallback, label) {
                  return preservePromisify(orig, function () {
                    const a = Array.prototype.slice.call(arguments);
                    const t = resolveTarget(a[0]);

                    if (!t) {
                      logLine(label + ' cmd=' + a[0] + ' kind=none（未拦截，交给内核）');
                      return orig.apply(this, a);
                    }

                    let rest = a.slice(1);
                    let cb = null;
                    if (hasCallback && typeof rest[rest.length - 1] === 'function') {
                      cb = rest.pop();
                    }
                    let cmdArgs = [];
                    let options;
                    if (Array.isArray(rest[0])) {
                      cmdArgs = rest[0];
                      options = rest[1];
                    } else {
                      options = rest[0];
                    }

                    // shim 形态：直接用系统 sh 执行，不经过 linker64。
                    // linker64 只认 ELF，把 shell 脚本喂给它必然失败。
                    if (t.kind === 'sh') {
                      const shArgs = ['/system/bin/sh', t.path].concat(cmdArgs);
                      logLine(label + ' cmd=' + a[0] + ' kind=sh -> ' + shArgs.join(' '));
                      if (options !== undefined) shArgs.push(options);
                      if (cb) shArgs.push(cb);
                      return orig.apply(this, shArgs);
                    }

                    // linker64 后面要跟的完整 argv（不含 linker64 自身）
                    //
                    // node 形态要把本补丁也带上（`--import <SELF>`）：
                    // 主进程的命令行 --import 不会传给子进程，而
                    // corepack/dist/pnpm.js 会在子进程里 spawnSync(pnpm-native)，
                    // 那个子进程没有补丁就又回到裸 exec → EACCES。
                    // node flags 必须排在脚本路径之前。
                    const inner = t.kind === 'node'
                      ? (SELF
                          ? [t.node, '--import', SELF, t.path].concat(cmdArgs)
                          : [t.node, t.path].concat(cmdArgs))
                      : [t.path].concat(cmdArgs);

                    logLine(label + ' cmd=' + a[0] + ' kind=' + t.kind +
                      ' -> linker64 ' + inner.join(' '));

                    const next = [LINKER, inner];
                    if (options !== undefined) next.push(options);
                    if (cb) next.push(cb);
                    return orig.apply(this, next);
                  });
                }

                /**
                 * 保住 `util.promisify` 的定制实现。
                 *
                 * Node 给 `execFile` 挂了 `util.promisify.custom`，签名是
                 * `(file, args, options) => Promise<{stdout, stderr}>`。包一层
                 * 普通函数会把这个符号丢掉，于是 `promisify(execFile)` 退化成
                 * 「回调 → 只 resolve stdout」，拿到的东西从对象变成字符串，
                 * 调用方解构 `.stdout` 就炸。这里把符号原样搬过去。
                 *
                 * 只对 `execFile` 有意义（它是四个入口里唯一带定制 promisify 的
                 * —— `spawn` 返回 ChildProcess，本来就不走 promisify）。
                 */
                const PROMISIFY_CUSTOM = Symbol.for('nodejs.util.promisify.custom');

                function preservePromisify(orig, wrapped) {
                  try {
                    if (orig[PROMISIFY_CUSTOM]) {
                      wrapped[PROMISIFY_CUSTOM] = orig[PROMISIFY_CUSTOM];
                    }
                  } catch { /* 搬不动就算了，不能影响 spawn 本身 */ }
                  return wrapped;
                }

                cp.spawn = makeWrapper(cp.spawn, false, 'spawn');
                cp.spawnSync = makeWrapper(cp.spawnSync, false, 'spawnSync');
                cp.execFile = makeWrapper(cp.execFile, true, 'execFile');
                cp.execFileSync = makeWrapper(cp.execFileSync, false, 'execFileSync');

                // 让 `import { spawn } from 'node:child_process'` 也拿到补丁版。
                // 必须在上面全部赋值之后再调 —— 它把 CJS 导出重新同步到 ESM
                // 具名绑定，dsh 的依赖里两种写法都有。
                syncBuiltinESMExports();

                // ── 加载自检：一次性把关键事实打进日志 ──────────────────────
                // 排查 pnpm spawn EACCES 时，最大的障碍是「不知道补丁到底有没有
                // 生效」：启动日志里连一行 [dsh-launcher] 都没有，既无法证实
                // 也无法否定。这里在 --import 执行完的那一刻就把结论打出来。
                //
                // 顺带把 bin/pnpm 的真实形态也报一次（存在与否、ELF 还是脚本、
                // shebang 是什么、能不能认领到 node 入口）—— 这样真机上不必再跑
                // ls / head，日志本身就是取证。
                //
                // 全部在加载时算一次，不进 spawn 热路径（rg 会频繁 spawn）。
                logLine('loaded V$patchVersion node=' + (nodeBinary() || 'null') +
                  ' prefix=' + (PREFIX_RAW || '(空)'));

                try {
                  const pnpmBin = findBin('pnpm');
                  if (!pnpmBin) {
                    logLine('bin/pnpm: 不存在（PREFIX/bin 里没有 pnpm 入口）');
                  } else {
                    const ph = readHead(pnpmBin);
                    logLine('bin/pnpm: path=' + pnpmBin +
                      ' elf=' + isElf(ph) +
                      ' shebang=' + (shebangName(ph) || 'none') +
                      ' entry=' + (nodeEntryFor('pnpm') || scanEntry('pnpm') || 'null'));
                  }
                } catch (e) {
                  logLine('bin/pnpm 探测失败: ' + e);
                }

                // ── pnpm-native 形态探测（一次性）──────────────────────────────
                // corepack 把 pnpm 缓存在 HOME 下。pnpm 12 起以**原生二进制**分发：
                // 它的 optionalDependencies 里有 os=android/cpu=arm64 的
                // @pnpm/exe.android-arm64 —— 即官方 Android 构建，linker64 能加载。
                // 所以这里要确认装进来的到底是不是 android 变体：
                // 读 ELF 头的 e_machine（偏移 18，小端 2 字节），
                // 183 = AArch64，62 = x86-64。
                try {
                  const cache = path.join(process.env.HOME || '',
                    '.cache/node/corepack/v1/pnpm');
                  let vers = [];
                  try { vers = fs.readdirSync(cache); } catch { vers = []; }
                  if (vers.length === 0) {
                    logLine('pnpm-native: 尚未缓存（' + cache + '）');
                  } else {
                    for (let i = 0; i < vers.length; i++) {
                      const bin = path.join(cache, vers[i], 'pnpm-native');
                      if (!fs.existsSync(bin)) continue;
                      const h = readHead(bin);
                      const machine = (h && h.length >= 20) ? h.readUInt16LE(18) : 0;
                      logLine('pnpm-native: ' + bin +
                        ' elf=' + isElf(h) +
                        ' e_machine=' + machine +
                        ' size=' + fs.statSync(bin).size);
                    }
                  }
                } catch (e) {
                  logLine('pnpm-native 探测失败: ' + e);
                }
            """.trimIndent() + "\n"

            val ok = runCatching { patchFile.writeText(js) }.isSuccess
            if (!ok) {
                onLog("⚠️ 写入 exec 补丁失败，bash / rg 可能仍不可用")
                return null
            }
        }
        return patchFile.absolutePath
    }

    /**
     * 把下载好的 dsh 引擎包安装到容器里。
     *
     * 引擎包只需包含 `@deepseek-ai/` 这一棵子树 —— 第三方依赖（zod 等）
     * 留在内置容器里，Node 的模块解析会从
     * `lib/node_modules/@deepseek-ai/dsh/` 逐级向上找到它们。
     *
     * ## 为什么按「第一个 node_modules/」归一化
     *
     * 引擎包可能出自不同打包方式，三种布局都很常见：
     *   - `lib/node_modules/@deepseek-ai/...`   （与容器同构）
     *   - `node_modules/@deepseek-ai/...`       （npm 打包默认）
     *   - `@deepseek-ai/...`                    （直接压缩作用域目录）
     *
     * 硬编码某一种（比如剥 `lib/` 前缀）一旦与实际不符，文件就会落到
     * `usr/node_modules/` 而不是 `usr/lib/node_modules/`，结果是引擎"装上了
     * 但检测不到"，启动永远失败。所以这里统一做法：**丢弃第一个
     * `node_modules/` 之前的所有前缀**，再落到 `usr/lib/` 下。
     *
     * @param input 引擎包（zip）的输入流，由下载方提供。
     * @param totalBytes 压缩包总字节数；未知时传负数，进度退化为不确定。
     * @param onProgress 解压进度 0f..1f，可能在后台线程被调用。
     */
    fun installDsh(
        context: Context,
        input: java.io.InputStream,
        /** 压缩包总字节数；未知时传负数，进度退化为不确定。 */
        totalBytes: Long = -1L,
        /** 0f..1f 的解压进度（按压缩包已读字节估算）。可能在后台线程被调用。 */
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        if (!runtimeInstalled(context)) {
            onLog("❌ 容器未安装，无法安装 dsh 引擎")
            return false
        }
        // ── 前置校验：只接受 zip ────────────────────────────────────────────
        // 常见误用是把 npm registry 的 .tgz 直链丢进来。那是 gzip+tar，
        // ZipInputStream 完全无法解析；直接抛"zip END header not found"
        // 之类的底层异常，很难据此判断原因。这里嗅一下魔数，给出可行动的提示。
        val buffered = java.io.BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val magic = ByteArray(4)
        val read = runCatching { buffered.read(magic) }.getOrDefault(0)
        runCatching { buffered.reset() }
        if (read >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) {
            onLog("❌ 这是 .tgz（gzip+tar）包，不是 zip，无法解压")
            onLog("❌ 且 npm 的 .tgz 只含 dsh 本体，不含它依赖的几十个 @deepseek-ai 同级包")
            onLog("❌ 正确做法：在电脑上 npm install 后把 @deepseek-ai 目录打成 zip")
            return false
        }

        onLog("⏳ 正在安装 dsh 引擎…")

        // 统计解压期间从压缩包读了多少字节，用来算进度。
        // ZipInputStream 没有暴露「已读压缩字节」，所以自己包一层。
        // 注意要在魔数嗅探（mark/reset）**之后**再包，否则那 4 字节会被重复计数。
        val counting = CountingInputStream(buffered)
        var lastReportAt = 0L

        // 先解压到临时目录，读到真实版本号后再改名成 lib/versions/<ver>。
        // 直接往最终位置写的话，中途失败会留下一个「看起来存在、其实残缺」
        // 的版本目录，之后每次启动都失败却查不出原因。
        val incoming = File(versionsRoot(context), INCOMING)
        runCatching { incoming.deleteRecursively() }
        incoming.mkdirs()
        val libDir = incoming

        // 数据目录（插件 / MCP / 会话 / 登录状态）的暂存位置。
        //
        // ★ 必须放在 incoming **之外** ★
        // incoming 最后会被整体改名成 lib/versions/<ver>，数据若暂存在里面，
        // 就会跟着引擎一起进版本目录 —— 那是错的，数据归属 home/.dsh-<ver>。
        val dataStaging = File(versionsRoot(context), INCOMING_DATA)
        runCatching { dataStaging.deleteRecursively() }

        var count = 0
        var written = 0L
        var dataCount = 0
        val samples = ArrayList<String>(8)

        try {
            ZipInputStream(counting).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name

                    // ── 数据目录条目：dsh-data/<相对路径> ───────────────────
                    //
                    // 这一判断必须排在 node_modules 归一化**之前**：
                    // 数据里也可能出现名叫 node_modules 的目录（插件自带依赖），
                    // 若先走归一化，这些文件会被错写成 node_modules/... 塞进引擎里，
                    // 既污染引擎又会丢掉插件的依赖。
                    if (name.startsWith(DATA_PREFIX)) {
                        val relData = name.substring(DATA_PREFIX.length)
                        if (relData.isNotBlank()) {
                            val outData = resolveInside(dataStaging, relData)
                            if (outData == null) {
                                onLog("⚠️ 跳过可疑数据条目: $name")
                            } else if (entry.isDirectory) {
                                outData.mkdirs()
                            } else {
                                outData.parentFile?.mkdirs()
                                FileOutputStream(outData).use { fos -> zis.copyTo(fos) }
                                dataCount++
                            }
                        }
                        // 数据条目也要报进度，否则大包里会有很长一段「不动」
                        val nowD = System.currentTimeMillis()
                        if (nowD - lastReportAt > 200) {
                            lastReportAt = nowD
                            onProgress(
                                if (totalBytes > 0) {
                                    (counting.count.toFloat() / totalBytes).coerceIn(0f, 1f)
                                } else {
                                    -1f
                                }
                            )
                        }
                        entry = zis.nextEntry
                        continue
                    }

                    // 归一化：定位 node_modules/ 的起始位置，丢弃它之前的前缀
                    val nm = name.indexOf("node_modules/")
                    val rel: String? = when {
                        nm >= 0 -> name.substring(nm)
                        // 包内直接就是作用域目录
                        name.contains("@deepseek-ai/") ->
                            "node_modules/" + name.substring(name.indexOf("@deepseek-ai/"))
                        else -> null
                    }

                    if (rel == null) {
                        entry = zis.nextEntry
                        continue
                    }

                    if (samples.size < 8) samples.add(rel)

                    val outFile = resolveInside(libDir, rel)
                    if (outFile == null) {
                        onLog("⚠️ 跳过可疑条目: $name")
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            written += zis.copyTo(fos)
                        }
                    }
                    count++
                    // 进度分两条路报：
                    // - 知道压缩包总大小时，按已读字节算真实百分比（每 200ms 限一次，
                    //   否则几千个条目会把主线程刷爆）；
                    // - 不知道时（SAF 流没有长度），退化成 -1f 让 UI 显示不确定进度条。
                    val now = System.currentTimeMillis()
                    if (now - lastReportAt > 200) {
                        lastReportAt = now
                        onProgress(
                            if (totalBytes > 0) {
                                (counting.count.toFloat() / totalBytes).coerceIn(0f, 1f)
                            } else {
                                -1f
                            }
                        )
                    }
                    if (count % 500 == 0) {
                        onLog("… 已安装 $count 项（${written / 1024 / 1024} MB）")
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            onLog("❌ dsh 引擎解压异常: ${e.message}")
            runCatching { incoming.deleteRecursively() }
            runCatching { dataStaging.deleteRecursively() }
            return false
        }

        if (count == 0) {
            runCatching { incoming.deleteRecursively() }
            runCatching { dataStaging.deleteRecursively() }
            onLog("❌ 引擎包里没有任何 node_modules 内容，包结构不对")
            return false
        }
        onLog("✅ 解压 dsh 引擎：$count 项，${written / 1024 / 1024} MB")
        if (dataCount > 0) {
            onLog("✅ 解压数据目录：$dataCount 个文件（插件 / 会话 / 登录状态等）")
        } else {
            onLog("ℹ️ 这个包里没有数据目录条目，导入后使用空数据")
        }
        // 打印几个条目名，便于确认包结构（配合日志面板排查）
        onLog("   条目示例: ${samples.joinToString(", ")}")

        val marker = File(incoming, ENGINE_MARKER)
        if (!marker.isFile || marker.length() == 0L) {
            runCatching { incoming.deleteRecursively() }
            runCatching { dataStaging.deleteRecursively() }
            onLog("❌ 引擎已解压，但未找到 $ENGINE_MARKER")
            onLog("❌ 请确认包内包含 @deepseek-ai/dsh/lib/bin.js")
            return false
        }

        // 版本号以包内 package.json 为准，不让用户填 ——
        // 目录名就是这个版本号，写错了以后「切换版本」会指到一个不存在的地方。
        val ver = runCatching {
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                .find(File(incoming, "node_modules/@deepseek-ai/dsh/package.json").readText())
                ?.groupValues?.get(1)
        }.getOrNull()

        if (ver.isNullOrBlank()) {
            runCatching { incoming.deleteRecursively() }
            runCatching { dataStaging.deleteRecursively() }
            onLog("❌ 读不出版本号（@deepseek-ai/dsh/package.json 缺失或损坏）")
            return false
        }

        // 同版本已经在磁盘上时**自动加后缀**（`0.2.1` → `0.2.1-2`），
        // 不需要用户起名字或确认：部署的标识就是版本键。
        //
        // 不删旧目录：allocateVersion 保证返回空闲键，会删的只可能是别人的部署。
        val key = Instances.allocateVersion(context, ver)
        val dest = engineRoot(context, key)
        dest.parentFile?.mkdirs()

        val moved = incoming.renameTo(dest) || runCatching {
            incoming.copyRecursively(dest, overwrite = true)
            incoming.deleteRecursively()
        }.isSuccess

        if (!moved) {
            runCatching { incoming.deleteRecursively() }
            runCatching { dataStaging.deleteRecursively() }
            onLog("❌ 无法落盘到 lib/versions/$key")
            return false
        }

        // ── 部署数据目录（插件 / 插件数据 / MCP / 会话 / 登录状态）──────────
        //
        // 目录名必须与 [dshHome] 的算法一致：`home/.dsh-<版本键>`。
        // 这里不能调 dshHome() —— 它依赖 currentVersion，而指针要到下面才设置，
        // 此刻读到的还是上一个版本，会把数据装到别的版本目录里去。
        if (dataCount > 0) {
            val dataTarget = File(instanceHome(context), ".dsh-$key")
            if (dataTarget.isDirectory && dataTarget.list()?.isNotEmpty() == true) {
                // 已有数据先改名备份，而不是直接删。
                // 导入整合包通常是「恢复某人整理好的一整套环境」，
                // 覆盖是预期行为，但万一导错了包，.bak 还能救回来。
                val bak = File(dataTarget.parentFile, ".dsh-$key.bak")
                runCatching { bak.deleteRecursively() }
                val backedUp = dataTarget.renameTo(bak)
                if (!backedUp) {
                    // 改名失败（跨挂载点等）就直接删，不能让旧数据挡住新数据
                    runCatching { dataTarget.deleteRecursively() }
                }
                onLog(
                    if (backedUp) "ℹ️ 原有数据已备份为 ${bak.name}"
                    else "ℹ️ 原有数据已清理"
                )
            }
            dataTarget.parentFile?.mkdirs()

            val dataMoved = dataStaging.renameTo(dataTarget) || runCatching {
                dataStaging.copyRecursively(dataTarget, overwrite = true)
                true
            }.getOrDefault(false)

            if (dataMoved) {
                runCatching { dataStaging.deleteRecursively() }

                // ── 兜底：丢掉包里可能夹带的密钥载体 ──────────────────────
                //
                // 导出侧已经会剔除 `.env` 与 `android-account.json`，但用户手里
                // 的包可能是**旧版本应用**导出的 —— 那时这两个文件会原样进包。
                // 导入方如果照单全收，就会在毫无察觉的情况下用上**别人的密钥**：
                // 花钱的是对方的账号，出问题也查不到源头。
                //
                // 删掉不影响可用性：下次启动时 [AccountStore] 会用本机自己的
                // 账号重新生成这两个文件；本机没有账号就等你填。
                var dropped = 0
                for (name in PACKAGE_SECRET_FILES) {
                    val f = File(dataTarget, name)
                    if (f.exists() && runCatching { f.delete() }.getOrDefault(false)) dropped++
                }
                if (dropped > 0) {
                    onLog("🔑 已丢弃整合包里的 $dropped 个密钥文件（.env / android-account.json）")
                    onLog("ℹ️ 本实例将使用本机自己的账号，请在账号页确认后再启动")
                }

                onLog("✅ 数据目录已恢复：${dataTarget.name}（$dataCount 个文件）")
            } else {
                runCatching { dataStaging.deleteRecursively() }
                onLog("⚠️ 引擎已安装，但数据目录落盘失败（插件/会话可能需要重装）")
            }
        } else {
            runCatching { dataStaging.deleteRecursively() }
        }

        // 指针指向新的版本键 —— 否则装完不会自动切过去
        setCurrentVersion(context, key)
        onLog("✅ dsh 引擎安装完成（$ver → 目录 $key，已设为当前版本）")
        return true
    }

    /**
     * 统计从底层流读过的字节数，用来估算解压进度。
     *
     * `ZipInputStream` 只暴露「当前条目解压后的大小」，不暴露「从压缩包读了多少
     * 压缩字节」，而进度条需要的是后者（前者要等整包读完才知道总量）。
     * 包一层 FilterInputStream 是最轻的做法，不改变任何读取语义。
     */
    private class CountingInputStream(
        src: java.io.InputStream
    ) : java.io.FilterInputStream(src) {

        /** 已读取的字节数（只增不减，reset 不回退）。 */
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        // 跳过也要计数，否则 zip 里用 skip 跳过条目录尾时进度会低估
        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) count += skipped
            return skipped
        }
    }

    /** 容器包合法的顶层目录。首条目若落在这些名字下，说明没有包装层。 */
    private val KNOWN_ROOTS = setOf(
        "bin", "lib", "etc", "share", "usr", "tmp", "opt", "var", "home"
    )

    /**
     * 判断 zip 是否多套了一层包装目录。
     *
     * 打包工具的行为不统一：直接压缩目录内容时根下就是 `bin/`、`lib/`；
     * 而右键压缩某个文件夹时会多出一层（例如 `runtime/bin/...`）。
     * 若不解开这一层，文件会落到 `usr/runtime/bin/node`，
     * 检测找的是 `usr/bin/node`，于是「解压成功但找不到 node」。
     *
     * 这里只看**第一个条目**推断：它的首段若不是已知根目录，就视为包装层。
     * 判定一次即固定，后续条目统一按同一前缀处理，避免中途切换造成错位。
     *
     * @return 要剥掉的前缀（含结尾斜杠），无包装层时返回 null。
     */
    private fun detectWrapper(firstEntryName: String): String? {
        if (!firstEntryName.contains('/')) return null
        val firstSegment = firstEntryName.substringBefore('/')
        return if (firstSegment in KNOWN_ROOTS) null else "$firstSegment/"
    }

    /**
     * 解压 zip 到 [destDir]（用于内置容器）。
     *
     * 会自动识别并剥离单层包装目录（见 [detectWrapper]），
     * 因此压缩包内是 `bin/...` 还是 `runtime/bin/...` 都能正确落盘。
     */
    private fun extractZip(
        input: java.io.InputStream,
        destDir: File,
        label: String
    ): Boolean {
        destDir.mkdirs()
        var count = 0
        var written = 0L
        val samples = ArrayList<String>(8)
        // null = 尚未判定；"" = 无包装层；"xxx/" = 要剥掉的包装层
        var stripPrefix: String? = null
        try {
            ZipInputStream(java.io.BufferedInputStream(input, 64 * 1024)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (samples.size < 8) samples.add(entry.name)

                    // 用首条目判定一次包装层，之后固定不变。
                    // 注意：stripPrefix 被 lambda 捕获，无法智能转换，
                    // 所以用局部 val 承接判定结果再赋值。
                    if (stripPrefix == null) {
                        val decided = detectWrapper(entry.name) ?: ""
                        stripPrefix = decided
                        if (decided.isNotEmpty()) {
                            onLog("   已识别包装层 '$decided'，将自动剥离")
                        }
                    }

                    // orEmpty()：stripPrefix 此刻已确定非空（上面刚判定过），
                    // 用 orEmpty 而不是 ?: "" 以免触发 "Elvis 多余" 的告警。
                    val prefix = stripPrefix.orEmpty()
                    var name = entry.name
                    if (prefix.isNotEmpty() && name.startsWith(prefix)) {
                        name = name.substring(prefix.length)
                    }
                    // 包装层自身那个目录条目，剥完就空了，跳过
                    if (name.isEmpty()) {
                        entry = zis.nextEntry
                        continue
                    }

                    // zip-slip 防护：拒绝 .. 和绝对路径
                    val outFile = resolveInside(destDir, name)
                    if (outFile == null) {
                        onLog("⚠️ 跳过可疑条目: ${entry.name}")
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            written += zis.copyTo(fos)
                        }
                    }

                    count++
                    if (count % 500 == 0) {
                        onLog("… 已处理 $count 项（${written / 1024 / 1024} MB）")
                    }
                    entry = zis.nextEntry
                }
            }
            onLog("✅ $label 解压完成：$count 项，${written / 1024 / 1024} MB")
            if (samples.isNotEmpty()) {
                onLog("   条目示例: ${samples.joinToString(", ")}")
            }
            return true
        } catch (e: Exception) {
            onLog("❌ $label 解压异常: ${e.message}")
            return false
        }
    }

    /** 把相对路径解析到 [root] 内，越界返回 null（zip-slip 防护）。 */
    private fun resolveInside(root: File, name: String): File? {
        if (name.isBlank()) return null
        if (name.startsWith("/") || name.contains("..")) return null
        val f = File(root, name)
        return if (f.canonicalPath.startsWith(root.canonicalPath + File.separator)) f else null
    }

    /** 给目录下所有文件加可执行位（不含子目录内的文件）。 */
    private fun grantExec(dir: File) {
        dir.listFiles()?.forEach { runCatching { it.setExecutable(true, false) } }
    }

    /**
     * 启动 Node 进程。
     *
     * @param permissionMode 沙箱权限模式，默认取自 [LauncherPrefs]（安全默认值
     *   `workspace-write`）。调用方可以显式覆盖，阶段三做多实例时由实例配置决定。
     */
    fun startNode(
        context: Context,
        permissionMode: PermissionMode = LauncherPrefs.getPermissionMode(context)
    ) {
        // 多版本切换的前提是「同一时刻只跑一个」：
        // 若上一个进程还活着，先停掉，否则两个实例会抢同一个 DSH_HOME。
        if (process != null) {
            onLog("⏹ 已有实例在运行，先停止再启动")
            stopProcess()
        }
        try {
            val prefix = File(context.filesDir, "usr").absolutePath
            // HOME 就是 filesDir/home —— **不要改成外部存储**。
            //
            // 曾经为了绕开 /data/user/0 的 0711 限制把 HOME 迁到外部目录，
            // 结果 dshHome() 在迁移执行前就被别的调用点触发，在空目录里建出
            // 了 .dsh-<版本>，迁移因此判定「已有内容」而跳过 ——
            // 用户的插件、登录状态、会话历史全部「消失」。详见 AppHome.kt。
            //
            // 读图工具的限制改用另一条路解决：截图会额外写一份到
            // ScreenCaptureState.externalScreenshotFile（只涉及图片文件，
            // 不碰 HOME 下的任何数据）。
            //
            // migrateHomeIfNeeded 现在的职责是**反向收回复原**：
            // 若上一版在外部目录里留下过真实数据，把它合并回内部目录。
            // 它在**本工作线程**里跑，不阻塞 UI；失败不阻断启动。
            // 实例模式下 HOME 是 `instances/<id>/home` —— 每个实例独一份，
            // 工作区（Documents）与 `$DSH_HOME` 都挂在它下面。
            // 没有实例时回落老的 `filesDir/home`，并顺手把上一版误迁到外部存储的
            // 数据收回来（migrateHomeIfNeeded 现在的职责就只有这一件事）。
            val home = (
                if (Instances.activeId(context) != null) instanceHome(context)
                else migrateHomeIfNeeded(context)
                ).absolutePath
            val tmp = File(context.cacheDir, "tmp").apply { mkdirs() }.absolutePath

            val nodePath = File(prefix, "bin/node").absolutePath

            // 引擎按「当前版本指针」解析；指针失效时 currentVersion 会自动回落
            val activeVersion = currentVersion(context)
            val entry = engineEntry(context, activeVersion)
            val binJsPath = entry?.absolutePath.orEmpty()

            // 容器与引擎分别校验：容器内置、引擎后天下载，缺哪个就说清哪个，
            // 避免只丢一句「启动失败」让人无从下手。
            if (!File(nodePath).exists()) {
                onLog("❌ 内置容器缺失（找不到 bin/node）")
                onLog("❌ 请确认 app/src/main/assets/runtime.zip 已放入并重新安装")
                return
            }
            if (entry == null || !entry.isFile) {
                onLog("❌ 未安装 dsh 引擎（lib/versions 下没有可用版本）")
                onLog("❌ 请在「下载中心」下载 dsh 引擎后重试")
                return
            }
            onLog("引擎版本: $activeVersion")

            // 用纯 JS 实现替换 flock 的原生绑定。
            //
            // `@deepseek-ai/node-addon-system` 的 flock 依赖平台原生模块，
            // 而上游只发布 linux / darwin 构建，**没有 android**。
            // 其 musl 二进制引用 `__errno_location`，Android 的 bionic 没有
            // 这个符号（bionic 用 `__errno()`），dlopen 必然失败：
            //   dlopen failed: cannot locate symbol "__errno_location"
            //
            // 所以伪装平台、补装 linux 二进制都走不通 —— 那不是配置问题，
            // 是 ABI 层面的不兼容。唯一可行的做法是**直接替换 flock.js**，
            // 用锁文件实现同等语义，绕开原生模块。
            ensureFlockShim(context)

            // ── 终端 PTY 后端（node-pty 的 Android 实现）────────────────────
            //
            // 「新建终端」用不了是两层问题：subprocess-local 的进程探测器只认
            // linux/darwin/win32（Termux 的 node 报 android），而 node-pty 在
            // Android 没有预编译产物、runtime 里是抛错的空壳。
            //
            // 这里在 spawn 之前把随 APK 分发的原生助手落到 $PREFIX/bin，并把
            // node-pty 换成转发到它的实现 + 给两处平台判断放行 android。
            //
            // ★ 为什么放在启动前而不是打包时 ★
            // 引擎是首启后按需 npm 装的，同一份 runtime 会被装进
            // lib/versions/<版本>/node_modules；打包时改只能覆盖容器自带的那份，
            // 换个版本就重新撞上「终端不可用」。这里按磁盘实际布局装配，幂等。
            //
            // 失败**不阻断启动**：终端用不了是缺功能，引擎起不来才是事故。
            PtySupport.ensure(context, prefix, onLog)

            // ── 文件系统适配（dsh-fs-local 的 EACCES 降级）──────────────────
            //
            // 「文件资源服务不可用」/某个目录一个文件都列不出来，根因是上游把
            // Android 共享存储（FUSE）返回的 EACCES 当成致命 IO 故障：一次
            // stat 失败就让整次目录列举 throw。这里改成「无法观测」，
            // 并把单个不可读的子项降级成 other，不再中断整个列表。
            //
            // 与 PTY 同一原因放在启动前：引擎按版本装在
            // lib/versions/<版本>/node_modules，打包时改只能覆盖容器那份。
            FsLocalPatch.ensure(context, prefix, onLog)

            // ── 交互式 shell 的路径与可执行位 ───────────────────────────────
            //
            // 终端里报 `command "/data/data/com.termux/files/usr/bin/bash"
            // is not an executable file` —— dsh 在 Android 上把 shell 解析成了
            // **Termux 的**私有路径，那是别的 uid 的 0700 目录，本应用连 stat
            // 都不行。这里把真实容器里的 bash 作为 SHELL 下发，并把已装引擎里
            // 写死的 Termux 前缀改成本机容器。
            //
            // 同时补一次可执行位：`ZipInputStream` 解压不还原 Unix 权限位，
            // 而 grantExec() 只在首次解压后跑一次；老安装或解压中断过的容器会
            // 留下没有 +x 的 bash，那时即使路径对了，前置检查也会报同一句话。
            val shellPath = ShellEnvFix.ensure(context, prefix, onLog)

            // ── 原生绑定替身（与 flock 同一思路的第二个实例）───────────────
            //
            // 上游 node-addon-require-builtin 从未发布 android 变体，
            // 而 0.2.1-rc.5 / 0.2.2-alpha.2 会在 boot 阶段强制要求它。
            // 加载器只校验导出形状、不校验是否为二进制，所以用纯 JS 顶上，
            // 能力来自启动参数里已有的 --expose-internals。
            //
            // 这里再装配一次（预检里已经装过）：走「允许启动不受支持的引擎」
            // 那条路时预检会被跳过，必须在启动前兜住。
            runCatching {
                RequireBuiltinShim.ensure(context, onLog)
            }.onFailure { onLog("⚠️ 原生绑定替身装配失败: ${it.message}") }

            // 原生绑定预检**不在这里**做。
            //
            // 它被移到了 MainActivity.startInstance：判定「这个版本跑不起来」
            // 时要能直接中止启动并把状态置成 ERROR，而 startNode 是 void，
            // 在这里 return 只会让界面永远停在「启动中」。
            //
            // 详见 NativeBindingGuard 的类注释。

            onLog("正在启动 Node 进程...")

            // dsh 的"默认工作区"由 Host controller 按 Documents 路径策略推导。
            // ProcessBuilder 默认继承 App 的 cwd —— 在 Android 上是 "/"，不可写，
            // 在根目录下 mkdir 必然失败，前端就报"无法创建默认工作区"。
            // 所以这里必须显式把 cwd 指到可写的 HOME。
            val workDir = File(home).apply { mkdirs() }

            // 预建落盘目录，消除"目录失败即拒绝初始化"的失败面。
            //
            // 工作区（Documents）所有版本共用：它是用户自己的文件与项目，
            // dsh 的状态（会话 / 插件 / 凭据）已由 DSH_HOME 按版本隔离，
            // 两者职责不同，不必都切。
            val documentsName = workspaceDir(context).name
            val documentsDir = File(workDir, documentsName).apply { mkdirs() }

            // 工作区必须是**真实目录**，不能是软链。
            //
            // 曾把 Documents 软链到 /storage/emulated/0/dshdata/Documents，
            // 结果 dsh 无法新建会话：
            //   session/workspace-attach-failed:
            //   cannot attach session to workspace '<...>/files/home/Documents':
            //   its cwd resolves to '/storage/emulated/0/dshdata/Documents'
            // 它拿记录路径与 realpath 比对，软链让两者不一致就直接拒绝。
            // 老会话不受影响（不需要重新挂载），所以症状是「新会话一律失败」。
            //
            // restoreWorkspaceIfExternalised 会把已存在的软链改回真实目录
            // （含把外部数据搬回来），已升级的设备也能自动恢复。
            restoreWorkspaceIfExternalised(documentsDir)

            // 每个版本自己的数据目录，互不相通。
            // ensureVersionDataDir 还会在老用户第一次升级时迁移一次旧数据。
            val dshHomeDir = ensureVersionDataDir(context)

            // 把 $DSH_HOME/attachments 软链到公开目录。
            //
            // 不改 DSH_HOME（版本隔离完全保持），只给附件这一棵子树换物理位置：
            // dsh 的 read_image / 图片上传都要往 attachments 里落盘，而它的
            // ensureDurableDirectory 会逐级 fsync 到根，走到 /data/user/0
            // （root:root 0711 的符号链接）时 open(O_RDONLY) 直接 EACCES。
            // 软链后 realpath 落到 /storage/emulated/0/...，整条上行路径都不再经过它。
            // 详见 AttachmentsStorage.kt。
            ensureAttachmentsExternal(context, dshHomeDir)

            // 清掉迁移过程留下的临时目录（只删 .seed-tmp，绝不碰 .dsh-<ver>）
            val stale = cleanupLegacyIsolation(context)
            if (stale > 0) onLog("🧹 已清理迁移临时目录")

            // ★ 先把 su 探测一次，再写 agent 说明 ★
            //
            // AGENTS.md 里要写「你现在是不是 root、有没有 su」。而真正调
            // `Privilege.usableSuPrefix()` 的地方在下面构造 ProcessBuilder 时，
            // **晚于** AgentGuide.refresh —— 顺序反过来的话，Root 模式下
            // 文档会写成「未检测到可用的 su」，把一条正确信息写成错的，
            // 而 agent 会据此不敢用 root 能力。
            //
            // 这里预热一次：探测结果进缓存，后面的启动流程直接复用同一个答案，
            // 不会出现「文档说有、实际启动又探一遍得不同结果」的分裂。
            // 本方法运行在工作线程上，允许起子进程。
            if (Privilege.getMode(context) == PrivMode.ROOT) {
                runCatching { Privilege.usableSuPrefix() }
            }

            // 在 dsh 起来之前刷新一次 agent 说明（AGENTS.md）。
            //
            // 桥接启动时（MainActivity.onCreate / 无障碍服务连接）也会刷一次，
            // 但那时 Documents/deepseek-harness 可能还不存在 —— 首次启动就是
            // 这种情况。放在这里保证「引擎即将拉起、工作区已建好」这个时刻
            // 说明文件一定就位，agent 第一轮对话读到的就是它。
            runCatching { AgentGuide.refresh(context) }
                .onFailure { onLog("⚠️ 刷新 agent 说明失败: ${it.message}") }

            // ── 关键修复：让 dsh 能找到 Documents ────────────────────────────
            // dsh 把首次默认工作区放在 <Documents>/deepseek-harness 下，而 Android 上
            // 既没有 xdg-user-dir 二进制，也没有 ~/.config/user-dirs.dirs，
            // 于是 Documents 探测失败 → 前端报"无法创建默认工作区"。
            // 这里两条路一起给：PATH 上的 shim 脚本，加上 xdg 读的配置文件。
            // 相比用 --patch 覆盖 Cordis 行，这条路完全不碰别人的模块配置 ——
            // patch 是"整行替换 config"，漏写键会让该行静默失效并导致白屏。

            // 1) PATH 上的 xdg-user-dir shim（PREFIX/bin 本来就在 PATH 里）
            val xdgShim = File(File(prefix, "bin").apply { mkdirs() }, "xdg-user-dir")
            xdgShim.writeText(
                """
                #!/system/bin/sh
                case "${'$'}1" in
                  DOCUMENTS) echo "${'$'}HOME/Documents" ;;
                  DOWNLOAD)  echo "${'$'}HOME/Download" ;;
                  DESKTOP)   echo "${'$'}HOME/Desktop" ;;
                  *)         echo "${'$'}HOME" ;;
                esac
                """.trimIndent() + "\n"
            )
            xdgShim.setExecutable(true, false)

            // 2) xdg-user-dir 实际读取的配置文件，防止 dsh 直接解析它
            val userDirs = File(workDir, ".config/user-dirs.dirs").apply {
                parentFile?.mkdirs()
            }
            userDirs.writeText(
                "XDG_DOCUMENTS_DIR=\"\$HOME/Documents\"\n" +
                    "XDG_DOWNLOAD_DIR=\"\$HOME/Download\"\n"
            )

            // 3) 截屏命令替身（screencap / screenrecord）
            //
            // agent 在探索「怎么读屏」时会先按习惯试 screencap。真 screencap 在
            // untrusted_app 域里必然写出 0 字节，它就会一路试下去。这里把命令名
            // 换成读取应用持续落盘的截图，让**第一次尝试就成功**。
            ensureScreenShims(context, prefix)

            // 4) a11y 命令 —— agent 的统一调用入口。
            //
            // 与参考实现的做法一致：在 PATH 上提供一个包装命令，鉴权在脚本内部
            // 完成，agent 不需要读 token、不需要拼 curl。AGENTS.md 里教的就是它。
            ensureA11yCli(prefix)

            // 使用 linker64 绕过 Android 执行限制。
            // 参数顺序有讲究：--patch 是 dsh 自己的 launcher flag，
            // 必须排在 app 的 inner flags（--no-open/--host/--port）之前，
            // 否则它会被当成 web app 的参数而静默忽略。
            //
            // useOverlay 先关掉：上一次白屏就是 --patch 的"整行替换 config"语义造成的
            // （只写了一个键，把该行原有配置全抹了）。保留开关是为了后续
            // 在拿到真实配置键之后能一键打开。
            val useOverlay = false

            // 动态分配端口：写死 3080 时，只要上次的 Node 没被彻底杀掉，
            // 下次启动就必然撞 EADDRINUSE 导致 startup failed。
            // 换端口能把这个死结化开，残留进程重启手机后自然消失。
            val port = allocatePort(3080)
            if (port == 0) {
                onLog("❌ 3080-3099 全部被占用，无法启动")
                onLog("❌ 可能有上次残留的 Node 进程，重启手机即可释放")
                return
            }
            currentPort = port
            onLog("监听端口: $port")

            // ── Shizuku 通道（幂等刷新，不依赖引擎端口）────────────────────
            //
            // 桥自己占 18091 起的端口段，与引擎端口无关；这里只是「顺手对齐一次」
            // 状态文件，保证引擎起来后 `shz` 立刻能用。
            //
            // 真正的启停由 Shizuku 状态驱动（见 ShizukuBridge.watchState），
            // 所以这里失败不影响任何东西 —— 也不该影响引擎启动。
            runCatching { ShizukuBridge.start(context, prefix) }
                .onFailure { onLog("⚠️ Shizuku 通道对齐失败: ${it.message}") }

            // ── 引擎状态自检 ────────────────────────────────────────────
            // 排查 flock 时一直缺少"设备上引擎到底长什么样"的数据，
            // 这里每次启动顺手打几行（只列目录，开销可忽略）。
            logEngineState(context)

            // ── 平台修正（--import 预加载）────────────────────────────────
            // flock 报错里的 "android-arm64" 是 `${process.platform}-${process.arch}`
            // 拼出来的，属运行时的**平台白名单检查**，与装了什么包无关。
            // 所以唯一的解法是让 process.platform 在运行期报 'linux'。
            //
            // 之前试过用 bootstrap 替换入口（node bootstrap.mjs bin.js），
            // 结果 dsh 能起但零输出、窗口不弹 —— 因为 bin.js 不再是主模块，
            // 它内部的 import.meta.main / argv[1] 判断全部失效。
            //
            // 已移除平台伪装（--import 改写 process.platform）。
            //
            // 原因：伪装成 linux 后，flock 会去加载 linux 原生二进制，
            // 那是 musl/glibc 构建，在 bionic 上 dlopen 直接失败
            // （cannot locate symbol "__errno_location"）。伪装只是把
            // 「平台不支持」换成了「二进制加载不了」，并没有真正解决问题。
            //
            // 现在保持 platform 为真实的 'android'，改为替换 flock.js 本身的
            // 实现（见 ensureFlockShim）。这样 ripgrep 等平台包也能正确解析到
            // 容器自带的 android 变体，不会互相干扰。

            // fs 兼容补丁：拦截硬链接失败，降级为复制。
            val fsPatch = ensureFsPatch(prefix)

            // exec 补丁：把 PREFIX 下的 ELF（bash / rg 等）改走 linker64。
            // Android 10+ 不允许从 App 数据目录直接 exec，dsh spawn 它们会 EACCES。
            val spawnPatch = ensureSpawnPatch(prefix)

            val args = mutableListOf(
                "/system/bin/linker64",
                nodePath,
                "--expose-internals"
            )
            // --import 必须在脚本路径之前，Node 才会当自己的参数处理；
            // 可以传多次，按顺序预加载
            if (fsPatch != null) args += listOf("--import", fsPatch)
            if (spawnPatch != null) args += listOf("--import", spawnPatch)
            args += listOf(binJsPath, "--profile", "web")
            if (useOverlay) {
                args += listOf("--patch", File(workDir, "android-overlay.yml").absolutePath)
            }
            args += listOf("--no-open", "--host", "127.0.0.1", "--port", port.toString())

            // ★ 环境变量先收进普通 Map，ProcessBuilder 最后才构造 ★
            //
            // 普通模式下它直接灌进 `pb.environment()`；但 Root 模式下命令要经
            // `su -c "<整行>"` 执行，而 su 没有传环境的能力 —— 变量必须**拼进
            // 那一行**。若仍用 `pb.environment()`，Root 模式会静默丢掉
            // PREFIX / HOME / DSH_HOME / NODE_PATH 等全部关键变量，引擎必然起不来。
            val env = LinkedHashMap<String, String>()
            env["PREFIX"] = prefix
            env["HOME"] = home
            env["TMPDIR"] = tmp
            env["LD_LIBRARY_PATH"] = "$prefix/lib"
            env["PATH"] = "$prefix/bin:/system/bin"

            // ── OpenSSL 的配置入口 ★ 也是「绝对路径依赖」的修法 ★ ──────────
            //
            // 容器里的 Node / libssl 是在 Termux 环境里编译的，OPENSSLDIR 被
            // 写死成 `/data/data/com.termux/files/usr/etc/tls`。而本应用的容器在
            // `filesDir/usr`：装了 Termux 的设备上那是别人的 0700 私有目录
            //（EACCES），没装的设备上那路径压根不存在。
            //
            // 这里把 `$PREFIX/etc/tls/openssl.cnf` 准备好（缺就写一份最小配置），
            // 再用 OPENSSL_CONF / SSL_CERT_FILE / NODE_EXTRA_CA_CERTS 指过去，
            // 让 OpenSSL 一开始就有可读的配置与 CA，不再回落到那个写死的路径。
            //
            // ⚠️ 注入严格「文件存在才设」—— 指向不存在的文件比不设更糟，
            // 详见 OpenSslEnv 的注释。这里也不额外传 `--openssl-config`：
            // OPENSSL_CONF 已经覆盖同一条链路，多一个参数只是多一个
            // 「路径算错就整个进程起不来」的失败面。
            runCatching { OpenSslEnv.ensure(prefix, onLog) }
                .onSuccess { env.putAll(it) }
                .onFailure { onLog("⚠️ OpenSSL 环境准备失败: ${it.message}") }

            // ── 交互式 shell ★ 终端用它，不要让它猜 ★ ─────────────────────
            //
            // 不设这个变量时，dsh 在 Android 上会退到 Termux 的 bash 路径
            // （`/data/data/com.termux/files/usr/bin/bash`）—— 那是别的应用的
            // 私有目录，本应用连 stat 都会 EACCES，终端于是报
            // 「command "..." is not an executable file」。
            //
            // 只认容器里真实存在的那一个；万一不存在就退回系统的 sh，
            // 至少能起一个能用的终端而不是继续报错。
            env["SHELL"] = shellPath ?: "/system/bin/sh"
            Log.i("DSH-runner", "SHELL=${env["SHELL"]}")

            // ── 把容器的 node_modules 显式挂进解析链 ★ 必须 ★ ─────────────
            //
            // ## 修的是什么
            //
            // 引擎目录从 `files/usr/lib/versions/<ver>` 搬到了
            // `instances/<id>/usr/lib/versions/<ver>`。这一搬**打断了 Node 的
            // 向上查找链**，直接导致启动崩溃：
            //
            // ```
            // host preparation failed:
            //   No usable native binding found for node-addon-require-builtin-android-arm64
            // ```
            //
            // 原因：Node 解析 `require('x')` 时会从当前文件所在目录逐级往上
            // 找 `node_modules`。旧位置往上走第 5 层正好是
            // `files/usr/lib/node_modules`（容器自带的包，含 bionic 构建的原生模块）；
            // 新位置往上走只会到 `files/node_modules`，**永远碰不到容器那一层**。
            //
            // 代码里另一处注释其实早就写明了这个依赖关系（见 checkEngineDeps：
            // 「容器自带的包也算可解析，Node 会沿解析链往上找到 usr/lib/node_modules」），
            // 是我搬目录时漏掉了这条隐含耦合。
            //
            // `NODE_PATH` 让 CommonJS 的 `require` 额外搜索这个目录 ——
            // 本次崩溃的调用栈正是 CJS（Module._compile / Module.require），
            // 所以这一行就能恢复原来的解析能力。
            env["NODE_PATH"] = "$prefix/lib/node_modules"
            // 数据目录：隔离开启时按版本分开，关闭时用老位置。
            // 不要把这里写死成 "$home/.dsh" —— 那样和实例设置面板显示的对不上。
            env["DSH_HOME"] = dshHomeDir.absolutePath

            // 沙箱权限模式。取值来自用户设置（默认 workspace-write），
            // 不再在代码里写死 —— 详见 PermissionMode 的注释。
            // 注意：Android 缺少 bubblewrap/Landlock，workspace-write 下
            // bash/glob/grep 会被 dsh 拒绝执行；需要这些能力时用户须显式开启
            // 「完全访问」并接受相应风险。
            env["DSH_PERMISSION_MODE"] = permissionMode.id

            // ── 用户密钥注入（每次启动都注入，与 dsh 已有账号共存）──────────
            //
            // 把启动器里**当前选中**的账号写进环境变量（OPENAI_API_KEY 这类
            // 各家通用的变量名），同时落一份 $DSH_HOME/android-account.json
            // 给需要完整信息的插件读。
            //
            // ★ 与 dsh 已有配置共存 ★
            //
            // 把 env 现有内容传进去：**已经有值的键会被跳过**，绝不覆盖。
            // dsh 自己的账号（或其 env 文件里配好的密钥）优先级更高，
            // 启动器只在缺位时补上。上一版曾无条件覆盖 DEEPSEEK_BASE_URL，
            // 直接把用户原本能用的账号打成 404 —— 这类覆盖一律不再做。
            //
            // 没有选中账号时什么都不做：不塞空字符串，那会让 dsh 以为
            // 「配置了但值是空的」。
            runCatching {
                AccountStore.ensureLoaded(context)
                // applyToEnvironment 返回一份待**新增**的变量表，由这里写进 env。
                //
                // ★ 为什么不把 env 传进去让函数自己写 ★
                //
                // `pb.environment()` 在 Kotlin 里推断成只读的
                // `Map<String, String>`，当 `MutableMap` 形参传会报
                // 「Type mismatch」。让它返回 Map、这里 forEach 写入，
                // 两侧都不需要强转，也不用 @Suppress。
                val toAdd = AccountStore.applyToEnvironment(context, env)
                toAdd.forEach { (k, v) -> env[k] = v }
                if (toAdd.isEmpty()) {
                    onLog("ℹ️ 未注入密钥（没有选中账号，或 dsh 已自行配置）")
                }
                AccountStore.describeForLog()?.let { onLog("账号: $it") }
            }.onFailure { onLog("⚠️ 账号注入失败: ${it.message}") }

            // ── 第三方密钥：在 dsh 的 settings.yaml 里补一条提供方路由 ──────
            //
            // ★ 为什么环境变量不够，还必须写这里 ★
            //
            // 上面注入的 `*_API_KEY` 只解决「凭据在不在」，不解决「dsh 知不知
            // 道这个端点」。第三方中转站不是 pi-ai 内置的任何一条路由，光有 key
            // 的话 dsh 只会拿它去连官方域名 —— 这正是「创建成功了但 dsh 里没有」
            // 的根因。声明一条 `llm-pi-ai.providers.<路由>`，端点与模型才有归宿。
            //
            // ★ 只添加，不覆盖 ★
            //
            // 路由名撞上已有配置时 DshProviderConfig 直接跳过（不写一个字节），
            // 官方账号也整个跳过（走 dsh 内置 deepseek 路由）。详见该文件的注释。
            runCatching {
                val acc = AccountStore.selected()
                if (acc != null) {
                    // 必须用**实际下发给进程**的那个目录（dshHomeDir），
                    // 不要另推一遍 dshHome(context) —— 记忆里「多个调用点各自
                    // 推导同一个根路径」那条铁律就是踩过的坑。
                    onLog(DshProviderConfig.describe(DshProviderConfig.applyTo(dshHomeDir, acc)))
                }
            }.onFailure { DshProviderConfig.logFailure(it) }

            onLog("cwd: ${workDir.absolutePath}")
            onLog("Documents: ${documentsDir.absolutePath}")
            onLog("xdg shim: ${xdgShim.absolutePath}")
            onLog("DSH_HOME: ${env["DSH_HOME"]}")
            // 数据目录现在按版本独立，日志里明确报出来，便于确认隔离生效。
            onLog("数据目录: ${dshHomeDir.name}（本版本专用，与其他版本不互通）")
            onLog("权限模式: ${permissionMode.id}")
            if (permissionMode.dangerous) {
                onLog("⚠️ 完全访问模式：命令将以 App 身份无隔离执行")
            }
            // 沙箱后端探测。**必须在这里说清楚**：Android 一般既没有 bwrap
            // 也没有 Landlock，于是默认的 workspace-write 会让所有 bash 失败。
            // 不提前报的话，用户只会看到 agent 反复试 bash 然后报一句 dsh 内部的
            // unsupported，完全看不出根因是本机没后端。
            //
            // 本方法运行在工作线程上，所以允许 verify=true（会起一个短命子进程）。
            runCatching {
                SandboxBackend.logReport(
                    mode = permissionMode,
                    prefix = File(prefix),
                    onLog = onLog
                )
            }.onFailure { onLog("⚠️ 沙箱后端探测失败: ${it.message}") }

            // 这一次是全新启动：清掉上次的「正在停止」标记与残留输出，
            // 否则上一次主动停止留下的 stopping=true 会让这次崩溃被当成正常退出。
            // ── 按运行权限模式构造 ProcessBuilder ★ 必须在这里，不能更早 ★ ──
            //
            // 上面所有 env[...] 都只是往 Map 里写；到这里才决定「怎么把这份
            // 环境交给引擎」。两种模式的差别不只是多一层 su：
            // 普通模式能把环境作为**进程属性**传下去，Root 模式只能拼成命令行。
            val privMode = Privilege.getMode(context)
            val pb: ProcessBuilder = if (privMode == PrivMode.ROOT) {
                val suPrefix = Privilege.usableSuPrefix()
                if (suPrefix == null) {
                    // 没 su 就**不要**退回普通模式：用户明确选了 Root，
                    // 静默降级会让他以为「Restricted 模式生效了」，然后在
                    // 一个没有 root 的环境里执行本该高权限的命令 —— 更难排查。
                    Privilege.invalidateSuCache()
                    onLog("❌ 已选 Root 模式，但本机没有可用的 su")
                    onLog("❌ 请在「设置 → 运行权限」切回普通模式，或先授权 su")
                    return
                }
                // 整行命令：cd → env 注入 → exec 真正的 argv。
                // 每个片段都必须过 shellQuote：路径里出现 $、反引号、空格或
                // 单引号时，不引用就会被 shell 当成语法而不是字面量。
                val cmdline = buildString {
                    append("cd ").append(Privilege.shellQuote(workDir.absolutePath))
                        .append(" && exec env")
                    env.forEach { (k, v) ->
                        append(' ').append(Privilege.shellQuote("$k=$v"))
                    }
                    args.forEach { append(' ').append(Privilege.shellQuote(it)) }
                }
                onLog("⚠️ Root 模式：引擎将以 uid 0 运行（su ${suPrefix.joinToString(" ")}）")
                Log.i("DSH-runner", "root launch via ${suPrefix.joinToString(" ")}")
                ProcessBuilder(*(suPrefix + listOf(cmdline)).toTypedArray())
            } else {
                if (privMode == PrivMode.SHIZUKU) {
                    // 引擎本体仍是 App 身份 —— Shizuku 授予的是本进程的 binder
                    // 能力，改不了 fork 出来的子进程 uid。这条日志是为了让
                    // 「选了 Shizuku 但 ps 里还是 u0_aXXX」不至于被当成 bug。
                    onLog("ℹ️ Shizuku 模式：引擎仍以 App 身份运行（Shizuku 不改变子进程 uid）")
                }
                ProcessBuilder(args).apply {
                    // cwd 从 "/"（ProcessBuilder 默认继承 App 的不可写 cwd）换成 HOME
                    directory(workDir)
                    environment().putAll(env)
                }
            }

            stopping = false
            synchronized(recentOutput) { recentOutput.clear() }

            val started = pb.start()
            process = started
            onLog("✅ 进程已发起，等待输出...")

            // 监听 stderr。
            //
            // ★★ 这个线程必须自己吞掉异常 ★★
            // 进程被 stopProcess()/destroy() 结束时，这条流以 IOException
            //（"Stream closed" / "Bad file descriptor"）收场。
            // 普通 Thread 上的未捕获异常会**直接终止整个应用进程**，
            // 表现就是「点停止、点重启都崩溃」——这正是本 bug 的直接原因。
            Thread {
                try {
                    started.errorStream.bufferedReader().forEachLine {
                        // 留一份给「启动失败」时展示：用户不需要自己翻几百行 ERR
                        rememberOutput("ERR: $it")
                        onLog("ERR: $it")
                    }
                } catch (e: java.io.IOException) {
                    // 进程结束时的正常收场，安静退出
                } catch (e: Exception) {
                    onLog("ERR 流读取结束: ${e.message ?: e.javaClass.simpleName}")
                }
            }.start()

            val reader = started.inputStream.bufferedReader()
            val regex = Regex("""(http://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_-]+)""")

            // stdout 的读取同理：走流关闭时 readLine 会抛 IOException。
            // 外层 catch(Exception) 虽然兜得住，但会误报成「启动失败」，
            // 所以这里单独区分：流关闭 = 进程已退出，不是启动失败。
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    rememberOutput(line)
                    onLog(line)
                    regex.find(line)?.let { onUrlReady(it.value) }
                }
                // 循环正常结束 = 流到 EOF = 进程已退出。
                // 这里以前只写一句日志就收工，runState 会永远停在 STARTING。
                onLog("⏹ Node 输出流已关闭（进程已退出）")
            } catch (e: java.io.IOException) {
                onLog("Node 输出流已关闭（进程已退出）")
            } finally {
                // ── 关键：进程退出必须通知界面 ────────────────────────────
                //
                // 只有「退出的正是当前进程」才回调。
                // stopProcess() 已经先把 process 置空，所以**用户主动停止**
                // 会走到这里但 isCurrent=false —— 那种情况的界面状态由
                // stopInstance / restartInstance 自己负责，不该在这里再改一次。
                //
                // 反过来，进程自己崩掉时 process 仍指向它，isCurrent=true，
                // 界面就能从 STARTING 切到 ERROR 并拿到最后几行输出。
                if (process === started) {
                    process = null
                    currentPort = 0
                    val code = runCatching { started.exitValue() }.getOrNull()
                    onExited?.invoke(stopping, code)
                }
            }
        } catch (e: Exception) {
            onLog("❌ 启动失败: ${e.message}")
            // 拉起阶段就抛异常（进程根本没起来）：同样要让界面脱离 STARTING
            onExited?.invoke(false, null)
        }
    }
}