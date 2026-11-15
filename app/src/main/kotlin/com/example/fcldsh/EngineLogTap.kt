package com.example.fcldsh

/**
 * 把 dsh 引擎的 stdout/stderr 行翻译成「模型在干什么」，喂给 [AgentActivity]
 * 与 [ModelCallFeed]。
 *
 * ## 为什么必须从引擎日志里看
 *
 * dsh 的 agent 跑在 Node 进程里，它调用的绝大多数工具（bash、读写文件、检索）
 * 都是**引擎内部**的工具，不经过本机的无障碍桥。所以只靠 [A11yBridgeServer]
 * 记流水，真机上会永远显示「暂无输出」—— 这个 bug 就是这么来的。
 *
 * 引擎唯一的对外通道就是它打印到 stdout/stderr 的文字，这也正是用户想看的
 * 「AI 正在调用什么、命令是什么」。
 *
 * ## 为什么要做「识别」而不是把日志原样贴上去
 *
 * 引擎输出里混着 HTTP 请求、启动横幅、堆栈等噪音。原样贴进 240dp 的悬浮卡片，
 * 显示的多半是 `GET /api/... 200`。所以这里只挑两类：
 *
 * | 类别 | 判据 | 去向 |
 * |---|---|---|
 * | 工具调用 | 命令提示符 `$ cmd`、`bash -lc "…"`、JSON 里的 `"command": "…"` | TOOL 状态 + 流水（🔧） |
 * | 模型输出 | 其它有实质内容的行 | THINKING 心跳 + 流水（💬） |
 * | 噪音 | 空行、纯符号、HTTP 访问日志、堆栈的 `at …` 行 | 丢弃 |
 *
 * 识别用的是**宽松的多模式匹配**，不是替 dsh 定义协议：匹配不上命令就退化成
 * 「模型输出」，只会让状态显示成「正在思考」，不会显示错内容。
 *
 * ## 线程
 *
 * 由 [com.DSHRunner] 的 stdout/stderr 读取线程直接调用，**不是主线程**。
 * 这里只写 [ModelCallFeed]（内部加锁）与 [AgentActivity]（内部加锁），
 * 不碰任何 View，所以安全。
 */
object EngineLogTap {

    /** 一次分类结果。 */
    private enum class Kind { TOOL, TEXT, NOISE }

    /** 命令提示符开头：`$ ls` / `❯ npm i` / `> node`。 */
    private val PROMPT = Regex("""^\s*[\$❯>]\s+(\S.*)$""")

    /** `bash -lc "…"` / `sh -c '…'`。 */
    private val SHELL_C = Regex("""\b(?:ba|z|da)?sh\s+-[a-z]*c\s+(.+)$""")

