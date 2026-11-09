package com.example.fcldsh

import android.content.Context
import android.os.Environment
import com.DSHRunner
import java.io.File
import java.util.Locale

/**
 * 磁盘占用，按界面上的**三行**分组。
 *
 * ## 为什么只有三行
 *
 * 早先想按「容器 / 各版本引擎 / npm 缓存 / 安装临时文件 / 下载文件 / 附件 / 工作区」
 * 逐项列，一共七行 —— 但用户要的是「一眼看出 1.4G 花在哪、一键收掉」，
 * 不是一张文件系统报表。所以把「同性质、同处置方式」的路径合并成三组：
 *
 * | 组 | 内容 | 处置 |
 * |---|---|---|
 * | [engine]   | `usr/` + `home/`（各实例数据、工作区） | 只能靠「删除实例」释放 |
 * | [cache]    | `home/.npm`、`home/.cache`、`cacheDir/` | 可随时清，清了只是下次装慢点 |
 * | [download] | 外部下载目录里的 `.tgz` | 可随时清 |
 *
 * [reclaimable] 就是「一键清理」能收回的字节数 —— 引擎与实例数据不在内，
 * 否则那个按钮会变成一个「删除我的会话」按钮。
 */
data class StorageUsage(
    /** 容器 + 各实例的引擎与数据（会话、工作区、凭据）。 */
    val engine: Long = 0L,
    /** npm 缓存与安装/下载的临时文件。 */
    val cache: Long = 0L,
    /** 下载中心保存的包文件。 */
    val download: Long = 0L
) {
    val total: Long get() = engine + cache + download

    /** 一键清理能释放的量（引擎与实例数据不在内）。 */
    val reclaimable: Long get() = cache + download
}

/**
 * 存储占用的测量与清理。
 *
 * ## ★ 这里只清「可再生的东西」★
 *
 * 引擎、`$DSH_HOME`（会话/凭据/插件）、工作区（`Documents` 里是用户自己的文件）
 * 全部不在这份代码的删除范围内 —— 它们只能随「删除实例」一起走，
 * 那条路径有二次确认（[Instances.delete]）。这里的每个函数都必须是
 * 「清完不会有任何信息永久消失」的，否则迟早有人手滑点掉。
 *
 * ## 为什么缓存要单独看重
 *
 * npm 的 `_cacache` 是**跨安装复用**的（见 [EngineInstaller] 里那段注释）：
 * 装一个引擎会落下几百 MB，装几个版本就上 G，而「删除实例」按设计
 * 只删实例自己的两棵目录（`usr/lib/versions/<id>` 与 `home/.dsh-<id>`），
 * 缓存一分不动 —— 这正是「装完 400MB，用一段时间删干净却剩 1.4G」的主因。
 */
object Storage {

    /** npm 的缓存。装在 HOME 下、在实例之外，所以删实例不会带走它。 */
    private fun npmCache(context: Context): File =
        File(DSHRunner.instanceHome(context), ".npm")

    /** dsh / node 自己可能写的一些缓存目录（不存在就跳过）。 */
    private fun dshCache(context: Context): File =
        File(DSHRunner.instanceHome(context), ".cache")

    /** 下载中心保存包文件的位置（应用自己的外部目录，零权限可用）。 */
    private fun downloadDir(context: Context): File? = runCatching {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    }.getOrNull()

    /**
     * 量一遍三组的占用。
     *
     * 会真的遍历磁盘（引擎一棵树有上万个文件），**必须在后台线程调用**。
     * 软链不跟随：`attachments` 可能是软链（见 AttachmentsStorage.kt），
     * 跟随会既重复计数又可能在异常链接上绕圈。
     */
    fun measure(context: Context): StorageUsage {
        val home = DSHRunner.instanceHome(context)
        val cacheHome = sizeOf(npmCache(context)) + sizeOf(dshCache(context))

        val usr = File(context.filesDir, "usr")
        // home 里除去缓存就是「实例数据」：`.dsh-<id>`、`Documents`、日志等
        val engine = sizeOf(usr) + (sizeOf(home) - cacheHome).coerceAtLeast(0L)

        val cache = cacheHome + sizeOf(context.cacheDir)
        val download = downloadDir(context)?.let { sizeOf(it) } ?: 0L

        return StorageUsage(engine = engine, cache = cache, download = download)
    }

    /**
     * 清缓存与安装残骸，返回释放的字节数。
     *
     * @param keepTmp 引擎进程正在跑时为 true：`cacheDir/tmp` 是它的 `TMPDIR`
     *   （见 [DSHRunner.startNode]），删掉会让运行中的进程写临时文件失败。
     */
    fun clearCache(context: Context, keepTmp: Boolean): Long {
        var freed = 0L

        freed += wipe(npmCache(context))
        freed += wipe(dshCache(context))

        // cacheDir 整体清空，但目录本身留着；keepTmp 时跳过 TMPDIR
        val cacheDir = context.cacheDir
        val kids = runCatching { cacheDir.listFiles() }.getOrNull().orEmpty()
        for (f in kids) {
            if (keepTmp && f.name == "tmp") continue
            freed += wipe(f)
        }

        freed += clearResidue(context)
        DSHRunner.onLog("🧹 已清理缓存 ${fmtSize(freed)}")
        return freed
    }

