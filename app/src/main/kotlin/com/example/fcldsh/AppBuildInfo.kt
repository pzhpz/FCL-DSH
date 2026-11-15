package com.example.fcldsh

/**
 * 应用版本的**源码唯一真源**。
 *
 * ## 为什么还要在源码里再写一遍版本号
 *
 * 正常情况下版本只该有一处：`app/module.toml` 的 `versionCode` / `versionName`，
 * 由构建系统灌进 APK 的 manifest 与 `BuildConfig`。但实测到一个**构建系统侧**的
 * 问题，它让那条链路不可信：
 *
 * | 观察 | 结果 |
 * |---|---|
 * | `module.toml` 与 `AndroidManifest.xml` 都写成 | `27` / `"2.1.0"` |
 * | 重新生成的 `BuildConfig.java` | `26` / `"2.1.2"`（上一次的旧值） |
 * | 合并后的 `AndroidManifest.xml` | **完全没有** `versionCode` / `versionName` 属性 |
 * | 全库搜索 `2.1.2` | 只在注释里命中，**磁盘上没有任何缓存文件存着它** |
 *
 * 也就是说构建系统的工程模型留在**进程内存**里，读到的版本总比源码慢一拍，
 * 删掉 `gen-build-config`、`merged-manifest`、`resources.ap_` 与 APK 全部
 * 中间产物后重建，它依然给出旧值。合并清单里没有版本属性也印证了这一点：
 * 版本不是从 manifest 来的，而是 `aapt2Link` 从那个模型里另取的。
 *
 * **唯一能刷新它的方式**：重启 CodeAssist（让它重新解析 `module.toml`），
 * 或在 IDE 的模块设置界面里改一次版本号。源码层面改不动。
 *
 * 在这种前提下，界面上的版本号若只取 `BuildConfig` 或已安装 APK 的
 * `PackageManager.versionName`，用户看到的永远是**某个历史值**，
 * 报 bug 时报的版本也是错的。
 *
 * ## 这里的约定
 *
 * - 界面上「应用版本」显示本文件的 [VERSION_NAME] —— 它随源码走，
 *   编译一次就一定是新值，不受构建缓存影响；
 * - 同时把**已安装 APK 实际写的版本**显示出来，两者不一致时一眼可见。
 *
 * ⚠️ 发版时**必须与本文件一起改** `app/module.toml` 的
 * `versionCode` / `versionName` 以及 `AndroidManifest.xml` 的
 * `android:versionCode` / `android:versionName` 三处 —— 否则「应用信息」
 * 页显示的仍是旧版本（那个值由 APK 的 manifest 决定，源码改不动它）。
 */
internal object AppBuildInfo {

    /**
     * 给人看的版本名。与 `module.toml` / `AndroidManifest.xml` 保持同步。
     *
     * ★ 改这里时那两处也要一起改，三处必须一致 ★
     * 上一版这里的注释写着「当前 2.1.0」，而常量早已是 2.2.0 ——
     * 注释比代码更容易过期，所以只写「保持一致」，不重复写具体数字。
     */
    const val VERSION_NAME = "2.3.0"

    /** 版本序号，同样与那两处保持同步。 */
    const val VERSION_CODE = 29
}
