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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextStyle
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
 * 需求点名的顺序：dsh设置 / 插件管理 / 会话管理 / 配置便签。
 * 前三项都直接读盘（[DSHRunner.dshHome] 指向的那份数据目录），
 * 最后一项是**与实例无关**的文本收藏（见 [ConfigClipboard]）。
 */
enum class InstanceTab(val label: String) {
    DSH("dsh设置"),
    PLUGIN("插件管理"),
    SESSION("会话管理"),
    SKILL("skill管理"),
    MCP("配置便签");

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
    onExportSkill: (String, String) -> Unit
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

                    // MCP 页**不读实例目录**了（见 [McpTab] 的注释），
                    // 所以不需要 dshHome / tick / 导入导出回调 —— 它是应用级的
                    // 配置剪贴板，与当前实例无关。
                    InstanceTab.MCP -> McpTab()
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

    // ── 存储 ────────────────────────────────────────────────────────
    Spacer(modifier = Modifier.height(6.dp))
    StorageSection(state = state)
}

/**
 * 「存储」一节：三行占用 + 一个一键清理。
 *
 * ## 为什么只有这么几行
 *
 * 磁盘上能吃掉空间的地方有七处（容器、各版本引擎、`$DSH_HOME`、工作区、
 * npm 缓存、安装临时文件、下载的包），逐项列出来会变成一张报表，
 * 而用户真正要的是「看出大头在哪、一键收掉」。所以按**处置方式**合并成三组：
 *
 * | 行 | 内容 | 能不能清 |
 * |---|---|---|
 * | 引擎与实例 | `usr/` + `home/`（会话、工作区、凭据） | 只能删实例，见上面那行 |
 * | 缓存与临时文件 | `home/.npm`、`home/.cache`、`cacheDir/` | 可清 |
 * | 下载文件 | 外部下载目录里的 `.tgz` | 可清 |
 *
 * ## 为什么要单独把缓存列出来
 *
 * npm 的 `_cacache` 是**跨安装复用**的：装一个引擎落几百 MB，装几个版本就上 G，
 * 而「删除该版本」按设计只删实例自己的两棵树，缓存一分不动 ——
 * 这正是「装完 400MB、删干净实例却剩 1.4G」的差额来源。
 * 现在删最后一个实例时会顺带收掉它（[Storage.purgeSharedCaches]），
 * 这个面板负责让用户随时手动收，也能看见到底占了多大。
 *
 * ## 实例正在运行时不清 `cacheDir/tmp`
 *
 * 那是引擎进程的 `TMPDIR`（见 [DSHRunner.startNode]），删掉会让运行中的
 * 进程写临时文件失败。其余缓存照清 —— 它们只被「安装」读，不被「运行」读。
 */
