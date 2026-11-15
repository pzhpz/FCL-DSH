package com.example.fcldsh

import android.content.Context
import androidx.compose.ui.graphics.toArgb

/**
 * dsh 沙箱权限模式。
 *
 * 通过环境变量 `DSH_PERMISSION_MODE` 下发给 Node 进程，由 dsh 的
 * `sandbox-policy` 行读取（见 base bundle：
 * `mode: !!js process.env.DSH_PERMISSION_MODE ?? 'workspace-write'`）。
 *
 * ## 为什么默认是 workspace-write 而不是 danger-full-access
 *
 * 之前为了在 Android 上让 `bash` / `glob` / `grep` 可用，代码里**写死**了
 * `danger-full-access`。那等于让模型生成的命令以本 App 的 uid 无隔离执行，
 * 可读写 `DSH_HOME` 下的凭据与会话记录；上游还会把审批策略从 `ask` 改成
 * `never`，即不再逐条询问。这个代价不该由代码单方面决定，必须由用户显式选择。
 *
 * 所以现在：
 * - 默认回退到 `workspace-write`（安全优先）；
 * - 需要命令执行能力时，在启动器设置里显式开启「完全访问」，并接受风险提示。
 *
 * ## Android 上的现实
 *
 * `workspace-write` 要求宿主能真正实施写入隔离（bubblewrap 的非特权 user
 * namespace，或 Landlock LSM）。Android 内核通常两者都不具备，于是 dsh 会
 * 拒绝执行命令而不是裸跑 —— 表现为 `bash` / `glob` / `grep` 全部不可用。
 * 这是安全换来的代价，不是 bug。
 */
