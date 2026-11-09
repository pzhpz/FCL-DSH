package com.example.fcldsh

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.DSHRunner
import java.io.File

/**
 * 应用内的配置文件编辑页 —— 补上 Android 上缺失的那一环。
 *
 * ## 为什么网页里的「打开配置文件」必然失败
 *
 * dsh 网页设置页有一个「打开配置文件」按钮，红字「无法打开配置文件」是它的
 * 失败回执。上游的做法是把路径交给**桌面**程序：macOS `open -t`、
 * Windows 资源管理器、Linux `xdg-open`。Android 三者皆无 —— 没有可用的
 * 目标程序，于是那条调用直接失败。
 *
 * 这不是配置坏了、也不是权限问题，**在网页侧修不了**：它不是本应用的代码，
 * 而 Android 确实没有一个「用系统默认编辑器打开这个文件」的通用约定。
 *
 * 参考项目（Deepseek-Harness-Local-Android）对这条 issue 的处理也是同一个结论：
 * 不去修网页上的按钮，而是在**应用自己**里放一个入口 —— 本页就是那个入口
 * （见 [MainActivity] 设置页的「配置文件」一节）。
 *
 * ## 为什么必须在应用内编辑
 *
 * 配置文件都在应用私有目录（`files/…`），除了本应用和 root 谁都读不到。
 * 用第三方编辑器打开需要先复制出去再拷回来，路径一变 dsh 就找不到，
 * 反而更容易把配置搞坏。所以「应用内读、应用内改、原子保存」本来就是
 * 唯一可靠的形态。
 *
 * ## 保存的原子性
 *
 * 先写同目录临时文件，再 rename 覆盖。rename 在同一文件系统内是原子的：
 * 要么全新、要么全旧。直接覆写的话，写到一半失败会留下半截 YAML ——
 * 引擎读到坏配置会触发安全模式，用户看到的是「我的插件全没了」。
 */
class ConfigEditorActivity : ComponentActivity() {

