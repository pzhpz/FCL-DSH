package com

import android.content.Context
import java.io.File

/**
 * 应用 HOME 目录的**唯一**入口。
 *
 * ## ★ 这里是 `filesDir/home`，不要再改成外部存储 ★
 *
 * 曾经把 HOME 迁到 `/storage/emulated/0/Android/data/<包名>/files/home`
 * 想绕开 `/data/user/0` 的 0711 限制（见下），结果**把用户的
 * 插件、登录状态、会话历史全弄丢了**。
 *
 * 事故原因很清楚：
 *
 * ```
 * dshHome() = appHomeDir() + "/.dsh-<版本>"
 * ```
 *
 * `dshHome()` 被 A11yBridgeServer / AgentGuide / EngineInstaller 等很多地方
 * **在迁移执行之前**调用。它们一碰 `appHomeDir()` 就会在外部目录里建出
 * `.dsh-<版本>`（空目录），于是等 `migrateHomeIfNeeded()` 真正跑到时，
 * 目标目录「已有内容」→ 判定为无需迁移 → 老数据永远留在 `filesDir/home`
 * 而 dsh 在新空目录里重新初始化。表现就是插件、登录、会话全部消失。
 *
 * **教训**：HOME 这种「所有数据都挂在下面」的根路径，不能在多个调用点上
 * 各自推导，更不能让「可能被提前创建」的路径参与迁移判定。
 * 数据安全优先于工具便利 —— 读图工具的限制用别的方式解决
 * （截图额外写一份到 [com.example.fcldsh.ScreenCaptureState.externalScreenshotFile]，那条路只涉及图片文件）。
 *
 * ## 关于 `/data/user/0` 的限制（保留记录，别再走那条路）
 *
 * `/data/user/0` 是指向 `/data/data` 的符号链接，权限 `root:root 0711`：
 * 普通应用 UID 有 `x`（可穿过）但没有 `r`（不可列出）。任何对**目录本身**
 * 做 `opendir` 的工具（很多读图器在打开文件前会先解析父目录）都会
 * `EACCES: permission denied, open '/data/user/0'`。
 *
 * 这个限制是真的，但**不能靠搬 HOME 来绕过** —— 代价是数据安全。
 */
internal fun appHomeDir(context: Context): File {
    val dir = File(context.filesDir, "home")
    runCatching { dir.mkdirs() }
    return dir
}

/** 曾经写过迁移标记的目录名。仅用于识别旧版的残留。 */
private const val MIGRATION_MARK = ".home-relocated"

/**
 * 返回可用的 HOME，并**救回**上一版误迁到外部存储的数据。
 *
 * ## 为什么还需要这个函数
 *
 * 上一版把 HOME 迁到了外部目录。可能的情况有三种：
 *
 * 1. **迁移从未成功**（绝大多数）：外部目录是空的、或只有几个空的
 *    `.dsh-<版本>` 目录。用户的数据全在 `filesDir/home` —— 直接返回它即可，
 *    不需要任何动作。这是当前用户的实际情况。
 * 2. **迁移成功过、之后又在新位置产生了数据**：新位置里有真实内容
 *    （非空的 `.dsh-*`）。那种情况下把新位置的内容合并回内部目录，
 *    否则用户在新位置聊出来的会话会随着回滚一起丢。
 * 3. **外部存储不可用**：`getExternalFilesDir` 返回 null，什么也不做。
 *
 * 只在 `DSHRunner.startNode()` 的工作线程里调用，不在 UI 线程。
 */
internal fun migrateHomeIfNeeded(context: Context): File {
    val home = appHomeDir(context)

    // 识别上一版的外部 HOME，把真实数据合并回来。
    //
    // 只复制「确实有数据」的部分：上一版留下的空 `.dsh-<版本>` 目录
    // 正是事故的产物，不能让它覆盖用户真正在用的内部目录。
    val legacyExternal = runCatching {
        context.getExternalFilesDir(null)?.let { File(it, "home") }
    }.getOrNull()

    if (legacyExternal != null && legacyExternal.isDirectory) {
        val salvage = runCatching { salvageFromExternal(legacyExternal, home) }.getOrDefault(0)
        if (salvage > 0) {
            DSHRunner.onLog("♻️ 已从旧的外部 HOME 收回 $salvage 个数据目录")
        }
    }

    home.mkdirs()
    return home
}

/**
 * 把外部 HOME 里有实际内容的子目录合并进内部 HOME。
 *
 * ## 判定「有实际内容」
 *
 * 事故留下的 `.dsh-<版本>` 是**空目录**（dsh 刚初始化就退出了）。
 * 而真正用过的目录里一定有文件（会话、凭据、插件配置）。
 * 所以判据是「递归数一下里面有没有文件」，而不是「目录存在」。
 *
 * ## 为什么只补缺失、不覆盖
 *
 * 内部 HOME 是用户的主数据。外部那份只可能是上一版短暂使用过的残留，
 * 冲突时**以内部为准**。这样最坏情况是多几个无害的旧目录，
 * 而不是把正在用的数据覆盖坏。
 *
 * @return 合并了多少个子目录（0 表示没有可救的数据）
 */
private fun salvageFromExternal(externalHome: File, home: File): Int {
    val kids = runCatching { externalHome.listFiles() }.getOrNull() ?: return 0
    var merged = 0
    for (src in kids) {
        if (!src.isDirectory) continue
        // 迁移标记之类的小文件跳过
        if (src.name == MIGRATION_MARK) continue
        // 空目录（事故产物）跳过 —— 这是关键，否则会把空壳当成数据
        if (!hasAnyFile(src)) continue

        val dst = File(home, src.name)
        if (dst.isDirectory && hasAnyFile(dst)) {
            // 内部已有真实数据 → 以内部为准，不碰
            continue
        }
        val ok = runCatching {
            copyTree(src, dst)
            true
        }.getOrDefault(false)
        if (ok) merged++
    }
    return merged
}

/** 递归判断目录里是否至少有一个普通文件。 */
private fun hasAnyFile(dir: File, depth: Int = 0): Boolean {
    if (depth > 8) return false
    val kids = runCatching { dir.listFiles() }.getOrNull() ?: return false
    for (f in kids) {
        if (isSymlink(f)) continue
        if (f.isFile) return true
        if (f.isDirectory && hasAnyFile(f, depth + 1)) return true
    }
    return false
}

/**
 * 递归复制目录，返回复制的字节数。
 *
 * 跳过符号链接：数据目录里不该有链接，真遇到也不值得为它冒无限递归的风险。
 * 用 `Os.lstat` 判断「这一项本身是不是链接」，不能用
 * `canonicalPath != absolutePath` —— 那对本应用里**每个**条目都成立
 * （`/data/user/0` 与 `/data/data` 是同一目录的两种写法），导出器曾被它坑成 0 字节包。
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

/** 这一项**本身**是不是符号链接（不跟随）。失败时保守地当作不是。 */
private fun isSymlink(f: File): Boolean = runCatching {
    val st = android.system.Os.lstat(f.absolutePath)
    android.system.OsConstants.S_ISLNK(st.st_mode)
}.getOrDefault(false)
