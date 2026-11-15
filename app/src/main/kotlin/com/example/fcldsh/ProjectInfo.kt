package com.example.fcldsh

/**
 * 开源相关的项目元信息：**唯一真源**。
 *
 * ## 为什么单独一个文件，而不是散在 UI 里
 *
 * 「关于」页、许可证弹窗、README、THIRD_PARTY_LICENSES 都在讲同一批事实
 * （项目地址、协议名、版权人）。散落各处的结果是改了一处漏了另一处 ——
 * 本仓库已经在「版本号三处同步」上吃过这个亏（见 AGENT_NOTES §8）。
 * 所以这里集中一次，UI 只引用常量。
 *
 * ## 仓库地址已确定
 *
 * [REPO_URL] 指向 `pzhpz/FCL-DSH`，默认分支 `main`。
 * [repoUrlIsPlaceholder] 为 false，UI 会正常跳转。
 *
 * ## ★ 仍缺一个文件：仓库根目录还没有 LICENSE ★
 *
 * 已用 GitHub API 确认 `contents/LICENSE` 返回 **404** —— 也就是说
 * [LICENSE_URL_GITHUB] 目前是死链接。所以许可证弹窗把**主按钮**指向
 * [LICENSE_URL_UPSTREAM]（gnu.org，永远可达），仓库全文只作为次要入口。
 *
 * 补上 `LICENSE` 之后（GitHub 仓库页 → Add file → Create new file →
 * 文件名填 `LICENSE` → 右侧 Choose a license template → GPL-3.0），
 * 那个入口才会生效。**法律文本必须逐字准确，不要手工抄写或让 AI 生成。**
 */
object ProjectInfo {

    /**
     * 界面显示的应用名。
     *
     * 必须与 `res/values/strings.xml` 的 `app_name`（桌面图标下的名字）
     * 和仓库名 `FCL-DSH` 一致。早先这里写的是 `"DSH Launcher"`，与
     * `app_name` 不一致 —— 用户看到的是 `FCL-DSH`，关于页却写 `DSH Launcher`，
     * 对一个开源项目来说这种不一致会让人觉得不是同一个东西。
     */
    const val APP_DISPLAY_NAME = "FCL-DSH"

    /**
     * 仓库地址。
     *
     * 默认分支是 `main` —— [LICENSE_URL_GITHUB] / [THIRD_PARTY_URL] 都按它拼。
     * 若以后改成 `master`，这三处要一起改。
     */
    const val REPO_URL = "https://github.com/pzhpz/FCL-DSH"

    /** 是否还没替换成真实地址。UI 据此给出明确提示，而不是让人点进 404。 */
    val repoUrlIsPlaceholder: Boolean get() = REPO_URL.contains("REPLACE_ME")

    /** 协议名（给人看的）。 */
    const val LICENSE_NAME = "GNU General Public License v3.0"

    /** SPDX 标识。刻意是 `-only` 而不是 `-or-later`：只授第 3 版，行为可预期。 */
    const val LICENSE_SPDX = "GPL-3.0-only"

    /** 版权声明行。年份与姓名请按实际情况改。 */
    const val COPYRIGHT = "Copyright (C) 2025 夜ksy"

    /** 仓库里的许可证全文。 */
    val LICENSE_URL_GITHUB: String get() = "$REPO_URL/blob/main/LICENSE"

    /** 官方原文（离线也能对照，不依赖本仓库是否可达）。 */
    const val LICENSE_URL_UPSTREAM = "https://www.gnu.org/licenses/gpl-3.0.html"

    /** 第三方组件清单。 */
    val THIRD_PARTY_URL: String get() = "$REPO_URL/blob/main/THIRD_PARTY_LICENSES.md"

    /**
     * 免责声明的正文。
     *
     * 写在代码里而不是只在 README：README 只有来仓库的人会看，而 APK 会被
     * 单独分发出去 —— 「API Key 产生的费用」「数据丢失」这些后果必须随应用一起送达。
     */
    const val DISCLAIMER = "本软件按「现状」提供，不附带任何担保。" +
        "它只是一个启动器：模型输出的内容、AI 对手机的操作、以及由此产生的" +
        "API 费用与数据变动，由使用者自行判断与承担。" +
        "请勿在含有重要数据或涉及他人隐私的设备上启用「完全访问」或自动化操作。"

    /**
     * 「完全免费」声明。
     *
     * 防止有人把开源代码打包后在应用商店收费 —— 这类倒卖在同类项目里很常见
     * （FCL、PCL 都被套壳卖过），所以这句话要在关于页和 README 顶部各放一次。
     */
    const val FREE_NOTICE = "本软件完全免费且开源。如果你是通过付费渠道获得它的，" +
        "说明你买到的是别人打包的副本 —— 请立即申请退款，并提醒其他人不要上当。"
}
