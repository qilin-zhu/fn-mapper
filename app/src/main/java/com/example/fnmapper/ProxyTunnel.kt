package com.example.fnmapper

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * HTTPS 代理 CONNECT 隧道实现：
 *
 *   手机本地应用 ──TCP──> 127.0.0.1:映射端口 (MappingService)
 *                          │
 *                          ├──(TLS/明文)──> HTTPS 代理
 *                          │     CONNECT 飞牛地址:端口 (+ Basic 认证)
 *                          │<─── 200 Connection established
 *                          │
 *                          └──双向透明转发──> 飞牛 192.168.31.18:5666
 *
 * 隧道建立后对上层完全透明，HTTP / WebSocket / 任意 TCP 流量均可通过。
 */
object ProxyTunnel {

    class TunnelException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * 一条已就绪的隧道连接。
     * [input] 是包装了缓冲的输入流 —— CONNECT 响应头之后代理可能已经转发了目标服务器的
     * 早期数据，必须从同一缓冲流继续读，不能丢弃。
     */
    class Tunnel(val socket: Socket, val input: BufferedInputStream)

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val HANDSHAKE_TIMEOUT_MS = 15_000

    /**
     * 建立经过 HTTPS 代理到 [targetHost]:[targetPort] 的 CONNECT 隧道。
     *
     * @param proxy 解析后的代理 (host, port, tls)
     * @param targetHost 目标主机（飞牛 IP/域名）
     * @param targetPort 目标端口
     * @param user 代理用户名，空则不发送认证头
     * @param password 代理密码
     */
    fun connect(
        proxy: Triple<String, Int, Boolean>,
        targetHost: String,
        targetPort: Int,
        user: String,
        password: String
    ): Tunnel {
        val (proxyHost, proxyPort, tls) = proxy

        // 1. 连接到代理（https:// 前缀则先与代理完成 TLS 握手）
        val socket: Socket = try {
            if (tls) {
                val raw = Socket()
                raw.connect(InetSocketAddress(proxyHost, proxyPort), CONNECT_TIMEOUT_MS)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, proxyHost, proxyPort, true) as SSLSocket
                ssl.startHandshake()
                ssl
            } else {
                Socket().also { it.connect(InetSocketAddress(proxyHost, proxyPort), CONNECT_TIMEOUT_MS) }
            }
        } catch (e: IOException) {
            throw TunnelException("无法连接代理 $proxyHost:$proxyPort：${e.message}", e)
        }

        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = socket.getOutputStream()

            // 2. 发送 CONNECT 请求
            val request = buildString {
                append("CONNECT ").append(targetHost).append(':').append(targetPort).append(" HTTP/1.1\r\n")
                append("Host: ").append(targetHost).append(':').append(targetPort).append("\r\n")
                if (user.isNotEmpty()) {
                    val cred = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
                    append("Proxy-Authorization: Basic ").append(cred).append("\r\n")
                }
                append("Proxy-Connection: keep-alive\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray(Charsets.ISO_8859_1))
            output.flush()

            // 3. 读取响应状态行与头部（读到空行为止）
            val statusLine = readLine(input) ?: throw TunnelException("代理提前关闭连接（CONNECT 无响应）")
            if (!statusLine.startsWith("HTTP/")) {
                throw TunnelException("代理返回了非 HTTP 响应：${statusLine.take(80)}")
            }
            val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw TunnelException("无法解析代理响应：${statusLine.take(80)}")
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            if (statusCode != 200) {
                socket.close()
                throw TunnelException(
                    when (statusCode) {
                        407 -> "代理认证失败（407），请检查用户名和密码"
                        403 -> "代理拒绝访问（403）"
                        502, 504 -> "代理无法连接到目标 ${targetHost}:${targetPort}（$statusCode）"
                        else -> "代理返回错误状态码 $statusCode"
                    }
                )
            }

            // 4. 隧道就绪：后续数据原样双向转发，不再有超时（长连接）
            socket.soTimeout = 0
            return Tunnel(socket, input)
        } catch (e: TunnelException) {
            runCatching { socket.close() }
            throw e
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw TunnelException("与代理协商隧道失败：${e.message}", e)
        }
    }

    /** 读取一行（以 \n 结尾，去掉结尾 \r\n），流结束时返回 null。 */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            }
            if (b == '\n'.code) {
                val s = buf.toString(Charsets.ISO_8859_1.name())
                return s.removeSuffix("\r")
            }
            if (b != '\r'.code) buf.write(b)
        }
    }

    /** 单向泵数据：src EOF 后对 dst 做半关闭，由调用方在两侧均结束后统一关闭。 */
    fun pump(src: InputStream, dst: OutputStream, onEof: () -> Unit) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                if (n > 0) {
                    dst.write(buf, 0, n)
                    dst.flush()
                }
            }
        } catch (_: IOException) {
            // 连接被重置/中断属于正常结束
        } finally {
            runCatching { onEof() }
        }
    }
}
