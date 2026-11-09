package com.example.fcldsh

import android.content.Context
import android.util.Log
import com.DSHRunner
import java.io.File

/**
 * 给 `@deepseek-ai/dsh-fs-local` 打 Android 适配补丁（移植自参考项目
 * `Deepseek-Harness-Local-Android-main/scripts/patch-fs-local-android.py`）。
 *
 * ## 修的是什么
 *
 * 现象：文件面板报「文件资源服务不可用」，或某个目录点进去直接报错、一个文件都
 * 列不出来。根因是上游把**权限错误**当成致命 IO 故障：
 *
 * ```js
 * async function probeStats(absolutePath, readStats) {
 *     try { return await readStats(absolutePath); }
 *     catch (error) {
 *         if (!isENOENT(error) && !isENOTDIR(error)) throw error;  // ← EACCES 在这里被抛出
 *         return null;
 *     }
 * }
 * ```
 *
 * 这段假设在 Linux/macOS 上成立（权限位由 app 自己掌握），**Android 不成立**：
 * 共享存储走 FUSE，`Documents/dshdata` 这类 media 卷上的 `stat()`/`lstat()` 即使
 * 拿到 MANAGE_EXTERNAL_STORAGE 也可能返回 **EACCES**（SELinux 域迁移、卷正在重新
 * 挂载、或目录项属于别的 uid 的 media 文件）。于是：
 *
 * - 文件预览：`locateFile()` 拿不到 `entry.type` → 前端 `meta.status === "none"`
 *   → 「文件资源服务不可用」；
 * - 目录列表：`listDirectory()` 对**每个子项**都 `probe()` + `resolveListedChildTarget()`，
 *   任一子项 EACCES 就 `throw listingIoError(...)` → **整个目录列举失败**。
 *
 * ## 修法（两处，都保持上游的可观测语义）
 *
 * 1. `probeStats`：把 EACCES/EPERM 视为「无法观测」返回 null，而不是致命错误。
 *    `stat` 失败不代表路径不存在，但调用方要的是「能不能读到元数据」；返回 null
 *    会让上层走 FS_NOT_FOUND（「文件不存在」），比「资源服务不可用」更准确，且不会
 *    中断整次列举。**其余 errno（EIO、ENOMEM 等）照旧上抛** —— 真故障不该被吞。
 * 2. `listDirectory`：单个子项失败时降级成 `type: "other"` 并保留 `name`，
 *    让同一目录的其余内容仍可浏览。
 *
 * ## 与参考脚本的一处**刻意不同**
 *
 * 参考脚本的替换文本调用 `isPermissionError(error)` 这个辅助函数，但它在参考项目
 * 仓库里**找不到任何定义**（`search_text("isPermissionError")` → 零命中）。它要么
 * 是上游运行时里恰好存在的内部函数，要么是那个脚本的疏漏 —— 后者会让补丁在运行时
 * 抛 `ReferenceError`，比不打补丁更糟。这里改成**自包含的判断**
 * （`error && (error.code === "EACCES" || error.code === "EPERM")`），不依赖任何
 * 外部符号，两种情况下都安全。
 *
 * ## 为什么在启动前做、而不是打包时改 runtime.zip
 *
 * 与 [PtySupport] 同一原因：引擎是首启后按需 npm 装的，同一份 runtime 会被装进
 * `lib/versions/<版本>/node_modules`，且版本可以并存/随时切换。打包时只能覆盖容器
 * 自带的那份，换版本就失效。所以每次启动引擎前按磁盘实际布局装配一次（幂等）。
 */
object FsLocalPatch {

    private const val TAG = "DSH-fs-local"

    /** 幂等判据：打过的文件里一定有这个标记。 */
    private const val MARKER = "[dsh-android] fs-local"

    /** 目标包在 node_modules 下的相对路径。 */
    private const val PKG_REL = "@deepseek-ai/dsh-fs-local/lib/index.js"

    /**
     * `probeStats` 里那句「ENOENT/ENOTDIR 之外一律上抛」。
     *
     * 用正则而不是逐字匹配，是为了不对缩进敏感 —— 上游用 tab 还是空格、
     * 缩进几层，都不该让补丁静默失效。
     */
    private val PROBE_ANCHOR = Regex(
        """if\s*\(\s*!isENOENT\(error\)\s*&&\s*!isENOTDIR\(error\)\s*\)\s*throw\s+error;"""
    )

