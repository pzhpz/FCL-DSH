# 从参考项目拷来的终端原生材料（未接入构建）

来源：`../Deepseek-Harness-Local-Android-main/app/src/main/cpp/`（Apache-2.0，同一上游）。

这两个文件是**文本源码**，在这里只是**存档**：本项目的 CodeAssist 原生构建链
没有 NDK / CMake，`tools/` 又不在任何 source root 下，所以它们**不参与编译**，
放进来不会影响 `assemble:app:debug`。

## 为什么需要它们

dsh 的 Web 终端与持久 bash 工具都经 `@deepseek-ai/dsh-subprocess-local` 调
`node-pty` 申请一个**新的**伪终端。Android 上有两层断点：

1. 进程探测器只认 `linux/darwin/win32`，而 Termux 版 node 报
   `process.platform === "android"` → 直接抛
   `terminal inspection is unsupported on platform android`；
2. `node-pty` 没有 Android 预编译产物，runtime 里是「一调用就抛错」的空壳。

`dsh_pty_exec.c` 补的是第 2 层的原生侧：一个**独立可执行文件**，挂在
`$PREFIX/bin/dsh_pty_exec`，由引擎里的 node-pty 兼容层 spawn。协议见该文件头部
注释（stdin/stdout 搬字节、fd 3 行协议 `R <cols> <rows>` / `K <SIGNAL>`、
`--dsh-pty-pidfile` 同步取 pid 与退出码）。

## 还缺什么（关键）

**缺的是编译产物，不是源码。**

- 参考项目里 `libdsh_pty_exec.so` **不在仓库里**：它由 CI 用 NDK + CMake 编出来、
  以 `lib*.so` 的名字打进 APK 的 `lib/<abi>/`（AGP 只收 `*.so`），App 启动时再从
  自己的 APK 里读出该条目、落到 `engine/bin/dsh_pty_exec` 并 `chmod +x`
  （targetSdk 28 豁免 W^X 才能 execve）。
- 因此这条路径**绕不开「拿到一个 arm64 预编译 ELF」**：要么从参考项目已发布的
  APK 里取 `lib/arm64-v8a/libdsh_pty_exec.so`，要么在能跑 NDK 的机器上编一次
  （`cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28`，再把产物改名成
  `libdsh_pty_exec.so`）。

## 拿到产物之后还要做的两件事

1. **落到容器里**：把二进制释放到 `$PREFIX/bin/dsh_pty_exec` 并 `chmod 0755`。
2. **打 node-pty 兼容层**：`$PREFIX/lib/node_modules/node-pty/lib/index.js` 换成
   转发到该助手的实现，并把 `runner-launch-*.js` 里的平台判断加上 `android`。
   参考项目的单一真源是 `../Deepseek-Harness-Local-Android-main/scripts/
   patch-node-pty-android.py`（内含完整 JS 实现）。本项目的引擎是首启时 npm 装进
   `$PREFIX/lib/versions/<ver>/node_modules`，所以这步必须在**启动引擎之前**按
   实际路径动态做，不能只改打包时的 runtime.zip。

## 附：App 侧自绘终端没走这条路

参考项目的 `TerminalActivity` / `TerminalPtySession` 依赖
`com.termux.terminal.*` 与 `com.termux.view.TerminalView` —— 那两个包在参考项目
仓库里是**空的**（`app/src/main/java/com/termux/` 无文件），实际由 JitPack 上的
Termux AAR 提供，另配 `jniLibs/<abi>/libtermux.so`（预编译二进制）。本项目既无法
解析那个 AAR（CodeAssist 依赖源里查不到），也无法拷贝二进制，故这条支线暂不可行。
