package com.example.fcldsh

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/**
 * 任务悬浮窗：应用退到后台时，在最上层留一块**固定尺寸**的小卡片，
 * 显示 dsh 的 agent 现在处于什么状态、正在跑什么命令。
 *
 * ## 布局（尺寸固定，不随内容变化）
 *
 * ```
 * ┌──────────────────────────────────┐
 * │ ┌──────────────┐      (✕)  (↩) │ ← 状态槽：明显边框，永远在、
 * │ │ ◠ 正在思考   │                │   位置永远不变
 * │ └──────────────┘                │
 * │ 💬 我先看一下当前的目录结构        │ ← 内容区：固定 2 行高
 * │                                 │
 * └──────────────────────────────────┘
 * ```
 *
 * 右上角两个小按键：
 *
 * | 按键 | 作用 | 位置 |
 * |---|---|---|
 * | `✕` | **临时收起**浮窗，下次从后台切回前台再切出去时自动恢复 | 左 |
 * | `↩` | 回到启动器 | 右（最常用，留在拇指容易碰到的角落） |
 *
 * 三个状态（[AgentPhase]）**共用同一个状态槽**，只是槽里的文字与转圈不同：
 *
 * | 状态 | 状态槽 | 内容区 |
 * |---|---|---|
 * | 暂无输出 | 灰字「暂无输出」，无转圈 | 空白 |
 * | 正在思考 | 主题色「正在思考」+ **转圈** | 最近 1 条模型输出 |
 * | 调用工具 | 成功色「调用工具」，无转圈 | 最多 [LINES] 条命令（等宽字） |
 *
 * ★ 状态槽里的内容不用 `GONE` 而是 `INVISIBLE`（转圈）、内容区用空行占位，
 * 加上窗口高度写死，卡片尺寸和内部每一行的位置都**完全不会动** ★。
 * 早期版本用 `WRAP_CONTENT` + `GONE`，结果是「转圈出现时文字整体右移」
 * 「命令行多一条卡片就长高一点」，看着一直在跳。
 *
 * ## 生效条件（四者缺一不可）
 *
 * 1. 用户在「权限设置 → 任务悬浮窗」里打开开关（[LauncherPrefs.getTaskOverlay]，
 *    默认关 —— 不请自来地在别人屏幕上挂一块东西是侵入性的）；
 * 2. 系统授予了「显示在其他应用上层」（`SYSTEM_ALERT_WINDOW`，即 [hasPermission]）。
 *    这是**特殊权限**，只能由用户在系统设置页手动拨开关；
 * 3. **dsh 正在启动或运行**（`runState.isAlive`）。没启动时切后台不该冒出一块
 *    写着「暂无输出」的空窗 —— 那是噪音，不是信息；
 * 4. 本应用**所有** Activity 都不在前台（见 [AppState] 里的活动计数）。
 *
 * ## ★ 为什么不能只在 MainActivity.onStop 里拉起 ★
 *
 * 「应用进后台」和「某个 Activity 停止」不是一回事：
 * 从启动器打开 dsh 网页窗口时，[DshWebActivity] 是**同一个进程里的另一个
 * Activity**，[MainActivity] 照样会走 `onStop` —— 若以此为准，一开网页窗口
 * 就会冒出浮窗，正好盖在有退出键和页面的地方。
 *
 * 同理，打开配置文件编辑页、下载页也会误触发。所以判定放在 [AppState] 里，
 * 用 `Application.ActivityLifecycleCallbacks` 统计进程内**已启动的 Activity
 * 数量**，归零才算真的进后台。
 *
 * ## 为什么内容是原生 View 而不是 Compose
 *
 * 浮窗的 View 挂在一段**没有 Compose 生命周期宿主**的窗口上。
 * `ComposeView` 需要 `ViewTreeLifecycleOwner`，硬塞进来会在组合策略
 * （DisposeOnViewTreeLifecycleDestroyed）找不到宿主时立刻销毁内容，
 * 表现为「窗口有、内容空」。原生 View 没有这个依赖，最稳。
 * 转圈动画同理，是自己画的 [OverlaySpinner]，不是 Compose 的 `CircularProgressIndicator`。
 *
 * ## 内容的刷新方式
 *
 * [ModelCallFeed] 与 [AgentActivity] 都是普通对象（不是 Compose snapshot state），
 * **读它们不会订阅、不会回调**。所以这里用低频轮询（[REFRESH_MS]）主动刷文字。
 * 转圈动画本身不依赖它，那是 [OverlaySpinner] 自己的 `ValueAnimator`。
 */
