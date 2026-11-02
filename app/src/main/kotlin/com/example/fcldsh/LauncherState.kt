package com.example.fcldsh

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.DSHRunner

/** 实例/进程的运行状态。 */
enum class RunState(val label: String) {
    STOPPED("已停止"),
    STARTING("启动中"),
    RUNNING("运行中"),
    ERROR("异常");

    /** 进程还活着（含正在拉起）。 */
    val isAlive: Boolean get() = this == STARTING || this == RUNNING
}

/**
 * 侧栏可以展开的面板。
 *
 * 同一时刻只会有一个：点另一个图标就直接顶掉当前的。
 * [NONE] 表示收回面板、回到主界面。
 */
enum class LauncherPanel(val title: String) {
    NONE(""),
    INSTANCE("实例设置"),
    DOWNLOAD("下载中心"),
    SETTINGS("设置"),
    LOG("运行日志"),
    ACCOUNT("用户")
}

/**
 * 启动器的全局 UI 状态。
 *
 * 放在 Activity 之外（由 [AppState] 持有），因为写它的人有三方：
 * 1. DSHRunner 的后台线程回调（onLog / onUrlReady）
 * 2. ActivityResult 的回调（选 zip）
 * 3. Compose 的 UI 事件
 * 三者要共享同一份状态，remember 生存在组合树里，够不着 1 和 2。
 */
class LauncherState {

    var runState by mutableStateOf(RunState.STOPPED)

    /** 实例名，显示在启动卡片上。 */
    var instanceName by mutableStateOf("dsh 0.2.1-rc.2")

    /**
     * 当前使用中的引擎版本号（以磁盘为准），未安装为 null。
     *
     * ## 为什么放在这里而不是各界面自己记
     *
     * 主页的版本卡片和「版本管理」弹窗显示的是同一个事实。之前两处各自
     * `remember { DSHRunner.installedDshVersion(...) }`，只在安装完成时刷新 ——
     * 于是在版本管理里切换了版本，主页卡片仍停在旧值，看上去「版本不会变」。
     *
     * 收口成一个共享状态后，任何地方改了指针都调 [refreshEngineVersion]，
     * 所有订阅者一起重组，不会再出现两处不一致。
     */
    var engineVersion by mutableStateOf<String?>(null)

    /** 从磁盘重读当前版本，并通知所有读取它的界面重组。 */
    fun refreshEngineVersion(context: Context) {
        engineVersion = DSHRunner.currentVersion(context)
    }

    /**
     * dsh 打印的带 token 地址；不为 null 就说明可以打开网页窗口了。
     *
     * ## ★ 不要再往这里加「换令牌」相关的状态 ★
     *
     * 先后试过两版，都被实测否掉了，记录在此免得再走一遍：
     *
     * **第一版**：推断「token 一次性」，发现地址被内置窗口用过就**自动重启**
     * 换新地址。代价是中断 agent 正在跑的任务、内置窗口失效。
     *
     * **第二版**：改成用户显式点击「重启后打开」才重启。日志证明它同样无效：
     *
     * ```
     * 68: dsh web: http://127.0.0.1:3081/?token=2NMXLdgq...
     * 70: 🌐 浏览器地址：http://127.0.0.1:3081/?token=2NMX…BZ3I
     * ```
     *
     * 这是**全新令牌**、端口也换了、内置窗口这次没有抢先打开（日志里没有
     * `📱` 那行），浏览器仍然报 authentication required。
     *
     * 结论：**令牌不是问题所在。** 失败点更可能在浏览器侧的 Cookie ——
     * dsh 的会话 Cookie 是 HttpOnly + SameSite=Strict，且**不区分端口**，
     * 前几轮在 `127.0.0.1:3080` 留下的旧 Cookie 会被带到 3081，
     * 服务端见到坏 Cookie 就回 401（它把多种失败合并成同一句、也不写日志）。
     *
     * 下一步应是「验证是否是 Cookie 问题」（无痕 / 清 Cookie 手动粘贴），
     * 而不是在令牌上继续做文章。若确认是「从外部应用发起导致 Cookie 丢失」，
     * 才需要上回环反代替浏览器附带 Cookie —— 那是另一个量级的改动。
     */
    var url by mutableStateOf<String?>(null)

