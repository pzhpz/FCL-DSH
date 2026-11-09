package com.example.fcldsh

import android.content.Context
import android.os.Build
import android.util.Log
import com.DSHRunner
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * 终端（PTY）后端装配 —— 让 dsh 的「新建终端」在 Android 上真正可用。
 *
 * ## 为什么需要这一步
 *
 * dsh 的 Web 终端与持久 bash 工具都经 `@deepseek-ai/dsh-subprocess-local` 调
 * `require("node-pty").spawn(...)` 申请一个**新的**伪终端。Android 上有两层断点：
 *
 * 1. **进程探测器拒收该平台**：`createProcessInspector()` 只认
 *    linux/darwin/win32，而 Termux 版 node 报 `process.platform === "android"`
 *    → 直接抛 `terminal inspection is unsupported on platform android`，
 *    于是任何终端 spawn 在这一步就失败。
 * 2. **node-pty 没有 Android 产物**：runtime 里是「一调用就抛错」的空壳。
 *
 * 修法分两半，都在本对象里：
 * - 把随 APK 分发的原生助手 `libdsh_pty_exec.so` 落到 `$PREFIX/bin/dsh_pty_exec`
 *   并 `chmod 0755`（它就是 fork 挂 PTY 的那个进程）；
 * - 把 node-pty 的 `lib/index.js` 换成转发到该助手的实现，并给
 *   `dsh-subprocess-local` 的两处平台判断放行 `android`
 *   （Android 有完整 procfs，上游的 Linux 实现本来就是对的，只是没被选上）。
 *
 * ## 为什么不能在打包时一次改好
 *
 * 引擎是**首启后按需安装**的：npm 装进 `$PREFIX/lib/versions/<版本>/node_modules`，
 * 版本可以并存、可以随时换。打包时改 runtime.zip 只能覆盖容器自带的
 * `$PREFIX/lib/node_modules`，换版本就会重新撞上「终端不可用」。
 * 所以在**每次启动引擎前**按磁盘实际布局装配一次（幂等，已装配的直接跳过）。
 *
 * ## 单一真源
 *
 * node-pty 的实现正文在 `assets/pty/node-pty-android.js`（文本，可直接改），
 * 原生助手在 `assets/pty/libdsh_pty_exec.so`（预编译，源见
 * `tools/reference-native/dsh_pty_exec.c`）。这里只负责搬运与两处平台放行。
 */
object PtySupport {

    private const val TAG = "DSH-pty"

    /** 资产目录：终端后端的两件材料都放这。 */
    private const val ASSET_DIR = "pty"

    /** 原生助手（AGP 只收 `lib*.so`，所以文件名带 lib 前缀；落地时改名）。 */
    private const val ASSET_HELPER = "libdsh_pty_exec.so"

    /** node-pty 的 Android 实现（替换 `node_modules/node-pty/lib/index.js`）。 */
    private const val ASSET_IMPL = "node-pty-android.js"

    /** 落地后的命令名：必须与资产里该实现的 EXEC_NAME 一致。 */
    private const val HELPER_NAME = "dsh_pty_exec"

    /**
     * 已装配的判据：实现里必然出现的版本串。
     *
     * **必须与 `assets/pty/node-pty-android.js` 的 `IMPL_REVISION` 逐字一致。**
     * 改了实现就把两边一起改 —— 否则已经写进 node_modules 的旧副本永远不会被
     * 替换（本项目在 Android 上没有 NDK，靠这个标记推进版本）。
     *
     * 用「实现自带的字符串」而不是另加一行注释，是为了让判据跟着内容走。
     */
    private const val IMPL_MARKER = "node-pty(android) rev:2"

    /** package.json 里的版本标记，与参考项目一致（仅作「已装配」判据）。 */
    private const val PTY_VERSION_MARKER = "0.0.0-android-ptyexec"

    /** EM_X86_64 / EM_AARCH64，用于在前几位字节上认一下 ELF 架构。 */
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183

    /**
     * 一处平台放行。
     *
     * 锚点取自上游 0.2.1-rc.2/rc.5，只替换**一处**、其余语义不动。
     */
    private class PlatformPatch(
        val dir: String,
        val filePrefix: String,
        val old: String,
        val new: String,
        val why: String
    )

