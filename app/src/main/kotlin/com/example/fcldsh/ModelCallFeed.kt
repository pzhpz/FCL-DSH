package com.example.fcldsh

/**
 * 「agent 正在干什么」流水：给 [TaskOverlay] 在后台时显示的几行文字。
 *
 * ## 为什么要单独一个流水，而不是直接读日志
 *
 * 启动器日志是给排查用的，绝大多数是 npm 装机输出、OpenSSL 探测这类噪音，
 * 最后一行随时被顶掉。悬浮窗要的是**窄流**：只留有语义的条目，
 * 而且能区分「工具调用」与「模型输出」—— 前者在「调用工具」状态下显示。
 *
 * ## 数据来源（两个）
 *
 * 1. [EngineLogTap]：引擎 stdout/stderr 里认出的命令与模型输出（主力，
 *    覆盖 bash / 文件 / 检索这些引擎内部工具）。
 * 2. [A11yBridgeServer]：无障碍桥的调用（手机操作类工具，有明确的开始/结束）。
 *
 * ## 为什么条目是不可变的
 *
 * 写入方是后台线程（引擎读取线程 / HTTP 请求线程），读方是主线程上的悬浮窗轮询。
 * 可变字段跨线程读写要额外保证可见性，而这里「连续相同就累加」用「换一个新条目」
 * 实现同样简单：整个队列由 [lock] 独占，条目一旦入队就不再改动，
 * 读出去天然是一致快照。
 *
 * ## 去重
 *
 * agent 常连着读好几次屏、连着跑几条相似的命令，若每次都追加一行，
 * 真正重要的那条就被挤出窗口了。所以**连续相同**的条目只累加计数，
 * 显示成 `💬 … ×3`。
 */
object ModelCallFeed {

    /** 条目类别，决定它出现在哪个状态下。 */
    enum class Kind {
        /** 工具调用：bash 命令、读屏、点按… —— 显示在「调用工具」状态。 */
        TOOL,

        /** 模型输出：思考/生成的文字 —— 显示在「正在思考」状态。 */
        TEXT
    }

    /** 一条记录。不可变；`count` 是「连续相同」的次数。 */
    class Entry(
        val icon: String,
        val text: String,
        val ok: Boolean,
        val kind: Kind,
        val count: Int,
        val at: Long
    ) {
        /** 悬浮窗里显示的那一行，例如 `💬 正在分析界面 ×3`。 */
        fun line(): String = buildString {
            if (icon.isNotEmpty()) {
                append(icon)
                append(' ')
            }
            append(text)
            if (!ok) append(" ✗")
            if (count > 1) {
                append(" ×")
                append(count)
            }
        }
    }

    /** 最多保留多少条。悬浮窗只显示几行，留这些便于以后做「展开查看」。 */
    private const val MAX_ENTRIES = 40

    private val lock = Any()

    private val entries = ArrayDeque<Entry>()

    /**
     * 记一条。
     *
     * @param icon 前置图标（emoji），可为空
     * @param text 一句话描述，越短越好（悬浮窗宽度只有几百 px）
     * @param ok false 表示这次调用失败/未能下发
     * @param kind 工具调用还是模型输出
     */
    fun record(
        icon: String,
        text: String,
        ok: Boolean = true,
        kind: Kind = Kind.TOOL
    ) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = entries.lastOrNull()
            // 连续相同 → 换一个计数加一的新条目（条目本身不可变，见类注释）。
            // 不用中间布尔量再判 `last != null`：那样编译器会报
            // 「Condition is always 'true'」。
            if (last != null &&
                last.icon == icon && last.text == text &&
                last.ok == ok && last.kind == kind
            ) {
                entries.removeLast()
                entries.addLast(Entry(icon, text, ok, kind, last.count + 1, now))
            } else {
                entries.addLast(Entry(icon, text, ok, kind, 1, now))
            }
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /** 最近 [n] 条（任意类别），**新的在前**。 */
    fun recent(n: Int): List<Entry> = recentOf(n, null)

    /**
     * 最近 [n] 条**指定类别**，新的在前。
     *
     * 「调用工具」状态只要命令，不能把模型输出混进来；「正在思考」则取任意类别
     * 的最近一条当上下文。所以按类别过滤必须支持。
     */
    fun recentOf(n: Int, kind: Kind?): List<Entry> {
        if (n <= 0) return emptyList()
        synchronized(lock) {
            if (entries.isEmpty()) return emptyList()
            val filtered = if (kind == null) {
                entries.toList()
            } else {
                entries.filter { it.kind == kind }
            }
            return filtered.asReversed().take(n)
        }
    }

    /** 是否还没有任何记录。 */
    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    /** 清空。停止引擎时调用，避免下次启动还显示上一轮的调用。 */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
