package com.example.fcldsh

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 版本显示名（用户起的标签）—— **纯 UI 层，不碰任何磁盘目录名**。
 *
 * ## 为什么单独存一层，而不是改目录名
 *
 * 版本目录名（`usr/lib/versions/<版本键>`）不只是个文件夹名，它是**身份标识**：
 *
 * - `DSH_HOME` 是 `home/.dsh-<版本>`，跟着版本键走；
 * - 实例元数据里记着它，切实例就是切这个键；
 * - dsh 自己会把它读进会话与配置里。
 *
 * 一旦把它改成用户输入的名字（比如「我的测试环境」），上面每一处都会对不上 ——
 * 会话挂载失败、数据目录错位、切换实例切不过去。这个坑项目里已经踩过一次
 * （参考 [com.example.fcldsh.Instances] 关于「实例标识只有名称」的教训）。
 *
 * 所以显示名做成**一层旁挂的标签**：键仍然是版本键，值才是给人看的名字。
 * 删掉标签不影响任何东西，只退回显示版本号。
 *
 * ## 存在哪
 *
 * 独立 SharedPreferences（`version_labels`），与 [LauncherPrefs] 分开：
 * 那些是全局偏好，这里是一张「版本键 → 标签」的映射表，生命周期与语义都不同。
 *
 * ## 去重
 *
 * **不做**。标签只是显示用的，两个版本叫同一个名字不会造成任何功能问题
 * （目录名仍然唯一）。强行加后缀反而让用户莫名其妙 —— 他明明起的是这个名字。
 */
object VersionLabels {

    private const val FILE = "version_labels"

    /**
     * 修订号：标签表每变一次就 +1。
     *
     * ## 为什么需要它
     *
     * 标签存在 SharedPreferences 里，而 [displayName] 是个**普通函数** ——
     * Compose 不会因为它背后的文件变了而重组。于是「装完整合包、名字写进去了，
     * 界面上还是旧名字」这种情况会**持续到下一次碰巧重组**（切页签、返回等），
     * 用户看到的就是「确认名称后没生效」。
     *
     * 解决办法是把修订号做成快照状态：[label] 读它，任何显示名字的地方
     * 就自动订阅了它；[set] 改标签时 +1，所有显示点立刻重组。
     * 比在每处调用点各自维护 `tick++` 可靠得多（那种写法漏一处就不刷新）。
     */
    var revision by mutableStateOf(0)
        private set

    /**
     * 标签长度上限。
     *
     * 40 是「手机上一行放得下、又够描述」的折中。版本管理小窗的标题只有一行
     * 且会省略号截断，太长的话用户自己都分不清两个长名字的区别。
     */
    const val MAX_LEN = 40

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 规范化用户输入。
     *
     * - 换行/制表符一律压成单个空格：标签是单行显示，带换行会让布局跳一下；
     * - 首尾空白去掉；
     * - 截到 [MAX_LEN]。
     *
     * 不做其它清洗 —— 中文、emoji、标点都该原样显示。
     */
    fun normalize(raw: String): String =
        raw.replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
            .take(MAX_LEN)

    /** 读某版本的标签；没设过返回 null。 */
    fun label(context: Context, versionKey: String): String? {
        // 触碰快照状态（见 [revision]）：组合期间读到这里，标签一变就会重组。
        revision.let { }
        if (versionKey.isEmpty()) return null
        val v = runCatching { prefs(context).getString(key(versionKey), null) }.getOrNull()
        return v?.takeIf { it.isNotBlank() }
    }

    /**
     * 写标签。传 null 或空串表示**清除**（退回显示版本号）。
     *
     * @return 实际存下的值；null 表示已清除
     */
    fun set(context: Context, versionKey: String, label: String?): String? {
        if (versionKey.isEmpty()) return null
        val clean = label?.let { normalize(it) }?.takeIf { it.isNotEmpty() }
        prefs(context).edit().apply {
            if (clean == null) remove(key(versionKey)) else putString(key(versionKey), clean)
        }.apply()
        // 通知所有显示名字的地方重组（见 [revision]）
        revision++
        return clean
    }

    /**
     * 界面上该显示什么：有标签用标签，否则用版本键本身。
     *
     * **所有展示版本名的地方都该走这里**，不要直接拼版本键，
     * 否则会出现「版本管理里叫『测试环境』、主页卡片上却还是 0.2.1-rc.4-2」。
     */
    fun displayName(context: Context, versionKey: String): String =
        label(context, versionKey) ?: versionKey

    /**
     * 从压缩包文件名推出默认标签。
     *
     * 安装整合包/本地引擎包时用：用户手上那个文件通常已经叫了有意义的名字
     * （`我的整合包.zip`），直接拿来当默认名比让他重打一遍合理。
     *
     * @return 去掉扩展名的名字；推不出来返回 null（调用方回落到版本号）
     */
    fun fromFileName(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        // 去掉可能的路径前缀（SAF 一般只给纯文件名，但没有保证）
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        // 依次剥掉常见的双扩展名与单扩展名
        val stripped = base
            .removeSuffix(".tar.gz")
            .removeSuffix(".tgz")
            .removeSuffix(".zip")
            .removeSuffix(".tar")
        return normalize(stripped).takeIf { it.isNotEmpty() }
    }

    /**
     * 给「新装一个版本」算默认名。
     *
     * 直接用版本键 —— 它已经带了自动去重后缀（同版本装第二次是 `0.2.1-rc.4-2`），
     * 所以默认名天然不会和已有实例撞名，用户什么都不改也能直接确认。
     */
    fun defaultFor(versionKey: String): String = versionKey

    /** 键前缀。加前缀是为了让偏好文件里一眼能看出这是标签表，不与其它键混淆。 */
    private fun key(versionKey: String) = "label_$versionKey"
}
