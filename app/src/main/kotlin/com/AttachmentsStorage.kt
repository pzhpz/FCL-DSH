package com

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 把某个私有子目录「外置」：先迁数据，成功后再替换成软链。
 *
 * ## 为什么要做这件事
 *
 * `/data/user/0` 是指向 `/data/data` 的符号链接，权限 `root:root 0711`：
 * 普通应用 UID 能穿过（`x`）但不能列出（`r`）。
 *
 * dsh 与它依赖的模块在**写盘时会逐级 fsync 到根**（`ensureDurableDirectory`），
 * 也常在打开文件前先解析父目录。这两种操作都会对路径上的每一级做 `open(O_RDONLY)`，
 * 走到 `/data/user/0` 就 `EACCES` —— 并且**报错停在 `/data/user/0`，
 * 不进入你给的具体文件**，所以换文件、换路径、复制都绕不过去。
 *
 * 软链让 `realpath()` 落到 `/storage/emulated/0/...`，整条上行路径都不再经过那一层。
 * 这正是参考项目 `EngineConfig.externaliseSubdir()` 的手法（它对 `sessions/`、
 * `workspaces/` 就这么做）。
 *
 * ## 铁律：只外置**子目录**，绝不动 `$HOME` / `$DSH_HOME` 本身
 *
 * 搬根目录会让 dsh 在新空目录里重新初始化，插件 / 登录 / 会话全部「消失」
 * —— 本项目真出过一次事故（见 [appHomeDir]）。
 *
 * 也不能外置 `profiles/`：上游 `assertOwnerOnly` 强制凭证文件 0600，
 * 而共享存储给不出 Unix 权限位（参考项目记录过这个边界）。
 *
 * @param dir   要外置的目录（`$DSH_HOME/attachments`、`$HOME/Documents` 等）。
 * @param target 新的物理位置，必须**不含** `/data/user/0` 这一段。
 * @param label  日志用的名字。
 * @return 实际生效的目录；无法外置时返回原目录或 null。
 */
internal fun externaliseDir(dir: File, target: File, label: String): File? {
    dir.parentFile?.mkdirs()

    if (isLink(dir)) {
        // ① 已经是软链
        val current = readLinkTarget(dir)
        if (current != null) {
            val same = runCatching { current.canonicalPath == target.canonicalPath }
                .getOrDefault(false)
            if (same) {
                // 目标可能被系统清理过（app external 会在「清除缓存」时被删）
                if (!target.isDirectory) target.mkdirs()
                return target
            }
            // 指向别处：只要那个目录还在就**保留**（数据在那边），不强改。
            // 强行重指是「用户数据突然不可用」的典型来源。
            if (current.isDirectory) {
                DSHRunner.onLog("ℹ️ $label 仍指向 ${current.absolutePath}（可用，保持不动）")
                return current
            }
        }
        runCatching { dir.delete() }
    } else if (dir.isDirectory) {
        // ② 真实目录（历史数据）：先搬到新位置，核对齐全才替换成软链。
        //    任何一步失败都保留原目录并放弃 —— 绝不让数据「变没」。
        if (!mergeTree(dir, target)) {
            DSHRunner.onLog("⚠️ $label 迁移未完成，保持原位（不建软链）")
            return dir
        }
        if (!dir.deleteRecursively()) {
            // 删不掉旧目录就不建软链，否则 dsh 会继续往旧目录写
            DSHRunner.onLog("⚠️ $label 旧目录无法删除，保持原位")
            return dir
        }
    } else if (dir.exists()) {
        // ③ 普通文件（不该出现）：不碰
        DSHRunner.onLog("⚠️ $label 是普通文件，跳过外置")
        return null
    }

    target.mkdirs()
    if (!createLink(dir, target)) {
        DSHRunner.onLog("⚠️ $label 软链创建失败，保持原地")
        return dir
    }
    DSHRunner.onLog("🔗 $label 已外置 → ${target.absolutePath}")
    return target
}

/**
 * 公开数据根目录（**不含** `/data/user/0` 这一段）。
 *
 * 两级回退，与参考项目 `publicDataRoot` 同理：
 *
 * 1. **`Documents/dshdata/<category>`** —— 真共享目录，文件管理器可见、
 *    卸载重装不丢。需要「所有文件访问」（MANAGE_EXTERNAL_STORAGE）。
 * 2. **`Android/data/<包名>/files/<category>`** —— 应用专属外部目录，零权限可用，
 *    但「清除数据 / 卸载」时被系统删掉。
 *
 * @param category 子目录名（`attachments` / `Documents` …）。
 * @param version  非 null 时再套一层版本目录（版本隔离用）。
 */
