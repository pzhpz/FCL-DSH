package com.example.fcldsh

import android.content.ClipDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.DSHRunner

/**
 * 侧栏面板进出场动画时长（毫秒）。
 *
 * 原先是 220ms，按需求**速度减一半**，所以时长翻倍成 440ms。
 * 配合 [PanelEasing] 的缓入缓出，起步与收尾都更柔和 ——
 * 匀速做完 440ms 会显得「拖」，加了缓动之后同样时长感觉更快更顺。
 */
private const val PANEL_ANIM_MS = 440

/**
 * 面板进出的缓动曲线：缓入缓出。
 *
 * 用 Material 的 `FastOutSlowInEasing`（cubic-bezier 0.4, 0, 0.2, 1）：
 * 起步慢、中段快、收尾慢。纯线性在这段时长下会显得机械，
 * 而且结尾「啪」地停住很生硬。
 */
private val PanelEasing = androidx.compose.animation.core.FastOutSlowInEasing

/**
 * 去掉 [LauncherPanel.NONE] 之后的面板列表。
 *
 * [MainActivity.PanelHost] 要「每块面板各驱动一条动画」，需要一份顺序固定、
 * 不含 NONE（它不是一个真的面板）的列表。抽成常量是为了在循环里
 * 不必写条件分支 —— 组合函数在循环里被条件跳过时，一旦条件变化
 * 组合结构就会错位。
 */
private val REAL_PANELS: List<LauncherPanel> =
    LauncherPanel.values().filter { it != LauncherPanel.NONE }

/**
 * 启动器主界面（仿 FCL 布局）。
 *
 * ## 布局
 *
 * ```
 * FrameLayout
 *  ├── ComposeView      右侧主区域（含右侧浮层：账号 + 启动），leftMargin = 72dp
 *  └── LinearLayout     左侧原生导航栏，72dp，5 个图标
 * ```
 *
 * 左侧栏：主界面 / 实例设置 / 下载 / 启动器设置 / 退出。
 * 目前**只有「退出」和右侧的「启动」真正生效**，其余图标点了给提示。
 *
 * 右侧浮层仿 FCL：上方是账号信息，下方是版本 + 启动按钮 + 管理版本。
 *
 * ## 为什么侧栏用原生 View
 *
 * 它只占左侧 72dp 一条，触摸分发只在自己边界内生效。
 * 若用 Compose 铺满全屏再在左边画图标，整个覆盖层会拦住触摸（踩过这个坑）。
 *
 * ## 网页在哪
 *
 * 启动成功后网页在独立的 [DshWebActivity] 里全屏运行（仿 FCL 弹出游戏窗口），
 * 启动器退到后台；关掉网页窗口回到这里，状态由 [AppState] 共享。
 */
class MainActivity : ComponentActivity() {

    /** 共享状态。网页窗口 [DshWebActivity] 也读同一份。 */
    private val ui: LauncherState get() = AppState.launcher

    private lateinit var composeOverlay: ComposeView

    /** 左侧导航栏（原生 View）。 */
    private lateinit var navRail: LinearLayout

    /** 根容器。换主题时要重建左侧栏，需要能摘掉旧的那个。 */
    private lateinit var rootView: FrameLayout

    /**
     * 主题色预设。
     *
     * FCL 用取色器 + 从背景图提取，本项目没有壁纸，取色器又是另一个组件，
     * 所以给一排预设色板 —— 覆盖 FCL 默认蓝、深蓝、绿、橙、红、紫。
     * 第一个是 FCL 的 `default_theme_color`，起「重置」的作用。
     */
    private val themePresets = listOf(
        Color(0xFF7797CF), // FCL 默认
        Color(0xFF4E77C1), // FCL 深色主题色
        Color(0xFF3E9C5A), // 绿
        Color(0xFFC8842A), // 橙
        Color(0xFFC0453F), // 红
        Color(0xFF8E6FD0)  // 紫
    )

    /**
     * 刚选好、**还没开始安装**的本地引擎包。
     *
     * ## 为什么只存 URI，不在这里就开流安装
     *
     * 从本地装也要先给实例起名字（与从 registry 下载同样处理）：
     * 同一个版本可以建多个实例，不命名就会把已有实例悄悄覆盖掉。
     *
     * 所以选择器返回后**只记下 URI**，由下载中心弹出命名窗，
     * 用户点确定之后才真正打开流去安装。
     *
     * 不在这里顺手把 `InputStream` 也开出来存着：SAF 的流在
     * 「选完文件 → 用户思考几十秒 → 点确定」这段时间里可能已经失效
     * （底层 fd 被回收），届时读出来是空包，症状是「安装完成但找不到 bin.js」。
     * 到用的时候再开，最稳。
     */
    private var pendingLocalZip by mutableStateOf<Uri?>(null)

