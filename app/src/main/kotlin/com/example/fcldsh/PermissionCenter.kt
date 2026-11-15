package com.example.fcldsh

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings

/**
 * 权限中心：检查并引导用户开启 dsh 运行所需的各项系统权限。
 *
 * ## 为什么单独抽出来
 *
 * 这些权限大多是**特殊权限**，不是普通的运行时权限弹窗能解决的：
 * 「所有文件访问」要去系统的「特殊应用权限」页、「无障碍」要去无障碍页、
 * 「忽略电池优化」要发一个专门的 Intent。检查方式也各不相同
 * （`Environment.isExternalStorageManager()`、`Settings.Secure`、
 * `checkSelfPermission`…）。集中在一处，UI 只负责展示。
 *
 * ## 状态判定要诚实
 *
 * 每一项都返回真实的系统状态，而不是「点过就算开启」。
 * 用户从系统设置返回后重新读一次，状态立刻更新。
 * 读不到（例如 API 低于某版本）时返回 [Status.UNSUPPORTED] 而不是假装失败。
 */
object PermissionCenter {

    enum class Status {
        GRANTED,
        DENIED,
        /** 当前系统版本没有这个概念（如 Android 10 以下没有「所有文件访问」）。 */
        UNSUPPORTED
    }

    /**
     * 一条权限项。
     *
     * @param key 稳定标识，用于 Compose 的 key
     * @param title 标题
     * @param desc 说明，讲清「开了能做什么」
     * @param status 当前真实状态
     * @param statusText 状态文案（已授权 / 未开启 / 当前系统不支持）
     * @param actionText 右侧操作按钮文案；null 表示无需操作
     * @param open 点击后跳转的意图；null 表示无需操作
     */
    data class Entry(
        val key: String,
        val title: String,
        val desc: String,
        val status: Status,
        val statusText: String,
        val actionText: String?,
        val open: Intent?
    )

    /**
     * 收集所有权限项及其当前状态。
     *
     * 不含麦克风：本启动器不做语音输入，声明 RECORD_AUDIO 只会让用户
     * 在授权列表里看到一个用不到的项，增加理解成本。
     */
    fun entries(context: Context): List<Entry> = listOf(
        allFilesAccess(context),
        notifications(context),
        batteryOptimization(context),
        accessibility(context),
        overlay(context)
    )

    // ------------------------------------------------------------------
    // 1. 所有文件访问（MANAGE_EXTERNAL_STORAGE，Android 11+）
    // ------------------------------------------------------------------

