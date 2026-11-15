package com.example.fcldsh

import android.os.SystemClock

/**
 * dsh agent 当前处于哪种状态。任务悬浮窗据此决定显示什么。
 *
 * 三态刻意做成枚举而不是若干 boolean：agent 任何时刻只可能处于其中一种，
 * 用 `isThinking` / `isCallingTool` 两个布尔会出现「两个都为 true」的
 * 非法组合，显示端就得再写一遍优先级判断。
 */
enum class AgentPhase(val label: String) {
    /** 引擎在跑，但本次会话还没有任何工具调用。 */
    IDLE("暂无输出"),

    /** 刚执行完一次工具调用，引擎又吐出了新内容 —— 模型在据此推理。 */
    THINKING("正在思考"),

    /** 正在执行一次工具调用（bash 命令、读屏、点按…）。悬浮窗显示具体命令。 */
    TOOL("调用工具")
}

/**
 * agent 状态机。
 *
 * ## ★ 判定规则（先说清「为什么以前会误报正在思考」）★
 *
 * 上一版把**任何一行引擎输出**都当成「模型在输出」，于是：
 *
 * ```
 * 启动引擎 → 打印几十行（运行时解压、npm、端口、dsh web 地址、插件加载…）
 *          → 每一行都刷新了「活动时间」→ phase() 说 THINKING
 * ```
 *
 * 结果就是用户看到的「刚启动、还没跟模型说任何话，悬浮窗就转圈说正在思考」。
 * 根因是**信号太弱**：引擎启动日志和模型输出在同一根 stdout 上，光凭
 * 「有输出」无法区分。
 *
 * 现在只有**两种**能让状态离开「暂无输出」的事实：
 *
 * | 事实 | 来源 | 状态 |
 * |---|---|---|
 * | 无障碍桥有请求在飞 | [beginTool] / [endTool] | TOOL |
 * | 日志里认出一次命令 | [onToolCall] | TOOL（保持 [TOOL_HOLD_MS]） |
 * | 工具调用之后引擎又吐出内容 | [onModelOutput] | THINKING（[THINK_WINDOW_MS] 内） |
 *
 * 关键点：**THINKING 的前提是 `lastToolAt != 0`** —— 一次工具调用都没发生过，
 * 怎么打印都不可能是「模型在思考」。启动阶段因此一定是「暂无输出」。
 *
 * ## 为什么是「工具调用之后」才算思考
 *
 * 我们看不到模型本身（它在服务端 Node 进程里），能观测到的只有它**动手**的证据。
 * 「刚动完手又在输出」是唯一有意义的推论点：它拿到了工具结果，正在读。
 * 反过来「没动过手但在输出」几乎一定是引擎自己的日志，推成「思考」就是误报。
 *
 * ## 阶段是算出来的，不是存起来的
 *
 * 没有定时器、没有后台线程改状态。每次 [phase] 按时间戳现算：
 * 不需要谁推进状态机，进程再卡也不会残留错状态；悬浮窗本来就在低频轮询
 * （见 [TaskOverlay]），天然是唯一读取方。
 */
object AgentActivity {

    /** 认出工具调用后继续保持「调用工具」的时长，让人看清命令。 */
    private const val TOOL_HOLD_MS = 3_000L

    /**
     * 工具调用之后，引擎再输出内容 → 认为模型在思考，持续这么久。
     *
     * 取 20 秒：模型读工具结果通常几秒内就发起下一次调用，超出这个窗口
     * 还没有新动作，说明它其实在等用户输入，继续转圈就是撒谎。
     */
    private const val THINK_WINDOW_MS = 20_000L

    private val lock = Any()

    /** 正在飞的桥请求数。用计数而不是布尔：并发请求不该被后一个覆盖成 0。 */
    private var inFlight = 0

    /**
     * 最近一次「工具调用」的时刻（[SystemClock.elapsedRealtime]）。
     *
     * 0 表示**本次会话一次工具调用都还没有** —— 这是「暂无输出」的判据之一。
     */
    private var lastToolAt = 0L

    /** 最近一次工具调用**之后**的输出时刻。0 表示还没有。 */
    private var lastOutputAt = 0L

    /** 一次工具调用开始（无障碍桥）。 */
    fun beginTool() {
        synchronized(lock) { inFlight++ }
    }

    /** 一次工具调用结束（无障碍桥），必须在 `finally` 里调。 */
    fun endTool() {
        synchronized(lock) {
            if (inFlight > 0) inFlight--
            lastToolAt = SystemClock.elapsedRealtime()
        }
    }

    /** 引擎日志里认出了一次工具调用（bash 命令等）。 */
    fun onToolCall() {
        synchronized(lock) { lastToolAt = SystemClock.elapsedRealtime() }
    }

    /**
     * 引擎日志里出现了疑似模型输出。
     *
     * 只记时间，**不在这里判状态**：是否算「思考」取决于之前有没有工具调用，
     * 那是 [phase] 的事。这样即使 [EngineLogTap] 漏判了噪音，
     * 也不会在启动阶段把状态推成「正在思考」。
     */
    fun onModelOutput() {
        synchronized(lock) { lastOutputAt = SystemClock.elapsedRealtime() }
    }

    /** 引擎停止 / 启动时复位。 */
    fun reset() {
        synchronized(lock) {
            inFlight = 0
            lastToolAt = 0L
            lastOutputAt = 0L
        }
    }

    /** 当前阶段。按时间戳现算，可任意线程调用。 */
    fun phase(): AgentPhase = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        when {
            // 桥请求在飞 —— 明确的「正在执行」
            inFlight > 0 -> AgentPhase.TOOL

            // 本次会话还没动过手 → 一定是「暂无输出」。
            // 启动日志再多也不能让它变成「正在思考」（见类注释）。
            lastToolAt == 0L -> AgentPhase.IDLE

            // 刚认出一次命令，多留一会儿让人看清
            now - lastToolAt < TOOL_HOLD_MS -> AgentPhase.TOOL

            // 工具调用之后又吐出内容，且还不算太久 → 它在读结果、想下一步
            lastOutputAt > lastToolAt &&
                now - lastToolAt < THINK_WINDOW_MS -> AgentPhase.THINKING

            // 彻底安静
            else -> AgentPhase.IDLE
        }
    }
}
