package com.example.fcldsh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.DSHRunner

/**
 * 下载中心 · 插件下载页。
 *
 * ## 布局
 *
 * 与实例设置里的插件页保持同一套语言：左边 **三分之一** 放下载源与操作，
 * 右边 **三分之二** 放插件列表，每项右侧自带「下载」按钮。
 *
 * ## 下载源
 *
 * · **默认源**是内置的 [DSHOPEN_INDEX]，只读展示，不可编辑；
 * · 下面是**选填**的自定义地址，填了就用它、留空就用默认源。
 *
 * 自定义源必须返回与默认源同形状的 `index.json`（`plugins` 数组，
 * 每项有 `name` / `title` / `versions`），解析逻辑在 [PluginDownloader] 里，
 * 两种源走的是同一条路径 —— 所以第三方源不需要任何额外适配。
 *
 * ## 装到哪
 *
 * 当前使用中的实例。左栏底部把「会装进哪个实例」明写出来，避免用户在
 * 多实例之间搞混 —— 装错实例的返工成本很高（得删掉再重装）。
 */
@Composable
fun PluginDownloadPanel() {
    val context = LocalContext.current

    // 已保存的自定义源；applied 是「当前正在用的那个」，
    // draft 是输入框里的内容 —— 两者分开，否则每敲一个字都要重新拉列表。
    var applied by remember { mutableStateOf(LauncherPrefs.getPluginSourceUrl(context)) }
    var draft by remember { mutableStateOf(applied) }

    var query by remember { mutableStateOf("") }

    // 分页：当前页（从 0 开始）与「跳页」小窗开关。
    // 页数由列表长度除以 [PLUGIN_PAGE_SIZE] 向上取整得到，
    // 换源 / 改搜索词后页码会超出范围，所以在下面统一夹一次。
    var page by remember { mutableStateOf(0) }
    var jumpDialog by remember { mutableStateOf(false) }

    // 点某个插件后弹出的版本列表。非空即显示小窗 ——
    // 用条目本身当状态，比「包名 + 另外查一遍」可靠：列表换源后仍指向原来那条。
    var versionPicker by remember { mutableStateOf<PluginDownloader.Entry?>(null) }

    // 安装完成后要让「已装」标记与列表颜色刷新。
    // 直接读两个状态当触发器：installBusy 由 true 变 false 就是一次安装结束。
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(PluginDownloader.installBusy, PluginDownloader.installMessage) { tick++ }

    val phase = PluginDownloader.phase
    val busy = PluginDownloader.installBusy

    // 目标实例：每次重组都读一遍，用户可能刚在别处切了实例
    val instance = DSHRunner.currentVersion(context)

    // 打开页面自动拉一次；已有列表（切走再回来）不重复拉
    LaunchedEffect(Unit) {
        if (PluginDownloader.phase == PluginDownloader.Phase.IDLE) {
            PluginDownloader.fetchIndex(context, applied)
        }
    }

    // 已装版本表。按 tick 与条目数缓存：装完后要立刻能看出哪几项已装。
    val installed = remember(tick, PluginDownloader.entries.size) {
        PluginDownloader.entries.associate { it.pkg to PluginDownloader.installedVersion(context, it.pkg) }
    }

    // 外边距从 20dp 收到 12dp：面板本身已有 PanelHost 的底色与内缩，
    // 再套 20dp 会让两栏整体往中间挤、左右空出两条无用的边。
    Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {

        // ── 左栏：搜索 + 下载源 + 操作 + 只读信息（1/3）────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            // 搜索放在左栏顶部（FCL 的下载页就是这么排的）：
            // 它属于「筛选条件」，和下载源是一类东西，不该压在列表上面
            // 占掉列表的高度。改搜索词要回到第一页，否则会停在一个
            // 对新结果集来说并不存在的页码上。
            FclSettingSection("搜索")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.Card
            ) {
                Column(modifier = Modifier.padding(Fcl.RowPadding)) {
                    SourceField(
                        value = query,
                        placeholder = "按名称 / 包名筛选",
                        onChange = {
                            query = it
                            page = 0
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("下载源")

            FclSettingRow(
                label = "默认源",
                description = DSHOPEN_INDEX,
                shape = RowShape.SINGLE
            ) {
                Text(text = "内置", color = Fcl.Success, fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("自定义源（选填）")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.Card
            ) {
                Column(modifier = Modifier.padding(Fcl.RowPadding)) {
                    SourceField(
                        value = draft,
                        placeholder = "留空则使用默认源",
                        onChange = { draft = it }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "须返回与默认源同结构的 index.json。",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            FillAction(
                text = if (phase == PluginDownloader.Phase.LOADING) "拉取中…" else "应用并拉取列表",
                enabled = phase != PluginDownloader.Phase.LOADING && !busy
            ) {
                // 记下这次的选择，切走面板再回来仍然用它
                LauncherPrefs.setPluginSourceUrl(context, draft)
                applied = draft
                PluginDownloader.clearInstallMessage()
                PluginDownloader.fetchIndex(context, draft)
            }

            if (applied.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                FclSettingRow(
                    label = "恢复默认源",
                    description = "清掉自定义地址，改回内置源。",
                    shape = RowShape.SINGLE,
                    onClick = if (busy) null else {
                        {
                            LauncherPrefs.setPluginSourceUrl(context, "")
                            draft = ""
                            applied = ""
                            PluginDownloader.fetchIndex(context, "")
                        }
                    }
                ) {
                    Text(text = "恢复", color = Fcl.ThemeText, fontSize = 12.sp)
                }
            }

            // ── 状态 ──
            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("状态")
            FclSettingRow(
                label = PluginDownloader.message.ifBlank { "尚未拉取" },
                description = PluginDownloader.loadedFrom.ifBlank { null },
                shape = RowShape.SINGLE
            ) {
                if (phase == PluginDownloader.Phase.LOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(14.dp).height(14.dp),
                        strokeWidth = 2.dp,
                        color = Fcl.Theme
                    )
                } else {
                    Text(
                        text = when (phase) {
                            PluginDownloader.Phase.READY -> "就绪"
                            PluginDownloader.Phase.ERROR -> "失败"
                            else -> "—"
                        },
                        color = when (phase) {
                            PluginDownloader.Phase.READY -> Fcl.Success
                            PluginDownloader.Phase.ERROR -> Fcl.Danger
                            else -> Fcl.TextTertiary
                        },
                        fontSize = 10.sp
                    )
                }
            }

            if (PluginDownloader.installMessage.isNotBlank()) {
                FclSettingRow(
                    label = PluginDownloader.installMessage,
                    shape = RowShape.SINGLE
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(14.dp).height(14.dp),
                            strokeWidth = 2.dp,
                            color = Fcl.Theme
                        )
                    } else {
                        Text(
                            text = if (PluginDownloader.installMessage.startsWith("❌")) "失败" else "完成",
                            color = if (PluginDownloader.installMessage.startsWith("❌")) {
                                Fcl.Danger
                            } else {
                                Fcl.Success
                            },
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // ── 安装目标（只读）──
            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("安装目标")
            if (instance == null) {
                FclSettingRow(
                    label = "还没有可用的实例",
                    description = "先去「dsh下载」装一个引擎版本，再回来装插件。",
                    shape = RowShape.SINGLE
                ) {
                    Text(text = "不可用", color = Fcl.Danger, fontSize = 10.sp)
                }
            } else {
                FclSettingRow(
                    label = "当前实例 $instance",
                    description = "插件装进这个实例的 profiles/web，重启实例后生效。",
                    shape = RowShape.SINGLE
                ) {
                    Text(text = "目标", color = Fcl.Success, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Spacer(modifier = Modifier.width(10.dp))

        // ── 右栏：插件列表（2/3）──────────────────────────────────────
        Column(modifier = Modifier.weight(2f).fillMaxHeight()) {
            val all = PluginDownloader.entries
            // 先按搜索词过滤，再分页 —— 分页永远作用在「筛选后的结果」上，
            // 否则搜出来 3 条却还显示 56 页，翻过去全是空的。
            val filtered = remember(query, all.size, PluginDownloader.phase) {
                val q = query.trim().lowercase()
                if (q.isEmpty()) all.toList()
                else all.filter {
                    it.display.lowercase().contains(q) ||
                        it.pkg.lowercase().contains(q) ||
                        it.desc.lowercase().contains(q)
                }
            }

            val pageCount = ((filtered.size + PLUGIN_PAGE_SIZE - 1) / PLUGIN_PAGE_SIZE)
                .coerceAtLeast(1)
            // 结果集变小后旧页码可能越界（换源、改搜索词都会），夹回来
            val safePage = page.coerceIn(0, pageCount - 1)
            if (safePage != page) page = safePage

            val shown = remember(filtered, safePage) {
                filtered.drop(safePage * PLUGIN_PAGE_SIZE).take(PLUGIN_PAGE_SIZE)
            }

            // ★ 分页条固定在这里，不随列表滚动 ★
            //
            // 它放在 scroll 容器**之外**，所以往下翻插件时按钮始终停在顶部 ——
            // 放到列表里面会跟着被划走，翻到第 3 屏想回第一页就得先滑回顶部。
            PageBar(
                page = safePage,
                pageCount = pageCount,
                onJump = { jumpDialog = true },
                onPrev = { page = (safePage - 1).coerceAtLeast(0) },
                onNext = { page = (safePage + 1).coerceAtMost(pageCount - 1) },
                onFirst = { page = 0 },
                onLast = { page = pageCount - 1 }
            )

            Spacer(modifier = Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    phase == PluginDownloader.Phase.LOADING -> Hint("正在拉取列表…")
                    phase == PluginDownloader.Phase.ERROR -> Hint(
                        PluginDownloader.message.ifBlank { "拉取失败" }
                    )
                    filtered.isEmpty() && all.isEmpty() -> Hint(
                        "列表为空。检查左栏的下载源是否可访问，再点「应用并拉取列表」。"
                    )
                    filtered.isEmpty() -> Hint("没有匹配「$query」的插件。")
                    else -> {
                        shown.forEachIndexed { i, e ->
                            val ver = installed[e.pkg]
                            if (i > 0) Spacer(modifier = Modifier.height(4.dp))
                            // ★ 每项用 SINGLE 而不是 of(i, size) ★
                            //
                            // of() 会把相邻行拼成一整块（首行只圆上两角、中间不圆），
                            // 十行连在一起完全看不出分界。这里每行独立圆角 +
                            // 行间 4dp 缝：既分得清，又不至于散成一片。
                            FclSettingRow(
                                label = if (ver == null) e.display else "${e.display}  $ver",
                                description = buildString {
                                    append(e.pkg)
                                    if (e.version.isNotBlank()) append(" · ${e.version}")
                                    if (e.category.isNotBlank()) append(" · ${e.category}")
                                    // 市场描述动辄四五行，是「一屏只放得下两个插件」
                                    // 的主因。这里去掉星数前缀，并限制总行数。
                                    val d = shortDesc(e.desc)
                                    if (d.isNotBlank()) append("\n$d")
                                },
                                // 第一行是包名元信息，后两行给描述；
                                // 不限制的话长描述会把单行撑到 140dp 以上。
                                descMaxLines = 3,
                                shape = RowShape.SINGLE,
                                // 点整行打开版本列表，自己挑要装哪个版本
                                onClick = if (busy) null else ({ versionPicker = e })
                            ) {
                                if (busy) {
                                    // 安装中：只让当前这一项显示进度，其余灰掉
                                    if (PluginDownloader.installingPkg == e.pkg) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.width(14.dp).height(14.dp),
                                            strokeWidth = 2.dp,
                                            color = Fcl.Theme
                                        )
                                    } else {
                                        Text(text = "等待", color = Fcl.TextTertiary, fontSize = 10.sp)
                                    }
                                } else {
                                    if (ver != null) {
                                        Text(text = "已装", color = Fcl.Success, fontSize = 10.sp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    ActionText(
                                        text = when {
                                            instance == null -> "无实例"
                                            ver != null -> "重装"
                                            else -> "下载"
                                        },
                                        enabled = instance != null && !busy,
                                        // 直接装：按当前实例的引擎版本自动挑一个
                                        // 能用的版本（挑不出来才退回最新版）。
                                        // 想指定版本就点整行，从列表里选。
                                        onClick = {
                                            PluginDownloader.install(
                                                context,
                                                e,
                                                PluginDownloader.bestVersion(e, instance)?.version
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    // ── 跳页小窗 ───────────────────────────────────────────────────
    if (jumpDialog) {
        JumpPageDialog(
            page = page,
            pageCount = ((PluginDownloader.entries.size + PLUGIN_PAGE_SIZE - 1) /
                PLUGIN_PAGE_SIZE).coerceAtLeast(1),
            onDismiss = { jumpDialog = false },
            onJump = { target ->
                page = target
                jumpDialog = false
            }
        )
    }

    // ── 版本选择小窗 ───────────────────────────────────────────────
    versionPicker?.let { entry ->
        VersionPickerDialog(
            entry = entry,
            engineVersion = instance,
            installedVersion = installed[entry.pkg],
            onDismiss = { versionPicker = null },
            onPick = { info ->
                versionPicker = null
                PluginDownloader.install(context, entry, info.version)
            }
        )
    }
}

/** 每页多少个插件。改这里即可调整分页粒度。 */
private const val PLUGIN_PAGE_SIZE = 10

/**
 * 去掉市场描述里的星数前缀，并压成一行。
 *
 * 索引里的描述形如 `★ 6,599 │ 可视化组合提示词、工具、技能…`，
 * 星数和竖线对「这个插件是干什么的」毫无帮助，却占掉了宝贵的一行高度。
 * 顺手把换行折成空格，避免描述自带的换行把行数顶掉。
 */
private fun shortDesc(raw: String): String {
    if (raw.isBlank()) return ""
    // 前缀：★ + 任意数字（含千分位逗号）+ 竖线（全角/半角都收）
    val stripped = Regex("^\\s*★\\s*[\\d,]*\\s*[│|]\\s*").replace(raw, "")
    return stripped.replace(Regex("\\s+"), " ").trim()
}

/**
 * 列表顶部的分页条：五个按键，固定不随列表滚动。
 *
 * 顺序按需求定的：
 *  1. **页码**（「3 / 56」）—— 点击弹小窗输入页码跳转
 *  2. 上一页
 *  3. 下一页
 *  4. 第一页
 *  5. 最后一页
 *
 * 页码那格做得比其他宽一些：它是唯一带信息的格子，
 * 「3 / 56」这种文本挤在等宽格里会被省略成「3 /…」。
 */
@Composable
private fun PageBar(
    page: Int,
    pageCount: Int,
    onJump: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFirst: () -> Unit,
    onLast: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Fcl.Radius),
        color = Fcl.Card
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ① 页码：显示「当前 / 总数」，可点开跳页小窗
            BarButton(
                text = "${page + 1} / $pageCount",
                enabled = true,
                weight = 1.6f,
                highlight = true,
                onClick = onJump
            )
            Spacer(modifier = Modifier.width(6.dp))
            // ② 上一页
            BarButton(
                text = "◀",
                enabled = page > 0,
                onClick = onPrev
            )
            Spacer(modifier = Modifier.width(6.dp))
            // ③ 下一页
            BarButton(
                text = "▶",
                enabled = page < pageCount - 1,
                onClick = onNext
            )
            Spacer(modifier = Modifier.width(6.dp))
            // ④ 第一页
            BarButton(
                text = "第一页",
                enabled = page > 0,
                onClick = onFirst
            )
            Spacer(modifier = Modifier.width(6.dp))
            // ⑤ 最后一页
            BarButton(
                text = "最后一页",
                enabled = page < pageCount - 1,
                onClick = onLast
            )
        }
    }
}

/** 分页条里的一格。禁用时不挂手势（真点不动，不是点了没反应）。 */
@Composable
private fun RowScope.BarButton(
    text: String,
    enabled: Boolean,
    weight: Float = 1f,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val base = Modifier
        .weight(weight)
        .clip(RoundedCornerShape(Fcl.Radius))
    Surface(
        modifier = if (enabled) base.fclClickable { onClick() } else base,
        shape = RoundedCornerShape(Fcl.Radius),
        color = if (enabled) Fcl.Track else Fcl.Card
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = when {
                    !enabled -> Fcl.TextTertiary
                    highlight -> Fcl.ThemeText
                    else -> Fcl.TextPrimary
                },
                fontSize = if (highlight) 13.sp else 12.sp,
                fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 跳页小窗：输入页码后确认跳转。
 *
 * 输入框只收数字，超出范围时夹到「第一页 ~ 最后一页」——
 * 让用户输 999 直接报错不如给他最后一页，反正他想要的是「往后翻到底」。
 */
@Composable
private fun JumpPageDialog(
    page: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    // 显示用 1 起，内部 0 起 —— 用户不认「第 0 页」
    var text by remember { mutableStateOf((page + 1).toString()) }

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
                    text = "跳到第几页",
                    color = Fcl.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "共 $pageCount 页，每页 $PLUGIN_PAGE_SIZE 个",
                    color = Fcl.TextTertiary,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = Fcl.Track
                ) {
                    BasicTextField(
                        value = text,
                        // 只留数字：其他字符直接丢掉，省得校验
                        onValueChange = { input ->
                            text = input.filter { it.isDigit() }.take(6)
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = Fcl.TextPrimary, fontSize = 13.sp),
                        cursorBrush = SolidColor(Fcl.Theme),
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = 10.dp, vertical = 9.dp
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End) {
                    BarButton(
                        text = "取消",
                        enabled = true,
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BarButton(
                        text = "跳转",
                        enabled = true,
                        highlight = true,
                        onClick = {
                            val n = text.toIntOrNull() ?: (page + 1)
                            onJump((n - 1).coerceIn(0, pageCount - 1))
                        }
                    )
                }
            }
        }
    }
}

/**
 * 版本选择小窗：列出这个插件的全部版本，让用户自己挑。
 *
 * ## 为什么要分「适配 / 未适配」两种标记
 *
 * 每个版本带 `engines.dsh`（形如 `0.1.5-rc.2 || 0.2.1-rc.1`），声明它能配哪些
 * 引擎版本。装一个不匹配的版本，插件可能直接在引擎启动时崩掉，
 * 而且症状和「插件本身有 bug」完全一样，很难查。
 *
 * 所以：
 *  · 匹配当前实例的版本打「适配当前」绿标，并排在最前（[PluginDownloader.bestVersion]
 *    选出来的那个）；
 *  · 没声明限制的打「未声明」，能装但没保证；
 *  · 明明声明了却不匹配的，如实标红并说明它要哪些引擎版本 ——
 *    不禁用，用户想试就让他试，但得知道自己在做什么。
 *
 * ## 为什么不禁用不匹配的项
 *
 * 用户的引擎版本可能被自己改过、或者索引的 `engines` 字段写旧了。
 * 一律禁用会把「本来能装」的插件也挡掉。给信息、不禁操作。
 */
@Composable
private fun VersionPickerDialog(
    entry: PluginDownloader.Entry,
    engineVersion: String?,
    installedVersion: String?,
    onDismiss: () -> Unit,
    onPick: (PluginDownloader.VersionInfo) -> Unit
) {
    val best = PluginDownloader.bestVersion(entry, engineVersion)
    val engine = engineVersion?.trim().orEmpty()

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
                    text = entry.display,
                    color = Fcl.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(entry.pkg)
                        if (engine.isNotBlank()) append(" · 当前实例 $engine")
                    },
                    color = Fcl.TextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Fcl.Line)
                Spacer(modifier = Modifier.height(6.dp))

                if (entry.versions.isEmpty()) {
                    // 索引没给版本列表时不能给一个空窗，直接说明并
                    // 提供「装最新版」这一条出路
                    Text(
                        text = "这个插件的索引没有列出可用版本。",
                        color = Fcl.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                } else {
                    Column(
                        modifier = Modifier
                            // 版本多时不让小窗顶满屏幕，列表内部自己滚
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        entry.versions.forEachIndexed { i, v ->
                            if (i > 0) Spacer(modifier = Modifier.height(6.dp))
                            VersionRow(
                                info = v,
                                engine = engine,
                                isBest = best != null && v.version == best.version,
                                isInstalled = installedVersion == v.version,
                                onClick = { onPick(v) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End) {
                    BarButton(text = "关闭", enabled = true, onClick = onDismiss)
                }
            }
        }
    }
}

/** 版本小窗里的一行：[版本号] [适配说明] [标记]。整行可点即安装。 */
@Composable
private fun VersionRow(
    info: PluginDownloader.VersionInfo,
    engine: String,
    isBest: Boolean,
    isInstalled: Boolean,
    onClick: () -> Unit
) {
    // 明确声明了却和当前引擎不匹配 —— 只在这时标红
    val incompatible = info.hasConstraint &&
        engine.isNotBlank() &&
        !PluginDownloader.enginesMatch(info.dshRange, engine)

    Surface(
        modifier = Modifier.fillMaxWidth().fclClickable { onClick() },
        shape = RoundedCornerShape(Fcl.Radius),
        color = Fcl.Track
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.version,
                    color = Fcl.TextPrimary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (info.hasConstraint) {
                        "需要 dsh ${info.dshRange}"
                    } else {
                        "未声明适配的 dsh 版本"
                    },
                    color = if (incompatible) Fcl.Danger else Fcl.TextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
            if (isInstalled) {
                Badge("已装", Fcl.Success)
                Spacer(modifier = Modifier.width(6.dp))
            }
            when {
                isBest -> Badge("适配当前", Fcl.Success)
                incompatible -> Badge("不匹配", Fcl.Danger)
            }
        }
    }
}

/** 小标签。 */
@Composable
private fun Badge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 9.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 一行说明文字，用于空态与加载态。 */
@Composable
private fun Hint(text: String) {    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Fcl.Radius),
        color = Fcl.Card
    ) {
        Text(
            text = text,
            color = Fcl.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(Fcl.RowPadding)
        )
    }
}

/**
 * 单行输入框（下方一条底线，与下载中心其他输入框同一种画法）。
 *
 * 不用 Material 的 TextField：它自带的内边距与外框会把左栏撑得很宽，
 * 在三分之一宽度里显得局促。
 */
@Composable
private fun SourceField(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit
) {
    Column {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Fcl.TextPrimary, fontSize = 11.sp),
            cursorBrush = SolidColor(Fcl.Theme),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = Fcl.TextTertiary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = Fcl.Line)
    }
}

/** 主题色实心按钮（铺满宽度）。 */
@Composable
private fun FillAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(Fcl.Radius))
    Surface(
        modifier = if (enabled) base.fclClickable { onClick() } else base,
        shape = RoundedCornerShape(Fcl.Radius),
        color = if (enabled) Fcl.Theme else Fcl.Track
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) Color.White else Fcl.TextTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 行内文字按钮。
 *
 * 禁用时**不挂手势**，而不是只把颜色调灰 —— 安装进行中再点一次会重复解包
 * 同一个目录，两个线程同时写只会留下半坏的文件。
 */
@Composable
private fun ActionText(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val base = Modifier
        .clip(RoundedCornerShape(Fcl.Radius))
        .padding(horizontal = 6.dp, vertical = 4.dp)
    Text(
        text = text,
        color = if (enabled) Fcl.ThemeText else Fcl.TextTertiary,
        fontSize = 12.sp,
        modifier = if (enabled) base.fclClickable { onClick() } else base
    )
}