object TaskOverlay {

    /** 卡片宽度。命令是等宽字，比中文更容易撑宽，所以比原来的 196dp 放宽一档。 */
    private const val WIDTH_DP = 240f

    /**
     * 卡片高度（写死）。原来是 110dp，按要求缩减一半 → 56dp。
     *
     * = [PAD_V_DP]×2 + [HEADER_H_DP] + [GAP_DP] + [LINES]×[LINE_H_DP]
     * = 4×2 + 18 + 2 + 2×14 = 56
     *
     * 高度减半的代价是内容区只能放 2 条：命令再长也还是一个字都看不全，
     * 不如把位置留给「最新两条」，其余靠启动器里的日志面板看。
     */
    private const val HEIGHT_DP = 56f

    /** 卡片内边距（上下 / 左右）。 */
    private const val PAD_V_DP = 4f
    private const val PAD_H_DP = 8f

    /** 头部（状态槽 + 按键）高度。 */
    private const val HEADER_H_DP = 18f

    /** 头部与内容区之间的间隙。 */
    private const val GAP_DP = 2f

    /** 每行命令的高度。 */
    private const val LINE_H_DP = 14f

    /** 内容区显示几条。高度 = [LINES] × [LINE_H_DP]。 */
    private const val LINES = 2

    /** 与屏幕边缘的间距。 */
    private const val MARGIN_DP = 10f

    /** 内容刷新间隔。 */
    private const val REFRESH_MS = 400L

    /** 转圈图形的直径。头部只有 18dp 高，12dp 是能看清又不顶破的上限。 */
    private const val SPINNER_DP = 11f

    /**
     * 回到启动器的小按键直径。
     *
     * 必须 ≤ [HEADER_H_DP]（18dp）：按键和状态槽在同一行，
     * 按键比行高就会把整行撑高，卡片高度写死也挡不住内容被裁。
     */
    private const val BUTTON_DP = 16f

    /** 判定为「拖动」而不是「点击」的位移阈值（屏幕像素）。 */
    private const val TOUCH_SLOP_PX = 12

    private val main = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var root: View? = null

    /** 状态文字：「暂无输出」/「正在思考」/「调用工具」。永远可见。 */
    private var phaseText: TextView? = null

    /**
     * 转圈动画。
     *
     * ★ 只有「正在思考」时 `VISIBLE`，其余状态用 `INVISIBLE`（不是 `GONE`）★
     * 用 `GONE` 会让状态文字整体左移，切换状态时那一行在跳。
     */
    private var spinner: OverlaySpinner? = null

    /** 内容区的各行，下标 0 是最新一条。 */
    private var callLines: List<TextView> = emptyList()

    /** 拖动起点（屏幕坐标）与当时的窗口偏移。 */
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false