    private val PLATFORM_PATCHES = listOf(
        PlatformPatch(
            dir = "@deepseek-ai/dsh-subprocess-local/lib",
            filePrefix = "runner-launch",
            old = "if (platform === \"linux\") return new LinuxProcessInspector(arch, internals);",
            new = "if (platform === \"linux\" || platform === \"android\") " +
                "return new LinuxProcessInspector(arch, internals);",
            why = "进程探测器接受 android"
        ),
        PlatformPatch(
            dir = "@deepseek-ai/dsh-subprocess-local/lib",
            filePrefix = "index.js",
            old = "this.platform === \"linux\" && observed.complete === true",
            new = "(this.platform === \"linux\" || this.platform === \"android\") && " +
                "observed.complete === true",
            why = "终端活动状态判定接受 android"
        )
    )

    /**
     * 启动引擎前调用一次：部署助手 + 装配各 node_modules 里的 node-pty。
     *
     * 全部操作幂等，失败只记日志，**不抛异常**（终端不可用不该阻断引擎启动）。
     *
     * @param prefix 容器根（`filesDir/usr`），助手下发到它的 `bin/`
     * @param onLog 与启动日志同一条通道，用户能在界面上看到结果
     */
    fun ensure(context: Context, prefix: String, onLog: (String) -> Unit) {
        val prefixDir = File(prefix)
        runCatching { deployHelper(context, prefixDir, onLog) }
            .onFailure { onLog("⚠️ 终端后端部署失败: ${it.message}") }
        runCatching { patchNodeModules(context, prefixDir, onLog) }
            .onFailure { onLog("⚠️ 终端 node-pty 装配失败: ${it.message}") }
    }

    // ==================== 原生助手 ====================

    private fun deployHelper(context: Context, prefixDir: File, onLog: (String) -> Unit) {
        val bin = File(prefixDir, "bin")
        if (!bin.isDirectory && !bin.mkdirs()) {
            onLog("⚠️ 终端后端：无法创建 $bin")
            return
        }
        val target = File(bin, HELPER_NAME)

        // 已在位且是完整 ELF → 只补执行位就够（省掉每次启动读一遍资产）
        if (target.isFile && looksLikeElf(target)) {
            if (!target.canExecute()) target.setExecutable(true, false)
            return
        }

        val bytes = readAsset(context, "$ASSET_DIR/$ASSET_HELPER")
        if (bytes == null) {
            onLog("⚠️ 终端后端：APK 内缺少 $ASSET_DIR/$ASSET_HELPER")
            return
        }
        if (bytes.size < 64) {
            onLog("⚠️ 终端后端：资产只有 ${bytes.size} 字节，疑似损坏")
            return
        }

        // 先写临时文件再改名：写入中途被杀不会留下半个可执行文件
        val tmp = File(bin, "$HELPER_NAME.tmp")
        tmp.writeBytes(bytes)
        val problem = elfProblem(tmp)
        if (problem != null) {
            tmp.delete()
            onLog("❌ 终端后端无效（$problem），已跳过")
            return
        }
        if (target.exists() && !target.delete()) {
            tmp.delete()
            onLog("⚠️ 终端后端：无法覆盖 $target")
            return
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            onLog("⚠️ 终端后端：无法重命名为 $target")
            return
        }
        target.setExecutable(true, false)
        onLog("✅ 终端 PTY 后端已部署（${bytes.size} 字节）")
        Log.i(TAG, "helper deployed: ${target.absolutePath} ${bytes.size}B")
    }

    /**
     * 认一下是不是 ELF，并顺手核对架构与当前设备是否一致。
     *
     * 返回 null 表示可用；否则返回一句人类可读的拒绝理由。
     * 架构不一致**只记警告不拒绝** —— 可能是给别的 ABI 备的包，
     * 真跑起来 execve 会报 ENOEXEC，那时日志里有部署记录可查。
     */
    private fun elfProblem(file: File): String? {
        val head = ByteArray(20)
        val read = runCatching {
            FileInputStream(file).use { it.read(head) }
        }.getOrDefault(-1)
        if (read < 20) return "读到的字节数只有 $read"
        if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) {
            return "不是 ELF 文件"
        }
        if (head[4] != 2.toByte()) return "不是 64 位 ELF"
        val machine = (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        val expected = if (Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true) {
            EM_X86_64
        } else {
            EM_AARCH64
        }
        if (machine != expected) {
            Log.w(TAG, "helper e_machine=$machine, device expects $expected")
        }
        return null
    }

    private fun looksLikeElf(file: File): Boolean {
        if (file.length() < 20) return false
        return elfProblem(file) == null
    }

