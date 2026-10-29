package com.example.fcldsh

import com.DSHRunner
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把已安装的 dsh 引擎连同它的数据目录导出成一个 zip 压缩包。
 *
 * ## 用途
 *
 * 1. **迁移/备份** —— 换手机或重装前把整套环境存到共享存储，
 *    不用再联网重装一遍（npm 装一次常常十几分钟）。
 * 2. **分享** —— 把一份装好的环境发给别人，对方用下载中心的
 *    「从本地文件安装」直接导入。
 * 3. **排查** —— 出问题时把包导出来在电脑上检查结构。
 *
 * ## 包里有什么
 *
 * 两类条目，对应导入侧两条落盘路径：
 *
 * ```
 * node_modules/<pkg>/…   → usr/lib/versions/<版本>/node_modules/
 * dsh-data/<相对路径>    → home/.dsh-<版本>/
 * ```
 *
 * **引擎侧打的是整个 `node_modules`**，不是只有 `@deepseek-ai`。
 * 传递依赖（hosted-git-info、birpc…）就在同一个目录里，少了它们
 * dsh 能启动但内置插件全部 import 失败，表现为「本部署没有可管理的
 * profile」和「新增会话失败」。早期只打作用域目录，导出的包
 * 「看起来有内容」却装回去是个残废引擎。
 *
 * **数据侧打的是该版本自己的 `DSH_HOME`**：插件、插件数据、MCP 配置、
 * 会话记录。需求是「导入整合包 ＝ 恢复一整套环境」，只给引擎的话，
 * 装完还得重装插件、重配 MCP。
 *
 * ## 登录状态**不**打包
 *
 * 凭据是设备与账号绑定的私密信息，混进一个可以随手分享的 zip 里风险太大。
 * 所以导出时按路径名把凭据类条目整体排除（见 [isCredentialPath]），
 * 导入方需要自己登录一次 —— 这是有意的取舍，不是缺陷。
 *
 * 被排除的文件名会写进导出日志，便于核对名单是否准确。
 *
 * ## 与导入侧的约定
 *
 * [DSHRunner.installDsh] 先认 `dsh-data/` 前缀（数据里也可能有叫
 * node_modules 的目录，必须先判），再对剩下的条目做
 * 「丢弃第一个 `node_modules/` 之前前缀」的归一化。导出侧严格按这个顺序
 * 写条目名，导出与导入才是一个完整往返，不需要额外元数据文件。
 *
 * ## 为什么写成 object 且自己维护状态
 *
 * 与 [EngineInstaller]、[DshEngineDownloader] 保持同一套模式：
 * 打包在后台线程跑，进度写进 Compose 状态由 UI 订阅。
 * [EngineTaskService] 也靠这里暴露的 [isBusy] / [message] 来维持前台保活，
 * 所以这几个成员不能省。
 *
 * 注意：KDoc 里不能出现「斜杠+星号」，Kotlin 的块注释支持嵌套，
 * 那样会再开一层注释、把整个文件判成未闭合（本项目已踩过多次）。
 */
object EngineExporter {

    enum class Phase(val label: String) {
        IDLE("就绪"),
        PACKING("打包中"),
        DONE("完成"),
        ERROR("失败")
    }

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** 0f..1f 为确定进度；负值表示未定（正在统计体积）。 */
    var progress by mutableStateOf(-1f)
        private set

    /** 供 UI 与前台服务通知显示的一行说明。 */
    var message by mutableStateOf("")
        private set

    /** 最近一次导出的落盘位置（SAF 提供的 Uri 字符串），供 UI 提示。 */
    var lastTarget by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var busy = false

    /** 供调用方判断"要不要先取消"，目前只在 UI 侧做禁用，不实现中断。 */
    @Volatile
    private var cancelled = false

    private val main = Handler(Looper.getMainLooper())

    private class Cancelled : Exception()

    fun isBusy(): Boolean = busy

    /** 打包过程中可取消；已写完的条目不会回滚（目标流由调用方关闭）。 */
    fun cancel() {
        if (busy) cancelled = true
    }

    fun reset() {
        if (busy) return
        phase = Phase.IDLE
        progress = -1f
        message = ""
        lastTarget = null
    }

