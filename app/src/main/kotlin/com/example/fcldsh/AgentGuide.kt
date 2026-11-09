package com.example.fcldsh

import android.content.Context
import com.DSHRunner
import android.util.Log
import java.io.File

/**
 * 让 dsh 的 agent 在**第一次对话**就知道本机有「手机控制」能力。
 *
 * ## 要解决的问题
 *
 * [A11yBridgeServer] 已经把读屏与点击暴露在 `127.0.0.1` 上，但 agent 不知道它存在。
 * 上一轮是靠人工把说明粘进对话才跑通的 —— 那不可持续：每次新会话、
 * 每换一个人用，都得重新解释一遍。
 *
 * 正确的做法是**把说明写进 agent 每次启动都会读的指令文件**。
 * 这样它一开口就已经知道：本机有个桥、怎么拿凭据、有哪些端点、哪些做不到。
 *
 * ## 写到哪里
 *
 * 三个位置，按「agent 最可能读到」排序取名：
 *
 * 1. `~/AGENTS.md`（即 `files/home/AGENTS.md`）—— 主入口。
 *    AGENTS.md 是各类 agent 工具链通用的「项目/环境说明」约定名。
 * 2. `<工作区>/AGENTS.md`（`~/Documents/deepseek-harness/AGENTS.md`）——
 *    工作区级约定，也是 cwd 所在。
 * 3. `~/a11y-bridge.json` / `~/a11y-bridge.md` —— 结构化凭据与可直接抄的命令。
 *
 * ## 为什么用「受管区块」而不是整文件覆写
 *
 * `~/AGENTS.md` 可能已经有用户自己写的内容（或用户以后手动补的内容）。
 * 整文件覆写会把它抹掉。所以用一对标记把我们的段落圈起来：
 *
 * ```
 * <!-- fcl-dsh:a11y-begin -->
 * …
 * <!-- fcl-dsh:a11y-end -->
 * ```
 *
 * 每次启动只替换标记之间的部分，标记外的内容原样保留。
 * 这样端口/token 变化时能刷新，而用户写的东西不会丢。
 *
 * ## 工作区那份只在「文件不存在」时创建
 *
 * 工作区属于用户的项目目录。若那里已经有 AGENTS.md，那是用户自己的文件，
 * 往里面塞我们的段落属于擅自改别人的仓库 —— 所以只在缺失时创建，
 * 已存在则跳过并记一条日志。
 */
object AgentGuide {

    private const val TAG = "AgentGuide"

    private const val BEGIN = "<!-- fcl-dsh:a11y-begin -->"
    private const val END = "<!-- fcl-dsh:a11y-end -->"

    /** agent 指令文件名。 */
    private const val AGENTS = "AGENTS.md"

    /**
     * 刷新全部文档。幂等，可反复调用。
     *
     * 在桥接启动后调用（[A11yBridgeServer.writeDiscovery]），
     * 也在 dsh 启动前调用一次（[com.DSHRunner.startNode]）——
     * 后者保证工作区目录已经存在时也能及时补上说明。
     *
     * ## ★ 主入口必须是 `$DSH_HOME/AGENTS.md`，不是 `$HOME/AGENTS.md` ★
     *
     * 这是本机制最关键的一点，之前写错位置导致**完全没生效**。
     *
     * 参考实现（Deepseek-Harness-Local-Android 的 `AgentContextSeed`）的注释写得很明确：
     * ```
     * dsh-agent-instructions 原生发现 $DSH_HOME/AGENTS.md
     * （user-global 层，无条件注入每个会话）
     * 引擎只加载 $DSH_HOME/AGENTS.md 这一个 user-global 指令文件，
     * 不会加载同目录下的其它文件名
     * ```
     *
     * 而本项目里 `DSH_HOME` = `home/.dsh-<版本>`（见 [com.DSHRunner.dshHome]），
     * **不等于** `$HOME`（`files/home`）。所以旧版写到 `$HOME/AGENTS.md`
     * 等于扔进一个 dsh 永远不会读的目录 —— agent 只能靠自己一轮轮探测环境，
     * 也就出现了「它反复试 screencap / screenrecord / dumpsys」的现象。
     */
    fun refresh(context: Context) {
        val app = context.applicationContext

        // ★ 真正的 user-global 指令文件所在目录
        val dshHome = DSHRunner.dshHome(app)
        // 旧的错误位置：保留一份，因为别的 agent 工具链可能按 $HOME 约定找
        val home = com.appHomeDir(app)
        val workspace = workspacePackageDir(app)

        val body = buildBlock(app)

        // 1) $DSH_HOME/AGENTS.md —— 主入口，dsh 无条件注入每个会话
        runCatching {
            dshHome.mkdirs()
            upsert(File(dshHome, AGENTS), body)
        }.onFailure { Log.w(TAG, "写 DSH_HOME/$AGENTS 失败: ${it.message}") }

        // 2) $HOME/AGENTS.md —— 兼容其它 agent 工具链的约定（dsh 不读这里）
        runCatching {
            home.mkdirs()
            upsert(File(home, AGENTS), body)
        }.onFailure { Log.w(TAG, "写 home/$AGENTS 失败: ${it.message}") }

        // 3) 工作区 AGENTS.md：仅在不存在时创建，绝不改动用户已有内容
        runCatching {
            val wsAgents = File(workspace, AGENTS)
            if (wsAgents.exists()) {
                Log.i(TAG, "工作区已有 $AGENTS，跳过（不覆盖用户文件）")
            } else {
                workspace.mkdirs()
                wsAgents.writeText(body + "\n")
            }
        }.onFailure { Log.w(TAG, "写工作区 $AGENTS 失败: ${it.message}") }
    }

