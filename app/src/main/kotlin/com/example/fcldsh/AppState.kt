package com.example.fcldsh

import com.DSHRunner
import android.app.Activity
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

        DSHRunner.onLog = { msg -> main.post { launcher.log(msg) } }
        DSHRunner.onUrlReady = { url ->
            main.post {
                launcher.url = url
                launcher.runState = RunState.RUNNING
            }
        }
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
}