    private fun post(block: () -> Unit) = main.post(block)

    /**
     * 找到「当前这一版引擎」的 `node_modules`；找不到返回 null。
     *
     * ## 为什么导出的是整个 node_modules 而不是只导出 @deepseek-ai
     *
     * 引擎的传递依赖（hosted-git-info、birpc…）就在同一个 `node_modules` 里，
     * 它们**不是**可选内容：少了它们 dsh 能启动，但内置插件全部 import 失败，
     * 表现为「本部署没有可管理的 profile」和「新增会话失败」。
     * 只导出作用域目录得到的包「看起来有内容」，装回去却是个残废引擎。
     *
     * 打包时条目名统一写成 `node_modules/<pkg>/…`，而 [DSHRunner.installDsh]
     * 的归一化规则是「丢弃第一个 node_modules/ 之前的前缀」，
     * 于是导出与导入构成完整往返 —— 依赖闭包一起进包、一起落地。
     *
     * ## 必须返回规范化路径
     *
     * `filesDir` 是 `/data/user/0/<pkg>/…`，而真实路径是 `/data/data/<pkg>/…`
     * （同一目录的两种写法）。若用未规范化的路径当根，后面算相对路径、
     * 判断符号链接全都会错位 —— 上一版正是因此把**每个文件**都当成符号链接跳过，
     * 于是「引擎目录为空」、导出产出 0 字节压缩包。这里统一 canonicalFile。
     */
    fun resolveExportRoot(context: Context): File? {
        val ver = DSHRunner.currentVersion(context) ?: return null
        val modules = File(DSHRunner.engineRoot(context, ver), "node_modules")
        // 引擎本体必须真实存在，否则装了半截，导出没有意义
        val scope = File(modules, "@deepseek-ai")
        if (!scope.isDirectory) return null
        if (scope.list()?.isEmpty() != false) return null
        return runCatching { modules.canonicalFile }.getOrDefault(modules)
    }

    /** 给 UI 用的一句话描述：这次会导出什么（版本 + 路径），用于日志与自检。 */
    fun describeSource(context: Context): String {
        val ver = DSHRunner.currentVersion(context)
        val root = resolveExportRoot(context)
        return if (root == null) {
            "未找到可导出的引擎目录（当前版本：${ver ?: "未安装"}）"
        } else {
            "版本 $ver → ${root.absolutePath}"
        }
    }

