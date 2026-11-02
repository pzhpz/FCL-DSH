package com.example.fcldsh

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.DSHRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实例设置面板的四个页签。
 *
 * 需求点名的顺序：dsh设置 / 插件管理 / 会话管理 / mcp管理。
 * 前三项都直接读盘（[DSHRunner.dshHome] 指向的那份数据目录），
 * 不摆任何「尚未接入」的假开关。
 */
enum class InstanceTab(val label: String) {
    DSH("dsh设置"),
    PLUGIN("插件管理"),
    SESSION("会话管理"),
    SKILL("skill管理"),
    MCP("mcp管理");

    companion object {
        /** 按显示顺序。不用 `entries`，兼容老一些的 Kotlin 编译器。 */
        val all: List<InstanceTab> = listOf(DSH, PLUGIN, SESSION, SKILL, MCP)
    }
}

/**
 * 实例设置面板（对应 FCL 的「实例设置」）。
 *
 * ## 为什么页签状态在 [LauncherState] 里
 *
 * 面板会被侧栏的其他图标整个顶掉（组合树卸载），`remember` 在这里活不过一次切换。
 * 详见 [LauncherState.instanceTab]。
 *
 * ## 数据都来自哪里
 *
 * 全部走 [DSHRunner.dshHome]：进程里的 `DSH_HOME` 与这里显示的是同一份目录。
 * 开了「版本隔离」时两边一起换到 `home/.dsh-<ver>`（`.dsh` 的兄弟目录，
 * 不能嵌套在 `.dsh/` 里 —— 那会让 dsh 推导出错误的 HOME，详见该函数的注释）。
 * 「插件 / 会话 / MCP」三页只做**只读枚举**：这些是 dsh 自己的数据结构，
 * 面板里擅自增删会绕开 dsh 的一致性检查，出了问题也难查。
 */
@Composable
fun InstanceSettingsPanel(
    state: LauncherState,
    tab: InstanceTab,
    onSelectTab: (InstanceTab) -> Unit,
    onExportEngine: () -> Unit,
    /** 删除当前版本。真正的删除（后台线程 + 进度 + 停实例）由 MainActivity 负责。 */
    onDeleteVersion: () -> Unit,
    /** 把当前实例的第三方插件（含依赖闭包）打成一个包。 */
    onExportPlugins: () -> Unit,
    /** 选一个插件包导入，**直接写入 dsh**（解包 + 登记 profile 清单）。 */
    onImportPlugins: () -> Unit,
    /** 把当前实例的全部会话（含标题缓存）打成一个包。 */
    onExportSessions: () -> Unit,
    /** 选一个会话包导入，**直接写入 dsh**（已存在的会话跳过）。 */
    onImportSessions: () -> Unit,
    /** 只导出指定的一个插件（包名，含 scope）。 */
    onExportPlugin: (String) -> Unit,
    /** 只导出指定的一个会话（[SessionInfo.id]）。 */
    onExportSession: (String) -> Unit,
    /** 把当前实例的全部 skill 打成一个包。 */
    onExportSkills: () -> Unit,
    /** 选一个 skill 包导入，**直接写入 dsh**（已存在的文件跳过）。 */
    onImportSkills: () -> Unit,
    /** 只导出指定的一个 skill（目录名，所在根目录标签）。 */
    onExportSkill: (String, String) -> Unit,
    /** 把当前实例的全部 MCP 相关文件打成一个包。 */
    onExportMcp: () -> Unit,
    /** 选一个 MCP 包导入，**直接写入 dsh**（已存在的文件跳过）。 */
    onImportMcp: () -> Unit,
    /** 只导出指定的一个 MCP 配置（相对 `DSH_HOME` 的路径）。 */
    onExportMcpConfig: (String) -> Unit
) {
    val context = LocalContext.current

    // 只读页面的刷新开关：点「刷新」重新扫盘一次
    var tick by remember { mutableStateOf(0) }

    // 变了才重新取：避免每帧读一遍磁盘。
    // dshHome 现在按版本独立（home/.dsh-<版本>），不再有隔离开关 ——
    // 「各个 dsh 不互通」是默认行为，不需要用户选择。
    val dshHome = remember(tick, state.engineVersion) { DSHRunner.dshHome(context) }

    // 内置 bundle（`@deepseek-ai/` 作用域）解析不到的兜底位置：引擎自己的 node_modules。
    // 插件页要靠它才能区分「内置」与「用户装的第三方插件」。
    val engineModules = remember(tick, state.engineVersion) {
        state.engineVersion?.let { File(DSHRunner.engineRoot(context, it), "node_modules") }
    }

    // 返回键收回面板 —— 与下载中心 / 启动器设置保持一致。
    // 面板内不放「关闭」按钮，收回统一交给侧栏的房子图标或返回键。
    BackHandler(enabled = true) { state.closePanel() }

    // 打开面板先与磁盘对齐一次：可能刚在下载中心装完一个新版本，
    // 否则「引擎版本」那行会停在打开应用那一刻的旧值。
    LaunchedEffect(Unit) { state.refreshEngineVersion(context) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        // 底色由 PanelHost 统一给（跟随「面板不透明度」滑块），这里必须透明
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 标题 ────────────────────────────────────────────────
            Text(
                text = "实例设置",
                color = Fcl.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)
            )

            HorizontalDivider(color = Fcl.Divider)

            // ── 选项卡 ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                InstanceTabBar(selected = tab, onSelect = onSelectTab)
            }

            // ★ 这里不能再无脑套 verticalScroll ★
            //
            // 插件页与会话页改成了「左面板 + 右列表」两栏，两栏各自滚动，
            // 需要**有界高度**。而 verticalScroll 会把子项的高度约束成无限，
            // 里面的 weight / fillMaxHeight 全部失效，两栏会塌成内容高度、
            // 左右分栏根本立不起来。
            //
            // 所以滚动下沉到各页自己管：DSH / MCP 仍由 [ScrollingTab] 提供，
            // 插件 / 会话页自己分栏滚动。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                // 标签内容走竖向滑动：初次展开从下方伸入；切换标签时
                // 旧的向下滑走、新的向上滑入，两条同时进行。
                // 面板整体缩回时这里不做任何事（横向缩回由 PanelHost 负责）。
                FclTabContentHost(tab = tab) { activeTab -> when (activeTab) {
                    InstanceTab.DSH -> ScrollingTab {
                        DshSettingsTab(
                            state = state,
                            dshHome = dshHome,
                            onExportEngine = onExportEngine,
                            // ★ 这里必须调用传进来的 onDeleteVersion ★
                            //
                            // 上一版写成了同名 lambda，只是 tick++ 刷新一下自己，
                            // 把真正的回调（MainActivity.requestDeleteVersion）完全遮蔽掉 ——
                            // 表现就是「弹了确认框，点删除什么都没发生，也没有进度窗」。
                            // 刷新交给删除完成后 MainActivity 里的 refreshEngineVersion，
                            // 不在这里做。
                            onDeleteVersion = onDeleteVersion
                        )
                    }

                    InstanceTab.PLUGIN -> PluginTab(
                        dshHome = dshHome,
                        engineModules = engineModules,
                        tick = tick,
                        onRefresh = { tick++ },
                        onExport = onExportPlugins,
                        onImport = onImportPlugins,
                        onExportPlugin = onExportPlugin
                    )

                    InstanceTab.SESSION -> SessionTab(
                        dshHome = dshHome,
                        tick = tick,
                        onRefresh = { tick++ },
                        onExport = onExportSessions,
                        onImport = onImportSessions,
                        onExportSession = onExportSession
                    )

                    InstanceTab.SKILL -> SkillTab(
                        dshHome = dshHome,
                        tick = tick,
                        onRefresh = { tick++ },
                        onExport = onExportSkills,
                        onImport = onImportSkills,
                        onExportSkill = onExportSkill
                    )

                    // MCP 也改用两栏：它现在要多显示「扫描位置」，
                    // 单栏滚到底才能看到，不如和插件/会话/skill 页保持一致。
                    InstanceTab.MCP -> McpTab(
                        dshHome = dshHome,
                        tick = tick,
                        onRefresh = { tick++ },
                        onExport = onExportMcp,
                        onImport = onImportMcp,
                        onExportMcpConfig = onExportMcpConfig
                    )
                } }
            }
        }
    }
}

/**
 * 纵向滚动的单栏页签内容（dsh 设置 / mcp 管理用）。
 *
 * 这两页是纯列表，套一层滚动最简单；插件 / 会话页要分栏，
 * 需要自己去分配高度，不能用这个容器包着。
 */
@Composable
private fun ScrollingTab(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
    ) {
        content()
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/**
 * 「左侧面板 + 右侧列表」两栏布局。
 *
 * ## 宽度分配
 *
 * 左栏（操作面板）占 **三分之一**，右栏（列表）占 **三分之二** ——
 * 按要求用 `weight(1f)` 与 `weight(2f)`。列表是主要阅读对象，给更宽；
 * 左侧只放按钮与只读信息，窄一点够用。
 *
 * ## 两栏各自滚动
 *
 * 左栏有操作按钮 + 内置插件 + 扫描位置，可能比右栏还长；共用一个滚动条的话
 * 会互相拖累（滚左栏把列表滚出视野）。所以各自 `verticalScroll`，
 * 互不影响。
 *
 * ## 高度必须由调用方给成有界
 *
 * 这里用 `fillMaxHeight`，依赖父级已有确定高度。若外层再套
 * `verticalScroll`，高度约束变成无限，两栏会塌成内容高度 ——
 * 详见 [InstanceSettingsPanel] 里那段注释。
 */
@Composable
private fun SplitPane(
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) { left() }

        // 两栏之间留一点缝，否则两张卡片贴在一起像一整个
        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) { right() }
    }
}

/**
 * 页签条。
 *
 * 画法与下载中心的 [DownloadTabBar] 保持一致（圆角底板 + 选中项下划线），
 * 但这里是四格而不是五格，且与 [InstanceTab] 绑定 —— 两处共用一套组件
 * 需要把页签类型泛型化，为省这点重复引入泛型不划算。
 */
