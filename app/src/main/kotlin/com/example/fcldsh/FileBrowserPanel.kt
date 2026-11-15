package com.example.fcldsh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.DSHRunner
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「文件浏览器」面板：浏览容器家目录，支持删除与导出。
 *
 * ## 显示范围：家目录里**除核心必要以外**的东西
 *
 * 根是 [DSHRunner.instanceHome]（`filesDir/home`）。下面两类不显示：
 *
 * 1. **隐藏项**（名字以 `.` 开头）默认全部隐藏 —— 这一类几乎就是
 *    「引擎自己的东西」：`.dsh-<版本>`（会话/插件/登录态）、`.npm`、`.cache`、
 *    `.config`、`.env`（明文密钥）。给它们一个删除按钮等于把「误删毁实例」
 *    做成一个点击距离。
 * 2. 需要看时可以用顶部「隐藏项」开关打开，但 [isCore] 命中的那些
 *    **仍然不给删除**（只标「核心」）—— 它们是实例的身份与登录态，
 *    删掉就是数据全丢，而这个面板不是回收站。
 *
 * 非隐藏的普通文件（`Documents`、`AGENTS.md`、截图、`a11y-bridge.json` 等）
 * 全部可见、可删、可导出。
 *
 * ## 删除的两条铁律
 *
 * - **软链只删链接本身**：`attachments` / `Documents` 是指向外部存储的软链，
 *   对链接 `deleteRecursively()` 会顺着把 `/storage/...` 下的真实目录删掉，
 *   而且不可恢复。所以先判 [com.isLink]，命中就只 `delete()`。
 * - **删目录是阻塞 IO**：几千个文件的树可能在主线程上卡住，所以放到
 *   [kotlinx.coroutines.Dispatchers.IO]，删完再回主线程刷新。
 *
 * ## 导出
 *
 * 面板自己不碰 SAF（那是 Activity 的职责，见 [MainActivity] 的导出 launcher），
 * 只通过 [onExport] 把选中的 [File] 交出去。
 */
