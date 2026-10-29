package com.example.fcldsh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 下载中心的五个页签。
 *
 * 页签名称照需求定：dsh下载 / 整合包下载 / 插件下载 / skill下载 / mcp下载。
 * 目前只有 [DSH] 接了真实数据（npm registry + 安装器），
 * 其余四个按 FCL 的 `page_download.xml` 版式做出样子，暂不接后端。
 *
 * 这里不再有 `installLabel`：原来每个页签底下有个「安装整合包 / 安装插件 / …」
 * 的实心按钮，但背后没有任何下载源，点下去只会弹一句「尚未接入」。
 * 按需求把这类假按钮去掉了，字段也就没有存在的必要。
 *
 * 同理也没有 `minecraft` 了：它当初只用来区分「选游戏版本」还是「选适配版本」的
 * 下拉文案，以及切 Modrinth / CurseForge 两个源。这些下拉已全部去掉、
 * 源统一为 [DSHOPEN_INDEX]，这个标志位没有任何读者了。
 */
enum class DownloadTab(val label: String) {
    DSH("dsh下载"),
    MODPACK("整合包下载"),
    PLUGIN("插件下载"),
    SKILL("skill下载"),
    MCP("mcp下载");

    companion object {
        /** 按显示顺序排列。不用 `entries`，兼容老一些的 Kotlin 编译器。 */
        val all: List<DownloadTab> = listOf(DSH, MODPACK, PLUGIN, SKILL, MCP)
    }
}

/**
 * 页签条（对应 FCL `ui_download.xml` 里的 `FCLTabLayout`）。
 *
 * FCL 那边挂在 `bg_tab_top_rounded` 上：整条一个圆角底板，
 * 选中项的文字变深并在下方画一条指示线。这里用同样的画法，
 * 只是把 Material 的 TabLayout 换成一排可点的 Column，避免引入额外依赖。
 */
@Composable
fun DownloadTabBar(selected: DownloadTab, onSelect: (DownloadTab) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Fcl.Radius),
        color = Fcl.Card
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            DownloadTab.all.forEach { tab ->
                val active = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fclClickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = tab.label,
                        color = if (active) Fcl.TextPrimary else Fcl.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    // 选中指示线：未选中时占位同高，避免整条高度跳动
                    if (active) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(Fcl.Theme)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(3.dp))
                    }
                }
            }
        }
    }
}

/**
 * 整合包 / 插件 / skill / mcp 页（版式照 FCL 的 `page_download.xml`）。
 *
 * ```
 * ┌────────────┬─────────────────────────────────────────┐
 * │ 名称        │                                         │
 * │ [搜索框]    │        尚未接入下载源（暂无数据）         │
 * │ 下载源      │                                         │
 * │ [Modrinth▾] │                                         │
 * │ 游戏版本    │                                         │
 * │ [1.21.1 ▾] │                                         │
 * │ [搜索]      │                                         │
 * └────────────┴─────────────────────────────────────────┘
 * ```
 *
 * ## 为什么右侧是空的
 *
 * 这四个页签**没有**接后端，之前用内置示例数据把列表填满，
 * 看上去像真有内容，点进去却什么都装不了 —— 反而误导人。
 * 所以示例列表、翻页条、以及「安装整合包 / 导出整合包」这些
 * 走不通的按钮全部去掉，只留左侧筛选栏（它们本身也没数据源，
 * 但保留了 FCL 的版式），右侧明确写「尚未接入」。
 *
 * 真接后端时在这里放列表即可，左侧筛选栏一行都不用动。
 */
