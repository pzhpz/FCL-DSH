package com.example.fcldsh

import com.DSHRunner
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * 进程级共享状态。
 *
 * ## 为什么需要它
 *
 * 仿 FCL 的两窗口结构：启动器（[MainActivity]）和网页窗口（[DshWebActivity]）
 * 是两个独立 Activity —— 启动后网页会占满整个屏幕，启动器退到后台。
 * 但两者要读同一份运行状态：网页窗口要显示日志、要知道 URL；
 * 启动器要显示「运行中/已停止」、要在停止时把网页窗口关掉。
 *
 * 所以状态必须放在 Activity 之外。这里用一个 object 持有单个 [LauncherState]，
 * 两个 Activity 都通过 `AppState.launcher` 访问。
 *
 * 注意：这只是单进程内的共享，不做持久化 —— 进程被系统杀掉后
 * Node 子进程也已经随之结束，重建时从零开始是正确行为。
 */
object AppState {

    /** 唯一的启动器状态实例。 */
    val launcher = LauncherState()

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var callbacksInstalled = false

    /**
     * 当前打开的网页窗口。
     *
     * 用 WeakReference 而不是强引用：Activity 销毁后不该被这个全局对象吊住。
     * 启动器点「停止」时需要主动关掉网页窗口，否则用户会看到一个已经失效的页面。
     */
    @Volatile
    private var webActivity: WeakReference<Activity>? = null

    /**
     * 把 DSHRunner 的回调接到共享状态上。
     *
     * Runner 的回调来自后台线程，而 [LauncherState] 里的 logs 是
     * SnapshotStateList（Compose 状态），必须回到主线程再写。
     * 用 `callbacksInstalled` 保证只装一次，避免两个 Activity 互相覆盖。
     */
    fun installRunnerCallbacks() {
        if (callbacksInstalled) return
        callbacksInstalled = true

        // ★ 日志必须合并投递，不能一行一个 post ★
        //
        // 引擎启动时会连续吐出几百行（容器检查、env 回显、插件加载…）。
        // 早先是 `main.post { launcher.log(msg) }`，一行一个 Message：
        // 主线程被几百个 post 灌满，每条还要各自触发一次 SnapshotStateList
        // 写入 + 一次终端镜像，于是「进程已发起」之后界面要卡好几秒才缓过来，
        // 看起来就像启动慢。合并成「每帧最多一批」后，主线程一次处理完，
        // 顺序不变，往返次数从 N 降到 1。
        DSHRunner.onLog = { msg ->
            synchronized(logLock) {
                pendingLogs.add(msg)
                if (!flushScheduled) {
                    flushScheduled = true
                    main.post(flushLogsTask)
                }
            }
        }
        DSHRunner.onUrlReady = { url ->
            main.post {
                launcher.url = url
                launcher.runState = RunState.RUNNING
            }
        }
    }

    /** 保护 [flushScheduled] 与 [pendingLogs]：写入方来自任意后台线程。 */
    private val logLock = Any()

    private val pendingLogs = ArrayList<String>(64)

    private var flushScheduled = false

    private val flushLogsTask = Runnable { flushLogs() }

    /**
     * 把攒下的日志一次性写进 [launcher]。
     *
     * 只在一批里做一次加锁拷贝，之后在主线程顺序 `log()` —— 顺序与逐条投递
     * 完全一致，只是少了 N-1 次 Handler 往返。
     */
    private fun flushLogs() {
        val batch: List<String>
        synchronized(logLock) {
            if (pendingLogs.isEmpty()) {
                flushScheduled = false
                return
            }
            batch = ArrayList(pendingLogs)
            pendingLogs.clear()
            flushScheduled = false
        }
        batch.forEach { launcher.log(it) }
    }

    /** 网页窗口登记自己，供启动器关闭时使用。 */
    fun attachWebActivity(activity: Activity) {
        webActivity = WeakReference(activity)
    }

    fun detachWebActivity(activity: Activity) {
        if (webActivity?.get() === activity) webActivity = null
    }

    /** 启动器点「停止」时调用：把已经失效的网页窗口关掉。 */
    fun closeWebWindow() {
        webActivity?.get()?.let { act ->
            main.post { runCatching { act.finish() } }
        }
        webActivity = null
    }

    /** 网页窗口是否还开着。 */
    fun isWebWindowOpen(): Boolean = webActivity?.get()?.isFinishing == false

    // ------------------------------------------------------------------
    // 前后台判定（供任务悬浮窗使用）
    // ------------------------------------------------------------------

    /**
     * 进程内**已启动（started）**的 Activity 数量。
     *
     * ## 为什么不是布尔值
     *
     * 打开 dsh 网页窗口时，[DshWebActivity] 上来、[MainActivity] 下去，
     * 中间**有一小段时间两个都在 started 状态**，也有可能出现
     * 「A.onStop 先于 B.onStart」的顺序。用布尔值会在这段时间里
     * 判定出错误的「进后台」，于是浮窗闪一下又消失。
     * 计数则只在**归零**时才算真的进后台。
     *
     * 只在主线程读写（生命周期回调都在主线程），不需要同步。
     */
    private var startedActivities = 0

    @Volatile
    private var backgroundWatcherInstalled = false

    /**
     * 装上「应用前后台」监听，驱动 [TaskOverlay]。
     *
     * ## 为什么用 ActivityLifecycleCallbacks 而不是各 Activity 自己调
     *
     * 本项目有四个 Activity：启动器、dsh 网页窗口、下载页、配置文件编辑页。
     * 任何一个盖上来都会让启动器 onStop，但只要**还有一个**在本进程前台，
     * 应用就没有进后台，不该弹浮窗。
     *
     * 让每个 Activity 各自 +/- 计数，等于以后新增一个 Activity 就要记得再改一处，
     * 漏改的表现是「打开那个页面时浮窗莫名冒出来」。挂在 Application 上
     * 一次覆盖全部，新增页面无需任何改动。
     *
     * 注册是幂等的：多次调用只会装一次。
     */
    fun installBackgroundWatcher(context: Context) {
        if (backgroundWatcherInstalled) return
        val app = context.applicationContext as? android.app.Application ?: return
        backgroundWatcherInstalled = true

        app.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities++
                    if (startedActivities == 1) {
                        // 回到前台：收起浮窗
                        TaskOverlay.sync(activity.applicationContext, inBackground = false)
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities--
                    if (startedActivities <= 0) {
                        // 夹到 0：极少数机型上 onStop 可能重复投递，
                        // 让计数变负之后下一次 onActivityStarted 又会误判成「回前台」。
                        startedActivities = 0
                        TaskOverlay.sync(activity.applicationContext, inBackground = true)
                    }
                }

                // 其余四个回调与「前后台」无关。
                // 用 started/stopped 而不是 resumed/paused：resumed 在
                // 系统弹窗、权限框盖上来时也会让出，那不算进后台。
                override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )
    }
}
