package com.example.fcldsh

import android.util.Log
import java.io.File

/**
 * 把容器里 OpenSSL 的「配置与证书路径」掰回本机容器，而不是 Termux 的私有目录。
 *
 * ## 为什么要做这件事
 *
 * `assets/runtime.zip` 里的 Node / libssl 是在 **Termux 环境里编译**的，
 * Termux 构建出的 OpenSSL 把 `OPENSSLDIR` 写死成
 * `/data/data/com.termux/files/usr/etc/tls`。本应用的容器却在
 * `/data/data/<本应用包名>/files/usr`。
 *
 * 于是引擎初始化 TLS 时会去读**另一个应用的私有目录**：
 * - 设备上装了 Termux：那是别人的 0700 目录，本应用连 stat 都 EACCES；
 * - 没装 Termux：那个路径根本不存在。
 *
 * 两种情况 OpenSSL 都拿不到配置。多数机型上它只落回内置默认值、不致命，
 * 但一旦某个环节把这个「配置文件读不到」升级成错误（不同 OpenSSL 构建、
 * 不同系统版本表现并不一致），表现就是网络模块直接不可用 ——
 * 而这类故障在开发机上未必复现（开发机可能装了 Termux、或系统版本较低），
 * 到用户机上是必现的。
 *
 * ## 做法：只做「存在才指过去」的注入
 *
 * 1. `$PREFIX/etc/tls/openssl.cnf` 若不存在，就地写一份最小可用配置；
 * 2. `OPENSSL_CONF` 指向它 —— OpenSSL 初始化时读 `OPENSSL_CONF` 环境变量，
 *    指向一个**存在**的文件，它就不会再去碰那个写死的 Termux 路径；
 * 3. `$PREFIX/etc/tls/cert.pem` 存在时，额外给出 `SSL_CERT_FILE` 与
 *    `NODE_EXTRA_CA_CERTS`（同一份 CA 包，供 OpenSSL 与 Node 各自使用）。
 *
 * ## ★ 每一条都必须「文件真实存在」才注入 ★
 *
 * 这是本文件最重要的一条纪律，反过来说也成立：
 * **指向不存在的配置比不设置更糟**。
 * - `--openssl-config` / `OPENSSL_CONF` 指向不存在的文件时，OpenSSL 初始化
 *   直接失败 —— 那正好复制了我们想消灭的那种故障，只是换了个路径；
 * - `SSL_CERT_FILE` 指向不存在的文件会让证书链校验一个根都没有；
 * - `NODE_EXTRA_CA_CERTS` 指向不存在的文件至少会刷一条 warning。
 *
 * 所以这里一律 `isFile && length() > 0` 判定，宁可不设。
 *
 * ## 为什么在启动前做，而不是解压时做一次
 *
 * `ensureRuntime()` 命中「容器已就绪」时直接返回，不会重跑解压逻辑；
 * 老用户、被手工拷回来的容器、解压中断过的容器都不会再经过那里。
 * 放在启动路径上则每次启动都能补上，且写文件是幂等的。
 */
object OpenSslEnv {

    private const val TAG = "DSH-openssl"

    /** 容器内 TLS 目录（相对 `$PREFIX`）。与 Termux 布局保持一致。 */
    private const val TLS_REL = "etc/tls"

    private const val CONF_NAME = "openssl.cnf"

    private const val CERT_NAME = "cert.pem"

    /**
     * 最小可用配置。
     *
     * 只声明「TLS 1.2 起步」，其余全部沿用 OpenSSL 默认值 —— 目标是让
     * 配置文件**可读**，不是改变加密策略。
     *
     * ⚠️ 刻意不写 `CipherString = DEFAULT@SECLEVEL=1`：
     * 那是把安全等级降到 OpenSSL 1.0.2 时代的水平，只有连极老的
     * 1024 位 RSA / RC4 服务端才需要。为了让 npm 连上 registry 而全局降级
     * 不划算；确有需要时由用户显式打开（见末尾注释行）。
     */
    private val DEFAULT_CONF = """
        # 由 fcl-dsh 启动器生成；删除后下次启动会自动重建。
        # 存在的意义：让 OpenSSL 有一个**可读**的配置入口，
        # 而不是回落到 Termux 构建里写死的 /data/data/com.termux/... 路径。
        openssl_conf = default_conf

        [default_conf]
        ssl_conf = ssl_sect

        [ssl_sect]
        system_default = system_default_sect

        [system_default_sect]
        MinProtocol = TLSv1.2
        # 仅当需要连极老的 TLS 服务端（1024 位 RSA 等）时取消注释：
        # CipherString = DEFAULT@SECLEVEL=1
    """.trimIndent() + "\n"

    /**
     * 确保配置就绪，并返回**可以安全下发**的环境变量表。
     *
     * 返回的 Map 只包含真实存在的文件对应的键；调用方直接
     * `env.putAll(...)` 即可，不需要再判断一次。
     *
     * @param prefix 容器根（`filesDir/usr` 的绝对路径）
     * @param onLog 与启动/安装日志同一条通道
     */
    fun ensure(prefix: String, onLog: (String) -> Unit): Map<String, String> {
        val tls = File(prefix, TLS_REL)
        val conf = File(tls, CONF_NAME)

        runCatching { ensureConf(conf) }
            .onFailure { onLog("⚠️ 生成 openssl.cnf 失败: ${it.message ?: it.javaClass.simpleName}") }

        val env = LinkedHashMap<String, String>(3)

        if (isUsableFile(conf)) {
            env["OPENSSL_CONF"] = conf.absolutePath
        } else {
            // 写不进去时**不要**设 OPENSSL_CONF：不设只是沿用 OpenSSL 内置默认，
            // 指向一个不存在的文件反而会把初始化打成错误。
            onLog("⚠️ 未能准备 $CONF_NAME，OpenSSL 将沿用内置默认配置")
        }

        val cert = File(tls, CERT_NAME)
        if (isUsableFile(cert)) {
            // SSL_CERT_FILE：OpenSSL 侧的 CA 包
            env["SSL_CERT_FILE"] = cert.absolutePath
            // NODE_EXTRA_CA_CERTS：Node 在自带根证书之外追加的 CA
            //（是「追加」而不是「替换」，所以不会打断 Node 自带的根证书链）
            env["NODE_EXTRA_CA_CERTS"] = cert.absolutePath
        }

        if (env.isNotEmpty()) {
            onLog("🔐 OpenSSL 配置: ${env["OPENSSL_CONF"] ?: "内置默认"}")
            Log.i(TAG, "openssl env: $env")
        }
        return env
    }

    /** 不存在或为空就写一份默认配置；已存在则一个字节都不动（用户可能改过）。 */
    private fun ensureConf(conf: File) {
        if (isUsableFile(conf)) return
        conf.parentFile?.mkdirs()
        conf.writeText(DEFAULT_CONF)
        Log.i(TAG, "wrote default openssl.cnf -> ${conf.absolutePath}")
    }

    /** 「存在且非空」——所有注入判定的唯一标准。 */
    private fun isUsableFile(f: File): Boolean = f.isFile && f.length() > 0L
}
