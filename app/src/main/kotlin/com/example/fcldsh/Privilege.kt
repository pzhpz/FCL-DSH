package com.example.fcldsh

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行权限模式 —— 决定「本应用能把命令跑到多高的身份上」。
 *
 * ## 与 [PermissionMode] 的区别（两者是**正交**的两个轴）
 *
 * | 轴 | 枚举 | 管什么 | 怎么下发 |
 * |---|---|---|---|
 * | 沙箱策略 | [PermissionMode] | dsh **内部**允不允许写文件 / 跑命令 | 环境变量 `DSH_PERMISSION_MODE` |
 * | 运行权限 | 本枚举 | 本应用**以什么身份**起进程、能以什么身份执行命令 | 进程本身（uid）与提权通道 |
 *
 * 所以四种组合都有意义，例如：
 * - 普通 + 工作区可写：拿不到 bwrap/Landlock，bash 会被 dsh 拒绝（安全默认）
 * - Root + 完全访问：引擎整体 root，命令也是 root
 * - Shizuku + 完全访问：引擎仍是 App 身份，但 AI 可用 adb(2000) 身份执行
 *
 * ## 为什么 Shizuku 模式不把引擎提权（关键）
 *
 * Shizuku 授予的是**本应用进程**的 binder 能力，它无法改变 `fork()` 出来的子进程
 * uid。所以「Shizuku 模式」不是一个「更高级的 root」，而是：
 * 引擎仍以 App 身份跑，另开一条 adb(2000) 的执行通道给 AI 用。
 * 把这点在 UI 上说清楚，比让用户以为「开了 Shizuku 就什么都能干」要有用。
 */
