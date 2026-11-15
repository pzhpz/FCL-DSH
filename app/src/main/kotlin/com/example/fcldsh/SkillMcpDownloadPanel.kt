package com.example.fcldsh

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // ── 查询状态 ────────────────────────────────────────────────────
    //
    // 「输入框里的文字」与「真正生效的关键词」分开保存：
    // 合成一个的话，每敲一个字就要打一次上游接口（那边没有防抖的理由）。
    var input by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }

    var sort by remember { mutableStateOf(SkillHub.Sort.SCORE) }

    // ★ 页码 0 起 ★ 与 [PageBar] 保持一致（它内部显示时 +1，"第 1 页"）。
    // 只有调 SkillHub.list 那一刻才转成上游的 1 起页码。
    var page by remember { mutableStateOf(0) }

    // 跳页小窗是否打开（复用插件页的 [JumpPageDialog]）
    var jumpDialog by remember { mutableStateOf(false) }

    var items by remember { mutableStateOf<List<SkillHub.Skill>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    // ── 安装状态 ────────────────────────────────────────────────────
    var installing by remember { mutableStateOf<String?>(null) }
    var installMsg by remember { mutableStateOf("") }

    // 拉取当前页。keyword / sort / page / reload 任一变化都重跑。
    //
    // 每次都**整页替换**而不是追加：分页条是「跳到第 N 页」的语义，
    // 累加会让第 2 页显示成「第 1 页 + 第 2 页」，页码与实际内容对不上。
    LaunchedEffect(keyword, sort, page, reload) {
        loading = true
        error = null
        val want = page
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 上游页码从 1 起，本地从 0 起
            runCatching { SkillHub.list(keyword, sort, want + 1, PAGE_SIZE) }
        }
        loading = false
        result.onSuccess { p ->
            // 只在仍然是同一页时才写入：快速连点翻页时，
            // 先发出、后返回的慢请求不该覆盖后发出的结果。
            if (page == want) {
                items = p.skills
                total = p.total
            }
        }.onFailure {
            if (page == want) error = it.message ?: it.javaClass.simpleName
        }
    }

    /** 装一个技能。全程后台线程，结束回主线程更新界面。 */
    fun startInstall(s: SkillHub.Skill) {
        if (installing != null) return
        installing = s.key
        installMsg = "正在安装「${s.name}」…"
        Thread {
            val n = SkillHub.install(context, s) { line -> DSHRunner.onLog(line) }
            val msg = if (n >= 0) {
                "✅ 已安装「${s.name}」（$n 个文件），新会话可用"
            } else {
                "❌ 安装失败：${s.name}，详见运行日志"
            }
            DSHRunner.onLog(msg)
            main.post {
                installing = null
                installMsg = msg
            }
        }.start()
    }

    Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {

        // ── 左栏：搜索 / 排序 / 导入（1/3）──────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclSettingSection("搜索")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.Card
            ) {
                Column(modifier = Modifier.padding(Fcl.RowPadding)) {
                    LinkedField(
                        value = input,
                        placeholder = "关键词，如 搜索 / 编程 / 写作",
                        onChange = { input = it }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "数据来自 SkillHub（skillhub.cn）。留空即浏览全部，" +
                            "装进当前实例的 skills 目录。",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            FillButton(
                text = if (loading && page == 0) "搜索中…" else "搜索",
                enabled = !loading
            ) {
                keyword = input.trim()
                // 换关键词必须回到第一页：否则会去请求「新关键词的第 7 页」，
                // 大概率直接跳空。
                page = 0
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("排序")
            SkillHub.Sort.values().forEachIndexed { i, s ->
                FclSettingRow(
                    label = s.label,
                    shape = RowShape.of(i, SkillHub.Sort.values().size),
                    onClick = {
                        if (sort != s) {
                            sort = s
                            page = 0
                        }
                    }
                ) {
                    Text(
                        text = if (sort == s) "✓" else "",
                        color = Fcl.Success,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("本地导入")
            FclSettingRow(
                label = "从文件导入",
                description = "自己打包的 skill zip，或别人给的包（不经 SkillHub）。",
                shape = RowShape.SINGLE,
                onClick = if (installing != null) null else onImport
            ) {
                Text(text = "选择文件", color = Fcl.ThemeText, fontSize = 12.sp)
            }

            if (installMsg.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                FclSettingRow(
                    label = installMsg,
                    shape = RowShape.SINGLE
                ) {
                    if (installing != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(14.dp).height(14.dp),
                            strokeWidth = 2.dp,
                            color = Fcl.Theme
                        )
                    } else {
                        Text(
                            text = if (installMsg.startsWith("❌")) "失败" else "完成",
                            color = if (installMsg.startsWith("❌")) Fcl.Danger else Fcl.Success,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Spacer(modifier = Modifier.width(10.dp))

        // ── 右栏：市场列表（2/3）────────────────────────────────────────
        //
        // 这一栏只列**市场上有什么**，不列本机已装了什么 ——
        // 已装技能的管理归「实例设置 → skill 管理」，两处各管一件事。
        //
        // 结构照插件下载页：分页条固定在顶部，只有列表滚动。
        Column(modifier = Modifier.weight(2f).fillMaxHeight()) {
            val pageCount = ((total + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
            // 结果集变小后旧页码可能越界（换关键词、换排序都会），夹回来
            val safePage = page.coerceIn(0, pageCount - 1)
            if (safePage != page) page = safePage

            FclSettingSection("SkillHub 技能（$total）")

            // ★ 分页条固定在滚动容器之外 ★
            //
            // 放进列表里会跟着被划走：翻到第 3 屏想回第一页，得先滑回顶部。
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
                error != null -> {
                    Hint("❌ 拉取失败：$error")
                    Spacer(modifier = Modifier.height(6.dp))
                    FclSettingRow(
                        label = "重试",
                        shape = RowShape.SINGLE,
                        onClick = if (loading) null else { { reload++ } }
                    ) {
                        Text(text = "重新加载", color = Fcl.ThemeText, fontSize = 12.sp)
                    }
                }

                items.isEmpty() && loading -> {
                    Hint("正在读取 SkillHub…")
                }

                items.isEmpty() -> {
                    Hint(
                        if (keyword.isBlank()) "这个源没有返回任何技能。"
                        else "没有匹配「$keyword」的技能，换个关键词试试。"
                    )
                }

                else -> {
                    items.forEachIndexed { i, s ->
                        FclSettingRow(
                            label = s.name,
                            description = buildString {
                                // 第一行：来源与热度
                                if (s.publisher.isNotBlank()) {
                                    append(s.publisher)
                                    if (s.publisherVerified) append(" ✓")
                                    append(" · ")
                                }
                                append(fmtCount(s.downloads))
                                append(" 次下载")
                                if (s.stars > 0) {
                                    append(" · ")
                                    append(fmtCount(s.stars))
                                    append(" 星")
                                }
                                if (s.version.isNotBlank()) {
                                    append(" · v")
                                    append(s.version)
                                }
                                if (s.needsApiKey) append(" · 需 API Key")
                                // 第二段：简介
                                if (s.description.isNotBlank()) {
                                    append("\n")
                                    append(s.description)
                                }
                            },
                            descMaxLines = 3,
                            // 分页之后列表是「一页就是这几条」，圆角按本页行数收口即可
                            shape = RowShape.of(i, items.size)
                        ) {
                            when {
                                installing == s.key -> Text(
                                    text = "安装中…",
                                    color = Fcl.TextTertiary,
                                    fontSize = 10.sp
                                )
                                installing != null -> Text(
                                    text = "等待",
                                    color = Fcl.TextTertiary,
                                    fontSize = 10.sp
                                )
                                else -> Text(
                                    text = "安装",
                                    color = Fcl.ThemeText,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Fcl.Radius))
                                        .fclClickable { startInstall(s) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (i < items.size - 1) Spacer(modifier = Modifier.height(4.dp))
                    }

                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    // ── 跳页小窗（与插件下载页共用同一个组件）──────────────────────
    //
    // 页码的显示与跳转都由 [PageBar] / [JumpPageDialog] 负责，
    // 这里只把「当前页」这个状态接上。
    if (jumpDialog) {
        JumpPageDialog(
            page = page,
            pageCount = ((total + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1),
            perPage = PAGE_SIZE,
            onDismiss = { jumpDialog = false },
            onJump = { target ->
                page = target
                jumpDialog = false
            }
        )
    }
}

/** 每页条数。20 足够填满一屏，又不至于一次拉太多。 */
private const val PAGE_SIZE = 20

/**
 * 下载量 / 星数的人类可读写法。
 *
 * 上游的 `downloads` 是六到八位数（实测 `11469861`），原样显示会把一行撑爆，
 * 也不利于快速比较。中文习惯用「万」，这里只做到万位。
 */
private fun fmtCount(n: Long): String = when {
    n >= 100_000_000L -> String.format(Locale.ROOT, "%.1f 亿", n / 100_000_000.0)
    n >= 10_000L -> String.format(Locale.ROOT, "%.1f 万", n / 10_000.0)
    else -> n.toString()
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

// `sizeLabel(bytes)` 已删除：它只服务于「已安装 skill」列表，
// 而 skill 下载页改成 SkillHub 市场后不再列本机已装项，这个函数就没人用了。
// 体积格式化在 SkillHub.install 内部另有实现（日志里那一句）。