internal fun publicDataRoot(context: Context, category: String, version: String?): File? {
    val manager = Build.VERSION.SDK_INT >= 30 &&
        android.os.Environment.isExternalStorageManager()
    if (manager) {
        val base = File(
            android.os.Environment.getExternalStorageDirectory(),
            "dshdata/$category"
        )
        val dir = if (version != null) File(base, version) else base
        if (isDirUsable(dir)) return dir
        DSHRunner.onLog("ℹ️ 共享目录 $category 不可用，回落应用外部目录")
    }

    val ext = runCatching { context.getExternalFilesDir(null) }.getOrNull()
    if (ext != null) {
        val base = File(ext, category)
        val dir = if (version != null) File(base, version) else base
        if (isDirUsable(dir)) return dir
        DSHRunner.onLog("⚠️ 应用外部目录 $category 不可写")
    }
    return null
}

/**
 * 目录是否真的可用：写 → 列 → 读回 三步。
 *
 * 不能只看 `canWrite()`：Android 11+ 的 FUSE 下它对某些路径返回 true，
 * 而实际 `list()` / 读取被拒（参考项目为此踩过两次坑）。
 *
 * 探针文件名带 pid + 纳秒：本函数可能被并发调用，固定名字会互相删掉
 * 对方刚写的探针，把可用目录误判成不可用。
 */
internal fun isDirUsable(dir: File): Boolean = runCatching {
    if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
    val tag = "${android.os.Process.myPid()}-${System.nanoTime()}"
    val probe = File(dir, ".probe-$tag")
    var ok = false
    try {
        probe.writeText("ok")
        val listed = dir.list()?.contains(probe.name) == true
        val readBack = probe.isFile && probe.readText() == "ok"
        ok = listed && readBack
    } finally {
        runCatching { probe.delete() }
    }
    ok
}.getOrDefault(false)

/** 这一项**本身**是不是符号链接（不跟随）。 */
internal fun isLink(f: File): Boolean = runCatching {
    val st = android.system.Os.lstat(f.absolutePath)
    android.system.OsConstants.S_ISLNK(st.st_mode)
}.getOrDefault(false)

/** 读取软链目标（不跟随）；失败返回 null。 */
internal fun readLinkTarget(f: File): File? = runCatching {
    if (Build.VERSION.SDK_INT >= 26) {
        val p = java.nio.file.Files.readSymbolicLink(f.toPath())
        if (p.isAbsolute) p.toFile() else File(f.parentFile, p.toString())
    } else {
        // API 23-25 没有 java.nio.file，用 toybox 的 readlink
        val p = ProcessBuilder("/system/bin/readlink", f.absolutePath)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        if (out.isEmpty()) null else File(out)
    }
}.getOrNull()

/**
 * 创建软链。API 26+ 走 `java.nio.file`，更老的版本用 toybox 的 `ln -s`。
 *
 * 两者都是系统能力（`/system/bin/ln` 是系统二进制，允许直接 exec），
 * 不依赖应用数据目录里的任何可执行文件 —— 那条路会 EACCES（W^X）。
 */
internal fun createLink(link: File, target: File): Boolean {
    if (Build.VERSION.SDK_INT >= 26) {
        val done = runCatching {
            runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        }.getOrDefault(false)
        if (done) return true
    }
    return runCatching {
        runCatching { link.delete() }
        val p = ProcessBuilder(
            "/system/bin/ln", "-s", target.absolutePath, link.absolutePath
        ).redirectErrorStream(true).start()
        p.waitFor()
        isLink(link)
    }.getOrDefault(false)
}

/**
 * 把 [src] 的内容合并进 [dst]，只在**全部核对通过**时返回 true。
 *
 * 语义（与参考项目的迁移一致）：
 * - 逐文件复制，**只补缺失**，目标已有同名文件就跳过。
 * - 不用 `copyRecursively(overwrite = true)`：FUSE 上「覆盖」等于先删目标，
 *   而删非本应用创建的文件会失败，整次迁移抛异常中断。
 * - 不用 `overwrite = false`：目标已有同名文件时会抛
 *   `FileAlreadyExistsException`，同样中断（参考项目记录过这个坑）。
 * - 搬完按**相对路径**核对，不是比文件总数 —— 目标自带文件会让总数恒成立，
 *   掩盖漏拷。
 */
