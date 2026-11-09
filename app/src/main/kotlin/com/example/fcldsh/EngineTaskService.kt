package com.example.fcldsh

import com.DSHRunner
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * 引擎「下载 / 安装 / 导出」期间的保活服务。
 *
 * ## 为什么必须有它
 *
 * npm 安装是几万个 HTTP 小请求 + 大量磁盘写入，耗时从几十秒到十几分钟不等。
 * 这段时间里用户很可能切走或熄屏，而 Android 对后台应用有两道限制：
 *
 * 1. **Doze / App Standby**：熄屏一段时间后 CPU 与网络都被冻结，
 *    npm 的请求全部超时，表现为进度条停住不动；
 * 2. **后台进程回收**：启动器退到后台后，这个进程随时可能被杀，
 *    `Thread` 直接消失，留下一堆半截文件（staging 目录）和一个永远
 *    停在"正在安装"的界面。
 *
 * 前台服务同时解决这两点：前台服务让系统知道"这个进程正在干用户可见的活"，
 * 不会轻易回收；partial WakeLock 让熄屏后 CPU 继续跑；WifiLock
 * 让屏幕关闭时 Wi-Fi 不被省电策略降速。
 *
 * ## 为什么自己轮询任务状态
 *
 * 三个任务源（[EngineInstaller]、[DshEngineDownloader]、[EngineExporter]）
 * 都是单例 object，各自持有 `busy` 标志。服务每秒读一次它们的
 * [isBusy] 与文案来刷新通知，**全部空闲两拍后自动 stopSelf** ——
 * 这样调用方不需要（也不能）可靠地手动 stop：多个任务可能并发，
 * 谁先结束就 stop 会把另一条任务的后台保护提前收掉。
 */
class EngineTaskService : Service() {

    companion object {
        private const val CHANNEL_ID = "engine-task"
        private const val NOTIF_ID = 0x4455
        private const val EXTRA_LABEL = "label"

        /** 通知刷新间隔。1 秒对下载进度足够，也不至于太费电。 */
        private const val TICK_MS = 1000L

        /** 启动后的宽限期：调用方可能在 start() 之后才把 busy 置真。 */
        private const val GRACE_TICKS = 3
        private const val IDLE_TICKS_TO_STOP = 2

        /** WakeLock 超时：兜底防止异常路径下永久持锁（正常路径会随服务销毁释放）。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L

        /**
         * 开始保活。由下载/安装/导出的入口调用。
         *
         * 重复调用是安全且必要的：从后台再次 startForegroundService 到同一个
         * 已运行的服务，只会再走一次 onStartCommand，不会出现两个通知。
         */
        fun start(context: Context, label: String) {
            val ctx = context.applicationContext
            val intent = Intent(ctx, EngineTaskService::class.java)
                .putExtra(EXTRA_LABEL, label)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure {
                // 保活失败不影响任务本身，只是熄屏后可能被冻结，所以只记日志
                DSHRunner.onLog("⚠️ 后台保活不可用: ${it.message ?: it.javaClass.simpleName}")
            }
        }

        /** 强制结束保活。当前没有调用方使用，保留给「用户主动取消并清场」的场景。 */
        fun stop(context: Context) {
            val ctx = context.applicationContext
            runCatching { ctx.stopService(Intent(ctx, EngineTaskService::class.java)) }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var ticks = 0
    private var idleTicks = 0

    /** 通知标题文案（"正在下载 dsh 引擎"之类），只用于第一帧。 */
    private var label: String = "正在处理引擎任务"

    private val ticker = object : Runnable {
        override fun run() {
            val text = currentTaskText()
            if (text != null) {
                idleTicks = 0
                updateNotification(text)
                // 任务仍在跑：WakeLock 若因超时释放过，续上
                if (wakeLock?.isHeld != true) acquireLocks()
            } else {
                ticks++
                if (ticks > GRACE_TICKS) {
                    idleTicks++
                    if (idleTicks >= IDLE_TICKS_TO_STOP) {
                        DSHRunner.onLog("⏹ 引擎任务已结束，释放后台保活")
                        stopSelf()
                        return
                    }
                }
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireLocks()
        DSHRunner.onLog("🔒 已开启后台保活（前台服务 + WakeLock）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LABEL)?.let { if (it.isNotBlank()) label = it }

        val text = currentTaskText() ?: label
        startInForeground(text)

        main.removeCallbacks(ticker)
        main.postDelayed(ticker, TICK_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        releaseLocks()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "引擎下载与安装",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "dsh 引擎下载/安装期间保持后台运行"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle(label)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_rail_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        // 点通知回到启动器（不在则新建），符合"长任务可查看进度"的预期
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            open,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.setContentIntent(pi)
        return builder.build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    // ------------------------------------------------------------------
    // 锁
    // ------------------------------------------------------------------

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fcl-dsh:engine-task")
                wl.setReferenceCounted(false)
                wl.acquire(WAKE_LOCK_TIMEOUT_MS)
                wakeLock = wl
            }.onFailure {
                DSHRunner.onLog("⚠️ WakeLock 申请失败: ${it.message ?: it.javaClass.simpleName}")
            }
        }
        if (wifiLock?.isHeld != true) {
            runCatching {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                // HIGH_PERF：熄屏时也保持 Wi-Fi 全速，避免系统省电策略把下载限成涓流。
                //
                // 它在 API 29 起有官方推荐的等价物：`WIFI_MODE_FULL_LOW_LATENCY`。
                // 新常量语义更准（低延迟模式，专为前台长任务设计），
                // 老系统上没有它，只能退回 HIGH_PERF —— 所以必须按版本分支。
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                // createWifiLock 在较新的 SDK 上被整体标记过时
                //（Google 正在把「Wi-Fi 电源锁」归入 legacy 电源管理）。
                // 目前没有等价替代：熄屏后不让 Wi-Fi 降速仍然只能靠它。
                //
                // ⚠️ 压制必须写成 `run { @Suppress(...) 调用 }`：判定只认
                // 「注解直接贴住调用表达式」这一种位置，写在 val 行首、
                // 语句上方或整个函数上都实测无效。
                val wf = run { @Suppress("DEPRECATION") wm.createWifiLock(mode, "fcl-dsh:engine-task") }
                wf.setReferenceCounted(false)
                wf.acquire()
                wifiLock = wf
            }.onFailure {
                // 没有 Wi-Fi 或系统不允许时忽略：WakeLock 已经能保证 CPU 不被冻结
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    // ------------------------------------------------------------------
    // 任务状态
    // ------------------------------------------------------------------

    /**
     * 当前正在跑的任务说明；三个来源都空闲时返回 null。
     *
     * 顺序按"最需要显示细节"排：安装 > 下载 > 导出。
     * 同时只可能有一个真正在跑（三者共用 busy 语义），这里只是防御性判断。
     */
    private fun currentTaskText(): String? {
        if (EngineInstaller.isBusy()) {
            return EngineInstaller.installMessage.ifBlank { "正在安装引擎…" }
        }
        if (DshEngineDownloader.isBusy()) {
            return DshEngineDownloader.message.ifBlank { "正在下载引擎包…" }
        }
        if (EngineExporter.isBusy()) {
            return EngineExporter.message.ifBlank { "正在打包引擎…" }
        }
        return null
    }
}