    // ==================== node-pty / subprocess-local ====================

    /**
     * 装配所有会用到 node-pty 的 node_modules 根。
     *
     * 两类位置都要覆盖（与 [DSHRunner.ensureFlockShim] 同一思路）：
     * - 容器自带的一份：`$PREFIX/lib/node_modules`
     * - 每个已装引擎版本各一份：`$PREFIX/lib/versions/<版本>/node_modules`
     */
    private fun patchNodeModules(
        context: Context,
        prefixDir: File,
        onLog: (String) -> Unit
    ) {
        val roots = ArrayList<File>(4)
        File(prefixDir, "lib/node_modules").let { if (it.isDirectory) roots.add(it) }
        for (v in DSHRunner.installedVersions(context)) {
            File(DSHRunner.engineRoot(context, v), "node_modules")
                .let { if (it.isDirectory) roots.add(it) }
        }
        if (roots.isEmpty()) {
            onLog("⚠️ 终端后端：找不到任何 node_modules，跳过 node-pty 装配")
            return
        }

        val impl = readAssetText(context, "$ASSET_DIR/$ASSET_IMPL")
        if (impl.isNullOrBlank()) {
            onLog("⚠️ 终端后端：APK 内缺少 $ASSET_DIR/$ASSET_IMPL")
            return
        }

        var ptyCount = 0
        var patchCount = 0
        for (root in roots) {
            if (patchNodePty(root, impl, onLog)) ptyCount++
            patchCount += patchPlatforms(root, onLog)
        }
        if (ptyCount == 0) {
            onLog("⚠️ 终端后端：node_modules 里没有 node-pty 包，终端仍不可用")
        } else {
            Log.i(TAG, "node-pty ready in $ptyCount root(s); $patchCount platform patch(es)")
        }
    }

    /** 把 node-pty 的实现换成 Android 版；返回是否处理了该根。 */
    private fun patchNodePty(root: File, impl: String, onLog: (String) -> Unit): Boolean {
        val pkg = File(root, "node-pty")
        if (!pkg.isDirectory) return false

        val target = File(pkg, "lib/index.js")
        val current = runCatching { target.readText() }.getOrNull()
        if (current == null || !current.contains(IMPL_MARKER)) {
            target.parentFile?.mkdirs()
            target.writeText(impl)
            onLog("✅ 已装配 node-pty 的 Android 实现")
        }

        // package.json 只改 version / main，其余字段原样保留 ——
        // 整个重写会丢掉上游声明的其他入口，未来上游加了字段就会缺。
        val json = File(pkg, "package.json")
        val meta = runCatching { JSONObject(json.readText()) }.getOrNull()
        if (meta == null) {
            json.writeText(
                "{\"name\":\"node-pty\",\"version\":\"$PTY_VERSION_MARKER\"," +
                    "\"main\":\"lib/index.js\"}\n"
            )
            return true
        }
        val changed = meta.optString("version") != PTY_VERSION_MARKER ||
            meta.optString("main") != "lib/index.js"
        if (changed) {
            meta.put("version", PTY_VERSION_MARKER)
            meta.put("main", "lib/index.js")
            json.writeText(meta.toString())
        }
        return true
    }

    /** 给 subprocess-local 的平台判断放行 android；返回改动处数。 */
    private fun patchPlatforms(root: File, onLog: (String) -> Unit): Int {
        var applied = 0
        for (p in PLATFORM_PATCHES) {
            val dir = File(root, p.dir)
            if (!dir.isDirectory) continue
            val names = dir.list()?.filter {
                it.startsWith(p.filePrefix) && it.endsWith(".js")
            }.orEmpty()
            for (name in names) {
                val f = File(dir, name)
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (text.contains(p.new)) continue
                if (!text.contains(p.old)) {
                    // 上游形态变了：只警告。终端会退回不可用，但不该拦住启动。
                    onLog("⚠️ 终端后端：$name 里找不到平台判断锚点（${p.why}）")
                    continue
                }
                f.writeText(text.replace(p.old, p.new))
                applied++
            }
        }
        return applied
    }

    // ==================== 资产读取 ====================

    private fun readAsset(context: Context, path: String): ByteArray? = runCatching {
        context.assets.open(path).use { it.readBytes() }
    }.getOrNull()

    private fun readAssetText(context: Context, path: String): String? = runCatching {
        context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}
