package com.example.fcldsh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.DSHRunner
import java.io.File

/**
 * 屏幕截图服务（MediaProjection）。
 *
 * ## 为什么必须走 MediaProjection
 *
 * agent 实测过：`screencap` 退出码 1、写出 0 字节；`screenrecord` 报
 * `no display`；`dumpsys SurfaceFlinger` 被拒 `missing android.permission.DUMP`。
 * 根因是它跑在 **SELinux `untrusted_app` 域**里 —— 这个域既没有
 * `CAPTURE_VIDEO_OUTPUT`，也没有 `android.permission.DUMP`。
 *
 * ```
 * untrusted_app ──screencap──> 需要 CAPTURE_VIDEO_OUTPUT / screencap 可见的 display
 *                              → 拒绝（表现为 0 字节，不报错）
 * ```
 *
 * 应用进程虽然同样是 untrusted_app，但它有**一条系统留给普通应用的合法通道**：
 * `MediaProjectionManager`。代价是必须由用户在系统弹窗里点一次同意，
 * 且授权**不能持久化**（每次进程重启都要重新点）。
 *
 * 所以这里的设计是：应用负责拿授权 + 持续抓帧，把最新一帧落成 PNG，
 * 再由 [A11yBridgeServer] 的 `/screenshot` 端点把文件路径告诉 agent。
 * agent 只需读那个 PNG，不需要任何系统权限。
 *
 * ## 为什么持续抓帧而不是"要的时候截一次"
 *
 * 每次按需建 VirtualDisplay 的延迟在 200–600ms，且部分 ROM 上连续
 * create/release 会失败。持续挂着 VirtualDisplay、ImageReader 回调里
 * 只更新「最新一帧」，请求时直接返回缓存 —— 代价是常驻一个虚拟显示器
 * （内存约一帧 RGBA，1080×2400 ≈ 10MB），换取稳定与低延迟。
 *
 * ## 帧的新鲜度
 *
 * VirtualDisplay 只在**画面变化**时推帧。若屏幕静止，最后一帧就是当前画面
 * （内容相同，不存在"过期"问题）。但若用户刚操作完，可能拿到的仍是上一帧，
 * 所以响应里带 `ageMs`，agent 可据此决定要不要稍等再取。
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?) = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var width = 0
    private var height = 0
    private var densityDpi = 0

    /** 复用同一个 Bitmap 做像素拷贝，避免每帧新建（1080×2400 每帧 10MB）。 */
    private var reuseBitmap: Bitmap? = null

    private val stopCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户从系统通知栏/状态栏撤销了投屏，或被系统回收
            Log.i(TAG, "MediaProjection 已停止")
            ScreenCaptureState.markInactive()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val t = HandlerThread("screen-capture").apply { start() }
        thread = t
        handler = Handler(t.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            Log.w(TAG, "缺少授权数据，无法开始截屏")
            stopSelf()
            return START_NOT_STICKY
        }
        // 先起前台，再取 MediaProjection —— Android 14 起强制这个顺序，
        // 反过来会在 getMediaProjection 处抛 SecurityException。
        startAsForeground()
        beginProjection(resultCode, data)
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "屏幕截图",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "让 AI 能读取手机当前画面（需用户授权投屏）"
                setShowBadge(false)
            }
        )
    }

    private fun startAsForeground() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("屏幕截图已开启")
            .setContentText("AI 可读取当前画面；不需要时可在设置里停止")
            .setSmallIcon(com.example.fcldsh.R.drawable.ic_perm_shield)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // mediaProjection 类型必须显式声明，否则 Android 14 拒绝启动
            startForeground(
                NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun beginProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm?.getMediaProjection(resultCode, data) }.getOrNull()
        if (mp == null) {
            DSHRunner.onLog("❌ 获取 MediaProjection 失败（授权可能已失效）")
            stopSelf()
            return
        }
        projection = mp
        // Android 14 起要求在 createVirtualDisplay 之前注册回调
        mp.registerCallback(stopCallback, handler)

        val metrics = screenMetrics()
        width = metrics.first
        height = metrics.second
        densityDpi = metrics.third
        Log.i(TAG, "屏幕 ${width}x$height @${densityDpi}dpi")

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "dsh-screen",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        ScreenCaptureState.markActive(width, height)
        DSHRunner.onLog("📸 屏幕截图已就绪（$width×$height），agent 可调 /screenshot")
    }

    /** 屏幕真实像素尺寸。用实时 metrics 而不是 Display.getSize，能覆盖分屏/折叠屏。 */
    private fun screenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm?.currentWindowMetrics?.bounds
            val d = resources.displayMetrics.densityDpi
            if (b != null) Triple(b.width(), b.height(), d)
            else Triple(1080, 2400, d)
        } else {
            val dm = resources.displayMetrics
            @Suppress("DEPRECATION")
            val realW = dm.widthPixels
            @Suppress("DEPRECATION")
            val realH = dm.heightPixels
            Triple(realW, realH, dm.densityDpi)
        }
    }

    /** ImageReader 回调：把最新一帧落成 PNG。 */
    private fun onFrame(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val bmp = toBitmap(image)
            // 交给统一的落盘逻辑：同时写内部（shim / `cat` 用）与外部私有目录
            // （读图工具用 —— 它在解析 /data/user/0 那一层就 EACCES）。
            ScreenCaptureState.writeFrame(this, bmp)
        } catch (e: Exception) {
            Log.w(TAG, "帧处理失败: ${e.message}")
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * Image → Bitmap。
     *
     * ImageReader 给的每一行末尾可能带对齐填充（rowPadding），
     * 直接按 width×height 拷贝会整体错位、右侧出现斜切花屏。
     * 所以先按「带 padding 的宽度」建图，再裁掉右边那一条。
     */
    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        // 用 val 接一次而不是直接复用可空字段：
        // `var bmp = reuseBitmap` 在 if 里重新赋值后，Kotlin 不会为它做
        // 智能类型转换（可变局部变量 + `||` 条件），后续 bmp.xxx 会报
        // 「Only safe (?.) calls are allowed on a nullable receiver」。
        val padded: Bitmap
        val cached = reuseBitmap
        if (cached != null && cached.width == paddedWidth && cached.height == image.height) {
            padded = cached
        } else {
            padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            reuseBitmap = padded
        }
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        return if (paddedWidth == image.width) {
            padded
        } else {
            // 裁掉每行末尾的对齐填充
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        }
    }

    override fun onDestroy() {
        ScreenCaptureState.markInactive()
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.unregisterCallback(stopCallback) }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        reuseBitmap = null
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
        super.onDestroy()
    }

    /**
     * 服务状态与对外入口。
     *
     * 状态用 Compose 可变状态：设置页要显示"是否已开启"，而开关由系统弹窗
     * 决定后回调到服务里 —— 普通字段变了不会触发重组。
     */
    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "dsh_screen_capture"
        private const val NOTIF_ID = 0x5C01

        const val ACTION_STOP = "com.example.fcldsh.STOP_SCREEN_CAPTURE"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        /** 启动投屏服务（用系统弹窗返回的 resultCode + data）。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /** 停止并释放投屏。 */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ScreenCaptureService::class.java).apply {
                        action = ACTION_STOP
                    }
                )
            }
        }
    }
}