@Composable
private fun StorageSection(state: LauncherState) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }

    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var freed by remember { mutableStateOf<Long?>(null) }
    // 清理完 tick++ 触发重量：Compose 里没有「重新执行一次测量」的按钮，
    // 用 key 变化是最省事的做法。
    var tick by remember { mutableStateOf(0) }

    // 统计要遍历引擎那上万个小文件，绝不能放主线程 —— 那会让面板卡住好几秒。
    LaunchedEffect(tick) {
        usage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Storage.measure(context)
        }
    }

    val installing = EngineInstaller.isBusy() || DshEngineDownloader.isBusy()
    val canClear = !busy && !installing
    val total = usage?.total ?: 0L
    val reclaimable = usage?.reclaimable ?: 0L

    FclSettingSection("存储")

    FclSettingRow(
        label = "引擎与实例",
        description = "容器、各版本引擎，以及实例里的会话、工作区与凭据。" +
            "这一块只能靠上面的「删除该版本」释放。",
        shape = RowShape.TOP
    ) {
        HScrollSize(usage?.engine)
    }

    FclSettingRow(
        label = "缓存与临时文件",
        description = "npm 下载缓存与安装临时文件。清掉不影响已装好的实例，" +
            "只是下次安装要重新下载。",
        shape = RowShape.MIDDLE
    ) {
        HScrollSize(usage?.cache)
    }

    FclSettingRow(
        label = "下载文件",
        description = "下载中心保存的包文件（应用外部下载目录）。",
        shape = RowShape.BOTTOM
    ) {
        HScrollSize(usage?.download)
    }

    // 一键清理
    FclSettingRow(
        label = "一键清理",
        description = when {
            busy -> "正在清理…"
            installing -> "安装/下载进行中，结束再清理。"
            usage == null -> "正在统计占用…"
            else -> "当前共 ${fmtSize(total)}，可释放 ${fmtSize(reclaimable)}。" +
                (freed?.let { " 上次释放 ${fmtSize(it)}。" } ?: "")
        },
        shape = RowShape.SINGLE
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (canClear) Fcl.Theme else Fcl.TextTertiary,
            modifier = if (canClear) {
                Modifier.fclClickable {
                    busy = true
                    freed = null
                    // 清理是纯磁盘操作（上万个文件），放后台线程；
                    // 结果回主线程再改 Compose 状态。
                    Thread {
                        val got = Storage.clearAll(context, keepTmp = state.runState.isAlive)
                        main.post {
                            busy = false
                            freed = got
                            tick++
                        }
                    }.start()
                }
            } else {
                Modifier
            }
        ) {
            Text(
                text = if (busy) "清理中" else "清理",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

/** 右侧的尺寸文案；还没统计完时显示 `…`，不假装是 0。 */
@Composable
private fun HScrollSize(bytes: Long?) {
    Text(
        text = bytes?.let { fmtSize(it) } ?: "…",
        color = Fcl.TextSecondary,
        fontSize = 11.sp
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
 * 已装插件的「包名 → 显示名」映射，供 [PlanSpec] 生成方案文本时使用。
 *
 * ## 为什么方案要写显示名而不是包名
 *
 * 用户实测：包名 `dsh-meme` 匹配不到插件，而它的显示名 `表情包` 能匹配到。
 * 原因是「用户认得的名字」与「npm 包名」不是同一个东西 —— 前者来自插件自己
 * 声明的 `title`，后者是安装目录名。方案是给人看、给人粘的，写显示名更合理，
 * 但匹配端必须两种都认（见 `PlanSpec.matchPlugin`）。
 *
 * 复用 [scanPlugins] 而不是在这里重新扫一遍目录：插件可能装在
 * `profiles/<p>/node_modules` 或 `profiles/node_modules`，解析顺序与 Node 一致，
 * 自己再写一份迟早会与插件管理页的结论不一致。
 *
 * 没有显示名的插件不出现在表里，调用方回落到包名 —— 这是正常降级。
 */
internal fun installedPluginTitles(
    dshHome: File,
    engineModules: File?,
    marketTitles: Map<String, String>
): Map<String, String> {
    val out = HashMap<String, String>(16)
    runCatching { scanPlugins(dshHome, engineModules, marketTitles) }.getOrNull()
        ?.items?.forEach { p ->
            if (p.title.isNotBlank()) out[p.name] = p.title
        }
    return out
}

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

            // ── 清单一致性（dsh 内部插件功能的修复入口）─────────────────
            //
            // 放在插件页而不是设置页：症状都出在插件装卸上（dsh 内部市场显示
            // 「无合适版本」、卸载报错），用户排查时人已经在这一页了。
            //
            // 真实根因（由真机日志确定，见 [ProfileRepair] 的类注释）：
            // `package.json` 里有一条**不可能满足**的版本声明
            // （实测是 `dsh-our-free-model@2.8.3`，而该包最新只到 2.1.0）。
            // pnpm 的 add 与 remove 都要先解析整棵依赖树，一条坏声明就让
            // 两个操作一起失败 —— 这才是「外面能装、里面不能」的真因。
            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("清单一致性")
            run {
                var repairing by remember { mutableStateOf(false) }
                // tick 参与 key：修复完成后 onRefresh 自增 tick，摘要随之重算
                val repairSummary = remember(tick) { ProfileRepair.summary(context) }
                FclSettingRow(
                    label = "检查插件清单",
                    description = repairSummary + "\n" +
                        "修复会：联网校验每个依赖的版本是否真的存在（不存在的改指" +
                        "已发布的最新版）、删掉 pnpm 的锁与元数据让它重新解析、" +
                        "摘掉指向未安装插件的加载项，并把残留的 .bak 备份移出 profile。",
                    shape = RowShape.SINGLE,
                    onClick = if (repairing || busy) {
                        null
                    } else {
                        {
                            repairing = true
                            Thread {
                                val msg = ProfileRepair.repair(context) { l ->
                                    DSHRunner.onLog(l)
                                }
                                main.post {
                                    DSHRunner.onLog(msg)
                                    repairing = false
                                    onRefresh()
                                }
                            }.start()
                        }
                    }
                ) {
                    Text(
                        text = if (repairing) "修复中…" else "修复",
                        color = if (repairing || busy) Fcl.TextTertiary else Fcl.ThemeText,
                        fontSize = 12.sp
                    )
                }
            }

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
// 配置剪贴板（原「mcp 管理」）
// ----------------------------------------------------------------------

/**
 * 配置剪贴板：**与实例无关**的三分类文本收藏。
 *
 * ## 为什么不再读实例里的 MCP
 *
 * 上一版这一页靠遍历实例私有目录去找 MCP 配置文件。那条路没有出口：
 *
 * - **位置只能猜**。MCP 可能写在 `mcp.json`、profile 补丁层的
 *   `cordis.patch.yml`、`local-plugins/<包>/cordis.patch.yml`、
 *   甚至插件数据目录里，形态是 JSON / JSONC / YAML 三种。
 *   每发现一种就往扫描逻辑里加一条，永远补不完；
 * - **找到了也改不了**。补丁层与插件包内的文件是**共享**的，
 *   里面还躺着别人的配置（主题、快捷键），按 JSON 重写会把整份 YAML 写坏，
 *   所以只能「只读展示」—— 一个只能看不能动的管理页没什么价值；
 * - **跟着实例走**。切实例就看不到了，而用户真正想要的是
 *   「这段配置我记下来，换个实例也能粘回去」。
 *
 * 现在它只存用户自己粘进来的文本：位置无关、实例无关，
 * 也就不存在「扫描不到」这个故障模式。
 *
 * ## 结构
 *
 * 仍然是左栏（分类 + 新增）/ 右栏（当前分类的条目），
 * 与插件页、会话页共用 [SplitPane]，观感一致。
 * 每条记录是**标题 + 正文**，正文原样保存、不做任何解析。
 */
@Composable
private fun McpTab() {
    val context = LocalContext.current

    // 当前筛选。页内状态：切走面板再回来会回到「命令」，
    // 与下载中心「切走再回来回到默认页签」的处理一致。
    var filter by remember { mutableStateOf(ClipFilter.COMMAND) }

    // 正在更改 / 新建 / 待删除。三者互斥，各自独立成窗。
    var editing by remember { mutableStateOf<ClipEntry?>(null) }
    var creating by remember { mutableStateOf<ClipCategory?>(null) }
    var pendingDelete by remember { mutableStateOf<ClipEntry?>(null) }

    // 读表（内部订阅了 ConfigClipboard.revision，写操作后自动重组）。
    // 全部视图直接取整表，其余按分类过滤 —— 见 [ClipFilter] 为什么两者分开。
    val all = ConfigClipboard.all(context)
    val entries = filter.category?.let { c -> all.filter { it.category == c } } ?: all
    val counts = ClipCategory.all.associateWith { c -> all.count { it.category == c } }
    val total = all.size

    SplitPane(
        left = {
            // ── 分类（含「全部」）────────────────────────────────────────
            FclSettingSection("分类")
            ClipFilter.all.forEachIndexed { i, f ->
                FclSettingRow(
                    label = f.label,
                    description = f.hint,
                    shape = RowShape.of(i, ClipFilter.all.size),
                    onClick = { filter = f }
                ) {
                    val n = f.category?.let { counts[it] ?: 0 } ?: total
                    Text(
                        text = "$n",
                        color = if (f == filter) Fcl.ThemeText else Fcl.TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("统计")
            FclSettingRow(
                label = "$total 条记录",
                description = "命令 ${counts[ClipCategory.COMMAND] ?: 0} · " +
                    "方案 ${counts[ClipCategory.PLAN] ?: 0} · " +
                    "MCP ${counts[ClipCategory.MCP] ?: 0}",
                shape = RowShape.SINGLE
            ) {
                Text(text = "与实例无关", color = Fcl.TextTertiary, fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))
        },
        right = {
            // ── 标题行 + 加号 ───────────────────────────────────────────
            //
            // 「新增」不再是左栏的一项：它是这一页唯一的**写**操作，
            // 放在列表正上方（内容自己的头部）比放在分类导航里更符合
            // 「这里的加号 = 往这个列表里加一条」的直觉。
            //
            // 在「全部」视图下新建时落到「命令」：新增必须有确定归属，
            // 而「全部」不是可存储的分类（见 [ClipFilter] 的注释）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${filter.label}（${entries.size}）",
                    color = Fcl.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                AddButton { creating = filter.category ?: ClipCategory.COMMAND }
            }

            if (entries.isEmpty()) {
                EmptyState(
                    title = if (total == 0) "还没有任何记录" else "「${filter.label}」里没有记录",
                    detail = "点右上角的「+」存一段文字进来。" +
                        "这里只负责保存，不会去读写任何实例目录。"
                )
            } else {
                entries.forEachIndexed { index, e ->
                    FclSettingRow(
                        label = e.title,
                        description = buildString {
                            // 「全部」视图里混着三个分类，得标出这一条属于哪类，
                            // 否则「计划」和「MCP 片段」在列表里看不出区别。
                            if (filter.category == null) {
                                append(e.category.label)
                                append(" · ")
                            }
                            append(previewOf(e.content))
                            append("\n")
                            append(timeText(e.updatedAt))
                        },
                        shape = RowShape.SINGLE
                    ) {
                        RowAction(text = "删除", danger = true) { pendingDelete = e }
                        Spacer(modifier = Modifier.width(2.dp))
                        RowAction(text = "更改") { editing = e }
                        Spacer(modifier = Modifier.width(2.dp))
                        RowAction(text = "复制") { copyToClipboard(context, e) }
                    }
                    if (index < entries.size - 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    )

    // ── 新建 ────────────────────────────────────────────────────────
    creating?.let { cat ->
        ClipEditDialog(
            category = cat,
            entry = null,
            onDismiss = { creating = null },
            onSave = { c, title, content ->
                ConfigClipboard.add(context, c, title, content)
                creating = null
                // 存完切到它所在的分类，用户能立刻看到结果。
                // 「全部」视图下不动：新条目本来就会出现，切走反而让它跳位置。
                if (filter.category != null) {
                    filter = ClipFilter.all.first { it.category == c }
                }
            }
        )
    }

    // ── 更改 ────────────────────────────────────────────────────────
    editing?.let { e ->
        ClipEditDialog(
            category = e.category,
            entry = e,
            onDismiss = { editing = null },
            onSave = { c, title, content ->
                ConfigClipboard.update(context, e.id, c, title, content)
                editing = null
                if (filter.category != null) {
                    filter = ClipFilter.all.first { it.category == c }
                }
            }
        )
    }

    // ── 删除确认 ────────────────────────────────────────────────────
    //
    // 剪贴板的删除**真的不可撤销**（没有回收站），所以按惯例二次确认 ——
    // 与插件/会话的删除保持同一套交互。
    pendingDelete?.let { e ->
        ConfirmDialog(
            title = "删除「${e.title}」",
            detail = "这条记录会从「${e.category.label}」里移除，不可撤销。",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                ConfigClipboard.delete(context, e.id)
                pendingDelete = null
            }
        )
    }
}

/**
 * 列表头部那个「+」按钮。
 *
 * 做成小方块而不是一整行按钮：它和列表是同一个视觉组（都在这块内容里），
 * 一整行会把「标题 — 列表」的连读打断；而纯文字「+」又没有可点的边界感，
 * 所以给一个带底色的小圆角块。
 *
 * 尺寸按 22dp × 22dp：比行内文字按钮略大，手指点得中，
 * 又不会把标题行撑高到和列表项一样。
 */
@Composable
private fun AddButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Fcl.Track,
        modifier = Modifier
            .size(22.dp)
            .fclClickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "+",
                color = Fcl.ThemeText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 列表里那行正文预览：取前两行，压掉空行，超长截断。
 *
 * 不做 Markdown 渲染也不显示全文 —— 列表要能一眼扫完，
 * 完整内容靠「更改」或「复制」拿到。
 */
private fun previewOf(content: String): String {
    val lines = content.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .toList()
    if (lines.isEmpty()) return "（空）"
    val joined = lines.joinToString(" / ")
    return if (joined.length <= 80) joined else joined.take(78) + "…"
}

/**
 * 把任意一段文本写进系统剪贴板。
 *
 * 抽出来是因为「配置便签的复制」与「复制方案」要做的是同一件事，
 * 而那段 `setPrimaryClip` 带一条必须准确贴住的 `@Suppress` ——
 * 复制一遍到第二个调用点，早晚会有一处漏掉注解或被随手「优化」成属性语法，
 * 而属性语法在 `ClipboardManager` 上是**编译错误**（见下面的注释）。
 *
 * @param label 剪贴板条目的标题（在部分 ROM 的剪贴板管理器里会显示）
 */
private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val cm = runCatching {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    }.getOrNull()
    if (cm == null) {
        DSHRunner.onLog("❌ 剪贴板不可用")
        return
    }
    runCatching {
        // ★ 必须写成方法调用，不能写 `cm.primaryClip = ...` ★
        //
        // 分析器会建议改成属性语法（UsePropertyAccessSyntax），但那个建议
        // 在框架的 `ClipboardManager` 上**不成立**：这里 `primaryClip` 被解析成
        // 只读属性，赋值直接报 `'val' cannot be reassigned`。
        // 原因是对外的 getter 是 `getPrimaryClip()`、setter 是
        // `setPrimaryClip(ClipData)` —— 名字对不上 JavaBean 的合成属性规则。
        //
        // 所以照旧用 setPrimaryClip，并在**调用表达式上**就地压掉那条建议。
        // 注解必须贴住这个调用；挂到函数上或单独一行都不生效（项目里踩过）。
        run { @Suppress("UsePropertyAccessSyntax") cm.setPrimaryClip(
            android.content.ClipData.newPlainText(label, text)
        ) }
    }.onSuccess {
        DSHRunner.onLog("📋 已复制「$label」")
    }.onFailure {
        DSHRunner.onLog("❌ 复制失败: ${it.message ?: it.javaClass.simpleName}")
    }
}

/** 把一条记录写进系统剪贴板。 */
private fun copyToClipboard(context: Context, e: ClipEntry) {
    copyTextToClipboard(context, e.title, e.content)
}

/**
 * 新建 / 编辑一条记录的小窗。
 *
 * ## 为什么正文用多行框而不是单行
 *
 * 这一页存的就是「一整段配置」。单行输入框粘一段 YAML 进去会只剩一行可见，
 * 用户没法检查自己粘对了没有。
 *
 * ## 为什么「保存」在正文为空时不可点
 *
 * 空正文的记录在列表里只能显示「（空）」，没有任何用处，
 * 还会让统计数字虚高。标题可以留空（会自动用正文首行兜底），正文不行。
 *
 * @param category 打开时选中的分类；用户可以在窗里改
 * @param entry null 表示新建
 */
@Composable
private fun ClipEditDialog(
    category: ClipCategory,
    entry: ClipEntry?,
    onDismiss: () -> Unit,
    onSave: (ClipCategory, String, String) -> Unit
) {
    // key 用 entry?.id：同一个小窗被复用于另一条时，输入框必须换成新内容，
    // 不能把上一条的残留带过去。
    var cat by remember(entry?.id) { mutableStateOf(entry?.category ?: category) }
    var title by remember(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }
    var content by remember(entry?.id) { mutableStateOf(entry?.content.orEmpty()) }

    val canSave = content.isNotBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            // 380dp：手机上竖屏/横屏都放得下。原来 420dp 在窄屏上会被贴边裁切。
            modifier = Modifier.width(380.dp),
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.DialogBg
        ) {
            // ## 为什么按钮必须留在滚动区**外面**
            //
            // 之前「保存」看不见，原因是它排在 Column 最后：横屏时可用高度很小，
            // 上面「标题 + 分类 + 两个输入框 + 说明」加起来就超了，
            // 超出部分被直接裁掉 —— 按钮正好是被裁掉的那一段。
            //
            // 所以改成：中间的可变内容进 verticalScroll（并且只占它需要的
            // 高度，`fill = false`），按钮固定在底部，永远在可视区内。
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (entry == null) "新增一条" else "更改",
                    color = Fcl.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 分类切换：三个小按钮比下拉更直接，且不用引入额外组件
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ClipCategory.all.forEach { c ->
                            val on = c == cat
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (on) Fcl.Theme else Fcl.Track,
                                modifier = Modifier.fclClickable { cat = c }
                            ) {
                                Text(
                                    text = c.label,
                                    // 选中项压在主题色底上，用纯白保证对比度
                                    // （`Fcl` 里没有 OnTheme 这个颜色）。
                                    color = if (on) Color.White else Fcl.TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 5.dp
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            if (it.length <= ConfigClipboard.MAX_TITLE * 2) title = it
                        },
                        singleLine = true,
                        placeholder = { Text("标题（留空则用正文首行）", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        placeholder = {
                            Text("正文：命令 / 方案 / 配置片段，原样保存", fontSize = 12.sp)
                        },
                        textStyle = TextStyle(fontSize = 12.sp, color = Fcl.TextPrimary),
                        // 120dp 而不是 180dp：横屏时高度很紧，
                        // 留给输入框的越多，越容易把下面的按钮挤出屏幕。
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "只保存在本机，与实例无关；不会写入任何实例目录。",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                // 分隔线 + 固定按钮行：让「保存」在视觉上是一个操作条，
                // 而不是一段读到最后才发现的文字。
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Fcl.Divider)
                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = "取消", color = Fcl.TextSecondary, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { onSave(cat, title, content) },
                        enabled = canSave
                    ) {
                        Text(
                            text = "保存",
                            // 可保存时用主题色 + 加粗，一眼能看出这是主操作；
                            // 正文为空时置灰，并如实说明原因（见下方提示）。
                            color = if (canSave) Fcl.ThemeText else Fcl.TextTertiary,
                            fontSize = 13.sp,
                            fontWeight = if (canSave) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }

                if (!canSave) {
                    Text(
                        text = "正文为空时无法保存。",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 读盘 / 格式化小工具
// ----------------------------------------------------------------------

/**
 * 空态提示：与下载中心的空态同一种口吻，不留假按钮。
 *
 * （历史上这里还有一整片 MCP 扫描代码 —— `McpConfig` / `scanMcpConfigs` /
 *  `parsePatchYaml` 等。改成「配置便签」后它们全部没了调用点，已一并删除。
 *  那套东西的教训写在 [ConfigClipboard] 的注释里：按位置猜配置文件永远补不完，
 *  而且找到了也改不动共享的 YAML。）
 */
@Composable
private fun EmptyState(title: String, detail: String) {
    FclSettingRow(label = title, description = detail, shape = RowShape.SINGLE)
}

// McpServer / McpConfig / McpScan / stripJsonc / parseMcpFile / namesOf /
// namesOfArray / scanMcpConfigs / parsePatchYaml 等**全部已删除**：
// 它们是「遍历实例目录找 MCP 配置」那一版的产物。
// 改成配置便签（[ConfigClipboard]）后，这套「按位置猜文件」的扫描彻底失去
// 调用点 —— 教训见 ConfigClipboard 的类注释。

// （scanMcpConfigs 及其全部预算常量、jsonLooksLikeMcp、MCP_MARKERS、
//   yamlBlocks、parsePatchYaml 已随上面的 scanner 一起删除。）

/**
 * 人类可读的体积。
 *
 * ⚠️ 这个函数**必须保留**：插件页、会话页、skill 页的副行都在用它
 * （`合计 ${humanSize(total)}` 之类）。当初删 `sizeText` 时把它一起删掉，
 * 才出现「整片 Unresolved reference 'humanSize'」——`sizeText` 只是它的
 * 一层包装，真正被引用的是它本身。
 */
private fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 时间戳 → `MM-dd HH:mm`。会话与文件列表共用。 */
private fun timeText(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