    /**
     * dsh 的默认工作区：`<workspaceDir>/deepseek-harness`。
     *
     * dsh 把首次默认工作区固定放在 Documents 下的这个名字里
     * （见 [com.DSHRunner.startNode] 里关于 xdg-user-dir 的注释）。
     */
    private fun workspacePackageDir(context: Context): File =
        File(DSHRunner.workspaceDir(context), "deepseek-harness")

    /** 把受管区块写入（或替换）到文件里，标记外的内容原样保留。 */
    private fun upsert(file: File, block: String) {
        val managed = "$BEGIN\n$block\n$END"
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(managed + "\n")
            return
        }
        val old = runCatching { file.readText() }.getOrNull() ?: return
        val start = old.indexOf(BEGIN)
        val end = old.indexOf(END)
        val updated = if (start >= 0 && end > start) {
            old.substring(0, start) + managed + old.substring(end + END.length)
        } else {
            // 没有标记：追加到末尾，保留原内容
            if (old.endsWith("\n") || old.isEmpty()) "$old$managed\n" else "$old\n\n$managed\n"
        }
        if (updated != old) file.writeText(updated)
    }

    /**
     * 受管区块正文。
     *
     * 语气按「给 agent 的操作手册」写：先说有什么、再说怎么连、
     * 然后列出端点与限制。刻意把「做不到什么」写清楚 ——
     * 否则 agent 会反复尝试截图或 shell 级命令，浪费轮次。
     */
    private fun buildBlock(context: Context): String {
        // 只取端口（用于文档里提一句「走的是哪个端口」）。
        //
        // 刻意**不把 token 写进 AGENTS.md**：鉴权已由 `a11y` 命令内部完成，
        // agent 不需要看到它。写进一份会被注入每个会话的文档里，
        // 等于把「读屏 + 代替用户点击」的钥匙抄进对话上下文 —— 没有必要且危险。
        val port = A11yBridgeServer.port
        val running = A11yBridgeServer.isRunning() && port > 0

        // 运行权限：让 agent 一开口就知道「自己现在是什么身份、能做什么」。
        // 这一点比它看起来重要 —— 不知道身份的模型会去试 `su`、或者反过来
        // 在 root 模式下小心翼翼地绕路，两种情况都是纯浪费。
        val priv = privilegeSection(context)

        // 「跨应用 shell」这一条要跟着运行权限走。
        //
        // 写死成「不可用」的话，Shizuku / Root 模式下 agent 明明有能力也不敢用，
        // 只能绕远路靠 `a11y` 一个个点；反过来在普通模式下写「可以用」，
        // 它会去调 `input tap` 然后被 SELinux 拒绝，白费几轮。
        val mode = Privilege.getMode(context)
        val crossAppShell = when {
            (mode == PrivMode.ROOT && Privilege.hasKnownRoot()) ||
                (mode == PrivMode.SHIZUKU && Privilege.shizukuModeSelectable()) ->
                "**跨应用 shell**：本模式有能力，用 `shz input tap ...`、" +
                    "`shz am start ...` 执行（Root 模式下直接去掉 `shz` 前缀）。"
            else ->
                "**跨应用任意 shell 操作**：`input tap`、`am start` 等需要 shell 权限，" +
                    "不可用。替代方案是 `a11y tapText`、`a11y back`、`a11y home`。\n" +
                    "  这类命令在当前运行权限下必然被拒绝，不要反复重试。"
        }

        // 注意 margin 前缀用 `¦` 而不是默认的 `|`。
        // trimMargin() 默认剥掉行首的 `|`，而这份文档里有 Markdown 表格，
        // 表格行正是以 `|` 开头 —— 用默认前缀会把表头与分隔行一起破坏掉。
        //
        // ★ 这里刻意**不教 agent 拼 curl + token** ★
        //
        // 参考实现（AgentContextSeed）的做法是：在 PATH 上放包装命令
        // （scr / loop / notify），文档明确写「通常不需要找 bridge token、
        // 不需要 curl 端口」。我们照做 —— `a11y` 命令内部自己读凭据。
        // 教 token 会让模型多绕两步，而且 token 一变就失效。
        val conn = if (running) {
            """
            ¦桥接当前**已启动**。用 `a11y` 命令调用即可（鉴权已内置，不要手动取 token）：
            ¦
            ¦```bash
            ¦a11y status          # 可用性检查
            ¦a11y shot            # 截图，输出 PNG 绝对路径
            ¦a11y screen          # 读屏纯文本
            ¦a11y help            # 全部命令
            ¦```
            ¦
            ¦端点端口为 `$port`。高级用法可直接传路径：`a11y '/screen?limit=50'`。
            """.trimMargin("¦")
        } else {
            """
            ¦桥接当前**未启动**（通常是无障碍服务未开启）。
            ¦
            ¦```bash
            ¦a11y status          # 会报「读不到 ~/a11y-bridge.json」
            ¦cat ~/a11y-bridge.json    # 文件不存在 → 桥接确实没起来
            ¦```
            ¦
            ¦请让用户到 **系统设置 → 无障碍** 里启用「**FCL-DSH 屏幕操作**」，
            ¦然后回到应用。开启后 `~/a11y-bridge.json` 会出现（端口从 18081 起）。
            ¦在它出现之前，读屏与点击都不可用，不要反复重试，也不要自己去找端口。
            """.trimMargin("¦")
        }

        return """
        ¦# 本机环境：Android + 手机控制桥接
        ¦
        ¦你运行在一个 Android 应用内置的 Node 环境里，**当前设备就是用户正在用的手机**。
        ¦该应用提供了「**截图** + 读屏 + 模拟点击」能力，并已把它包装成 PATH 上的
        ¦`a11y` 命令 —— 不需要 root，不需要 Shizuku，也不需要自己找端口或 token。
        ¦
        ¦$priv
        ¦
        ¦## 快速开始
        ¦
        ¦```bash
        ¦a11y status
        ¦```
        ¦
        ¦返回 `"service":"connected"` 才说明可用；`disconnected` 表示无障碍服务没开。
        ¦
        ¦### 如果直接敲 `a11y` 报 Permission denied
        ¦
        ¦改用 `sh` 显式执行：
        ¦
        ¦```bash
        ¦sh "${'$'}PREFIX/bin/a11y" status
        ¦sh "${'$'}PREFIX/bin/a11y" shot
        ¦```
        ¦
        ¦原因是 SELinux 禁止对**应用数据目录里的文件**执行 `execve`（W^X）。
        ¦这一限制**无法通过改 shebang 绕过** —— 内核在读到 shebang 之前就已经拒绝了
        ¦执行请求，所以把 `#!/system/bin/sh` 换成任何别的路径都没用。
        ¦而 `sh <脚本>` 能工作，因为此时 `sh` 是把脚本当**数据**打开并解释执行的，
        ¦走的是读权限而不是执行权限。
        ¦
        ¦这与本环境里 `node` 必须用 `/system/bin/linker64 <node>` 加载是同一个限制。
        ¦
        ¦$conn
        ¦
        ¦## 端点
        ¦
        ¦| 请求 | 作用 |
        ¦|---|---|
        ¦| `GET /status` | 可用性检查（含 `canScreenshot`） |
        ¦| `GET /screenshot` | **截图**，返回 PNG 的绝对路径 |
        ¦| `GET /screen?limit=200` | 读屏，返回节点 JSON（文字 + 控件 + 坐标） |
        ¦| `GET /screen/text?limit=200` | 读屏，返回纯文本（每行一条） |
        ¦| `POST /tap?x=&y=` | 按屏幕像素点击 |
        ¦| `POST /longPress?x=&y=&ms=` | 长按，`ms` 默认 800 |
        ¦| `POST /tapText?text=&exact=` | 按文字找工作并点击（**优先用这个**） |
        ¦| `POST /input?text=&target=&append=` | **往输入框写整段文字**（不用逐字点按键） |
        ¦| `POST /back` / `POST /home` | 返回键 / 回桌面 |
        ¦| `GET /windows` | 本应用窗口范围 + 屏幕尺寸 + **形态** `mode` |
        ¦| `GET /check?x=&y=` | 目标点是否被本应用窗口挡住（也支持 `l,t,r,b`） |
        ¦| `POST /drag?dx=&dy=` | 拖动本应用窗口（抓**顶部中央**） |
        ¦| `POST /window/step?scale=` | 缩小一档：`large → small → ball` |
        ¦| `POST /window/restore` | 从悬浮球/小窗恢复（点最后已知位置） |
        ¦| `POST /swipe?x1=&y1=&x2=&y2=&ms=` | 任意滑动 |
        ¦
        ¦`/screen` 每个节点形如：
        ¦
        ¦```json
        ¦{"text":"发送","desc":"","cls":"android.widget.Button",
        ¦ "id":"com.foo:id/send","b":[980,1180,1060,1240],
        ¦ "click":true,"edit":false,"scroll":false,"focus":false}
        ¦```
        ¦
        ¦`b` 是 `[left,top,right,bottom]` 屏幕**物理像素**，可直接取中心喂给 `/tap`：
        ¦
        ¦```bash
        ¦a11y tree 120         # 先看有哪些可点元素（含坐标）
        ¦a11y tapText 发送     # 按文字点（比坐标稳）
        ¦sleep 0.4 && a11y screen   # 等界面响应后复核
        ¦```
        ¦
        ¦## 使用要点
        ¦
        ¦- **优先 `/tapText`**：它走控件自身的点击动作，比按坐标戳可靠；
        ¦  点不动时才退回按坐标点中心。
        ¦- **点击是异步的**：返回 `"ok":true` 只代表手势已下发，不代表界面已变化。
        ¦  操作后 `sleep 0.3~0.5` 再 `/screen` 复核。
        ¦- **横竖屏切换后要重新取坐标**：像素坐标基于当前屏幕，旋转后失效。
        ¦- 读不到想要的内容时，先 `/screen/text` 看可见文字全貌，再决定点哪里。
        ¦
        ¦## 往输入框写文字（不要逐字点软键盘）
        ¦
        ¦需要输入文字时，**不要**用 `/tap` 一个一个点软键盘按键。用 `/input`：
        ¦
        ¦```bash
        ¦a11y input "你好，帮我看看这个"          # 整体替换当前输入框
        ¦a11y input "关键词" "搜索"               # 指定提示文字含「搜索」的输入框
        ¦a11y append "（补充一句）"               # 在原有内容后追加
        ¦a11y clear                              # 清空当前输入框
        ¦a11y clear "评论"                        # 清空指定输入框
        ¦```
        ¦
        ¦原理是系统的 `ACTION_SET_TEXT`：一次调用写入整段，与输入法完全无关。
        ¦
        ¦**为什么不能逐字点按键**：键位随输入法布局、语言、大小写状态变化，
        ¦坐标几乎算不准；中文和 emoji 根本无法用按键输入；每个字符一次手势，
        ¦一句话要几十次 IPC。
        ¦
        ¦**定位规则**（重要）：
        ¦
        ¦- 不传 `target` 时，优先用**当前有输入焦点**的框；没有焦点就取页面上
        ¦  第一个可编辑控件。所以更稳的用法是**先 `a11y tapText` 点一下输入框**
        ¦  让它拿到焦点，再 `a11y input`。
        ¦- 传了 `target` 却匹配不到时，**不会**回落到别的输入框 —— 宁可失败，
        ¦  也不把文字写进你没指定的地方。匹配范围只有 `text`/`contentDescription`/
        ¦  `hintText`，不含「找旁边的标签」这种猜测。
        ¦
        ¦**失败时怎么办**：返回 `"ok":false` 说明该输入框拒绝 `ACTION_SET_TEXT`
        ¦（部分 WebView / 自定义控件会这样）。此时：
        ¦
        ¦1. 先 `a11y tree` 确认输入框存在、看它的 `cls` 与 `id`；
        ¦2. 试着用 `a11y tap "输入框提示"` 聚焦后再 `a11y input`；
        ¦3. 仍失败就只能请用户手动输入 —— **不要**退回逐字点按键，
        ¦   那既慢又几乎不可能对准。
        ¦
        ¦写完记得复核：`a11y tree` 看那个输入框的 `text` 是否已是你要的内容。
        ¦
        ¦
        ¦## 小窗：本应用挡住目标时怎么办
        ¦
        ¦**本应用不能自己进入小窗。** Android 没有让应用主动把自身缩成自由窗口的
        ¦公共 API（`WINDOWING_MODE_FREEFORM` 是系统私有）。所以：
        ¦
        ¦1. 需要边看对话边操作时，**请用户手动把本应用设为小窗**
        ¦   （最近任务里长按图标 →「小窗」/「在自由窗口中打开」，不同系统位置不同）；
        ¦2. 小窗挡住目标时，你自己把它挪开或缩小。
        ¦
        ¦### 三档形态（`mode` 字段）
        ¦
        ¦| 形态 | 表现 | 遮挡 |
        ¦|---|---|
        ¦| `large` | 可调大的窗口，有最小尺寸下限 | 大 |
        ¦| `small` | 小窗，略高于下限 | 最小 |
        ¦| `ball` | **悬浮球**：窗口不可见，退化成一个图标 | 无（但界面也看不见） |
        ¦| `unknown` | 读不到窗口：可能是悬浮球，也可能是应用被切到后台 | — |
        ¦
        ¦继续缩小会依次经过 `large → small → ball`。点悬浮球图标可回到大窗。
        ¦
        ¦`unknown` 是**故意不猜**的：悬浮球与「切到后台」在无障碍侧完全一样。
        ¦
        ¦### 流程
        ¦
        ¦```bash
        ¦a11y mode               # 窗口范围 + 形态（等价 a11y windows）
        ¦a11y check 540 1200     # 目标点是否被挡住（也支持 check l t r b）
        ¦```
        ¦
        ¦`check` 返回 `"blocked":true` 时，按遮挡程度选一条：
        ¦
        ¦```bash
        ¦# 轻度遮挡：把小窗挪开（dx 正=右，负=左；dy 正=下，负=上）
        ¦a11y drag 700 0
        ¦sleep 0.7 && a11y mode      # 拖动异步，必须复核实际位置
        ¦
        ¦# 挡得太多：缩小一档
        ¦a11y step
        ¦sleep 0.6 && a11y mode
        ¦
        ¦# 已经是 small 还是挡：再缩就到悬浮球了
        ¦a11y step                   # small → ball
        ¦sleep 0.6 && a11y mode      # 期望 mode=ball 或 unknown
        ¦
        ¦# 需要重新看界面时恢复
        ¦a11y restore
        ¦sleep 0.6 && a11y mode
        ¦```
        ¦
        ¦### 手势几何（这些数字是实测校准的，别自己猜）
        ¦
        ¦- **拖动**：必须按住窗口**顶部中央**。按左上角、顶部偏左都无效（窗口不动）。
        ¦  `a11y drag` 已按 `centerX, top + 6% 高度` 起手，你不用算坐标。
        ¦- **缩小**：按住**左下角外侧**一点点，向右上划。
        ¦  可拖动边框并不在 `bounds` 矩形**内**，而是贴着矩形**外侧** ——
        ¦  上一版按内侧 24px 起手，结果落进内容区变成滚动页面。
        ¦  `a11y step` 已按 `left - 6px, bottom + 6px` 起手。
        ¦- 手算回退（某台设备上封装手势不生效时）：
        ¦
        ¦```bash
        ¦a11y mode                                  # 记下 l/b（左、下）
        ¦a11y swipe <l-6> <b+6> <l+400> <b-400> 500 # 左下外侧 → 右上
        ¦```
        ¦
        ¦### 规则
        ¦
        ¦- **不要盲点**：`check` 说挡住就先挪开/缩小，挪不动就告诉用户，
        ¦  而不是直接 `/tap`（那一下会被小窗吃掉，你会误以为界面没反应）。
        ¦- **重试上限 3 次**：拖动后 `mode` 返回同一矩形，说明该设备不允许拖动，
        ¦  停下来请用户手动处理。
        ¦- **挪完必须复核**：`drag` / `step` 只表示「手势已下发」，不代表窗口真变了。
        ¦- **`restore` 只是启发式**：悬浮球是系统绘制的图标，它的坐标**不在**本应用的
        ¦  `AccessibilityWindowInfo` 里，只能点最后已知窗口位置的中心。
        ¦  点完必须复核；仍失败就请用户手动点悬浮球，**不要重复盲点**。
        ¦- 小窗贴到屏幕边缘时部分系统会自动回弹，属正常现象。
        ¦
        ¦## 要看画面（截图）
        ¦
        ¦想在界面上「看图」时，**不要**去试 `screencap`/`screenrecord` 的原始实现，
        ¦也不要试 `uiautomator dump`、`dumpsys SurfaceFlinger` —— 这个进程跑在
        ¦SELinux `untrusted_app` 域，缺 `CAPTURE_VIDEO_OUTPUT` 与 `DUMP`，
        ¦那些命令必然失败（表现为 0 字节输出或 Permission Denial）。
        ¦
        ¦正确做法：
        ¦
        ¦```bash
        ¦a11y shot
        ¦# → /storage/emulated/0/Android/data/<包名>/files/screenshot.png
        ¦```
        ¦
        ¦`a11y shot` 只输出 PNG 的**绝对路径**（不输出 base64），
        ¦拿到后直接用你的读图能力打开那个文件即可。
        ¦需要完整元信息时用 `a11y /screenshot`，返回
        ¦`{ok,path,width,height,ageMs,mode}`。
        ¦
        ¦### 交给读图工具时，用「外部」那一份
        ¦
        ¦同一帧会写到两个位置：
        ¦
        ¦| 路径 | 谁能读 |
        ¦|---|---|
        ¦| `/storage/emulated/0/Android/data/<包名>/files/screenshot.png` | **读图工具用这个**（`a11y shot` 输出的就是它） |
        ¦| `~/screenshot.png` | 只能 `cat` / `ls`，读图工具打不开 |
        ¦
        ¦`${'$'}HOME` 位于 `/data/user/0/<包名>/files/home`。而 `/data/user/0` 是
        ¦`root:root 0711` 的符号链接 —— 普通 UID 能穿过（`x`）但不能列出（`r`）。
        ¦读图工具在**解析路径根**时会对那一层做目录操作，直接
        ¦`EACCES: permission denied, open '/data/user/0'`；
        ¦报错会停在 `/data/user/0` 而不进入你给的具体文件，正是因为它还没走到文件那一步。
        ¦
        ¦所以：**不要**把 `~/...`、`/data/user/0/...` 或 `/data/data/...` 交给读图工具。
        ¦用 `a11y shot` 原样输出的路径即可。
        ¦
        ¦⚠️ **不要为了绕开这个限制去改 `${'$'}HOME`。** 那会让 dsh 在新的空目录里重新初始化，
        ¦插件、登录状态、会话历史都会「消失」（本项目真的这么翻过车）。
        ¦截图单独多写一份到外部目录，只涉及图片文件，不动任何数据 —— 这才是正确做法。
        ¦
        ¦## 看图 / 上传图片（`read_image`）
        ¦
        ¦两处已经**软链到公开目录**，`read_image` 与「在对话框里上传图片」应当直接可用：
        ¦
        ¦```
        ¦${'$'}DSH_HOME/.dsh-<版本>/attachments  →  /storage/emulated/0/dshdata/attachments/<版本>
        ¦${'$'}HOME/Documents                    →  /storage/emulated/0/dshdata/Documents
        ¦```
        ¦
        ¦为什么要这样：`/data/user/0` 是 `root:root 0711` 的符号链接，普通 UID
        ¦能穿过（`x`）不能列出（`r`）。dsh 写附件存储时会 `mkdir` + `chmod` 再
        ¦**逐级 fsync 到根**；解析路径时也常先打开父目录。这两种操作走到
        ¦`/data/user/0` 就 `EACCES`，而且**报错停在 `/data/user/0`、不进入具体文件**。
        ¦
        ¦所以关键点是：**换图片路径、复制文件都无效** —— 失败点在路径根，不在文件。
        ¦只有把**根**换成软链才有用。
        ¦
        ¦两处都外置的原因不同：
        ¦
        ¦| 外置对象 | 解决什么 |
        ¦|---|---|
        ¦| `attachments` | 写附件存储时逐级 fsync 撞 `/data/user/0` |
        ¦| `Documents`（工作区） | `read_image` 用工作区根目录当基准解析路径；它还留在 `/data/user/0` 下时，连「把图复制到工作区再读」也会失败 |
        ¦
        ¦判断是否生效：看应用运行日志里有没有这两行
        ¦
        ¦```
        ¦🔗 附件目录 已外置 → /storage/emulated/0/dshdata/attachments/<版本>
        ¦🔗 工作区 已外置 → /storage/emulated/0/dshdata/Documents
        ¦```
        ¦
        ¦**`${'$'}HOME` 与 `${'$'}DSH_HOME` 本身都没有搬**，版本隔离照常生效
        ¦（插件 / 登录 / 会话仍在各自版本的 `.dsh-<版本>` 里）。
        ¦
        ¦如果两行都在、`read_image` 仍报 `EACCES ... /data/user/0`，那说明它解析的是
        ¦`${'$'}HOME` 本身（进程 cwd），而 `${'$'}HOME` 受版本隔离约束不能搬 ——
        ¦这种情况**不要再试换路径**，改用：`a11y screen` 读文字、
        ¦或把 PNG 交给用户让他贴进对话。把日志转告用户即可。
        ¦
        ¦注意：**不要自己去调 `screencap`**。本环境的 `screencap` 是 Termux 工具链里的
        ¦系统二进制，在 `untrusted_app` 域里会因为 `LD_LIBRARY_PATH` 冲突报
        ¦`CANNOT LINK EXECUTABLE ... BIO_flush`，或写出 0 字节 —— 这条路不通。
        ¦`ageMs` 是这一帧距现在多久 —— 刚操作完界面时它偏大，就先 `sleep 0.4`
        ¦再取一次，避免看到上一屏。
        ¦
        ¦**前提只有一个**：系统的「无障碍服务」里「FCL-DSH 屏幕操作」是开启的。
        ¦不需要别的授权，也不需要用户点任何投屏/录制弹窗 —— 截图能力由无障碍服务
        ¦自身提供。`/screenshot` 会在文件过期时**现场截一帧**，所以你不用关心
        ¦缓存新不新。
        ¦
        ¦`/status` 里的 `screenshotMode` 会告诉你走的是哪条路：
        ¦
        ¦| 值 | 含义 |
        ¦|---|---|
        ¦| `accessibility` | 正常，无障碍截图可用（首选，无额外授权） |
        ¦| `projection` | 回落到了投屏截图（需用户每进程授权一次） |
        ¦| `none` | 两条路都不通，看 `screenshotHint` |
        ¦
        ¦拿不到图时 `/screenshot` 返回的 `fix` 字段会说明具体该做什么，例如：
        ¦
        ¦```json
        ¦{"ok":false,"reason":"无障碍截图失败","errorCode":2,
        ¦ "fix":"无障碍服务缺少截图能力：请让用户到 系统设置 → 无障碍 里关闭再开启一次…"}
        ¦```
        ¦
        ¦`errorCode=2` 表示服务没有截图权限 —— **通常是用户升级应用后没有重新开关过
        ¦无障碍服务**（系统只在服务重新绑定时读取能力声明）。这时应当把 `fix` 转告
        ¦用户，而不是反复重试。`errorCode=3` 只是调用太频繁，`sleep 0.5` 再试即可。
        ¦
        ¦## 已知做不到（不要在这些上浪费轮次）
        ¦
        ¦- $crossAppShell
        ¦- **`screencap` / `screenrecord` / `uiautomator dump` / `dumpsys`**：
        ¦  这些原生命令在本沙箱里必然失败（链接错误、`no display`、`Permission Denial`）。
        ¦  截图一律用 `a11y shot`，读屏一律用 `a11y screen` / `a11y tree`。
        ¦- **录屏**：本应用没有实现录屏。
        ¦- **读屏可能不全**：WebView、Flutter 等自绘界面的部分内容在无障碍树里缺失。
        ¦  这种界面优先用 `a11y shot` 看图，而不是依赖 `a11y tree`。
        ¦
        ¦## 安全
        ¦
        ¦`a11y` 能让持有它的进程读屏并代替用户点击，凭据等同密码。
        ¦**不需要**也**不要**去读 `~/a11y-bridge.json`（`a11y` 已内置鉴权）——
        ¦更不要把它的内容贴进对话、日志或提交到代码库。
        """.trimMargin("¦")
    }

    /**
     * 「你的运行权限」章节 —— 让 agent 一开口就知道自己是什么身份、能做什么。
     *
     * ## 为什么必须写进 AGENTS.md，而不是只让 agent 自己探
     *
     * 不告诉它身份时，模型在两种情况都会浪费时间：
     * - 普通模式下反复试 `su` / `shz`，每次失败还要再换一种写法；
     * - root 模式下反而畏手畏脚，绕着弯子用 `a11y` 去做本就该直接改文件的事。
     *
     * 一次写清楚「你是什么身份、边界在哪、有哪条额外通道」，比让它试错便宜得多。
     *
     * ## 只用只读状态，不起子进程
     *
     * 本函数会被 [refresh] 调用，而 [refresh] 可能在主线程上
     * （`A11yBridgeServer.start` → `writeDiscovery`）。所以这里**绝不**调
     * [Privilege.probeRoot]（最多起 3 个子进程、首次还会弹 Magisk 授权框），
     * 只读模式偏好与两个 Volatile 缓存。
     */
    private fun privilegeSection(context: Context): String {
        val mode = Privilege.getMode(context)
        val shzReady = Privilege.shizukuModeSelectable()
        val hasRoot = Privilege.hasKnownRoot()

        val body = when (mode) {
            PrivMode.NORMAL -> """
                ¦## 你的运行权限：普通模式
                ¦
                ¦引擎与本应用**同一个应用 UID**，你执行的所有命令都受这条边界约束：
                ¦
                ¦- 能读写：`${'$'}PREFIX`（容器）、`${'$'}HOME`、`${'$'}DSH_HOME`、已授权的共享存储
                ¦- **不能**：碰别的应用的数据、杀别人的进程、改系统文件
                ¦- `shz` 命令**不可用**（不存在，或调用必然失败）。不要试它，也不要试 `su`
                ¦
                ¦需要跨应用能力时，只能用 `a11y` 那套（读屏 + 点击）——
                ¦那是本模式唯一可用的对外操作通道。
            """.trimMargin("¦")

            PrivMode.SHIZUKU -> if (shzReady) """
                ¦## 你的运行权限：Shizuku 模式（已授权）
                ¦
                ¦引擎本身仍以**应用 UID** 运行 —— Shizuku 改不了子进程的 uid，
                ¦所以在 bash 里直接敲的命令仍是应用身份。
                ¦
                ¦但这个模式多了一条 **adb 身份**的通道：`shz <命令>`。
                ¦它由应用转发给 Shizuku 服务，以 `uid=2000(adb/shell)` 执行。
                ¦
                ¦```bash
                ¦shz id                    # 先确认：应看到 uid=2000(adb)
                ¦shz pm list packages      # 列出全部已安装应用（应用身份做不到）
                ¦shz am force-stop <包名>   # 强制停止别的应用
                ¦shz kill <pid>            # 结束别人的进程
                ¦shz dumpsys activity      # 系统级信息
                ¦```
                ¦
                ¦`shz` 打不开时先跑 `shz status`，看 `shizukuRunning` 与 `shizukuGranted`。
                ¦
                ¦⚠️ 它**不是 root**：不能写 `/system`、不能改别的应用的私有文件。
                ¦需要 root 时请用户切到「Root 模式」；用 `shz su` 提权没有意义。
            """.trimMargin("¦") else """
                ¦## 你的运行权限：Shizuku 模式（**通道未就绪**）
                ¦
                ¦用户在设置里选了 Shizuku 模式，但此刻通道建不起来。
                ¦原因只有两种，处理方式完全不同：
                ¦
                ¦| 情况 | 判断依据 | 用户要做什么 |
                ¦|---|---|---|
                ¦| **服务没在运行 / binder 未送达** | 通知栏没有 Shizuku 常驻通知；`pingBinder()` 为假 | ① 打开 Shizuku 应用，用「无线调试」配对或 root 启动服务，主页显示「运行中」；② 若服务已在运行而状态仍不变，让用户把本应用从最近任务里划掉重开 —— binder 只在 Shizuku 服务启动或应用进程启动时投递一次 |
                ¦| **服务在跑但未授权** | 服务可见，但本应用没被授权 | 设置 → 权限设置 → 运行模式 → 点「申请」；或在 Shizuku 的「应用管理」里打开 FCL-DSH 的开关 |
                ¦
                ¦**这时 `shz` 命令不存在**（连不上就不会生成它），执行会得到 127。
                ¦
                ¦- **不要**反复试 `shz`，也不要自己去找端口或 `shz-bridge.json` —— 都不存在。
                ¦- 需要跨应用操作时，先退回 `a11y`（读屏 + 点击）。
                ¦- 通道是**事件驱动**的：服务一旦起来、授权一旦到位，只要引擎在跑，
                ¦  `shz` 会立刻出现，**不需要重启引擎**。所以告诉用户去启动服务即可，
                ¦  不必让他重启实例。
            """.trimMargin("¦")

            PrivMode.ROOT -> if (hasRoot) """
                ¦## 你的运行权限：Root 模式
                ¦
                ¦整个引擎以 `uid=0` 启动，你执行的**每一条命令都是 root**：
                ¦
                ¦- **不需要** `su -c` 前缀，直接写命令
                ¦- 能读写全盘、改系统文件、管理任意进程与应用
                ¦- 只受内核与 SELinux 策略约束
                ¦
                ¦```bash
                ¦id                        # 确认：应为 uid=0(root)
                ¦ls /data/data             # 所有应用的私有目录都可见
                ¦```
                ¦
                ¦⚠️ **这里的破坏是不可撤销的**：没有回收站，也没有备份。
                ¦删错文件、改错系统配置都可能让设备无法开机。
                ¦
                ¦所以：只读探查（`ls` / `cat` / `ps` / `dumpsys`）尽管做；
                ¦**写操作一次只做一件事**，动手前先说明你要改哪个路径；
                ¦不确定就先问用户。不要批量 `rm -rf`，不要在未确认的情况下
                ¦动 `/system`、`/data/adb` 这类位置。
            """.trimMargin("¦") else """
                ¦## 你的运行权限：Root 模式（**未检测到可用的 su**）
                ¦
                ¦用户在设置里选了 Root 模式，但本机**没有实测可用的 su**。
                ¦
                ¦- 引擎可能根本起不来（启动时会拒绝执行并写日志）。
                ¦- 即便起来了也不要假定自己有 root —— 先用 `id` 确认。
                ¦- 应转告用户：确认设备已 root（Magisk / KernelSU）并已授权本应用，
                ¦  或到 设置 → 权限设置 → 运行模式 切回普通模式。
            """.trimMargin("¦")
        }

        // 模式是**引擎启动时**下发的，改完必须重启实例才生效 ——
        // 这一点不写清楚，agent 会在用户切完模式后仍按旧身份行事。
        return body.trimEnd() + "\n\n" +
            "该模式在引擎**启动时**生效；用户改动后需要重启实例（主界面「停止」再「启动」）。\n"
    }
}