enum class PermissionMode(
    /** 传给 dsh 的原始值，必须与上游 schema 完全一致。 */
    val id: String,
    val label: String,
    val desc: String,
    /** 是否为危险模式：UI 上需要额外警示。 */
    val dangerous: Boolean
) {
    WORKSPACE_WRITE(
        id = "workspace-write",
        label = "工作区可写（推荐）",
        desc = "只允许写入实例的工作区目录，命令执行受沙箱约束。" +
            "注意：Android 缺少 bubblewrap / Landlock，此模式下 bash、glob、grep " +
            "会被 dsh 拒绝执行——这是安全换来的代价。",
        dangerous = false
    ),

    READ_ONLY(
        id = "read-only",
        label = "只读",
        desc = "禁止一切写入。文件读取正常，写入与命令执行都会被拒绝。",
        dangerous = false
    ),

    DANGER_FULL_ACCESS(
        id = "danger-full-access",
        label = "完全访问（危险）",
        desc = "不施加任何隔离，模型生成的命令以本 App 身份直接执行，" +
            "可读写 DSH_HOME 下的凭据与会话记录；审批策略同时变为「从不询问」。" +
            "仅在需要 bash / 搜索能力时开启。",
        dangerous = true
    );

    companion object {
        /** 安全默认值。 */
        val DEFAULT = WORKSPACE_WRITE

        /**
         * 按 id 反查。未知值一律回退到默认，避免旧配置或手改文件导致启动失败。
         *
         * 用 `values()` 而不是 `entries`：后者需要较新的 Kotlin 版本，
         * 本项目未显式声明，用 `values()` 更稳。
         */
        fun fromId(id: String?): PermissionMode =
            values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * dsh 网页窗口（[com.example.fcldsh.DshWebActivity]）的屏幕方向。
 *
 * 只影响那个全屏网页窗口，**不影响启动器本身**（启动器在 Manifest 里锁
 * `sensorLandscape`，因为它左侧有固定宽度的导航栏，竖屏下会被挤成一条缝）。
 */
enum class DshScreenMode(val label: String, val desc: String) {
    /** 跟随重力感应，四个方向都可能。 */
    AUTO(
        "自动",
        "跟随重力感应旋转。横屏看长对话更舒服，竖屏更适合阅读。"
    ),

    /** 锁定横屏。 */
    LANDSCAPE(
        "强制横屏",
        "始终横向显示。左侧会话栏与正文并排，接近桌面端布局。"
    ),

    /** 锁定竖屏。 */
    PORTRAIT(
        "强制竖屏",
        "始终竖向显示。单栏布局，手机上读长文本更省眼。"
    );

    companion object {
        val DEFAULT = AUTO

        /**
         * 按 name 反查。未知值回退默认 —— 旧配置或手改文件不该让窗口打不开。
         *
         * 用 `values()` 而非 `entries`：后者需要较新的 Kotlin 版本。
         */
        fun fromName(name: String?): DshScreenMode =
            values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 启动器的轻量偏好存储。
 *
 * 目前只放权限模式一项。阶段三引入多实例后，实例级的覆盖值会存进
 * 各实例自己的配置（`filesDir/instances/<id>/instance.json`），
 * 这里的全局值退化为「新实例的默认值」。
 */
object LauncherPrefs {

    private const val FILE = "launcher_prefs"
    private const val KEY_PERMISSION = "permission_mode"
    private const val KEY_ENGINE_URL = "engine_url"
    private const val KEY_DSH_SCREEN_MODE = "dsh_screen_mode"
    private const val KEY_DSH_ZOOM = "dsh_zoom_percent"
    private const val KEY_THEME_COLOR = "theme_color"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_THEME_ALPHA = "theme_alpha"
    private const val KEY_ENGINE_VERSION = "engine_version"
    private const val KEY_VERSION_ISOLATION = "version_isolation"
    private const val KEY_DATA_MIGRATED = "data_migrated"
    private const val KEY_PLUGIN_SOURCE = "plugin_source_url"
    private const val KEY_FORCE_UNSUPPORTED = "force_unsupported_engine"
    private const val KEY_EXIT_BTN_X = "web_exit_btn_x"
    private const val KEY_EXIT_BTN_Y = "web_exit_btn_y"
    private const val KEY_TASK_OVERLAY = "task_overlay"

    /**
     * dsh 引擎下载地址的默认值。
     *
     * 留空是刻意的：引擎包由使用者自行托管，内置一个我无法核实的地址
     * 反而会误导（下载 404 或内容不对的包）。首次进入下载中心时若为空，
     * UI 会提示填写，同时保留「从本地文件安装」这条退路。
     */
    const val DEFAULT_ENGINE_URL = ""

    /** 读取全局权限模式。未设置过时返回安全默认值。 */
    fun getPermissionMode(context: Context): PermissionMode {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return PermissionMode.fromId(sp.getString(KEY_PERMISSION, null))
    }

    /** 写入全局权限模式。 */
    fun setPermissionMode(context: Context, mode: PermissionMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERMISSION, mode.id)
            .apply()
    }

    /** 读取 dsh 引擎下载地址。 */
    fun getEngineUrl(context: Context): String {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getString(KEY_ENGINE_URL, DEFAULT_ENGINE_URL).orEmpty()
    }

    /** 写入 dsh 引擎下载地址。 */
    fun setEngineUrl(context: Context, url: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENGINE_URL, url.trim())
            .apply()
    }

    /**
     * 是否允许启动「预检判定为不可运行」的引擎版本。默认关。
     *
     * ## 为什么需要这个开关
     *
     * dsh 0.2.2-alpha.x 缺少上游从未发布的 android 原生绑定，本机必然起不来。
     * 预检会直接中止启动，不再把上百行堆栈倒给用户 —— 但这建立在
     * 「我们的判定是对的」这个前提上。
     *
     * 判定逻辑（看父包在不在、看 optionalDependencies 有没有 android 变体、
     * 看 build 产物）终归是静态推断，可能因上游结构变化而误判。
     * 留一个显式开关，用户仍能强行尝试；默认关，避免正常用户被误判挡住。
     */
    fun getForceUnsupportedEngine(context: Context): Boolean {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_FORCE_UNSUPPORTED, false)
    }

    /** 写入「允许启动不受支持的引擎」。 */
    fun setForceUnsupportedEngine(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_UNSUPPORTED, on)
            .apply()
    }

    /**
     * 网页窗口里那个悬浮「✕」关闭键的位置，归一化成 0..1。
     *
     * 默认 `(1f, 0f)` = 右上角（与改动前的固定位置一致）。
     *
     * ## 为什么存比例而不是像素
     *
     * 像素值在换设备、转屏、分屏后可能落到屏幕外；比例只要乘上当前可用
     * 宽高就一定在范围内。这也是拖动后能「记住位置」的实现基础。
     */
    fun getWebExitButtonPos(context: Context): Pair<Float, Float> {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getFloat(KEY_EXIT_BTN_X, 1f) to sp.getFloat(KEY_EXIT_BTN_Y, 0f)
    }

    /** 写入关闭键位置（会夹到 0..1，防止越界值把按钮放到屏幕外）。 */
    fun setWebExitButtonPos(context: Context, x: Float, y: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_EXIT_BTN_X, x.coerceIn(0f, 1f))
            .putFloat(KEY_EXIT_BTN_Y, y.coerceIn(0f, 1f))
            .apply()
    }

    /**
     * 插件下载源（自定义索引地址）。空串表示用内置的 [DSHOPEN_INDEX]。
     *
     * 单独存一项而不是复用引擎地址：两者是完全不同的东西
     * （一个指向引擎 zip，一个指向插件市场的 index.json），
     * 混用会让用户改了一个另一个跟着变。
     */
    fun getPluginSourceUrl(context: Context): String {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getString(KEY_PLUGIN_SOURCE, "").orEmpty()
    }

    /** 写入插件下载源；传空串即恢复默认源。 */
    fun setPluginSourceUrl(context: Context, url: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLUGIN_SOURCE, url.trim())
            .apply()
    }

    // ── 引擎版本指针（多版本共存）────────────────────────────────────

    /**
     * 当前使用中的引擎版本号；没装过返回 null。
     *
     * 这里只存「选了哪个」，不存「装了哪些」—— 后者以 `usr/lib/versions/`
     * 下的实际目录为准。磁盘才是唯一可信来源，偏好文件丢掉或写坏时，
     * [com.DSHRunner.currentVersion] 会自动回落到最新的已装版本。
     */
    fun getEngineVersion(context: Context): String? {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getString(KEY_ENGINE_VERSION, null)?.takeIf { it.isNotBlank() }
    }

    /** 写入当前引擎版本。 */
    fun setEngineVersion(context: Context, version: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENGINE_VERSION, version)
            .apply()
    }

    /**
     * 清掉当前引擎版本的指针。
     *
     * 删除「正在使用的那一版」且没有别的版本可回落时用它。
     * 由调用方（[com.DSHRunner.deleteVersion]）负责判断要不要清，
     * 这样键名不会泄漏到别的文件里去硬编码。
     */
    fun clearEngineVersion(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENGINE_VERSION)
            .apply()
    }

    // ── 数据目录迁移标记 ─────────────────────────────────────────────

    /**
     * 是否已经做过「共享数据目录 → 按版本独立数据目录」的一次性迁移。
     *
     * ## 为什么需要这个标记
     *
     * 早期所有版本共用一个 `home/.dsh`。现在改成每个版本自己的
     * `home/.dsh-<版本>`（见 [com.DSHRunner.dshHome]），于是老用户升级后
     * 会看到「会话和登录态全没了」——数据还在 `home/.dsh`，只是新版本不再读它。
     *
     * 所以第一次启动时把老目录复制进当时正在用的那个版本，标记置位后就不再复制。
     * **只迁移一次**：之后新装的版本一律从空目录开始，
     * 符合「各个 dsh 不互通」的要求，不会把老会话带进新版本。
     */
    fun isDataMigrated(context: Context): Boolean {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DATA_MIGRATED, false)
    }

    /** 标记迁移已完成。 */
    fun setDataMigrated(context: Context, done: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DATA_MIGRATED, done)
            .apply()
    }

    // ── dsh 网页窗口（方向 / 缩放）──────────────────────────────────
    //
    // 这两个偏好只影响 DshWebActivity（那个全屏网页窗口），
    // 与启动器自身的 Manifest 方向锁定、以及 dsh 进程都无关。

    /** 读取 dsh 窗口的屏幕方向偏好；未设置过时返回「自动」。 */
    fun getDshScreenMode(context: Context): DshScreenMode {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return DshScreenMode.fromName(sp.getString(KEY_DSH_SCREEN_MODE, null))
    }

    /** 写入 dsh 窗口的屏幕方向偏好。下次打开窗口时生效。 */
    fun setDshScreenMode(context: Context, mode: DshScreenMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DSH_SCREEN_MODE, mode.name)
            .apply()
    }

    /**
     * 读取 dsh 窗口的缩放百分比（50..200，默认 75）。
     *
     * 越界值一律夹回区间：手改偏好文件或旧版本遗留值不该让页面缩到看不见。
     */
    fun getDshZoom(context: Context): Int {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getInt(KEY_DSH_ZOOM, DEFAULT_ZOOM).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** 写入 dsh 窗口的缩放百分比。下次打开窗口时生效。 */
    fun setDshZoom(context: Context, percent: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DSH_ZOOM, percent.coerceIn(MIN_ZOOM, MAX_ZOOM))
            .apply()
    }

    /** 缩放百分比的合法范围。上限 200 已足够看清小字，再大只会剩几个字。 */
    const val MIN_ZOOM = 50
    const val MAX_ZOOM = 200

    /**
     * 默认缩放 75%。
     *
     * 100% 是按桌面 DPI 排版的观感 —— 手机屏幕窄，dsh 页面在 100% 下一屏
     * 只放得下很少内容。75% 既不用左右拖动，字也仍然清楚。
     *
     * ★ 这个常量是**唯一**的默认值来源 ★
     * [getDshZoom] 的缺省参数、设置页里「是否已改动」的高亮判断、
     * DshWebActivity 的初始值都引用它，别再各写一个字面量 100/75。
     */
    const val DEFAULT_ZOOM = 75

    // ── 主题设置（纯 UI 偏好，不影响 dsh 进程的任何行为）──────────────

    /** 读取主题色。未设置过时返回 FCL 的默认主题色。 */
    fun getThemeColor(context: Context): Int {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getInt(KEY_THEME_COLOR, Fcl.DefaultTheme.toArgb())
    }

    /** 写入主题色。 */
    fun setThemeColor(context: Context, color: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_COLOR, color)
            .apply()
    }

    /** 读取明暗模式。未设置过时返回「跟随系统」。 */
    fun getThemeMode(context: Context): Fcl.Mode {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val name = sp.getString(KEY_THEME_MODE, null) ?: return Fcl.Mode.FOLLOW
        return Fcl.Mode.values().firstOrNull { it.name == name } ?: Fcl.Mode.FOLLOW
    }

    /** 写入明暗模式。 */
    fun setThemeMode(context: Context, mode: Fcl.Mode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }

    /** 读取面板不透明度（0..1）。 */
    fun getPanelAlpha(context: Context): Float {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getFloat(KEY_THEME_ALPHA, 0.5f)
    }

    /** 写入面板不透明度（0..1）。 */
    fun setPanelAlpha(context: Context, alpha: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_THEME_ALPHA, alpha.coerceIn(0f, 1f))
            .apply()
    }

    // ── 任务悬浮窗 ──────────────────────────────────────────────────

    /**
     * 是否在应用退到后台时显示任务悬浮窗。默认 **关**。
     *
     * ## 为什么默认关
     *
     * 悬浮窗会盖在**别的应用**上面（包括用户正在读的文档、正在操作的银行页面）。
     * 不请自来地往别人屏幕上挂一块东西是侵入性的，本启动器已有的
     * 「所有文件访问」「无障碍」「屏幕截图」全是默认关 + 显式引导，
     * 这一项保持一致。
     *
     * 另外它需要 `SYSTEM_ALERT_WINDOW` 这个特殊权限，而这个权限
     * 只能由用户到系统设置页手动打开 —— 默认开等于让用户每次切后台
     * 都撞一次「添加失败」。
     *
     * 真正的显示判定见 [TaskOverlay.sync]：偏好只是三个条件之一。
     */
    fun getTaskOverlay(context: Context): Boolean {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_TASK_OVERLAY, false)
    }

    /** 写入任务悬浮窗开关。 */
    fun setTaskOverlay(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TASK_OVERLAY, on)
            .apply()
    }
}