/**
 * 对外可见的运行状态与截图落盘位置。
 *
 * 必须是**顶层对象**，不能写成 [ScreenCaptureService] 的嵌套对象。
 * 嵌套时它的全名是 `ScreenCaptureService.ScreenCaptureState`，
 * 外部的 [A11yBridgeServer] 无法解析（`Unresolved reference`），
 * 而且类内部的属性初始化器（stopCallback）引用它时也会出问题。
 */
object ScreenCaptureState {

    /** 固定文件名，落在 dsh 的 HOME 下：agent 读 `~/screenshot.png` 即可。 */
    private const val FILE_NAME = "screenshot.png"

    /** 是否已授权且正在抓帧。Compose 状态，设置页订阅它显示开关状态。 */
    var active by mutableStateOf(false)
        private set

    /** 最近一次成功写帧的时刻（elapsedRealtime）。0 表示还没有帧。 */
    @Volatile
    var lastFrameAt: Long = 0L
        private set

    @Volatile
    var frameWidth: Int = 0
        private set

    @Volatile
    var frameHeight: Int = 0
        private set

    fun markActive(w: Int, h: Int) {
        active = true
        frameWidth = w
        frameHeight = h
    }

    fun markInactive() {
        active = false
    }

    fun markFrame() {
        lastFrameAt = SystemClock.elapsedRealtime()
    }

