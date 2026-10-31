package com.example.fcldsh

import java.io.File

/**
 * 探测本机能不能真正实施「沙箱写入隔离」。
 *
 * ## 为什么需要单独探测
 *
 * dsh 的 `workspace-write` 不是「自己把路径过滤一遍」那么轻的实现：它要把命令
 * 放进一个**内核级**隔离环境里跑，实现手段只有两种 ——
 * bubblewrap（非特权 user namespace + mount ns）或 Landlock LSM。
 * 两者都拿不到时，dsh 的选择是**拒绝执行命令**（而不是裸跑到宿主里）。
 *
 * Android 内核通常两样都不给：
 * - 出厂内核普遍没开 `CONFIG_SECURITY_LANDLOCK`；
 * - 非特权 user namespace 被关掉（`max_user_namespaces = 0`）；
 * - 系统里没有预装 `bwrap`。
 *
 * 结果是：用户在**默认**的 `workspace-write` 下会遇到「所有 bash 都失败」，
 * 而报错来自 dsh 内部（一句 unsupported / denied），看不出根因是本机没后端。
 *
 * 这个对象负责把那句话提前说出来，并给出该怎么做。
 *
 * ## 为什么全是文件读取，不跑子进程
 *
 * 设置界面要显示状态。若探测里有 `ProcessBuilder`，就可能卡住主线程。
 * 所以 [detect] 只做 stat 与读几个小文件（微秒级，主线程安全）；
 * 需要「bwrap 到底能不能跑起来」这种更强的验证时，用 [verifyBwrap]，
 * **它只允许在后台线程调用**（引擎启动时调用一次）。
 */
object SandboxBackend {

    /** 探测到的后端种类。 */
    enum class Kind(internal val label: String) {
        BUBBLEWRAP("bubblewrap"),
        LANDLOCK("Landlock"),
        NONE("无")
    }

    /**
     * 一次探测的结果。
     *
     * 刻意保留原始事实（[bwrapPath]、[usernsMax]、[lsm]）而不只给一个布尔：
     * 现场排查时「到底缺哪一样」比「可不可用」有用得多。
     */
    data class Report(
        val kind: Kind,
        /** 找到的 bwrap 绝对路径；null 表示没找到。 */
        val bwrapPath: String?,
        /** `/proc/sys/user/max_user_namespaces` 的值；-1 表示读不到。 */
        val usernsMax: Int,
        /** `/sys/kernel/security/lsm` 的内容；空串表示读不到。 */
        val lsm: String
    ) {
        /** 本机是否具备执行命令所需的隔离后端。 */
        val available: Boolean get() = kind != Kind.NONE

        /** 一句话现状，直接可以写进日志或界面。 */
        val summary: String
            get() = when (kind) {
                Kind.BUBBLEWRAP -> "可用（bubblewrap: $bwrapPath）"
                Kind.LANDLOCK -> "可用（内核启用了 Landlock）"
                Kind.NONE -> "不可用（没找到 bwrap，内核也未启用 Landlock）"
            }

        /** 该怎么做。只说一句可行的话，不铺开讲原理。 */
        val hint: String
            get() = if (available) {
                "命令可以在沙箱内执行。"
            } else {
                "本机没有沙箱后端，dsh 在「工作区可写」模式下会拒绝执行 " +
                    "bash / glob / grep。需要这些能力请到 设置 → 权限设置 " +
                    "选「完全访问」并确认风险提示。"
            }
    }

    /**
     * bwrap 的可能位置。
     *
     * `prefix/bin` 排在前面：引擎包自带的话优先用它（那才是 dsh 会看到的那一个，
     * 因为启动引擎时 `PATH` 就是 `$PREFIX/bin:/system/bin`）。
     */
    private val SYSTEM_BWRAP = listOf(
        "/system/bin/bwrap",
        "/system/xbin/bwrap",
        "/system_ext/bin/bwrap",
        "/vendor/bin/bwrap"
    )

