/*
 * node-pty Android 后端（移植自参考项目 Deepseek-Harness-Local-Android-main，
 * scripts/patch-node-pty-android.py 里的 NODE_PTY_IMPL，Apache-2.0，同一上游）。
 *
 * 为什么需要这个文件
 * ------------------
 * dsh 的 Web 终端与持久 bash 工具都经 `@deepseek-ai/dsh-subprocess-local`
 * 调 `require("node-pty").spawn(...)` 申请一个**新的**伪终端。node-pty 没有
 * Android 预编译产物，runtime 里是「一调用就抛错」的空壳 —— 于是终端功能在
 * Android 上整体不可用。
 *
 * 本文件就是那个空壳的**真实实现**：把 spawn 转发给随 APK 分发的原生助手
 * `dsh_pty_exec`（assets/pty/libdsh_pty_exec.so，安装时落到 $PREFIX/bin），
 * 由它 fork 出挂 PTY 的 shell，并在 PTY 与本进程的管道之间搬运字节。
 *
 * 进程拓扑
 * --------
 *   node 引擎 ── spawn ──> dsh_pty_exec ── fork+PTY ──> bash
 *                stdin/stdout 管道     PTY master↔slave
 *                fd3 = 指令下行（R/K）
 *                pidfile = pid/退出状态上行
 *
 * 维护提示
 * --------
 * 由 PtySupport.kt 在每次启动引擎前写入各 node-pty 包的 lib/index.js，
 * 目标文件里若有 MARKER 就不再重写（幂等）。改这里即生效，无需改 Kotlin。
 */
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { EventEmitter } = require("events");

/**
 * 装配版本标记。
 *
 * PtySupport 用「目标文件里有没有这一串」判断要不要重写。**改实现时必须改它** ——
 * 否则已经装到 node_modules 里的旧副本永远不会被替换（本项目在 Android 上不能
 * 重新编译原生助手，靠这个标记做版本推进）。
 */
const IMPL_REVISION = "node-pty(android) rev:2";

/** 助手相对容器根的位置（PtySupport 部署到 <prefix>/bin/dsh_pty_exec）。 */
const EXEC_NAME = "dsh_pty_exec";

/** pidfile 参数名（与 dsh_pty_exec.c 的解析一致）。 */
const PIDFILE_FLAG = "--dsh-pty-pidfile";

/** bionic 的动态链接器：应用数据目录里的 ELF 必须经它加载。 */
const LINKER = "/system/bin/linker64";

/** 系统 shell：shebang 脚本交给它（linker64 只认 ELF）。 */
const SYSTEM_SH = "/system/bin/sh";

/**
 * 读文件头几个字节；打不开返回 undefined。
 *
 * 只需要区分「ELF / shebang / 其它」，4 字节足够。
 */
function readHead(file, count) {
  let fd;
  try {
    fd = fs.openSync(file, "r");
    const buf = Buffer.alloc(count);
    const read = fs.readSync(fd, buf, 0, count, 0);
    return read < count ? undefined : buf;
  } catch (_) {
    return undefined;
  } finally {
    if (fd !== undefined) {
      try {
        fs.closeSync(fd);
      } catch (_) {
        /* 已关闭 */
      }
    }
  }
}

/**
 * 决定「真正让助手 execv 什么」。
 *
 * ## 为什么必须有这一步（实测事故：终端起来立刻退出 127）
 *
 * `dsh_pty_exec` 在子进程里直接 `execv(program, ...)`。而 Android 10+ 对
 * **应用数据目录**里的文件禁止 `execve`（W^X），本项目的容器正好在
 * `/data/data/<pkg>/files/usr` 下 —— 于是 execv 必然失败，助手按约定
 * `_exit(127)`，终端只显示「进程已退出 (127)」，一个字都不解释。
 *
 * 引擎自己的子进程不受影响，是因为 `dsh-spawn-patch` 在 JS 层把所有 ELF 改写成
 * `/system/bin/linker64 <path>` —— 但那是 `child_process` 的补丁，**管不到 C 层
 * 里的 execv**。所以这里补上同样的改写：
 *
 * - ELF（且不在系统目录）→ `program = /system/bin/linker64`，argv = `[file, ...]`
 * - `#!` 脚本            → `program = /system/bin/sh`，argv = `[file, ...]`
 * - 其余/读不到/系统目录 → 原样，不做多余改动
 *
 * 与 spawn 补丁的判定顺序刻意保持一致，避免同一条命令在不同层得到不同结果。
 */