    private val ticker = object : Runnable {
        override fun run() {
            if (root == null) return
            refreshContent()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    /**
     * 是否已获得「显示在其他应用上层」权限。
     *
     * `Settings.canDrawOverlays` 从 API 23 起可用，而本项目 minSdk 就是 23，
     * 所以不需要版本门禁。读不到（极少数 ROM 抛异常）按「没有权限」处理：
     * 宁可让设置页显示「去授权」，也不要在没权限时尝试 addView 然后崩溃。
     */
    fun hasPermission(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 显示 / 隐藏
    // ------------------------------------------------------------------

    /** 当前是否正在显示。 */
    val isShowing: Boolean get() = root != null

    /**
     * 用户用浮窗上的 ✕ 按键**临时收起**了它。
     *
     * ## 为什么需要这个状态，而不是直接改偏好开关
     *
     * 「暂时不想看到」和「以后都不要」是两件事。让 ✕ 去改
     * [LauncherPrefs.getTaskOverlay] 意味着用户还得回设置页重新打开，
     * 而且下次启动应用时它也还是关的 —— 那不是「暂时」。
     *
     * 所以这里只存一个内存标记：
     * - 置位：[dismiss]（点 ✕）；
     * - 复位：**下一次从后台切回前台**时（见 [sync] 的 `!inBackground` 分支）。
     *
     * 效果就是「关掉它 → 干别的事 → 回到 App → 再切后台，它又回来了」。
     * 进程被杀掉时标记自然丢失，也符合「暂时」的语义。
     */
    @Volatile
    private var dismissed = false

    /**
     * 按当前偏好、引擎状态与权限决定显示还是隐藏。
     * 由 [AppState] 在前后台切换时调用。
     *
     * @param inBackground true 表示本应用所有 Activity 都已停止。
     */
    fun sync(context: Context, inBackground: Boolean) {
        if (!inBackground) {
            // 回到前台：解除「临时收起」，这样下次再切后台浮窗会重新出现
            dismissed = false
            hide()
            return
        }
        if (!LauncherPrefs.getTaskOverlay(context)) {
            hide()
            return
        }
        // 用户点过 ✕：本次驻留后台期间保持收起，直到下一次前后台切换
        if (dismissed) {
            hide()
            return
        }
        // ★ 没在跑就不显示 ★
        //
        // 用户明确要求过：dsh 未启动时切后台不该有浮窗。此前只判断
        // 「进后台 + 开关打开 + 有权限」，于是一台根本没启动过引擎的手机上，
        // 每次回到桌面都会挂出一块写着「暂无输出」的空窗。
        if (!AppState.launcher.runState.isAlive) {
            hide()
            return
        }
        if (!hasPermission(context)) {
            // 只在真的尝试过且失败时提示一次，避免每次切后台都刷日志
            if (!warnedNoPermission) {
                warnedNoPermission = true
                AppState.launcher.log(
                    "⚠️ 任务悬浮窗已开启，但缺少「显示在其他应用上层」权限；" +
                        "请到 设置 → 权限设置 → 任务悬浮窗 授权"
                )
            }
            hide()
            return
        }
        show(context)
    }

    @Volatile
    private var warnedNoPermission = false

    /** 用户授予权限后重置提示，下次缺权限时能再提醒一次。 */
    fun resetPermissionWarning() {
        warnedNoPermission = false
    }

    /**
     * ✕ 按键：**临时收起**浮窗，下次从后台切回前台再切出去时自动恢复。
     *
     * 只置位 [dismissed] 并 [hide]，不改任何持久化偏好 —— 用户没说要永久关掉
     * 这个功能，只是此刻不想看见它。语义与复位时机见 [dismissed] 的注释。
     */
    fun dismiss() {
        dismissed = true
        hide()
        AppState.launcher.log("🪟 悬浮窗已暂时收起（下次切到后台会恢复）")
        // 刻意**不**写进 LauncherPrefs：那个开关的语义是「要不要这个功能」，
        // 而这次只是「此刻不想看」。两者混在一起，用户下次启动应用会发现
        // 功能莫名其妙还是关着的。
    }

    /**
     * 显示浮窗。重复调用是安全的（已经显示时直接返回）。
     *
     * 任何异常都吃掉并写日志：这个函数会在 Activity 的 onStop 里被调用，
     * 在那里抛异常等于用户一切后台就崩，比浮窗没出来严重得多。
     */
    fun show(context: Context) {
        if (root != null) return

        val ctx = context.applicationContext
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        appContext = ctx

        val ok = runCatching {
            val view = buildView(ctx)
            val lp = buildLayoutParams(ctx)
            wm.addView(view, lp)
            windowManager = wm
            root = view
            applyTouchHandler(view, lp)
        }

        if (ok.isFailure) {
            val e = ok.exceptionOrNull()
            AppState.launcher.log(
                "⚠️ 任务悬浮窗添加失败: ${e?.message ?: e?.javaClass?.simpleName}"
            )
            root = null
            windowManager = null
            return
        }

        refreshContent()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    /** 移除浮窗。没有显示时是空操作。 */
    fun hide() {
        main.removeCallbacks(ticker)
        val view = root ?: return
        root = null
        phaseText = null
        spinner = null
        callLines = emptyList()
        // 先移除再清窗口管理器的引用：removeView 期间 spinner 会走
        // onDetachedFromWindow，那里需要停掉动画。
        runCatching { windowManager?.removeView(view) }
        windowManager = null
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    /**
     * 卡片结构（尺寸全部写死，见类注释里的示意图）：
     *
     * ```
     * LinearLayout(vertical, 固定 240×56dp)
     *  ├── LinearLayout(horizontal, 高 18dp)      ← 头部
     *  │    ├── 状态槽（weight=1，高 18dp，有明显边框）
     *  │    │    ├── OverlaySpinner 11dp（INVISIBLE 占位）
     *  │    │    └── TextView 状态文字 10sp
     *  │    ├── TextView ✕ 16dp                  ← 暂时收起
     *  │    └── TextView ↩ 16dp                  ← 回到启动器
     *  └── LinearLayout(vertical, 高 2×14dp)      ← 内容区
     *       └── 2 × TextView（各高 14dp，9sp）
     * ```
     *
     * 按键放在头部右侧而不用 `FrameLayout` 压在上面：压上去的话它会和
     * 状态槽的边框重叠，看起来像槽被咬掉一块。
     */
    private fun buildView(ctx: Context): View {
        val rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                ctx.dpFor(PAD_H_DP), ctx.dpFor(PAD_V_DP),
                ctx.dpFor(PAD_H_DP), ctx.dpFor(PAD_V_DP)
            )
            // FCL 的卡片观感：底色 Card + 主题色描边 + 10dp 圆角
            background = GradientDrawable().apply {
                cornerRadius = ctx.dpFor(10f).toFloat()
                setColor(Fcl.Card.toArgb())
                setStroke(ctx.dpFor(1f), Fcl.Theme.toArgb())
            }
        }

        // ── 头部：状态槽 + 回到启动器按键 ─────────────────────────────
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // ★ 状态槽：三态共用这一块，边框明显、位置不变、永不隐藏 ★
        val chip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(ctx.dpFor(5f), 0, ctx.dpFor(7f), 0)
            background = GradientDrawable().apply {
                cornerRadius = ctx.dpFor(6f).toFloat()
                // 主题色 10% 填充 + 1.5dp 主题色描边：比卡片那圈 1dp 描边明显，
                // 但仍是同一套配色，不会显得像弹窗按钮
                setColor(withAlpha(Fcl.Theme.toArgb(), 0x1A))
                setStroke(ctx.dpFor(1.5f), Fcl.Theme.toArgb())
            }
        }

        val spin = OverlaySpinner(ctx).apply {
            // ★ INVISIBLE 而不是 GONE ★ 转圈出现/消失时状态文字不能位移
            visibility = View.INVISIBLE
        }
        val spinSize = ctx.dpFor(SPINNER_DP)
        chip.addView(spin, LinearLayout.LayoutParams(spinSize, spinSize))

        val label = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Fcl.TextTertiary.toArgb())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ctx.dpFor(5f), 0, 0, 0)
            text = AgentPhase.IDLE.label
        }
        chip.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // weight=1：状态槽吃掉除按键以外的全部宽度，宽度恒定
        header.addView(
            chip,
            LinearLayout.LayoutParams(0, ctx.dpFor(HEADER_H_DP), 1f)
        )