    /**
     * 探测。纯文件读取，可在主线程调用。
     *
     * @param prefix 引擎容器目录（通常是 `filesDir/usr`）。它的 `bin/bwrap`
     *   会排在系统路径之前。传 null 表示只查系统路径。
     */
    fun detect(prefix: File? = null): Report {
        val bwrap = findBwrap(prefix)
        val lsm = readLsm()
        val userns = readUsernsMax()
        val kind = when {
            bwrap != null -> Kind.BUBBLEWRAP
            lsm.contains("landlock", ignoreCase = true) -> Kind.LANDLOCK
            else -> Kind.NONE
        }
        return Report(kind, bwrap, userns, lsm)
    }

    /**
     * 真正跑一次 `bwrap --version`，确认它不是「存在但不可执行」。
     *
     * Android 上 `File.canExecute()` 只能反映权限位，反映不了 SELinux 拒绝
     * 或 ABI 不兼容。一个「存在却跑不起来」的 bwrap 会让 dsh 报出更难理解的错误。
     *
     * ⚠️ 会阻塞最多 1.5 秒，**只能在后台线程调用**。
     */
    fun verifyBwrap(path: String): String? = runCatching {
        val p = ProcessBuilder(path, "--version")
            .redirectErrorStream(true)
            .start()
        val ok = p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        val text = p.inputStream.bufferedReader().use { r ->
            val line = r.readLine() ?: ""
            line.trim()
        }
        if (!ok) {
            p.destroy()
            "bwrap 启动超时"
        } else if (p.exitValue() == 0) {
            text.ifBlank { "bwrap 可执行" }
        } else {
            "bwrap 退出码 ${p.exitValue()}：${text.ifBlank { "无输出" }}"
        }
    }.getOrElse { e ->
        "bwrap 无法执行：${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * 启动引擎时把探测结果写进运行日志。
     *
     * 与 [detect] 的区别：这是**后台线程**（允许 [verifyBwrap]），
     * 而且会结合用户选的权限模式说明「命令到底能不能用」。
     */
    fun logReport(mode: PermissionMode, prefix: File?, onLog: (String) -> Unit) {
        val r = detect(prefix)
        onLog("沙箱后端: ${r.summary}")
        r.bwrapPath?.let { path ->
            onLog("bwrap 自检: ${verifyBwrap(path) ?: "未执行"}")
        }
        onLog("LSM: ${r.lsm.ifBlank { "读不到" }}")
        onLog("max_user_namespaces: ${if (r.usernsMax < 0) "读不到" else r.usernsMax.toString()}")

        when {
            mode == PermissionMode.READ_ONLY ->
                onLog("ℹ️ 只读模式：任何写入与命令执行都会被拒绝（这是设置里的选择）")
            mode == PermissionMode.DANGER_FULL_ACCESS ->
                onLog("⚠️ 完全访问模式：命令无隔离执行，不受沙箱后端限制")
            r.available ->
                onLog("✅ 命令可以在沙箱内执行")
            else ->
                onLog("⚠️ ${r.hint}")
        }
    }

    /**
     * 找 bwrap 可执行文件。顺序：容器自带的 → 系统路径。
     */
    private fun findBwrap(prefix: File?): String? {
        val candidates = ArrayList<String>(SYSTEM_BWRAP.size + 2)
        if (prefix != null) {
            File(prefix, "bin/bwrap")
                .takeIf { it.isFile }
                ?.let { candidates.add(it.absolutePath) }
            File(prefix, "usr/bin/bwrap")
                .takeIf { it.isFile }
                ?.let { candidates.add(it.absolutePath) }
        }
        candidates.addAll(SYSTEM_BWRAP)
        return candidates.firstOrNull { path ->
            val f = File(path)
            f.isFile && f.canExecute()
        }
    }

    /**
     * 读内核启用的 LSM 列表（形如 `capability,landlock,yama,selinux`）。
     * 读不到就返回空串 —— 不能据此断定「没有」。
     */
    private fun readLsm(): String =
        runCatching { File("/sys/kernel/security/lsm").readText().trim() }.getOrDefault("")

    /**
     * 非特权 user namespace 的上限。0 表示被关闭（Android 默认）。
     * 这个值对排查有意义，但**不是**判定依据。
     */
    private fun readUsernsMax(): Int =
        runCatching {
            File("/proc/sys/user/max_user_namespaces").readText().trim().toInt()
        }.getOrDefault(-1)
}