function planProgram(file, args) {
  const keep = { program: file, argv: args };
  if (typeof file !== "string" || file.length === 0) return keep;
  if (file === LINKER || file === SYSTEM_SH) return keep;

  // 软链要先解析：容器里的 bin/sh 很可能是指向 bash 的链接，
  // 而路径里是否含 /system 决定了能不能直接 exec。
  let real = file;
  try {
    real = fs.realpathSync(file);
  } catch (_) {
    /* 不存在就按原路径判，交给下游报错 */
  }
  if (real === LINKER || real === SYSTEM_SH) return keep;
  if (real.startsWith("/system/")) return keep;

  const head = readHead(real, 4);
  if (head === undefined) return keep;

  if (head[0] === 0x7f && head[1] === 0x45 && head[2] === 0x4c && head[3] === 0x46) {
    return { program: LINKER, argv: [real].concat(args) };
  }
  if (head[0] === 0x23 && head[1] === 0x21) {
    return { program: SYSTEM_SH, argv: [real].concat(args) };
  }
  return keep;
}

/**
 * 定位助手。
 *
 * ⚠️ 与参考项目的差异：参考项目把 runtime 直接铺在 $PREFIX（node-pty 在
 * `$PREFIX/lib/node_modules`），所以「上溯三层 + bin」正好命中。本项目引擎装在
 * `$PREFIX/lib/versions/<ver>/node_modules`，同一个相对深度会指到
 * `versions/bin`（不存在）。因此这里改成**逐级上溯找 bin/dsh_pty_exec**，
 * 两种布局都能命中，也不怕以后目录再搬。
 */
