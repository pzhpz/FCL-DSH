package com.example.fcldsh

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    MCP("mcp管理");

    companion object {
        /** 按显示顺序。不用 `entries`，兼容老一些的 Kotlin 编译器。 */
        val all: List<InstanceTab> = listOf(DSH, PLUGIN, SESSION, MCP)
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
    onDeleteVersion: () -> Unit
) {
    val context = LocalContext.current

    // 只读页面的刷新开关：点「刷新」重新扫盘一次
    var tick by remember { mutableStateOf(0) }

    // 变了才重新取：避免每帧读一遍磁盘。
    // dshHome 现在按版本独立（home/.dsh-<版本>），不再有隔离开关 ——
    // 「各个 dsh 不互通」是默认行为，不需要用户选择。
    val dshHome = remember(tick, state.engineVersion) { DSHRunner.dshHome(context) }

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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                when (tab) {
                    InstanceTab.DSH -> DshSettingsTab(
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

                    InstanceTab.PLUGIN -> PluginTab(
                        dshHome = dshHome,
                        tick = tick,
                        onRefresh = { tick++ }
                    )

                    InstanceTab.SESSION -> SessionTab(
                        dshHome = dshHome,
                        tick = tick,
                        onRefresh = { tick++ }
                    )

                    InstanceTab.MCP -> McpTab(
                        dshHome = dshHome,
                        tick = tick,
                        onRefresh = { tick++ }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
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

    Spacer(modifier = Modifier.height(8.dp))

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

    Spacer(modifier = Modifier.height(8.dp))

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

                Spacer(modifier = Modifier.height(16.dp))

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
 */
private data class PluginInfo(
    val name: String,
    val version: String,
    val dir: File,
    val where: String
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
private fun PluginTab(dshHome: File, tick: Int, onRefresh: () -> Unit) {
    val scan = remember(tick) { scanPlugins(dshHome) }

    FclSettingSection("插件")

    if (scan.items.isEmpty()) {
        EmptyState(
            title = "还没有插件",
            detail = "在下面列出的位置都没找到已安装的插件。装完点「刷新列表」再试。"
        )
    } else {
        scan.items.forEachIndexed { i, p ->
            FclSettingRow(
                label = if (p.version.isBlank()) p.name else "${p.name}  ${p.version}",
                // 说明装在哪，便于用户自己核对
                description = p.where,
                shape = RowShape.of(i, scan.items.size)
            ) {
                Text(text = "已装", color = Fcl.Success, fontSize = 10.sp)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

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

    Spacer(modifier = Modifier.height(8.dp))
    FclSettingSection("操作")
    FclSettingRow(
        label = "刷新列表",
        description = "重新扫描磁盘。装完插件后点一下即可看到。",
        shape = RowShape.SINGLE,
        onClick = onRefresh
    ) {
        Text(text = "刷新", color = Fcl.ThemeText, fontSize = 12.sp)
    }
}

/**
 * 扫描已安装插件。
 *
 * 候选顺序按「最可能是真实位置」排列，全部扫一遍而不是命中即停 ——
 * 插件可能装在不同 profile 下，用户预期是「装了的都能看到」。
 */
private fun scanPlugins(dshHome: File): PluginScan {
    val searched = ArrayList<String>(8)
    // 用 canonicalPath 去重：同一个包可能同时被多条候选路径命中
    // （软链、或 profile 的 node_modules 与顶层 node_modules 指向同一处）
    val seen = HashMap<String, PluginInfo>(16)

    // ⚠️ 局部函数的声明顺序有约束：Kotlin 要求先声明后使用
    // （不像顶层函数那样可以跨文件前向引用）。所以 addPackage 必须写在
    // addModules 之前 —— 反过来会报 `Unresolved reference: addPackage`。
    fun addPackage(dir: File, name: String, label: String) {
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (seen.containsKey(key)) return
        val version = readPackageVersion(dir)
        // where 用「label + 包名」而不是绝对路径：绝对路径太长，界面上会挤成省略号
        seen[key] = PluginInfo(name, version, dir, "$label/$name")
    }

    fun addModules(nodeModules: File, label: String) {
        if (!nodeModules.isDirectory) return
        val kids = runCatching { nodeModules.listFiles() }.getOrNull() ?: return
        for (k in kids) {
            // 跳过 .bin / .package-lock.json 之类
            if (k.name.startsWith(".")) continue
            if (!k.isDirectory) continue
            if (k.name.startsWith("@")) {
                // scope 包：磁盘上是两层目录，真正的包在下一层
                val scoped = runCatching { k.listFiles() }.getOrNull() ?: continue
                for (p in scoped) {
                    if (!p.isDirectory) continue
                    addPackage(p, "${k.name}/${p.name}", label)
                }
            } else {
                addPackage(k, k.name, label)
            }
        }
    }

    // ① 各 profile 的 node_modules（dsh 实际解析插件的基准）
    val profiles = File(dshHome, "profiles")
    if (profiles.isDirectory) {
        searched.add(profiles.absolutePath)
        val list = runCatching { profiles.listFiles() }.getOrNull().orEmpty()
        for (p in list) {
            if (!p.isDirectory) continue
            val nm = File(p, "node_modules")
            if (nm.isDirectory) {
                searched.add(nm.absolutePath)
                addModules(nm, "profiles/${p.name}")
            }
        }
        // profile 目录下直接放 node_modules 也算
        val top = File(profiles, "node_modules")
        if (top.isDirectory) {
            searched.add(top.absolutePath)
            addModules(top, "profiles")
        }
    } else {
        searched.add(profiles.absolutePath)
    }

    // ② 兜底：plugins 目录（少数版本/第三方打包会用这个位置）
    val plugins = File(dshHome, "plugins")
    searched.add(plugins.absolutePath)
    if (plugins.isDirectory) addModules(plugins, "plugins")

    val items = seen.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    return PluginScan(items, searched)
}

/** 从包的 `package.json` 读 version；失败返回空串（列表里就不显示版本）。 */
private fun readPackageVersion(dir: File): String = runCatching {
    val pkg = File(dir, "package.json")
    if (!pkg.isFile) return@runCatching ""
    // 只取 version 字段，不整体解析 —— package.json 常带注释/BOM/非法 JSON，
    // 用正则取一个字段比 JSONObject 容错得多。
    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
        .find(pkg.readText())?.groupValues?.get(1).orEmpty()
}.getOrDefault("")

// ----------------------------------------------------------------------
// 会话管理
// ----------------------------------------------------------------------

/** 单个会话在列表里最多显示多少条（会话可能上千个，全列出来会卡）。 */
private const val SESSION_SHOW_LIMIT = 8

/**
 * 一个会话。
 *
 * @param id        会话标识：有 uuid 用 uuid，否则用所在目录名
 * @param fileCount 属于它的文件数（dsh 一个会话常有 metadata + 内容两个文件）
 * @param size      这些文件的合计体积
 * @param last      最近修改时间
 */
private data class SessionInfo(
    val id: String,
    val fileCount: Int,
    val size: Long,
    val last: Long
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
private fun SessionTab(dshHome: File, tick: Int, onRefresh: () -> Unit) {
    val scan = remember(tick) { scanSessions(dshHome) }
    val sessions = scan.items
    val totalSize = remember(tick) { sessions.sumOf { it.size } }

    FclSettingSection("会话")

    if (sessions.isEmpty()) {
        EmptyState(
            title = "还没有会话记录",
            detail = "在 dsh 里聊过一次之后，会话会落在这个数据目录下。"
        )
    } else {
        FclSettingRow(
            label = "共 ${sessions.size} 个会话",
            description = "合计 ${humanSize(totalSize)}。只读展示，删除请用 dsh 自己的界面。",
            shape = if (sessions.size > SESSION_SHOW_LIMIT) RowShape.TOP else RowShape.SINGLE
        ) {
            Text(text = "只读", color = Fcl.TextTertiary, fontSize = 10.sp)
        }

        if (sessions.size > SESSION_SHOW_LIMIT) {
            // 按修改时间倒序展示最近几个，长列表留给文件管理器
            val recent = sessions.sortedByDescending { it.last }.take(SESSION_SHOW_LIMIT)
            recent.forEachIndexed { i, s ->
                FclSettingRow(
                    label = s.id,
                    description = timeText(s.last) + " · " + humanSize(s.size) +
                        if (s.fileCount > 1) " · ${s.fileCount} 个文件" else "",
                    shape = RowShape.of(i, recent.size)
                ) {
                    Text(text = "最近", color = Fcl.TextTertiary, fontSize = 10.sp)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

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

    Spacer(modifier = Modifier.height(8.dp))
    FclSettingSection("操作")
    FclSettingRow(
        label = "刷新列表",
        description = "重新扫描数据目录。",
        shape = RowShape.SINGLE,
        onClick = onRefresh
    ) {
        Text(text = "刷新", color = Fcl.ThemeText, fontSize = 12.sp)
    }
}

/**
 * 判断一个文件是否**不是**会话数据（锁文件、临时文件、半成品）。
 *
 * 这几类都在 `sessions/` 下真实存在，是旧实现把会话数虚高的直接原因。
 */
private fun isNotSessionPayload(name: String): Boolean {
    val n = name.lowercase(Locale.ROOT)
    return n.contains(".lock") ||          // session.lock.dsh-lock
        n.contains(".dsh-lock") ||
        n.endsWith(".tmp") ||              // 原子替换的中间态
        n.endsWith(".part") ||
        n.endsWith(".migrating")
}

/** 从文件名里抽 uuid（会话 metadata 常见命名：`session-<uuid>.json`）。 */
private val SESSION_UUID_RE =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

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

        val parent = f.parentFile
        // 身份判定：
        //  · 在 sessions/<id>/ 这种子目录里 → 用目录路径（一个会话一个目录）
        //  · 直接平铺在 sessions/ 下 → 用文件名里的 uuid
        //  · 两者都没有 → 退回文件名去扩展名
        val key: String = when {
            parent != null && parent.absolutePath != scanFrom.absolutePath ->
                parent.name

            else -> SESSION_UUID_RE.find(f.name)?.value
                ?: f.name.substringBeforeLast('.')
        }

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

    val items = groups.entries
        .map { SessionInfo(it.key, it.value.first, it.value.second, it.value.third) }
        .sortedByDescending { it.last }
    return SessionScan(items, scanFrom.absolutePath)
}

// ----------------------------------------------------------------------
// mcp 管理
// ----------------------------------------------------------------------

/**
 * mcp 管理：读 `DSH_HOME` 下的 mcp 配置文件，列出里面登记的服务名。
 *
 * 只解析 `servers` / `mcpServers` 里的键名 —— 用 Android 自带的
 * `org.json` 就够，不额外引依赖。解析失败（`.jsonc` 带注释之类）时
 * 只报告"文件存在"，不硬猜内容。
 */
@Composable
private fun McpTab(dshHome: File, tick: Int, onRefresh: () -> Unit) {
    val found = remember(tick) { lookupMcpConfig(dshHome) }

    FclSettingSection("MCP")

    if (found == null) {
        EmptyState(
            title = "还没有 MCP 配置",
            detail = "在 dsh 里添加 MCP 服务后，配置会出现在这个数据目录下。"
        )
    } else {
        val (file, servers, parseError) = found
        FclSettingRow(
            label = file.name,
            description = file.absolutePath,
            shape = if (servers.isEmpty()) RowShape.SINGLE else RowShape.TOP
        ) {
            Text(text = sizeText(file), color = Fcl.TextTertiary, fontSize = 10.sp)
        }

        if (parseError != null) {
            FclSettingRow(
                label = "无法解析配置",
                description = parseError,
                shape = RowShape.BOTTOM
            ) {
                Text(text = "—", color = Fcl.TextTertiary, fontSize = 10.sp)
            }
        } else if (servers.isNotEmpty()) {
            servers.forEachIndexed { i, name ->
                FclSettingRow(
                    label = name,
                    description = "已登记的服务",
                    shape = RowShape.of(i + 1, servers.size + 1)
                ) {
                    Text(text = "启用", color = Fcl.Success, fontSize = 10.sp)
                }
            }
        } else {
            FclSettingRow(
                label = "没有登记任何服务",
                description = "配置文件存在，但 servers 列表是空的。",
                shape = RowShape.BOTTOM
            ) {
                Text(text = "空", color = Fcl.TextTertiary, fontSize = 10.sp)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    FclSettingSection("操作")
    FclSettingRow(
        label = "刷新列表",
        description = "重新读取 MCP 配置。",
        shape = RowShape.SINGLE,
        onClick = onRefresh
    ) {
        Text(text = "刷新", color = Fcl.ThemeText, fontSize = 12.sp)
    }
}

// ----------------------------------------------------------------------
// 读盘 / 格式化小工具
// ----------------------------------------------------------------------

/** 空态提示：与下载中心的空态同一种口吻，不留假按钮。 */
@Composable
private fun EmptyState(title: String, detail: String) {
    FclSettingRow(label = title, description = detail, shape = RowShape.SINGLE)
}

private data class McpConfig(
    val file: File,
    val servers: List<String>,
    /** 非 null 表示文件读到了但解析不了，值是给人看的原因。 */
    val parseError: String?
)

/**
 * 在数据目录里找 MCP 配置。
 *
 * 候选名按常见写法从近到远试；找不到返回 null。
 */
private fun lookupMcpConfig(root: File): McpConfig? {
    val candidates = listOf(
        "mcp.json",
        "mcp.jsonc",
        "config/mcp.json",
        "mcp/servers.json"
    )
    for (rel in candidates) {
        val f = File(root, rel)
        if (!f.isFile) continue
        val text = runCatching { f.readText() }.getOrNull()
            ?: return McpConfig(f, emptyList(), "文件存在但读取失败")
        return try {
            val json = org.json.JSONObject(text)
            val servers = json.optJSONObject("mcpServers")
                ?: json.optJSONObject("servers")
            val names = servers?.keys()?.asSequence()?.toList() ?: emptyList()
            McpConfig(f, names, null)
        } catch (e: org.json.JSONException) {
            McpConfig(f, emptyList(), "不是标准 JSON（可能带注释），只报告文件存在")
        }
    }
    return null
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