    private fun probeReplacement(): String = """
        |if (!isENOENT(error) && !isENOTDIR(error)) {
        |    // $MARKER — Android 共享存储（FUSE）会对本应用无法观测的目录项返回
        |    // EACCES：SELinux 域迁移、卷正在重新挂载、或属于别的 uid 的 media 文件。
        |    // 上游把这类失败一律当致命错误，于是「一个不可读的子项」会扩散成
        |    // 「整个文件面板不可用」。这里报告为「无法观测」（null），上层据此说
        |    // 「文件不存在」，而不是让整次列举失败。其余 errno 仍照旧上抛。
        |    if (error && (error.code === "EACCES" || error.code === "EPERM")) return null;
        |    throw error;
        |}
    """.trimMargin()

    /** `listDirectory` 里子项解析失败的分支。 */
    private val LIST_ANCHOR = Regex(
        """throw\s+listingIoError\(localDisplayPath\(target\.displayPath,\s*entry\.name\),\s*error\);"""
    )

    private fun listReplacement(): String = """
        |// $MARKER — 一个不可观测的子项不能让整个目录失败。共享存储上经常混着
        |// 本应用能列出、却不能 stat 的条目；上游遇到第一个就中止整次列举。
        |// 这里保留名字、类型记为 other，让同目录其余内容仍可浏览。
        |if (error && (error.code === "EACCES" || error.code === "EPERM")) {
        |    result.push({
        |        name: entry.name,
        |        type: "other",
        |        target: {
        |            displayPath: localDisplayPath(target.displayPath, entry.name),
        |            targetKey: FsTargetKey(localDisplayPath(target.displayPath, entry.name))
        |        }
        |    });
        |    continue;
        |}
        |throw listingIoError(localDisplayPath(target.displayPath, entry.name), error);
    """.trimMargin()

    /**
     * 启动引擎前调用一次。失败只记日志，**不抛异常**。
     *
     * @param prefix 容器根（`filesDir/usr`）
     * @param onLog 与启动日志同一条通道
     */
    fun ensure(context: Context, prefix: String, onLog: (String) -> Unit) {
        runCatching { patchAll(context, File(prefix), onLog) }
            .onFailure { onLog("⚠️ fs-local 补丁失败: ${it.message}") }
    }

    private fun patchAll(context: Context, prefixDir: File, onLog: (String) -> Unit) {
        val roots = ArrayList<File>(4)
        File(prefixDir, "lib/node_modules").let { if (it.isDirectory) roots.add(it) }
        for (v in DSHRunner.installedVersions(context)) {
            File(DSHRunner.engineRoot(context, v), "node_modules")
                .let { if (it.isDirectory) roots.add(it) }
        }

        var seen = 0
        var patched = 0
        for (root in roots) {
            val file = File(root, PKG_REL)
            if (!file.isFile) continue
            seen++
            if (patchFile(file)) patched++
        }

        when {
            seen == 0 ->
                // 不是错误：老版本引擎可能根本没有这个包，终端/文件面板照旧。
                Log.i(TAG, "dsh-fs-local not found under any node_modules; skipped")
            patched > 0 -> onLog("✅ 已适配 dsh-fs-local（Android 权限降级）")
            else -> Log.i(TAG, "dsh-fs-local already patched in $seen location(s)")
        }
    }

    /**
     * 打一个文件。返回是否真的改动了内容。
     *
     * 两个锚点**必须都命中**才写盘：[probeStats] 是「读不了」的主因，
     * [listDirectory] 是「列不出」的主因，只打一半会留下更难看懂的半残状态。
     * 任一未命中就整体放弃这个文件，只记日志。
     */
    private fun patchFile(file: File): Boolean {
        val original = runCatching { file.readText() }.getOrNull() ?: return false
        if (original.contains(MARKER)) return false

        if (!PROBE_ANCHOR.containsMatchIn(original)) {
            Log.w(TAG, "probeStats anchor not found in ${file.absolutePath}")
            return false
        }
        if (!LIST_ANCHOR.containsMatchIn(original)) {
            Log.w(TAG, "listDirectory anchor not found in ${file.absolutePath}")
            return false
        }

        // 用 lambda 做替换，避免替换文本里的 $ 被当成反向引用
        var out = PROBE_ANCHOR.replace(original, Regex.escapeReplacement(probeReplacement()))
        out = LIST_ANCHOR.replace(out, Regex.escapeReplacement(listReplacement()))
        if (out == original) return false

        runCatching { file.writeText(out) }
            .onFailure {
                Log.w(TAG, "write failed: ${file.absolutePath}: ${it.message}")
                return false
            }
        Log.i(TAG, "patched ${file.absolutePath}")
        return true
    }
}
