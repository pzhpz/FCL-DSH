package com.example.fcldsh

import android.content.Context
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.DSHRunner
import java.io.File

/**
 * 容器终端会话。
 *
 * ## ★ 这不是「网页终端」，也不是真正的 PTY ★
 *
 * 需求原话是「使用容器的终端 ui 服务，做成 web 页面面板」。本应用里**不存在**
 * 这样一个服务：容器只有 node + shell，dsh 自己的 Web 终端是 dsh 前端的一部分
 * （要走它的会话与 agent 通道），并没有独立可访问的 ttyd / wetty 之类端口。
 * 所以做成**原生面板 + 标准输入输出管道**，能力是真实可用的，但有两个边界：
 *
 * 1. **没有 PTY**：拿不到窗口尺寸、也没有终端控制序列。`vim`、`top`、`htop`
 *    这类全屏交互程序跑不起来（会刷屏或直接报 `not a tty`）。
 *    `TERM=dumb` 是如实声明，避免程序以为自己在真终端上。
 * 2. **shell 用 `/system/bin/sh`**：容器里的 `bash`/`node` 等二进制位于应用
 *    私有目录，SELinux 禁止对它们 `execve`（W^X），直接写 `$PREFIX/bin/bash`
 *    必然 `Permission denied`。要跑容器程序需要显式经 linker64 加载 ——
 *    这一点在面板顶部有说明，不要在这里假装能直接敲 `node`。
 *
 * ## 为什么还要在 `LauncherState.log` 里镜像
 *
 * 需求是「在终端能看到启动器打出的启动命令」。启动器把所有对外可见的动作
 * 都汇到 `log()`，在那里镜像一次就能全覆盖；否则每加一个操作都要记得来
 * 这里补一行，漏一处就出现「日志里有、终端里没有」。
 */
internal object TerminalSession {

    /** 输出行。SnapshotStateList：新行进来面板自动重组。 */
    val lines = mutableStateListOf<String>()

    /**
     * 命令历史（最近的在末尾，最多 [MAX_HISTORY] 条）。
     *
     * 内联输入框支持上下方向键翻历史 —— 这是「像正常终端」的一部分：
     * 行式终端没有 readline 的历史，只能由界面自己记。
     */
    val history = mutableStateListOf<String>()

    /** 会话是否在跑。真机上也用来给状态灯上色。 */
    var running by mutableStateOf(false)
        private set

    /** 保留行数。终端输出可能非常多，不设上限迟早吃内存。 */
    private const val MAX_LINES = 2000

    /** 历史条数上限。 */
    private const val MAX_HISTORY = 200

    /**
     * 会话代号。
     *
     * 每次 [start] 加一；读输出线程只在自己这一代仍然有效时才追加。
     * 没有它的话，重启会话后旧进程的收尾行会串到新会话里，
     * 用户会看到一条莫名其妙的「已退出」插在新命令中间。
     */
    private var generation = 0

    private var proc: Process? = null
    private var stdin: java.io.OutputStream? = null

    // ── 对外 ──────────────────────────────────────────────────────────

    /** 把启动器的一行日志镜像进终端（会话没开时什么都不做）。 */
    fun mirror(text: String) {
        if (!running) return
        append(generation, "── $text")
    }