        val btnSize = ctx.dpFor(BUTTON_DP)

        /**
         * 头部右侧那两个圆形小按键的公共构造。
         *
         * 抽成局部函数是为了让两个按键**尺寸、描边、内边距完全一致**：
         * 手写两遍很容易一边 16dp 一边 15dp，在 18dp 的行高里看得出来。
         */
        fun roundButton(
            glyph: String,
            desc: String,
            onClick: () -> Unit
        ): TextView = TextView(ctx).apply {
            text = glyph
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.CENTER
            setTextColor(Fcl.Theme.toArgb())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(ctx.dpFor(1f), Fcl.Theme.toArgb())
            }
            contentDescription = desc
            isClickable = true
            setOnClickListener { onClick() }
        }

        // ★ ✕ 放在 ↩ 左边 ★
        //
        // 顺序是有意的：↩ 是「回到启动器」，用得最多，留在最右角（拇指容易碰到、
        // 位置也一直是那里）；✕ 是「暂时收起」，放中间。反过来插到右边会让
        // ↩ 的位置左右横跳，用户会点错。
        header.addView(
            roundButton("✕", "暂时收起悬浮窗（下次切到后台会恢复）") { dismiss() },
            LinearLayout.LayoutParams(btnSize, btnSize).apply {
                leftMargin = ctx.dpFor(6f)
            }
        )

        val button = roundButton("↩", "回到启动器") { openLauncher() }
        header.addView(
            button,
            LinearLayout.LayoutParams(btnSize, btnSize).apply {
                leftMargin = ctx.dpFor(6f)
            }
        )

        rootView.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ctx.dpFor(HEADER_H_DP)
            )
        )

        // ── 内容区：高度写死为 LINES 行，行数多少都不会改变卡片大小 ──
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val lines = ArrayList<TextView>(LINES)
        for (i in 0 until LINES) {
            val tv = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(Fcl.TextSecondary.toArgb())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                // 空行也占着位置：内容多少都不影响布局
                visibility = View.INVISIBLE
            }
            content.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ctx.dpFor(LINE_H_DP)
                )
            )
            lines.add(tv)
        }
        rootView.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ctx.dpFor(LINES * LINE_H_DP)
            ).apply { topMargin = ctx.dpFor(GAP_DP) }
        )

        phaseText = label
        spinner = spin
        callLines = lines
        return rootView
    }

    private fun buildLayoutParams(ctx: Context): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26 起 TYPE_PHONE / TYPE_SYSTEM_ALERT 都被废弃并会抛异常，
            // 必须改用 TYPE_APPLICATION_OVERLAY。
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            // API 23..25：没有 TYPE_APPLICATION_OVERLAY 这个常量。
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val width = ctx.dpFor(WIDTH_DP)
        val height = ctx.dpFor(HEIGHT_DP)
        val margin = ctx.dpFor(MARGIN_DP)
        val screenW = ctx.resources.displayMetrics.widthPixels

        return WindowManager.LayoutParams(
            width,
            // ★ 固定高度，不用 WRAP_CONTENT ★
            // 用 WRAP_CONTENT 时，内容多一行、转圈一出现，窗口就会重新测量，
            // 正是「卡片大小一直在变」的直接原因。
            height,
            type,
            // NOT_FOCUSABLE：不抢输入焦点、不弹输入法，也不影响用户操作下面的应用。
            // 触摸事件仍然会派发到这个窗口本身（拖动/点击需要它）。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认贴右上角
            x = (screenW - width - margin).coerceAtLeast(margin)
            y = margin
        }
    }

    /**
     * 拖动移动。**轻点卡片不做任何事**（回到启动器由头部那个小按键负责）。
     *
     * 浮窗压在别的应用上面，如果不能挪走，用户迟早会因为它挡住按钮而
     * 关掉这个功能。所以拖动是必需项，不是装饰。
     *
     * 监听挂在根布局上即可：按键是 `clickable` 的子 View，会先消费自己的
     * 触摸事件，根布局的 `onTouchListener` 根本不会被调用（ViewGroup 只在
     * 没有任何子 View 处理该事件时才走自己的 onTouch 路径）。所以不需要
     * 像早期版本那样手动挑一个「不含按键」的子 View 当拖动目标。
     */
    private fun applyTouchHandler(view: View, lp: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragStartX = lp.x
                    dragStartY = lp.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (!dragging &&
                        (abs(dx) > TOUCH_SLOP_PX || abs(dy) > TOUCH_SLOP_PX)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        lp.x = dragStartX + dx
                        lp.y = dragStartY + dy
                        runCatching { windowManager?.updateViewLayout(view, lp) }
                    }
                    true
                }

                // 抬手时什么都不做 —— 回启动器是小按键的事。
                // 必须返回 Boolean：这个 when 是整个 lambda 的返回表达式，
                // 写成 `else -> dragging = false`（Unit）会报
                // 「Return type mismatch: expected 'Boolean', actual 'Any'」。
                else -> {
                    dragging = false
                    false
                }
            }
        }
    }

    /**
     * 小按键：回到启动器。
     *
     * 从**没有 Activity 的窗口**里启动 Activity 必须带 `FLAG_ACTIVITY_NEW_TASK`，
     * 否则直接抛 `AndroidRuntimeException: Calling startActivity() from outside
     * of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag`。
     *
     * 用 REORDER_TO_FRONT 而不是 CLEAR_TOP：用户可能正停在某个面板上，
     * 回到原来那个界面比重建一个空的启动器更符合预期。
     */
    private fun openLauncher() {
        val ctx = appContext ?: return
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
            )
        }
    }

    /** 最近一次显示时用的 application context，供 [openLauncher] 使用。 */
    @Volatile
    private var appContext: Context? = null

    // ------------------------------------------------------------------
    // 内容
    // ------------------------------------------------------------------

    /**
     * 刷新状态槽与内容区。三种状态**共用同一块位置**，相互顶替：
     *
     * | 状态 | 状态槽 | 内容区 |
     * |---|---|---|
     * | 暂无输出 | 灰字，无转圈 | 空白（固定 4 行高） |
     * | 正在思考 | 主题色 + **转圈** | 最近 1 条模型输出当上下文 |
     * | 调用工具 | 成功色，无转圈 | 最多 [LINES] 条命令（等宽字） |
     *
     * ★ 状态槽在任何状态下都可见 ★ —— 上一版在「调用工具」时把整行藏掉，
     * 结果用户看不到「调用工具」这四个字，只看到一串命令，以为状态没了。
     *
     * 取数据时按类别分开：「调用工具」只取 [ModelCallFeed.Kind.TOOL] 的条目，
     * 模型输出混进去会让人分不清哪行是真的在执行的命令。
     */
    private fun refreshContent() {
        val label = phaseText ?: return

        // 引擎已经不在跑（崩溃 / 用户在网页窗口里停掉了）：浮窗没有意义了。
        // 这里兜住「浮窗已显示期间引擎退出」的情况，normal 的收起路径在 sync()。
        if (!AppState.launcher.runState.isAlive) {
            hide()
            return
        }

        val phase = AgentActivity.phase()

        label.text = phase.label
        // ★ 必须用 setTextColor(ColorStateList)，不能写 `label.textColor = …` ★
        // TextView 只有 setTextColor 两个 setter、没有 getTextColor，Kotlin 的
        // 合成属性要求 getter + setter **成对**，所以那个属性根本不存在
        // （写成属性赋值会报 Unresolved reference 'textColor'，已实测）。
        // 分析器仍会提示「Use property access syntax」，那条建议是错的，别照着改。
        label.setTextColor(
            ColorStateList.valueOf(
                when (phase) {
                    AgentPhase.IDLE -> Fcl.TextTertiary.toArgb()
                    AgentPhase.THINKING -> Fcl.Theme.toArgb()
                    AgentPhase.TOOL -> Fcl.Success.toArgb()
                }
            )
        )
        // ★ INVISIBLE 而非 GONE ★：可见性同时驱动 [OverlaySpinner] 的动画起停，
        // 但不能让状态文字跟着左右跳。
        spinner?.visibility =
            if (phase == AgentPhase.THINKING) View.VISIBLE else View.INVISIBLE

        val lines = callLines
        if (lines.isEmpty()) return

        val wanted = when (phase) {
            AgentPhase.IDLE -> 0
            AgentPhase.THINKING -> 1
            AgentPhase.TOOL -> lines.size
        }
        // 「调用工具」只显示命令；「正在思考」不限类别，取最近一条当上下文即可
        val recent = if (phase == AgentPhase.TOOL) {
            ModelCallFeed.recentOf(wanted, ModelCallFeed.Kind.TOOL)
        } else {
            ModelCallFeed.recent(wanted)
        }

        for (i in lines.indices) {
            val entry = recent.getOrNull(i)
            if (entry == null) {
                // INVISIBLE：高度本来就写死了，这里只是清掉残留文字
                lines[i].visibility = View.INVISIBLE
                lines[i].text = ""
            } else {
                lines[i].visibility = View.VISIBLE
                lines[i].text = entry.line()
                // 命令用等宽字：一眼能分辨「这是真正在跑的命令」还是模型说的话
                lines[i].typeface = if (entry.kind == ModelCallFeed.Kind.TOOL) {
                    Typeface.MONOSPACE
                } else {
                    Typeface.DEFAULT
                }
                // 失败一律标红；思考时那一条只当上下文，弱化成次级文字色。
                // 同上：只能调 setTextColor(ColorStateList)，不能写 textColor 属性。
                lines[i].setTextColor(
                    ColorStateList.valueOf(
                        when {
                            !entry.ok -> Fcl.Danger.toArgb()
                            phase == AgentPhase.THINKING -> Fcl.TextSecondary.toArgb()
                            i == 0 -> Fcl.TextPrimary.toArgb()
                            else -> Fcl.TextSecondary.toArgb()
                        }
                    )
                )
            }
        }
    }
}

/**
 * 给一个 ARGB 整数换掉 alpha 通道。
 *
 * 状态槽的底色用「主题色 + 10% 不透明度」，需要在不引入 Compose Color 的前提下
 * 拼出带 alpha 的值（浮窗是原生 View，只有 `toArgb()` 出来的 Int）。
 */
private fun withAlpha(argb: Int, alpha: Int): Int =
    (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

/** dp → px。浮窗用原生 View，必须自己换算。 */
private fun Context.dpFor(v: Float): Int =
    (v * resources.displayMetrics.density + 0.5f).toInt()
