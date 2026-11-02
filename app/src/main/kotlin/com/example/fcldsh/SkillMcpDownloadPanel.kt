package com.example.fcldsh

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.DSHRunner
import java.util.Locale

/**
 * 下载中心 · skill 下载页。
 *
 * ## 为什么不做「索引列表 + 翻页」，和插件页不一样
 *
 * 插件是 npm 包，有 registry 能列出版本、能翻页；**skill 不是**。
 * skill 的核心就是一个 `SKILL.md`（外加若干资源文件），
 * 它的生态是 GitHub 仓库与若干注册表，没有一份统一的、可翻页的版本索引。
 *
 * 与其硬套一套插件式的界面（结果只能是空的），不如给用户两条真实可用的路：
 *
 *  1. **粘链接直接装** —— GitHub 仓库、仓库子目录、`SKILL.md` 直链、zip 直链都认；
 *  2. **推荐来源** —— 点开就是浏览器里的社区合集，挑好再粘回来。
 *
 * 装到哪里、怎么装，全在 [SkillInstaller] 里，本文件只负责界面。
 */
@Composable
fun SkillDownloadPanel(onImport: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }

    // 订阅安装状态：busy 变化要禁用按钮，message 变化要显示结果
    val busy = SkillInstaller.busy
    val message = SkillInstaller.message

    // 装完后列表要刷新：用 busy 的 true→false 边沿自增一个键。
    //
    // 不能直接 `remember(busy)` —— 安装**开始**时也会重算一次扫描，
    // 而那时磁盘还没变化，白扫一遍。只在结束时刷才对。
    var refreshKey by remember { mutableStateOf(0) }
    var prevBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (prevBusy && !busy) refreshKey++
        prevBusy = busy
    }

    val dshHome = remember { DSHRunner.dshHome(context) }
    val scan = remember(refreshKey) { scanSkills(dshHome) }

    Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {

        // ── 左栏：安装入口 + 推荐来源（1/3）────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclSettingSection("从链接安装")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.Card
            ) {
                Column(modifier = Modifier.padding(Fcl.RowPadding)) {
                    LinkedField(
                        value = url,
                        placeholder = "GitHub 仓库或 SKILL.md 链接",
                        onChange = { url = it }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "支持：GitHub 仓库 / 仓库子目录 / SKILL.md 直链 / zip 直链。" +
                            "装进当前实例的 skills 目录，重启实例后生效。",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            FillButton(
                text = if (busy) "安装中…" else "安装",
                enabled = !busy && url.isNotBlank()
            ) {
                SkillInstaller.installFromUrl(context, url)
            }

            if (message.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                FclSettingRow(
                    label = message,
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
                            text = if (message.startsWith("❌")) "失败" else "完成",
                            color = if (message.startsWith("❌")) Fcl.Danger else Fcl.Success,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("导入本地包")
            FclSettingRow(
                label = "从文件导入",
                description = "在实例设置里打包好的 skill zip，或别人给的包。",
                shape = RowShape.SINGLE,
                onClick = if (busy) null else onImport
            ) {
                Text(text = "选择文件", color = Fcl.ThemeText, fontSize = 12.sp)
            }

            // ── 推荐来源：点开系统浏览器 ──
            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("推荐来源")
            val sources = listOf(
                "Anthropic 官方示例" to "https://github.com/anthropics/skills",
                "ComposioHQ 合集" to "https://github.com/ComposioHQ/awesome-claude-skills",
                "VoltAgent 合集" to "https://github.com/VoltAgent/awesome-claude-skills",
                "obra 实战技能" to "https://github.com/obra/superpowers",
                "科研方向合集" to "https://github.com/K-Dense-AI/claude-scientific-skills",
                "skills.sh 导航" to "https://skills.sh"
            )
            sources.forEachIndexed { i, (name, link) ->
                FclSettingRow(
                    label = name,
                    description = link,
                    shape = RowShape.of(i, sources.size),
                    onClick = { openUrl(context, link) }
                ) {
                    Text(text = "打开", color = Fcl.ThemeText, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Spacer(modifier = Modifier.width(10.dp))

        // ── 右栏：已安装（2/3）──────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclSettingSection("已安装 skill（${scan.items.size}）")

            if (scan.items.isEmpty()) {
                Hint(
                    "还没有 skill。可以从左侧粘一个链接安装，" +
                        "或到「实例设置 → skill 管理」导入本地包。"
                )
            } else {
                scan.items.forEachIndexed { i, s ->
                    FclSettingRow(
                        label = s.title.ifBlank { s.id },
                        description = "${s.path} · ${s.fileCount} 个文件 · " +
                            sizeLabel(s.size),
                        shape = RowShape.SINGLE
                    ) {
                        Text(text = "已装", color = Fcl.Success, fontSize = 10.sp)
                    }
                    if (i < scan.items.size - 1) Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

/**
 * 下载中心 · MCP 下载页。
 *
 * ## 为什么这一页主要是「入口」而不是「列表」
 *
 * 与 skill 同理，而且更极端：MCP 服务器是**要跑起来的东西**，
 * 它的生态由注册表与市场构成（官方 registry、聚合目录、社区目录），
 * 每个源的结构都不同，没有一份可以拉到本地翻页的统一清单。
 *
 * 所以这里给的是：**去哪找**（可点开的注册表与市场）+ **怎么装进来**
 * （导入包 / 用插件渠道装 bundle 形式的 MCP 连接器）。
 * 硬造一个假列表，用户点进去也装不了任何东西。
 */
@Composable
fun McpDownloadPanel(onImport: () -> Unit) {
    val context = LocalContext.current
    val dshHome = remember { DSHRunner.dshHome(context) }
    val scan = remember { scanMcpConfigs(dshHome) }
    val totalServers = scan.configs.sumOf { it.servers.size }

    Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {

        // ── 左栏：安装入口（1/3）────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclSettingSection("装进当前实例")

            FclSettingRow(
                label = "导入 MCP 包",
                description = "别人打包好的 MCP 配置 zip（含 bundle 形式的脚本）。",
                shape = RowShape.SINGLE,
                onClick = onImport
            ) {
                Text(text = "选择文件", color = Fcl.ThemeText, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingRow(
                label = "用插件渠道装",
                description = "bundle 形式的 MCP（cordis.patch.yml + 脚本）本质是插件，" +
                    "到「插件下载」里找对应的连接器更可靠。",
                shape = RowShape.SINGLE
            ) {
                Text(text = "说明", color = Fcl.TextTertiary, fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("统计")
            FclSettingRow(
                label = "${scan.configs.size} 个配置文件",
                description = "共 $totalServers 个已登记的服务。增删请在 dsh 或对应插件里操作。",
                shape = RowShape.SINGLE
            ) {
                Text(text = "只读", color = Fcl.TextTertiary, fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Spacer(modifier = Modifier.width(10.dp))

        // ── 右栏：去哪找 + 已装（2/3）───────────────────────────────────
        Column(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclSettingSection("注册表与市场")
            val markets = listOf(
                "官方 MCP 注册表" to "https://registry.modelcontextprotocol.io",
                "MCP.so" to "https://mcp.so",
                "ModelScope MCP 广场" to "https://modelscope.cn/mcp",
                "PulseMCP" to "https://www.pulsemcp.com",
                "Glama MCP" to "https://glama.ai/mcp",
                "官方服务器目录" to "https://github.com/VoltAgent/official-mcp-servers",
                "社区精选列表" to "https://github.com/wong2/awesome-mcp-servers"
            )
            markets.forEachIndexed { i, (name, link) ->
                FclSettingRow(
                    label = name,
                    description = link,
                    shape = RowShape.of(i, markets.size),
                    onClick = { openUrl(context, link) }
                ) {
                    Text(text = "打开", color = Fcl.ThemeText, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "⚠️ MCP 服务器会以你的权限运行。只装你信任的来源。",
                color = Fcl.Warning,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            FclSettingSection("已安装（${scan.configs.size}）")
            if (scan.configs.isEmpty()) {
                Hint(
                    "还没有 MCP 配置。装好之后会出现在这里，" +
                        "也可以在「实例设置 → mcp 管理」里查看与导出。"
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
                                append("\n没有登记任何服务")
                            } else {
                                append("\n${cfg.servers.size} 个服务")
                            }
                        },
                        shape = RowShape.SINGLE
                    ) {
                        Text(
                            text = if (cfg.bundle) "bundle" else "配置",
                            color = if (cfg.bundle) Fcl.ThemeText else Fcl.TextTertiary,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

// ----------------------------------------------------------------------
// 小工具
// ----------------------------------------------------------------------

/** 用系统浏览器打开一个链接；没有可用浏览器时静默失败（不崩）。 */
internal fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { DSHRunner.onLog("⚠️ 无法打开链接: $url") }
}

/** 一行说明文字，用于空态。 */
@Composable
private fun Hint(text: String) {
    Surface(
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

/** 下划线风格的单行输入框（与下载中心其它输入框同一种画法）。 */
@Composable
private fun LinkedField(
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
private fun FillButton(
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

/** 体积文案。 */
private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024L ->
        String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
