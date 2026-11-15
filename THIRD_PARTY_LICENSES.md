# 第三方组件与协议

本文件列出 DSH Launcher 分发时包含或依赖的第三方组件，以及各自的授权协议。
**修改依赖时请同步更新本文件。**

> 本表按 `app/module.toml` 的实际依赖整理。
> 协议名称以各组件的官方仓库/制品声明为准；如与上游不一致，以上游为准。

---

## 一、随 APK 一起分发的代码库

这些是编译进 APK 的 Maven 依赖。

| 组件 | 版本 | 协议 |
|---|---|---|
| Kotlin Standard Library | 随工具链 | Apache-2.0 |
| AndroidX Activity Compose | 1.13.0 | Apache-2.0 |
| AndroidX Compose UI | 1.11.2 | Apache-2.0 |
| AndroidX Compose Foundation | 1.11.2 | Apache-2.0 |
| AndroidX Compose Material3 | 1.4.0 | Apache-2.0 |
| AndroidX Compose UI Tooling Preview | 1.11.2 | Apache-2.0 |
| AndroidX Material Icons Core | 1.7.8 | Apache-2.0 |
| Shizuku API / Provider / AIDL (`dev.rikka.shizuku`) | 13.1.5 | Apache-2.0 |
| XZ for Java (`org.tukaani:xz`) | 1.12 | Public Domain |

Apache-2.0 全文：<https://www.apache.org/licenses/LICENSE-2.0>
XZ for Java 项目主页：<https://tukaani.org/xz/java.html>

### 矢量图标

`app/src/main/res/drawable/ic_rail_*.xml` 等图标是 **Material Design Icons**，
由 Google 以 Apache-2.0 发布。部分文件头部的注释写着「移植自 FoldCraftLauncher」，
那是**来源路径**的记录（从 FCL 仓库里取的同一套 Material 图标），图标本身的
授权仍来自 Material Design Icons。

> 若后续要从 FCL 引入**非 Material 图标**的素材，请先确认其授权 ——
> FCL 使用的是自定义的有限许可，不一定允许再分发。

---

## 二、随 APK 分发、但**不在本仓库内**的二进制

`app/src/main/assets/runtime.zip` 是打包进 APK 的 Node 运行时，体积较大，
**不纳入 Git 仓库**（见 `.gitignore`），但会随 APK 一起分发。

| 内容 | 协议 | 说明 |
|---|---|---|
| Node.js | MIT | 运行时主体 |
| Termux 用户态（bash、coreutils、ripgrep、openssl 等） | **各自独立**（GPL-2.0 / GPL-3.0 / LGPL / Apache-2.0 / MIT 混合） | 见下方提示 |

> ⚠️ **合规提示（请自行核对，我不替你下结论）**
>
> Termux 生态里的许多包是 GPL/LGPL 授权。**随 APK 二进制分发这些程序时，
> 通常需要同时提供对应的源代码或书面要约**（GPL-3.0 §6 / GPL-2.0 §3）。
> 建议做法：
> 1. 在 Releases 说明里列出 runtime.zip 的构成与来源；
> 2. 提供获取对应源码的途径（例如指向 Termux 的包构建仓库，或自行镜像）；
> 3. 若你不打算承担这部分义务，考虑改为**首次启动时由用户自行下载**运行时，
>    而不是随 APK 分发。

---

## 三、不随包分发、运行时按需安装

| 组件 | 协议 | 说明 |
|---|---|---|
| DeepSeek Harness（dsh 引擎） | 见上游仓库 | 由用户在「下载中心」自行安装；本应用不修改其源码，也不把它打包进 APK |

用户在本应用里安装的插件、技能、MCP 服务由各自作者授权，与本项目无关。

---

## 四、仅作参考、未使用代码

| 项目 | 关系 |
|---|---|
| [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) | 仅参考界面版式与交互手感（侧栏布局、设置行样式、按下缩放）。**未复制其 Kotlin/Java 代码**。 |
| Deepseek-Harness-Local-Android | 仅参考容器目录布局与插件/会话的组织方式。 |

---

## 五、本项目的授权

DSH Launcher 本身以 **GPL-3.0-only** 授权，全文见仓库根目录的 `LICENSE`。

如果你的仓库里还没有 `LICENSE` 文件，请用 GitHub 的模板生成
（**Add file → Create new file → 文件名填 `LICENSE` → 右侧点 "Choose a license template"
→ 选 GNU General Public License v3.0**）。法律文本必须逐字准确，
**不要手工抄写或让 AI 生成**。
