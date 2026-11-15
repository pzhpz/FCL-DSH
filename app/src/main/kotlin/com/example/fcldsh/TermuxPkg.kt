package com.example.fcldsh

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.DSHRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 从 **Termux 软件仓库**装包到本应用的 `$PREFIX`（`filesDir/usr`）。
 *
 * ## 结论先行：能不能「像 Termux 一样装 ffmpeg / python」
 *
 * **能装、大部分能用，但有一个改不了的边界，必须先说清。**
 *
 * Termux 的包是 `.deb`，里面是为 Android 编译好的 aarch64 二进制，但它们是在
 * Termux 的容器路径下 configure 出来的，很多二进制把
 * `/data/data/com.termux/files/usr` 这个前缀**编译进了字符串常量**。
 *
 * 那个路径属于 Termux 的私有目录（0700），我们既读不到也改不了；而我们的前缀
 * （`/data/user/0/com.example.fcldsh/files/usr`）**比它长**，所以连「在 ELF 里
 * 原地覆写字符串」都做不到 —— 会挤爆后面的字节，而 ELF 的偏移是编译期定死的。
 *
 * 结论按包类型分开：
 *
 * | 包类型 | 结果 |
 * |---|---|
 * | 纯数据包（字体、证书、terminfo、tzdata） | 完全可用 |
 * | 动态链接可执行文件（ffmpeg、jq、git） | 可用 —— 经系统 linker 加载，库靠 `LD_LIBRARY_PATH` 找 |
 * | 脚本类包 | 可用 —— 装完把脚本里的 Termux 前缀改写成我们的 |
 * | 前缀被编译进代码的（python、perl 的 `@INC`） | 需要额外环境变量（python 用 `PYTHONHOME`，见 [SandboxExec]） |
 *
 * 「能不能执行」不是障碍：本项目 `targetSdk = 34` 落在 `untrusted_app_29` 域，
 * 私有目录文件不能直接 exec，但交给 `/system/bin/linker64 <二进制>` 就行 ——
 * 这是本项目一直在用的机制，详见 [SandboxExec] 的类注释。
 *
 * ## 为什么不做成 apt
 *
 * `apt` / `dpkg` 自己也是 ELF，同样要经 linker 加载，而它们需要 root 或
 * `--force-not-root` 才能装，还会去写 `dpkg` 的状态库（那条路径同样被编译死）。
 * 直接用仓库的 `Packages` 索引 + 自己解 `.deb` 更可控，也少一层会坏的东西。
 *
 * ## 安装流程
 *
 * 1. 拉索引（`dists/stable/main/binary-<arch>/Packages`）解析成包表；
 * 2. 递归解析 `Depends`（只按名字、忽略版本约束，理由见 [parseDepends]）；
 * 3. 逐个下载 `.deb`，交给 [DebArchive] 解 `ar` → `tar`；
 * 4. 剥掉 `./data/data/com.termux/files/usr/` 前缀，落到 `$PREFIX`；
 * 5. 对解出来的**文本文件**改写其中的 Termux 前缀（二进制改不了，见上）；
 * 6. 记录每个包装了哪些文件，供卸载。
 *
 * 装完**不用重开应用**：`SandboxExec` 的 rc 在下次打开终端时按 `$PREFIX/bin`
 * 重新生成同名 shell 函数，所以新装的命令直接敲就有。
 */
internal object TermuxPkg {

    /** 仓库根。Termux 官方源，各架构都在同一域名下。 */
    private const val REPO_ROOT = "https://packages.termux.dev/apt/termux-main"

    /** Termux 编译期写死的前缀。解包时要剥掉它。 */
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /** 安装记录文件名（放在 `$PREFIX/etc` 下）。 */
    private const val DB_NAME = "fcl-pkgs.json"

    /** 文本改写的文件大小上限（超过的多半是数据，没必要扫）。 */
    private const val MAX_PATCH_BYTES = 4L * 1024L * 1024L

    /** 判别「二进制还是文本」时只看前这么多字节。 */
    private const val SNIFF_BYTES = 512