@Composable
private fun InstanceTabBar(selected: InstanceTab, onSelect: (InstanceTab) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Fcl.Radius),
        color = Fcl.Card
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            InstanceTab.all.forEach { t ->
                val active = t == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fclClickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = t.label,
                        color = if (active) Fcl.TextPrimary else Fcl.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    // 未选中时占同高的位，避免切换页签时整条高度跳动
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(if (active) Fcl.Theme else Color.Transparent)
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// dsh 设置
// ----------------------------------------------------------------------

/**
 * dsh 设置页：引擎版本、导出整合包、删除该版本、目录。
 *
 * 「导出整合包」接到 MainActivity 的 `engineExportLauncher`（SAF 的 CreateDocument），
 * 打完的包能被下载中心的「导入本地」原样装回去。
 *
 * 这里**没有**「版本隔离」开关：每个版本各持一份数据目录（`home/.dsh-<版本>`）
 * 已经是默认行为，不需要用户选择。
 */
@Composable
private fun DshSettingsTab(
    state: LauncherState,
    dshHome: File,
    onExportEngine: () -> Unit,
    onDeleteVersion: () -> Unit
) {
    val context = LocalContext.current

    val version = state.engineVersion
    val installed = version != null

    // 导出进度来自 EngineExporter（Compose 状态，自动重组）
    val exportPhase = EngineExporter.phase
    val exportProgress = EngineExporter.progress
    val exportMessage = EngineExporter.message
    val exporting = exportPhase == EngineExporter.Phase.PACKING

    FclSettingSection("引擎")

    FclSettingRow(
        label = "引擎版本",
        description = if (installed)
            "启动与导出都针对这一版。点此打开版本管理，可切换或多装一版。"
        else
            "还没有安装任何引擎版本，请先到下载中心安装。",
        shape = RowShape.TOP,
        onClick = { state.showVersions = true }
    ) {
        Text(
            text = version ?: "未安装",
            color = if (installed) Fcl.ThemeText else Fcl.TextTertiary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }

    // ── 导出整合包 ──────────────────────────────────────────────────
    FclSettingRow(
        label = "导出整合包",
        description = if (exporting)
            "$exportMessage" + if (exportProgress >= 0f) "（${(exportProgress * 100).toInt()}%）" else ""
        else
            "把当前版本连同依赖一起打成 zip，可备份、可分享，" +
                "也能用下载中心的「导入本地」装回去。",
        shape = RowShape.MIDDLE,
        // 引擎没装或正在打包时不让点：导出的目标目录不存在，点了只会失败
        onClick = if (installed && !exporting) {
            { onExportEngine() }
        } else null
    ) {
        Text(
            text = when {
                !installed -> "不可用"
                exporting -> "打包中…"
                exportPhase == EngineExporter.Phase.DONE -> "再次导出"
                else -> "导出"
            },
            color = if (installed && !exporting) Fcl.ThemeText else Fcl.TextTertiary,
            fontSize = 12.sp
        )
    }

    // ── 删除该版本 ──────────────────────────────────────────────────
    //
    // 放在导出下面：两个操作都作用于「当前版本」，
    // 「先备份、再删除」是唯一稳妥的顺序。
    DeleteVersionRow(
        version = version,
        shape = RowShape.BOTTOM,
        deleting = state.deletingVersion != null,
        onDeleteVersion = onDeleteVersion
    )

    Spacer(modifier = Modifier.height(6.dp))

    // ── 目录 ────────────────────────────────────────────────────────
    FclSettingSection("目录")

    FclSettingRow(
        label = "数据目录",
        description = "会话、凭据、插件与 MCP 配置。当前版本专用，与其他版本不互通。",
        shape = RowShape.TOP
    ) {
        // 显示的是**状态**而不是「查看」：面板里没有打开文件管理器的能力，
        // 一个看起来能点却什么都不做的「查看」属于假按钮。
        Text(
            text = version?.let { "独立" } ?: "—",
            color = if (installed) Fcl.Success else Fcl.TextTertiary,
            fontSize = 11.sp
        )
    }

    WorkspacePathRow(
        label = "",
        path = dshHome.absolutePath,
        shape = RowShape.BOTTOM
    )

    Spacer(modifier = Modifier.height(6.dp))

    FclSettingRow(
        label = "工作区",
        description = "dsh 的默认工作区落在 <工作区>/deepseek-harness。各版本共用，属于你自己的文件。",
        shape = RowShape.SINGLE
    ) {
        Text(text = "Documents", color = Fcl.TextSecondary, fontSize = 11.sp)
    }

    WorkspacePathRow(
        label = "",
        path = DSHRunner.workspaceDir(context).absolutePath,
        shape = RowShape.SINGLE
    )
}

/**
 * 「删除该版本」一行：二次确认 + 后台删除 + 进度小窗。
 *
 * ## 为什么要二次确认
 *
 * 它会连带删掉这个版本的**数据目录**（会话、凭据、插件配置），
 * 不可恢复。而这一行在列表里和其他行长得一样，误触的代价太大。
 *
 * ## 为什么删除要有独立进度
 *
 * 一个版本的 node_modules 有上万个文件，`deleteRecursively()` 要跑几秒到几十秒。
 * 没有进度反馈的话，界面看起来就是「点了没反应」。
 */
@Composable
private fun DeleteVersionRow(
    version: String?,
    shape: RowShape,
    deleting: Boolean,
    onDeleteVersion: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }

    FclSettingRow(
        label = "删除该版本",
        description = if (version == null) "当前没有可删除的版本。"
        else "删除 $version 的引擎文件与它自己的数据目录（会话、凭据、插件配置），不可恢复。",
        shape = shape,
        // 正在删或没装版本时不允许再点
        onClick = if (version != null && !deleting) {
            { confirming = true }
        } else null
    ) {
        Text(
            text = if (deleting) "删除中…" else if (version == null) "不可用" else "删除",
            color = if (version != null && !deleting) Fcl.Danger else Fcl.TextTertiary,
            fontSize = 12.sp
        )
    }

    if (confirming && version != null) {
        // 刻意**不用** Material 的 AlertDialog：
        // 它用 MaterialTheme 的 surface/圆角/内边距，跟 FCL 这套自定义配色
        // （Fcl.DialogBg + 8dp 圆角 + 12/15sp 字阶）放在一起明显是「另一个软件」。
        // 这里与版本管理、导出进度窗用同一套 Dialog + Surface 写法，风格统一。
        FclConfirmDialog(
            title = "删除 $version？",
            message = "会删除引擎文件与它的独立数据目录，其中的会话、插件配置、" +
                "MCP 设置一并消失，无法恢复。建议先「导出整合包」备份。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirming = false
                onDeleteVersion()
            },
            onDismiss = { confirming = false }
        )
    }
}

/**
 * FCL 风格的二次确认弹窗。
 *
 * 与 `VersionManagerPanel`、`ExportProgressDialog` 共用同一套外观，
 * 避免混进 Material 默认样式。点外部或返回键都算取消。
 */
@Composable
private fun FclConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.width(320.dp),
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.DialogBg
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = title,
                    color = Fcl.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = message,
                    color = Fcl.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Fcl.TextSecondary, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = onConfirm) {
                        Text(
                            text = confirmText,
                            color = if (danger) Fcl.Danger else Fcl.ThemeText,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}


/**
 * 一行等宽字体路径 + 存在状态。
 *
 * 路径单独占一行是因为 DSH_HOME 很长，塞进 `FclSettingRow` 的 trailing
 * 会被挤成省略号，反而看不出到底指向哪里。
 */
@Composable
private fun WorkspacePathRow(label: String, path: String, shape: RowShape) {
    val exists = remember(path) { File(path).isDirectory }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape.shape(),
        color = Fcl.Card
    ) {
        Column(modifier = Modifier.padding(horizontal = Fcl.RowPadding, vertical = 8.dp)) {
            if (label.isNotEmpty()) {
                Text(text = label, color = Fcl.TextTertiary, fontSize = 9.sp)
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = path,
                color = Fcl.TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (exists) "目录存在" else "目录尚未创建（首次启动时自动建）",
                color = if (exists) Fcl.Success else Fcl.TextTertiary,
                fontSize = 9.sp
            )
        }
    }
}

// ----------------------------------------------------------------------
// 插件管理
// ----------------------------------------------------------------------

/**
 * 一个已安装插件。
 *
 * @param name    包名（含 scope），如 `dsh-whale-widget` 或 `@scope/foo`
 * @param version 从 `package.json` 读到的版本；读不到为空串
 * @param dir     包目录
 * @param where   相对 `DSH_HOME` 的位置，用于在列表里说清「装在哪」
 * @param builtin 是否是引擎自带、用户没装过的内置 bundle（`@deepseek-ai/` 作用域下的包）。
 *   这类不算「插件」，单独归到「内置」一栏 —— 混在主列表里会让用户看到一堆
 *   自己不认识的名字，以为装错了东西。
 * @param title   插件自己声明的**显示名**（中文优先）。包名是 `dsh-whale-widget`
 *   这种机器名，用户认不出；插件在市场/管理器里显示的名字由包内清单提供，
 *   见 [readPluginTitle]。取不到时为空串，界面退回显示包名。
 */
private data class PluginInfo(
    val name: String,
    val version: String,
    val dir: File,
    val where: String,
    val builtin: Boolean = false,
    val title: String = ""
)

/**
 * 插件扫描结果。
 *
 * @param items     找到的插件
 * @param searched  实际扫描过的位置（**要在 UI 上显示出来**）。
 *   这一项不是装饰：插件到底装在哪里随 dsh 版本而变，把扫描路径摊开给用户看，
 *   才能在「还是没显示出来」时立刻判断是该补哪条候选路径，而不是靠猜。
 */
private data class PluginScan(
    val items: List<PluginInfo>,
    val searched: List<String>
)

/**
 * 插件管理：扫描已安装的插件。
 *
 * ## ★ 为什么不能只看 `DSH_HOME/plugins`（这是之前的真实 bug）★
 *
 * 上一版只扫 `$DSH_HOME/plugins`，结果**用户装了插件却一直显示「还没有插件」**。
 * 因为 dsh 的插件是 npm 包，按 Node 的解析规则装在各 profile 的
 * `node_modules` 下 —— 参考项目的 `EngineConfig` 注释写得很明确：
 *
 * > dsh 的 Loader 以 **profile 目录为 baseUrl** 解析插件名
 * > （Node 的父目录 node_modules 查找）… 在
 * > `$DSH_HOME/profiles/node_modules/<包名>` 放一条链接
 *
 * 所以真正的候选位置是 `profiles/<profile>/node_modules/` 与 `profiles/node_modules/`，
 * `plugins/` 只是兜底（某些版本/第三方打包可能用）。
 *
 * ## 为什么还要读 `package.json`
 *
 * 光列目录名不够：scope 包（`@scope/name`）在磁盘上是两层目录，
 * 直接列 `node_modules` 会得到一串 `@scope` 而看不到真正的包；
 * 而且版本号只有 `package.json` 里有。所以走「找包 → 读 package.json」。
 *
 * 只读展示。启用/禁用由 dsh 自己的 profile 配置决定，
 * 面板里改文件会绕开它的一致性检查。
 */