    companion object {
        /** 目标文件绝对路径；缺省表示先列出候选文件让用户挑。 */
        private const val EXTRA_PATH = "path"

        /**
         * 可直接编辑的上限。
         *
         * 1 MB 是刻意的保守值：整段文本放在一个输入框里，超过这个量级
         * 输入与滚动都会明显卡顿。更大的文件交给会话里的 AI 处理，
         * 那边有真正的流式读写工具。
         *
         * 刻意**不是** `private`：同文件的 `EditorBody`（顶层私有 Composable）
         * 也要读它，而 Kotlin 的 `private` 在 companion 里只对本类可见，
         * 顶层函数访问会编译报「Cannot access ... it is private」。
         */
        const val MAX_BYTES = 1_000_000L

        /**
         * 打开配置编辑页。
         *
         * @param path 直接打开某个文件；传 null 则展示候选列表
         */
        fun open(context: Context, path: File?) {
            context.startActivity(
                Intent(context, ConfigEditorActivity::class.java).apply {
                    if (path != null) putExtra(EXTRA_PATH, path.absolutePath)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent?.getStringExtra(EXTRA_PATH)

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                FclTheme {
                    ConfigEditorScreen(path = path, onClose = { finish() })
                }
            }
        }
        setContentView(view)
    }
}

/** 页面骨架：没选文件时是候选列表，选了就是编辑器。 */
@Composable
private fun ConfigEditorScreen(path: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val candidates = remember { configCandidates(context) }
    var selected by remember { mutableStateOf<File?>(path?.let { File(it) }) }
    val target = selected

    Box(modifier = Modifier.fillMaxSize().background(Fcl.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (target == null) "配置文件" else target.name,
                    color = Fcl.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (target == null) "关闭" else "返回",
                    color = Fcl.ThemeText,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fclClickable {
                            if (target == null) onClose() else selected = null
                        }
                        .padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (target == null) {
                CandidateList(candidates = candidates, onPick = { selected = it })
            } else {
                EditorBody(file = target)
            }
        }
    }
}

/** 候选文件列表。全部来自真实存在于磁盘上的配置，不做虚构条目。 */
@Composable
private fun CandidateList(candidates: List<File>, onPick: (File) -> Unit) {
    if (candidates.isEmpty()) {
        Text(
            text = "没找到可编辑的配置文件。\n\n" +
                "这些文件由 dsh 在首次启动时生成：先从主界面「打开」一次，让它建好 " +
                "profile 目录后再回来。",
            color = Fcl.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        candidates.forEachIndexed { i, f ->
            FclSettingRow(
                label = f.name,
                description = f.parentFile?.absolutePath,
                shape = RowShape.of(i, candidates.size),
                onClick = { onPick(f) }
            ) {
                Text(text = "编辑", color = Fcl.ThemeText, fontSize = 12.sp)
            }
        }
    }
}

/** 编辑器本体：文本区 + 保存/重新载入/复制路径。 */
@Composable
private fun EditorBody(file: File) {
    val context = LocalContext.current
    val tooBig = file.length() > ConfigEditorActivity.MAX_BYTES

    val initial = remember(file) {
        runCatching { if (tooBig) "" else file.readText() }.getOrElse { "" }
    }
    var loaded by remember(file) { mutableStateOf(initial) }
    var text by remember(file) { mutableStateOf(initial) }
    var notice by remember(file) {
        mutableStateOf(
            if (tooBig) {
                "文件超过 1 MB，未载入。请让 AI 在会话里处理这个文件。"
            } else {
                null
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = file.absolutePath,
            color = Fcl.TextTertiary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (notice != null) {
            Text(
                text = notice.orEmpty(),
                color = if (notice.orEmpty().startsWith("已保存")) Fcl.Success else Fcl.Danger,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.Card
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Fcl.RowPadding)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        notice = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = Fcl.TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(Fcl.Theme)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    tooBig -> "只读"
                    text == loaded -> "未改动"
                    else -> "已修改"
                },
                color = if (text != loaded && !tooBig) Fcl.ThemeText else Fcl.TextTertiary,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "复制路径",
                color = Fcl.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fclClickable {
                        runCatching {
                            val cm = context.getSystemService(
                                android.content.ClipboardManager::class.java
                            )
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "path",
                                    file.absolutePath
                                )
                            )
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )

            Text(
                text = "重新载入",
                color = Fcl.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fclClickable {
                        val disk = runCatching { file.readText() }.getOrNull()
                        if (disk == null) {
                            notice = "读取失败：无法读取该文件"
                        } else {
                            loaded = disk
                            text = disk
                            notice = null
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )

            Text(
                text = "保存",
                color = if (tooBig) Fcl.TextTertiary else Fcl.ThemeText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fclClickable(enabled = !tooBig) {
                        val ok = runCatching {
                            val dir = file.parentFile
                            if (dir == null) {
                                false
                            } else {
                                dir.mkdirs()
                                // 先写同目录临时文件，再 rename 覆盖：
                                // 同一文件系统内的 rename 是原子的，不会留下半截配置。
                                val tmp = File(dir, file.name + ".fcl-tmp")
                                tmp.writeText(text)
                                if (file.exists() && !file.delete()) {
                                    tmp.delete()
                                    false
                                } else {
                                    val renamed = tmp.renameTo(file)
                                    if (!renamed) tmp.delete()
                                    renamed
                                }
                            }
                        }.getOrDefault(false)

                        if (ok) {
                            loaded = text
                            notice = "已保存"
                        } else {
                            notice = "保存失败：无法写入该文件"
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * 收集磁盘上真实存在的候选配置文件。
 *
 * 覆盖 dsh 与启动器会写的那几处（顺序即重要性）：
 * - `$DSH_HOME/AGENTS.md` —— agent 每个会话都会读的主说明
 * - `$HOME/AGENTS.md` —— 其它工具链的约定位置
 * - `$DSH_HOME/profiles/<profile>/cordis.yml`（及 `cordis.yaml`、`package.json`）
 *   —— profile 的插件与补丁配置
 * - `workspace.json` —— 工作区配置（存在才列）
 */
private fun configCandidates(context: Context): List<File> {
    val names = listOf(
        "AGENTS.md",
        "cordis.yml",
        "cordis.yaml",
        "workspace.json",
        "package.json"
    )
    val seen = LinkedHashSet<String>()
    val out = ArrayList<File>(8)

    fun accept(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        for (n in names) {
            val f = File(dir, n)
            if (!f.isFile) continue
            if (seen.add(f.absolutePath)) out.add(f)
        }
    }

    // HOME 与应用私有根：两个都可能抛（例如实例数据目录还没建），
    // 任何一个失败都不该让整个列表变空。
    val dshHome = runCatching { DSHRunner.dshHome(context) }.getOrNull()
    val home = runCatching { com.appHomeDir(context) }.getOrNull()

    accept(dshHome)
    accept(home)

    for (root in listOfNotNull(dshHome, home)) {
        val docs = File(root, "Documents")
        accept(docs)
        // 工作区有一层子目录（如 Documents/deepseek-harness）
        runCatching { docs.listFiles()?.forEach { if (it.isDirectory) accept(it) } }
    }

    dshHome?.let { h ->
        runCatching {
            File(h, "profiles").listFiles()?.forEach { if (it.isDirectory) accept(it) }
        }
    }

    return out
}