    private fun allFilesAccess(context: Context): Entry {
        val title = "所有文件访问"
        val desc = "授权后 AI 可在共享存储（如 Download/Documents）建立与读写文件"

        // 这个权限是 Android 11 引入的。低版本用普通存储权限即可，
        // 但本项目没声明 READ/WRITE_EXTERNAL_STORAGE，所以低版本直接标为不支持。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Entry(
                key = "all_files",
                title = title,
                desc = desc,
                status = Status.UNSUPPORTED,
                statusText = "系统版本不支持",
                actionText = null,
                open = null
            )
        }

        val granted = Environment.isExternalStorageManager()
        return Entry(
            key = "all_files",
            title = title,
            desc = desc,
            status = if (granted) Status.GRANTED else Status.DENIED,
            statusText = if (granted) "已授权" else "未授权",
            actionText = if (granted) null else "去授权",
            open = if (granted) null else Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).also {
                // 少数 ROM 没有这个 App 专属页面，加包名会抛 ActivityNotFound，
                // 调用方会退化到全局页面（见 openSafely）
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    // ------------------------------------------------------------------
    // 2. 通知权限（Android 13+ 是运行时权限）
    // ------------------------------------------------------------------

    private fun notifications(context: Context): Entry {
        val title = "通知权限"
        val desc = "任务完成时推送提醒（Android 13 及以上需要授权）"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // 13 以下通知默认允许
            return Entry(
                key = "notifications",
                title = title,
                desc = desc,
                status = Status.GRANTED,
                statusText = "默认允许",
                actionText = null,
                open = null
            )
        }

        val granted = context.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        return Entry(
            key = "notifications",
            title = title,
            desc = desc,
            status = if (granted) Status.GRANTED else Status.DENIED,
            statusText = if (granted) "已授权" else "未授权",
            actionText = if (granted) null else "去授权",
            open = if (granted) null else appDetailsIntent(context)
        )
    }

    // ------------------------------------------------------------------
    // 3. 忽略电池优化
    // ------------------------------------------------------------------

    private fun batteryOptimization(context: Context): Entry {
        val title = "忽略电池优化"
        val desc = "避免后台 Node 进程被系统清理，长时间任务不中断"

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

        if (ignoring) {
            return Entry(
                key = "battery",
                title = title,
                desc = desc,
                status = Status.GRANTED,
                statusText = "已加入白名单",
                actionText = null,
                open = null
            )
        }

        // 优先用 App 专属申请页；部分 ROM 只支持全局列表页，
        // 调用方在 openSafely 里做退化处理。
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return Entry(
            key = "battery",
            title = title,
            desc = desc,
            status = Status.DENIED,
            statusText = "未加入白名单",
            actionText = "去设置",
            open = intent
        )
    }

    // ------------------------------------------------------------------
    // 5. 无障碍服务
    // ------------------------------------------------------------------

    /**
     * 无障碍服务状态。
     *
     * 本应用**有**自己的 AccessibilityService（[DshAccessibilityService]），
     * 提供读屏与模拟点击两项能力。所以这里的判定必须是「**我们这个**服务
     * 有没有被开启」，见 [isAccessibilityServiceEnabled] ——
     * 只看「系统里有没有任何服务在跑」会把输入法、悬浮球之类算成已授权，
     * 于是界面显示「已开启」而实际调读屏永远拿到空结果。
     *
     * 用户仍需到「系统设置 → 无障碍」手动开启：应用无法自行启用无障碍服务，
     * 这是系统的设计，任何引导都只能停在这一步。
     */
    private fun accessibility(context: Context): Entry {
        val title = "无障碍服务"
        val desc = "授权后 AI 可读取屏幕内容并模拟点击、长按" +
            "（用于替你操作界面；需在系统设置里手动开启）"

        val enabled = isAccessibilityServiceEnabled(context)
        return Entry(
            key = "accessibility",
            title = title,
            desc = desc,
            status = if (enabled) Status.GRANTED else Status.DENIED,
            statusText = if (enabled) "已开启" else "未开启",
            // 已开启时仍保留入口：用户可能需要进去关掉它，给个直达链接不算多余
            actionText = if (enabled) "已开启" else "去开启",
            open = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * 本应用的无障碍服务是否已被用户开启。
     *
     * ## 为什么不能只看「有没有任何服务在跑」
     *
     * `ENABLED_ACCESSIBILITY_SERVICES` 是所有应用的无障碍服务清单，
     * 里面常有输入法、悬浮球、语音助手等一大堆。只要非空就认为「已授权」，
     * 会让用户在**我们自己的服务根本没开**时看到「已开启」—— 那是最坏的误导：
     * 点读屏永远返回空，而界面说一切正常。
     *
     * 所以按 `包名/类名` 精确比对 ComponentName 的扁平化字符串，
     * 两种合法写法（`pkg/.Cls` 与 `pkg/pkg.Cls`）都要认，比较时忽略大小写。
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, DshAccessibilityService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (flat.isBlank()) return false

        val candidates = setOf(
            expected.flattenToString(),
            expected.flattenToShortString()
        )
        return flat.split(':').any { item ->
            val trimmed = item.trim()
            candidates.any { it.equals(trimmed, ignoreCase = true) }
        }
    }

    // ------------------------------------------------------------------
    // 6. 显示在其他应用上层（SYSTEM_ALERT_WINDOW，即悬浮窗权限）
    // ------------------------------------------------------------------

    /**
     * 「任务悬浮窗」所需的系统权限。
     *
     * ## 这是特殊权限，不是运行时权限
     *
     * `SYSTEM_ALERT_WINDOW` **没有**运行时申请通道：`requestPermissions()`
     * 对它无效，系统只会给出 `PERMISSION_DENIED` 且不弹框。唯一的途径
     * 是把用户送到 `ACTION_MANAGE_OVERLAY_PERMISSION` 页面自己拨开关，
     * 所以这里和「所有文件访问」「电池优化」一样只做引导。
     *
     * 状态用 [TaskOverlay.hasPermission] 判定 —— 那里已经把
     * `Settings.canDrawOverlays` 包在 runCatching 里，个别 ROM 上丢异常
     * 也不会让设置页打不开。
     *
     * 文案刻意说明「开了能看到什么」：这一项的开关在应用内（权限设置页
     * 的任务悬浮窗一行），而这里只负责系统那一层授权。用户很容易把
     * 「系统允许画浮窗」和「本次要显示浮窗」混为一谈。
     */
    private fun overlay(context: Context): Entry {
        val title = "显示在其他应用上层"
        val desc = "「任务悬浮窗」需要它：dsh 运行期间退到后台时，在屏幕上留一块小卡片，" +
            "显示 agent 的状态（暂无输出 / 正在思考 / 调用工具）"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // minSdk 是 23，实际走不到；留着是为了以后调低 minSdk 时不出错。
            return Entry(
                key = "overlay",
                title = title,
                desc = desc,
                status = Status.UNSUPPORTED,
                statusText = "系统版本不支持",
                actionText = null,
                open = null
            )
        }

        val granted = TaskOverlay.hasPermission(context)
        return Entry(
            key = "overlay",
            title = title,
            desc = desc,
            status = if (granted) Status.GRANTED else Status.DENIED,
            statusText = if (granted) "已授权" else "未授权",
            actionText = if (granted) null else "去授权",
            open = if (granted) null else Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 本应用的系统详情页（用于授权运行时权限）。 */
    private fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 安全地打开一个系统设置页。
     *
     * 不同 ROM（尤其国产定制）经常砍掉某些设置页，直接 startActivity
     * 会抛 ActivityNotFoundException。这里按「原意图 → 应用详情页」
     * 顺序退化，保证点了一定有反应，而不是静默失败。
     *
     * @return 是否成功打开了某个页面。
     */
    fun openSafely(activity: Activity, intent: Intent?): Boolean {
        if (intent == null) return false
        if (runCatching { activity.startActivity(intent) }.isSuccess) return true

        // 退化 1：所有文件访问专属页失败时，改用全局「所有文件访问」列表页
        val fallbackAllFiles = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) {
            if (runCatching { activity.startActivity(fallbackAllFiles) }.isSuccess) return true
        }

        // 退化 2：电池优化专属页失败时，改用全局电池优化列表页
        if (intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            val fallbackBattery = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { activity.startActivity(fallbackBattery) }.isSuccess) return true
        }

        // 退化 3：悬浮窗专属页失败时，改用不带包名的同一个 action。
        //
        // 部分 ROM 缺少「按包名直达」的页面，但保留了这个 action 的全局列表
        // （系统里叫「显示在其他应用上层」/「特殊应用权限」）。
        // 不带 data 时系统会落到列表页，用户仍能自己找到本应用。
        if (intent.action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
            val fallbackOverlay = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { activity.startActivity(fallbackOverlay) }.isSuccess) return true
        }

        // 退化 4：都失败就去本应用详情页，至少那里能看到权限入口
        return runCatching {
            activity.startActivity(appDetailsIntent(activity))
        }.isSuccess
    }
}
