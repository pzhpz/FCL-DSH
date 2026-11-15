# DSH Launcher（fcl-dsh）

把 [DeepSeek Harness](https://github.com/deepseek-ai)（下称 dsh）完整搬到 Android 上运行。
界面版式参照 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)（FCL），
运行方式是「内置 Node 容器 + 独立全屏网页窗口」，不依赖 Termux、不需要 root。

> ⚠️ **本软件完全免费且开源。**
> 如果你是通过付费渠道获得它的，说明你买到的是别人打包的副本 ——
> 请立即申请退款，并提醒其他人不要上当。仓库地址见下方「获取」。

---

## 目录

- [它是什么](#它是什么)
- [功能](#功能)
- [快速开始](#快速开始)
- [从源码构建](#从源码构建)
- [目录结构](#目录结构)
- [权限与安全（请务必读）](#权限与安全请务必读)
- [性能说明](#性能说明)
- [已知限制](#已知限制)
- [致谢](#致谢)
- [许可](#许可)
- [免责声明](#免责声明)

---

## 它是什么

dsh 本身是一个跑在 Node 里的 agent 服务，通过本地网页界面交互。这个项目做的事是：

1. 把 **Node 运行时**（Termux 用户态 + Node）随 APK 内置，首次启动自动解压到应用私有目录；
2. 用 `linker64` 绕过 Android 对私有目录的 `exec` 限制，把 dsh 引擎拉起来；
3. 适配 Android 上缺东西的部分（flock 原生绑定、node-pty、`xdg-user-dir`、OpenSSL 配置路径、
   `screencap` 替身、文件系统 EACCES 降级……）；
4. 提供启动器 UI：引擎多版本管理、实例导入导出、账号密钥、终端、文件浏览器、运行日志；
5. 提供一整套让 agent **能操作手机**的本地桥（读屏、点按、输入、截图），以及一个后台悬浮窗
   显示 agent 当前在做什么。

不修改 dsh 引擎源码。引擎在「下载中心」单独安装，多版本可共存。

## 功能

| 模块 | 说明 |
|---|---|
| 引擎多版本 | 每个版本独立数据目录（`$HOME/.dsh-<version>`），互不相通，可随时切换 |
| 内置容器 | Node 运行时随 APK 分发，首次启动解压，之后无需联网 |
| 全屏网页窗口 | 独立 Activity，沉浸式；方向（自动/横屏/竖屏）与缩放（50%–200%）可调 |
| 无障碍桥 | 本地 HTTP 服务（`127.0.0.1`，随机 token 鉴权），让 agent 读屏 / 点按 / 输入 / 截图 |
| 任务悬浮窗 | 三态显示：**暂无输出 / 正在思考 / 调用工具**（含正在执行的命令）；可拖动、可临时收起 |
| 终端 | 容器内 shell，同时镜像启动器日志（PTY 由随包分发的原生助手提供） |
| 文件浏览器 | 容器家目录可视化（`filesDir/home`），隐藏项默认折叠，核心目录不可删 |
| 实例导入导出 | 整包 zip，导出时自动剔除 `.env`、`android-account.json` 等凭据并抹除内容中的密钥 |
| 账号与密钥 | 多账号保存、选择注入；官方账号走 dsh 内置路由，第三方中转可自动补提供方配置 |
| 插件 / 会话 / MCP / 技能 | 在「实例设置」里分页管理 |

## 快速开始

1. 从 **Releases** 下载 `app-release.apk` 并安装（`minSdk 23`，Android 6.0+）。
2. 打开应用 → **下载中心** → 安装 dsh 引擎（需联网）。
3. 回到主界面 → **启动**。首次启动会解压内置容器，通常需要十几秒到半分钟。
4. 引擎就绪后会打开全屏网页窗口，在网页里正常使用 dsh。

想要 agent 能操作手机，还需要在 **设置 → 权限设置** 里：

- 开启「屏幕操作」无障碍服务（用于读屏与截图）；
- 授予「显示在其他应用上层」（任务悬浮窗需要）；
- 需要执行 bash / glob / grep 时，把「沙箱权限」切到 **完全访问**（原因见下节）。

## 从源码构建

本项目在 **Android 设备上的 CodeAssist**（设备内 IDE）里开发和构建，
不使用 Android Studio，也不跑桌面版 Gradle daemon。

- 构建配置：`app/module.toml`（`compileSdk 36` / `minSdk 23` / `targetSdk 34`）
- 构建任务：`assemble:app:release`
- 依赖：Kotlin、Jetpack Compose（Material3）、Shizuku API、XZ for Java

> **`app/src/main/assets/runtime.zip` 不在仓库里。**
> 它是 Node 运行时二进制包（体积很大），不适合放进 Git。构建前请自行放入该路径，
> 可从 Releases 或项目群文件获取。缺少它时应用能编译，但启动会报
> 「内置容器缺失（找不到 bin/node）」。

## 目录结构

```
app/
  module.toml                     构建配置与依赖
  src/main/AndroidManifest.xml    权限 / provider / service 声明
  src/main/assets/runtime.zip     Node 运行时（不入库，需自备）
  src/main/kotlin/com/
      DSHRunner.kt                引擎进程启动/停止、日志、路径推导的唯一入口
  src/main/kotlin/com/example/fcldsh/
      MainActivity.kt             启动器主界面（侧栏 + 各面板）
      DshWebActivity.kt           全屏网页窗口
      TaskOverlay.kt              任务悬浮窗（三态）
      AgentActivity.kt            三态状态机
      EngineLogTap.kt             从引擎输出里识别命令与模型输出
      A11yBridgeServer.kt         无障碍本地 HTTP 桥
      DshAccessibilityService.kt  读屏与手势
      EngineInstaller.kt          引擎下载与安装
      EngineExporter.kt           实例导出（含密钥剔除）
      …                           其余见目录
AGENT_NOTES.md                    踩坑记录与设计约束（改代码前建议先读）
```

## 权限与安全（请务必读）

### 沙箱权限：为什么默认「工作区可写」跑不了 bash

dsh 的 `workspace-write` 需要**内核级**隔离（bubblewrap 或 Landlock）。
Android 内核普遍两者都没有，此时 dsh 会**拒绝执行**命令，而不是裸跑到宿主里。
表现就是 agent 一直说 bash 不可用。

想用 bash / glob / grep，只能把权限模式切到 **完全访问**（`danger-full-access`）。
代价是：模型生成的命令会以本应用的身份**无隔离**执行，可读写应用私有目录
（含 `filesDir/usr` 运行时、`$DSH_HOME` 下的凭据与会话记录），且不再逐条询问。

缓解因素：Android 应用沙箱把影响面限制在单个应用的私有存储内，
不像桌面那样能触及整个用户目录。**但请自行判断是否接受。**

### 无障碍桥

- 只绑 `127.0.0.1`，局域网内其他设备连不上；
- 每个请求都必须带随机 token，token 写在应用私有目录的 `~/a11y-bridge.json` 里；
- 同设备上的其他应用也能连回环端口，**回环不是安全边界** —— 安全性来自 token 与私有目录；
- 读屏能读到密码框与其他应用界面，点击能代替用户操作。不用时请关闭无障碍服务。

### 运行模式

| 模式 | 说明 |
|---|---|
| 普通（默认） | 引擎以本应用身份运行 |
| Shizuku | 通过 Shizuku 执行高权限命令，但**引擎本身仍是应用身份**（Shizuku 不改子进程 uid） |
| Root | 引擎以 `uid 0` 运行，需设备已 root 并授权 su |

## 性能说明

真机（8 核 / Android）实测冷启动分段数据，**结论是瓶颈在引擎侧，不在本仓库**：

| 阶段 | 耗时 |
|---|---|
| 启动器自身准备（校验、装配 shim、建目录、查参数、起进程） | **约 100 ms** |
| 引擎自举（`pb.start()` → `dsh web:` 输出） | **11–15 s** |
| 网页窗口实例化 + 页面加载 | 约 20 ms + 1.2 s |

那 11–15 秒里，约 7–9 秒是 Node **加载并编译 `node_modules`**（V8 在主线程单线程编译，
外部改不了），其余是插件初始化与 web 服务就绪。同一台机器上多次启动会有 2–3 秒波动，
属于闪存状态与后台负载差异，不是回归。

启动过程中的诊断（引擎自检、沙箱探测、路径回显）已全部**延迟到页面显示之后再跑**，
启动路径上不做任何「只产出日志」的 I/O。

## 已知限制

- **不支持 `invokedynamic`**：本项目在 CodeAssist 的运行模型下开发，若你用它跑含大量
  动态字节码的代码，可能失败（与本应用功能无关，属开发环境限制）。
- 部分引擎版本不认 `--no-open` / `--host` / `--port`，此时会尝试打开系统浏览器并失败，
  日志里能看到 `could not open the default browser`。用内置窗口即可，不影响使用。
- 引擎版本兼容性随上游变化，建议使用「下载中心」里标注可用的版本。

## 致谢

- **[Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)** —— 界面版式参考
  （左侧竖排导航、白色圆角设置行、按下缩放反馈）。仅参考外观与交互，未使用其代码。
- **DeepSeek Harness** —— 引擎本体，本应用只是它的 Android 启动器与容器。
- **Deepseek-Harness-Local-Android** —— 容器目录布局、插件与会话组织方式的参考。
- **[Shizuku](https://github.com/RikkaApps/Shizuku)**（Apache-2.0）—— 提供高权限执行通道。
- Jetpack Compose / AndroidX（Apache-2.0）、Kotlin（Apache-2.0）、XZ for Java（Public Domain）。

第三方组件的完整清单与各自协议见 **[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)**。

## 许可

本项目以 **GNU General Public License v3.0**（`GPL-3.0-only`）授权，全文见
[LICENSE](LICENSE)。你可以自由使用、修改、分发；分发修改后的版本时，
须同样以 GPL-3.0 提供完整源代码并保留版权声明。

## 免责声明

本软件按「现状」提供，不附带任何担保。它只是一个启动器：
**模型输出的内容、AI 对手机的操作、以及由此产生的 API 费用与数据变动，
由使用者自行判断与承担。** 请勿在含有重要数据或涉及他人隐私的设备上启用
「完全访问」或自动化操作。
