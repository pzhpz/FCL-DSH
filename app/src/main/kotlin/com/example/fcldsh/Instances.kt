package com.example.fcldsh

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 一个 dsh 部署。
 *
 * ## ★ 标识就是「版本号」★
 *
 * 早期版本用「实例名」做标识，代价是需要用户手起名字、还要处理重名、
 * 维护 `instance.json` 元数据、以及一整套「实例目录 → HOME → 引擎」的
 * 嵌套路径。那套设计反复出错，已经放弃。
 *
 * 现在只用**一个**标识：[id]，它就是 `usr/lib/versions/` 下的目录名。
 * 正常情况下 id 等于引擎的真实版本号（如 `0.2.1`）；同版本要并存时，
 * 自动加后缀（`0.2.1-2`、`0.2.1-3`…），见 [allocateVersion]。
 *
 * ```
 * filesDir/usr/lib/versions/<id>/node_modules/…   ← 引擎
 * filesDir/home/.dsh-<id>/                        ← $DSH_HOME
 * ```
 *
 * 靠这一层「目录名不同」就实现了「同名版本互不相通」，不需要额外元数据。
 */
data class Instance(
    /** 版本键：`usr/lib/versions/` 下的目录名，唯一。 */
    val id: String,
    /** 显示名，与 [id] 相同（界面上直接显示版本号）。 */
    val name: String,
    /** 引擎自报的真实版本号；读不到时回落到 [id]。 */
    val version: String,
    /** 该目录下引擎入口是否存在。扫目录时只收装好的，所以基本恒为 true。 */
    val installed: Boolean
)

/**
 * 版本目录的扫描 / 分配 / 删除。
 *
 * 所有路径推导都集中在这里，别处不要自己拼 `filesDir/usr/lib/versions`。
 */
object Instances {

    private const val TAG = "Instances"

    /** 容器安装目录（相对 filesDir）。 */
    private const val USR = "usr"

    /** 引擎版本根目录（相对 usr）。必须与 [com.DSHRunner] 的 VERSIONS_REL 一致。 */
    private const val VERSIONS_REL = "lib/versions"

    /** 引擎入口文件（相对版本目录）。存在且非空才算装好。 */
    private const val ENGINE_MARKER = "node_modules/@deepseek-ai/dsh/lib/bin.js"

    /**
     * 安装中的暂存目录（不是可用版本，扫目录时必须跳过）。
     *
     * 对外可见是因为 [Storage.clearResidue] 要清它的残留 —— 失败/取消的安装
     * 会在版本根下留一整棵 `node_modules`，它不属于任何实例，
     * 「删除实例」时顺手收掉才不会越攒越多。
     */
    const val INCOMING = "__incoming__"

    /** 导入整合包时数据目录的暂存目录（同样不是可用版本）。 */
    const val INCOMING_DATA = "__incoming_data__"

    /** 后缀从 2 开始：`-1` 看起来像版本号的一部分，容易误读。 */
    private const val SUFFIX_FROM = 2

    /** 后缀上限；真到这么多份同名部署，用时间戳兜底。 */
    private const val SUFFIX_TO = 999

    /** 版本根目录：`filesDir/usr/lib/versions`。 */
    fun versionsRoot(context: Context): File =
        File(File(context.filesDir, USR), VERSIONS_REL)

    /**
     * HOME 目录。
     *
     * 所有版本共用同一个 HOME（`filesDir/home`），各自的 `$DSH_HOME`
     * 是它下面的 `.dsh-<版本键>` —— 「互不相通」靠那一层区分，不靠 HOME。
     */
    fun homeDir(context: Context): File = File(context.filesDir, "home")

    /** 某个版本键的引擎目录（其下就是 `node_modules/`）。 */
    fun engineRoot(context: Context, id: String): File =
        File(versionsRoot(context), id)

    /** 某个版本键的 `$DSH_HOME`。 */
    fun dshHome(context: Context, id: String): File =
        File(homeDir(context), ".dsh-$id")

    /** 指针指向 [id] 时清掉它（其余情况不动，避免误清别人的选择）。 */
    private fun clearPointerIfActive(context: Context, id: String) {
        if (LauncherPrefs.getEngineVersion(context) == id) {
            LauncherPrefs.clearEngineVersion(context)
        }
    }