function locateHelper() {
  const candidates = [];
  const prefix = process.env.PREFIX;
  if (prefix) candidates.push(path.join(prefix, "bin", EXEC_NAME));
  let dir = __dirname;
  for (let i = 0; i < 10; i++) {
    candidates.push(path.join(dir, "bin", EXEC_NAME));
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  for (const c of candidates) {
    try {
      fs.accessSync(c, fs.constants.X_OK);
      return c;
    } catch (_) {
      /* 继续找下一个候选 */
    }
  }
  return undefined;
}

/** 同步睡眠：只用于 spawn 后取 pid、退出后取状态这两个极短窗口。 */
function sleepMs(ms) {
  const shared = new SharedArrayBuffer(4);
  Atomics.wait(new Int32Array(shared), 0, 0, ms);
}

/** 把 pidfile 读成 { pid } 或 { exitCode, signal }；读不到返回 undefined。 */
function readStatus(pidfile) {
  try {
    const text = fs.readFileSync(pidfile, "utf8").trim();
    if (text.startsWith("EXIT ")) {
      const parts = text.split(/\s+/);
      const code = Number.parseInt(parts[1], 10);
      const sig = Number.parseInt(parts[2], 10);
      return {
        exitCode: Number.isFinite(code) ? code : 0,
        signal: Number.isFinite(sig) && sig > 0 ? sig : undefined,
      };
    }
    const pid = Number.parseInt(text, 10);
    return Number.isFinite(pid) && pid > 0 ? { pid } : undefined;
  } catch (_) {
    return undefined;
  }
}

/** node-pty 兼容终端：接口面 = 上游 LocalTerminalHandle 实际用到的成员。 */
class AndroidTerminal extends EventEmitter {
  constructor(child, pidfile, file, cols, rows) {
    super();
    this.child = child;
    this.pidfile = pidfile;
    this._file = file;
    this._cols = cols;
    this._rows = rows;
    this._killTimer = undefined;
    this._exited = false;
    this._paused = false;

    // ⚠️ pid 必须**同步**可得：LocalTerminalHandle 在构造时就读 terminal.pid。
    // 助手 fork 完才写文件，这里短轮询等它（上限 2s，足够 fork 完成）。
    let status;
    for (let i = 0; i < 200; i++) {
      status = readStatus(pidfile);
      if (status !== undefined && status.pid !== undefined) break;
      sleepMs(10);
    }
    this.pid = status !== undefined && status.pid !== undefined ? status.pid : 0;

    this._onStdout = (buf) => {
      if (!this._paused) this.emit("data", buf.toString("utf8"));
    };
    child.stdout.on("data", this._onStdout);
    child.on("exit", () => this._settle());
    child.on("error", () => this._settle());
  }

  /** 子进程退出：从 pidfile 取真实退出码/信号（字节流已全部走 stdout）。 */
  _settle() {
    if (this._exited) return;
    this._exited = true;
    let exitCode = 0;
    let signal;
    // 助手写完状态才退出：稍等一下确保文件已落盘
    for (let i = 0; i < 100; i++) {
      const st = readStatus(this.pidfile);
      if (st !== undefined && st.exitCode !== undefined) {
        exitCode = st.exitCode;
        signal = st.signal;
        break;
      }
      sleepMs(5);
    }
    try {
      fs.unlinkSync(this.pidfile);
    } catch (_) {
      /* 清理失败无碍 */
    }
    this.emit("exit", { exitCode, signal });
  }

  onData(cb) {
    this.on("data", cb);
    return { dispose: () => this.off("data", cb) };
  }

  onExit(cb) {
    this.on("exit", cb);
    return { dispose: () => this.off("exit", cb) };
  }

  write(data) {
    if (this._exited) return;
    try {
      this.child.stdin.write(data);
    } catch (_) {
      /* 管道已关：忽略，退出事件会随后到达 */
    }
  }

  resize(cols, rows) {
    if (this._exited) return;
    this._cols = cols;
    this._rows = rows;
    this._control(`R ${cols} ${rows}`);
  }

  pause() {
    this._paused = true;
  }

  resume() {
    this._paused = false;
  }

  kill(signal) {
    if (this._exited) return;
    // ⚠️ 必须允许**重复**调用：引擎的清理是 SIGTERM → 等宽限期 → SIGKILL 的升级
    // 序列，而交互式 bash 默认忽略 SIGTERM —— 如果这里只发第一次，升级就被吞掉，
    // 终端关闭后 shell 会作为孤儿残留。实测踩过（"terminal cleanup failed;
    // surviving pid: N"）。
    this._control(`K ${signal === undefined ? "SIGTERM" : signal}`);
    if (this._killTimer === undefined) {
      // 兜底：信号没能收掉子进程组时，直接终止助手本身（它会带走子进程）
      this._killTimer = setTimeout(() => {
        if (!this._exited) {
          try {
            this.child.kill("SIGKILL");
          } catch (_) {
            /* 已退出 */
          }
        }
      }, 5000);
      if (typeof this._killTimer.unref === "function") this._killTimer.unref();
    }
  }

  _control(line) {
    try {
      this.child.stdio[3].write(`${line}\n`);
    } catch (_) {
      /* 控制通道不可用：非致命，退出路径仍能从 pidfile 取状态 */
    }
  }
}

/**
 * 与 node-pty 同形的 spawn。
 * @param {string} file 可执行文件绝对路径（引擎传 shell 路径）
 * @param {string[]} args 参数（不含程序名）
 * @param {{name?:string,cols?:number,rows?:number,cwd?:string,env?:object}} options
 */
function spawnPty(file, args, options) {
  const helper = locateHelper();
  if (helper === undefined) {
    throw new Error(
      "node-pty(android): dsh_pty_exec helper not found; expected $PREFIX/bin/" + EXEC_NAME,
    );
  }
  const cols = options && Number.isFinite(options.cols) ? options.cols : 80;
  const rows = options && Number.isFinite(options.rows) ? options.rows : 24;
  const cwd = options && options.cwd && options.cwd.length > 0 ? options.cwd : os.homedir();
  const tmpBase =
    process.env.TMPDIR && process.env.TMPDIR.length > 0 ? process.env.TMPDIR : os.tmpdir();
  const pidfile = path.join(
    tmpBase,
    "dsh-pty-" + process.pid + "-" + Date.now() + "-" + Math.floor(Math.random() * 1e6) + ".pid",
  );

  // pidfile 走 argv 末尾（fd 3 是 JS→助手的单向下行通道，不能反向传参）
  //
  // ⚠️ program 不能直接用 file：助手在 C 层 execv 它，绕不过 Android 对
  // 「应用数据目录里的 ELF」的 execve 禁令（详见 planProgram）。这里把 ELF /
  // shebang 改写成 linker64 / 系统 sh，与 spawn 补丁保持同一套判定。
  const plan = planProgram(file, args || []);
  const argv = [String(rows), String(cols), cwd, plan.program].concat(
    plan.argv,
    [PIDFILE_FLAG, pidfile],
  );
  const child = spawn(helper, argv, {
    stdio: ["pipe", "pipe", "pipe", "pipe"],
    env: options && options.env ? options.env : process.env,
    cwd: process.env.HOME && process.env.HOME.length > 0 ? process.env.HOME : cwd,
  });
  // 助手的诊断输出走 stderr：不混入终端内容，只在出错时留给排查
  child.stderr.on("data", (buf) => {
    const text = buf.toString("utf8").trim();
    if (text.length > 0) process.stderr.write("[dsh_pty_exec] " + text + "\n");
  });

  return new AndroidTerminal(child, pidfile, file, cols, rows);
}

module.exports = { spawn: spawnPty };
module.exports.default = module.exports;
