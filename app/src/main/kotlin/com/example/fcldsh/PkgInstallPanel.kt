package com.example.fcldsh

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.Locale

/**
 * 装包 / 卸包的小窗 —— [TermuxPkg] 的界面。
 *
 * ## 为什么按「包名搜索」而不是做分类浏览
 *
 * Termux 仓库有几千个包，而索引里的 `Section` / `Tags` 字段并不完整（不少包是空的），
 * 按分类树去组织必然出现「一大半包归不进去」。搜索是唯一在完整数据上都成立
 * 的浏览方式 —— 用户知道 `ffmpeg`、`python`、`jq` 这些名字，直接搜最快。
 *
 * ## 索引什么时候拉
 *
 * 打开窗口时拉一次（几 MB，走 IO），之后 [TermuxPkg.loadIndex] 有内存缓存。
 * 拉索引失败不影响已装列表的显示 —— 那部分只读本地记录。
 *
 * ## 安装

 * 必须在后台线程：下载 + 解包是几十秒到几分钟的事。这里用裸 `Thread` 而不是
 * 协程，是因为 [TermuxPkg.install] 内部一路阻塞式 IO，包在协程里只是多一层
 * 无意义的 `withContext`。
 */
@Composable
fun PackageInstallDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefix = remember { File(context.filesDir, "usr") }

    var query by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    // 索引拉取：阻塞 IO，用 produceState 挂到 IO 上。
    // 失败也要有结果（[indexError]），否则界面会永远停在「正在读取索引」。
    var indexError by remember { mutableStateOf<String?>(null) }
    val index by produceState(
        initialValue = emptyList<TermuxPkg.Pkg>(),
        reload
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { TermuxPkg.loadIndex() }.onFailure {
                indexError = it.message ?: it.javaClass.simpleName
            }.getOrDefault(emptyList())
        }
    }

    // 已装列表：写盘后靠 TermuxPkg.phase 变化触发重读。
    val phase = TermuxPkg.phase
    val installed = remember(phase, reload) { TermuxPkg.installed(prefix) }
    val message = TermuxPkg.message
    val progress = TermuxPkg.progress
    val busy = TermuxPkg.isBusy()

    val shown = remember(index, query) { TermuxPkg.search(index, query, limit = 40) }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            // 装包途中不许点外部关掉：后台线程还在写 $PREFIX，
            // 关掉窗只会让用户失去对它的控制（与安装进度窗同一条规矩）。
            dismissOnBackPress = !busy,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.width(460.dp),
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.DialogBg
        ) {
            Column(modifier = Modifier.padding(14.dp)) {

                // ── 标题 ────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "安装软件包",
                        color = Fcl.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "来源：Termux 仓库 · ${TermuxPkg.debArch()}",
                        color = Fcl.TextTertiary,
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text(text = "关闭", color = Fcl.TextSecondary, fontSize = 12.sp)
                    }
                }

                // ── 进度 / 结果 ──────────────────────────────────────
                if (busy || message.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${TermuxPkg.phase.label} · $message",
                        color = when (TermuxPkg.phase) {
                            TermuxPkg.Phase.ERROR -> Fcl.Danger
                            TermuxPkg.Phase.DONE -> Fcl.Success
                            else -> Fcl.TextSecondary
                        },
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    if (busy) {
                        Spacer(modifier = Modifier.height(4.dp))
                        if (progress in 0f..1f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Fcl.Theme,
                                trackColor = Fcl.Track
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Fcl.Theme,
                                trackColor = Fcl.Track
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── 搜索框 ──────────────────────────────────────────
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Fcl.Radius),
                    color = Fcl.Card
                ) {
                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索包名，例如 ffmpeg / python / jq",
                                color = Fcl.TextTertiary,
                                fontSize = 11.sp
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp, color = Fcl.TextPrimary),
                            cursorBrush = SolidColor(Fcl.ThemeText),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── 结果列表 ────────────────────────────────────────
                if (index.isEmpty()) {
                    Text(
                        text = indexError?.let { "读取索引失败：$it\n（检查网络后点「重试」）" }
                            ?: "正在读取仓库索引…",
                        color = if (indexError != null) Fcl.Danger else Fcl.TextTertiary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = { indexError = null; reload++ },
                        enabled = !busy
                    ) {
                        Text(text = "重试", color = Fcl.ThemeText, fontSize = 12.sp)
                    }
                } else {
                    FclSettingSection("匹配 ${shown.size} 个（共 ${index.size} 个包）")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        shown.forEachIndexed { i, pkg ->
                            PkgRow(
                                pkg = pkg,
                                installedVersion = installed
                                    .firstOrNull { it.name == pkg.name }?.version,
                                busy = busy,
                                shape = RowShape.of(i, shown.size),
                                onInstall = {
                                    Thread {
                                        TermuxPkg.install(
                                            context = context,
                                            prefix = prefix,
                                            names = listOf(pkg.name),
                                            onLog = { line -> AppState.launcher.log(line) }
                                        )
                                    }.start()
                                }
                            )
                        }
                    }
                }

                // ── 已装 ────────────────────────────────────────────
                if (installed.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FclSettingSection("已安装（${installed.size}）")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        installed.forEachIndexed { i, rec ->
                            FclSettingRow(
                                label = rec.name,
                                description = "${rec.version} · ${rec.files.size} 个文件",
                                shape = RowShape.of(i, installed.size)
                            ) {
                                if (!busy) {
                                    PkgAction(text = "卸载") {
                                        Thread {
                                            TermuxPkg.uninstall(
                                                prefix,
                                                rec.name,
                                                onLog = { l -> AppState.launcher.log(l) }
                                            )
                                            // 卸载不动 phase，手动触发一次重读
                                            reload++
                                        }.start()
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "安装位置：${prefix.absolutePath}\n" +
                        "装完重开终端即可直接敲命令（容器命令会被包装成同名 shell 函数）。\n" +
                        "这里不预装任何东西，装什么由你搜索决定。",
                    color = Fcl.TextTertiary,
                    fontSize = 9.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

/**
 * 搜索结果的一行。
 *
 * 已装的包不显示「安装」而显示版本号：重复安装同一个包没有意义，
 * 而留一个能点的按钮只会让人以为可以「更新」。
 */
@Composable
private fun PkgRow(
    pkg: TermuxPkg.Pkg,
    installedVersion: String?,
    busy: Boolean,
    shape: RowShape,
    onInstall: () -> Unit
) {
    FclSettingRow(
        label = pkg.name,
        description = buildString {
            append(pkg.description.ifBlank { "（无描述）" })
            val size = when {
                pkg.size >= 1024L * 1024L ->
                    String.format(Locale.ROOT, "%.1f MB", pkg.size / 1024.0 / 1024.0)
                pkg.size >= 1024L ->
                    String.format(Locale.ROOT, "%d KB", pkg.size / 1024)
                else -> "${pkg.size} B"
            }
            append("\n${pkg.version} · $size")
            if (pkg.depends.isNotEmpty()) {
                append(" · 依赖 ${pkg.depends.size} 个")
            }
        },
        shape = shape
    ) {
        if (installedVersion != null) {
            Text(text = "已装", color = Fcl.Success, fontSize = 10.sp)
        } else if (busy) {
            Text(text = "…", color = Fcl.TextTertiary, fontSize = 11.sp)
        } else {
            PkgAction(text = "安装", onClick = onInstall)
        }
    }
}

/**
 * 行内小按钮。
 *
 * 与 `FileBrowserPanel.FileAction` 是同一个形状 —— 那边的 `RowAction` 是
 * `InstanceSettingsPanel.kt` 里的 `private` 顶层函数，跨文件引用不到。
 * 三个地方各有一份小包装，是为了不让「一个纯样式函数」去牵动三个文件的可见性。
 */
@Composable
private fun PkgAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (danger) Fcl.Danger else Fcl.ThemeText,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(Fcl.Radius))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .fclClickable { onClick() }
    )
}