    /**
     * 为即将安装的 [realVersion] 分配一个**未被占用**的版本键。
     *
     * 这是本次改动的核心：同一个版本装第二次时不再弹窗让用户起名，
     * 而是自动变成 `0.2.1-2`。判断「占用」同时看引擎目录与数据目录 ——
     * 只删了引擎、留下 `.dsh-*` 的情况下也算占用，否则新装的会
     * 直接接管旧数据，那是「悄悄串数据」。
     */
    fun allocateVersion(context: Context, realVersion: String): String {
        val root = versionsRoot(context)
        val home = homeDir(context)
        val clean = realVersion.trim().ifBlank { "unknown" }

        fun free(key: String): Boolean =
            !File(root, key).exists() && !File(home, ".dsh-$key").exists()

        if (free(clean)) return clean
        for (n in SUFFIX_FROM..SUFFIX_TO) {
            val candidate = "$clean-$n"
            if (free(candidate)) return candidate
        }
        val fallback = "$clean-${System.currentTimeMillis()}"
        Log.w(TAG, "同名版本过多，回落到时间戳后缀：$fallback")
        return fallback
    }

    /**
     * 扫描全部装好的部署，新的排在前面。
     *
     * 只认「入口文件存在且非空」的目录：安装中途失败、或手动删了一半的
     * 残留不该出现在列表里，否则选中它只会换回一次启动失败。
     */
    fun list(context: Context): List<Instance> {
        val dirs = runCatching { versionsRoot(context).listFiles() }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Instance>(dirs.size)
        for (d in dirs) {
            if (!d.isDirectory) continue
            if (d.name == INCOMING || d.name == INCOMING_DATA) continue
            val entry = File(d, ENGINE_MARKER)
            if (!entry.isFile || entry.length() == 0L) continue
            out.add(
                Instance(
                    id = d.name,
                    name = d.name,
                    version = readVersionOf(d),
                    installed = true
                )
            )
        }
        // 新的在前：按目录最后修改时间倒序。
        // 不按版本号字符串排序 —— `rc.10` 会被排到 `rc.2` 前面，
        // 而带 `-2` 后缀的键又不是合法版本号，语义上排不出正确顺序。
        out.sortWith(Comparator<Instance> { a, b ->
            val ta = runCatching { File(versionsRoot(context), a.id).lastModified() }
                .getOrDefault(0L)
            val tb = runCatching { File(versionsRoot(context), b.id).lastModified() }
                .getOrDefault(0L)
            when {
                tb != ta -> tb.compareTo(ta)
                else -> b.id.compareTo(a.id)
            }
        })
        return out
    }

    /** 按版本键查；不存在返回 null。 */
    fun byId(context: Context, id: String?): Instance? {
        if (id.isNullOrBlank()) return null
        val dir = File(versionsRoot(context), id)
        if (!dir.isDirectory) return null
        val entry = File(dir, ENGINE_MARKER)
        return Instance(
            id = dir.name,
            name = dir.name,
            version = readVersionOf(dir),
            installed = entry.isFile && entry.length() > 0L
        )
    }

    /** 当前使用中的版本键；指针失效（目录被删）时返回 null。 */
    fun activeId(context: Context): String? {
        val id = LauncherPrefs.getEngineVersion(context) ?: return null
        return if (File(versionsRoot(context), id).isDirectory) id else null
    }

    /** 当前使用中的部署；未选择或已失效时返回 null。 */
    fun active(context: Context): Instance? = byId(context, activeId(context))

    /** 切换当前部署。只写指针，真正换进程发生在下次「启动」。 */
    fun setActive(context: Context, id: String) {
        LauncherPrefs.setEngineVersion(context, id)
    }

    /**
     * 记录某个键对应的真实版本号。
     *
     * 现在没有元数据文件：真实版本号由引擎自己的 `package.json` 决定，
     * 随时可读。所以这里只有一件事要做 —— 传空串表示「引擎已删除」，
     * 此时清掉指针即可。保留这个函数是为了不打断既有调用点。
     */
    fun setVersion(context: Context, id: String, version: String) {
        if (version.isNotBlank()) return
        if (LauncherPrefs.getEngineVersion(context) == id) {
            LauncherPrefs.clearEngineVersion(context)
        }
    }