@Composable
fun FileBrowserPanel(onExport: (File) -> Unit) {
    val context = LocalContext.current
    val root = remember { DSHRunner.instanceHome(context) }
    val scope = rememberCoroutineScope()

    var dir by remember { mutableStateOf(root) }
    var tick by remember { mutableStateOf(0) }
    var showHidden by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 只列一层，逐层只看直接子项：家目录里也有几万个文件（`Documents` 下的
    // 工作区），一次全量递归会把面板卡住。
    val entries = remember(dir, tick, showHidden) { listEntries(dir, showHidden) }
    val atRoot = dir.absolutePath == root.absolutePath

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

        // ── 标题行 ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "文件浏览器",
                color = Fcl.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = relPath(root, dir),
                color = Fcl.TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SmallAction(if (showHidden) "隐藏项 ✓" else "隐藏项") {
                showHidden = !showHidden
            }
            Spacer(modifier = Modifier.width(4.dp))
            SmallAction("刷新") { tick++ }
            if (!atRoot) {
                Spacer(modifier = Modifier.width(4.dp))
                SmallAction("上级") { dir = dir.parentFile ?: root }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (busy) {
            Text(
                text = "正在删除…",
                color = Fcl.Warning,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // ── 列表 ────────────────────────────────────────────────────
        FclSettingSection("${entries.size} 项")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            if (entries.isEmpty()) {
                FclSettingRow(
                    label = "这里没有可显示的项目",
                    description = if (!showHidden) {
                        "隐藏项已折叠：.dsh-* / .npm / .cache 等引擎数据，" +
                            "以及 AGENTS.md、a11y-bridge.* 等启动器生成的文件。" +
                            "可用顶部「隐藏项」展开只读查看。"
                    } else {
                        "这个目录是空的。"
                    },
                    shape = RowShape.SINGLE
                ) { }
            } else {
                entries.forEachIndexed { index, f ->
                    val isDir = f.isDirectory && !com.isLink(f)
                    val builtin = isBuiltin(f.name)
                    // 核心与内置都不给删除：核心删了实例坏掉，内置是启动器自己
                    // 写的文件（AGENTS.md / 桥凭据 / 截图），删了下次启动又会生成，
                    // 留着删除按钮只会让人以为「删掉了某个重要东西」。
                    val locked = isCore(f.name) || builtin
                    FclSettingRow(
                        label = f.name + (if (isDir) "/" else ""),
                        description = buildString {
                            append(if (isDir) "文件夹" else fbSize(f.length()))
                            append(" · ")
                            append(fbTime(f.lastModified()))
                            if (com.isLink(f)) append(" · 软链")
                            if (isCore(f.name)) append(" · 核心，不可删")
                            else if (builtin) append(" · 启动器生成，不可删")
                        },
                        shape = RowShape.of(index, entries.size),
                        // 目录点进去；文件点了不做动作（避免误触）
                        onClick = if (isDir) {
                            { dir = f }
                        } else {
                            null
                        }
                    ) {
                        if (!locked && !busy) {
                            FileAction(text = "删除", danger = true) { pendingDelete = f }
                            Spacer(modifier = Modifier.width(2.dp))
                        }
                        FileAction(text = "导出", enabled = !busy) { onExport(f) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Text(
            text = "根目录：${root.absolutePath}\n" +
                "隐藏项包含 .dsh-* 等引擎数据与 AGENTS.md、a11y-bridge.* 等启动器生成的文件。\n" +
                "软链只删链接本身，不会动外部存储里的真实文件。",
            color = Fcl.TextTertiary,
            fontSize = 9.sp,
            lineHeight = 13.sp
        )
    }

    // ── 删除确认 ────────────────────────────────────────────────────
    pendingDelete?.let { target ->
        val isDir = target.isDirectory && !com.isLink(target)
        ConfirmDialog(
            title = "删除「${target.name}」",
            detail = buildString {
                append("将删除 ")
                append(relPath(root, target))
                append("。此操作不可撤销，没有回收站。")
                if (isDir) {
                    append("\n\n这是一个目录，里面所有内容会一起被删除。")
                }
                if (isCore(target.name)) {
                    append("\n\n⚠️ 这是核心目录，删除会导致实例数据丢失。")
                }
            },
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                busy = true
                scope.launch {
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        deleteTree(target)
                    }
                    busy = false
                    if (ok) {
                        DSHRunner.onLog("🗑 已删除 ${relPath(root, target)}")
                    } else {
                        DSHRunner.onLog("❌ 删除失败: ${relPath(root, target)}")
                    }
                    tick++
                }
            }
        )
    }
}

/**
 * 启动器**自己生成**在 `$HOME` 下的文件。
 *
 * 这些不是用户内容：`AGENTS.md` 每次启动由 [AgentGuide] 重写、`a11y-bridge.*`
 * 是桥接的端口与凭据、`screenshot.png` 是 `a11y shot` 的落点。它们对用户
 * 没有「管理」价值，却占了家目录最上面几行 —— 所以和 `.dsh-*` 一样按隐藏项
 * 处理，只有打开「隐藏项」才看得到，而且**不给删除**（删了下次启动又生成，
 * 那个按钮只会误导）。
 */
private val BUILTIN_FILES = setOf(
    "AGENTS.md",
    "a11y-bridge.json",
    "a11y-bridge.md",
    "screenshot.png",
    "workspace.json",
    "workspace.jsonc",
    "attachment-storage.json"
)

/** 是不是启动器生成的文件（大小写敏感 —— 这些都是精确文件名）。 */
private fun isBuiltin(name: String): Boolean = name in BUILTIN_FILES

/**
 * 列出 [dir] 的直接子项，目录在前、大小写无关排序。
 *
 * 「隐藏项」是一组条件而不是单一规则：
 * - 名字以 `.` 开头（`.dsh-<版本>`、`.npm`、`.cache`、`.env`…）；
 * - 或者命中 [BUILTIN_FILES]（启动器生成的配置与截图）。
 *
 * 两类都由 [showHidden] 决定是否出现；即使出现，[isCore] / [isBuiltin]
 * 命中的也不给删除（在 UI 层判断，这里只负责列）。
 */
private fun listEntries(dir: File, showHidden: Boolean): List<File> {
    val kids = runCatching { dir.listFiles() }.getOrNull().orEmpty()
    return kids
        .asSequence()
        .filter { it.name != ".keep" }
        .filter { showHidden || !(it.name.startsWith(".") || isBuiltin(it.name)) }
        .sortedWith(
            compareBy<File> { if (it.isDirectory) 0 else 1 }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
        .toList()
}

/**
 * 「核心必要」的判据：删了实例就坏掉的目录。
 *
 * 只按**名字前缀**判断，不比路径 —— 这些名字在 `$HOME` 下只出现在顶层
 * （`.dsh-<版本>`、包管理器缓存、配置目录），子目录里同名无害。
 */
private fun isCore(name: String): Boolean {
    val core = listOf(".dsh", ".npm", ".cache", ".config", ".local", ".ssh", ".env")
    return core.any { name == it || name.startsWith("$it-") }
}

/**
 * 递归删除，返回是否删干净。
 *
 * **软链只删链接**：`deleteRecursively()` 会顺着链接删外部真实目录，
 * 那是不可恢复的破坏（`attachments` / `Documents` 都是软链）。
 */
private fun deleteTree(file: File): Boolean {
    if (com.isLink(file)) return runCatching { file.delete() }.getOrDefault(false)
    if (file.isDirectory) {
        val kids = runCatching { file.listFiles() }.getOrNull()
        if (kids != null) {
            var ok = true
            for (k in kids) {
                if (!deleteTree(k)) ok = false
            }
            if (!ok) return false
        }
    }
    return runCatching { file.delete() }.getOrDefault(false)
}

/** 相对根目录的显示路径；根本身显示为「/」。 */
private fun relPath(root: File, f: File): String {
    val r = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    val p = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
    if (p == r) return "/"
    return if (p.startsWith(r + File.separator)) p.substring(r.length + 1) else p
}

private fun fbSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun fbTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * 行内文字按钮（删除 / 导出）。
 *
 * 为什么不复用 `InstanceSettingsPanel.kt` 里的 `RowAction`：那是该文件的
 * `private` 顶层函数，文件作用域可见，跨文件引用不到（会报 Unresolved reference）。
 * 为两个按钮把它提升成 `internal` 会牵动另一个文件，这里自带一份更稳妥。
 *
 * 禁用时**不挂手势**，而不是只调灰 —— 「点了没反应」和「点不动」对用户是两回事。
 */
@Composable
private fun FileAction(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val base = Modifier
        .clip(RoundedCornerShape(Fcl.Radius))
        .padding(horizontal = 6.dp, vertical = 4.dp)
    Text(
        text = text,
        color = when {
            !enabled -> Fcl.TextTertiary
            danger -> Fcl.Danger
            else -> Fcl.ThemeText
        },
        fontSize = 12.sp,
        modifier = if (enabled) base.fclClickable { onClick() } else base
    )
}

/** 面板顶部的小文字按钮（隐藏项 / 刷新 / 上级）。 */
@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Fcl.Radius),
        color = Fcl.Track,
        modifier = Modifier.fclClickable { onClick() }
    ) {
        Text(
            text = text,
            color = Fcl.ThemeText,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * 把 [src] 打进 zip 写到 [out]，返回给人看的结果说明。
 *
 * 由 [MainActivity] 的导出 launcher 调用（SAF 选好位置后才真正执行）。
 *
 * ## 为什么不跟随软链
 *
 * `Documents` / `attachments` 是指向 `/storage/emulated/0/...` 的软链。
 * 跟随进去会打出一个几百 MB 甚至上 GB 的包，而且用户以为导出的是「这个链接」。
 * 软链直接跳过，并在日志里说明 —— 比静默打出一个巨大的包好。
 */
internal fun exportFileTo(out: java.io.OutputStream, src: File): String {
    var files = 0
    var skipped = 0
    java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out, 64 * 1024)).use { zos ->
        fun put(f: File, name: String) {
            if (com.isLink(f)) {
                skipped++
                return
            }
            if (f.isDirectory) {
                val kids = runCatching { f.listFiles() }.getOrNull().orEmpty()
                if (kids.isEmpty()) {
                    runCatching {
                        zos.putNextEntry(java.util.zip.ZipEntry("$name/"))
                        zos.closeEntry()
                    }
                }
                for (k in kids) put(k, "$name/${k.name}")
                return
            }
            if (!f.isFile) return
            runCatching {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                f.inputStream().use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
                files++
            }
        }
        put(src, src.name)
        zos.finish()
    }
    val msg = "已导出 ${src.name}（$files 个文件）" +
        if (skipped > 0) "，跳过 $skipped 个软链" else ""
    return msg
}
