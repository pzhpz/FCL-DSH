package com.example.fcldsh

import android.util.Log
import com.DSHRunner
import java.io.File
import java.io.FileInputStream

/**
 * 修「终端报 `command "/data/data/com.termux/files/usr/bin/bash" is not an executable file`」。
 *
 * ## 现象与根因
 *
 * 本应用的容器在 `/data/data/com.example.fcldsh/files/usr`，但 dsh 的
 * `@deepseek-ai/dsh-subprocess-local` 在 Android 上把交互式 shell 解析成了
 * **Termux 的路径** `/data/data/com.termux/files/usr/bin/bash` —— 那是另一个
 * 应用的私有目录（0700，别的 uid 连 stat 都不行），所以它既不是本应用能执行的
 * 文件，也确实不存在。
 *
 * 这是上游「dsh 官方支持 Termux」的假设与本应用布局的错位，不是终端后端坏了：
 * 从报错内容能看出前面几层已经通了（平台判断已放行 android、node-pty 已被
 * 换成我们的实现），失败点只剩「用哪个 bash」。
 *
 * ## 两处一起修，缺一不可
 *
 * 1. **路径**：把 `$PREFIX/bin/bash` 作为 `SHELL` 下发给引擎（最直接的一层），
 *    并把已安装引擎的 `dsh-subprocess-local` 里那个写死的 Termux 前缀
 *    改写成真实 `$PREFIX`（兜住不读 `SHELL` 的代码路径）。
 * 2. **可执行位**：`assets/runtime.zip` 是用 `ZipInputStream` 解出来的，它**不还原
 *    Unix 权限位**；`grantExec()` 只在首次解压后跑一次。老安装、被外部拷回来的
 *    容器、或解压中断过，都会留下没有 `+x` 的 `bin/bash` —— 那种情况下即使路径
 *    对了，subprocess-local 的前置检查仍会报同一句话。所以启动前再补一次。
 *
 * ## 边界
 *
 * - 只动 `@deepseek-ai/dsh-subprocess-local/lib` 下的 js 文件里的**字面前缀**，
 *   不做正则、不碰 node_modules 之外的文件；替换后该字面量消失，天然幂等。
 * - 失败只记日志，不抛异常（终端不可用不该拦住引擎启动）。
 */
object ShellEnvFix {

    private const val TAG = "DSH-shell"

    /**
     * 上游写死的 Termux 容器前缀。
     *
     * ⚠️ 只替换**这一整串**，不要改成替换 `/data/data/com.termux` ——
     * 后者会连带改到「是否在 Termux 里」的探测，让引擎误判环境。
     */
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /** 目标包在 node_modules 下的相对目录。 */
    private const val PKG_DIR = "@deepseek-ai/dsh-subprocess-local/lib"

    /**
     * 启动引擎前调用一次。
     *
     * @param prefix 容器根（`filesDir/usr`）
     * @param onLog 与启动日志同一条通道
     * @param report 返回给调用方的结论：真正可用的 bash 绝对路径（找不到为 null）
     */
    fun ensure(
        context: android.content.Context,
        prefix: String,
        onLog: (String) -> Unit
    ): String? {
        val prefixDir = File(prefix)
        runCatching { ensureExecutableBits(prefixDir, onLog) }
            .onFailure { onLog("⚠️ 可执行位修复失败: ${it.message}") }
        runCatching { rewriteTermuxPrefix(context, prefixDir, onLog) }
            .onFailure { onLog("⚠️ 引擎 shell 路径改写失败: ${it.message}") }

        val bash = File(prefixDir, "bin/bash")
        if (!bash.isFile) {
            onLog("⚠️ 容器里找不到 bin/bash，终端将退回 /system/bin/sh")
            return null
        }
        return bash.absolutePath
    }

    // ==================== 可执行位 ====================

    /**
     * 给 `bin/` 与 ripgrep 平台包补 `+x`。
     *
     * 只处理「看起来确实是要执行的」文件（ELF 或带 `#!`），避免把
     * `etc/`、`.keep` 这类文本也标成可执行 —— 那会让后续排查凭空多出噪音。
     */
    private fun ensureExecutableBits(prefixDir: File, onLog: (String) -> Unit) {
        var fixed = 0
        val dirs = ArrayList<File>(4)
        File(prefixDir, "bin").let { if (it.isDirectory) dirs.add(it) }
        File(prefixDir, "lib/node_modules/@vscode").listFiles()?.forEach { pkg ->
            File(pkg, "bin").let { if (it.isDirectory) dirs.add(it) }
        }

        for (dir in dirs) {
            for (f in dir.listFiles().orEmpty()) {
                if (!f.isFile || f.canExecute()) continue
                if (!looksRunnable(f)) continue
                if (runCatching { f.setExecutable(true, false) }.getOrDefault(false)) fixed++
            }
        }
        if (fixed > 0) {
            onLog("✅ 已为 $fixed 个容器命令补上可执行位")
            Log.i(TAG, "chmod +x applied to $fixed file(s)")
        }
    }

    /** ELF 头或 `#!` 开头 → 认为应该可执行。 */
    private fun looksRunnable(file: File): Boolean {
        val head = ByteArray(4)
        val read = runCatching {
            FileInputStream(file).use { it.read(head) }
        }.getOrDefault(-1)
        if (read < 2) return false
        if (head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte()) return true
        return head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
    }

    // ==================== Termux 前缀改写 ====================

    private fun rewriteTermuxPrefix(
        context: android.content.Context,
        prefixDir: File,
        onLog: (String) -> Unit
    ) {
        val real = prefixDir.absolutePath
        if (real == TERMUX_PREFIX) return   // 真跑在 Termux 里，别动

        val roots = ArrayList<File>(4)
        File(prefixDir, "lib/node_modules").let { if (it.isDirectory) roots.add(it) }
        for (v in DSHRunner.installedVersions(context)) {
            File(DSHRunner.engineRoot(context, v), "node_modules")
                .let { if (it.isDirectory) roots.add(it) }
        }

        var files = 0
        for (root in roots) {
            val dir = File(root, PKG_DIR)
            if (!dir.isDirectory) continue
            for (f in dir.listFiles().orEmpty()) {
                if (!f.isFile || !f.name.endsWith(".js")) continue
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (!text.contains(TERMUX_PREFIX)) continue
                val out = text.replace(TERMUX_PREFIX, real)
                if (out == text) continue
                if (runCatching { f.writeText(out) }.isSuccess) {
                    files++
                    Log.i(TAG, "rewrote termux prefix in ${f.absolutePath}")
                }
            }
        }
        if (files > 0) {
            onLog("✅ 已把引擎里的 Termux 路径改成本机容器（$files 个文件）")
        }
    }
}