    /**
     * 删除一个部署：引擎目录 + 它自己的数据目录 + 外置附件。
     *
     * 只删**这一个键**下面东西，绝不碰共享的 `usr/lib/versions` 与 `home`
     * 根目录 —— 那会把别的版本一起带走。
     *
     * @param onProgress 0f..1f 进度回调，可能在后台线程被调用。
     * @return 目录是否已不存在（本来就不存在也算成功）。
     */
    fun delete(context: Context, id: String, onProgress: (Float) -> Unit = {}): Boolean {
        val engine = engineRoot(context, id)
        val data = dshHome(context, id)
        if (!engine.exists() && !data.exists()) {
            clearPointerIfActive(context, id)
            return true
        }

        // 先数一遍条目，才能给出有意义的进度：引擎有上万个文件，
        // 删一次几十秒是常态，没有进度反馈看起来就是「点了没反应」。
        var total = 0
        fun count(d: File) {
            val kids = runCatching { d.listFiles() }.getOrNull() ?: return
            for (f in kids) {
                total++
                if (f.isDirectory) count(f)
            }
        }
        count(engine)
        count(data)

        var done = 0
        fun remove(d: File) {
            if (!d.exists()) return
            // ★ 软链只删链接本身 ★
            //
            // `attachments` 是外置的软链（见 AttachmentsStorage.kt）。对链接做
            // 递归会顺着走到外部目录里，把那边的文件一起删掉 —— 本项目在
            // 插件/工作区上各踩过一次，这里必须先拦。外部那份由
            // removeAttachmentsForVersion() 按版本精确清理。
            if (com.isLink(d)) {
                runCatching { d.delete() }
                done++
                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                return
            }
            val kids = runCatching { d.listFiles() }.getOrNull() ?: return
            for (f in kids) {
                if (f.isDirectory && !com.isLink(f)) remove(f)
                else {
                    runCatching { f.delete() }
                    done++
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            runCatching { d.delete() }
        }

        Log.i(TAG, "正在删除部署 $id …")
        remove(engine)
        remove(data)
        // 附件软链在公开位置（见 AttachmentsStorage.kt），不在上面两棵树里
        runCatching { com.removeAttachmentsForVersion(context, id) }

        // 删的是当前使用的那份时清掉指针
        clearPointerIfActive(context, id)

        // ── 彻底卸载：把「不属于任何实例」的残留一起收掉 ──────────────────
        //
        // 上面两棵树删完，磁盘上往往还剩两三百 MB 甚至上 G：
        //   · `usr/lib/versions/__incoming__*` —— 失败/取消的安装留下的整棵 node_modules
        //   · `home/.npm` —— npm 缓存，跨安装复用，装过几个版本就上 G
        // 前者任何时候都能删；后者只在「一个实例都不剩」时才删（见 purgeSharedCaches
        // 的注释：留着能让下次安装快几十秒）。用户点的是「卸载」，
        // 不该留下一堆没人会再读的东西。
        onProgress(1f)
        runCatching { Storage.clearResidue(context) }
        runCatching { Storage.purgeSharedCaches(context) }

        val ok = !engine.exists() && !data.exists()
        if (ok) Log.i(TAG, "已删除部署 $id（含缓存与安装残留）")
        else Log.w(TAG, "删除部署 $id 未完全成功，可能有文件被占用")
        return ok || (!engine.exists() && !data.exists())
    }

    /** 从某个版本目录里读引擎自报的版本号；读不到回落到目录名。 */
    private fun readVersionOf(dir: File): String {
        val pkg = File(dir, "node_modules/@deepseek-ai/dsh/package.json")
        val v = runCatching {
            if (!pkg.isFile || pkg.length() == 0L) return@runCatching null
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                .find(pkg.readText())?.groupValues?.get(1)
        }.getOrNull()
        return v?.takeIf { it.isNotBlank() } ?: dir.name
    }
}