    /** 会话没开就开一个（幂等）。 */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (running) return
        start(context)
    }

    /** 重启会话（清空输出）。 */
    @Synchronized
    fun restart(context: Context) {
        start(context)
    }

    /** 清空输出，不动进程。 */
    fun clear() {
        lines.clear()
    }

    /**
     * 往 shell 写一行命令。
     *
     * ## 为什么要自己回显命令
     *
     * 这不是 PTY，`sh` 的 stdin 是管道 —— **管道不是 tty，shell 不会回显输入**。
     * 所以「你敲的命令」必须由界面自己写进滚动输出里，否则命令行就凭空消失了。
     *
     * 回显前缀 `fcl$ ` 与内联输入框前面画的那个提示符**逐字一致**，
     * 这样「输入行」和「已执行行」在视觉上是同一个东西，滚动时不会跳。
     */
    fun send(command: String) {
        val w = stdin
        if (w == null) {
            append(generation, "❌ 会话未启动")
            return
        }
        val cmd = command.trimEnd()
        append(generation, PROMPT + cmd)
        // 连续重复的命令不重复记账（与 bash 的 HISTCONTROL=ignoredups 一致）
        if (cmd.isNotBlank() && history.lastOrNull() != cmd) {
            history.add(cmd)
            if (history.size > MAX_HISTORY) {
                history.removeRange(0, history.size - MAX_HISTORY)
            }
        }
        runCatching {
            w.write((command + "\n").toByteArray())
            w.flush()
        }.onFailure {
            append(generation, "❌ 写入失败: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    /**
     * 提示符文本。**唯一真源**：界面画输入行、[send] 回显、帮助文本都用它。
     * 改这里就三处一起变，不会出现「输入时是一个样子、执行后是另一个样子」。
     */
    const val PROMPT = "fcl$ "

    // ── 内部 ──────────────────────────────────────────────────────────

    @Synchronized
    private fun start(context: Context) {
        // 先把上一代会话收掉。running 置 false 让旧线程的收尾行被丢弃。
        running = false
        runCatching { stdin?.close() }
        runCatching { proc?.destroy() }
        stdin = null
        proc = null

        generation++
        val gen = generation
        lines.clear()

        val app = context.applicationContext
        val prefix = File(app.filesDir, "usr")
        val home = runCatching { DSHRunner.instanceHome(app) }
            .getOrElse { File(app.filesDir, "home") }
        runCatching { home.mkdirs() }

        // ★ 关键一步：生成 shell rc，把容器命令包装成同名函数 ★
        //
        // 没有它，用户想跑 node 就得手写
        // `LD_LIBRARY_PATH=$PREFIX/lib /system/bin/linker64 $PREFIX/bin/node`。
        // 有了它直接敲 `node -v` —— 函数由 sh 自己解释，函数体 exec 的是
        // 系统 linker（允许执行），容器二进制只是 argv，绕开 W^X。
        // 机制与本项目 targetSdk 的关系见 [SandboxExec] 的注释。
        val rc = runCatching { SandboxExec.ensureRc(prefix, home) }.getOrNull()

        append(gen, "FCL-DSH 终端 · 容器 shell（/system/bin/sh）")
        append(gen, "HOME=${home.absolutePath}")
        append(gen, "PREFIX=${prefix.absolutePath}")
        append(
            gen,
            "exec 策略: " + if (SandboxExec.execRestricted(app)) {
                "W^X 生效（targetSdk≥29），容器命令经 ${SandboxExec.linkerPath()} 加载"
            } else {
                "私有目录可直接执行（targetSdk<29）"
            }
        )
        append(gen, "")
        if (rc != null) {
            append(gen, "· 容器命令已包装为同名函数，可直接敲：node / npm / bash / rg …")
            append(gen, "· 输入 fcl-help 查看已包装的命令与用法。")
        } else {
            append(gen, "⚠️ 生成 shell 初始化脚本失败，容器命令需要手写 linker64：")
            append(gen, "    LD_LIBRARY_PATH=\$PREFIX/lib /system/bin/linker64 \$PREFIX/bin/node -v")
        }
        // pkg 服务：终端里直接敲 `pkg` 就需要它。
        // 放在这里而不是只在 startNode —— **不启动 dsh 也要能用终端装包**。
        runCatching { PkgBridge.ensure(app, prefix, { line -> append(gen, line) }) }

        append(gen, "· 无 PTY：vim / top 这类全屏程序不可用。输入 exit 结束会话。")
        append(gen, "")

        val pb = ProcessBuilder("/system/bin/sh", "-i")
        pb.directory(home)
        // stdout 与 stderr 合并：管道里两者本来就是同一条流，
        // 分开读会出现「报错和正常输出错位」的观感。
        pb.redirectErrorStream(true)

        val env = pb.environment()
        env["HOME"] = home.absolutePath
        env["PREFIX"] = prefix.absolutePath
        env["TMPDIR"] = app.cacheDir.absolutePath
        env["PATH"] = prefix.absolutePath + "/bin:/system/bin:/system/xbin:/sbin"
        // 没有 PTY 就如实声明；别写 xterm-256color 骗程序。
        env["TERM"] = "dumb"
        // 系统 sh 必须用它自己的库；继承应用的 LD_LIBRARY_PATH 会加载失败。
        env.remove("LD_LIBRARY_PATH")
        // mksh 在交互模式下会 source $ENV，函数定义由此生效。
        // 路径里没有需要展开的元字符，直接给绝对路径。
        if (rc != null) env["ENV"] = rc.absolutePath

        val p = runCatching { pb.start() }.getOrElse { e ->
            append(gen, "❌ 启动 shell 失败: ${e.message ?: e.javaClass.simpleName}")
            running = false
            return
        }

        proc = p
        stdin = p.outputStream
        running = true

        Thread {
            val reader = runCatching { p.inputStream.bufferedReader() }.getOrNull()
            if (reader != null) {
                reader.use { r ->
                    while (true) {
                        val line = runCatching { r.readLine() }.getOrNull() ?: break
                        append(gen, line.trimEnd())
                    }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (gen == generation) {
                running = false
                append(gen, "── shell 已退出（code=$code）")
            }
        }.start()
    }

    /** 追加一行；代号过期或超限时按规则处理。 */
    private fun append(gen: Int, line: String) {
        // 代号过期的行直接丢掉（见 [generation]）
        if (gen != generation) return
        lines.add(line)
        if (lines.size > MAX_LINES) {
            lines.removeRange(0, lines.size - MAX_LINES)
        }
    }
}

/**
 * 「终端」面板 —— 内联输入，像正常终端一样在光标处打字。
 *
 * 布局：顶部标题 + 状态 + 装包/清空/重启，下面是**一整块输出区**；
 * 输入行不单独占一行，而是排在输出末尾的 `fcl$ ` 提示符后面，
 * 跟着内容一起滚动、一起到底。
 *
 * ## 为什么不做成「输入框 + 执行按钮」
 *
 * 那是表单，不是终端：每敲一条命令要先把视线移到下方输入条，再挪到按钮上，
 * 与 Termux / 任何 shell 的手感都不一样。内联输入后，敲字的位置就是你
 * 「眼睛正在看的位置」，回车即执行。
 *
 * ## 为什么真的原生 PTY 还做不到
 *
 * 项目里确实有 PTY 助手（`assets/pty/libdsh_pty_exec.so`，dsh 自己的
 * Web 终端在用）。但它的输出是**带 ANSI 控制序列的原始字节流** —— 光标移动、
 * 擦除行、颜色全在里面。要在 Compose 里正确显示，必须先有一个终端模拟器
 * （字符网格 + 光标 + SGR 解析），否则 `vim` 会画成一堆转义字符，
 * 连 mksh 自己的行内编辑都会把已输入的字符叠成两遍。
 *
 * 所以这一版仍然走**行式管道**（`sh -i` + stdin/stdout），行为诚实：
 * 一行一条命令、无全屏程序、无颜色。内联输入把「像终端」的体感补上了，
 * 而 PTY + 模拟器是一个独立的、体量大得多的工程。
 *
 * 自动滚到底用 `LaunchedEffect(lines.size)`：行数变化就滚到 `maxValue`。
 */
@Composable
fun TerminalPanel() {
    val context = LocalContext.current
    val lines = TerminalSession.lines
    val running = TerminalSession.running

    var input by remember { mutableStateOf("") }
    // 历史浏览位置：-1 = 正在编辑新命令；≥0 = 正在看 history[histIdx]
    var histIdx by remember { mutableStateOf(-1) }
    // 上翻历史前暂存用户已输入的内容，翻回来时还原（与 bash 行为一致）
    var draft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }

    // 装包小窗是否打开。放在这里（而不是面板外）是因为入口就在本面板标题行 ——
    // 用户「想装点什么」的时机就是正在用终端的时候，跳到别的页签去装反而绕。
    var showPkg by remember { mutableStateOf(false) }

    /** 提交当前输入：清空编辑态、写进 shell、把焦点还给输入框。 */
    fun submit() {
        val cmd = input
        input = ""
        histIdx = -1
        draft = ""
        if (cmd.isNotBlank()) TerminalSession.send(cmd)
        // 提交后重新聚焦：点过输出区或按过软键盘「完成」后都能继续打字
        runCatching { focus.requestFocus() }
    }

    // 首次进入就把会话拉起来。ProcessBuilder.start() 会 fork，
    // 放到 IO 线程上做，别占组合期。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            TerminalSession.ensureStarted(context)
        }
        // 会话起来后把光标放进输入行，打开就能直接打字
        runCatching { focus.requestFocus() }
    }

    LaunchedEffect(lines.size) {
        // 刚组合时还没测量完，maxValue 可能是 0；失败就等下一次。
        runCatching { scroll.animateScrollTo(scroll.maxValue) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

        // ── 标题行 ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "容器终端",
                color = Fcl.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (running) "运行中" else "已停止",
                color = if (running) Fcl.Success else Fcl.TextTertiary,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            // 装包入口。放在最左边（紧跟状态文字之后、清空/重启之前）：
            // 它打开的是一个独立小窗，与右边两个「影响当前会话」的操作用途不同，
            // 分开摆放才不会在误触重开 shell 时把正在看的输出清掉。
            TerminalAction("装包") { showPkg = true }
            Spacer(modifier = Modifier.width(4.dp))
            TerminalAction("清空") { TerminalSession.clear() }
            Spacer(modifier = Modifier.width(4.dp))
            TerminalAction("重启") {
                Thread { TerminalSession.restart(context) }.start()
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── 输出区 ──────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.Card
        ) {
            // ★ SelectionContainer：长按选中 + 系统复制 ★
            //
            // 需求是「可以主动长按选择复制」，不是一键复制整个缓冲区。
            // Compose 里只有包上 `SelectionContainer` 才会有这套交互：
            // 长按任意位置 → 出现选择手柄与「复制 / 全选」浮动菜单，
            // 选中范围可以跨多行拖动手柄调整。单靠自己写按钮做不到「选一部分」。
            //
            // 注意它只能包住**同一块**可选中区域：这里整段输出是一个容器，
            // 所以能跨行连选；如果每行各自包一个，就只能一行一行复制。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(10.dp)
            ) {
                SelectionContainer {
                    Column {
                        if (lines.isEmpty()) {
                            Text(
                                text = "正在启动 shell…",
                                color = Fcl.TextTertiary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        lines.forEach { line ->
                            Text(
                                text = line,
                                color = if (line.startsWith("──")) Fcl.TextSecondary
                                else Fcl.TextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // ── 内联输入行 ──────────────────────────────────────────
                //
                // 这就是「像正常终端一样在光标处输入」的实现：提示符 + 输入框
                // 直接排在输出末尾，而不是另起一行独立输入条。
                //
                // 三个刻意的选择：
                // · **放在滚动区内部** —— 它必须跟着内容一起滚到底部，
                //   否则输出一多，输入行就和最后一行输出分家（中间空一大截）。
                // · **提示符与回显用同一个常量** `TerminalSession.PROMPT`，
                //   输入时和回车后长得一模一样，滚动时视觉不跳。
                // · **整行可点即聚焦** —— 用户习惯点终端任意位置就继续打字。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fclClickable { runCatching { focus.requestFocus() } },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = TerminalSession.PROMPT,
                        color = Fcl.ThemeText,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    BasicTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            // 手动改动后退出历史浏览态，否则方向键会跳回旧命令
                            histIdx = -1
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = Fcl.TextPrimary,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(Fcl.ThemeText),
                        // 软键盘的回车键：显示为「发送」，按下即执行
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            .focusable()
                            // 物理键盘 / 外接键盘：
                            // · Enter 执行
                            // · ↑ ↓ 翻命令历史（行式终端没有 readline，
                            //   历史只能由界面自己记，见 TerminalSession.history）
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        submit()
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        val h = TerminalSession.history
                                        if (h.isEmpty()) return@onPreviewKeyEvent true
                                        if (histIdx == -1) {
                                            draft = input
                                            histIdx = h.size - 1
                                        } else if (histIdx > 0) {
                                            histIdx--
                                        }
                                        input = h[histIdx.coerceIn(0, h.size - 1)]
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        val h = TerminalSession.history
                                        if (histIdx == -1) return@onPreviewKeyEvent true
                                        if (histIdx < h.size - 1) {
                                            histIdx++
                                            input = h[histIdx]
                                        } else {
                                            histIdx = -1
                                            input = draft
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "直接在光标处输入，回车执行；↑ ↓ 翻命令历史。" +
                "无 PTY：全屏程序（vim/top）不可用；容器二进制需经 linker64 加载。" +
                "启动器日志以「──」前缀同步到这里。",
            color = Fcl.TextTertiary,
            fontSize = 9.sp,
            lineHeight = 13.sp
        )
    }

    // 装包小窗。Dialog 自己是一个独立窗口，放在 Column 外面（而不是里面）
    // 才不会参与终端的布局测量 —— 它不占位，只是叠在上层。
    if (showPkg) {
        PackageInstallDialog(onDismiss = { showPkg = false })
    }
}

/** 终端面板上的一个小文字按钮（清空 / 重启）。 */
@Composable
private fun TerminalAction(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Fcl.Radius),
        color = Fcl.Track,
        modifier = Modifier.fclClickable { onClick() }
    ) {
        Text(
            text = text,
            color = Fcl.ThemeText,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