@Composable
fun DownloadBrowseTab(
    tab: DownloadTab,
    onPickLocalFile: () -> Unit
) {
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    // 每个页签各自记住自己的筛选条件：切页签回来还是原样
    var query by remember(tab) { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 左侧筛选栏 ────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .width(if (wide) 200.dp else 148.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "名称",
                    color = Fcl.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                SearchField(value = query, placeholder = "支持中英文搜索", onChange = { query = it })

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "下载源",
                    color = Fcl.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 所有页签都用同一个索引源（dshopen），不做选择。
                // 原来整合包页还挂着 Modrinth / CurseForge 两个下拉项 ——
                // 它们既没有对应的拉取实现，也和 dsh 的插件生态无关，已按需求去掉。
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = Fcl.Track
                ) {
                    Text(
                        text = DSHOPEN_INDEX,
                        color = Fcl.TextSecondary,
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }

                // 「游戏版本 / 适配 dsh 版本」下拉已按需求去掉：
                // 这几页都还没接后端，一个不能真正筛出东西的版本选择器
                // 只会让人以为功能已经可用。
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 只留「搜索」。原先这里还有「安装整合包」—— 它背后没有下载源，
            // 点下去只能弹一句「尚未接入」，属于假按钮，已按需求去掉。
            FclFillButton(text = "搜索") {
                toast(context, "搜索「$query」（尚未接入下载源）")
            }

            // ── 导入本地（从 dsh 页签搬过来的一条）──────────────────────
            // 需求要求把它从 dsh 下载页挪到整合包下载页，所以只在整合包页签出现。
            // 对应的「导出本地」已按需求去掉：导出对普通用户没有意义，
            // 而且要遍历上万个引擎文件，卡顿还容易失败。
            if (tab == DownloadTab.MODPACK) {
                Spacer(modifier = Modifier.height(10.dp))
                FclSettingRow(
                    label = "导入本地",
                    description = "从本地 zip 安装，包结构要求与网络安装一致。",
                    shape = RowShape.SINGLE,
                    onClick = onPickLocalFile
                ) {
                    Text(text = "选择文件", color = Fcl.ThemeText, fontSize = 12.sp)
                }
            }
        }

        // ── 右侧内容区 ────────────────────────────────────────────────
        // 这块以前是一屏示例条目 + 翻页条。示例数据看着像真内容，
        // 点进去却什么也装不了，所以整块换成明确的空态。
        Column(
            modifier = Modifier.fillMaxHeight().weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "尚未接入下载源",
                color = Fcl.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when (tab) {
                    DownloadTab.MODPACK -> "整合包列表还在开发中。要装本地包，用左侧「导入本地」。"
                    DownloadTab.PLUGIN -> "插件列表还在开发中。"
                    DownloadTab.SKILL -> "skill 列表还在开发中。"
                    DownloadTab.MCP -> "mcp 列表还在开发中。"
                    DownloadTab.DSH -> "请到 dsh 下载页签安装引擎。"
                },
                color = Fcl.TextTertiary,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
    }
}

// ----------------------------------------------------------------------
// 小组件
// ----------------------------------------------------------------------

/** 下划线风格的搜索框（FCL 的 `FCLEditText` 没有边框，只有一条底线）。 */
@Composable
private fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    Column {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Fcl.TextPrimary, fontSize = 12.sp),
            cursorBrush = SolidColor(Fcl.Theme),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(text = placeholder, color = Fcl.TextTertiary, fontSize = 12.sp)
                    }
                    inner()
                }
            }
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = Fcl.Line)
    }
}

/** 主题色实心按钮（FCL 的 `FCLButton` 默认样式：填满宽度、白字、8dp 圆角）。 */
@Composable
private fun FclFillButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().fclClickable { onClick() },
        shape = RoundedCornerShape(Fcl.Radius),
        color = Fcl.Theme
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, color = Color.White, fontSize = 13.sp)
        }
    }
}

// ----------------------------------------------------------------------
// 页签默认值
// ----------------------------------------------------------------------

/**
 * 插件 / skill / mcp 的默认下载源（dshopen 的索引文件）。
 *
 * 它是一个 `index.json`：仓库方把所有可下载条目的元数据集中在这里。
 * 接入真实列表时按这个 URL 拉一次、解析出条目即可，UI 不用动。
 */
const val DSHOPEN_INDEX = "https://dshopen.org/index.json"

// ----------------------------------------------------------------------
// 预览（仅用于 IDE 里看版式，不参与运行）
// ----------------------------------------------------------------------

@androidx.compose.ui.tooling.preview.Preview(widthDp = 900, heightDp = 420)
@Composable
private fun PreviewModpack() {
    FclTheme {
        Surface(color = Fcl.Bg, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                DownloadTabBar(selected = DownloadTab.MODPACK, onSelect = {})
                DownloadBrowseTab(DownloadTab.MODPACK, onPickLocalFile = {})
            }
        }
    }
}

/** 占位页的点击反馈：明确告诉用户这里还没接功能，而不是静默无响应。 */
private fun toast(context: android.content.Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}