internal fun mergeTree(src: File, dst: File): Boolean {
    dst.mkdirs()
    val files = ArrayList<File>(64)
    val dirs = ArrayList<File>(16)
    collectNoFollow(src, files, dirs, 0)

    for (f in files) {
        // 先写 `.tmp` 再改名：避免中途被杀时留下半个文件被当成完整附件
        val target = File(dst, relOf(src, f))
        if (target.isFile) continue
        val ok = runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".migrating")
            f.inputStream().use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
        if (!ok) return false
    }
    // 空目录结构同样有意义
    for (d in dirs) {
        val rel = relOf(src, d)
        if (rel.isNotEmpty()) File(dst, rel).mkdirs()
    }
    // 按相对路径核对：源的每个文件都必须在目标存在
    for (f in files) {
        if (!File(dst, relOf(src, f)).isFile) return false
    }
    return true
}

/** [f] 相对 [base] 的路径。 */
private fun relOf(base: File, f: File): String =
    f.absolutePath.substring(base.absolutePath.length).trimStart(File.separatorChar)

/** 递归收集普通文件与子目录，跳过符号链接（防穿越与环）。 */
private fun collectNoFollow(
    dir: File,
    files: MutableList<File>,
    dirs: MutableList<File>,
    depth: Int
) {
    if (depth > 12) return
    val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
    for (f in kids) {
        if (isLink(f)) continue
        if (f.isDirectory) {
            dirs.add(f)
            collectNoFollow(f, files, dirs, depth + 1)
        } else if (f.isFile) {
            files.add(f)
        }
    }
}

// ----------------------------------------------------------------------
// attachments
// ----------------------------------------------------------------------

/**
 * 把 `$DSH_HOME/attachments` 外置。
 *
 * ## 为什么需要
 *
 * agent 逐层查过 `read_image` 的调用链（`dsh-fs-local` / `dsh-attachment-local`）：
 *
 * ```
 * read_image → resolveLocalTarget() → realpath.native()      ← 这步没问题
 *            → 写入附件存储 $DSH_HOME/attachments/v1
 *            → ensureDurableDirectory(): mkdir + chmod + 逐级 fsync 到根
 *            → 走到 /data/user/0 时 open(O_RDONLY) → EACCES
 * ```
 *
 * **失败点与图片本身放在哪里无关** —— 换 `/sdcard`、换 `/data/data`、
 * 复制到别处全部无效。同一根因也让**对话框上传图片**失败
 * （上传的图同样要落进 `attachments/v1`）。
 *
 * ## 为什么按版本分目录
 *
 * 附件属于某个版本的数据，删除该版本时应一并清掉。所以目标路径带版本号，
 * 与 `$DSH_HOME` 一样是「每版本一份、互不相通」。
 *
 * @param dshHomeDir 当前版本的 `$DSH_HOME`（`home/.dsh-<版本>`）。
 */
internal fun ensureAttachmentsExternal(context: Context, dshHomeDir: File): File? {
    val version = dshHomeDir.name.removePrefix(".dsh-")
    val target = publicDataRoot(context, "attachments", version)
    if (target == null) {
        DSHRunner.onLog("⚠️ 附件目录：没有可用的公开位置，保持原地（read_image 可能仍失败）")
        return null
    }
    return externaliseDir(File(dshHomeDir, "attachments"), target, "附件目录")
}

/**
 * 删除某个版本的附件目录（版本被删时调用）。
 *
 * 只删 `attachments/<版本>` 这一层，绝不碰共享根目录里的其它版本。
 */
internal fun removeAttachmentsForVersion(context: Context, version: String) {
    runCatching {
        val manager = Build.VERSION.SDK_INT >= 30 &&
            android.os.Environment.isExternalStorageManager()
        if (manager) {
            val docs = File(
                android.os.Environment.getExternalStorageDirectory(),
                "dshdata/attachments/$version"
            )
            if (docs.isDirectory) docs.deleteRecursively()
        }
        context.getExternalFilesDir(null)?.let { ext ->
            val dir = File(ext, "attachments/$version")
            if (dir.isDirectory) dir.deleteRecursively()
        }
    }
}

// ----------------------------------------------------------------------
// 工作区
// ----------------------------------------------------------------------