    // ────────────────────────── 对外状态（Compose） ──────────────────────

    enum class Phase(val label: String) {
        IDLE("就绪"),
        INDEX("读取仓库索引"),
        RESOLVE("解析依赖"),
        DOWNLOAD("下载"),
        EXTRACT("解包"),
        PATCH("修正路径"),
        DONE("完成"),
        ERROR("失败")
    }

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** 0f..1f；负值表示不确定。 */
    var progress by mutableStateOf(-1f)
        private set

    var message by mutableStateOf("")
        private set

    @Volatile
    private var busy = false

    fun isBusy(): Boolean = busy

    // ────────────────────────── 包元数据 ──────────────────────────

    /**
     * 索引里的一条包记录。
     *
     * 只留装包真正要用的字段 —— 索引里有二十多个字段，全解析出来除了占内存
     * 没有任何用处。
     */
    data class Pkg(
        val name: String,
        val version: String,
        /** 仓库内相对路径，如 `pool/main/f/ffmpeg/ffmpeg_6.0_aarch64.deb`。 */
        val filename: String,
        /** 已剥掉版本约束的依赖名列表。 */
        val depends: List<String>,
        val description: String,
        val size: Long
    ) {
        val url: String get() = "$REPO_ROOT/$filename"
    }

    /** 已装的包记录。 */
    data class Installed(
        val name: String,
        val version: String,
        /** 相对 `$PREFIX` 的路径。 */
        val files: List<String>
    )

    /** 索引缓存（内存）。重复装包不必每次重下几 MB 的索引。 */
    @Volatile
    private var cachedIndex: List<Pkg>? = null

    @Volatile
    private var cachedArch: String? = null

    // ────────────────────────── 索引 ──────────────────────────

    /**
     * 当前设备的 Termux 架构名。
     *
     * Termux 用 Debian 风格命名（`aarch64` / `arm` / `x86_64` / `i686`），
     * 不是 Android 的 `arm64-v8a`。映射错了索引会 404，所以只认这几个。
     */
    fun debArch(): String {
        // 只取首选 ABI。`Build.CPU_ABI` 已废弃（它只反映 32 位那条），
        // `SUPPORTED_ABIS` 永远非空，所以不需要再写一个「读不到就猜」的分支。
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when (abi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> "aarch64"
        }
    }

    /**
     * 拉并解析索引。**阻塞**，只能在后台线程调用。
     *
     * @param force 忽略内存缓存，强制重新下载
     */
    fun loadIndex(force: Boolean = false): List<Pkg> {
        val arch = debArch()
        val cached = cachedIndex
        if (!force && cached != null && cachedArch == arch) return cached

        setState(Phase.INDEX, -1f, "正在读取仓库索引（$arch）…")
        val text = fetchText("$REPO_ROOT/dists/stable/main/binary-$arch/Packages")
        val list = parseIndex(text)
        cachedIndex = list
        cachedArch = arch
        return list
    }

