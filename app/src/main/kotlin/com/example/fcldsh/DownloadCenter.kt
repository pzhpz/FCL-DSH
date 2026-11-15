package com.example.fcldsh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    SKILL("skill下载");

    companion object {
        /** 按显示顺序排列。不用 `entries`，兼容老一些的 Kotlin 编译器。 */
        val all: List<DownloadTab> = listOf(DSH, MODPACK, PLUGIN, SKILL)
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
 * 整合包下载页。
 *
 * ## 为什么这里只有「导入本地包」
 *
 * 这一页历史上试过两种东西，都删掉了：
 *
 * 1. **网络列表**（搜索框 + 下载源 + 游戏版本）——背后从来没有接过后端，
 *    点「搜索」只能弹一句「尚未接入」。假按钮比空页面更误导人。
 * 2. **方案（Plan）**——一段文本描述「装哪个 dsh + 哪些插件 + 哪些技能」，
 *    由启动器逐项匹配后统一下载。它的根本问题是：
 *    - 匹配要跨三个源（npm registry / 插件市场索引 / SkillHub），
 *      任一源不可达就整项跳过，用户看到的是「一片跳过」；
 *    - 装出来的实例与手动安装的不是同一条路径，profile 清单要靠猜，
 *      反复出现过「插件全部消失」「实例无法启动」；
 *    - 每次修都得重新下一遍引擎才能验证，代价太高。
 *
 * 现在回到最朴素的形态：**只保留已经稳定可用的本地包导入**。
 * 引擎从「dsh下载」页装，插件从「插件下载」页装，skill 从「skill下载」页装，
 * 各自独立、各自可验证 —— 不再有一条横跨三者的脆弱链路。
 */
@Composable
fun DownloadBrowseTab(
    onPickLocalFile: () -> Unit
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 左栏：入口 ────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .width(if (wide) 200.dp else 148.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclSettingSection("本地")
            FclSettingRow(
                label = "导入本地包",
                description = "从本地 zip 安装，包结构要求与网络安装一致。",
                shape = RowShape.SINGLE,
                onClick = onPickLocalFile
            ) {
                Text(text = "选择文件", color = Fcl.ThemeText, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // ── 右栏：说明 ────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclSettingSection("说明")

            FclSettingRow(
                label = "引擎、插件、技能分开装",
                description = "引擎在「dsh下载」页装，插件在「插件下载」页装，" +
                    "skill 在「skill下载」页装。每次只做一件事，出问题也只看一处。",
                shape = RowShape.TOP
            ) {
                Text(text = "推荐", color = Fcl.Success, fontSize = 10.sp)
            }

            FclSettingRow(
                label = "关于本地包",
                description = "本地包就是别人用「实例设置 → 导出整合包」打好的 zip，" +
                    "含引擎本体与依赖闭包。装完后到「实例设置」里切换实例即可使用。",
                shape = RowShape.MIDDLE
            ) { }

            FclSettingRow(
                label = "没有在线列表",
                description = "这一页不再从网络拉取整合包列表 —— 没有可用的索引源，" +
                    "摆一个搜不出东西的搜索框只会误导。",
                shape = RowShape.BOTTOM
            ) { }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

// ----------------------------------------------------------------------
// 小组件
// ----------------------------------------------------------------------

// `SearchField` / `FclFillButton` 已删除：它们只服务于整合包页原来那套
// 「搜索框 + 下载源」UI，那套 UI 在改成「方案」页时整块去掉了
// （网络列表从来没接过后端，搜索只能弹一句「尚未接入」）。

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

/**
 * 整合包页的版式预览。
 *
 * 高度特意取 420dp —— 这是启动器锁横屏后单块面板的近似可用高度。
 * 留这个预览是为了**在 IDE 里一眼看出**内容有没有把什么东西挤出可视区。
 */
@androidx.compose.ui.tooling.preview.Preview(widthDp = 900, heightDp = 420)
@Composable
private fun PreviewModpackPage() {
    FclTheme {
        Surface(color = Fcl.Bg, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                DownloadTabBar(selected = DownloadTab.MODPACK, onSelect = {})
                DownloadBrowseTab(onPickLocalFile = {})
            }
        }
    }
}

// `toast(context, text)` 已删除：它只被原来那套「搜索」按钮用来弹
// 「尚未接入下载源」，那套 UI 已经整块去掉。