    /**
     * 从本地选一个引擎 zip 安装。
     *
     * 给「没有稳定下载地址」的情况留退路：把引擎包放进手机存储，
     * 用系统文件选择器指过来即可。安装逻辑与网络下载完全共用
     * [DSHRunner.installDsh]，所以包结构要求一致。
     */
    private val engineZipLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择引擎包")
                return@registerForActivityResult
            }
            // 先只记下选择，交给命名窗；安装要等用户确认名字之后
            pendingLocalZip = uri
        }

    /**
     * 把已安装的 dsh 引擎导出成 zip。
     *
     * 用 SAF 的 `CreateDocument` 让用户自己挑保存位置，好处是**不需要任何
     * 存储权限**（也不依赖 MANAGE_EXTERNAL_STORAGE 有没有真的批下来），
     * 用户可以直接存到 Download、网盘目录或 U 盘。
     *
     * 打包本身走后台线程 + 前台保活：引擎是几千个小文件，
     * 边打包边切走应用时不能被系统冻结在半截。
     */
    private val engineExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择导出位置")
                return@registerForActivityResult
            }

            EngineTaskService.start(this, "正在导出 dsh 引擎")

            Thread {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        EngineExporter.exportTo(this, out, uri.toString()) > 0
                    } ?: run {
                        runOnUiThread { ui.log("❌ 无法打开所选位置的写入流") }
                        false
                    }
                }.getOrElse { e ->
                    runOnUiThread {
                        ui.log("❌ 导出失败: ${e.message ?: e.javaClass.simpleName}")
                    }
                    false
                }

                if (!ok) {
                    // 失败细节已由 EngineExporter 写进日志，这里只在 UI 侧兜一句
                    runOnUiThread { ui.log("❌ 引擎导出未完成") }

                    // ★ 把已经建出来的空文件删掉 ★
                    //
                    // 文件选择器在用户点「确定」的瞬间就把文件建好了。
                    // 导出失败时不清掉，用户手上就会多出一个 0 字节的 zip，
                    // 还会以为备份成功了。删除失败也无所谓（部分 SAF 提供方
                    // 不支持 deleteDocument），所以整体吞掉异常。
                    val removed = runCatching {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    }.getOrDefault(false)
                    runOnUiThread {
                        if (removed) {
                            ui.log("🧹 已删除未完成的空文件，避免留下 0 字节压缩包")
                        } else {
                            ui.log("⚠️ 请手动删除目标位置的空白压缩包")
                        }
                    }
                }
            }.start()
        }

    // ── 插件 / 会话的单独导入与导出 ────────────────────────────────
    //
    // 与「导出整合包」共用同一套 SAF 思路：CreateDocument 让用户挑保存位置、
    // GetContent 让用户挑要导入的文件，**不需要任何存储权限**。
    // 与整合包的区别只有一个：这里打的是纯数据（插件包 / 会话），不含引擎本体，
    // 因此体积小、速度快，适合日常搬运。

    private val pluginExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择插件导出位置")
                return@registerForActivityResult
            }
            exportToUri(uri, "正在导出插件") { out ->
                PluginSessionTransfer.exportPlugins(this, out)
            }
        }

    private val pluginImportLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择插件包")
                return@registerForActivityResult
            }
            importFromUri(uri, "正在导入插件") { input ->
                PluginSessionTransfer.importPlugins(this, input)
            }
        }

    private val sessionExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择会话导出位置")
                return@registerForActivityResult
            }
            exportToUri(uri, "正在导出会话") { out ->
                PluginSessionTransfer.exportSessions(this, out)
            }
        }

    private val sessionImportLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择会话包")
                return@registerForActivityResult
            }
            importFromUri(uri, "正在导入会话") { input ->
                PluginSessionTransfer.importSessions(this, input)
            }
        }

    // ── 单项导出 ────────────────────────────────────────────────────
    //
    // 「导出一个插件 / 一个会话」与「全部导出」用的是同一套 SAF，区别只在
    // 打包时传什么参数。但**参数不能经 lambda 捕获传进回调**：SAF 的启动是
    // 异步的（用户可能慢慢挑位置），回调触发时那次调用的栈早就没了。
    // 所以用这两个字段把「要导的是哪一个」带过去，回调里取完立刻清空 ——
    // 不清空的话，用户取消一次后再点另一个会沿用上一次的目标。

    /** 待导出的插件包名；null 表示当前没有待办的单项插件导出。 */
    private var pendingPluginExport: String? = null

    /** 待导出的会话标识（[SessionInfo.id]）。 */
    private var pendingSessionExport: String? = null

    private val singlePluginExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            val name = pendingPluginExport
            pendingPluginExport = null
            if (uri == null) {
                ui.log("❌ 未选择导出位置")
                return@registerForActivityResult
            }
            if (name == null) {
                ui.log("❌ 没有指定要导出的插件")
                return@registerForActivityResult
            }
            exportToUri(uri, "正在导出插件") { out ->
                PluginSessionTransfer.exportPlugin(this, out, name)
            }
        }

    private val singleSessionExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            val key = pendingSessionExport
            pendingSessionExport = null
            if (uri == null) {
                ui.log("❌ 未选择导出位置")
                return@registerForActivityResult
            }
            if (key == null) {
                ui.log("❌ 没有指定要导出的会话")
                return@registerForActivityResult
            }
            exportToUri(uri, "正在导出会话") { out ->
                PluginSessionTransfer.exportSession(this, out, key)
            }
        }

    // ── skill 的导入导出 ────────────────────────────────────────────

    private val skillExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择 skill 导出位置")
                return@registerForActivityResult
            }
            exportToUri(uri, "正在导出 skill") { out ->
                PluginSessionTransfer.exportSkills(this, out)
            }
        }

    private val skillImportLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择 skill 包")
                return@registerForActivityResult
            }
            importFromUri(uri, "正在导入 skill") { input ->
                PluginSessionTransfer.importSkills(this, input)
            }
        }

    /** 待导出的 skill：目录名 + 所在根目录标签。 */
    private var pendingSkillExport: Pair<String, String>? = null

    private val singleSkillExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            val target = pendingSkillExport
            pendingSkillExport = null
            if (uri == null) {
                ui.log("❌ 未选择导出位置")
                return@registerForActivityResult
            }
            if (target == null) {
                ui.log("❌ 没有指定要导出的 skill")
                return@registerForActivityResult
            }
            exportToUri(uri, "正在导出 skill") { out ->
                PluginSessionTransfer.exportSkill(this, out, target.first, target.second)
            }
        }

    // ── MCP 的导入导出 ──────────────────────────────────────────────

    private val mcpExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择 MCP 导出位置")
                return@registerForActivityResult
            }
            exportToUri(uri, "正在导出 MCP") { out ->
                PluginSessionTransfer.exportMcp(this, out)
            }
        }

    private val mcpImportLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                ui.log("❌ 未选择 MCP 包")
                return@registerForActivityResult
            }
            importFromUri(uri, "正在导入 MCP") { input ->
                PluginSessionTransfer.importMcp(this, input)
            }
        }

    /** 待导出的 MCP 配置：相对 `DSH_HOME` 的路径。 */
    private var pendingMcpExport: String? = null

    private val singleMcpExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            val rel = pendingMcpExport
            pendingMcpExport = null
            if (uri == null) {
                ui.log("❌ 未选择导出位置")
                return@registerForActivityResult
            }
            if (rel == null) {
                ui.log("❌ 没有指定要导出的 MCP 配置")
                return@registerForActivityResult
            }
            exportToUri(uri, "正在导出 MCP") { out ->
                PluginSessionTransfer.exportMcpConfig(this, out, rel)
            }
        }

    /**
     * 单插件导出的默认文件名。
     *
     * scope 包（`@scope/name`）里的 `/` 不能出现在文件名里，
     * 直接拼会得到一个非法路径：SAF 有些提供方会悄悄截断，
     * 有些会直接失败。所以把 `@` 去掉、`/` 换成 `-`。
     */
    private fun pluginZipName(pkg: String): String =
        pkg.removePrefix("@").replace('/', '-') + ".zip"

    /**
     * 把数据打包写进用户选定的位置。
     *
     * ## 为什么要在这里删掉失败留下的空文件
     *
     * 文件选择器在用户点「确定」的瞬间就把文件建好了。打包失败时不清掉，
     * 用户手上会多出一个 0 字节的 zip，还会以为备份成功了。
     * 删除失败也无所谓（部分 SAF 提供方不支持 deleteDocument），所以整体吞掉。
     *
     * @param work 在后台线程往输出流里写包，返回给人看的结果说明
     */
    private fun exportToUri(uri: Uri, label: String, work: (java.io.OutputStream) -> String) {
        // 前台保活：插件包可能几千个小文件，边打包边切走应用不能被冻结在半截
        EngineTaskService.start(this, label)

        Thread {
            // 只有「我们自己没能开始打包」时才在这里补日志 ——
            // 成功/失败细节已由 PluginSessionTransfer 写进日志面板，
            // 重复打印会让同一条结果出现两次。
            var logHere = false
            val msg = runCatching {
                val out = contentResolver.openOutputStream(uri)
                if (out == null) {
                    logHere = true
                    "❌ 无法打开所选位置的写入流"
                } else {
                    out.use { work(it) }
                }
            }.getOrElse { e ->
                logHere = true
                "❌ $label 失败: ${e.message ?: e.javaClass.simpleName}"
            }

            if (logHere) runOnUiThread { ui.log(msg) }

            if (msg.startsWith("❌")) {
                val removed = runCatching {
                    android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                }.getOrDefault(false)
                runOnUiThread {
                    if (removed) {
                        ui.log("🧹 已删除未完成的空文件，避免留下 0 字节压缩包")
                    } else {
                        ui.log("⚠️ 请手动删除目标位置的空白压缩包")
                    }
                }
            }
        }.start()
    }

    /**
     * 从用户选定的文件里导入数据，**直接写入 dsh**。
     *
     * 结果提示统一由 [PluginSessionTransfer] 写进日志面板；这里只补
     * 「流都打不开」这一类失败 —— 那种情况还没走到业务逻辑。
     */
    private fun importFromUri(uri: Uri, label: String, work: (java.io.InputStream) -> String) {
        EngineTaskService.start(this, label)

        Thread {
            runCatching {
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    "❌ 无法读取所选文件"
                } else {
                    input.use { work(it) }
                }
            }.onFailure { e ->
                runOnUiThread {
                    ui.log("❌ $label 失败: ${e.message ?: e.javaClass.simpleName}")
                }
            }.onSuccess { msg ->
                if (msg.startsWith("❌")) runOnUiThread { ui.log(msg) }
            }
        }.start()
    }

    /**
     * 投屏授权（MediaProjection）的结果回调。
     *
     * ## 为什么必须经由 Activity
     *
     * `MediaProjectionManager.createScreenCaptureIntent()` 必须由 Activity 发起，
     * 用户点「开始录制」后系统把 `resultCode` + `data` 交回。服务本身**无法**
     * 自己申请授权 —— 这是 Android 的硬性设计，也是为什么 agent 那边
     * `screencap` 永远拿不到画面：它没有这条授权路径。
     *
     * ## 授权不能持久化
     *
     * 拿到的是**一次性凭证**：进程重启后失效，必须重新点弹窗。
     * 所以这里不做任何"记住已授权"的尝试，如实通过 [ScreenCaptureState]
     * 反映当前是否真的在抓帧。
     */
    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
                ui.log("📸 已授权屏幕截图，正在启动抓帧…")
            } else {
                ui.log("已取消屏幕截图授权")
            }
        }

    /**
     * 请求一次投屏授权。已授权时不再重复弹窗，避免多建一个 VirtualDisplay。
     */
    private fun requestScreenCapture() {
        if (ScreenCaptureState.active) {
            ui.log("📸 屏幕截图已在运行中")
            return
        }
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        if (mpm == null) {
            ui.log("❌ 当前系统不支持 MediaProjection")
            return
        }
        val intent = runCatching { mpm.createScreenCaptureIntent() }.getOrNull()
        if (intent == null) {
            ui.log("❌ 无法创建投屏授权请求")
            return
        }
        runCatching { screenCaptureLauncher.launch(intent) }.onFailure {
            ui.log("❌ 拉起投屏授权失败: ${it.message}")
        }
    }

    /**
     * 发起一次「导出整合包」：先自检，再拉起 SAF 的保存位置选择器。
     *
     * ## 为什么单独抽一个方法
     *
     * 触发点有两处：实例设置的 dsh 设置页（导出行）、以及将来可能的版本管理弹窗。
     * 各自直接 `engineExportLauncher.launch(...)` 会让「引擎未装」的防御、
     * 文件名的拼法散落在各处 —— 导出的文件名里必须带版本号，否则连着导两个版本
     * 会走同一份默认名，用户很容易把旧的覆盖掉。
     *
     * 这里**不做** `EngineExporter.isBusy()` 的拦截：ActivityResult 回调里
     * `exportTo` 自己会挡掉并发请求并写日志，多拦一层反而会出现
     * 「点了没反应也没提示」的情况。
     */
    private fun requestEngineExport() {
        // ★ 关键：先确认真的有东西可导出，再开文件选择器 ★
        //
        // 上一版是先 launch 再判断，于是「引擎目录不对」时文件已经被建出来了，
        // 导出侧却立刻返回 -1 —— 用户拿到的就是一个 0 字节的 zip。
        // 现在把校验提前，失败时连文件都不会创建。
        val root = EngineExporter.resolveExportRoot(this)
        val version = ui.engineVersion
        if (root == null) {
            ui.log("❌ ${EngineExporter.describeSource(this)}")
            ui.log("❌ 没有可导出的内容，已取消（不会产生空压缩包）")
            ui.log("❌ 可以到下载中心装一个引擎版本后再试")
            return
        }
        ui.log("📦 准备导出引擎 ${version ?: "?"}")
        ui.log("   来源: ${root.absolutePath}")
        engineExportLauncher.launch("dsh-engine-${version ?: "unknown"}.zip")
    }

    /**
     * 删除当前实例：先停进程，再后台递归删除，进度写进共享状态。
     *
     * ## 「删除版本」现在就是「删除实例」
     *
     * 以前一个版本就是一个部署，所以「删版本」天然等于删掉整个引擎与数据目录。
     * 现在同一个版本可以有多个实例，版本号不再是实例的标识 ——
     * 继续按「只删引擎版本」来理解会留下一个半死状态：实例还在，
     * 但没有引擎可启动。
     *
     * 所以这个按钮删的是 `instances/<id>/` **整个目录**：
     * 引擎、工作区（Documents）、会话、凭据、插件一起消失。
     * 确认弹窗已经把这一点写清楚，并建议先导出备份。
     *
     * 老部署（没有实例、数据仍挂在 `filesDir` 下）回落到按版本删除，
     * 否则升级后这个按钮会完全没反应。
     *
     * ## 顺序为什么必须是「先停再删」
     *
     * 若 dsh 进程还在跑，它正持有着目录里的文件（日志、会话写到一半）。
     * Linux 允许删除已打开的文件，但进程会继续往里写 —— 结果是
     * 「目录删了、磁盘空间没释放、进程行为诡异」，下次启动还会报错。
     * 所以先走 [stopForInstanceSettings]，它对正在运行的实例会等待退出。
     */
    private fun requestDeleteInstance(state: LauncherState) {
        // 防重入：删除按钮在 UI 侧已禁用，这里再挡一次（回调可能被连点触发）
        if (state.deletingVersion != null) return

        val instance = Instances.active(this)
        val version = state.engineVersion
        // 老部署（没有实例、数据仍挂在 filesDir 下）仍按版本删。
        // 两种都没有时按钮不该走到这里，兜一句日志免得静默。
        if (instance == null && version == null) {
            ui.log("❌ 当前没有可删除的实例")
            return
        }
        val label = instance?.name ?: version!!

        stopForInstanceSettings("准备删除实例「$label」")

        state.deletingVersion = label
        state.deleteProgress = 0f
        // 递归删上万个文件要几十秒，熄屏时不能被冻结在半截
        EngineTaskService.start(this, "正在删除实例「$label」")

        Thread {
            // ── 先等停止流程走完 ────────────────────────────────────────────
            //
            // stopForInstanceSettings 内部是异步的（起线程调 stopProcess），
            // 它返回时 Node 可能还活着。此时直接删目录会边写边删：
            // 目录被删掉、磁盘空间却不释放，下次启动还可能读到半截会话文件。
            //
            // 轮询上限 3 秒（stopProcess 自己的优雅退出是 1.5 秒 + 强制结束后再等 1 秒），
            // 等不到也继续删 —— 卡住不让用户删掉一个坏版本是更糟的结果。
            var waited = 0
            while (ui.runState.isAlive && waited < 3000) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
                waited += 50
            }
            if (waited > 0) ui.log("⏹ 已等待实例退出 ${waited}ms，开始删除")

            val ok = if (instance != null) {
                // 有实例：删整个实例目录（引擎 + HOME + 工作区 + 会话）
                Instances.delete(this, instance.id) { p ->
                    runOnUiThread { state.deleteProgress = p }
                }
            } else {
                // 老部署：没有实例概念，仍按版本删
                DSHRunner.deleteVersion(this, version!!) { p ->
                    runOnUiThread { state.deleteProgress = p }
                }
            }
            runOnUiThread {
                state.deletingVersion = null
                state.deleteProgress = 0f
                // 删除会改变「当前实例」与版本指针，全部界面都要跟着刷新
                state.refreshEngineVersion(this)
                if (ok) {
                    ui.log("🗑 已删除实例「$label」（含引擎、工作区与会话）")
                } else {
                    ui.log("⚠️ 删除实例「$label」未完全成功，可能有文件仍被占用")
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 回调只装一次：状态写进 AppState，两个 Activity 共享
        AppState.installRunnerCallbacks()

        // 无障碍桥接（127.0.0.1 HTTP）：让 dsh 的 agent 能用 curl 读屏与点击。
        // 幂等，无障碍服务那边也会调一次（系统可能只为了绑定服务而重启进程）。
        // 端口与 token 写在 ~/a11y-bridge.json，agent 直接 cat 就能拿到。
        A11yBridgeServer.start(this)

        // 生成/更新 Node 侧的 fs 补丁（硬链接降级 + 祖先目录 open 容错）。
        //
        // 必须赶在 dsh 启动之前写好：DSHRunner.startNode 会 `--import` 这个文件。
        // 放在 onCreate 而不是 startNode 里，是因为这块补丁的内容会随版本增长，
        // 而 DSHRunner 已经 2600+ 行、定点替换容易失手 —— 生成逻辑独立在
        // com/FsPatch.kt，这里只负责尽早触发。
        //
        // 该函数用「内容特征」判断是否已是最新（不是看标记），所以与
        // DSHRunner.ensureFsPatch() 的先后顺序无关，重复调用也安全。
        com.ensureFsPatchV2(java.io.File(filesDir, "usr").absolutePath)

        // 主题：先从磁盘恢复，再交给 Fcl。放在建 View 之前，
        // 这样首帧就是用户上次选的配色，不会闪一下默认蓝再变色。
        val sysDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        Fcl.restore(
            theme = Color(LauncherPrefs.getThemeColor(this)),
            mode = LauncherPrefs.getThemeMode(this),
            alpha = LauncherPrefs.getPanelAlpha(this),
            systemDarkNow = sysDark
        )

        // 首帧就把「当前引擎版本」读出来，否则版本卡片会先显示"未安装"再跳变
        ui.refreshEngineVersion(this)

        val root = FrameLayout(this)
        rootView = root

        // ★ 关键：给根容器铺上当前主题的底色。
        //
        // 左侧栏是**原生 View**，而 Compose 覆盖层为了给它让位，leftMargin 是
        // railW + 两侧 inset —— 也就是说侧栏那一条**不在 Compose 的绘制范围内**。
        // 于是它透出来的是**窗口底色**，而窗口主题是
        // android:Theme.Material.Light（恒为浅色白）。
        //
        // 侧栏背景 Fcl.MenuBg 是半透明的（约 40% 黑/白），压在恒白的窗口底色上：
        // 深色模式下「40% 黑 + 100% 白」= 一片灰白，看上去就像没跟随主题。
        // 补上这层底色后，深色模式下侧栏才真的显深。
        applyRootBackground()

        // FCL 的左侧栏是 wrap_content：图标 24dp + 左右 5dp 内边距。
        // 这里取 30dp —— 比之前 56dp 收窄约一半。
        val railW = (Fcl.RailWidth.value * resources.displayMetrics.density).toInt()

        composeOverlay = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                // FCL 主题：把主题蓝灌进 Material3 的 colorScheme，
                // 这样 TabRow / 按钮 / 弹窗不用逐个改参数就自动变成 FCL 的配色。
                FclTheme {
                    // 主题一变就同步「原生那一半」：侧栏与根容器底色。
                    //
                    // 侧栏是原生 View，不会自己订阅 Compose 状态；原先只靠设置页里
                    // 三次手动 refreshNavRail()，漏掉任何一条路径（比如跟随系统时
                    // 用户去改系统深色）就会留着旧配色。这里在读主题状态的地方
                    // 挂一次副作用，把所有路径统一收口。
                    LaunchedEffect(Fcl.isDark, Fcl.themeColor, Fcl.panelAlpha) {
                        refreshNavRail()
                    }
                    FclHome(state = ui)
                }
            }
        }
        root.addView(
            composeOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                // 侧栏现在是浮起来的（左右各留 8dp），主区域要让出这段总宽度
                leftMargin = railW + (2 * Fcl.RailInset.value * resources.displayMetrics.density).toInt()
            }
        )

        navRail = makeNavRail(railW)
        root.addView(
            navRail,
            FrameLayout.LayoutParams(railW, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
                // 仿 FCL：侧栏不贴边，上下左右留一点，让 8dp 圆角和阴影显出来
                val inset = (Fcl.RailInset.value * resources.displayMetrics.density).toInt()
                leftMargin = inset
                topMargin = inset
                bottomMargin = inset
            }
        )

        setContentView(root)

        enterFullscreen()
    }

    /**
     * 全屏沉浸：忽略刘海/挖孔与状态栏，界面直接铺满整块屏幕。
     *
     * ## 为什么要在代码里做，而不是只改主题
     *
     * 主题里能设的只有 `statusBarColor` / `navigationBarColor`，
     * 它们只是把系统栏**涂成透明**，栏本身仍占着高度 —— 内容还是被顶下去一截。
     * 真正要「占满」必须两件事一起做：
     *
     * 1. `layoutInDisplayCutoutMode` 允许内容**画进摄像头挖孔**那一块；
     * 2. 把装饰视图的 fitSystemWindows 关掉并**隐藏系统栏**。
     *
     * 顺序不能反：先让内容铺到系统栏区域，再隐藏栏，
     * 否则会看到布局先缩一次再展开。
     *
     * ## 与网页窗口保持一致
     *
     * [DshWebActivity] 用的是同一套做法（都是 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`）：
     * 从屏幕边缘划入时系统栏临时浮现、松手自动收起。
     * 这样用户在启动器与网页窗口之间来回切，手感是一致的 ——
     * 而不是一个能划出来、另一个永远不出现。
     *
     * `IMMERSIVE_STICKY` 而不是 `IMMERSIVE`：STICKY 下用户意外划出系统栏后
     * 会自己缩回去，不需要额外代码去复位。
     */
    private fun enterFullscreen() {
        // ── 1) 允许内容延伸进刘海/挖孔 ─────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ 才有 ALWAYS：横竖屏都无视挖孔，内容直接压过去。
            // 启动器虽然锁横屏，但用户可能用「小窗/分屏」把它摆成竖的，
            // SHORT_EDGES 在那种情况下仍会留边，用 ALWAYS 更彻底。
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28~29 只有 SHORT_EDGES 可用（短边方向允许压进挖孔）
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // ── 2) 内容铺到系统栏区域，再把系统栏隐藏 ──────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 用 WindowCompat 而不是 window.setDecorFitsSystemWindows：
            // 后者在 API 35 已废弃，且 WindowCompat 在低版本上会回落到
            // LAYOUT_* 位标志，语义一致。
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { c ->
                c.hide(android.view.WindowInsets.Type.systemBars())
                c.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // API 30 以下走旧的 systemUiVisibility 位标志。
            // LAYOUT_* 三个标志等于 setDecorFitsSystemWindows(false)，
            // FULLSCREEN / HIDE_NAVIGATION 才是真正把栏藏起来。
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    /**
     * 焦点回来时重新收一次系统栏。
     *
     * 系统栏在几种情况下会被重新唤出：切到别的应用再回来、弹系统对话框、
     * 以及部分 ROM 在打开软键盘后不会自己收起。`IMMERSIVE_STICKY` 只能处理
     * 「用户划出来又松手」这一种，其余情况必须在这里补一刀。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    /**
     * 系统深色模式切换时同步主题状态。
     *
     * ## 为什么这个回调必须写
     *
     * AndroidManifest 里 MainActivity 声明了
     * `configChanges="...|uiMode|..."`，意思是这些配置变化**自己处理**。
     * 副作用是：用户去系统设置里开/关深色模式时，Activity **不会被重建**，
     * 于是 onCreate 里的 [Fcl.restore] 不会重跑，[Fcl.systemDarkState] 就一直是
     * 启动那一刻的值 —— 「跟随系统」模式下整个应用的配色（包括左侧栏）都卡住不变。
     *
     * 这里只负责把新值灌进 [Fcl]；真正的刷新由 Compose 侧那条
     * `LaunchedEffect(Fcl.isDark, ...)` 统一驱动，所以不需要再手动重建视图。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val dark = (newConfig.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        Fcl.setSystemDark(dark)
    }

    // ------------------------------------------------------------------
    // 左侧导航栏（原生 View）
    // ------------------------------------------------------------------

    /**
     * 仿 FCL 的竖排图标栏：前 4 个在顶部，退出按钮固定在最下方。
     *
     * 目前只有「退出」有实际行为，其余按要求只给提示 —— 这样点下去有反馈，
     * 不会让用户以为是卡住了。
     *
     * 材质照搬 FCL 的 `bg_game_menu`：半透明白（`ui_bg_color` = #40F4F4F4）+ 8dp 圆角。
     * 注意这是**原生 View**，拿不到 Compose 的 [Fcl] 色值对象以外的能力，
     * 所以颜色从 [Fcl] 里读，转成 ARGB int 再喂给 [GradientDrawable]。
     */
    private fun makeNavRail(railW: Int): LinearLayout {
        val d = resources.displayMetrics.density
        val rail = LinearLayout(this)
        rail.orientation = LinearLayout.VERTICAL
        // FCL: android:background="@drawable/bg_game_menu"（8dp 圆角 + 25% 白）
        rail.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Fcl.Radius.value * d
            setColor(argb(Fcl.MenuBg))
        }
        rail.gravity = Gravity.CENTER_HORIZONTAL
        // FCL: paddingStart/End 5dp, paddingTop/Bottom 10dp
        rail.setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        // FCL 的侧栏 elevation = 100dp；用兼容写法抬起来，否则圆角贴着内容会发灰
        rail.elevation = 8 * d

        // 1) 主界面 —— 收回当前面板（若没面板则什么都不做）
        //
        // ⚠️ 这里**不能写 `context.`**。MainActivity 继承 ComponentActivity，
        // 它本身就是 Context，但**没有**名为 `context` 的属性/方法 ——
        // 裸写 `context` 会被解析成 Kotlin 标准库的顶层函数 `context(...)`，
        // 报「Function invocation 'context(...)' expected」+「Unresolved reference」。
        // 直接用 Activity 自己的成员（`packageName` / `resources`）即可。
        val packageName = this.packageName
        rail.addView(makeRailIcon(resources.getIdentifier("ic_rail_home", "drawable", packageName), railW) {
            if (ui.hasPanel) ui.closePanel() else hint("已经在主界面了")
        })

        // 2) 实例设置 —— dsh 设置 / 插件管理 / 会话管理 / mcp 管理 四个选项卡。
        // 导出整合包也在这一页的 dsh 设置里。
        rail.addView(makeRailIcon(resources.getIdentifier("ic_rail_tools", "drawable", packageName), railW) {
            ui.openPanel(LauncherPanel.INSTANCE)
        })

        // 3) 下载中心。openPanel 会直接顶掉当前面板，不需要先关再开。
        rail.addView(makeRailIcon(resources.getIdentifier("ic_rail_download", "drawable", packageName), railW) {
            ui.openPanel(LauncherPanel.DOWNLOAD)
        })

        // 4) 启动器设置（选项卡：启动器设置 / dsh 窗口 / 权限设置 / 关于）
        //
        // 这里原先有一个「多人游戏」占位图标，点下去只弹一句「暂未实现」。
        // 正式版不留假按钮：功能不做就干脆不摆出来，否则用户会以为是自己
        // 没找到入口。
        rail.addView(makeRailIcon(resources.getIdentifier("ic_rail_settings", "drawable", packageName), railW) {
            ui.openPanel(LauncherPanel.SETTINGS)
        })

        // 弹簧：把退出按钮顶到最下方
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // 5) 退出 —— 有面板时只收回面板，没有面板才真正退出应用
        rail.addView(makeRailIcon(resources.getIdentifier("ic_rail_exit", "drawable", packageName), railW) {
            if (ui.hasPanel) ui.closePanel() else exitLauncher()
        })

        return rail
    }

    /** Compose 的 [androidx.compose.ui.graphics.Color] → 原生 ARGB int。 */
    private fun argb(color: androidx.compose.ui.graphics.Color): Int =
        android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )

    /**
     * 按当前主题重建左侧图标栏。
     *
     * ## 为什么必须重建而不是刷新
     *
     * 侧栏是**原生 View**（`LinearLayout` + `GradientDrawable`），它不认识 Compose 的
     * 状态订阅：`Fcl.Card` 这类 getter 改了，Compose 那边会重组，但已经画出来的
     * `GradientDrawable` 不会自己变。所以换主题色/透明度后，只能把整条栏摘掉重造。
     *
     * 代价很小：5 个 `ImageView` + 5 个 `StateListDrawable`，重建是微秒级，
     * 而且只发生在用户松开滑块或点色板时，不在滚动/动画路径上。
     */
    private fun refreshNavRail() {
        if (!::navRail.isInitialized || !::rootView.isInitialized) return

        // 侧栏是半透明的，它压在哪层底色上决定了最终观感，
        // 所以深/浅色切换时这两者必须一起刷。
        applyRootBackground()

        val railW = (Fcl.RailWidth.value * resources.displayMetrics.density).toInt()
        val inset = (Fcl.RailInset.value * resources.displayMetrics.density).toInt()

        // 先用旧栏的位置反推它所在的父容器，避免把 rootView 记错
        val parent = navRail.parent as? FrameLayout ?: rootView
        parent.removeView(navRail)

        navRail = makeNavRail(railW)
        parent.addView(
            navRail,
            FrameLayout.LayoutParams(railW, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
                leftMargin = inset
                topMargin = inset
                bottomMargin = inset
            }
        )
    }

    /**
     * 给根容器铺上与 Compose 侧**同一张**壁纸。
     *
     * ## 为什么必须有这一层
     *
     * Compose 覆盖层为了给侧栏让位，左边留了 `railW + 2*inset` 的边距，
     * 侧栏那条**不在它的绘制范围内**。侧栏自己是半透明的 [Fcl.MenuBg]，
     * 透出来的就是这一层的图像；没有这一层时透的是窗口主题色 ——
     * 而 [Theme.App] 继承的是 `Material.Light`，恒为白色。
     *
     * 所以这里必须和 [fclBackground] 用同一张图：只要两边不一致，
     * 侧栏那一条和右侧就会出现一道明显的接缝。
     *
     * 用 [BitmapDrawable] 并设 `gravity = FILL`：把图拉伸铺满整屏，
     * 保证和 Compose 侧 `ContentScale.Crop` 的裁切位置一致 ——
     * 两边各裁各的会错位，接缝会更难看。
     */
    private fun applyRootBackground() {
        if (!::rootView.isInitialized) return
        // 同上：Activity 里没有 `context` 这个名字，直接用自己的成员。
        val packageName = this.packageName
        val res = resources.getIdentifier(
            if (Fcl.isDark) "background_dark" else "background_light",
            "drawable",
            packageName
        )
        val d = androidx.core.content.ContextCompat.getDrawable(this, res)
        if (d is android.graphics.drawable.BitmapDrawable) {
            d.gravity = Gravity.FILL
        }
        rootView.background = d
    }

    /**
     * 侧栏的一个图标按钮：正方形可点区域，带按下反馈。
     *
     * 图标用**矢量图 + tint**，不是 emoji —— emoji 会渲染成彩色字形，
     * 与 FCL 的扁平单色风格不符。矢量图还能随主题色改 tint。
     *
     * FCL 的 `FCLMenuView` 按下时是「背景浮现 + 图标染主题色」，这里同样：
     * 按下底色是 8dp 圆角的主题蓝 20% 透明，图标在按下时切到主题色。
     */
    private fun makeRailIcon(iconRes: Int, railW: Int, onClick: () -> Unit): LinearLayout {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.gravity = Gravity.CENTER
        box.isClickable = true
        box.isFocusable = true
        box.setOnClickListener { onClick() }

        val pressedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Fcl.Radius.value * d
            setColor(argb(Fcl.Theme.copy(alpha = 0.22f)))
        }
        val idleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Fcl.Radius.value * d
            setColor(android.graphics.Color.TRANSPARENT)
        }

        // 按下反馈：手写 StateListDrawable，避免 theme.resolveAttribute 在
        // 本项目的分析器里解析不稳（会报 Unresolved reference）。
        val bg = android.graphics.drawable.StateListDrawable()
        bg.addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
        bg.addState(intArrayOf(), idleBg)
        box.background = bg

        val iconView = ImageView(this)
        iconView.setImageResource(iconRes)
        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        // FCL 图标默认是 darker_gray 一档；这里同样给中性深灰，浅底上看得清
        iconView.imageTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(argb(Fcl.ThemeDark), argb(Fcl.Icon))
        )
        val side = (24 * d).toInt()
        box.addView(iconView, LinearLayout.LayoutParams(side, side))

        // FCL 相邻图标间距 10dp → 条目高 24 + 10 = 34，再留一点触摸面积到 44dp
        box.layoutParams = LinearLayout.LayoutParams(railW, (44 * d).toInt()).apply {
            topMargin = (4 * d).toInt()
        }
        return box
    }

    /** 退出启动器。不停 Node 进程：它和网页窗口的生命周期由「停止」按钮管理。 */
    private fun exitLauncher() {
        finishAffinity()
    }

    /** 未实现功能的统一提示。 */
    private fun hint(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // FCL 风格主界面
    // ------------------------------------------------------------------

    @Composable
    private fun FclHome(state: LauncherState) {
        // 拿到 URL 就拉起独立全屏窗口（仿 FCL 弹出游戏窗口）。
        // 用 url 作 key：从网页窗口返回时 url 没变，不会重复拉起。
        LaunchedEffect(state.url) {
            if (state.url != null) openWebWindow(state)
        }

        // 底色：FCL 是用户壁纸，本项目不提供壁纸，改用主题蓝调的浅渐变当「壁纸替身」
        // （见 Fcl.fclBackground 的说明）。半透明白面板压在它上面才有层次。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .fclBackground()
        ) {
            // 右侧菜单：对应 FCL activity_main.xml 的 @id/right_menu
            //   background="@drawable/bg_right_menu"  → 半透明白 + 只圆左侧两角
            //   layout_constraintWidth_percent="0.25" → 屏宽的 1/4
            //
            // 手机竖屏下 1/4 屏宽只有约 90dp，"打开界面" 会被挤断，所以按比例取值
            // 之后再夹到 [150dp, 260dp]：平板/横屏得到 25% 的观感，竖屏也不会崩。
            // 与 PanelHost 共用同一个宽度：两处各算各的会让面板盖住右栏
            // 或者平白空出一截。
            val panelWidth = rightMenuWidth()
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = Fcl.Radius, bottomStart = Fcl.Radius))
                    .background(Fcl.RightMenuBg)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AccountBlock()

                    Spacer(modifier = Modifier.weight(1f))

                    LaunchBlock(
                        state = state,
                        onLaunchOrOpen = {
                            if (state.url != null && state.runState.isAlive) {
                                openWebWindow(state)
                            } else {
                                toggleRun(state)
                            }
                        },
                        onStop = { stopInstance(state) },
                        onRestart = { restartInstance(state) },
                        onManageVersions = { state.showVersions = true },
                        onOpenLog = { state.openPanel(LauncherPanel.LOG) },
                        onOpenBrowser = { openInBrowser(state) }
                    )
                }
            }

            // 底部胶囊（原 FCL 的 FCLDynamicIsland）已按需求移除：
            // 它显示「实例名 · 运行状态」，与右侧「版本卡片 + 启动按钮」重复，
            // 而且在全屏布局下压在屏幕最底部、正对导航手势条，容易误触。

            // ── 侧栏面板：从左侧滑入，同一时刻只有一个 ────────────────────
            PanelHost(state = state)

            // 版本管理（由「管理版本」按钮打开，是 Dialog，不受面板切换影响）
            if (state.showVersions) {
                VersionManagerPanel(state = state, onClose = { state.showVersions = false })
            }

            // 导出整合包的进度小窗口。
            //
            // 打包是几千个小文件的后台任务，之前只在实例设置那一行里用文字更新，
            // 用户点完保存位置后几乎看不到反馈，会以为没开始。这里改成
            // 悬浮小窗口：进度条 + 百分比 + 当前字节数，并且可以中途取消。
            if (EngineExporter.phase == EngineExporter.Phase.PACKING) {
                ExportProgressDialog()
            }

            // 删除实例的进度小窗口。
            // 删一个实例要递归删掉上万个文件，没有反馈就是个「点了没反应」的界面。
            //
            // `state.deletingVersion` 这个字段名是历史遗留（以前删的是版本），
            // 现在装的是**实例名**。字段名不改是为了避免牵动 LauncherState
            // 与其它读取点，但窗口里的措辞必须按实例来说。
            val deletingLabel = state.deletingVersion
            if (deletingLabel != null) {
                DeleteProgressDialog(label = deletingLabel, progress = state.deleteProgress)
            }

            // 导入整合包的进度小窗口。
            // 下载与解压两个阶段都展示：下载有确定百分比，解压只有条目计数。
            val importPhase = DshEngineDownloader.phase
            if (importPhase == DshEngineDownloader.Phase.DOWNLOADING ||
                importPhase == DshEngineDownloader.Phase.INSTALLING
            ) {
                ImportProgressDialog()
            }
        }
    }

    /**
     * 删除实例的进度窗口。
     *
     * 与 [ExportProgressDialog] 的区别：这里**不给**取消按钮。
     * 删到一半停下只会留下一个残缺的实例目录（引擎一半有一半没有），
     * 反而比删完更难恢复，所以只能等它跑完。
     *
     * @param label 要删除的对象名字。现在传的是**实例名**；
     *   老部署（无实例概念）下传的是版本号。
     */
    @Composable
    private fun DeleteProgressDialog(label: String, progress: Float) {
        Dialog(
            // 不让外部点击/返回键关掉：删除无法安全中断
            onDismissRequest = { },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(
                modifier = Modifier.width(300.dp),
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.DialogBg
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "正在删除「$label」",
                        color = Fcl.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "引擎、工作区与会话正在被移除，请稍候。",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = Fcl.Danger,
                        trackColor = Fcl.Track
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        color = Fcl.TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    /**
     * 导入整合包的进度窗口。
     *
     * 两个阶段共用一个窗口：
     * - [DshEngineDownloader.Phase.DOWNLOADING]：有确定百分比（Content-Length 可用时）；
     * - [DshEngineDownloader.Phase.INSTALLING]：解压，进度不确定，用不确定进度条。
     *
     * 下载阶段允许取消（[DshEngineDownloader.cancel]），
     * 解压阶段不可中断 —— 解压到一半停下会留下半个引擎。
     */
    @Composable
    private fun ImportProgressDialog() {
        // 订阅状态：这三个都是 Compose 的 mutableStateOf
        val phase = DshEngineDownloader.phase
        val progress = DshEngineDownloader.progress
        val message = DshEngineDownloader.message

        val downloading = phase == DshEngineDownloader.Phase.DOWNLOADING

        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(
                modifier = Modifier.width(300.dp),
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.DialogBg
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = if (downloading) "正在下载整合包" else "正在安装整合包",
                        color = Fcl.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (downloading)
                            "下载完成后会自动解压安装，请保持应用在前台。"
                        else
                            "正在解压引擎文件，这一步无法中断。",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // progress < 0 表示不确定（连接中 / 解压中）
                    if (progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Fcl.Theme,
                            trackColor = Fcl.Track
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Fcl.Theme,
                            trackColor = Fcl.Track
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = buildString {
                            if (progress >= 0f) append("${(progress * 100).toInt()}%  ·  ")
                            append(message.ifBlank { "请稍候…" })
                        },
                        color = Fcl.TextSecondary,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        // 只有下载阶段能取消；解压阶段隐藏按钮而不是禁用，
                        // 免得让人误以为「按了没用」。
                        if (downloading) {
                            TextButton(onClick = { DshEngineDownloader.cancel() }) {
                                Text("取消下载", color = Fcl.Danger, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 导出整合包的进度窗口。
     *
     * 只在 [EngineExporter.Phase.PACKING] 时展示（由调用方判断），
     * 结束后自动消失 —— 结果留在实例设置那一行和运行日志里。
     *
     * 关闭方式刻意给了两种：点外部/返回键**不**关闭（打包还在跑，
     * 关掉窗口会让人以为任务被取消了），只有「取消导出」按钮才真的中断。
     */
    @Composable
    private fun ExportProgressDialog() {
        // 这两行是订阅：EngineExporter 是普通 object + Compose 状态，
        // 读它们才会随打包进度重组。
        val progress = EngineExporter.progress
        val message = EngineExporter.message

        Dialog(
            // 不让外部点击/返回键关掉：任务仍在后台跑，关窗会误导
            onDismissRequest = { },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(
                modifier = Modifier.width(300.dp),
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.DialogBg
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "正在导出整合包",
                        color = Fcl.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "引擎文件很多，请保持应用在前台。",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // progress < 0 表示还在统计体积，此时给不确定进度条
                    if (progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Fcl.Theme,
                            trackColor = Fcl.Track
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Fcl.Theme,
                            trackColor = Fcl.Track
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (progress >= 0f) {
                            "${(progress * 100).toInt()}%  ·  ${message.ifBlank { "打包中…" }}"
                        } else {
                            message.ifBlank { "正在统计体积…" }
                        },
                        color = Fcl.TextSecondary,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { EngineExporter.cancel() }) {
                            Text("取消导出", color = Fcl.Danger, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    /**
     * 右侧菜单面板的宽度。
     *
     * 单独抽出来是因为有两处要用同一个值：[FclHome] 用来摆放右栏，
     * [PanelHost] 用来给侧栏面板「让位」—— 若两处各算各的，
     * 面板不是盖住右栏，就是平白空出一截。
     */
    @Composable
    private fun rightMenuWidth(): Dp = with(LocalConfiguration.current) {
        (screenWidthDp.dp * 0.30f).coerceIn(150.dp, 260.dp)
    }

    /**
     * 侧栏面板的容器：负责进出场动画、互斥显示、以及「不挡住两块侧栏」。
     *
     * ## 布局约束
     *
     * 面板只占**中间那条**：左边让开左侧图标栏（再留一点缝），
     * 右边让开[右侧菜单面板][rightMenuWidth]。之前是 `fillMaxSize()`，
     * 一展开就把右栏整个盖住，看起来像右栏消失了。
     *
     * ## 为什么用位移而不是 Material 的 AnimatedVisibility
     *
     * `slideInHorizontally` 在 `androidx.compose.animation:animation` 里，
     * 本项目的依赖只到 foundation（它只带 animation-core），
     * 引入整个 animation 包只为一条滑入动画不划算。
     * 用 `Animatable` + `graphicsLayer` 几行就能做到同样效果。
     *
     * ## 互相顶掉时的动画
     *
     * 每块面板各有一条自己的进度（见 [progress]），顶替时两条同时跑：
     * - 被顶掉的那块：1 → 0，从右往左缩回屏幕外；
     * - 新顶出的那块：0 → 1，从左边伸出来。
     *
     * 两版更早的实现都有问题，一并记在这里免得又改回去：
     * 1. 「位置不动、内容直接换掉」—— 视觉上就是突然覆盖，很生硬；
     * 2. 「共用 enter / exit 两条进度」—— 新面板首帧读到上一块残留的 1f，
     *    于是**先闪一下全开**，再被 `snapTo(0f)` 弹回屏幕外重新滑入，
     *    看上去就是「闪一下就出现在终点」，滑出过程根本看不见。
     */
    @Composable
    private fun PanelHost(state: LauncherState) {
        val target = state.panel

        // 每个面板一份独立进度：0f = 完全藏在左边，1f = 完全展开。
        //
        // ★ 为什么必须「一板一份」，而不是共用 enter / exit 两条 ★
        // 共用时，新面板的第一帧读到的还是上一块留下的 1f —— 于是先「闪一下全开」，
        // 等协程里 snapTo(0f) 落地才被弹回屏幕外、再滑进来。这正是要修的现象：
        // 闪一下，然后新面板瞬间出现在终点，滑出过程看不见。
        // 改成每块面板各持一条**从 0f 起步**的进度后，新面板的首帧天然在屏幕外，
        // 不再依赖「先 snap 再 animate」这种对执行顺序的赌博。
        val progress = remember {
            LauncherPanel.values().associateWith { Animatable(0f) }
        }

        // 每块面板一个 effect：该显示就往 1f 走，不该显示就往 0f 走。
        // 各面板的 effect 互不阻塞，所以「旧的缩回」与「新的伸出」天然同时进行 ——
        // 顶替看起来才是两块面板擦身而过，而不是先关再开。
        REAL_PANELS.forEach { p ->
            val anim = progress[p]
            val shown = p == target
            // 键里带 shown：方向一变就重启动画。中途被打断时 Animatable 的
            // MutatorMutex 会取消同一条上的旧动画，从当前值接着往新目标走，
            // 不会跳回 0 或 1。
            if (anim != null) {
                LaunchedEffect(p, shown) {
                    anim.animateTo(
                        targetValue = if (shown) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = PANEL_ANIM_MS,
                            easing = PanelEasing
                        )
                    )
                }
            }
        }

        // 只绘制「当前目标」和「还没退干净」的面板。
        // 这里读 anim.value 是为了订阅：动画推进时每帧重组。
        val live = REAL_PANELS.filter { p ->
            p == target || (progress[p]?.value ?: 0f) > 0f
        }
        // 一块都没有就完全不绘制，避免透明层拦住触摸
        if (live.isEmpty()) return

        // 与左侧栏留一点缝；右侧为菜单面板让位
        val gap = 8.dp
        val gapPx = with(LocalDensity.current) { gap.toPx() }
        val reservedEnd = rightMenuWidth() + gap

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = gap, end = reservedEnd)
        ) {
            // 退场中的先画（在下层），当前目标最后画（在最上层），
            // 这样新面板伸出来时叠在旧面板之上，像真的把它顶掉。
            live.filter { it != target }.forEach { p ->
                PanelSlot(
                    panel = p,
                    state = state,
                    progress = progress[p]?.value ?: 0f,
                    gapPx = gapPx,
                    onExportEngine = { requestEngineExport() },
                    onExportPlugins = { pluginExportLauncher.launch("dsh-plugins.zip") },
                    onImportPlugins = { pluginImportLauncher.launch("application/zip") },
                    onExportSessions = { sessionExportLauncher.launch("dsh-sessions.zip") },
                    onImportSessions = { sessionImportLauncher.launch("application/zip") },
                    onExportPlugin = { name ->
                        pendingPluginExport = name
                        singlePluginExportLauncher.launch(pluginZipName(name))
                    },
                    onExportSession = { key ->
                        pendingSessionExport = key
                        singleSessionExportLauncher.launch("session-$key.zip")
                    },
                    onExportSkills = { skillExportLauncher.launch("dsh-skills.zip") },
                    onImportSkills = { skillImportLauncher.launch("application/zip") },
                    onExportSkill = { id, label ->
                        // 参数必须经字段带过 SAF 回调：启动是异步的，
                        // lambda 捕获的栈在回调触发时早已不存在。
                        pendingSkillExport = id to label
                        singleSkillExportLauncher.launch("skill-$id.zip")
                    },
                    onExportMcp = { mcpExportLauncher.launch("dsh-mcp.zip") },
                    onImportMcp = { mcpImportLauncher.launch("application/zip") },
                    onExportMcpConfig = { rel ->
                        pendingMcpExport = rel
                        singleMcpExportLauncher.launch(
                            "mcp-" + rel.substringAfterLast('/') + ".zip"
                        )
                    }
                )
            }
            if (target != LauncherPanel.NONE) {
                PanelSlot(
                    panel = target,
                    state = state,
                    progress = progress[target]?.value ?: 0f,
                    gapPx = gapPx,
                    onExportEngine = { requestEngineExport() },
                    onExportPlugins = { pluginExportLauncher.launch("dsh-plugins.zip") },
                    onImportPlugins = { pluginImportLauncher.launch("application/zip") },
                    onExportSessions = { sessionExportLauncher.launch("dsh-sessions.zip") },
                    onImportSessions = { sessionImportLauncher.launch("application/zip") },
                    onExportPlugin = { name ->
                        pendingPluginExport = name
                        singlePluginExportLauncher.launch(pluginZipName(name))
                    },
                    onExportSession = { key ->
                        pendingSessionExport = key
                        singleSessionExportLauncher.launch("session-$key.zip")
                    },
                    onExportSkills = { skillExportLauncher.launch("dsh-skills.zip") },
                    onImportSkills = { skillImportLauncher.launch("application/zip") },
                    onExportSkill = { id, label ->
                        // 参数必须经字段带过 SAF 回调：启动是异步的，
                        // lambda 捕获的栈在回调触发时早已不存在。
                        pendingSkillExport = id to label
                        singleSkillExportLauncher.launch("skill-$id.zip")
                    },
                    onExportMcp = { mcpExportLauncher.launch("dsh-mcp.zip") },
                    onImportMcp = { mcpImportLauncher.launch("application/zip") },
                    onExportMcpConfig = { rel ->
                        pendingMcpExport = rel
                        singleMcpExportLauncher.launch(
                            "mcp-" + rel.substringAfterLast('/') + ".zip"
                        )
                    }
                )
            }
        }
    }

    /**
     * 一块面板本体：统一底色（跟随透明度滑块）+ 进度驱动的横向位移。
     *
     * 位移量用 `size.width + gapPx` 而不是只有宽度：面板左边缘本来就有 [gap]
     * 那么宽的留白，只位移自身宽度的话会残一条边露在屏幕上。
     */
    @Composable
    private fun PanelSlot(
        panel: LauncherPanel,
        state: LauncherState,
        progress: Float,
        gapPx: Float,
        onExportEngine: () -> Unit,
        onExportPlugins: () -> Unit,
        onImportPlugins: () -> Unit,
        onExportSessions: () -> Unit,
        onImportSessions: () -> Unit,
        onExportPlugin: (String) -> Unit,
        onExportSession: (String) -> Unit,
        onExportSkills: () -> Unit,
        onImportSkills: () -> Unit,
        onExportSkill: (String, String) -> Unit,
        onExportMcp: () -> Unit,
        onImportMcp: () -> Unit,
        onExportMcpConfig: (String) -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topEnd = Fcl.Radius, bottomEnd = Fcl.Radius))
                .graphicsLayer {
                    translationX = -(1f - progress) * (size.width + gapPx)
                }
                .background(Fcl.PanelBg)
        ) {
            when (panel) {
                LauncherPanel.INSTANCE -> InstanceSettingsPanel(
                    state = state,
                    tab = state.instanceTab,
                    onSelectTab = { state.instanceTab = it },
                    onExportEngine = onExportEngine,
                    onDeleteVersion = { requestDeleteInstance(state) },
                    onExportPlugins = onExportPlugins,
                    onImportPlugins = onImportPlugins,
                    onExportSessions = onExportSessions,
                    onImportSessions = onImportSessions,
                    onExportPlugin = onExportPlugin,
                    onExportSession = onExportSession,
                    onExportSkills = onExportSkills,
                    onImportSkills = onImportSkills,
                    onExportSkill = onExportSkill,
                    onExportMcp = onExportMcp,
                    onImportMcp = onImportMcp,
                    onExportMcpConfig = onExportMcpConfig
                )

                LauncherPanel.DOWNLOAD -> DownloadPanel(
                    onPickLocalFile = {
                        engineZipLauncher.launch("application/zip")
                    }
                )

                LauncherPanel.SETTINGS -> SettingsPanel()

                LauncherPanel.LOG -> LogPanel(state = state)

                LauncherPanel.NONE -> Unit
            }
        }
    }

    /**
     * 版本管理面板里的一行。
     *
     * 把「实例」与「老部署的版本号」两种来源归一成同一种可选项，
     * 下面那块列表因此只需要一份渲染代码 —— 两者的切换动作虽然不同，
     * 但已经收进 [onPick] 里了。
     */
    private data class VersionPickRow(
        /** 切换时用的键：实例 id，或老部署的版本号。 */
        val key: String,
        val title: String,
        val subtitle: String,
        val isCurrent: Boolean,
        /** 是否用等宽字体（版本号用，实例名不用）。 */
        val mono: Boolean,
        /**
         * 副行是否该用警示色。
         *
         * 目前没有任何调用点会传 true —— 「引擎未安装」那类提示已从界面移除，
         * 保留这个字段只是为了不改动既有渲染代码。
         */
        val warn: Boolean = false,
        val onPick: () -> Unit
    )

    /**
     * 版本管理小窗口。
     *
     * 职责只有两件：**列出全部实例**、**选择下次「启动」用哪个实例**。
     * 不做删除、导入、重命名 —— 那些属于下载中心的范畴。
     *
     * ## 为什么用小窗口而不是全屏面板
     *
     * 它只是"看一眼、点一下"的轻量交互，占满整屏没有必要，
     * 也和下载中心的居中进度窗风格不统一。用 Dialog 后背景自动变暗、
     * 触摸不穿透，视觉上是一层临时浮层，符合 FCL 那种"点开小卡片"的手感。
     *
     * ## 多版本共存与切换
     *
     * 引擎现在可以并存多份（`usr/lib/versions/<ver>/`），列表来自**扫描磁盘**，
     * 点某个版本只是写一个指针；真正换进程发生在下次启动时。
     *
     * 若切换时 dsh 正在运行，必须先把旧进程停掉 —— 两个实例共用一个
     * `DSH_HOME` 会互相踩会话文件。所以这里走 [restartInstance]：
     * 停 → 等端口释放 → 按新指针启动。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VersionManagerPanel(state: LauncherState, onClose: () -> Unit) {
        val context = LocalContext.current

        // 实例列表：打开面板时扫一次磁盘；切换/装完后 tick++ 重扫。
        //
        // ## ★ 必须列**实例**，不能列版本 ★
        //
        // 原先扫的是 `usr/lib/versions` 下的目录名 —— 那就是版本号。
        // 同一个版本装了两个实例时，列表里只有一行、显示的还只是版本号：
        // 用户看不出自己有两个实例，也根本选不到第二个。
        //
        // 实例才是用户认知里的对象（他给实例起了名字），版本只是实例的一个属性。
        var tick by remember { mutableStateOf(0) }
        val instances = remember(tick) { Instances.list(context) }
        val activeId = remember(tick) { Instances.activeId(context) }
        val activeInstance = instances.firstOrNull { it.id == activeId }

        // 升级前的老部署没有实例，数据仍在 filesDir/home 与 filesDir/usr 下。
        // 那种情况回落到按版本列出，否则面板会一片空白、用户以为数据丢了。
        val legacyVersions = remember(tick) {
            if (instances.isEmpty()) DSHRunner.installedVersions(context) else emptyList()
        }

        // 「当前使用中」直接读共享状态，切换后面板与主页卡片同时重组。
        val current = state.engineVersion
        val installed = activeInstance?.installed == true ||
            (instances.isEmpty() && current != null)

        // 归一成一组可选项：有实例就列实例，老部署才列版本号。
        //
        // 刻意**不用 remember** 包起来：这里只有几个元素，每次重组重算的代价
        // 可以忽略，而 remember 会把 `state.runState` 之类的快照过期掉 ——
        // 那正是「点切换没反应」这类 bug 的来源。
        val rows: List<VersionPickRow> = if (instances.isNotEmpty()) {
            instances.map { inst ->
                VersionPickRow(
                    key = inst.id,
                    title = inst.name,
                    subtitle = buildString {
                        // 只写出真实存在的版本号；不再显示「未装引擎」这类提示。
                        if (inst.version.isNotBlank()) append("引擎 ${inst.version}")
                        if (inst.id == activeId) {
                            if (isNotEmpty()) append(" · 当前") else append("当前")
                        }
                    },
                    isCurrent = inst.id == activeId,
                    // 实例名可能有中文，不套等宽字体
                    mono = false,
                    onPick = {
                        if (inst.id != activeId) {
                            // 只写指针，真正换进程在下次「启动」；
                            // 若此刻正在运行，则按新实例重启（同一时刻只跑一个）。
                            Instances.setActive(context, inst.id)
                            state.refreshEngineVersion(context)
                            tick++
                            if (!inst.installed) {
                                // 保持原控制流不变（不装引擎时不重启）：
                                // 只把「还没有装引擎」这句话从提示里去掉了。
                                state.log("✅ 已切换到「${inst.name}」")
                            } else if (state.runState.isAlive) {
                                state.log("🔄 已切换到「${inst.name}」，正在重启实例…")
                                restartInstance(state)
                            } else {
                                state.log("✅ 已切换到「${inst.name}」，下次启动时生效")
                            }
                        }
                    }
                )
            }
        } else {
            legacyVersions.map { v ->
                VersionPickRow(
                    key = v,
                    title = v,
                    subtitle = if (v == current) "当前使用中" else "旧版单实例布局 · 点此切换",
                    isCurrent = v == current,
                    mono = true,
                    onPick = {
                        if (v != current) {
                            DSHRunner.setCurrentVersion(context, v)
                            state.refreshEngineVersion(context)
                            tick++
                            if (state.runState.isAlive) {
                                state.log("🔄 已切换到 $v，正在重启实例…")
                                restartInstance(state)
                            } else {
                                state.log("✅ 已切换到 $v，下次启动时生效")
                            }
                        }
                    }
                )
            }
        }

        // 打开面板先与磁盘对齐一次（别处可能改过指针）
        LaunchedEffect(Unit) { state.refreshEngineVersion(context) }

        // 安装流程结束（在下载中心里装的）后，回到这里能立刻看到新状态
        LaunchedEffect(EngineInstaller.installPhase) {
            if (EngineInstaller.installPhase == EngineInstaller.InstallPhase.DONE) {
                tick++
                state.refreshEngineVersion(context)
            }
        }

        // 纯查看型浮层，允许返回键与点击外部关闭（与安装进度窗不同，
        // 这里没有正在进行的后台任务，关掉不会有副作用）。
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(
                modifier = Modifier.width(340.dp),
                // FCL 的 dialog_background：8dp 圆角 + #F4F4F4 实心，无描边
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.DialogBg
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // ── 标题 ────────────────────────────────────────────
                    Text(
                        text = "版本管理",
                        color = Fcl.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // ── 当前使用中 ───────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (installed) Fcl.Success else Fcl.Idle)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                // 有实例时显示**实例名** —— 那是用户自己起的、认得出来的名字。
                                // 没有实例的老部署才回落到版本号，此时版本号确实是它的标识。
                                text = activeInstance?.name
                                    ?: current
                                    ?: "还没有实例",
                                color = if (installed) Fcl.TextPrimary else Fcl.TextSecondary,
                                fontSize = 12.sp,
                                // 实例名可能有中文，不套等宽字体；老部署的版本号仍然用等宽
                                fontFamily = if (activeInstance != null) null
                                else FontFamily.Monospace
                            )
                            Text(
                                text = when {
                                    activeInstance != null &&
                                        activeInstance.version.isNotBlank() ->
                                        "当前实例 · 引擎 ${activeInstance.version}"
                                    activeInstance != null -> "当前实例"
                                    installed -> "当前使用中（旧版单实例布局）"
                                    else -> "请先到下载中心安装"
                                },
                                color = Fcl.TextTertiary,
                                fontSize = 9.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Fcl.Line)

                    // ── 实例列表（老部署回落到版本列表）──────────────────
                    if (rows.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "还没有实例",
                                color = Fcl.TextSecondary,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            TextButton(onClick = {
                                onClose()
                                AppState.launcher.openPanel(LauncherPanel.DOWNLOAD)
                            }) { Text("前往下载中心", fontSize = 11.sp) }
                        }
                    } else {
                        // 小窗口里固定限高，版本变多时内部滚动，不撑破窗口
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                        ) {
                            itemsIndexed(rows) { idx, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // 点一下即切换。已在用的那个点了不做任何事，避免无谓重启。
                                        //
                                        // ★ 切换后**不关闭弹窗** ★
                                        // 之前这里紧跟一句 onClose()，用户点完窗口就没了、
                                        // 再打开才看到结果，观感上就是「点了没反应、版本不会变」。
                                        // 现在留在原地让选中态立刻移动，用户自己关。
                                        .fclClickable { row.onPick() }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = row.isCurrent,
                                        // 整行已有点击，这里传 null 避免双重触发；
                                        // 但不再置 enabled=false —— 禁用态会让整行看着点不动。
                                        onClick = null,
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = Fcl.Success,
                                            unselectedColor = Fcl.Track
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = row.title,
                                            color = if (row.isCurrent) Fcl.Success
                                            else Fcl.TextPrimary,
                                            fontSize = 12.sp,
                                            // 实例名可以是中文，只有版本号才用等宽；
                                            // 传 null 表示用主题默认字体
                                            fontFamily = if (row.mono) FontFamily.Monospace
                                            else null,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = row.subtitle,
                                            // 警示优先于选中态：当前实例若没装引擎，
                                            // 副行必须是红的，绿色会被读成「一切正常」。
                                            color = when {
                                                row.warn -> Fcl.Danger
                                                row.isCurrent -> Fcl.Success
                                                else -> Fcl.TextTertiary
                                            },
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (idx < rows.size - 1) {
                                    HorizontalDivider(color = Fcl.Divider)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "切换会立即写盘；若 dsh 正在运行会自动重启到所选实例。" +
                            "同一时刻只运行一个实例，各实例的引擎、工作区与会话互相独立。",
                        color = Fcl.TextTertiary,
                        fontSize = 9.sp,
                        lineHeight = 13.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                }
            }
        }
    }

    /**
     * 启动器设置面板。
     *
     * 目前只有「沙箱权限模式」一项，但它是唯一影响安全边界的配置 ——
     * 之前被写死成 `danger-full-access`（等于让模型命令以 App 身份无隔离执行），
     * 现在交还给用户显式选择，默认 workspace-write。
     *
     * 危险模式需要二次确认，避免误触。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsPanel(onBack: () -> Unit = { ui.closePanel() }) {
        val context = LocalContext.current
        var mode by remember { mutableStateOf(LauncherPrefs.getPermissionMode(context)) }
        var pendingDanger by remember { mutableStateOf<PermissionMode?>(null) }
        // 选项卡：0 启动器设置 / 1 权限设置 / 2 关于
        var tab by remember { mutableStateOf(0) }

        // 主题设置：初值从 Fcl 读（它已在 onCreate 时由磁盘恢复），
        // 改动立刻写回 Fcl（驱动重组）与磁盘（下次启动仍然生效）。
        var themeMode by remember { mutableStateOf(Fcl.mode) }
        var panelAlpha by remember { mutableStateOf(Fcl.panelAlpha) }

        // dsh 网页窗口的方向与缩放（设置 → dsh 窗口）。
        //
        // 与上面几项的区别：这些值**不驱动任何本界面重组**，只写进偏好，
        // 下次打开 DshWebActivity 时由它的 applyWindowSettings() 读取生效。
        // 所以这里不需要像 Fcl.setThemeColor() 那样同步一个全局单例。
        var dshScreenMode by remember {
            mutableStateOf(LauncherPrefs.getDshScreenMode(context))
        }
        var dshZoom by remember {
            mutableStateOf(LauncherPrefs.getDshZoom(context).toFloat())
        }

        // ── 权限状态刷新 ──────────────────────────────────────────────────
        // 用户点「去授权」会跳到系统设置页，回来时 Activity 走 ON_RESUME。
        // 没有这个监听的话，权限状态会一直停在打开设置面板那一刻的旧值。
        //
        // 这里直接拿 Activity 当 LifecycleOwner，而不是用
        // LocalLifecycleOwner —— 后者在 Compose 1.11 里正从
        // androidx.compose.ui.platform 迁移到 androidx.lifecycle.compose，
        // 两个路径不一定同时存在，直接强转最稳且无额外依赖。
        var refreshTick by remember { mutableStateOf(0) }
        val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
            }
            lifecycleOwner?.lifecycle?.addObserver(observer)
            onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
        }
        // refreshTick 变化即重新读一遍真实系统状态
        val permEntries = remember(refreshTick) { PermissionCenter.entries(context) }

        BackHandler(enabled = true) { onBack() }

        // 底色交给 PanelHost（它用 Fcl.PanelBg，跟随透明度滑块）。
        // 这里若再铺一层不透明的 Fcl.Bg，面板就永远是死白/死黑，
        // 设置里的「面板不透明度」对它完全不起作用。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "设置",
                    color = Fcl.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)
                )

                // 选项卡：面板内不再放「关闭」按钮，
                // 收回靠侧栏的房子/退出图标或返回键（见 PanelHost）。
                TabRow(
                    selectedTabIndex = tab,
                    // 透明：让面板统一的半透明底色透上来
                    containerColor = Color.Transparent,
                    contentColor = Fcl.Theme
                ) {
                    // 页签顺序：启动器设置 / dsh 窗口设置 / 权限设置 / 关于
                    //
                    // 「dsh 窗口」单独一页而不是塞进「启动器设置」：
                    // 它管的是那个全屏网页窗口（方向、缩放），与启动器自身的
                    // 配色/权限是两套东西，混在一起容易让人以为改这里能改启动器方向。
                    listOf("启动器设置", "dsh 窗口", "权限设置", "关于")
                        .forEachIndexed { i, label ->
                            Tab(
                                selected = tab == i,
                                onClick = { tab = i },
                                text = { Text(label, fontSize = 12.sp, maxLines = 1) }
                            )
                        }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    // 内容区统一按 FCL `page_setting_list.xml` 的样子渲染：
                    // 一组白色 8dp 圆角行，同组相邻行连成一整块，组间空 8dp。
                    if (tab == 0) {
                        // ── 外观：只改启动器配色，不影响 dsh 进程 ──────────
                        FclSettingSection("外观")

                        FclSettingRow(
                            label = "主题模式",
                            description = "浅色 / 深色 / 跟随系统。只改界面配色，不影响 dsh。",
                            shape = RowShape.TOP
                        ) {
                            Fcl.Mode.values().forEach { m ->
                                val on = themeMode == m
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (on) Fcl.Theme else Fcl.Track,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .fclClickable {
                                            themeMode = m
                                            LauncherPrefs.setThemeMode(context, m)
                                            Fcl.setMode(m)
                                            refreshNavRail()
                                        }
                                ) {
                                    Text(
                                        text = m.label,
                                        color = if (on) Color.White else Fcl.TextSecondary,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(
                                            horizontal = 7.dp, vertical = 3.dp
                                        )
                                    )
                                }
                            }
                        }

                        FclSettingRow(
                            label = "主题色",
                            description = "按钮、选中态与强调元素的颜色。",
                            shape = RowShape.MIDDLE
                        ) {
                            themePresets.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(
                                            width = if (c == Fcl.themeColor) 2.dp else 0.dp,
                                            color = Fcl.TextSecondary,
                                            shape = CircleShape
                                        )
                                        .fclClickable {
                                            Fcl.setThemeColor(c)
                                            LauncherPrefs.setThemeColor(context, c.toArgb())
                                            refreshNavRail()
                                        }
                                )
                            }
                        }

                        FclSettingRow(
                            label = "面板不透明度",
                            description = "左侧栏与右侧菜单的透明度。",
                            shape = RowShape.MIDDLE
                        ) {
                            Text(
                                text = "${(panelAlpha * 100).toInt()}%",
                                color = Fcl.TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        // 滑块单独占一张卡的下半段，与上一行连成一组
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RowShape.BOTTOM.shape(),
                            color = Fcl.Card
                        ) {
                            Slider(
                                value = panelAlpha,
                                onValueChange = {
                                    panelAlpha = it
                                    Fcl.setPanelAlpha(it)
                                },
                                onValueChangeFinished = {
                                    LauncherPrefs.setPanelAlpha(context, panelAlpha)
                                    refreshNavRail()
                                },
                                modifier = Modifier.padding(horizontal = Fcl.RowPadding)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // ── 沙箱权限 ────────────────────────────────────────
                        FclSettingSection("沙箱权限")
                        val permModes = PermissionMode.values()
                        permModes.forEachIndexed { i, option ->
                            val selected = mode == option
                            FclSettingRow(
                                label = option.label,
                                description = option.desc,
                                shape = RowShape.of(i, permModes.size),
                                onClick = {
                                    if (option.dangerous) {
                                        pendingDanger = option
                                    } else {
                                        mode = option
                                        LauncherPrefs.setPermissionMode(context, option)
                                    }
                                }
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Fcl.Theme,
                                        unselectedColor = Fcl.TextTertiary
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "修改后需要重启实例（主界面「停止」再「启动」）才会生效。",
                            color = Fcl.TextTertiary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // ── 诊断 ────────────────────────────────────────────
                        // 只读操作，结果全部写进运行日志（版本卡片齿轮可查看/复制）。
                        FclSettingSection("诊断工具")
                        FclDiagnoseRow(
                            label = "平台信息",
                            desc = "把 ABI、SDK、路径等运行时信息写入运行日志。",
                            shape = RowShape.TOP,
                            onRun = { Thread { DSHRunner.diagnosePlatform(context) }.start() }
                        )
                        FclDiagnoseRow(
                            label = "扫描 flock",
                            desc = "定位 flock 原生模块的实际加载路径。",
                            shape = RowShape.MIDDLE,
                            onRun = { Thread { DSHRunner.scanFlockSource(context) }.start() }
                        )
                        FclDiagnoseRow(
                            label = "检查平台可选包",
                            desc = "列出 npm 按平台装下来的可选包。",
                            shape = RowShape.BOTTOM,
                            onRun = { Thread { DSHRunner.listPlatformPackages(context) }.start() }
                        )
                    }

                    if (tab == 1) {
                        // ── dsh 窗口 ────────────────────────────────────────
                        //
                        // 只影响那个全屏网页窗口（DshWebActivity）。
                        // 启动器本身的方向/缩放在这里改不了 —— 启动器在 Manifest
                        // 里锁 sensorLandscape（左侧导航栏竖屏会被挤成一条缝）。
                        //
                        // 改动**下次打开窗口时生效**：方向由 onCreate 里的
                        // requestedOrientation 决定，缩放由 setInitialScale 与
                        // viewport meta 决定，两者都只在窗口创建时设定。
                        FclSettingSection("屏幕方向")

                        FclSettingRow(
                            label = "屏幕模式",
                            description = dshScreenMode.desc,
                            shape = RowShape.SINGLE
                        ) {
                            // 四页签下横排三枚 chips 会挤，改用纵向列表更稳
                            // （见下面的三个单选行）
                            Text(
                                text = dshScreenMode.label,
                                color = Fcl.ThemeText,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        DshScreenMode.values().forEachIndexed { i, m ->
                            val on = dshScreenMode == m
                            FclSettingRow(
                                label = m.label,
                                description = m.desc,
                                shape = RowShape.of(i, DshScreenMode.values().size),
                                onClick = {
                                    dshScreenMode = m
                                    LauncherPrefs.setDshScreenMode(context, m)
                                    // 若此刻正停在网页窗口里，立刻应用方向；
                                    // 否则会把启动器自己也转过去，所以先判类型。
                                    val act = context as? android.app.Activity
                                    if (act is DshWebActivity) {
                                        val orient = when (m) {
                                            DshScreenMode.AUTO ->
                                                android.content.pm.ActivityInfo
                                                    .SCREEN_ORIENTATION_FULL_SENSOR

                                            DshScreenMode.LANDSCAPE ->
                                                android.content.pm.ActivityInfo
                                                    .SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                                            DshScreenMode.PORTRAIT ->
                                                android.content.pm.ActivityInfo
                                                    .SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                        }
                                        runCatching { act.requestedOrientation = orient }
                                    }
                                }
                            ) {
                                RadioButton(
                                    selected = on,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Fcl.Theme,
                                        unselectedColor = Fcl.TextTertiary
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // ── 缩放 ────────────────────────────────────────────
                        FclSettingSection("显示缩放")

                        FclSettingRow(
                            label = "窗口缩放",
                            description = "dsh 页面的整体缩放比例。越小一屏显示的内容越多，" +
                                "越大字越清楚。",
                            shape = RowShape.MIDDLE
                        ) {
                            Text(
                                text = "${dshZoom.toInt()}%",
                                color = if (dshZoom.toInt() == 100) Fcl.TextSecondary
                                else Fcl.ThemeText,
                                fontSize = 12.sp
                            )
                        }

                        // 与「面板不透明度」同样的两段式：上排标题+数值，下面滑块。
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RowShape.BOTTOM.shape(),
                            color = Fcl.Card
                        ) {
                            Slider(
                                value = dshZoom,
                                valueRange = LauncherPrefs.MIN_ZOOM.toFloat()..
                                    LauncherPrefs.MAX_ZOOM.toFloat(),
                                // 步进 5%（50→200 共 30 档，steps 是中间档数）：
                                // 连续滑块很难精确停在 100，而 100 是最需要能回得去的值。
                                steps = 29,
                                onValueChange = { dshZoom = it },
                                onValueChangeFinished = {
                                    LauncherPrefs.setDshZoom(context, dshZoom.toInt())
                                },
                                modifier = Modifier.padding(horizontal = Fcl.RowPadding)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "改动在下次打开 dsh 窗口时生效。缩放无法热更新 —— " +
                                "就地重载页面会打断正在进行的对话。",
                            color = Fcl.TextTertiary,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                    }

                    if (tab == 2) {
                        // ── 权限中心 ────────────────────────────────────────
                        FclSettingSection("特殊权限")
                        permEntries.forEachIndexed { i, entry ->
                            PermissionRow(
                                entry = entry,
                                shape = RowShape.of(i, permEntries.size),
                                onOpen = {
                                    PermissionCenter.openSafely(this@MainActivity, entry.open)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "这些权限需要跳到系统设置页手动开启，返回本页后状态会自动刷新。",
                            color = Fcl.TextTertiary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // ── AI 读屏（MediaProjection）──────────────────────
                        //
                        // 单独一组而不是并进上面的「特殊权限」：那些是跳到系统
                        // 设置页拨开关，这一项是弹系统授权框、且**每次都失效**。
                        // 混在一起会让人以为它也是一次性开启、永久有效。
                        FclSettingSection("AI 读屏")

                        // 订阅状态：授权/撤销都可能发生在系统侧（通知栏停止投屏），
                        // 读这个状态才能让这一行自己刷新。
                        val captureActive = ScreenCaptureState.active

                        FclSettingRow(
                            label = "屏幕截图",
                            description = if (captureActive) {
                                "已开启。AI 可直接读取当前画面；不需要时点此关闭。" +
                                    "授权不会持久化，应用重启后需要重新开启。"
                            } else {
                                "让 AI 能看到手机画面。系统会弹出「开始录制或投放」" +
                                    "确认框，必须手动同意。这是本机唯一可用的截屏方式 ——" +
                                    "screencap / screenrecord 在应用沙箱里必然失败。"
                            },
                            shape = RowShape.SINGLE,
                            onClick = {
                                if (captureActive) {
                                    ScreenCaptureService.stop(this@MainActivity)
                                    ui.log("📸 已停止屏幕截图")
                                } else {
                                    requestScreenCapture()
                                }
                            }
                        ) {
                            Text(
                                text = if (captureActive) "关闭" else "开启",
                                color = if (captureActive) Fcl.Danger else Fcl.ThemeText,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "开启后当前画面会持续写入 ~/screenshot.png，" +
                                "AI 通过 /screenshot 端点读取该路径，无需任何 shell 权限。",
                            color = Fcl.TextTertiary,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                    }

                    if (tab == 3) {
                        // ── 关于 ────────────────────────────────────────────
                        //
                        // 版本号从 PackageManager 现取，不写死在代码里：
                        // build.gradle.kts 改版本后这里自动跟着变，
                        // 免得发版时忘了同步、用户报 bug 时报了个旧版本号。
                        val pkgInfo = remember {
                            runCatching {
                                context.packageManager
                                    .getPackageInfo(context.packageName, 0)
                            }.getOrNull()
                        }
                        val versionName = pkgInfo?.versionName ?: "未知"

                        FclSettingSection("应用")
                        FclSettingRow(
                            label = "DSH Launcher",
                            description = "把 dsh 完整搬到手机上运行，界面版式参照 FCL。",
                            shape = RowShape.TOP
                        ) {
                            Text(
                                text = "v$versionName",
                                color = Fcl.ThemeText,
                                fontSize = 12.sp
                            )
                        }
                        FclSettingRow(
                            label = "包名",
                            shape = RowShape.BOTTOM
                        ) {
                            Text(
                                text = context.packageName,
                                color = Fcl.TextTertiary,
                                fontSize = 10.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        FclSettingSection("开发者")
                        FclSettingRow(
                            label = "夜ksy",
                            description = "问题反馈、功能建议、安装遇到卡点，都可以通过下面任一渠道找。",
                            shape = RowShape.TOP
                        ) {
                            Text(
                                text = "开发",
                                color = Fcl.Success,
                                fontSize = 10.sp
                            )
                        }
                        // 三行联系方式的文本都可长按复制 —— 数字串让用户手抄
                        // 是不现实的，而 qq 群号又不能直接点开（要额外权限）。
                        FclSettingRow(
                            label = "B 站",
                            description = "视频演示与更新说明",
                            shape = RowShape.MIDDLE
                        ) {
                            Text(
                                text = "夜ksy",
                                color = Fcl.ThemeText,
                                fontSize = 12.sp
                            )
                        }
                        FclSettingRow(
                            label = "QQ",
                            description = "一对一反馈",
                            shape = RowShape.MIDDLE
                        ) {
                            Text(
                                text = "2676196492",
                                color = Fcl.ThemeText,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        FclSettingRow(
                            label = "QQ 群",
                            description = "使用交流与版本发布",
                            shape = RowShape.BOTTOM
                        ) {
                            Text(
                                text = "139235410",
                                color = Fcl.ThemeText,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        FclSettingSection("组成")
                        FclSettingRow(
                            label = "容器",
                            description = "Node 运行时随应用内置，首次启动自动解压，无需联网下载。",
                            shape = RowShape.TOP
                        )
                        FclSettingRow(
                            label = "引擎",
                            description = "dsh 本体在下载中心单独安装。多版本可共存，" +
                                "各自一套数据目录，互不相通。",
                            shape = RowShape.MIDDLE
                        )
                        FclSettingRow(
                            label = "界面",
                            description = "Kotlin + Jetpack Compose 手写。",
                            shape = RowShape.BOTTOM
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        FclSettingSection("致谢")
                        FclSettingRow(
                            label = "参考项目",
                            description = "界面版式参考 Fold Craft Launcher；" +
                                "容器与插件布局参考 Deepseek-Harness-Local-Android。",
                            shape = RowShape.SINGLE
                        )
                    }
                }
            }
        }

        // 危险模式二次确认
        pendingDanger?.let { dangerous ->
            AlertDialog(
                onDismissRequest = { pendingDanger = null },
                title = { Text("开启完全访问？") },
                text = { Text(dangerous.desc) },
                confirmButton = {
                    TextButton(onClick = {
                        mode = dangerous
                        LauncherPrefs.setPermissionMode(context, dangerous)
                        pendingDanger = null
                        hint("已切换到完全访问，重启实例后生效")
                    }) { Text("我了解风险，开启") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDanger = null }) { Text("取消") }
                }
            )
        }
    }

    /**
     * 权限中心的一行。
     *
     * 布局：盾牌图标 + 标题/说明 + 右侧状态与操作按钮。
     * 已授权或系统不支持的项不给按钮（没有可操作内容），
     * 未授权的才显示「去授权」，点整行或按钮都能跳系统设置。
     */
    @Composable
    private fun PermissionRow(
        entry: PermissionCenter.Entry,
        shape: RowShape = RowShape.SINGLE,
        onOpen: () -> Unit
    ) {
        val color = when (entry.status) {
            PermissionCenter.Status.GRANTED -> Fcl.Success
            PermissionCenter.Status.DENIED -> Fcl.Warning
            PermissionCenter.Status.UNSUPPORTED -> Fcl.TextTertiary
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (entry.open != null) Modifier.fclClickable { onOpen() } else Modifier
                ),
            // FCL 的 bg_container_white：纯白卡片 + 8dp 圆角，无描边
            shape = shape.shape(),
            color = Fcl.Card
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 统一用盾牌图标，靠 tint 区分状态 ——
                // 扁平风格下颜色+文字比换图标更容易扫读。
                Image(
                    painter = painterResource(id = LocalContext.current.resources.getIdentifier("ic_perm_shield", "drawable", LocalContext.current.packageName)),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(color),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        color = Fcl.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.desc,
                        color = Fcl.TextSecondary,
                        fontSize = 9.sp,
                        lineHeight = 13.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = entry.statusText,
                        color = color,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                    if (entry.actionText != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        // FCL 的按钮一律是主题色底 + 白字（对应 FCLButton 的默认样式）
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Fcl.Theme,
                            modifier = Modifier.fclClickable { onOpen() }
                        ) {
                            Text(
                                text = entry.actionText,
                                color = Color.White,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 下载中心：dsh 引擎的下载与安装。
     *
     * 容器（Node + bash + 第三方依赖）已随 APK 内置，这里只负责引擎那一层。
     * 两条安装路径共用 [DSHRunner.installDsh]，所以包结构要求一致：
     * 包内需含 `@deepseek-ai/dsh/lib/bin.js`，外层是 `lib/`、`node_modules/`
     * 还是直接作用域目录都能自动归一化。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DownloadPanel(
        onPickLocalFile: () -> Unit
    ) {
        val context = LocalContext.current

        // 订阅版本中心 / 安装器的状态。它们都是 Compose 状态，
        // 变化会自动触发重组，无需手动刷新。
        val catalogPhase = EngineInstaller.catalogPhase
        val versions = EngineInstaller.versions
        val tags = EngineInstaller.distTags
        val catalogMessage = EngineInstaller.catalogMessage
        val installPhase = EngineInstaller.installPhase
        val installMessage = EngineInstaller.installMessage
        val busy = EngineInstaller.isBusy()

        // 当前页签。默认落在 dsh 下载 —— 它是唯一接了真实数据的页。
        // 放在这里而不是 LauncherState：切走面板再回来回到默认页是合理的，
        // 记住上次页签反而会让人以为面板没被重置。
        var tab by remember { mutableStateOf(DownloadTab.DSH) }

        // 待命名的安装目标版本。非空时弹出「实例命名」小窗 ——
        // 同一个版本可以建多个实例，所以点「下载」不能直接开工，
        // 必须先确定这次要装成哪个实例（否则会默认覆盖已有实例）。
        var pendingInstallVersion by remember { mutableStateOf<String?>(null) }

        // 安装 / 建实例之后的刷新触发器。
        //
        // 实例的增删改都直接改文件，不经过任何 Compose 状态；
        // 自增它能让依赖它的读取重新读盘。下载中心**不再做实例管理**
        // （列实例、改名、删除都归「实例设置」），所以这里只剩安装路径在用。
        var instanceTick by remember { mutableStateOf(0) }

        // 导出流程的状态由整合包页签订阅（导入/导出已搬到那边），
        // 这里不再读，避免 dsh 页因为无关的打包任务而重组。

        // 打开面板就拉一次版本列表；已有缓存则只在失败时允许手动重试
        LaunchedEffect(Unit) {
            if (catalogPhase == EngineInstaller.CatalogPhase.IDLE) {
                EngineInstaller.fetchCatalog()
            }
        }

        // 注意：这里刻意**不**读「当前装了什么」。
        // 下载中心只负责「把某个版本装下来」，已装版本与切换都在版本管理里，
        // 两处各显示一份安装状态只会互相矛盾（装完没刷新、切了版本没同步）。

        // 返回键收回面板（面板内不再放「关闭」按钮）
        BackHandler(enabled = true) { ui.closePanel() }

        // 底色交给 PanelHost（Fcl.PanelBg，跟随透明度滑块），这里透明即可
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 标题 ────────────────────────────────────────────────
                Text(
                    text = "下载中心",
                    color = Fcl.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)
                )

                HorizontalDivider(color = Fcl.Divider)

                // ── 页签 ────────────────────────────────────────────────
                // 仿 FCL 的 ui_download.xml：TabLayout 悬在内容之上，
                // 一条圆角底板 + 选中项下划线，下面才是各自的内容区。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    DownloadTabBar(selected = tab, onSelect = { tab = it })
                }

                if (tab == DownloadTab.DSH) {
                    // dsh 下载：沿用原有的引擎安装面板（唯一接了真实数据的一页）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            // 20dp 收到 12dp：与插件下载页保持同一套外距，
                            // 两个页签切换时内容左边不会跳位置
                            .padding(12.dp)
                    ) {
                    // ── 引擎说明 ────────────────────────────────────────
                    // 照 FCL 的 bg_container_white：纯白卡片 + 8dp 圆角。
                    // 这里不再显示「已安装/未安装」—— 安装状态归版本管理页，
                    // 下载中心只说明「这一页是干什么的」。
                    FclCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "dsh 引擎",
                            color = Fcl.TextPrimary,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "容器（Node 运行时）已随应用内置，并在首次启动时自动解压。" +
                                "下面列出官方可用版本，点「下载」装哪个就用哪个，" +
                                "多版本可共存，切换在「版本管理」里。",
                            color = Fcl.TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── 版本列表（来自官方 npm registry）────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "选择版本",
                            color = Fcl.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (catalogPhase == EngineInstaller.CatalogPhase.LOADING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Fcl.Theme
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        TextButton(
                            onClick = { EngineInstaller.fetchCatalog() },
                            enabled = catalogPhase != EngineInstaller.CatalogPhase.LOADING
                        ) { Text("刷新", fontSize = 11.sp) }
                    }

                    Text(
                        text = catalogMessage,
                        color = when (catalogPhase) {
                            EngineInstaller.CatalogPhase.ERROR -> Fcl.Danger
                            EngineInstaller.CatalogPhase.READY -> Fcl.TextSecondary
                            else -> Fcl.TextTertiary
                        },
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (catalogPhase == EngineInstaller.CatalogPhase.READY) {
                        // 版本可能很多，限制渲染数量避免面板过长。
                        // 最新的排在最前，所以截断不影响选常用版本。
                        val shown = versions.take(40)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Fcl.Radius))
                                .background(Fcl.Card)
                        ) {
                            shown.forEachIndexed { idx, v ->
                                // 反查该版本对应的 dist-tag（latest/next/alpha…）
                                val tag = tags.entries.firstOrNull { it.value == v }?.key

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = 12.dp,
                                            end = 6.dp,
                                            top = 6.dp,
                                            bottom = 6.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = v,
                                                color = Fcl.TextPrimary,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            if (tag != null) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Fcl.Track
                                                ) {
                                                    Text(
                                                        text = tag,
                                                        color = Fcl.ThemeText,
                                                        fontSize = 9.sp,
                                                        modifier = Modifier.padding(
                                                            horizontal = 6.dp, vertical = 2.dp
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 每行独立的下载按钮：点哪行装哪版，
                                    // 不再需要"先选中再安装"两步操作。
                                    //
                                    // 点下去先弹「实例命名」小窗（见 InstanceNameDialog）：
                                    // 同一个版本可以建多个实例，必须先确定名字，
                                    // 否则会默认覆盖掉已有实例。
                                    TextButton(
                                        onClick = {
                                            EngineInstaller.selectedVersion = v
                                            pendingInstallVersion = v
                                        },
                                        enabled = !busy,
                                        contentPadding = PaddingValues(
                                            horizontal = 12.dp, vertical = 4.dp
                                        )
                                    ) {
                                        Text(
                                            text = "下载",
                                            fontSize = 11.sp,
                                            color = Fcl.ThemeText
                                        )
                                    }
                                }
                                if (idx < shown.size - 1) {
                                    HorizontalDivider(color = Fcl.Divider)
                                }
                            }
                        }
                        if (versions.size > shown.size) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "仅显示最新的 ${shown.size} 个，共 ${versions.size} 个版本",
                                color = Fcl.TextTertiary,
                                fontSize = 9.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Fcl.Divider)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "点版本行右侧的「下载」即可安装，安装前会让你给实例起个名字。" +
                            "同一版本可以建多个实例，各自装一份引擎、各自一套工作区与会话，互不相通。" +
                            "安装完成后回到主界面点「启动」。" +
                            "若安装失败，可在主界面齿轮处打开运行日志查看具体原因。",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                    }
                } else if (tab == DownloadTab.PLUGIN) {
                    // 插件下载：接了真实数据（插件市场索引 → 装进当前实例）。
                    //
                    // ★ 这里必须给成有界高度，不能套 verticalScroll ★
                    //
                    // PluginDownloadPanel 内部是「左 1/3 + 右 2/3」两栏，
                    // 靠 weight 分宽度、靠 fillMaxHeight 分高度。外面若再包一层
                    // verticalScroll，高度约束会变成无限，两栏塌成内容高度、
                    // 分栏直接立不起来（实例设置页踩过同一个坑）。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        PluginDownloadPanel()
                    }
                } else if (tab == DownloadTab.SKILL) {
                    // skill 下载：走「粘链接安装」，不复用插件那套翻页列表 ——
                    // skill 没有统一的版本索引可拉，硬套只会得到一个空列表。
                    //
                    // 同样必须给有界高度（两栏布局），不能套 verticalScroll。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        SkillDownloadPanel(
                            onImport = {
                                skillImportLauncher.launch("application/zip")
                            }
                        )
                    }
                } else if (tab == DownloadTab.MCP) {
                    // MCP 下载：本质是「注册表入口 + 导入」，理由见该面板的注释。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        McpDownloadPanel(
                            onImport = {
                                mcpImportLauncher.launch("application/zip")
                            }
                        )
                    }
                } else {
                    // 整合包：版式照 FCL 的 page_download.xml。
                    // 右侧是空态（尚未接入下载源），假列表与假按钮已按需求去掉。
                    DownloadBrowseTab(
                        tab = tab,
                        onPickLocalFile = onPickLocalFile
                    )
                }
            }
        }

        // ── 安装：直接开工，不再让用户起名字 / 确认 ──────────────────────
        //
        // 部署的标识就是**版本号本身**（`usr/lib/versions/` 下的目录名）。
        // 同一个版本装第二次时，Instances.allocateVersion 会自动加后缀
        // （`0.2.1` → `0.2.1-2`），所以既不需要用户干预，也不会覆盖已有部署。
        val pending = pendingInstallVersion
        if (pending != null) {
            pendingInstallVersion = null
            EngineInstaller.selectedVersion = pending
            EngineInstaller.installVersion(context, pending)
        }

        // ── 本地包：同样直接开工 ────────────────────────────────────────
        //
        // 流要现开现用：不提前打开，避免「选完文件后放置太久」失效。
        val localZip = pendingLocalZip
        if (localZip != null) {
            pendingLocalZip = null
            val stream = runCatching {
                context.contentResolver.openInputStream(localZip)
            }.getOrNull()
            if (stream != null) {
                DshEngineDownloader.installFrom(context, stream, "本地引擎包")
            }
            // 读不到文件时不做任何改动：列表里不会留下半个部署，
            // 因为版本目录是解压成功之后才出现的。
            instanceTick++
        }

        // ── 居中模态进度窗 ──────────────────────────────────────────────
        // 只在「安装中」与「已结束」时出现。安装中不可关闭，
        // 因为底层 npm 进程还在跑，关掉窗只是让用户失去对它的控制。
        if (installPhase != EngineInstaller.InstallPhase.IDLE) {
            InstallProgressDialog(
                phase = installPhase,
                message = installMessage,
                progress = EngineInstaller.progress,
                bytesPerSecond = EngineInstaller.bytesPerSecond,
                onCancel = { EngineInstaller.cancelInstall() },
                onDismiss = { EngineInstaller.resetInstall() }
            )
        }
    }

    /** 下载速度文案。与 EngineInstaller 内部格式保持一致。 */
    private fun fmtSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024 ->
            String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1024.0 / 1024)
        bytesPerSecond >= 1024L ->
            String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
    }

    /**
     * 居中的安装进度窗。
     *
     * ## 为什么用 Dialog 而不是在页面内画一层
     *
     * 要求是"弹窗显示时其他 UI 无法交互"。`Dialog` 天然是模态的：
     * 它独占一个 Window，触摸事件不会穿透到下层界面，比用 Box 叠一层
     * 再加拦截廉价得多，也不容易漏（比如侧栏的原生按钮叠一层 Box 就拦不住）。
     *
     * `dismissOnBackPress` / `dismissOnClickOutside` 在安装中设为 false，
     * 避免用户误触把窗口关掉却以为任务也停了。
     */
    @Composable
    private fun InstallProgressDialog(
        phase: EngineInstaller.InstallPhase,
        message: String,
        progress: Float,
        bytesPerSecond: Long,
        onCancel: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val running = phase == EngineInstaller.InstallPhase.INSTALLING
        val color = when (phase) {
            EngineInstaller.InstallPhase.ERROR -> Fcl.Danger
            EngineInstaller.InstallPhase.DONE -> Fcl.Success
            else -> Fcl.Theme
        }

        Dialog(
            // 安装中不允许用返回键关闭：进程还在跑，关掉窗只会让人误以为已停下
            onDismissRequest = { if (!running) onDismiss() },
            properties = DialogProperties(
                dismissOnBackPress = !running,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(
                modifier = Modifier.width(340.dp),
                // FCL 的 dialog_background：8dp 圆角 + #F4F4F4
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.DialogBg
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = when (phase) {
                            EngineInstaller.InstallPhase.INSTALLING -> "正在安装"
                            EngineInstaller.InstallPhase.DONE -> "安装完成"
                            else -> "安装失败"
                        },
                        color = Fcl.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 确定进度条：进度由真实下载字节驱动（见 EngineInstaller 的进度采样）。
                    // 只有在还没量到任何字节的极短窗口（progress < 0）里才退回不确定动画。
                    if (running) {
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = color,
                                trackColor = Fcl.Track
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = color,
                                trackColor = Fcl.Track
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))

                        // 百分比单独一行，与字节数/速度分开，方便快速扫读
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (progress >= 0f) {
                                    "${(progress * 100).toInt()}%"
                                } else {
                                    "准备中"
                                },
                                color = color,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            // 速度：只有真的在下数据时才显示，避免一直挂个「0 B/s」
                            if (running && bytesPerSecond > 0) {
                                Text(
                                    text = fmtSpeed(bytesPerSecond),
                                    color = Fcl.TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Text(
                        text = message,
                        color = color,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.heightIn(min = 34.dp)
                    )

                    if (running) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "首次安装需下载数百个依赖包，可能需要数分钟。",
                            color = Fcl.TextTertiary,
                            fontSize = 9.sp,
                            lineHeight = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (running) {
                            OutlinedButton(onClick = onCancel) { Text("取消安装") }
                        } else {
                            TextButton(onClick = onDismiss) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }

    /**
     * 安装前给实例命名的小窗。
     *
     * ## 为什么要弹这一步
     *
     * 同一个 dsh 版本可以建多个实例，每个实例装一份引擎、一套 HOME 与工作区，
     * 互不相通。若点「下载」就直接开工，第一个实例会被第二个悄悄覆盖 ——
     * 用户看到的是「装完了，但上一个实例里聊的东西不见了」。
     * 所以命名是安装的必要前置，不是可选步骤。
     *
     * ## 默认名与重名后缀
     *
     * 默认名取 `dsh-<版本>`。若已被占用，[Instances.suggestName] 会直接给出
     * 带后缀的可用名（`dsh-0.2.1-2`、`-3`…），所以**用户什么都不改也能按确定** ——
     * 这正是需求说的「提前发现有同名实例时，默认名称会自动加后缀」。
     *
     * 用户手工把名字改成另一个已存在的实例时，**确定也不会被拦**：同样自动
     * 加后缀。实例的标识只有名称，所以重名意味着「再并存一个」而不是
     * 「覆盖原来那个」；下载同一版本、反复从本地装同一个包都靠这条路径
     * 实现同版本共存。「名称为空」才拦。
     *
     * ## 为什么 onConfirm 返回 String?
     *
     * 建目录/写元数据可能失败（磁盘满、权限）。返回非空字符串表示失败原因，
     * 窗口保持打开并把原因显示出来；返回 null 表示成功，由调用方关窗。
     * 若只返回 Unit，失败时窗口已经关掉，用户就只剩一句日志可看。
     *
     * @param version 要安装的引擎版本号；从 registry 下载时是确切版本。
     * @param nameBase 默认名的基名。默认 `dsh-<版本>`；从本地 zip 安装时
     *   选择那一刻还读不到版本号（要解析包内 package.json），所以调用方传
     *   `dsh`，等装完由安装器把真实版本回写进实例元数据。
     * @param versionHint 描述里那句「这是哪个版本」。本地包传一句说明，
     *   避免显示成「引擎版本 本地安装包」这种不通的话。
     * @param onConfirm 传入选定的名字；返回 null 表示已创建成功。
     */
    @Composable
    private fun InstanceNameDialog(
        version: String,
        onDismiss: () -> Unit,
        onConfirm: (String) -> String?,
        nameBase: String = "dsh-$version",
        versionHint: String = "引擎版本 $version。"
    ) {
        val context = LocalContext.current

        // 默认名按基名算一次。用 remember(nameBase) 而不是 remember：
        // 同一个小窗被复用于另一个版本（或另一条安装路径）时，
        // 默认名必须跟着换，否则会把上一次算出来的残留名带过去。
        val baseName = nameBase
        // 命名窗已不再被任何调用点使用：安装改为按「版本号 + 自动后缀」分配目录名，
        // 不需要用户起名字。这里保留最小实现，只为不打断既有渲染代码。
        val autoRenamed = false
        var name by remember(baseName) { mutableStateOf(baseName) }
        var error by remember(baseName) { mutableStateOf<String?>(null) }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(
                modifier = Modifier.width(340.dp),
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.DialogBg
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "给实例命名",
                        color = Fcl.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = versionHint +
                            "同一版本可以建多个实例，各自一套引擎、工作区与会话，互不相通。",
                        color = Fcl.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            // 用户一动手就把上次的报错清掉，避免红色提示一直挂着
                            error = null
                        },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 自动加过后缀就明说一句，否则用户会以为默认名本来就长这样
                    if (autoRenamed && error == null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "已存在同名实例，默认名已自动加后缀。",
                            color = Fcl.TextTertiary,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }

                    if (error != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = error!!,
                            color = Fcl.Danger,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(modifier = Modifier.width(4.dp))
                        // 只有「名称为空」才拦。重名不拦 —— 见下面的说明。
                        TextButton(
                            onClick = {
                                val clean = name.trim()
                                if (clean.isEmpty()) {
                                    error = "名称不能为空"
                                    return@TextButton
                                }
                                // ★ 重名自动追加后缀，不再报错 ★
                                //
                                // 实例的标识只有名称，所以撞名意味着「再并存一个」，
                                // 而不是「覆盖原来那个」：下载同一个版本、或反复从
                                // 本地安装同一个包，正是要这样多开几个实例。
                                // [Instances.create] 内部也做了同样的处理，这里先算一次
                                // 是为了把最终名字交给 onConfirm —— 调用方用它切当前实例。
                                error = onConfirm(clean)
                            }
                        ) {
                            Text("确定", color = Fcl.ThemeText)
                        }
                    }
                }
            }
        }
    }

    /**
     * DSH 图标。
     *
     * 用 `res/drawable/ic_dsh_logo.xml` 这个**矢量图**，不是 PNG。
     *
     * ## 为什么最终选了矢量图
     *
     * 先用过 PNG，两次都卡在构建期：从外部拷进项目的二进制文件带着
     * 构建进程读不了的属主/权限，`mergeResources` 和 `packageApk` 都会抛
     * `AccessDeniedException`，而且复制、改名都治不了根（权限跟的是文件本身）。
     *
     * XML 是文本，我能直接创建、构建进程也一定能读。附带三个好处：
     * 任意尺寸不失真、体积只有几百字节、颜色可直接改。
     */
    @Composable
    private fun DshLogo(size: Dp, corner: Dp) {
        Image(
            painter = painterResource(id = R.drawable.ic_dsh_logo),
            contentDescription = "DSH",
            modifier = Modifier
                .size(size)
                // 矢量的圆角是画在路径里的，这里再裁一次让边缘更干净
                .clip(RoundedCornerShape(corner))
        )
    }

    /**
     * 账号区：头像 + 用户名，居中。
     *
     * 排版照 FCL `activity_main.xml` 的 `@id/account`：
     * 52dp 圆头像 → 名字（16sp 粗体、主题色）→ 一行提示（12sp）。
     * 这里没有「添加账户」提示，因为本启动器只有一个本机用户，
     * 那行提示在 FCL 里是用来引导登入微软账户的，这里放会误导。
     */
    @Composable
    private fun AccountBlock() {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 头像：DSH 图标。之前是「渐变底 + JS 字母」，
            // 渐变不符合扁平化要求，改用真实图标（纯色兜底）。
            DshLogo(size = 52.dp, corner = 14.dp)
            Spacer(modifier = Modifier.height(4.dp))
            // 原来分两行显示「本地用户 / 离线账户」，现在合并为一行。
            // 这个启动器只有单机场景，账户类型对用户没有实际意义。
            Text(
                text = "用户",
                // FCL 的 account_name 用 app:use_theme_color="true"，即主题蓝
                color = Fcl.ThemeText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }

    /**
     * 启动区：版本卡片 + 管理版本按钮。
     *
     * 版本卡片右侧的齿轮在 FCL 里是「版本设置」，这里用来打开运行日志 ——
     * 排查启动失败时必须能看到 Node 的输出，而侧栏 5 个图标都已按需求占位，
     * 放在这里是最不破坏布局的入口。
     */
    @Composable
    private fun LaunchBlock(
        state: LauncherState,
        onLaunchOrOpen: () -> Unit,
        onStop: () -> Unit,
        onRestart: () -> Unit,
        onManageVersions: () -> Unit,
        onOpenLog: () -> Unit,
        /** 用手机自带浏览器打开 dsh 页面（老设备 WebView 太旧时的出路）。 */
        onOpenBrowser: () -> Unit
    ) {
        val alive = state.runState.isAlive
        val starting = state.runState == RunState.STARTING
        val running = state.runState == RunState.RUNNING
        val canOpen = running && state.url != null

        // 当前版本读共享状态（见 LauncherState.engineVersion）：
        // 在版本管理里切换版本后，这张卡片会跟着一起变。
        // 之前这里自己 remember 一份、只在 installPhase 变化时刷新，
        // 所以切了版本卡片纹丝不动 —— 那正是「版本不会变」的成因。
        val context = LocalContext.current
        val shownVersion = state.engineVersion
        // 安装完成 / 重新进入时与磁盘对齐。切换版本的刷新在弹窗里做。
        LaunchedEffect(EngineInstaller.installPhase) {
            state.refreshEngineVersion(context)
        }

        // ── 版本卡片：对应 FCL @id/version_card ─────────────────────────
        // FCL 的版本卡片是「图标 + 版本名 + 右侧齿轮」，点卡片本身进版本列表，
        // 齿轮进版本设置。这里保持同样的分工：
        //   点卡片 → 版本管理；齿轮 → 运行日志（排查启动失败必须能看到 Node 输出）。
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fclClickable { onManageVersions() },
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.Card
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DshLogo(size = 26.dp, corner = 7.dp)

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // 不再显示「未安装引擎」这类提示：有版本号就写版本号，
                        // 没有就留一个中性占位。
                        text = shownVersion ?: "—",
                        color = if (shownVersion != null) Fcl.TextPrimary else Fcl.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = if (shownVersion != null) "点此管理版本" else "点此安装引擎",
                        color = Fcl.TextSecondary,
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }

                // 齿轮：打开运行日志。
                // 用矢量图而不是 emoji：emoji 是彩色字形，破坏扁平单色风格。
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .fclClickable { onOpenLog() },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_rail_settings),
                        contentDescription = "运行日志",
                        colorFilter = ColorFilter.tint(Fcl.Icon),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ── 启动按钮：对应 FCL @id/start ────────────────────────────────
        // FCL 把「启动」单独做成屏幕底部的一整块可点区域：播放图标 + 文字横排居中，
        // 图标与文字都用主题色（app:use_theme_color="true"），按下缩放（anim_scale）。
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fclClickable(enabled = !starting) { onLaunchOrOpen() },
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.Card
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_start),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Fcl.Theme),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        starting -> "启动中…"
                        canOpen -> "打开界面"
                        alive -> "运行中"
                        else -> "启动"
                    },
                    color = Fcl.Theme,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // ── 浏览器访问入口 ──────────────────────────────────────────────
        //
        // 只在**启动成功之后**出现（canOpen = 运行中且有 URL）：还没起来时
        // 点它只会得到一个连不上的地址，属于误导。
        //
        // 为什么需要这条退路：内置 WebView 的版本由系统镜像决定、**无法升级**。
        // 老设备上内核太旧会让 dsh 前端白屏或 JS 报错，而同一台机器上的
        // Chrome / 系统浏览器往往是最新内核 —— 把渲染交给别人的内核就能用。
        //
        // 做成小按键而不是与「打开界面」并列的大按钮：
        // 绝大多数设备内置 WebView 是好的，这条路只在出问题时才需要。
        // 用户描述的场景也正是「页面用不了 → 回主页 → 找浏览器入口」。
        if (canOpen) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fclClickable { onOpenBrowser() }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 按钮文案要说清实际动作：复制地址 + 打开浏览器，
                // 用户随后自己粘贴。写成「用浏览器打开」会让人以为点完
                // 页面就自动出来了，结果停在浏览器首页会以为功能坏了。
                Text(
                    text = "复制地址并打开浏览器",
                    color = Fcl.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }

        // ── 运行中时补「停止 / 重启」 ───────────────────────────────────
        // FCL 退出游戏即结束进程，我们的网页窗口关掉后 Node 仍在跑，
        // 所以必须给显式的停止入口；改了配置（如权限模式）后也需要重启。
        if (alive) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    Text("停止", color = Fcl.Danger, fontSize = 11.sp, maxLines = 1)
                }
                TextButton(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    Text("重启", color = Fcl.ThemeText, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 日志面板
    // ------------------------------------------------------------------

    /**
     * 运行日志。
     *
     * 1. 实时滚动：新日志到达自动滚到底，用户上滑查历史后暂停跟随；
     * 2. 一键复制：按行号格式化后写入剪贴板；
     * 3. 只看错误：过滤 ERR/ERROR/WARN 及 WebView 的 WINERR/REJECT。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LogPanel(state: LauncherState) {
        val context = LocalContext.current
        var errorOnly by remember { mutableStateOf(false) }
        var copied by remember { mutableStateOf(false) }

        // 返回键收回面板（面板内不再放「关闭」按钮）
        BackHandler(enabled = true) { ui.closePanel() }

        // 用 derivedStateOf 避免每次重组都重算整张过滤表
        val lines by remember(state.logs.size, errorOnly) {
            derivedStateOf {
                if (!errorOnly) state.logs.toList()
                else state.logs.filter { l ->
                    val u = l.uppercase()
                    u.contains("ERR") || u.contains("ERROR") ||
                        u.contains("WARN") || u.contains("WINERR") ||
                        u.contains("REJECT") || l.contains("❌") ||
                        l.contains("失败") || l.contains("异常")
                }
            }
        }

        val listState = rememberLazyListState()

        // 只在用户已位于底部附近时才跟随，否则翻历史会被不断拽回底部
        LaunchedEffect(lines.size) {
            if (lines.isEmpty()) return@LaunchedEffect
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (last >= lines.size - 3) {
                listState.animateScrollToItem(lines.size - 1)
            }
        }

        // 底色交给 PanelHost（Fcl.PanelBg，跟随透明度滑块），这里透明即可
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "运行日志",
                        color = Fcl.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${lines.size} 行",
                        color = Fcl.TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        // 过滤态只复制可见行，避免"复制了但看到的不是全部"
                        val text = if (errorOnly) lines.joinToString("\n") else state.logsAsText()
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh-log", text))
                        copied = true
                    }) {
                        Text(if (copied) "已复制 ✓" else "复制")
                    }
                    TextButton(onClick = {
                        state.clearLogs()
                        copied = false
                    }) {
                        Text("清空")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    FilterChip(
                        selected = errorOnly,
                        onClick = { errorOnly = !errorOnly },
                        label = { Text("只看错误") }
                    )
                }

                HorizontalDivider(color = Fcl.Divider)

                if (lines.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (errorOnly) "没有匹配的错误" else "暂无日志",
                            color = Fcl.TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(lines) { _, line ->
                            val isErr = line.uppercase().let {
                                it.contains("ERR") || it.contains("❌") ||
                                    it.contains("失败") || it.contains("异常")
                            }
                            Text(
                                text = line,
                                color = if (isErr) Fcl.Danger else Fcl.TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // ------------------------------------------------------------------
    // 非 Compose 逻辑
    // ------------------------------------------------------------------

    /**
     * 打开（或重新打开）独立的全屏网页窗口。
     *
     * 仿 FCL：网页在独立 Activity 里全屏运行，启动器退到后台。
     * 关掉窗口后进程仍在跑，用户可以从「打开界面」再进来。
     */
    private fun openWebWindow(state: LauncherState) {
        val u = state.url ?: return
        // 记录实际交给内置窗口的地址（token 脱敏）。
        // 排查「浏览器打不开」时要用它和 dsh 打印的最新 `dsh web:` 行对照，
        // 确认端口与 token 都来自当前存活的进程 —— 曾出现过旧引擎占着 3080、
        // 新进程改用 3081 的情况。
        ui.log("📱 内置窗口地址：${redactUrl(u)}")
        startActivity(
            Intent(this, DshWebActivity::class.java)
                .putExtra(DshWebActivity.EXTRA_URL, u)
        )
    }

    /**
     * 日志用的脱敏 URL：token 只露头尾各 4 位。
     *
     * ## 为什么需要它
     *
     * 排查鉴权失败时最需要回答的是「App 交给浏览器的是不是当前活着的那个地址」：
     * 把这里打印的片段与 dsh 打印的最新 `dsh web:` 行对照，
     * 就能判断端口/token 是否对得上。
     *
     * 只露头尾既能比对，又不会把凭证写进日志（日志可以被复制/分享出去）。
     */
    private fun redactUrl(u: String): String = runCatching {
        val token = Uri.parse(u).getQueryParameter("token").orEmpty()
        when {
            token.isEmpty() -> u
            token.length <= 8 -> u.replace(token, "…")
            else -> u.replace(token, token.take(4) + "…" + token.takeLast(4))
        }
    }.getOrDefault(u.substringBefore('?'))

    /**
     * 用**手机自带的浏览器**打开 dsh 页面。
     *
     * ## 为什么需要这条路
     *
     * 内置 WebView 的版本由系统镜像决定，且**无法升级**。部分老设备上的
     * WebView 内核太旧，dsh 前端会白屏或 JS 报错，而同一台机器上的
     * Chrome / 系统浏览器往往是最新内核 —— 换个浏览器就能用。
     *
     * 这不是「更差的备选」，而是老设备的**唯一出路**：应用改不了系统 WebView
     * 版本，只能把渲染交给别人的内核。
     *
     * ## 为什么 URL 能跨进程用
     *
     * dsh 监听 `127.0.0.1`，回环地址是**设备级共享**的（不分应用 UID），
     * 所以浏览器进程同样能连上。URL 里带的 token 是 dsh 自己的鉴权参数，
     * 浏览器访问同样需要它 —— 这与在电脑上打开是同一套机制。
     *
     * ## 失败处理
     *
     * 设备可能没有装任何浏览器（罕见但存在），或默认浏览器被禁用。
     * 这种情况 `startActivity` 抛 `ActivityNotFoundException`，
     * 必须捕获后给出可行动的提示，而不是让应用崩掉或静默无反应。
     */
    private fun openInBrowser(state: LauncherState) {
        // 永远用 state.url —— 它是 dsh **最后一次**打印的地址，也就是当前存活
        // 那个进程的地址。曾出现过旧引擎占着 3080、新进程改用 3081 的情况。
        val u = state.url
        if (u == null) {
            ui.log("❌ 还没有可访问的地址（请先启动实例）")
            return
        }

        // ★ 复制地址 → 只开浏览器 → 由用户自己粘贴 ★
        //
        // ## 为什么不再用 ACTION_VIEW 直接把地址塞给浏览器
        //
        // 实测（真机反馈）有三条硬结论：
        // 1. 首次进入常报 `authentication required`；
        // 2. **刷新无效** —— 刷新的是已跳转到 `/` 的地址，不再带 `?token=`；
        // 3. **重进一次就能正常加载**。
        //
        // 组合起来说明：浏览器**没有持久化会话 Cookie**，每次能用的加载都要靠
        // token 握手完成。而 `ACTION_VIEW` 的行为完全由系统与浏览器决定 ——
        // 它可能在冷启动时抢先发起请求（此时前端几十个 client.js 还没就绪），
        // 也可能复用已存在的标签页（那个标签页早已跳到 `/`，token 没了）。
        // 这两种都会落进 401，而应用侧无法干预。
        //
        // 改成「复制 + 空开浏览器」后，**发起请求的时机由用户掌握**：
        // 粘贴时浏览器已经热了、地址是完整的 token 地址，一次就能成。
        // 代价是用户要按一次粘贴，换来的是确定性。
        val copied = copyUrlToClipboard(u)
        launchBrowserForPaste(u, copied)
    }

    /**
     * 把完整地址写进剪贴板。
     *
     * ## 为什么要标成 sensitive
     *
     * 地址里带 dsh 的一次性访问令牌。Android 13+ 会在系统弹出「已复制」气泡里
     * 预览剪贴板内容，标记为 sensitive 后不显示预览，避免令牌被别人瞄到。
     *
     * 失败不阻断流程：此时退化为「直接带地址打开浏览器」，用户仍能用。
     */
    private fun copyUrlToClipboard(url: String): Boolean = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("dsh 地址", url)
        // setExtras 是 API 24+，EXTRA_IS_SENSITIVE 是 API 33+；
        // TIRAMISU 的判断同时覆盖两者。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        // 必须用显式 setter，不能写 `cm.primaryClip = clip`。
        //
        // 分析器会把 getPrimaryClip/setPrimaryClip 当成一对可变属性、提示
        // "Use property access syntax"（本文件 LogPanel 里那条既有警告就是它），
        // 但**编译器**在这个 SDK 上把 `primaryClip` 解析成只读属性，
        // 于是报 `'val' cannot be reassigned`。以编译器为准。
        cm.setPrimaryClip(clip)
        true
    }.getOrElse { e ->
        ui.log("⚠️ 复制地址失败: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    /**
     * 只打开浏览器**本身**（不带任何网址），让用户自己粘贴。
     *
     * ## 为什么用 makeMainSelectorActivity
     *
     * `ACTION_MAIN` + `CATEGORY_APP_BROWSER` 是系统约定的「打开默认浏览器主界面」
     * 入口：它不触发任何导航、不新建带地址的标签页，正好是我们要的「干净打开」。
     *
     * 直接 `ACTION_VIEW` 一个 `about:blank` 之类的占位地址不行 —— 那仍会产生一次
     * 导航，有些浏览器还会把它当成新标签页的开端，反而更乱。
     *
     * @return 是否成功拉起浏览器；false 时调用方退化为带地址打开。
     */
    private fun launchBrowserOnly(): Boolean = runCatching {
        val intent = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            Intent.CATEGORY_APP_BROWSER
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 复制后打开浏览器；浏览器拉不起来时退回「直接带地址打开」。
     *
     * 记录脱敏地址到日志：与 dsh 打印的 `dsh web:` 行对照，
     * 就能确认端口/token 是否来自当前存活的进程。
     */
    private fun launchBrowserForPaste(url: String, copied: Boolean) {
        ui.log("🌐 地址：${redactUrl(url)}")

        if (launchBrowserOnly()) {
            ui.log("🌐 已打开浏览器")
            if (copied) {
                ui.log("   ✔ 地址已复制到剪贴板，请在浏览器地址栏粘贴访问")
                hint("地址已复制，请在浏览器中粘贴")
            } else {
                // 复制失败但浏览器能开：只能让用户手抄，所以直接把地址打全
                ui.log("   ⚠️ 复制失败，请手动输入下面的地址：")
                ui.log("   $url")
                hint("复制失败，请手动输入地址")
            }
            // 首次粘贴仍可能失败（浏览器冷启动、前端资源没就绪）。
            // 这条提示给的是那种情况下的正确动作 —— 重新复制粘贴，而不是刷新。
            ui.log("   若粘贴后仍提示 authentication required：")
            ui.log("   不要刷新（刷新不带 token），回到这里再点一次本按钮重新取地址")
            return
        }

        // 拉不起浏览器（没装 / 被禁用 / 无默认浏览器）：退化为 ACTION_VIEW
        ui.log("ℹ️ 无法直接打开浏览器主界面，改为带地址打开")
        val ok = runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (ok) {
            ui.log("🌐 已用浏览器打开该地址")
        } else {
            ui.log("❌ 没有可用的浏览器（或默认浏览器被禁用）")
            ui.log("   请在系统设置里安装/启用一个浏览器后重试")
            if (copied) {
                ui.log("   地址已复制到剪贴板，可粘贴到任意浏览器访问")
                hint("未找到浏览器，地址已复制")
            } else {
                hint("未找到可用的浏览器")
            }
        }
    }

    /**
     * 启动 / 停止 的统一入口。
     *
     * 启动分两步，各自负责一件事：
     * 1. **内置容器**（Node + bash + 依赖库）：随 APK 分发，首次启动自动解压；
     * 2. **dsh 引擎**：后天下载，可单独升级而不必重装 APK。
     *
     * 不再让用户手动挑 runtime.zip —— 容器是固定基础，手动选文件既多余
     * 又容易选错版本。
     */
    private fun toggleRun(state: LauncherState) {
        if (state.runState.isAlive) {
            stopInstance(state)
            return
        }

        // 防连点：启动中再点会重复起线程
        if (state.runState == RunState.STARTING) return
        startInstance()
    }

    /**
     * 停止 Node 进程。
     *
     * ## 为什么不能"直接 finish 网页窗口"了事
     *
     * 早期实现是 `stopProcess()` + `AppState.closeWebWindow()` 就完事。
     * 但真机反馈：点「停止运行」有时会让**整个应用退出**，而不是只停 dsh。
     *
     * 原因：Node（约 200MB）+ WebView 都很吃内存，网页窗口在前台时，
     * 系统很容易把后台的启动器回收掉。此时把网页窗口 finish 掉，
     * 任务栈里就一个 Activity 都不剩 —— 用户看到的就是"应用没了"。
     *
     * 所以这里做三件事：
     * 1. 停止进程放到**后台线程**，避免 destroy 偶尔阻塞卡住 UI；
     * 2. 关掉网页窗口后，显式把启动器**拉回前台**（REORDER_TO_FRONT）；
     * 3. 状态复位统一在最后做，保证 UI 立刻显示"已停止"。
     */
    private fun stopInstance(state: LauncherState) {
        state.log("⏹ 正在停止 Node…")
        Thread {
            DSHRunner.stopProcess()
            runOnUiThread {
                // 网页窗口里的页面已经没有后端了，关掉它
                AppState.closeWebWindow()
                state.reset()
                // 关键：保证启动器一定在前台。若它曾被系统回收，
                // 这一步会把任务栈重新填上，而不是留下一个空任务。
                bringLauncherToFront()
            }
        }.start()
    }

    /**
     * 重启：先停，等端口释放后再起。
     *
     * 中间那 600ms 不是凑数 —— Node 退出后监听端口需要一点时间
     * 才会真正释放，立刻重启有可能撞上 EADDRINUSE，
     * 表现就是"重启后页面打不开"。
     */
    private fun restartInstance(state: LauncherState) {
        state.log("🔄 正在重启 Node…")
        Thread {
            DSHRunner.stopProcess()
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {
                // 被打断就直接继续，不必等满
            }
            runOnUiThread {
                AppState.closeWebWindow()
                state.reset()
                // 刻意**不**调 bringLauncherToFront()：重启时启动器本来就在前台，
                // 再发一次 REORDER_TO_FRONT 的自启动 Intent 会在部分 ROM 上
                // 触发任务栈重建，用户看到的就是「点重启，应用退出了」。
                startInstance()
            }
        }.start()
    }

    /**
     * 实例设置被改动时的统一处理：**先把正在跑的 dsh 停掉**。
     *
     * ## 为什么必须停
     *
     * 数据目录（`DSH_HOME`）、引擎版本、权限模式这些都是**起进程时一次性读进环境变量**
     * 的。运行中改设置，进程里用的还是旧值：
     * - 开了/关了版本隔离 → 数据目录不变，看起来「开关没反应」；
     * - 反过来，还可能在两套数据目录之间写出不一致的状态。
     *
     * 上一版没有任何停止逻辑，用户关掉隔离后一切照旧，只能靠猜。
     * 现在改成显式停一次，并在日志里说清楚「新设置将在下次启动时生效」。
     *
     * 实例本来就没在跑时只写一行日志，不做多余动作。
     */
    private fun stopForInstanceSettings(reason: String) {
        if (!ui.runState.isAlive) {
            ui.log("⚙️ $reason")
            ui.log("   实例当前未运行，下次启动即按新设置生效")
            return
        }
        ui.log("⚙️ $reason")
        ui.log("   设置只在启动时读取，正在停止当前实例…")
        // 直接复用「停止运行」的既有链路：它会关掉网页窗口、把启动器拉回前台、
        // 复位运行状态，避免出现「进程没了但按钮还显示运行中」。
        stopInstance(ui)
    }

    /**
     * 把启动器拉到前台。
     *
     * 用 REORDER_TO_FRONT 而不是 CLEAR_TOP：后者会把栈上的
     * 网页窗口销毁，而这里已经显式 finish 过了，不需要重复。
     * 若启动器本来就在前台，这个调用是无害的（只是重新排序）。
     */
    private fun bringLauncherToFront() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }

    private fun startInstance() {
        ui.runState = RunState.STARTING
        ui.log("⏳ 准备运行时…")
        Thread {
            // 1) 内置容器
            if (!DSHRunner.ensureRuntime(this)) {
                runOnUiThread {
                    ui.runState = RunState.ERROR
                    ui.log("❌ 内置容器不可用，无法启动")
                }
                return@Thread
            }

            // 2) dsh 引擎（后天下载）
            if (!DSHRunner.dshInstalled(this)) {
                runOnUiThread {
                    ui.runState = RunState.ERROR
                    ui.log("❌ 尚未安装 dsh 引擎")
                    ui.log("❌ 请在「下载中心」下载引擎后重试")
                    hint("请先下载 dsh 引擎")
                }
                return@Thread
            }

            DSHRunner.startNode(this)
        }.start()
    }
}