    /** JSON 字段：`"command":"…"` / `"cmd": "…"`。 */
    private val JSON_COMMAND = Regex(""""command"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /** 工具名：`"name":"bash"` / `tool=bash` / `run_command`。 */
    private val TOOL_NAME = Regex(
        """"(?:tool|name)"\s*:\s*"([A-Za-z0-9_.\-]{2,40})"|""" +
            """\b(?:tool|cmd|command)\s*[=:]\s*([A-Za-z0-9_.\-]{2,40})"""
    )

    /** HTTP 访问日志 / 心跳，整行丢弃。 */
    private val HTTP_NOISE = Regex("""^\s*(?:GET|POST|PUT|PATCH|DELETE|HEAD)\s+/\S*""")

    /** 堆栈行 `    at foo (bar.js:1:1)`。 */
    private val STACK_NOISE = Regex("""^\s*at\s+\S+""")

    /** 纯符号 / 分隔线。 */
    private val SYMBOLS_ONLY = Regex("""^[\s\-=_*~•·.>#|]+$""")

    /**
     * 引擎启动阶段的固定噪音。
     *
     * ★ 这一条是「刚启动就显示正在思考」的最后一道闸 ★
     *
     * 引擎起来的头几十行全是这类内容（端口、URL、插件、npm、OpenSSL…）。
     * 它们既不是命令也不是模型说的话，混进 [ModelCallFeed] 会让流水一开始
     * 就被垃圾填满，真正的命令还没来得及记录就被挤出窗口。
     */
    private val STARTUP_NOISE = Regex(
        """(?i)(?:""" +
            """dsh web|listening|server (?:started|listening|running)|""" +
            """localhost|127\.0\.0\.1|0\.0\.0\.0|https?://|port\s*\d|:\d{4,5}\b|""" +
            """install(?:ing|ed)?\b|npm |pnpm |node\b|resolv|plugin|""" +
            """config|workspace|loading|loaded|ready\b|version|""" +
            """openssl|sandbox|permission|token|api[ _-]?key|""" +
            """warn(?:ing)?|info:|debug:|trace:""" +
            """)"""
    )

    /** 日志行前缀：`[plugin]`、`INFO:`、`2024-01-01 12:00:00`。 */
    private val LOG_PREFIX = Regex(
        """^\s*(?:\[[^\]]{1,24}\]|[A-Z]{2,8}\b\s*[:|]|\d{4}-\d{2}-\d{2}|[A-Za-z]{3,9}\s+\d{1,2}[:\s])"""
    )

    /** 含中日韩字符 = 大概率是人话（模型输出），不是机器日志。 */
    private val CJK = Regex("""[\u4e00-\u9fff]""")

    /** 喂一行引擎输出。可任意线程调用。 */
    fun onLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return

        when (classify(line)) {
            Kind.NOISE -> return

            Kind.TOOL -> {
                val cmd = commandOf(line)
                AgentActivity.onToolCall()
                ModelCallFeed.record(
                    icon = "🔧",
                    text = if (cmd.isEmpty()) clip(line) else clip(cmd),
                    ok = true,
                    kind = ModelCallFeed.Kind.TOOL
                )
            }

            Kind.TEXT -> {
                AgentActivity.onModelOutput()
                ModelCallFeed.record(
                    icon = "💬",
                    text = clip(line),
                    ok = true,
                    kind = ModelCallFeed.Kind.TEXT
                )
            }
        }
    }

    /**
     * 分类。
     *
     * 顺序很重要：先判噪音（不然 HTTP 日志会被当成模型输出，状态一直在
     * 「正在思考」），再判命令（`$ bash -lc …` 这种既像提示符又像 shell 调用，
     * 走哪条都归 TOOL）。
     */
    private fun classify(line: String): Kind {
        // ── 第一层：明确的噪音 ──────────────────────────────────────
        if (SYMBOLS_ONLY.matches(line)) return Kind.NOISE
        if (HTTP_NOISE.containsMatchIn(line)) return Kind.NOISE
        if (STACK_NOISE.containsMatchIn(line)) return Kind.NOISE
        if (line.length < 4) return Kind.NOISE

        // ── 第二层：命令（必须在噪音判断之后，但要在 STARTUP_NOISE 之前）──
        //
        // 顺序有讲究：真实命令里常带 `http://`（curl）、`npm`（npm install）
        // 这类词，先跑 STARTUP_NOISE 会把它们误杀成噪音，那才是真要显示的内容。
        val looksLikeCommand = PROMPT.containsMatchIn(line) ||
            SHELL_C.containsMatchIn(line) ||
            JSON_COMMAND.containsMatchIn(line)
        if (looksLikeCommand) return Kind.TOOL

        // ── 第三层：启动噪音 / 带日志前缀的行 ────────────────────────
        //
        // ★ 这里是「刚启动就显示正在思考」的根源 ★
        // 早先只过滤了 HTTP 访问日志，剩下的启动行（端口、插件加载、OpenSSL…）
        // 全被判成「模型输出」，于是引擎一起来状态就跳到 THINKING。
        if (STARTUP_NOISE.containsMatchIn(line)) return Kind.NOISE
        if (LOG_PREFIX.containsMatchIn(line)) return Kind.NOISE

        // 只有工具名、没有命令体（`tool=bash`）：算一次调用，
        // 让用户知道「它在动」，具体参数显示不出来就显示工具名。
        if (TOOL_NAME.containsMatchIn(line)) return Kind.TOOL

        // ── 第四层：只剩「像人话的行」才当模型输出 ──────────────────
        //
        // 含中文 → 基本可以确定是模型/界面文案，不是机器日志；
        // 纯英文则要求有多个空格分隔的词（一句英文至少 3 个），
        // 单 token 的行（`starting`、`ok`）一律算噪音。
        if (CJK.containsMatchIn(line)) return Kind.TEXT
        if (line.split(' ').count { it.isNotBlank() } >= 4) return Kind.TEXT

        return Kind.NOISE
    }

    /** 从命中行里抽出命令本体，抽不出来就返回空串（由调用方退回整行）。 */
    private fun commandOf(line: String): String {
        JSON_COMMAND.find(line)?.let { m ->
            val v = unescape(m.groupValues[1])
            if (v.isNotBlank()) return v
        }
        SHELL_C.find(line)?.let { m ->
            val v = unescape(m.groupValues[1]).trim().trim('"', '\'')
            if (v.isNotBlank()) return v
        }
        PROMPT.find(line)?.let { m ->
            val v = m.groupValues[1]
            if (v.isNotBlank()) return v
        }
        return ""
    }

    /** JSON 字符串里的 `\n` / `\"` 还原成可读文本。 */
    private fun unescape(s: String): String = s
        .replace("\\n", " ")
        .replace("\\r", " ")
        .replace("\\t", " ")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    /** 压成一行并截断。悬浮窗一行装不下 200 个字符的命令。 */
    private fun clip(s: String): String {
        val flat = s.replace(Regex("""\s+"""), " ").trim()
        val max = 80
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }
}