    /**
     * 当前展开的面板。
     *
     * 用一个枚举而不是三个 boolean：点侧栏另一个图标时**直接顶掉**当前面板，
     * 不需要先关再开。早期用 showSettings / showDownload 各自为政，
     * 会出现两个面板叠在一起的问题。
     *
     * 存这里而不是 Compose 的 remember：面板由**原生侧栏**触发，
     * 而原生 View 够不到 Compose 的组合树，只能通过共享状态传递。
     */
    var panel by mutableStateOf(LauncherPanel.NONE)

    /** 是否有面板展开。侧栏「退出」据此决定是收回面板还是退出应用。 */
    val hasPanel: Boolean get() = panel != LauncherPanel.NONE

    /** 打开（或切换到）某个面板。已在同一面板时也保持展开。 */
    fun openPanel(target: LauncherPanel) {
        panel = target
    }

    /** 收回面板，回到主界面。 */
    fun closePanel() {
        panel = LauncherPanel.NONE
    }

    /**
     * 实例设置面板当前选中的页签。
     *
     * 放在这里而不是面板里的 `remember`：面板被侧栏顶掉时整棵子树会被卸载，
     * `remember` 跟着丢掉，再打开就跳回第一个页签。用户在看「会话管理」时
     * 去下载中心取个包再回来，落回 dsh 设置会让人以为点错了。
     */
    var instanceTab by mutableStateOf(InstanceTab.DSH)

    /**
     * 正在删除的版本号；null 表示没有删除任务在进行。
     *
     * 删除一个版本要递归删掉上万个文件（node_modules），耗时几秒到几十秒。
     * 用共享状态而不是面板内的 `remember`：删除期间用户可能切到别的面板，
     * 切回来时进度条必须还在，不能凭空重置成一个「没在删」的样子。
     */
    var deletingVersion by mutableStateOf<String?>(null)

    /** 删除进度 0f..1f。 */
    var deleteProgress by mutableStateOf(0f)

    /**
     * 版本管理小窗是否展开。
     *
     * 它是从「管理版本」按钮弹出的 Dialog，不走侧栏，所以与 [panel] 独立 ——
     * 弹窗叠在任意主界面之上都是合理的。
     */
    var showVersions by mutableStateOf(false)

    /**
     * 完整日志历史。用 SnapshotStateList 而不是普通 MutableList，
     * 这样日志面板能直接订阅到新行，不用手动刷新。
     */
    val logs = mutableStateListOf<String>()

    /** 侧栏/卡片上那一行摘要用的。 */
    val lastLog: String get() = logs.lastOrNull() ?: "准备启动…"

    fun log(msg: String) {
        logs.add(msg)
        // Node 的启动输出很啰嗦，不设上限迟早吃内存
        if (logs.size > MAX_LOG_LINES) {
            logs.removeRange(0, logs.size - MAX_LOG_LINES)
        }
    }

    /** 日志面板的「清空」：只清 UI 侧记录，不影响已 fork 的 Node 进程。 */
    fun clearLogs() {
        logs.clear()
    }

    /** 拼成可复制的纯文本。带序号便于对照截图里的行号。 */
    fun logsAsText(): String =
        if (logs.isEmpty()) "" else logs.mapIndexed { i, l -> "${i + 1}: $l" }.joinToString("\n")

    /** 停止后回到"未启动"形态。 */
    fun reset() {
        url = null
        runState = RunState.STOPPED
    }

    private companion object {
        const val MAX_LOG_LINES = 500
    }
}