    /**
     * 记录一帧，同时更新宽高。
     *
     * 无障碍截图（AccessibilityService.takeScreenshot）拿到的位图尺寸来自实际
     * 帧，不一定等于 VirtualDisplay 建的尺寸，所以这里允许覆盖。
     */
    fun markFrame(w: Int, h: Int) {
        lastFrameAt = SystemClock.elapsedRealtime()
        if (w > 0) frameWidth = w
        if (h > 0) frameHeight = h
    }

    /** 距离上一帧过去了多久（毫秒）；还没出过帧返回 -1。 */
    fun frameAgeMs(): Long {
        val at = lastFrameAt
        if (at == 0L) return -1L
        return SystemClock.elapsedRealtime() - at
    }

    /**
     * 截图文件的内部路径（`files/home/screenshot.png`）。
     *
     * 这个目录正是 dsh 进程的 `$HOME`，所以 `screencap` 替身脚本
     * 用 `~/screenshot.png` 就能读到。
     *
     * ⚠️ 但**不要把它交给图片工具**：它的完整路径形如
     * `/data/user/0/<pkg>/files/home/screenshot.png`，而 `/data/user/0`
     * 是 `root:root 0711` —— 普通 UID 只能穿过、不能列出。
     * 图片工具在解析路径根时会对那一层做目录操作，直接 EACCES。
     * 需要给人/工具读取的副本见 [readableScreenshotFile]。
     */
    fun screenshotFile(context: Context): File =
        File(com.appHomeDir(context), FILE_NAME)

    /**
     * 截图的外部私有路径（`/storage/emulated/0/Android/data/<pkg>/files/screenshot.png`）。
     *
     * ## 为什么需要这一份
     *
     * agent 实测：读图工具每次都在 `/data/user/0` 上报
     * `EACCES: permission denied, open '/data/user/0'`，而且**在进入给定路径之前**
     * 就失败 —— 说明它是在解析路径根那一层，而不是打不开具体文件。
     * 换 `/data/data/…`、相对路径、拷到 `/sdcard` 全都报同一个错，
     * 因为它们最终都要穿过 `/data/user/0`。
     *
     * 外部私有目录是同一份数据的**另一个挂载视图**，路径里根本没有
     * `/data/user/0` 这一段，所以解析不会踩到那个 0711 目录。
     * 它同样属于应用私有（Android 11+ 其他应用也读不到）。
     *
     * 拿不到外部目录（极少见：存储未挂载）时返回 null，调用方回落内部路径。
     */
    fun externalScreenshotFile(context: Context): File? =
        runCatching {
            context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
        }.getOrNull()

    /**
     * 给 agent / 读图工具用的路径：优先外部私有目录。
     *
     * 两者都还不可用（外部存储没挂载、但内部已有一帧）时回落内部，
     * 至少 `sh` 能 `cat` 出来。
     */
    fun readableScreenshotFile(context: Context): File {
        val ext = externalScreenshotFile(context)
        if (ext != null && ext.isFile && ext.length() > 0L) return ext
        return screenshotFile(context)
    }

    /**
     * 写一帧到**两个位置**，返回可供 agent 读取的那个路径（优先外部）。
     *
     * ## 为什么要写两份
     *
     * 读的两方对路径的容忍度不同：
     * - `screencap` 替身脚本（以及用户的 `cat ~/screenshot.png`）走内部路径，
     *   那是 dsh 的 `$HOME`，最直观；
     * - 读图工具走外部路径，因为它的路径根解析在 `/data/user/0` 上会失败。
     *
     * 一份 PNG 只有几百 KB，写两次的代价远小于「图有了但打不开」。
     * 任一目标写入失败都不影响另一个，返回最后一个成功的路径。
     *
     * @return 可读路径；两个位置都写失败时返回 null（调用方据此报错）。
     */
    fun writeFrame(context: Context, bmp: Bitmap): File? {
        val targets = ArrayList<File>(2)
        targets.add(screenshotFile(context))
        externalScreenshotFile(context)?.let { targets.add(it) }

        var readable: File? = null
        var lastError: String? = null
        for (target in targets) {
            val ok = runCatching {
                target.parentFile?.mkdirs()
                // 先写 .tmp 再改名：避免 agent 读到写了一半的 PNG
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                true
            }.getOrElse { e ->
                lastError = e.message
                false
            }
            if (ok) readable = target
        }

        if (readable == null) {
            Log.w("ScreenCaptureState", "截图落盘全部失败: ${lastError ?: "unknown"}")
            return null
        }
        markFrame(bmp.width, bmp.height)
        return readable
    }
}