enum class PrivMode(
    /** 存进偏好文件的值；不要随显示文案改。 */
    val id: String,
    val label: String,
    val desc: String,
    /** 是否为危险模式：UI 上需要额外警示 + 二次确认。 */
    val dangerous: Boolean
) {
    NORMAL(
        id = "normal",
        label = "普通模式",
        desc = "引擎与本应用同身份（App 沙箱 uid）。不依赖任何外部程序，" +
            "装完就能用。命令无法越过本应用的权限边界。",
        dangerous = false
    ),

    SHIZUKU(
        id = "shizuku",
        label = "Shizuku 模式",
        desc = "额外给 AI 一条 adb(uid 2000) 身份的执行通道（可杀进程、改应用私有目录属主等）。" +
            "引擎本体仍是 App 身份 —— Shizuku 改不了子进程 uid。" +
            "需要在设备上装好 Shizuku 并授权本应用。",
        dangerous = false
    ),

    ROOT(
        id = "root",
        label = "Root 模式",
        desc = "整个引擎以 su(uid 0) 启动，AI 执行的命令也是 root。" +
            "可读写全盘、可改系统文件；一旦模型行为失控，后果不可撤销。" +
            "请确认设备已有可用的 su。",
        dangerous = true
    );

    companion object {
        /** 安全默认值：不依赖任何外部程序。 */
        val DEFAULT = NORMAL

        /**
         * 按 id 反查。未知值回退默认 —— 旧配置或手改文件不该让应用打不开。
         *
         * 用 `values()` 而不是 `entries`：后者需要较新的 Kotlin 版本。
         */
        fun fromId(id: String?): PrivMode =
            values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 一次能力探测的结果。
 *
 * 刻意保留原始事实而不只给布尔：现场排查时「装了 Shizuku 但没授权」与
 * 「根本没装」是完全不同的两件事，处理方式也不同。
 */
data class PrivCapability(
    /** 存在可用的 su（**实际执行成功拿到 uid=0**，不是文件存在）。 */
    val hasRoot: Boolean = false,
    /** 设备上装了 Shizuku（能被 binder 看见）。 */
    val hasShizuku: Boolean = false,
    /** Shizuku 已对本应用授权。 */
    val shizukuGranted: Boolean = false
) {
    /** 当前模式在该设备上是否真的可用。NORMAL 永远可用。 */
    fun usable(mode: PrivMode): Boolean = when (mode) {
        PrivMode.NORMAL -> true
        PrivMode.SHIZUKU -> hasShizuku && shizukuGranted
        PrivMode.ROOT -> hasRoot
    }
}

/**
 * 运行权限的存取、能力探测与提权执行。
 *
 * ## 探测原则：以「真能执行」为准，不看文件存在性
 *
 * 只 `stat` 到 `/system/bin/su` 就判定「有 root」是错的：文件在但 SELinux 拒绝、
 * 或那个 su 是不认 `-c` 的 toybox 版，都会让 UI 说「可用」而后续每条命令全失败。
 * 所以 [probeRoot] 必须**真的执行一次 `id` 并看到 `uid=0`**。
 *
 * 同理 Shizuku 不能只看包名：装了但没授权时 binder 是通的、`checkSelfPermission`
 * 却是拒绝，两种状态要分开报。
 */
object Privilege {

    private const val TAG = "DSH-priv"

    /** 偏好文件名。与 [LauncherPrefs] 分开：这里存的是「设备能力」相关状态。 */
    private const val PREFS = "dsh_priv"
    private const val KEY_MODE = "priv_mode"
    private const val KEY_SHIZUKU_DENIED = "shizuku_denied"

    /**
     * 常见 su 路径。
     *
     * `/debug_ramdisk/su` 不能漏：Magisk 25+ 把 su 放在 `magisk --path`（默认
     * 就是这里），不在任何 `PATH` 目录中，裸 `su` 找不到 —— 漏了它就会出现
     * 「KernelSU 能检测到、Magisk 检测不到」这种一半一半的反馈。
     */
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su",
        "/sbin/su", "/vendor/bin/su", "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/magisk/su", "/data/adb/ksu/bin/su"
    )

    /** Shizuku 的包名（server 与 manager 两种分发形态）。 */
    private val SHIZUKU_PACKAGES = listOf(
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager"
    )

    /** Shizuku 授权请求码。任意固定值即可。 */
    const val REQUEST_CODE_SHIZUKU = 0x5A17

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ==================== 模式存取 ====================

    fun getMode(ctx: Context): PrivMode =
        PrivMode.fromId(prefs(ctx).getString(KEY_MODE, null))

    fun setMode(ctx: Context, mode: PrivMode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.id).apply()
        Log.i(TAG, "priv mode -> ${mode.id}")
    }

    /** 用户是否已明确拒绝过 Shizuku 授权（拒绝即尊重，不再自动弹窗）。 */
    fun shizukuDenied(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHIZUKU_DENIED, false)

    fun markShizukuDenied(ctx: Context, denied: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SHIZUKU_DENIED, denied).apply()
    }

    // ==================== su ====================

    /** 在已知路径里找 su；找不到返回 null（不往 PATH 里瞎试）。 */
    fun findSu(): String? = SU_PATHS.firstOrNull { File(it).exists() }

    /**
     * 探测 Root：**必须真的执行一次并拿到 uid=0**。
     *
     * 逐个尝试三种常见调用语法，因为不同 su 实现不通用：
     * ① `su -c id` —— Magisk / KernelSU 标准写法
     * ② `su 0 id` —— toybox su 的位置参数写法（不认 `-c`，会报
     *    `invalid uid/gid '-c'`）
     * ③ `su root -c id` —— 部分老版 SuperSU
     *
     * ⚠️ 会阻塞最多 3 × 2 秒，**只能在后台线程调用**（首次会弹 Magisk 授权框）。
     */
    fun probeRoot(): Boolean {
        // 已有实测可用的 su 前缀就直接复用。
        //
        // 这条短路是必须的：每次进设置页都会走到这里，而每一轮探测都会让 Magisk
        // 弹一次授权框 —— 进一次设置页连弹三次，用户会以为应用在发疯。
        // 成功过的结果不会失效（除非卸载 su），所以缓存命中即视为可用。
        if (suPrefixCache != null) return true

        for (argv in listOf(
            arrayOf("su", "-c", "id"),
            arrayOf("su", "0", "id"),
            arrayOf("su", "root", "-c", "id")
        )) {
            if (trySu(*argv)) {
                Log.i(TAG, "probeRoot ok via '${argv.joinToString(" ")}'")
                return true
            }
        }
        Log.i(TAG, "probeRoot: no usable su")
        return false
    }

    private fun trySu(vararg argv: String): Boolean = runCatching {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        if (!waitExited(p, 2_000L)) {
            runCatching { p.destroy() }
            return@runCatching false
        }
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val ok = out.contains("uid=0")
        if (!ok && out.isNotBlank()) {
            Log.i(TAG, "trySu '${argv.joinToString(" ")}' -> ${out.trim().take(80)}")
        }
        ok
    }.getOrDefault(false)

    /**
     * 返回**实测可用**的 su 参数前缀（含 su 自身）。
     *
     * 调用方把要执行的 shell 命令**作为一个字符串**接在后面即可：
     * ```
     * ProcessBuilder(usableSuPrefix()!! + listOf("id")).start()
     * ```
     * 缓存结果：探测要起子进程，不该每次执行命令都探一遍。
     */
    @Volatile
    private var suPrefixCache: List<String>? = null

    fun usableSuPrefix(): List<String>? {
        suPrefixCache?.let { return it }
        val candidates = listOf(
            listOf("su", "-c"),
            listOf("su", "0"),
            listOf("su", "root", "-c")
        )
        for (prefix in candidates) {
            if (trySu(*(prefix + listOf("id")).toTypedArray())) {
                suPrefixCache = prefix
                return prefix
            }
        }
        return null
    }

    /** 清掉缓存的 su 前缀（用户可能刚刚授权/卸载了 su）。 */
    fun invalidateSuCache() {
        suPrefixCache = null
    }

    /**
     * 是否已经**实测确认**过 su 可用 —— 只读缓存，**不起子进程**。
     *
     * 供 [com.example.fcldsh.AgentGuide] 生成 agent 文档时使用：那条路径可能在
     * 主线程上（`A11yBridgeServer.start` → `writeDiscovery`），绝不能调
     * [probeRoot]（那会最多起 3 个子进程、首次还会弹 Magisk 授权框）。
     *
     * 返回 false 只代表「还没确认过」，不代表「一定没有 root」。
     * 对写文档来说这个保守方向是对的：没确认就按做不到来讲，
     * agent 不会去试一条可能卡住的命令。
     */
    fun hasKnownRoot(): Boolean = suPrefixCache != null

    /**
     * 以 root 身份执行一条命令。
     *
     * @param command 完整 shell 命令（调用方自行拼引用）
     * @return 合并的 stdout+stderr；无可用 su 或超时返回 null
     */
    fun runSu(command: String, timeoutMs: Long = 8_000L): String? {
        // ⚠️ 刻意写成**块体**并让 `return` 都发生在 runCatching **外面**。
        //
        // 早先是 `fun runSu(...): String? = runCatching { ... return null ... }.getOrNull()`。
        // 表达式体 + lambda 内非局部 `return` 会让 lambda 的推断类型变成
        // `Nothing`，于是 `runCatching` 得到 `Result<Nothing>`、`getOrNull()`
        // 得到 `Nothing?`；接着调用方 `runSu(cmd).also { ... }` 就被判成
        // 「Only safe (?.) or non-null asserted calls are allowed on a nullable
        // receiver」。这不是真实的可空性，纯粹是类型推断被 `Nothing` 污染。
        val prefix = usableSuPrefix() ?: return null
        return runCatching {
            val p = ProcessBuilder(*(prefix + listOf(command)).toTypedArray())
                .redirectErrorStream(true)
                .start()
            if (!waitExited(p, timeoutMs)) {
                runCatching { p.destroy() }
                Log.w(TAG, "runSu timeout: ${command.take(60)}")
                return@runCatching null
            }
            p.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    // ==================== Shizuku ====================

    /**
     * Shizuku 库要求的最低系统版本。
     *
     * `dev.rikka.shizuku:*:13.1.5` 的 AAR 声明 `minSdk 24`，而本项目是 23
     * （见 AndroidManifest 里的 `tools:overrideLibrary`）。**每一条会触碰
     * `rikka.shizuku.*` 类的路径都必须先过这道门禁**，否则在 Android 6 上
     * 会在类加载阶段抛 NoClassDefFoundError —— 而它不是 Exception，
     * 只有 catch Throwable 才拦得住，`runCatching` 恰好是。
     * 即便如此，显式判断比依赖 catch 更清楚。
     */
    private const val SHIZUKU_MIN_SDK = 24

    /** 本机系统版本是否支持 Shizuku 库。 */
    fun shizukuSupported(): Boolean = Build.VERSION.SDK_INT >= SHIZUKU_MIN_SDK

    /** 设备上是否装了 Shizuku（只看包，不看授权；不触碰 Shizuku 库的类）。 */
    fun shizukuInstalled(ctx: Context): Boolean = SHIZUKU_PACKAGES.any { pkg ->
        runCatching {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)
    }

    /** Shizuku 服务是否在线（binder 通了）。 */
    fun shizukuRunning(): Boolean {
        if (!shizukuSupported()) return false
        return runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
    }

    /**
     * 本应用是否已获得 Shizuku 授权。
     *
     * **只回答「这条权限有没有被授予」**，与参考项目
     * （Deepseek-Harness-Local-Android 的 `Privilege.shizukuGranted()`）
     * 逐字一致：一趟 `checkSelfPermission()`，不加任何附加条件。
     *
     * ## ⚠️ 绝不能拿它单独当「可用」用（实测事故）
     *
     * 用户关掉 Shizuku 后，界面仍显示「已授权」、AGENTS.md 也写成
     * 「Shizuku 模式（已授权）」，但 `shz` 命令根本不存在、`id -u` 还是应用 uid。
     *
     * 原因就是把这个查询当成了「可用」的充分条件 —— 它不回答「服务在不在」。
     * 服务没起来时 binder 是死的，但权限记录仍然是「已授予」。
     *
     * 所以「有没有授权」与「能不能用」必须分开：
     * 判断可用一律用 [shizukuUsable] 或 [shizukuState]。
     * 之前我曾把 `!shizukuRunning()` 塞进本函数来「修」那个 bug，
     * 结果是让这个查询名不副实（授权状态被服务状态污染）。
     * 正确的做法是分档（见 [ShizukuState]），而不是篡改语义。
     */
    fun shizukuGranted(): Boolean {
        if (!shizukuSupported()) return false
        return runCatching {
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * Shizuku 通道当前是否**真的能用**：系统版本够 **且** 服务在线 **且** 已授权。
     *
     * 与参考项目的 `shizukuUsable()` 同构（它没有 SDK 门禁那一项，
     * 因为参考项目 minSdk 24 本身就不低于 Shizuku 库的要求）。
     *
     * 这是 UI、AGENTS.md、桥接启动**唯一**该看的判据。
     */
    fun shizukuUsable(): Boolean =
        shizukuSupported() && shizukuRunning() && shizukuGranted()

    /**
     * 通道状态的分档描述 —— 用于 UI 文案与 agent 文档。
     *
     * 之所以要分档而不是一个布尔：``服务未运行`` 与 ``未授权`` 对用户/agent
     * 是完全不同的行动项（去启动服务 vs 去点授权），
     * 合成一句「不可用」等于把排查方向丢掉。
     */
    enum class ShizukuState {
        /** 系统版本低于库要求（Android 7 以下）。 */
        UNSUPPORTED,
        /** 设备上没有装 Shizuku。 */
        NOT_INSTALLED,
        /** 装了，但服务没在运行（没有常驻通知、servicemanager 里没有 binder）。 */
        NOT_RUNNING,
        /** 服务在跑，但本应用没被授权。 */
        NOT_GRANTED,
        /** 三个条件都满足，`shz` 通道可用。 */
        READY
    }

    /** 实时计算一次通道状态。做 binder 调用，建议在后台线程调用（主线程亦可，开销很小）。 */
    fun shizukuState(ctx: Context): ShizukuState = when {
        !shizukuSupported() -> ShizukuState.UNSUPPORTED
        !shizukuInstalled(ctx) && !shizukuRunning() -> ShizukuState.NOT_INSTALLED
        !shizukuRunning() -> ShizukuState.NOT_RUNNING
        !shizukuGranted() -> ShizukuState.NOT_GRANTED
        else -> ShizukuState.READY
    }

    /**
     * 功能自检：真的用 Shizuku 执行一次 `id -u`，确认拿到 adb 身份。
     *
     * ## 为什么光看状态不够
     *
     * `pingBinder()` 只证明「binder 对象在」，不证明「能执行命令」；
     * `checkSelfPermission()` 只证明「权限被授予」。两者都成立而实际执行失败
     * 的情况是存在的（服务刚被杀、权限被撤但状态没刷新）。
     *
     * 所以这是唯一的地面真相：**跑一条命令，看 uid 是不是 2000**。
     *
     * ⚠️ 会起进程，**只能在后台线程调用**。
     */
    fun shizukuSelfTest(ctx: Context): Boolean {
        if (!shizukuUsable()) return false
        return runCatching {
            val out = shizukuExec(ctx, "id -u", timeoutMs = 5_000L)
            val uid = out.trim().lines().lastOrNull { it.isNotBlank() }?.trim()
            val ok = uid == "2000"
            Log.i(TAG, "shizukuSelfTest: uid=$uid ok=$ok")
            ok
        }.getOrDefault(false)
    }

    // ==================== 授权状态订阅 ====================

    /**
     * Shizuku 状态变化回调。
     *
     * ## 为什么必须有它（实测 bug：授权后 UI 仍显示「未授权」）
     *
     * 界面原先只在 `ON_RESUME` 时重探一次状态。但 Shizuku 的授权框是**叠在
     * 本页之上的一层**（由 Shizuku 自己的进程弹出），用户点「允许」时本应用
     * 通常**不会**经历 `onPause → onResume` —— 于是那次重探根本没发生，
     * 界面就一直停在进页那一刻的旧值，看起来像「授权没生效」。
     *
     * 正确做法是订阅 Shizuku 自己的回调：授权结果、binder 上下线都会通知。
     * 这三个回调覆盖了全部状态跃迁：
     * - [rikka.shizuku.Shizuku.addRequestPermissionResultListener]：用户点了允许/拒绝
     * - [rikka.shizuku.Shizuku.addBinderReceivedListenerSticky]：服务启动（含已在线的立即回调）
     * - [rikka.shizuku.Shizuku.addBinderDeadListener]：服务停止/被杀
     *
     * 用自己的接口而不是直接暴露 `rikka.shizuku.*` 的类型：UI 层不该依赖
     * Shizuku 库的类 —— 那样一旦在 Android 6 上提升类型签名就会触发类加载。
     */
    fun interface StateListener {
        /** Shizuku 状态可能变了，调用方应重探并刷新界面。 */
        fun onShizukuStateChanged()
    }

    private val stateListeners =
        java.util.concurrent.CopyOnWriteArraySet<StateListener>()

    @Volatile
    private var rikkaListenersRegistered = false

    /**
     * 只注册一次底层监听。多次调用无副作用。
     *
     * Shizuku 的监听是**进程级**的，与某个界面无关；所以这里用「注册一次 +
     * 自己维护回调集合」的方式，界面来去只增删 [stateListeners]，
     * 避免反复 add/remove 底层监听（那会随列表重排重复注册）。
     */
    @Synchronized
    private fun ensureRikkaListeners() {
        if (rikkaListenersRegistered || !shizukuSupported()) return
        runCatching {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, _ ->
                notifyStateListeners()
            }
            // Sticky 版本：若 binder 此刻已就绪，注册时会**立即**回调一次。
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
                notifyStateListeners()
            }
            rikka.shizuku.Shizuku.addBinderDeadListener {
                notifyStateListeners()
            }
            rikkaListenersRegistered = true
            Log.i(TAG, "shizuku listeners registered")
        }.onFailure { Log.w(TAG, "register shizuku listeners failed: ${it.message}") }
    }

    private fun notifyStateListeners() {
        // 这里不再维护任何「授权缓存」。
        //
        // 早先存了一个 `startupGranted` 布尔，UI 与 AGENTS.md 都读它 ——
        // 结果用户关掉 Shizuku 之后，界面仍显示「已授权」，而 `shz` 命令
        // 压根没生成、`id -u` 还是应用 uid。根因是那个缓存只由
        // `checkSelfPermission()` 喂养，而它**不反映服务是否在运行**。
        //
        // 现在所有判断都走实时的 [shizukuUsable] / [shizukuState]，
        // 回调只负责「通知界面该重读了」。
        for (l in stateListeners) {
            // 单个回调抛异常不能拖累其它监听者
            runCatching { l.onShizukuStateChanged() }
        }
    }

    // ==================== 启动时检查 ====================

    /**
     * 应用启动时调一次：挂上状态监听，并把当时的状态打进日志。
     *
     * ## 为什么要在启动时就查（而不是等用户点进设置页）
     *
     * 用户的要求是「先在整个软件启动时检查有没有 Shizuku 授权」：进设置页时
     * 状态应当**已经**是准的。同时 `ensureRikkaListeners()` 越早挂越好 ——
     * `addBinderReceivedListenerSticky` 在 binder 已就绪时会立刻回调一次，
     * 正好把「Shizuku 服务稍后才启动」这种时序也覆盖掉。
     *
     * **不缓存结论**：只打日志。原因是缓存过就必然有过期，
     * 而这个状态恰恰会变（用户随时可以关掉服务）。
     *
     * 会做 binder 调用，**建议在后台线程调用**。
     */
    fun startupCheck(ctx: Context) {
        runCatching {
            ensureRikkaListeners()
            val state = shizukuState(ctx)
            Log.i(
                TAG,
                "startupCheck: sdk=${Build.VERSION.SDK_INT} supported=${shizukuSupported()} " +
                    "installed=${shizukuInstalled(ctx)} running=${shizukuRunning()} " +
                    "granted=${shizukuGranted()} state=$state usable=${state == ShizukuState.READY}"
            )
        }.onFailure { Log.w(TAG, "startupCheck failed: ${it.message}") }
    }

    /**
     * 「Shizuku 模式」当前是否可选。
     *
     * ✅ 实时判定（[shizukuUsable]），**不读任何缓存**。
     * 服务没运行 → false，界面那一行就是灰的、点不动。
     */
    fun shizukuModeSelectable(): Boolean = shizukuUsable()

    /**
     * 重新实时读一次「能不能用」。
     *
     * **便宜**（一次 binder ping + 一次权限查询，不做 root 探测、不起子进程），
     * 所以可以在设置面板打开期间低频轮询 —— 这是监听回调之外的兜底：
     * 不同 Shizuku 版本对状态回调的行为并不完全一致，轮询能保证
     * 状态在一个很短的时间窗内一定收敛到真实值。
     */
    fun refreshGranted(): Boolean = shizukuUsable()

    /** 订阅状态变化。界面进入时调用，须与 [removeStateListener] 成对。 */
    fun addStateListener(l: StateListener) {
        stateListeners.add(l)
        ensureRikkaListeners()
    }

    fun removeStateListener(l: StateListener) {
        stateListeners.remove(l)
    }

    /**
     * 请求 Shizuku 授权。
     *
     * 用户此前**明确拒绝过**且本次不是主动再要（[explicit]=false）时直接返回 ——
     * 「拒绝即尊重」：不能每次进设置页都弹一次框。
     *
     * @param explicit 用户主动点了「请求授权」
     */
    fun requestShizukuPermission(ctx: Context, explicit: Boolean) {
        if (!shizukuSupported()) {
            Log.i(TAG, "requestShizukuPermission: SDK ${Build.VERSION.SDK_INT} < $SHIZUKU_MIN_SDK")
            return
        }
        if (!shizukuRunning()) {
            Log.i(TAG, "requestShizukuPermission: service not running")
            return
        }
        if (shizukuGranted()) return
        if (shizukuDenied(ctx) && !explicit) {
            Log.i(TAG, "requestShizukuPermission: previously denied, skip auto prompt")
            return
        }
        if (explicit) markShizukuDenied(ctx, false)
        runCatching { rikka.shizuku.Shizuku.requestPermission(REQUEST_CODE_SHIZUKU) }
            .onFailure { Log.w(TAG, "requestPermission failed: ${it.message}") }
    }

    /**
     * 以 Shizuku（adb uid 2000）身份执行 shell 命令。
     *
     * 官方链路：`Shizuku.getBinder()` → `IShizukuService.newProcess` →
     * `IRemoteProcess` 的 ParcelFileDescriptor 读输出。
     *
     * ## 超时是必须的
     *
     * `readText()` 会阻塞到流关闭（只在进程退出、管道关闭后出现），
     * `IRemoteProcess.waitFor()` 也是**无参阻塞**调用（AIDL 就这么定义，
     * 没有 Java `Process` 那样的 `waitFor(timeout, unit)` 重载）。
     * 所以读流与等待都放后台线程，调用线程按 deadline 返回 —— 否则一条卡住的
     * 命令会把整个调用方（可能是启动热路径）永久钉死，且没有任何日志。
     *
     * 超时**不当成失败**：返回已读到的部分输出，避免把「环境慢」误判成「命令失败」。
     */
    fun shizukuExec(ctx: Context, cmd: String, timeoutMs: Long = 10_000L): String {
        // 门禁先于任何 `rikka.shizuku.*` 引用生效：Android 6 上这些类根本不存在。
        if (!shizukuSupported()) {
            throw UnsupportedOperationException(
                "Shizuku 需要 Android $SHIZUKU_MIN_SDK+，本机是 ${Build.VERSION.SDK_INT}"
            )
        }
        Log.i(TAG, "shizukuExec: ${ctx.packageName} cmd=${cmd.take(80)}")
        val binder = rikka.shizuku.Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku binder 不可用（服务未运行？）")
        val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
            ?: throw IllegalStateException("Shizuku 服务接口不可用")
        val rp = svc.newProcess(
            arrayOf("sh", "-c", cmd),
            arrayOf("PATH=/system/bin:/system/xbin"),
            null
        )

        val deadline = System.currentTimeMillis() + timeoutMs
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                for (pfd in arrayOf(rp.inputStream, rp.errorStream)) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                        .bufferedReader().use { br ->
                            val buf = CharArray(4096)
                            while (true) {
                                val n = br.read(buf)
                                if (n <= 0) break
                                synchronized(sb) { sb.append(buf, 0, n) }
                            }
                        }
                }
            }
        }.apply { isDaemon = true; name = "dsh-shizuku-read"; start() }

        val waiter = Thread { runCatching { rp.waitFor() } }
            .apply { isDaemon = true; name = "dsh-shizuku-wait"; start() }
        waiter.join(maxOf(1L, deadline - System.currentTimeMillis()))
        val finished = !waiter.isAlive
        if (!finished) {
            Log.w(TAG, "shizukuExec timed out after ${timeoutMs}ms: ${cmd.take(80)}")
            runCatching { rp.destroy() }
        }
        runCatching { reader.join(300L) }
        val out = synchronized(sb) { sb.toString() }
        return if (finished) out else out + "\n[timeout after ${timeoutMs}ms]"
    }

    // ==================== 统一入口 ====================

    /**
     * 按当前模式执行一条命令：**Shizuku 优先 → su 兜底**。
     *
     * 为什么不按「当前选中的模式」硬分流：用户选了 Shizuku 但设备上 su 也活着
     * （或反之）时，能用就用比报错有用。模式只决定**UI 上默认尝试哪条**，
     * 真正执行时两条都试。
     *
     * @return 命令输出；两条通道都不可用返回 null
     */
    fun runPrivileged(ctx: Context, command: String): String? {
        // ⚠️ 不用 `runCatching { return ... }`：lambda 里的非局部 return 会把
        // lambda 类型推成 `Nothing`，`onFailure { it.message }` 随即被判成
        // 「nullable receiver」。用 getOrElse 拿到值再统一分流，既避开推断问题、
        // 也让两条通道的分支一眼看得清。
        if (shizukuUsable()) {
            val viaShizuku = runCatching { shizukuExec(ctx, command) }.getOrElse { e ->
                Log.w(TAG, "runPrivileged shizuku failed: ${e.message}")
                null
            }
            if (viaShizuku != null) return viaShizuku
        }
        val viaSu = runSu(command)
        if (viaSu == null) {
            Log.w(TAG, "runPrivileged: neither shizuku nor su available")
        }
        return viaSu
    }

    /**
     * 探测全部能力。会起子进程（[probeRoot] 最多 6 秒），**只能在后台线程调用**。
     */
    fun probe(ctx: Context): PrivCapability = PrivCapability(
        hasRoot = probeRoot(),
        hasShizuku = shizukuInstalled(ctx) || shizukuRunning(),
        shizukuGranted = shizukuGranted()
    )

    // ==================== 工具 ====================

    /**
     * 等子进程退出，带超时；返回 true 表示已退出。
     *
     * ## 为什么不用 `Process.waitFor(timeout, unit)`
     *
     * 那个重载是 **API 26** 才有的，而本项目 minSdk 是 23 —— 在 23–25 的设备上
     * 直接 `NoSuchMethodError`，而且是在探测 Root 这条最基础的路径上崩。
     * 所以低版本退化成「轮询 exitValue + 小睡」：精度差 20ms，但不会崩。
     */
    private fun waitExited(p: Process, timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= 26) {
            return runCatching { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                // 仍在运行，继续等
            }
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * 给 shell 拼接安全地引用一个参数（POSIX 单引号转义）。
     *
     * 只包一层双引号是不够的：路径里出现 `$` `\` 反引号时会被 shell 展开，
     * 而这里的输入包含用户可控的目录名。
     */
    fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