    /**
     * 把引擎打包写到 [output]。
     *
     * 流由调用方创建和关闭（SAF 的 `ContentResolver.openOutputStream`），
     * 这里只在 Finally 里 flush，不 close —— 由调用方负责，避免
     * 文件选择器回调里拿到的流被提前关掉又二次关闭。
     *
     * @param label 目标说明（"下载/dsh-0.2.1.zip" 之类），只用于日志与提示。
     * @return 成功时返回写入的条目数，失败返回 -1。
     */
    fun exportTo(context: Context, output: OutputStream, label: String): Int {
        if (busy) {
            DSHRunner.onLog("⚠️ 已有导出任务在进行，忽略本次请求")
            return -1
        }

        val root = resolveExportRoot(context)
        if (root == null) {
            DSHRunner.onLog("❌ ${describeSource(context)}")
            DSHRunner.onLog("❌ 没有可导出的内容，已放弃（不会产出空压缩包）")
            post {
                phase = Phase.ERROR
                progress = -1f
                message = "没有可导出的引擎内容"
            }
            return -1
        }

        busy = true
        cancelled = false
        lastTarget = label
        post {
            phase = Phase.PACKING
            progress = -1f
            message = "正在统计体积…"
        }

        var count = 0
        try {
            // ── 先统计，才能给出确定进度 ─────────────────────────────
            // 引擎子树通常几千个小文件、几十 MB，遍历一次的开销可以忽略，
            // 换来的是可信的进度条（而不是原地转圈的动画）。
            val files = ArrayList<File>(4096)
            var total = 0L
            collect(root, files) { size -> total += size }
            if (files.isEmpty()) {
                DSHRunner.onLog("❌ 引擎目录为空: ${root.absolutePath}")
                post {
                    phase = Phase.ERROR
                    progress = -1f
                    message = "引擎目录为空"
                }
                return -1
            }

            // ── 数据目录（插件 / 插件数据 / MCP / 会话）一并进包，但**不含登录状态** ──
            //
            // 需求是「导入整合包就等于恢复一整套环境」，但登录状态明确不打包：
            // 凭据是设备与账号绑定的私密信息，混进可以随手分享的整合包里风险太大。
            // 导入方需要自己登录一次，这是有意的取舍。
            //
            // 目录不存在（全新安装、还没启动过）时跳过，不算错误。
            // 这里取 canonicalFile：与 resolveExportRoot 同理，
            // 用未规范化路径算相对路径会因 /data/user/0 与 /data/data 两种写法错位。
            val dataDir = runCatching { DSHRunner.dshHome(context).canonicalFile }
                .getOrElse { DSHRunner.dshHome(context) }
            val dataAll = ArrayList<File>(512)
            if (dataDir.isDirectory) {
                // 先全量收集、体积稍后再算：被过滤掉的凭据不该计入进度总量，
                // 否则进度条会永远走不到 100%。
                collect(dataDir, dataAll) { }
            }

            val dataFiles = ArrayList<File>(dataAll.size)
            val skipped = ArrayList<String>(8)
            var skippedBytes = 0L
            for (f in dataAll) {
                val rel = relativeTo(dataDir, f)
                if (isCredentialPath(rel)) {
                    skippedBytes += f.length()
                    if (skipped.size < 8) skipped.add(rel)
                    continue
                }
                dataFiles.add(f)
                total += f.length()
            }

            DSHRunner.onLog(
                "📦 开始导出：引擎 ${files.size} 个文件、数据 ${dataFiles.size} 个文件，" +
                    "共 ${mb(total)} MB"
            )
            if (skipped.isNotEmpty()) {
                DSHRunner.onLog(
                    "🔒 已排除 ${skipped.size}${if (skipped.size >= 8) "+" else ""} 个登录状态文件" +
                        "（${mb(skippedBytes)}）：${skipped.joinToString(", ")}"
                )
                DSHRunner.onLog("ℹ️ 整合包不含登录凭据，导入后需要重新登录一次")
            } else if (dataFiles.isNotEmpty()) {
                // 一个都没排除时把数据目录的顶层名字打出来。
                //
                // 排除名单是按名字猜的（dsh 把凭据写在哪个文件由它自己的插件决定，
                // 我没有可靠依据写死路径）。这条日志是**核对手段**：
                // 若这里列出了明显属于登录态的文件（token / auth / credential…），
                // 说明名单漏了，需要把那个名字补进 CREDENTIAL_NAMES。
                val tops = runCatching { dataDir.listFiles() }.getOrNull()
                    ?.map { if (it.isDirectory) it.name + "/" else it.name }
                    ?.sorted()
                    ?.take(12)
                    ?: emptyList()
                DSHRunner.onLog("🔎 未发现凭据类文件，数据目录顶层：${tops.joinToString(", ")}")
                DSHRunner.onLog("   若上面有属于登录状态的文件，请告知以便补充排除名单")
            }

            // 条目名前缀有两类，分别对应导入侧的两条落盘路径：
            //   node_modules/<pkg>/…  → usr/lib/versions/<ver>/node_modules/
            //   dsh-data/<相对路径>    → home/.dsh-<ver>/
            //
            // 引擎侧：root 就是 `<ver>/node_modules`，所以 rel 已经是
            // `@deepseek-ai/...`、`hosted-git-info/...` 这种形式。加上 node_modules
            // 前缀后，installDsh 的「丢弃第一个 node_modules/ 之前的前缀」规则
            // 就能原样吃回去，依赖闭包也一起进包。
            //
            // 两类前缀绝不能混：导入侧先判 dsh-data/，再对引擎条目做 node_modules
            // 归一化。数据里若也有名为 node_modules 的目录（插件自带依赖），
            // 混在一起就会被误判成引擎文件。
            //
            // 不用 use{}：那会在结束（含异常路径）关闭调用方传进来的流，
            // 而 SAF 的 Uri 输出流由调用方统一 close，二次关闭虽然多数实现
            // 容忍，但没必要留下这个隐式约定。这里只 finish + flush。
            val zos = ZipOutputStream(BufferedOutputStream(output, 64 * 1024))
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var lastPostAt = 0L

            // 抽成局部函数：两批文件的写入逻辑完全一样，只有来源目录与前缀不同。
            // done / total / count / lastPostAt 由闭包共享，进度是连续的一条线，
            // 不会出现「引擎 100% 之后数据又从 0% 开始」的跳动。
            fun writeAll(base: File, items: List<File>, prefix: String) {
                for (f in items) {
                    if (cancelled) throw Cancelled()

                    val rel = f.absolutePath.substring(base.absolutePath.length)
                        .trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/')

                    val entry = ZipEntry(prefix + rel).apply {
                        // 固定时间戳：同一份引擎多次导出得到字节一致的包，
                        // 便于用哈希判断"两台机器上的引擎是否相同"。
                        time = FIXED_TIME
                    }
                    zos.putNextEntry(entry)
                    f.inputStream().use { input ->
                        while (true) {
                            if (cancelled) throw Cancelled()
                            val read = input.read(buf)
                            if (read <= 0) break
                            zos.write(buf, 0, read)
                            done += read

                            val now = System.currentTimeMillis()
                            if (now - lastPostAt > 250) {
                                lastPostAt = now
                                val p = if (total > 0) (done.toFloat() / total) else -1f
                                val text = "${mb(done)} / ${mb(total)} MB"
                                post { progress = p; message = text }
                            }
                        }
                    }
                    zos.closeEntry()
                    count++
                }
            }

            writeAll(root, files, "node_modules/")
            if (dataFiles.isNotEmpty()) {
                writeAll(dataDir, dataFiles, DSHRunner.DATA_PREFIX)
            }

            zos.finish()
            zos.flush()

            val withData = dataFiles.isNotEmpty()
            DSHRunner.onLog(
                "✅ 已导出：$count 个文件，${mb(total)} MB" +
                    (if (withData) "（含数据目录 ${dataFiles.size} 个文件）" else "（仅引擎，无数据目录）") +
                    " → ${label.ifBlank { "所选位置" }}"
            )
            post {
                phase = Phase.DONE
                progress = 1f
                message = if (withData) {
                    "已导出 $count 个文件（${mb(total)} MB，含数据）"
                } else {
                    "已导出 $count 个文件（${mb(total)} MB）"
                }
            }
            return count
        } catch (e: Cancelled) {
            DSHRunner.onLog("⏹ 导出已取消")
            post {
                phase = Phase.IDLE
                progress = -1f
                message = "已取消"
            }
            return -1
        } catch (e: IOException) {
            DSHRunner.onLog("❌ 导出失败: ${e.message ?: e.javaClass.simpleName}")
            post {
                phase = Phase.ERROR
                progress = -1f
                message = "导出失败: ${e.message ?: e.javaClass.simpleName}"
            }
            return -1
        } catch (e: Exception) {
            DSHRunner.onLog("❌ 导出异常: ${e.message ?: e.javaClass.simpleName}")
            post {
                phase = Phase.ERROR
                progress = -1f
                message = "导出异常: ${e.message ?: e.javaClass.simpleName}"
            }
            return -1
        } finally {
            busy = false
            cancelled = false
        }
    }