    /** 清空下载目录（只影响本应用保存的包文件）。 */
    fun clearDownloads(context: Context): Long {
        val dir = downloadDir(context) ?: return 0L
        val freed = wipe(dir)
        DSHRunner.onLog("🧹 已清理下载文件 ${fmtSize(freed)}")
        return freed
    }

    /** 一键清理：缓存 + 下载。不碰引擎与实例数据。 */
    fun clearAll(context: Context, keepTmp: Boolean): Long {
        val freed = clearCache(context, keepTmp) + clearDownloads(context)
        DSHRunner.onLog("🧹 一键清理完成，共释放 ${fmtSize(freed)}")
        return freed
    }

    /**
     * 清掉「不属于任何实例、也不在 cacheDir 里」的安装残骸。
     *
     * 只有两处：版本根下的 `__incoming__` / `__incoming_data__`。
     * 安装或下载正在进行时**必须**跳过 —— 那正是它们在写的暂存目录，
     * 删了会让一次正常安装以「结果里没有 @deepseek-ai 目录」告终。
     */
    fun clearResidue(context: Context): Long {
        if (EngineInstaller.isBusy() || DshEngineDownloader.isBusy()) {
            DSHRunner.onLog("ℹ️ 安装/下载进行中，跳过残骸清理")
            return 0L
        }
        val root = Instances.versionsRoot(context)
        var freed = 0L
        for (name in listOf(Instances.INCOMING, Instances.INCOMING_DATA)) {
            freed += wipe(File(root, name))
        }
        return freed
    }

    /**
     * 删掉**最后一个**实例后收掉共享残留。
     *
     * ## 为什么必须等到「一个实例都不剩」
     *
     * npm 缓存是跨安装复用的：留着它，下次装同版本几乎不用下载。
     * 所以只要还有别的实例活着就不该动它。
     *
     * 但反过来，用户把实例全删光之后，这些缓存就再没有任何读者了 ——
     * 它们正是「删干净还剩 1.4G」的差额。此时一并收掉才是「彻底卸载」。
     *
     * 老布局的 `home/.dsh`（所有版本共用）同理：没有引擎可用时它就是
     * 谁都读不到的孤儿数据。**只在没有实例时删**，别的时候绝不碰。
     */
    fun purgeSharedCaches(context: Context) {
        if (Instances.list(context).isNotEmpty()) return
        // 正在装引擎时 npm 正往 .npm 里写，删了会让这次安装直接失败
        if (EngineInstaller.isBusy() || DshEngineDownloader.isBusy()) {
            DSHRunner.onLog("ℹ️ 安装/下载进行中，保留共享缓存")
            return
        }
        val freed = wipe(npmCache(context)) + wipe(dshCache(context)) + clearResidue(context)
        val legacy = File(DSHRunner.instanceHome(context), ".dsh")
        val legacyFreed = if (legacy.exists()) wipe(legacy) else 0L
        if (freed + legacyFreed > 0) {
            DSHRunner.onLog("🧹 已释放共享缓存与残留数据 ${fmtSize(freed + legacyFreed)}")
        }
    }

    /**
     * 递归统计字节数。
     *
     * 不限深度（npm 缓存是 `content-v2/sha512/ab/cd/<hash>`），跳过软链，
     * 读长度失败的文件直接忽略 —— 统计期间文件被删是常态，不该抛异常。
     */
    private fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        if (!dir.isDirectory || com.isLink(dir)) {
            return runCatching { dir.length() }.getOrDefault(0L)
        }
        var total = 0L
        fun walk(d: File) {
            val items = runCatching { d.listFiles() }.getOrNull() ?: return
            for (f in items) {
                if (com.isLink(f)) continue
                if (f.isDirectory) walk(f)
                else total += runCatching { f.length() }.getOrDefault(0L)
            }
        }
        walk(dir)
        return total
    }

    /**
     * 删除一个文件或目录，返回释放的字节数。
     *
     * ★ 软链只 `delete()` 链接本身 ★ —— 对链接做 `deleteRecursively()`
     * 会顺着链接把外部目录的内容一起删掉（本项目在附件/插件上各踩过一次）。
     */
    private fun wipe(target: File): Long {
        if (!target.exists()) return 0L
        val bytes = sizeOf(target)
        val ok = if (com.isLink(target)) {
            runCatching { target.delete() }.getOrDefault(false)
        } else if (target.isDirectory) {
            runCatching { target.deleteRecursively() }.getOrDefault(false)
        } else {
            runCatching { target.delete() }.getOrDefault(false)
        }
        return if (ok) bytes else 0L
    }
}

/** 人类可读的字节文案。三档足够，面板里不需要 `1234.5 KB` 这种精度。 */
internal fun fmtSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 ->
        String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L ->
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