@Composable
private fun PluginTab(
    dshHome: File,
    engineModules: File?,
    tick: Int,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onExportPlugin: (String) -> Unit
) {
    val context = LocalContext.current

    // 市场索引缓存下来的「包名 → 显示名」。
    //
    // ★ 为什么需要它 ★
    //
    // 插件包的 package.json 里**没有**显示名字段 —— 实测 dsh-whale-widget
    // 只有 name / description / keywords，所以这里以前只能显示
    // `dsh-whale-widget` 这种用户认不出的机器名。
    // 而「插件下载」页能显示「余额小鲸鱼挂件」，是因为它读的是市场索引里的
    // `title: { zh, en }`。[PluginDownloader] 拉列表时把那映射缓存到了磁盘，
    // 这里直接复用，不需要再发一次网络请求（离线也能用）。
    val marketTitles = remember(tick) { PluginDownloader.cachedTitles(context) }

    val scan = remember(tick, engineModules, marketTitles) {
        scanPlugins(dshHome, engineModules, marketTitles)
    }

    // 订阅忙碌状态：导出进行中时行内的「导出」按钮要真的点不动
    val busy = PluginSessionTransfer.busy

    // 待确认删除的插件。非空即弹确认窗 —— 删除不可逆，
    // 不能让行内按钮一点就执行（它就在「导出」旁边，很容易误触）。
    var pendingDelete by remember { mutableStateOf<PluginInfo?>(null) }

    // 删盘是阻塞 IO，必须离开主线程；做完再回主线程刷新列表。
    // 用 Handler + Thread 而不是协程：本项目其它后台任务都是这个写法，
    // 保持一致，也不额外引入依赖。
    val main = remember { Handler(Looper.getMainLooper()) }

    // 只把「用户自己装的」当插件。内置 bundle 单列一栏，否则主列表里会混进一堆
    // 用户没装过、也认不出的名字（之前正是这个问题：2 个插件却列出 6 项）。
    val userPlugins = scan.items.filter { !it.builtin }
    val builtin = scan.items.filter { it.builtin }

    SplitPane(
        left = {
            // ── 能操作的：全部集中在左栏顶部 ──
            TransferActions(
                kind = PluginSessionTransfer.Kind.PLUGINS,
                refreshDesc = "重新扫描磁盘，装完插件后点这里。",
                exportDesc = "全部第三方插件打成 zip（含依赖闭包）。",
                importDesc = "解包并登记到 profile 清单，重启实例后生效。",
                onRefresh = onRefresh,
                onExport = onExport,
                onImport = onImport
            )

            // ── 以下只读、不能操作，按要求放在左栏下方 ──
            if (builtin.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FclSettingSection("内置（引擎自带）")
                builtin.forEachIndexed { i, p ->
                    FclSettingRow(
                        label = p.name.removePrefix("@deepseek-ai/"),
                        description = p.version.ifBlank { "内置" },
                        shape = RowShape.of(i, builtin.size)
                    ) {
                        Text(text = "内置", color = Fcl.TextTertiary, fontSize = 10.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 把扫描路径摊开：这是排查「装了却看不到」的关键信息。
            FclSettingSection("扫描位置")
            if (scan.searched.isEmpty()) {
                EmptyState(title = "没有可扫描的目录", detail = "数据目录尚未创建。")
            } else {
                scan.searched.forEachIndexed { i, path ->
                    val exists = remember(path) { File(path).isDirectory }
                    FclSettingRow(
                        label = if (exists) "已存在" else "不存在",
                        description = path,
                        shape = RowShape.of(i, scan.searched.size)
                    ) {
                        Text(
                            text = if (exists) "✓" else "—",
                            color = if (exists) Fcl.Success else Fcl.TextTertiary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        },
        right = {
            FclSettingSection("已安装插件（${userPlugins.size}）")

            if (userPlugins.isEmpty()) {
                EmptyState(
                    title = "还没有装第三方插件",
                    detail = "profile 的加载列表里没有内置以外的插件。装完点左侧「刷新列表」再试。"
                )
            } else {
                userPlugins.forEachIndexed { i, p ->
                    // 主标题是插件自己声明的显示名（"余额小鲸鱼挂件"），不是包名
                    // （"dsh-whale-widget"）—— 后者只在没声明显示名时顶上。
                    val title = p.title.ifBlank { p.name }
                    FclSettingRow(
                        label = if (p.version.isBlank()) title else "$title  ${p.version}",
                        // 没有显示名时可说清了「装在哪」；有显示名时把包名放进描述，
                        // 既认得又对得上 dsh 自己的名字。
                        description = if (p.title.isBlank()) {
                            p.where
                        } else {
                            "${p.name} · ${p.where}"
                        },
                        shape = RowShape.of(i, userPlugins.size)
                    ) {
                        Text(text = "已装", color = Fcl.Success, fontSize = 10.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        // 单项导出：只打这一个插件（+ 它的依赖闭包），
                        // 适合把某一个插件单独发给别人，不用整包搬全部插件。
                        RowAction(
                            text = "导出",
                            enabled = !busy,
                            onClick = { onExportPlugin(p.name) }
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        // 删除：目录 + profile 清单登记一起去掉（见 deletePlugin）。
                        // 先弹确认窗，不直接执行。
                        RowAction(
                            text = "删除",
                            enabled = !busy,
                            danger = true,
                            onClick = { pendingDelete = p }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    )

    // 删除确认窗。放在 SplitPane 之后：它是覆盖层，不参与分栏布局。
    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除插件「${target.title.ifBlank { target.name }}」",
            detail = "将删除 ${target.name} 的安装目录，并从 profile 加载清单里摘除。" +
                "重启实例后彻底消失。此操作不可撤销，需要时可重新下载。",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val pkg = target.name
                pendingDelete = null
                Thread {
                    PluginSessionTransfer.deletePlugin(context, pkg)
                    // 回主线程刷新：tick 变了列表才会重新扫盘
                    main.post { onRefresh() }
                }.start()
            }
        )
    }
}

/**
 * 行内小动作（FCL 里设置项右侧那种纯文字按钮）。
 *
 * 不用 Material 的 `Button`：它的内边距与最小高度会把 48dp 的行走形撑高，
 * 与旁边那串「已装 / 内置」小字对不齐。这里只是一段可点的文字，
 * 按下时有全局的缩放反馈（[Modifier.fclClickable]）。
 *
 * 禁用时**不挂手势**，而不是只把颜色调灰 —— 导入导出在后台跑，
 * 「点了没反应」和「点不动」对用户的区别很大。
 */
@Composable
private fun RowAction(
    text: String,
    enabled: Boolean = true,
    /** 危险操作（删除）用警示色，与「导出」这类安全操作用视觉区分开。 */
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

/**
 * 破坏性操作的二次确认。
 *
 * 删除插件/会话是不可逆的（会话删了找不回来，插件删了要重装），
 * 单点一下直接执行太容易误触 —— 行内「删除」按钮就在「导出」旁边，
 * 手指一偏就点中了。
 *
 * 只提供「取消 / 确认」两个动作，不提供「不再提示」：
 * 这类操作频率低，每次都确认更稳妥。
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    detail: String,
    confirmText: String = "删除",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true)
    ) {
        Surface(
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.Card
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    color = Fcl.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = detail,
                    color = Fcl.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(text = "取消", color = Fcl.TextSecondary, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = onConfirm) {
                        Text(text = confirmText, color = Fcl.Danger, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * 左侧面板的操作区：刷新 / 导出全部 / 导入 + 结果行。插件页与会话页共用。
 *
 * ## 为什么忙碌时把 onClick 置成 null 而不是只改文字
 *
 * [FclSettingRow] 只有在 `onClick != null` 时才挂点击手势。置空等于**真的点不动**，
 * 而不是「点了没反应」—— 导入导出在后台线程跑，连点两次会同时写同一个目录。
 *
 * 结果行只在有结果时出现，避免常驻一条空文案。
 */
@Composable
private fun TransferActions(
    kind: PluginSessionTransfer.Kind,
    refreshDesc: String,
    exportDesc: String,
    importDesc: String,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    // 订阅这两个状态：busy 变化要立刻禁用按钮，lastResult 变化要显示结果
    val busy = PluginSessionTransfer.busy
    // ★ 必须按 kind 过滤 ★
    //
    // 两个页面共用同一份 lastResult。不过滤的话，导出插件后切到会话页会看到
    // 「已导出 2 个插件」—— 用户会以为两页的数据串了。
    val result = PluginSessionTransfer.lastResult
        .takeIf { PluginSessionTransfer.lastResultKind == kind }
        .orEmpty()

    FclSettingSection("操作")

    FclSettingRow(
        label = "刷新列表",
        description = refreshDesc,
        shape = RowShape.TOP,
        onClick = if (busy) null else onRefresh
    ) {
        Text(
            text = if (busy) "…" else "刷新",
            color = if (busy) Fcl.TextTertiary else Fcl.ThemeText,
            fontSize = 12.sp
        )
    }

    FclSettingRow(
        label = "导出全部",
        description = exportDesc,
        shape = RowShape.MIDDLE,
        onClick = if (busy) null else onExport
    ) {
        Text(
            text = if (busy) "进行中" else "导出",
            color = if (busy) Fcl.TextTertiary else Fcl.ThemeText,
            fontSize = 12.sp
        )
    }

    FclSettingRow(
        label = "导入",
        description = importDesc,
        // 有结果行时本行要接上，圆角才不会在中间断开
        shape = if (result.isBlank()) RowShape.BOTTOM else RowShape.MIDDLE,
        onClick = if (busy) null else onImport
    ) {
        Text(
            text = if (busy) "进行中" else "导入",
            color = if (busy) Fcl.TextTertiary else Fcl.ThemeText,
            fontSize = 12.sp
        )
    }

    if (result.isNotBlank()) {
        FclSettingRow(
            label = result,
            shape = RowShape.BOTTOM
        ) {
            Text(
                text = if (result.startsWith("❌")) "失败" else "完成",
                color = if (result.startsWith("❌")) Fcl.Danger else Fcl.Success,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * 扫描已安装插件。
 *
 * ## ★ 判据换成了 profile 清单，不再列 node_modules（这是真实 bug）★
 *
 * 旧实现对每个 profile 的 `node_modules` 无差别 listFiles，于是
 * **插件本身和它的第三方依赖被一起当成插件列出**。用户装了 2 个插件，
 * 页面上却出现 6 项：
 *
 * | 显示项 | 真实身份 |
 * |---|---|
 * | `dsh-whale-widget` | 插件 ✓ |
 * | `dsh-meme` | 插件 ✓ |
 * | `sharp` / `@img/colour` / `detect-libc` / `semver` | dsh-meme 的**依赖** ✗ |
 *
 * 它们不是插件，也永远不会出现在 dsh 的插件列表里 —— 用户看着一堆
 * 自己没装过的名字，只会以为装错了东西。
 *
 * ## 现在的主判据
 *
 * `profiles/<profile>/package.json` 的 **`dsh.profile.bundles`** ——
 * 这正是引擎启动时实际加载的 bundle 列表（dsh 自己的插件管理器也读这一项，
 * 参考项目的 `PluginKillSwitch` / `PluginRecovery` 同样以它为准）。
 * 里面列了什么，引擎就加载什么；没列的一律不是插件，哪怕是插件目录里
 * 存在的包。
 *
 * 找不到清单（老版本/未初始化）才退回扫目录，并且只收名字像插件的包
 * （见 [looksLikePlugin]），不再把传递依赖混进来。
 *
 * @param engineModules 引擎自己的 `node_modules`，用来识别「内置」bundle；
 *   传 null 时不影响第三方插件的识别。
 * @param marketTitles 市场索引缓存下来的「包名 → 显示名」。插件包自己的
 *   package.json 没有显示名字段，所以取不到名字时靠这份映射兜底，
 *   避免列表里全是 `dsh-whale-widget` 这种用户认不出的机器名。
 */
private fun scanPlugins(
    dshHome: File,
    engineModules: File?,
    marketTitles: Map<String, String>
): PluginScan {
    val searched = ArrayList<String>(8)
    val found = LinkedHashMap<String, PluginInfo>(16)

    val profiles = File(dshHome, "profiles")
    searched.add(profiles.absolutePath)
    val profileDirs = runCatching { profiles.listFiles() }
        .getOrNull()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        .orEmpty()

    // ① 主路径：按每个 profile 的 bundles 清单逐个定位包目录
    for (p in profileDirs) {
        val manifest = File(p, "package.json")
        if (!manifest.isFile) continue
        searched.add(manifest.absolutePath)
        val bundles = readBundles(manifest)
        if (bundles.isEmpty()) continue
        val nm = File(p, "node_modules")
        if (nm.isDirectory) searched.add(nm.absolutePath)
        for (name in bundles) {
            if (found.containsKey(name)) continue
            // 解析顺序与 Node 一致：profile 自己的 node_modules → profiles 顶层
            // → 引擎目录。三处都没有，说明清单里列了但实际没装（残留条目）。
            val dir = File(nm, name).takeIf { it.isDirectory }
                ?: File(profiles, "node_modules/$name").takeIf { it.isDirectory }
                ?: engineModules?.let { File(it, name) }?.takeIf { it.isDirectory }
                ?: continue
            found[name] = PluginInfo(
                name = name,
                version = readPackageVersion(dir),
                dir = dir,
                where = "profiles/${p.name}",
                builtin = isBuiltinBundle(name),
                // 先读包自己的清单；那里没有显示名（插件普遍如此）时
                // 回落到市场索引缓存下来的名字。
                title = readPluginTitle(dir).ifBlank { marketTitles[name].orEmpty() }
            )
        }
    }

    // ② 兜底：没有可用清单时扫目录，但只收名字像插件的包
    if (found.isEmpty()) {
        fun acceptFrom(nodeModules: File, label: String) {
            if (!nodeModules.isDirectory) return
            searched.add(nodeModules.absolutePath)
            val kids = runCatching { nodeModules.listFiles() }.getOrNull() ?: return
            for (k in kids) {
                if (k.name.startsWith(".")) continue
                if (!k.isDirectory) continue
                if (k.name.startsWith("@")) {
                    // scope 包：磁盘上是两层目录，真正的包在下一层
                    val scoped = runCatching { k.listFiles() }.getOrNull() ?: continue
                    for (s in scoped) {
                        if (!s.isDirectory) continue
                        val name = "${k.name}/${s.name}"
                        if (found.containsKey(name)) continue
                        if (!looksLikePlugin(s, name)) continue
                        found[name] = PluginInfo(
                            name, readPackageVersion(s), s, label,
                            isBuiltinBundle(name),
                            readPluginTitle(s).ifBlank { marketTitles[name].orEmpty() }
                        )
                    }
                } else {
                    if (!looksLikePlugin(k, k.name)) continue
                    if (found.containsKey(k.name)) continue
                    found[k.name] = PluginInfo(
                        k.name, readPackageVersion(k), k, label, isBuiltinBundle(k.name),
                        readPluginTitle(k).ifBlank { marketTitles[k.name].orEmpty() }
                    )
                }
            }
        }

        for (p in profileDirs) acceptFrom(File(p, "node_modules"), "profiles/${p.name}")
        acceptFrom(File(profiles, "node_modules"), "profiles")
        val plugins = File(dshHome, "plugins")
        searched.add(plugins.absolutePath)
        if (plugins.isDirectory) acceptFrom(plugins, "plugins")
    }

    val items = found.values.sortedWith(
        compareBy({ it.builtin }, { it.name.lowercase(Locale.ROOT) })
    )
    return PluginScan(items, searched)
}

/**
 * 读 profile 清单里的 `dsh.profile.bundles`。
 *
 * 这是引擎实际加载的 bundle 列表，也是判断「哪些是插件」的唯一可靠来源。
 * 解析失败（带注释、BOM、文件半写）一律返回空表，由调用方转兜底路径。
 */
internal fun readBundles(manifest: File): List<String> = runCatching {
    val text = manifest.readText().removePrefix("\uFEFF")
    val arr = org.json.JSONObject(text)
        .optJSONObject("dsh")
        ?.optJSONObject("profile")
        ?.optJSONArray("bundles")
        ?: return@runCatching emptyList()
    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
}.getOrDefault(emptyList())

/**
 * 引擎自带、用户没装过的 bundle。它们不算「插件」，界面上单列一栏。
 *
 * `internal` 而非 `private`：[PluginSessionTransfer] 导出插件时要跳过它们。
 */
internal fun isBuiltinBundle(name: String): Boolean =
    name.startsWith("@deepseek-ai/") ||
        name == "dsh-web-app" ||
        name == "dsh-app-boot"

/**
 * 兜底扫描时的「像插件」判据。
 *
 * 依赖包（`semver`、`sharp`、`@img/colour`、`detect-libc`…）都不带 dsh 标记，
 * 靠这一条就能把它们挡在外面。真插件要么包名带 dsh，要么 package.json 里
 * 带 `"dsh"` 字段。
 */
private fun looksLikePlugin(dir: File, name: String): Boolean {
    if (name.contains("dsh", ignoreCase = true)) return true
    val text = runCatching { File(dir, "package.json").readText() }.getOrNull() ?: return false
    return text.contains("\"dsh\"")
}

/** 从包的 `package.json` 读 version；失败返回空串（列表里就不显示版本）。 */
internal fun readPackageVersion(dir: File): String = runCatching {
    val pkg = File(dir, "package.json")
    if (!pkg.isFile) return@runCatching ""
    // 只取 version 字段，不整体解析 —— package.json 常带注释/BOM/非法 JSON，
    // 用正则取一个字段比 JSONObject 容错得多。
    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
        .find(pkg.readText())?.groupValues?.get(1).orEmpty()
}.getOrDefault("")

/**
 * 从包清单里取插件的**显示名**（中文优先）。
 *
 * ## 为什么不能直接显示包名
 *
 * 包里声明的名字是 `dsh-whale-widget` 这类 npm 机器名，用户认不出是什么插件；
 * 插件在 dsh 自己的管理器与市场里显示的名字是包内清单给的。市场索引
 * （`plugin-market.indexes`）里每个条目的形状就是 `name`（包名）+
 * `title: { en, zh }`（显示名），插件包内的字段与之同源。
 *
 * ## 候选顺序（都按「拿不到就试下一个」）
 *
 * 1. `title` —— 字符串，或 `{ zh, en }` 双语对象；
 * 2. `dsh.title` / `dsh.displayName` —— 挂在 dsh 命名空间下的写法；
 * 3. `cordis.title` / `cordis.displayName` —— 插件是 cordis 插件，另一种常见位置；
 * 4. 顶层 `displayName`；
 * 5. 包内的 `cordis.yml` 里的 `title:` / `displayName:` 行。
 *
 * 全部取不到返回空串，界面退回显示包名 —— 绝不猜、也不拿描述当名字。
 *
 * @param dir 插件包目录
 */
private fun readPluginTitle(dir: File): String {
    val text = runCatching {
        File(dir, "package.json").readText().removePrefix("\uFEFF")
    }.getOrNull()
    if (text != null) {
        val fromJson = runCatching {
            val o = org.json.JSONObject(text)
            pickTitle(o.opt("title"))?.let { return@runCatching it }
            o.optJSONObject("dsh")?.let { d ->
                pickTitle(d.opt("title"))?.let { return@runCatching it }
                pickTitle(d.opt("displayName"))?.let { return@runCatching it }
            }
            o.optJSONObject("cordis")?.let { c ->
                pickTitle(c.opt("title"))?.let { return@runCatching it }
                pickTitle(c.opt("displayName"))?.let { return@runCatching it }
            }
            pickTitle(o.opt("displayName"))
        }.getOrNull()
        if (!fromJson.isNullOrBlank()) return fromJson
    }

    // 包内 cordis.yml 的 title: / displayName:
    for (name in listOf("cordis.yml", "cordis.yaml")) {
        val f = File(dir, name)
        if (!f.isFile) continue
        val hit = runCatching {
            Regex("(?m)^\\s*(?:title|displayName)\\s*:\\s*[\"']?([^\"'\\n#]+)")
                .find(f.readText())?.groupValues?.get(1)?.trim()
        }.getOrNull()
        if (!hit.isNullOrBlank()) return hit
    }
    return ""
}

/**
 * 从 `title` 字段的两种形状里挑出给人看的字符串。
 *
 * 双语对象优先 `zh`，没有中文就用 `en`；都没有返回 null。
 */
private fun pickTitle(v: Any?): String? = when (v) {
    null -> null
    is org.json.JSONObject -> v.optString("zh").ifBlank { v.optString("en") }
        .ifBlank { v.optString("name") }.ifBlank { null }
    is String -> v.ifBlank { null }
    else -> null
}

// ----------------------------------------------------------------------
// 会话管理
// ----------------------------------------------------------------------

/** 单个会话在列表里最多显示多少条（会话可能上千个，全列出来会卡）。 */
private const val SESSION_SHOW_LIMIT = 50

/**
 * 一个会话。
 *
 * @param id        会话标识：有 uuid 用 uuid，否则用所在目录名
 * @param fileCount 属于它的文件数（dsh 一个会话常有 metadata + 内容两个文件）
 * @param size      这些文件的合计体积
 * @param last      最近活跃时间（优先取引擎记录的 lastPromptAt，退回文件 mtime）
 * @param title     **dsh 给这个会话总结的名字**。之前完全不读它，列表里只有
 *   `2a1059c6` 这种 uuid 尾号，用户根本认不出哪个会话是哪个。
 */
private data class SessionInfo(
    val id: String,
    val fileCount: Int,
    val size: Long,
    val last: Long,
    val title: String = ""
)

/**
 * 会话投影缓存里的一条元信息。
 *
 * dsh 把会话状态写在 `$DSH_HOME/storages/session_projcache/sessions/<id>.json`，
 * 是**明文 JSON**（不像 `session.v4.jsonl.zstd` 要先解压），体积几 KB，
 * 而且随活动实时更新 —— 标题与活跃时间都在里面。
 *
 * @param title        引擎算好的标题（没有时退回首条用户消息）
 * @param lastPromptAt 最后一次提问时间；为 0 表示这条缓存里没记
 */
private data class ProjMeta(
    val title: String = "",
    val lastPromptAt: Long = 0L
)

/** 会话扫描结果：列表 + 实际扫描位置（扫描位置要显示出来，便于核对）。 */
private data class SessionScan(
    val items: List<SessionInfo>,
    val root: String
)

/**
 * 会话管理：列出已有的会话。
 *
 * ## ★ 之前为什么会「莫名其妙一大堆会话」（真实 bug）★
 *
 * 旧实现是「文件名包含 session 就收」：
 *
 * ```kotlin
 * } else if (f.name.contains("session", ignoreCase = true)) {
 * ```
 *
 * 于是这些都被当成了会话：
 *
 * | 实际文件 | 旧实现当成 |
 * |---|---|
 * | `session.lock.dsh-lock` | 一个会话 ❌ 这是**锁文件** |
 * | `session.v4.jsonl.zstd.<hash>.tmp` | 一个会话 ❌ 这是**临时文件** |
 * | `session.v4.jsonl.zstd` + `session-<uuid>.json` | **两个**会话 ❌ 其实是同一个 |
 *
 * 用户只聊过几次却看到 16 个，就是这个原因：既收了非会话文件，
 * 又把一个会话的多个文件各算一条。
 *
 * ## 现在的规则
 *
 * 1. **排除**锁/临时/半成品：`.lock`、`.dsh-lock`、`.tmp`、`.part`、`.migrating`；
 * 2. **按会话聚合**而不是按文件：同一会话的 metadata 与内容文件算一条；
 *    - 文件在 `sessions/<id>/` 这类子目录里 → 用**目录**当身份；
 *    - 平铺在 `sessions/` 下 → 用文件名里的 **uuid** 当身份；
 * 3. 体积与时间按聚合后计算，不再逐个文件相加。
 */
@Composable
private fun SessionTab(
    dshHome: File,
    tick: Int,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onExportSession: (String) -> Unit
) {
    val context = LocalContext.current
    val scan = remember(tick) { scanSessions(dshHome) }
    val sessions = scan.items
    val totalSize = remember(tick) { sessions.sumOf { it.size } }

    // 订阅忙碌状态：导出进行中时行内的「导出」按钮要真的点不动
    val busy = PluginSessionTransfer.busy

    // 待确认删除的会话。会话删了找不回来，必须二次确认。
    var pendingDelete by remember { mutableStateOf<SessionInfo?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    SplitPane(
        left = {
            // ── 能操作的：全部集中在左栏顶部 ──
            TransferActions(
                kind = PluginSessionTransfer.Kind.SESSIONS,
                refreshDesc = "重新扫描数据目录。",
                exportDesc = "全部会话打成 zip（含标题缓存）。",
                importDesc = "写入数据目录，已存在的会话跳过、不覆盖。",
                onRefresh = onRefresh,
                onExport = onExport,
                onImport = onImport
            )

            // ── 以下只读、不能操作，按要求放在左栏下方 ──
            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("统计")
            FclSettingRow(
                label = "共 ${sessions.size} 个会话",
                description = "合计 ${humanSize(totalSize)}。可在右侧逐项删除。",
                shape = RowShape.SINGLE
            ) {
                Text(text = "只读", color = Fcl.TextTertiary, fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("扫描位置")
            val rootExists = remember(scan.root) { File(scan.root).isDirectory }
            FclSettingRow(
                label = if (rootExists) "已存在" else "不存在",
                description = scan.root,
                shape = RowShape.SINGLE
            ) {
                Text(
                    text = if (rootExists) "✓" else "—",
                    color = if (rootExists) Fcl.Success else Fcl.TextTertiary,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
        },
        right = {
            FclSettingSection("会话（${sessions.size}）")

            if (sessions.isEmpty()) {
                EmptyState(
                    title = "还没有会话记录",
                    detail = "在 dsh 里聊过一次之后，会话会落在这个数据目录下。"
                )
            } else {
                // ★ 这里以前是 `if (sessions.size > SESSION_SHOW_LIMIT)` 才列明细 ★
                //
                // 上限是 8，于是**会话不超过 8 个时一行明细都不渲染**，界面上只剩
                // 一句「共 N 个会话」—— 用户看到的就是「只显示数量、看不到每个会话」。
                // 现在无条件列出（截断到上限，超出时补一行说明），并且用标题而不是 uuid。
                //
                // 「共 N 个会话」那行已移到左栏的「统计」里，所以这里的圆角
                // 直接按列表本身的行数算，不再额外 +1。
                val shown = sessions.take(SESSION_SHOW_LIMIT)
                val moreCount = sessions.size - shown.size
                val totalRows = shown.size + if (moreCount > 0) 1 else 0

                shown.forEachIndexed { i, s ->
                    val named = s.title.isNotBlank()
                    FclSettingRow(
                        // 标题是第一眼要看到的东西，放在 label；uuid 退到描述里备用
                        label = if (named) s.title else s.id,
                        description = (if (named) "${s.id} · " else "") +
                            timeText(s.last) + " · " + humanSize(s.size) +
                            if (s.fileCount > 1) " · ${s.fileCount} 个文件" else "",
                        shape = RowShape.of(i, totalRows)
                    ) {
                        Text(
                            text = if (named) "已总结" else "未命名",
                            color = if (named) Fcl.Success else Fcl.TextTertiary,
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        // 单项导出：只打这一个会话（本体 + 标题缓存），
                        // 适合单独分享某段对话，不用把全部会话搬出去。
                        RowAction(
                            text = "导出",
                            enabled = !busy,
                            onClick = { onExportSession(s.id) }
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        // 删除：本体文件 + 标题缓存一起删。先弹确认窗。
                        RowAction(
                            text = "删除",
                            enabled = !busy,
                            danger = true,
                            onClick = { pendingDelete = s }
                        )
                    }
                }

                if (moreCount > 0) {
                    FclSettingRow(
                        label = "还有 $moreCount 个会话未显示",
                        description = "只列最近 $SESSION_SHOW_LIMIT 个，长列表留给文件管理器。",
                        shape = RowShape.of(totalRows - 1, totalRows)
                    ) {
                        Text(text = "…", color = Fcl.TextTertiary, fontSize = 10.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    )

    // 删除确认窗。放在 SplitPane 之后：覆盖层不参与分栏布局。
    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除会话「${target.title.ifBlank { target.id }}」",
            detail = "将删除这个会话的本体文件与标题缓存（${target.fileCount} 个文件，" +
                "${humanSize(target.size)}）。重启实例后彻底消失。" +
                "此操作不可撤销，建议先「导出」备份。",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val key = target.id
                pendingDelete = null
                Thread {
                    PluginSessionTransfer.deleteSession(context, key)
                    main.post { onRefresh() }
                }.start()
            }
        )
    }
}

// ----------------------------------------------------------------------
// skill 管理
// ----------------------------------------------------------------------

/**
 * 一个已安装的 skill。
 *
 * @param id   目录名（skill 的身份）
 * @param path 相对 `DSH_HOME` 的位置，便于用户核对
 * @param dir  实际目录，删除时用
 */
internal data class SkillInfo(
    val id: String,
    val path: String,
    val dir: File,
    val fileCount: Int,
    val size: Long,
    /** 从 SKILL.md / README.md 里读到的标题；读不到为空串。 */
    val title: String
)

/**
 * skill 扫描结果：列表 + 实际扫过的位置（扫描位置要显示出来便于核对）。
 *
 * `internal` 而非 `private`：「skill 下载」页也要列已装 skill，
 * 两处共用同一个扫描函数，避免「设置页能看到、下载页看不到」这种分叉。
 */
internal data class SkillScan(
    val items: List<SkillInfo>,
    val searched: List<String>
)

/**
 * skill 管理：扫描并删除已安装的 skill。
 *
 * ## 为什么扫多条候选路径
 *
 * dsh 的 skill 存放位置随版本/插件而变（有的版本在 `$DSH_HOME/skills`，
 * 有的在 profile 下，`@michengai/dsh-skills-manager` 这类插件还会自己建目录）。
 * 与其赌一条路径，不如把几条候选全扫一遍，并在界面上把**实际扫过的位置**
 * 摊开 —— 万一一个都没扫到，用户能立刻看出该补哪条路径，而不是干瞪眼。
 *
 * ## skill 的身份是「目录」
 *
 * 一个 skill 就是一个目录（里面通常有 SKILL.md）。所以按目录认身份，
 * 不按文件 —— 这与会话完全不同（会话是「一堆文件算一条」）。
 */
@Composable
private fun SkillTab(
    dshHome: File,
    tick: Int,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onExportSkill: (String, String) -> Unit
) {
    val scan = remember(tick) { scanSkills(dshHome) }
    val skills = scan.items

    var pendingDelete by remember { mutableStateOf<SkillInfo?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    // 删除进行中：防止连点两次同时删同一个目录
    var deleting by remember { mutableStateOf(false) }

    SplitPane(
        left = {
            // 刷新 / 导出全部 / 导入 —— 与插件页、会话页同一套组件，
            // 靠 kind 区分操作结果，避免「导出 skill 后切到插件页看到这条结果」。
            TransferActions(
                kind = PluginSessionTransfer.Kind.SKILLS,
                refreshDesc = "重新扫描磁盘，装完 skill 后点这里。",
                exportDesc = "全部 skill 打成 zip，保留原始目录结构。",
                importDesc = "解包写回原位置，已存在的文件跳过。",
                onRefresh = onRefresh,
                onExport = onExport,
                onImport = onImport
            )

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("扫描位置")
            if (scan.searched.isEmpty()) {
                EmptyState(title = "没有可扫描的目录", detail = "数据目录尚未创建。")
            } else {
                scan.searched.forEachIndexed { i, path ->
                    val exists = remember(path) { File(path).isDirectory }
                    FclSettingRow(
                        label = if (exists) "已存在" else "不存在",
                        description = path,
                        shape = RowShape.of(i, scan.searched.size)
                    ) {
                        Text(
                            text = if (exists) "✓" else "—",
                            color = if (exists) Fcl.Success else Fcl.TextTertiary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("说明")
            FclSettingRow(
                label = "什么是 skill",
                description = "skill 是给 Agent 用的可复用指令包，一个目录一个 skill，" +
                    "通常带 SKILL.md。删除后需要重新安装。",
                shape = RowShape.SINGLE
            ) {
                Text(text = "只读", color = Fcl.TextTertiary, fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))
        },
        right = {
            FclSettingSection("已安装 skill（${skills.size}）")

            if (skills.isEmpty()) {
                EmptyState(
                    title = "没有找到 skill",
                    detail = "在左侧列出的位置都没扫到 skill 目录。" +
                        "若确认已安装，请把真实路径告知开发者以补上扫描位置。"
                )
            } else {
                skills.forEachIndexed { i, s ->
                    FclSettingRow(
                        label = s.title.ifBlank { s.id },
                        description = buildString {
                            append(s.path)
                            append(" · ${s.fileCount} 个文件")
                            append(" · ${humanSize(s.size)}")
                        },
                        shape = RowShape.SINGLE
                    ) {
                        // 单项导出：只打这一个 skill 目录。
                        // label 是它所在的那条根目录（`skills` / `profiles/x/skills`），
                        // 同名 skill 装在多处时靠它精确找回，不会打错那个。
                        RowAction(
                            text = "导出",
                            enabled = !PluginSessionTransfer.busy,
                            onClick = {
                                onExportSkill(s.id, s.path.substringBeforeLast('/'))
                            }
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        RowAction(
                            text = "删除",
                            enabled = !deleting,
                            danger = true,
                            onClick = { pendingDelete = s }
                        )
                    }
                    if (i < skills.size - 1) Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    )

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除 skill「${target.title.ifBlank { target.id }}」",
            detail = "将删除目录 ${target.path}（${target.fileCount} 个文件，" +
                "${humanSize(target.size)}）。此操作不可撤销。",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val dir = target.dir
                pendingDelete = null
                deleting = true
                Thread {
                    val ok = deleteSkillDir(dir)
                    main.post {
                        deleting = false
                        onRefresh()
                    }
                    if (!ok) DSHRunner.onLog("❌ 删除 skill 失败: ${dir.absolutePath}")
                }.start()
            }
        )
    }
}

/**
 * 删除一个 skill 目录。
 *
 * ## 软链只删链接本身
 *
 * 与插件删除同一条铁律：对指向别处的软链做 `deleteRecursively()` 会顺着链接
 * 把外部目录一起删掉，且不可恢复。所以先用 [com.isLink] 判断，
 * 链接只 `delete()`，真实目录才递归删。
 */
private fun deleteSkillDir(dir: File): Boolean = runCatching {
    if (com.isLink(dir)) dir.delete() else dir.deleteRecursively()
}.getOrDefault(false)

/**
 * 扫描 skill。
 *
 * 候选路径按「最可能」排列，**全部扫一遍而不是命中即停** ——
 * skill 可能同时装在全局与 profile 两处。
 */
internal fun scanSkills(dshHome: File): SkillScan {
    val searched = ArrayList<String>(8)
    val found = LinkedHashMap<String, SkillInfo>(16)

    // canonicalPath 去重：同一目录可能被多条候选路径命中
    val seen = HashSet<String>(16)

    // ⚠️ 局部函数必须先声明后使用（Kotlin 的硬约束）
    fun acceptBase(base: File, label: String) {
        if (!base.isDirectory) return
        searched.add(base.absolutePath)
        val kids = runCatching { base.listFiles() }.getOrNull() ?: return
        for (d in kids) {
            if (!d.isDirectory) continue
            // 跳过隐藏目录与明显的元数据目录
            if (d.name.startsWith(".")) continue
            if (d.name == "node_modules") continue
            val key = runCatching { d.canonicalPath }.getOrDefault(d.absolutePath)
            if (!seen.add(key)) continue

            var files = 0
            var size = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(d)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                val children = runCatching { cur.listFiles() }.getOrNull() ?: continue
                for (f in children) {
                    if (com.isLink(f)) continue
                    if (f.isDirectory) {
                        stack.addLast(f)
                    } else {
                        files++
                        size += runCatching { f.length() }.getOrDefault(0L)
                    }
                }
            }

            found[d.name] = SkillInfo(
                id = d.name,
                path = "$label/${d.name}",
                dir = d,
                fileCount = files,
                size = size,
                title = readSkillTitle(d)
            )
        }
    }

    // ① 数据目录顶层（多数版本把 skill 放在这里）
    acceptBase(File(dshHome, "skills"), "skills")

    // ② 各 profile 下（插件管理器装的 skill 常落在这里）
    val profiles = File(dshHome, "profiles")
    val profileDirs = runCatching { profiles.listFiles() }
        .getOrNull()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        .orEmpty()
    for (p in profileDirs) {
        acceptBase(File(p, "skills"), "profiles/${p.name}/skills")
    }

    // ③ Claude 风格的 `.claude/skills`（部分 skill 直接复用生态格式）
    acceptBase(File(dshHome, ".claude/skills"), ".claude/skills")

    val items = found.values.sortedBy { it.id.lowercase(Locale.ROOT) }
    return SkillScan(items, searched)
}

/**
 * 从 skill 目录里读显示名。
 *
 * 优先取 `SKILL.md` / `skill.md` 的第一个 `# 标题`，
 * 其次 `README.md` 的同名字段。都读不到返回空串，界面回落到目录名。
 */
private fun readSkillTitle(dir: File): String {
    for (name in listOf("SKILL.md", "skill.md", "README.md", "readme.md")) {
        val f = File(dir, name)
        if (!f.isFile) continue
        val text = runCatching { f.readText() }.getOrNull() ?: continue
        // 只看前 40 行：标题一定在前面，读整个文件没必要
        val head = text.lineSequence().take(40)
        for (line in head) {
            val t = line.trim()
            if (t.startsWith("# ")) return t.removePrefix("# ").trim()
            // YAML front matter 里的 name: 也认
            if (t.startsWith("name:")) {
                val v = t.removePrefix("name:").trim().trim('"', '\'')
                if (v.isNotEmpty()) return v
            }
        }
    }
    return ""
}

/**
 * 判断一个文件是否**不是**会话数据（锁文件、临时文件、半成品）。
 *
 * 这几类都在 `sessions/` 下真实存在，是旧实现把会话数虚高的直接原因。
 */
internal fun isNotSessionPayload(name: String): Boolean {
    val n = name.lowercase(Locale.ROOT)
    return n.contains(".lock") ||          // session.lock.dsh-lock
        n.contains(".dsh-lock") ||
        n.endsWith(".tmp") ||              // 原子替换的中间态
        n.endsWith(".part") ||
        n.endsWith(".migrating")
}

/** 从文件名里抽 uuid（会话 metadata 常见命名：`session-<uuid>.json`）。 */
internal val SESSION_UUID_RE =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * 一个文件属于哪个会话 —— **导出单个会话时必须与列表用同一套判据**。
 *
 * 若这里和 [scanSessions] 各写一份，就会出现「列表里能选、导出却说找不到」：
 * 两处对同一个会话算出了不同的 key。所以抽成一个函数，双方都调它。
 *
 * 判定顺序：
 *  · 在 `sessions/<id>/` 这类子目录里 → 用目录名（一个会话一个目录）；
 *  · 平铺在 `sessions/` 下 → 用文件名里的 uuid；
 *  · 两者都没有 → 退回文件名去扩展名。
 *
 * @param scanFrom 扫描根（`sessions/`，不存在时是数据根）
 */
internal fun sessionKeyOf(f: File, scanFrom: File): String {
    val parent = f.parentFile
    return when {
        parent != null && parent.absolutePath != scanFrom.absolutePath -> parent.name
        else -> SESSION_UUID_RE.find(f.name)?.value ?: f.name.substringBeforeLast('.')
    }
}

/**
 * 扫描会话。
 *
 * 优先只看 `sessions/`（dsh 的规范位置）。该目录不存在时才从数据根递归找 ——
 * 递归是兜底，不是主路径：范围越大越容易把别的文件误收进来。
 */
private fun scanSessions(root: File): SessionScan {
    val sessionsRoot = File(root, "sessions")
    val scanFrom = if (sessionsRoot.isDirectory) sessionsRoot else root

    // 按「会话身份」聚合。key → (fileCount, size, last)
    val groups = LinkedHashMap<String, Triple<Int, Long, Long>>(16)
    var files = 0

    fun accept(f: File) {
        if (files >= 400) return
        if (isNotSessionPayload(f.name)) return
        if (!f.name.contains("session", ignoreCase = true)) return
        files++

        // 身份判定统一走 sessionKeyOf：导出单个会话时用的是同一个函数，
        // 两处若各写一份，会出现「列表里能选、导出却说找不到」。
        val key: String = sessionKeyOf(f, scanFrom)

        val size = runCatching { f.length() }.getOrDefault(0L)
        val time = runCatching { f.lastModified() }.getOrDefault(0L)
        val old = groups[key]
        groups[key] = if (old == null) {
            Triple(1, size, time)
        } else {
            Triple(old.first + 1, old.second + size, maxOf(old.third, time))
        }
    }

    fun walk(dir: File, depth: Int) {
        if (depth > 3) return
        val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in kids) {
            // 全限定名：isLink 定义在 `com` 包（AttachmentsStorage.kt），
            // 本文件在 `com.example.fcldsh`，裸名解析不到。
            // 它用 Os.lstat + S_ISLNK 判断「这一项本身是不是链接」，
            // 不能用 canonicalPath 比较（/data/user/0 与 /data/data 是同一目录）。
            if (com.isLink(f)) continue
            if (f.isDirectory) walk(f, depth + 1) else accept(f)
        }
    }

    if (scanFrom.isDirectory) walk(scanFrom, 0)

    // 标题与活跃时间从投影缓存补上。
    //
    // ★ 为什么必须走这份缓存，而不是读 session.v4.jsonl.zstd ★
    //   · 那个文件是 zstd 压缩的，Kotlin 侧要解压得多引一个依赖；
    //   · 更关键的是它**只在建会话时写一次**，mtime 会永久停在建会话那一刻，
    //     拿它当「最近活跃」是错的。
    //   投影缓存是明文 JSON、随活动更新，标题（引擎自己总结的名字）也在里面。
    val metas = readProjCache(root)

    fun metaOf(id: String): ProjMeta? {
        val norm = id.lowercase(Locale.ROOT)
        metas[norm.removePrefix("session-")]?.let { return it }
        // 聚合用的 key 有时是目录名、有时是裸 uuid，两边都得能对上
        SESSION_UUID_RE.find(norm)?.let { m -> metas[m.value]?.let { return it } }
        return null
    }

    val items = groups.entries
        .map { (key, v) ->
            val meta = metaOf(key)
            SessionInfo(
                id = key,
                fileCount = v.first,
                size = v.second,
                // 有 lastPromptAt 就用它（比文件 mtime 准），否则退回 mtime
                last = meta?.lastPromptAt?.takeIf { it > 0L } ?: v.third,
                title = meta?.title.orEmpty()
            )
        }
        .sortedByDescending { it.last }
    return SessionScan(items, scanFrom.absolutePath)
}

/**
 * 读会话投影缓存：`$DSH_HOME/storages/session_projcache/sessions/<id>.json`。
 *
 * 返回 `去掉 session- 前缀的小写 id → 元信息`。目录不存在时返回空表，
 * 列表会退回显示 uuid 尾号（不报错、不影响其它内容）。
 */
private fun readProjCache(root: File): Map<String, ProjMeta> {
    val dir = File(root, "storages/session_projcache/sessions")
    if (!dir.isDirectory) return emptyMap()
    val files = runCatching { dir.listFiles() }.getOrNull() ?: return emptyMap()
    val out = HashMap<String, ProjMeta>(files.size.coerceAtLeast(8))
    for (f in files) {
        if (!f.isFile || !f.name.endsWith(".json")) continue
        val id = f.name.removeSuffix(".json")
            .removePrefix("session-")
            .lowercase(Locale.ROOT)
        out[id] = readProjMeta(f)
    }
    return out
}

/**
 * 解析单条投影缓存。
 *
 * 数据形状：`record.rows.<字段>.val`，其中 `title` 的 val 可能是字符串，
 * `titleInput` / `sessionListMetadata` 的 val 是对象 —— 两种都按「拿不到就用空」处理，
 * 因为这份格式没有版本承诺，缺字段不该让整页崩掉。
 */
private fun readProjMeta(f: File): ProjMeta = runCatching {
    val text = f.readText().removePrefix("\uFEFF")
    val rows = org.json.JSONObject(text)
        .optJSONObject("record")
        ?.optJSONObject("rows")
        ?: return@runCatching ProjMeta()

    fun objVal(key: String): org.json.JSONObject? =
        rows.optJSONObject(key)?.optJSONObject("val")

    val rawTitle = rows.optJSONObject("title")?.opt("val")?.toString().orEmpty()
    val title = if (rawTitle.isBlank() || rawTitle == "null") "" else rawTitle
    // 引擎还没总结出标题时，用首条用户消息兜底 —— 也比 uuid 强
    val firstPrompt = objVal("titleInput")?.optString("first").orEmpty()

    ProjMeta(
        title = title.ifBlank { firstPrompt },
        lastPromptAt = objVal("sessionListMetadata")?.optLong("lastPromptAt", 0L) ?: 0L
    )
}.getOrDefault(ProjMeta())

// ----------------------------------------------------------------------
// mcp 管理
// ----------------------------------------------------------------------

/**
 * mcp 管理：读 `DSH_HOME` 下的 MCP 配置文件，列出里面登记的服务。
 *
 * ## 这一版修的三个「看起来能用、实际不能用」的问题
 *
 * 1. **`.jsonc` 永远解析失败** —— `org.json` 不认注释，而 dsh 的不少配置
 *    是带注释的 JSONC。旧实现遇到注释就只报「文件存在」，
 *    等于该页对这类文件完全失效。现在先剥注释再解析。
 * 2. **只认 4 条硬编码路径** —— 实际存放位置随版本/插件而变。
 *    现在加上 profile 目录与有界搜索。
 * 3. **找不到时不给任何线索** —— 现在把**实际扫过的位置**摊开，
 *    扫不到时用户能立刻看出该补哪条路径，而不是干瞪眼。
 *
 * ## 支持的配置形状
 *
 * ```
 * { "mcpServers": { "名字": { "command": ... } } }
 * { "servers":    { "名字": { "url": ... } } }
 * { "servers":    [ { "name": "名字", ... } ] }
 * [ { "name": "名字", ... } ]
 * ```
 *
 * 四种都在真实生态里出现过。认不出时如实报「没有登记任何服务」，
 * 不硬猜内容。
 */
@Composable
private fun McpTab(
    dshHome: File,
    tick: Int,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onExportMcpConfig: (String) -> Unit
) {
    val context = LocalContext.current
    val scan = remember(tick) { scanMcpConfigs(dshHome) }
    val totalServers = remember(tick) { scan.configs.sumOf { it.servers.size } }

    // 待确认的删除目标。两种粒度：
    //  · 删单个服务（经典 JSON 里摘一条）
    //  · 删整份配置（bundle 就是卸载整个包）
    var pendingServer by remember { mutableStateOf<Pair<McpConfig, String>?>(null) }
    var pendingConfig by remember { mutableStateOf<McpConfig?>(null) }

    // 删盘是阻塞 IO，必须离开主线程；完成后回主线程刷新。
    var deleting by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }

    SplitPane(
        left = {
            // 刷新 / 导出全部 / 导入。bundle 形式的 MCP 导出时会把同目录的
            // mcp-server.mjs / launch-mcp.sh 一起打进去 —— 只打 yml 等于导空壳。
            TransferActions(
                kind = PluginSessionTransfer.Kind.MCP,
                refreshDesc = "重新读取 MCP 配置。",
                exportDesc = "全部 MCP 配置打成 zip，bundle 会连同脚本一起打包。",
                importDesc = "解包写回原路径，已存在的文件跳过。",
                onRefresh = onRefresh,
                onExport = onExport,
                onImport = onImport
            )

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("统计")
            FclSettingRow(
                label = "${scan.configs.size} 个配置文件",
                description = "共 $totalServers 个已登记的服务。" +
                    "可在右侧逐项删除；删除前会自动备份配置文件。",
                shape = RowShape.SINGLE
            ) {
                Text(text = "可删", color = Fcl.Success, fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("扫描位置")
            if (scan.searched.isEmpty()) {
                EmptyState(title = "没有可扫描的目录", detail = "数据目录尚未创建。")
            } else {
                scan.searched.forEachIndexed { i, path ->
                    val exists = remember(path) { File(path).isFile }
                    FclSettingRow(
                        label = if (exists) "已存在" else "不存在",
                        description = path,
                        shape = RowShape.of(i, scan.searched.size)
                    ) {
                        Text(
                            text = if (exists) "✓" else "—",
                            color = if (exists) Fcl.Success else Fcl.TextTertiary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        },
        right = {
            FclSettingSection("MCP 服务")

            if (scan.configs.isEmpty()) {
                EmptyState(
                    title = "还没有 MCP 配置",
                    detail = "左侧列出的位置都没找到 MCP 配置。装好后会出现在这里。"
                )
            } else {
                scan.configs.forEach { cfg ->
                    FclSettingRow(
                        label = cfg.rel.substringAfterLast('/'),
                        description = buildString {
                            append(cfg.rel)
                            if (cfg.parseError != null) {
                                append("\n⚠️ ${cfg.parseError}")
                            } else if (cfg.servers.isEmpty()) {
                                append("\n配置文件存在，但没有登记任何服务")
                            } else {
                                append("\n${cfg.servers.size} 个服务")
                            }
                        },
                        shape = RowShape.SINGLE
                    ) {
                        // bundle 形式标出来：用户一眼能分清「经典 mcp.json」
                        // 与「local-plugins 里的 MCP bundle」
                        if (cfg.bundle) {
                            Text(
                                text = "bundle",
                                color = Fcl.ThemeText,
                                fontSize = 10.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Text(
                                text = sizeText(cfg.file),
                                color = Fcl.TextTertiary,
                                fontSize = 10.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        // 单项导出：bundle 会把同目录脚本一起打进去
                        RowAction(
                            text = "导出",
                            enabled = !PluginSessionTransfer.busy && !deleting,
                            onClick = { onExportMcpConfig(cfg.rel) }
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        // 整份删掉：bundle 相当于「卸载这个 MCP 包」，
                        // 经典配置则是删掉整个 mcp.json
                        RowAction(
                            text = if (cfg.bundle) "卸载" else "删除",
                            enabled = !deleting,
                            danger = true,
                            onClick = { pendingConfig = cfg }
                        )
                    }

                    if (cfg.parseError == null && cfg.servers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        cfg.servers.forEachIndexed { i, s ->
                            FclSettingRow(
                                label = s.name,
                                description = s.detail.ifBlank { "已登记的服务" },
                                shape = RowShape.SINGLE
                            ) {
                                Text(text = "启用", color = Fcl.Success, fontSize = 10.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                // 删单个服务只对经典 JSON 有效：bundle 的配置
                                // 和它自己的脚本是一体的，摘一条没法真正卸载，
                                // 所以那种情况只给「卸载」。
                                if (!cfg.bundle) {
                                    RowAction(
                                        text = "删除",
                                        enabled = !deleting,
                                        danger = true,
                                        onClick = { pendingServer = cfg to s.name }
                                    )
                                }
                            }
                            if (i < cfg.servers.size - 1) {
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    )

    // ── 删单个服务 ──────────────────────────────────────────────────
    pendingServer?.let { (cfg, name) ->
        ConfirmDialog(
            title = "删除 MCP 服务「$name」",
            detail = "将从 ${cfg.rel} 里移除这一项。配置文件会先备份成 " +
                "${cfg.file.name}.bak-delete。\n\n" +
                "注意：写回时会丢掉原文件里的注释（JSON 不支持注释）。",
            onDismiss = { pendingServer = null },
            onConfirm = {
                pendingServer = null
                deleting = true
                Thread {
                    val ok = removeServerFromConfig(cfg, name)
                    main.post {
                        deleting = false
                        if (ok) {
                            DSHRunner.onLog("✅ 已删除 MCP 服务 $name（${cfg.rel}）")
                        } else {
                            DSHRunner.onLog("❌ 删除 MCP 服务失败: ${cfg.rel}")
                        }
                        onRefresh()
                    }
                }.start()
            }
        )
    }

    // ── 删整份配置 / 卸载 bundle ────────────────────────────────────
    pendingConfig?.let { cfg ->
        ConfirmDialog(
            title = if (cfg.bundle) {
                "卸载 MCP bundle「${cfg.file.parentFile?.name ?: cfg.rel}」"
            } else {
                "删除配置文件 ${cfg.rel.substringAfterLast('/')}"
            },
            detail = if (cfg.bundle) {
                "将删除整个包目录（cordis.patch.yml、mcp-server.mjs、launch-mcp.sh 等），" +
                    "并从 profile 加载清单里摘除。此操作不可撤销，建议先「导出」备份。"
            } else {
                "将删除 ${cfg.rel}。此操作不可撤销，建议先「导出」备份。"
            },
            onDismiss = { pendingConfig = null },
            onConfirm = {
                val target = cfg
                pendingConfig = null
                deleting = true
                Thread {
                    val ok = deleteMcp(context, target)
                    main.post {
                        deleting = false
                        if (ok) {
                            DSHRunner.onLog("✅ 已删除 MCP 配置 ${target.rel}")
                        } else {
                            DSHRunner.onLog("❌ 删除 MCP 配置失败: ${target.rel}")
                        }
                        onRefresh()
                    }
                }.start()
            }
        )
    }
}

/**
 * 从一个经典 JSON / JSONC 配置里删掉单个服务条目。
 *
 * ## 为什么写回前要备份、写回会丢注释
 *
 * `org.json` 只能输出标准 JSON，注释在规范化过程中必然消失。
 * 配置里若有用户手写的说明，这里保不住 —— 所以先存一份
 * `<文件名>.bak-delete`，同时在确认框里如实告知。
 *
 * @return 是否真的改动并写回
 */
private fun removeServerFromConfig(cfg: McpConfig, serverName: String): Boolean {
    val raw = runCatching { cfg.file.readText() }.getOrNull() ?: return false
    val text = stripJsonc(raw.removePrefix("\uFEFF"))

    // 备份：改坏了用户还能自己还原
    runCatching {
        File(cfg.file.parentFile, cfg.file.name + ".bak-delete").writeText(raw)
    }

    var touched = false

    // 形状一：顶层对象
    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
    if (obj != null) {
        // mcpServers 是对象
        obj.optJSONObject("mcpServers")?.let { m ->
            if (m.remove(serverName) != null) touched = true
        }
        // servers 是对象
        obj.optJSONObject("servers")?.let { m ->
            if (m.remove(serverName) != null) touched = true
        }
        // servers 是数组
        obj.optJSONArray("servers")?.let { arr ->
            val kept = org.json.JSONArray()
            var arrTouched = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) {
                    kept.put(arr.opt(i))
                    continue
                }
                val n = o.optString("name").ifBlank { o.optString("id") }
                if (n == serverName) {
                    arrTouched = true
                    continue
                }
                kept.put(o)
            }
            if (arrTouched) {
                obj.put("servers", kept)
                touched = true
            }
        }
        if (touched) {
            return runCatching {
                cfg.file.writeText(obj.toString(2) + "\n")
                true
            }.getOrDefault(false)
        }
    }

    // 形状二：顶层数组
    val arr = runCatching { org.json.JSONArray(text) }.getOrNull() ?: return false
    val kept = org.json.JSONArray()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i)
        if (o == null) {
            kept.put(arr.opt(i))
            continue
        }
        val n = o.optString("name").ifBlank { o.optString("id") }
        if (n == serverName) {
            touched = true
            continue
        }
        kept.put(o)
    }
    if (!touched) return false
    return runCatching {
        cfg.file.writeText(kept.toString(2) + "\n")
        true
    }.getOrDefault(false)
}

/**
 * 删除一整份 MCP 配置。
 *
 * ## 两种形态，两套做法
 *
 * · **bundle**（`local-plugins/<包>/cordis.patch.yml`）：配置和它的
 *   `mcp-server.mjs` / `launch-mcp.sh` 是一体的，只删 yml 会留下一个
 *   没人引用的目录，所以**整个包目录一起删**，并从 profile 清单里摘除。
 * · **经典配置**（`mcp.json`）：删掉整个文件即可。
 *
 * 软链只删链接本身 —— 对链接 `deleteRecursively()` 会顺着删掉外部目录，
 * 不可恢复（与插件删除同一条铁律）。
 */
private fun deleteMcp(context: Context, cfg: McpConfig): Boolean = runCatching {
    if (cfg.bundle) {
        val pkgDir = cfg.file.parentFile ?: return@runCatching false
        val pkgName = pkgDir.name
        val removed = if (com.isLink(pkgDir)) {
            pkgDir.delete()
        } else {
            pkgDir.deleteRecursively()
        }
        // 若它被登记进 profile 清单，一并摘掉；没登记就什么都不做
        PluginSessionTransfer.unregisterPlugins(context, listOf(pkgName))
        removed
    } else {
        cfg.file.delete()
    }
}.getOrDefault(false)

// ----------------------------------------------------------------------
// 读盘 / 格式化小工具
// ----------------------------------------------------------------------

/** 空态提示：与下载中心的空态同一种口吻，不留假按钮。 */
@Composable
private fun EmptyState(title: String, detail: String) {
    FclSettingRow(label = title, description = detail, shape = RowShape.SINGLE)
}

internal data class McpServer(
    val name: String,
    /** 给人看的一行细节：`command` / `url` 之类，取不到为空串。 */
    val detail: String
)

internal data class McpConfig(
    val file: File,
    /** 相对 `DSH_HOME` 的路径，界面上显示这个比绝对路径短得多。 */
    val rel: String,
    val servers: List<McpServer>,
    /** 非 null 表示文件读到了但解析不了，值是给人看的原因。 */
    val parseError: String?,
    /** 是不是 bundle 形式（`local-plugins` 下的 cordis.patch.yml）。 */
    val bundle: Boolean = false
)

/**
 * MCP 扫描结果。
 *
 * `internal` 而非 `private`：「MCP 下载」页也要列已装配置，
 * 与设置页共用同一个扫描函数。
 */
internal data class McpScan(
    val configs: List<McpConfig>,
    val searched: List<String>
)

/**
 * 剥掉 JSONC 的注释与尾随逗号，让 `org.json` 能解析。
 *
 * ## 为什么不能直接用正则删 `//`
 *
 * 因为 `"url": "https://..."` 里的 `//` 会被误删，把值改坏。
 * 所以走一个小状态机：只有**字符串之外**的 `//` 与斜杠加星号才算注释。
 * 顺带处理尾随逗号（`{"a":1,}` 这种），那是 JSONC 允许但 JSON 不允许的。
 *
 * 这个函数不追求完备（不处理正则字面量之类，JSON 里也没有），
 * 目标是让带注释的 MCP 配置能被读懂。
 */
private fun stripJsonc(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    var inString = false
    var escaped = false
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            sb.append(c)
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            i++
            continue
        }
        when {
            c == '"' -> {
                inString = true
                sb.append(c)
                i++
            }
            // 行注释：吃到行尾，但保留换行（否则两个 token 会粘在一起）
            c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                while (i < text.length && text[i] != '\n') i++
            }
            // 块注释：吃到闭合
            c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                i += 2
                while (i + 1 < text.length &&
                    !(text[i] == '*' && text[i + 1] == '/')
                ) {
                    i++
                }
                i = (i + 2).coerceAtMost(text.length)
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    // 尾随逗号：`,}` 与 `,]` 收敛掉（此时字符串已被精确保留，
    // 所以这个正则不会碰到字符串内容里的逗号 —— 见下方注意事项）
    return sb.toString().replace(Regex(",(\\s*[}\\]])"), "$1")
}

/**
 * 解析一个 MCP 配置文件。
 *
 * 按四种已知形状依次尝试；都不匹配时返回 `parseError`，
 * 而不是把「解析不了」伪装成「没有服务」。
 */
private fun parseMcpFile(f: File, rel: String): McpConfig {
    val raw = runCatching { f.readText() }.getOrNull()
        ?: return McpConfig(f, rel, emptyList(), "文件存在但读取失败")
    if (raw.isBlank()) {
        return McpConfig(f, rel, emptyList(), "文件是空的")
    }

    val text = stripJsonc(raw.removePrefix("\uFEFF"))

    // 形状一：顶层是对象
    runCatching {
        val o = org.json.JSONObject(text)
        // ① mcpServers 是对象
        o.optJSONObject("mcpServers")?.let { return McpConfig(f, rel, namesOf(it), null) }
        // ② servers 是对象
        o.optJSONObject("servers")?.let { return McpConfig(f, rel, namesOf(it), null) }
        // ③ servers 是数组
        o.optJSONArray("servers")?.let { return McpConfig(f, rel, namesOfArray(it), null) }
        // 对象解析成功但没有这三个键 —— 如实说明，不硬猜
        return McpConfig(
            f, rel, emptyList(),
            "是合法 JSON，但没有 mcpServers / servers 字段"
        )
    }

    // 形状四：顶层是数组
    runCatching {
        val arr = org.json.JSONArray(text)
        return McpConfig(f, rel, namesOfArray(arr), null)
    }

    return McpConfig(
        f, rel, emptyList(),
        "不是可识别的 JSON / JSONC，只报告文件存在"
    )
}

/** 从 `{ "name": { ... } }` 里取键名，并尽量带上一行细节。 */
private fun namesOf(o: org.json.JSONObject): List<McpServer> {
    val out = ArrayList<McpServer>(o.length())
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = o.optJSONObject(k)
        val detail = when {
            v == null -> ""
            v.optString("command").isNotBlank() -> "命令: " + v.optString("command")
            v.optString("url").isNotBlank() -> "地址: " + v.optString("url")
            else -> ""
        }
        out.add(McpServer(k, detail))
    }
    return out.sortedBy { it.name.lowercase(Locale.ROOT) }
}

/** 从 `[ { "name": ... } ]` 里取名字。 */
private fun namesOfArray(arr: org.json.JSONArray): List<McpServer> {
    val out = ArrayList<McpServer>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name").ifBlank { o.optString("id") }
        if (name.isBlank()) continue
        val detail = when {
            o.optString("command").isNotBlank() -> "命令: " + o.optString("command")
            o.optString("url").isNotBlank() -> "地址: " + o.optString("url")
            else -> ""
        }
        out.add(McpServer(name, detail))
    }
    return out
}

/**
 * 扫描 MCP 配置。
 *
 * 三条来源，全部扫一遍：
 *  1. `DSH_HOME` 下的固定候选名；
 *  2. 各 profile 目录下的 `mcp.json` / `mcp.jsonc`；
 *  3. **有界搜索**：数据目录下两层以内、文件名以 `mcp` 开头的 json/jsonc。
 *
 * 第 3 条是兜底。跳过 `node_modules` / `sessions` / `storages` / `logs`
 * 这几个大目录 —— 它们只会有成千上万的无关文件，扫进去只会拖慢界面。
 */
internal fun scanMcpConfigs(root: File): McpScan {
    val searched = ArrayList<String>(12)
    val seen = HashSet<String>(8)
    val out = ArrayList<McpConfig>(4)

    fun tryFile(f: File, rel: String) {
        searched.add(f.absolutePath)
        if (!f.isFile) return
        val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        if (!seen.add(key)) return
        out.add(parseMcpFile(f, rel))
    }

    // ① 固定候选名
    for (rel in listOf("mcp.json", "mcp.jsonc", "config/mcp.json", "mcp/servers.json")) {
        tryFile(File(root, rel), rel)
    }

    // ② profile 目录下
    val profiles = File(root, "profiles")
    if (profiles.isDirectory) {
        val kids = runCatching { profiles.listFiles() }.getOrNull().orEmpty()
        for (p in kids) {
            if (!p.isDirectory || p.name.startsWith(".")) continue
            for (name in listOf("mcp.json", "mcp.jsonc")) {
                tryFile(File(p, name), "profiles/${p.name}/$name")
            }
        }
    }

    // ③ local-plugins 里的 **bundle 形式** MCP 包
    //
    // ★ 这是「MCP 装好了但列表里什么都没有」的真正原因 ★
    //
    // bundle 形式的 MCP 不是 `mcp.json`，而是：
    //
    // ```
    // profiles/web/local-plugins/dsh-shell-demo/
    //   cordis.patch.yml   ← 插入 @deepseek-ai/dsh-mcp-client，带 serverName
    //   mcp-server.mjs     ← 真正的 stdio server
    //   launch-mcp.sh
    // ```
    //
    // 上面两步只找「文件名以 mcp 开头、扩展名 json/jsonc」的文件，
    // 而这里的配置叫 `cordis.patch.yml`（YAML，且不以 mcp 开头），
    // 于是被完全跳过 —— 服务明明在跑，页面却显示「还没有 MCP 配置」。
    val localPlugins = ArrayList<File>(4)
    for (p in runCatching { profiles.listFiles() }.getOrNull().orEmpty()) {
        if (!p.isDirectory || p.name.startsWith(".")) continue
        val lp = File(p, "local-plugins")
        if (!lp.isDirectory) continue
        for (pkg in runCatching { lp.listFiles() }.getOrNull().orEmpty()) {
            if (pkg.isDirectory && !pkg.name.startsWith(".")) localPlugins.add(pkg)
        }
    }
    for (pkg in localPlugins) {
        val cfg = parseMcpBundle(pkg, root)
        if (cfg != null) {
            searched.add(cfg.file.absolutePath)
            val key = runCatching { cfg.file.canonicalPath }.getOrDefault(cfg.file.absolutePath)
            if (seen.add(key)) out.add(cfg)
        }
    }

    // ④ 有界搜索兜底
    val skip = setOf("node_modules", "sessions", "storages", "logs", ".git")
    fun walk(dir: File, depth: Int, rel: String) {
        if (depth > 2) return
        val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in kids) {
            if (com.isLink(f)) continue
            val childRel = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            if (f.isDirectory) {
                if (f.name in skip) continue
                if (f.name.startsWith(".")) continue
                walk(f, depth + 1, childRel)
                continue
            }
            val n = f.name.lowercase(Locale.ROOT)
            if (!n.startsWith("mcp")) continue
            if (!n.endsWith(".json") && !n.endsWith(".jsonc")) continue
            tryFile(f, childRel)
        }
    }
    if (root.isDirectory) walk(root, 0, "")

    // 有服务的那几份排前面，空/坏配置排后面 —— 用户打开这页是想看有什么服务
    val sorted = out.sortedWith(
        compareByDescending<McpConfig> { it.servers.size }
            .thenBy { it.rel }
    )
    return McpScan(sorted, searched)
}

/**
 * 判断 `local-plugins` 下的一个包是不是 **MCP bundle**，并读出它的服务名。
 *
 * ## bundle 形式长什么样
 *
 * `local-plugins` 里的 MCP 不走 `mcp.json`，而是在 `cordis.patch.yml` 里
 * 插入 `@deepseek-ai/dsh-mcp-client`，并用 `serverName` 声明服务名：
 *
 * ```
 * # profiles/web/local-plugins/dsh-shell-demo/cordis.patch.yml
 * - target: '@deepseek-ai/dsh-mcp-client'
 *   serverName: shell
 *   command: /path/to/launch-mcp.sh
 * ```
 *
 * 所以判据是「文件里出现 dsh-mcp-client」，服务名用正则从 `serverName`
 * 抠出来 —— 这里**不做完整 YAML 解析**：为此引一个 YAML 库不值得，
 * 而且 `cordis.patch.yml` 是用户手写的，格式容错比严格更重要。
 *
 * @param pkg  `local-plugins` 下的包目录
 * @param root `$DSH_HOME`，用来算相对路径
 * @return 是 MCP bundle 时返回配置；不是（或读不了）返回 null
 */
private fun parseMcpBundle(pkg: File, root: File): McpConfig? {
    for (name in listOf("cordis.patch.yml", "cordis.patch.yaml", "cordis.yml")) {
        val f = File(pkg, name)
        if (!f.isFile) continue
        val text = runCatching { f.readText() }.getOrNull() ?: continue
        // 判据：这条配置有没有挂 MCP 客户端插件
        if (!text.contains("dsh-mcp-client")) continue

        // serverName: shell  /  serverName: "shell"  /  serverName: 'shell'
        val serverName = Regex("serverName\\s*:\\s*[\"']?([A-Za-z0-9_.-]+)")
            .find(text)?.groupValues?.get(1)

        val rel = bundleRel(pkg, root, name)

        return McpConfig(
            file = f,
            rel = rel,
            servers = if (serverName != null) {
                listOf(McpServer(serverName, "bundle: ${pkg.name}"))
            } else {
                emptyList()
            },
            // 找到 bundle 但没读到 serverName：如实说明，不硬编一个名字
            parseError = if (serverName == null) {
                "是 MCP bundle，但没读到 serverName 字段"
            } else {
                null
            },
            bundle = true
        )
    }
    return null
}

/**
 * 算出 bundle 配置相对 `$DSH_HOME` 的路径。
 *
 * 优先用 canonicalPath 真算；算不出来（路径不在 root 下、规范化失败）
 * 才退回按 `profiles/web/local-plugins/<包>/<文件>` 拼 ——
 * 后者是 dsh 的标准位置，极端情况下也不至于给出一个乱路径。
 */
private fun bundleRel(pkg: File, root: File, fileName: String): String {
    val real = runCatching {
        val base = root.canonicalPath
        val p = pkg.canonicalPath
        if (p.startsWith(base + File.separator)) {
            p.substring(base.length + 1) + "/" + fileName
        } else {
            null
        }
    }.getOrNull()
    return real ?: "profiles/web/local-plugins/${pkg.name}/$fileName"
}

// `describe(f)` 已删除：重写插件页后不再需要「目录 · N 项」这种描述
// （插件列表现在显示「包名 + 版本」与安装位置，会话列表显示聚合后的会话）。

private fun sizeText(f: File): String = humanSize(f.length())

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun timeText(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