/**
 * 确保工作区（`$HOME/Documents`）是**真实目录**，不是软链。
 *
 * ## ★ 为什么必须撤销外置（真实故障）★
 *
 * 曾经把 `Documents` 软链到 `/storage/emulated/0/dshdata/Documents`，想去掉
 * 路径里的 `/data/user/0`。结果 dsh **无法新建会话**：
 *
 * ```
 * session/workspace-attach-failed:
 *   cannot attach session to workspace '/data/data/<pkg>/files/home/Documents':
 *   its cwd resolves to '/storage/emulated/0/dshdata/Documents'
 * ```
 *
 * dsh 会把它记录的 workspace 路径与 `realpath()` 的结果比对，
 * 软链让两者不一致就直接拒绝。已存在的会话不受影响（它们不需要重新挂载），
 * 所以症状是「老会话能开，新会话一律失败」。
 *
 * 教训：**工作区这类被引擎当作身份标识的路径，不能用软链改物理位置。**
 * 附件目录（attachments）不是 cwd，不受这条约束；但工作区是。
 *
 * ## 为什么还要「修复」而不只是「不再外置」
 *
 * 已经升过级的设备上，软链**已经存在**了。仅删掉调用点不会把它变回真实目录，
 * 故障会一直留着。所以这里做一次性的反向迁移：
 *
 * 1. 把软链目标里的内容合并回同级的临时真实目录；
 * 2. 删除**软链本身**（`delete()` 只删链接，不动目标）；
 * 3. 把临时目录改名成 `Documents`。
 *
 * 任何一步失败都保留原状并记日志 —— 数据安全优先于「修好」。
 * 外部那份**不删**：万一合并有遗漏，用户还能自己找回来。
 *
 * @return 修好后（或本来就正常）的工作区目录
 */
internal fun restoreWorkspaceIfExternalised(workspaceDir: File): File {
    // ① 不是软链：本来就是真实目录，建好即可
    if (!isLink(workspaceDir)) {
        runCatching { workspaceDir.mkdirs() }
        return workspaceDir
    }

    DSHRunner.onLog("🔧 工作区是软链（会导致无法新建会话），正在改回真实目录…")

    val target = readLinkTarget(workspaceDir)
    val parent = workspaceDir.parentFile
    if (parent == null) {
        DSHRunner.onLog("⚠️ 工作区没有父目录，无法修复")
        return workspaceDir
    }

    // ② 先合并到一个临时真实目录，成功后再替换软链。
    //    用同级目录而不是 cache：rename 必须在同一挂载点内才原子。
    val staging = File(parent, workspaceDir.name + ".restoring")
    runCatching { staging.deleteRecursively() }

    if (target != null) {
        if (!mergeTree(target, staging)) {
            // 合并失败 → 保留软链（至少文件还能用），下次启动再试
            runCatching { staging.deleteRecursively() }
            DSHRunner.onLog("⚠️ 工作区数据搬回失败，暂时保留软链（新建会话仍会失败）")
            return workspaceDir
        }
    } else {
        // 读不到软链目标（目标已被删或链接损坏）→ 建空目录
        DSHRunner.onLog("ℹ️ 工作区软链目标不可读，将重建为空目录")
    }
    runCatching { staging.mkdirs() }

    // ③ 删软链本身。
    //    ⚠️ 这里**只能**用 delete()，绝不能用 deleteRecursively() ——
    //    后者会顺着软链把外面那个目录的内容一起删掉。
    if (!runCatching { workspaceDir.delete() }.getOrDefault(false)) {
        runCatching { staging.deleteRecursively() }
        DSHRunner.onLog("⚠️ 无法删除工作区软链，保持原状")
        return workspaceDir
    }

    // ④ 临时目录改名成正式名字
    if (!staging.renameTo(workspaceDir)) {
        // 跨挂载点等少见情况：退化为复制
        runCatching { staging.copyRecursively(workspaceDir, overwrite = true) }
        runCatching { staging.deleteRecursively() }
    }
    runCatching { workspaceDir.mkdirs() }

    if (isLink(workspaceDir)) {
        DSHRunner.onLog("⚠️ 工作区修复未完成，仍是软链")
    } else {
        DSHRunner.onLog("✅ 工作区已恢复为真实目录，新会话可以正常创建")
        if (target != null) {
            DSHRunner.onLog("ℹ️ 原外置目录保留在 ${target.absolutePath}，确认无误后可手动删除")
        }
    }
    return workspaceDir
}