    /**
     * 递归收集普通文件，并把字节数累加给 [onSize]。
     *
     * ## 跳过符号链接的原因
     *
     * 容器里某些包会为了省空间放符号链接，`File.listFiles` 跟随链接时
     * 可能绕回上层目录，形成无限递归。
     *
     * ## ★ 不能用 canonicalPath 和 absolutePath 比较来判断链接 ★
     *
     * 上一版就是这么写的，结果是**每一个文件都被当成符号链接跳过**，
     * `files` 最终为空，导出报「引擎目录为空」并产出 0 字节压缩包。
     *
     * 原因：`filesDir` 给的是 `/data/user/0/<pkg>/…`，而真实路径是
     * `/data/data/<pkg>/…`（同一目录的两种写法，`/data/user/0` 是指向
     * `/data/data` 的符号链接）。于是 `canonicalPath != absolutePath`
     * 对**所有**条目都成立 —— 这个判断从一开始就错了。
     *
     * 改用 `Os.lstat` 直接问内核「这一项本身是不是链接」，
     * 与路径写法无关，也不会跟随链接。minSdk 23 上 `android.system.Os`
     * 从 API 21 就有，不需要版本守卫。
     */
    private fun collect(dir: File, out: MutableList<File>, onSize: (Long) -> Unit) {
        val items = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in items) {
            if (isSymlink(f)) continue
            if (f.isDirectory) {
                collect(f, out, onSize)
            } else if (f.isFile) {
                out.add(f)
                onSize(f.length())
            }
        }
    }

    /** 这一项**本身**是不是符号链接（不跟随）。失败时保守地当作不是。 */
    private fun isSymlink(f: File): Boolean = runCatching {
        val st = android.system.Os.lstat(f.absolutePath)
        android.system.OsConstants.S_ISLNK(st.st_mode)
    }.getOrDefault(false)

    /** 相对 [base] 的路径，统一用 `/` 分隔（跨平台拼条目名时不受分隔符影响）。 */
    private fun relativeTo(base: File, f: File): String =
        f.absolutePath.substring(base.absolutePath.length)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')

    /** 判断凭据名前要剥掉的扩展名。只剥配置类扩展，不碰目录名。 */
    private val CREDENTIAL_EXTENSIONS = listOf(
        ".json", ".jsonc", ".yml", ".yaml", ".toml", ".txt", ".db", ".sqlite", ".dat"
    )

    /**
     * 会被整体排除的「登录状态」文件名（已去扩展名、小写）。
     *
     * ## 为什么用名字而不是固定路径
     *
     * dsh 把凭据放在 `DSH_HOME` 下的哪个文件里，取决于它自己的
     * `llm-deepseek-account` / `llm-deepseek-api-key` 插件实现，
     * 我没有可靠依据写死一个路径。所以按「名字像凭据就排除」来兜，
     * 并**偏保守**：多排除一个无害（导入方重新登录一次），
     * 少排除一个就是把别人的 token 打进可随手分享的包里。
     *
     * 导出日志会列出实际被排除的文件名。如果发现某个真正存放登录态的
     * 文件不在名单里，把它加进这里即可；反之若误排除了插件数据，
     * 从名单里删掉那一个名字。
     */
    private val CREDENTIAL_NAMES = setOf(
        "auth", "auths", "authentication", "authorization",
        "credential", "credentials", "creds",
        "secret", "secrets",
        "account", "accounts",
        "login", "logins", "signin",
        "token", "tokens", "refresh-token", "refresh_token",
        "session-token", "session_token", "sessiontokens",
        "apikey", "apikeys", "api-key", "api-keys", "api_key", "api_keys",
        "keyring", "keyrings", "netrc",
        "cookies", "cookie-jar"
    )

    /** 名字里出现这些片段就足以判定为凭据，不再要求精确相等。 */
    private val CREDENTIAL_MARKERS = listOf(
        "credential", "keyring", "netrc", "sessiontoken", "session-token"
    )

    /**
     * 这个相对路径是否属于「登录状态 / 凭据」。
     *
     * 逐段判断：任一路径段命中就算命中。这样 `auth/token.json`、
     * `plugins/foo/credentials.json`、`.credentials` 都能覆盖。
     */
    private fun isCredentialPath(rel: String): Boolean {
        for (segment in rel.split('/')) {
            if (segment.isEmpty()) continue

            var name = segment.lowercase(Locale.ROOT)
            for (ext in CREDENTIAL_EXTENSIONS) {
                if (name.endsWith(ext)) {
                    name = name.dropLast(ext.length)
                    break
                }
            }
            // 点开头的隐藏文件（.credentials / .netrc）去掉点再比
            name = name.trimStart('.')
            if (name.isEmpty()) continue

            if (name in CREDENTIAL_NAMES) return true
            if (CREDENTIAL_MARKERS.any { name.contains(it) }) return true
        }
        return false
    }

    /** 固定成 DOS 时间 1980-01-01，让重复导出产生字节一致的包。 */
    private const val FIXED_TIME = 315532800000L

    private fun mb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1024f / 1024f)
}