    /**
     * 解析 Debian `Packages` 索引。
     *
     * 格式是 RFC822 风格：字段 `Key: value`，记录之间空行分隔。
     * 只取装包需要的几项，**不认识的字段直接跳过** —— 索引的字段集随仓库演进，
     * 硬校验只会让整个解析在某天突然全空。
     */
    private fun parseIndex(text: String): List<Pkg> {
        val out = ArrayList<Pkg>(1024)
        val cur = HashMap<String, String>(16)

        fun flush() {
            val name = cur["Package"].orEmpty()
            val filename = cur["Filename"].orEmpty()
            if (name.isNotEmpty() && filename.isNotEmpty()) {
                out.add(
                    Pkg(
                        name = name,
                        version = cur["Version"].orEmpty(),
                        filename = filename,
                        depends = parseDepends(cur["Depends"].orEmpty()),
                        description = cur["Description"].orEmpty()
                            .lineSequence().firstOrNull().orEmpty(),
                        size = cur["Size"]?.toLongOrNull() ?: 0L
                    )
                )
            }
            cur.clear()
        }

        for (line in text.lineSequence()) {
            if (line.isBlank()) {
                flush()
                continue
            }
            // 续行（以空格 / 制表符开头）属于上一个字段；我们只关心首行，跳过
            if (line[0] == ' ' || line[0] == '\t') continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            cur[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        flush()
        return out
    }

    /**
     * 把 `Depends` 拆成纯包名。
     *
     * 形如 `libc++ (>= 27), libandroid-support, zlib`。
     * **版本约束一律忽略**：索引里每个包只有一个版本，比较没有意义，而硬解析
     * 约束只会在遇到 `<<`、`:any`、`|` 这些写法时把整个依赖漏掉 ——
     * 宁可多装一个，也不能因为解析不了一行就漏依赖。
     */
    private fun parseDepends(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .asSequence()
            // 备选依赖 `a | b` 取第一个：装一个就够
            .map { it.substringBefore('|') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('(').trim() }
            .map { it.substringBefore(':').trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * 从索引里按名字找包（界面搜索用）。
     *
     * 名字完全匹配的排最前，其余按「名字里含关键词」。
     */
    fun search(index: List<Pkg>, query: String, limit: Int = 60): List<Pkg> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return index.take(limit)
        val exact = index.filter { it.name.lowercase(Locale.ROOT) == q }
        val partial = index.filter {
            val n = it.name.lowercase(Locale.ROOT)
            n.contains(q) && n != q
        }
        return (exact + partial).take(limit)
    }

    // ────────────────────────── 安装 ──────────────────────────

    /**
     * 安装一个包及其依赖。**阻塞**，只能后台线程调用。
     *
     * @param prefix 容器根（`filesDir/usr`）
     * @param onLog 与启动日志同一条通道，用户能在界面上看到进度
     * @return 装成功的包数；负数表示整体失败
     */
    fun install(
        context: Context,
        prefix: File,
        names: List<String>,
        onLog: (String) -> Unit
    ): Int {
        if (busy) {
            onLog("⚠️ 已有安装任务在进行")
            return -1
        }
        busy = true
        var installed = 0
        try {
            val index = loadIndex()
            val byName = index.associateBy { it.name }

            setState(Phase.RESOLVE, -1f, "正在解析依赖…")
            val order = resolveOrder(names, byName, onLog)
            if (order.isEmpty()) {
                setState(Phase.ERROR, -1f, "没有找到要安装的包")
                return -1
            }
            onLog("📦 将要安装 ${order.size} 个包：${order.joinToString(" ")}")

            val db = loadDb(prefix)
            var done = 0
            for (name in order) {
                val pkg = byName[name] ?: continue
                val deb = File(context.cacheDir, "pkg-${pkg.name}.deb")
                try {
                    setState(
                        Phase.DOWNLOAD,
                        done.toFloat() / order.size,
                        "正在下载 $name ${pkg.version}…"
                    )
                    downloadTo(pkg.url, deb)

                    setState(
                        Phase.EXTRACT,
                        done.toFloat() / order.size,
                        "正在解包 $name…"
                    )
                    val result = DebArchive.extract(
                        deb = deb,
                        mapper = { raw -> mapToPrefix(raw, prefix) },
                        onProgress = { p ->
                            progress = (done.toFloat() + p) / order.size
                        },
                        // 软链目标同样要剥前缀，否则链接会指向别人的私有目录
                        onSoftLink = { target -> rewritePrefix(target, prefix) }
                    )

                    setState(Phase.PATCH, -1f, "正在修正 $name 的路径…")
                    patchPrefix(prefix, result.written)

                    val rels = result.written.mapNotNull { relOf(prefix, it) }
                    db[pkg.name] = Installed(pkg.name, pkg.version, rels)
                    saveDb(prefix, db)
                    installed++
                    done++
                    onLog("✅ 已安装 $name ${pkg.version}（${rels.size} 个文件）")
                } catch (e: Exception) {
                    onLog("❌ $name 安装失败: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    runCatching { deb.delete() }
                }
            }

            setState(
                if (installed > 0) Phase.DONE else Phase.ERROR,
                1f,
                if (installed > 0) "已安装 $installed 个包" else "全部失败，详见日志"
            )
            return installed
        } catch (e: Exception) {
            setState(Phase.ERROR, -1f, "安装失败: ${e.message ?: e.javaClass.simpleName}")
            onLog("❌ 包安装失败: ${e.message ?: e.javaClass.simpleName}")
            return -1
        } finally {
            busy = false
        }
    }

    /**
     * 依赖解析：深度优先、依赖在前。
     *
     * 只做**名字**层面的闭包（理由见 [parseDepends]）。索引里不存在的依赖
     * （例如 `termux-am` 这种 Termux 自带的）直接跳过并记一条日志 ——
     * 让整个安装因为一个装不到的依赖而中止是不合理的。
     */
    private fun resolveOrder(
        roots: List<String>,
        byName: Map<String, Pkg>,
        onLog: (String) -> Unit
    ): List<String> {
        val order = LinkedHashSet<String>()
        val visiting = HashSet<String>()

        fun visit(name: String) {
            if (name in order) return
            if (!visiting.add(name)) return // 依赖环，跳过
            val pkg = byName[name]
            if (pkg == null) {
                if (name.isNotEmpty()) onLog("ℹ️ 跳过仓库里没有的依赖: $name")
                visiting.remove(name)
                return
            }
            for (dep in pkg.depends) visit(dep)
            visiting.remove(name)
            order.add(name)
        }

        for (n in roots) visit(n)
        return order.toList()
    }

    /** 供界面显示的已装列表。 */
    fun installed(prefix: File): List<Installed> =
        loadDb(prefix).values.sortedBy { it.name }

    /**
     * 卸载一个包：删掉它记录过的文件，再清掉空目录。
     *
     * **不删别的包的文件**：只按记录删。若两个包共享同一个文件（例如都提供
     * `/bin/env`），后装的会覆盖记录，卸载时那个文件被删一次 —— 在「绝不误删
     * 别人文件」的前提下这是可接受的取舍。
     *
     * @return 删掉的文件数；-1 表示这个包没装过
     */
    fun uninstall(prefix: File, name: String, onLog: (String) -> Unit): Int {
        val db = loadDb(prefix)
        val rec = db[name] ?: return -1
        var removed = 0
        for (rel in rec.files) {
            val f = File(prefix, rel)
            val isLink = com.isLink(f)
            if (isLink || (f.exists() && !f.isDirectory)) {
                if (runCatching { f.delete() }.getOrDefault(false)) removed++
            }
            // 顺手清掉空父目录，最多向上 3 层 —— 再多就可能一路删到 $PREFIX 本身
            var dir = f.parentFile
            var depth = 0
            while (dir != null && depth < 3 &&
                dir.absolutePath.length > prefix.absolutePath.length
            ) {
                val kids = runCatching { dir.list() }.getOrNull()
                if (kids == null || kids.isNotEmpty()) break
                if (!runCatching { dir.delete() }.getOrDefault(false)) break
                dir = dir.parentFile
                depth++
            }
        }
        db.remove(name)
        saveDb(prefix, db)
        onLog("🗑 已卸载 $name（删除 $removed 个文件）")
        return removed
    }

    // ────────────────────────── 路径映射 ──────────────────────────

    /**
     * 把 tar 里的路径映射成 `$PREFIX` 下的目标文件。
     *
     * 认三种写法：
     * - `./data/data/com.termux/files/usr/bin/x`（Termux 官方包的实际形态）
     * - `data/data/com.termux/files/usr/bin/x`
     * - `/data/data/com.termux/files/usr/bin/x`
     *
     * 不在这前缀下的一律返回 null（[DebArchive] 会连同数据一起跳过）。
     * 空路径（包根目录本身）也返回 null —— 创建 `$PREFIX` 没有意义，
     * 它本来就存在。
     */
    private fun mapToPrefix(raw: String, prefix: File): File? {
        var p = raw
        while (p.startsWith("./")) p = p.substring(2)
        if (p.startsWith("/")) p = p.substring(1)
        val base = TERMUX_PREFIX.trimStart('/')
        val rel = when {
            p == base -> return null
            p.startsWith("$base/") -> p.substring(base.length + 1)
            else -> return null
        }
        if (rel.isEmpty()) return null
        // 再挡一次目录穿越：畸形包里的 `..` 不该写到 $PREFIX 之外
        if (rel.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return File(prefix, rel)
    }

    /** 把一段文本里的 Termux 前缀改写成我们的前缀。 */
    private fun rewritePrefix(raw: String, prefix: File): String =
        if (raw.contains(TERMUX_PREFIX)) {
            raw.replace(TERMUX_PREFIX, prefix.absolutePath)
        } else {
            raw
        }

    /** 绝对路径 → 相对 `$PREFIX` 的路径；不在其下返回 null。 */
    private fun relOf(prefix: File, f: File): String? {
        val base = prefix.absolutePath + File.separator
        val p = f.absolutePath
        return if (p.startsWith(base)) p.substring(base.length) else null
    }

    /**
     * 对刚写出的文件做**文本级**前缀改写。
     *
     * 只处理「看起来是文本」的文件：小于 [MAX_PATCH_BYTES]、前 [SNIFF_BYTES]
     * 字节里没有 NUL。二进制（ELF）改不了 —— 我们的前缀比 Termux 的长，
     * 原地覆写会挤爆后面的字节。
     *
     * 这不是「尽力而为」而是**唯一能做的一层**：不改写的话，装出来的脚本会去
     * `/data/data/com.termux/...` 找文件，必然失败。
     */
    private fun patchPrefix(prefix: File, written: List<File>) {
        val ours = prefix.absolutePath
        var patched = 0
        for (f in written) {
            if (com.isLink(f) || !f.isFile) continue
            if (f.length() > MAX_PATCH_BYTES) continue
            val head = ByteArray(SNIFF_BYTES)
            val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(-1)
            if (n <= 0) continue
            if ((0 until n).any { head[it] == 0.toByte() }) continue // 二进制
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (!text.contains(TERMUX_PREFIX)) continue
            runCatching {
                f.writeText(text.replace(TERMUX_PREFIX, ours))
                // 原本是脚本的保持可执行（解包时没有还原权限位）
                if (text.startsWith("#!")) f.setExecutable(true, false)
                patched++
            }
        }
        if (patched > 0) DSHRunner.onLog("🔧 已改写 $patched 个脚本里的容器前缀")
    }

    // ────────────────────────── 安装记录 ──────────────────────────

    private fun dbFile(prefix: File): File = File(File(prefix, "etc"), DB_NAME)

    private fun loadDb(prefix: File): LinkedHashMap<String, Installed> {
        val out = LinkedHashMap<String, Installed>()
        val f = dbFile(prefix)
        if (!f.isFile) return out
        val text = runCatching { f.readText() }.getOrNull() ?: return out
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return out
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = obj.optJSONObject(k) ?: continue
            val arr = o.optJSONArray("files") ?: JSONArray()
            val files = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) files.add(s)
            }
            out[k] = Installed(k, o.optString("version"), files)
        }
        return out
    }

    private fun saveDb(prefix: File, db: Map<String, Installed>) {
        val obj = JSONObject()
        for ((k, v) in db) {
            val o = JSONObject()
            o.put("version", v.version)
            o.put("files", JSONArray(v.files))
            obj.put(k, o)
        }
        val f = dbFile(prefix)
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(obj.toString(2))
        }
    }

    // ────────────────────────── 网络 ──────────────────────────

    private fun setState(p: Phase, prog: Float, msg: String) {
        phase = p
        progress = prog
        message = msg
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "fcl-dsh")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code: $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "fcl-dsh")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code: $url")
            dest.outputStream().buffered(64 * 1024).use { out ->
                conn.inputStream.use { it.copyTo(out, 64 * 1024) }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
