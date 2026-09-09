package com.example.fnmapper

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 端口及其服务名：应用端口为应用名，Docker 端口为容器名，本机默认端口为固定名称。
 */
data class PortInfo(val port: Int, val name: String)

/**
 * 从 fnos-scanner 的端口 JSON（/ports.json）获取端口列表。
 *
 * 拉取策略：优先直连（手机与 NAS 同内网时最快）；
 * 直连失败且设置页已填代理时，自动改用 CONNECT 隧道经代理拉取
 * （手机在外网时同样可用，复用 [ProxyTunnel] 的隧道能力）。
 */
object PortsFetcher {

    class FetchException(message: String) : IOException(message)

    private const val DIRECT_CONNECT_TIMEOUT_MS = 4_000
    private const val DIRECT_READ_TIMEOUT_MS = 10_000
    private const val PROXY_READ_TIMEOUT_MS = 15_000
    private const val MAX_BODY_BYTES = 1 shl 20 // 1MB 响应上限

    /**
     * 拉取并解析端口列表。所有尝试路径的失败原因会拼接进最终异常，便于界面诊断。
     *
     * @param urlText 在线地址，如 http://192.168.31.18:8787/ports.json
     * @param proxy 解析后的代理 (host, port, tls)，null 表示未配置
     * @param user 代理用户名
     * @param password 代理密码
     */
    fun fetch(
        urlText: String,
        proxy: Triple<String, Int, Boolean>?,
        user: String,
        password: String
    ): List<PortInfo> {
        val url = parseUrl(urlText)
            ?: throw FetchException("在线地址格式不正确，示例：http://192.168.31.18:8787/ports.json")

        val errors = mutableListOf<String>()
        // 1) 直连
        try {
            return parsePortsJson(httpGetDirect(url))
        } catch (e: Exception) {
            errors.add("直连：${e.message}")
        }
        // 2) 经代理 CONNECT 隧道
        if (proxy != null) {
            try {
                return parsePortsJson(httpGetViaProxy(url, proxy, user, password))
            } catch (e: Exception) {
                errors.add("经代理：${e.message}")
            }
        }
        throw FetchException(errors.joinToString("；"))
    }

    /** 解析 http/https URL，其他协议或非法地址返回 null。 */
    private fun parseUrl(text: String): URL? {
        val t = text.trim()
        if (!t.startsWith("http://", true) && !t.startsWith("https://", true)) return null
        val url = runCatching { URL(t) }.getOrNull() ?: return null
        if (url.protocol != "http" && url.protocol != "https") return null
        return url
    }

    // ------------------------------------------------------------------
    // 直连
    // ------------------------------------------------------------------

    private fun httpGetDirect(url: URL): String {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = DIRECT_CONNECT_TIMEOUT_MS
            conn.readTimeout = DIRECT_READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrElse { "" }
            if (code != 200) throw FetchException("HTTP $code ${body.take(120)}")
            return body
        } catch (e: FetchException) {
            throw e
        } catch (e: Exception) {
            throw FetchException(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // 经代理 CONNECT 隧道
    // ------------------------------------------------------------------

    private fun httpGetViaProxy(
        url: URL,
        proxy: Triple<String, Int, Boolean>,
        user: String,
        password: String
    ): String {
        val port = if (url.port > 0) url.port else if ("https".equals(url.protocol, true)) 443 else 80
        val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")

        val tunnel = try {
            ProxyTunnel.connect(proxy, url.host, port, user, password)
        } catch (e: Exception) {
            throw FetchException(e.message ?: "隧道建立失败")
        }

        var socket = tunnel.socket
        var input: InputStream = tunnel.input
        try {
            // https 目标：CONNECT 之后对目标主机再做一次 TLS 握手
            if ("https".equals(url.protocol, true)) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(tunnel.socket, url.host, port, true) as SSLSocket
                runCatching {
                    // 域名场景补充 SNI（IP 地址不支持 SNI，失败可忽略）
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(url.host))
                    ssl.sslParameters = params
                }
                ssl.startHandshake()
                socket = ssl
                input = BufferedInputStream(ssl.getInputStream(), 16 * 1024)
            }

            socket.soTimeout = PROXY_READ_TIMEOUT_MS
            val out = socket.getOutputStream()
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(url.host).append(':').append(port).append("\r\n")
                append("Accept: application/json\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(request.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val statusLine = readLine(input) ?: throw FetchException("目标服务无响应")
            if (!statusLine.startsWith("HTTP/")) {
                throw FetchException("目标返回非 HTTP 响应：${statusLine.take(60)}")
            }
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw FetchException("无法解析响应：${statusLine.take(60)}")
            while (true) {
                if (readLine(input) == null) break
            }
            // Connection: close —— 读到 EOF 即完整响应体
            val body = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                body.write(buf, 0, n)
                if (body.size() > MAX_BODY_BYTES) throw FetchException("响应数据过大")
            }
            val text = body.toString("UTF-8")
            if (code != 200) throw FetchException("HTTP $code ${text.take(120)}")
            return text
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 读一行 HTTP 头（到 \n，去掉 \r），流结束返回 null。 */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").removeSuffix("\r")
            if (b != '\r'.code) buf.write(b)
        }
    }

    // ------------------------------------------------------------------
    // JSON 解析
    // ------------------------------------------------------------------

    /**
     * 解析 fnos-scanner 端口 JSON，返回带服务名的端口列表：
     * { ok, host, mainPort, ports:[...], apps:[{name,appName,port}], docker:[{name,image,ports:[{port}]}] }
     * 服务名规则：主端口=「fnOS 主端口」，应用端口=应用名，Docker 端口=容器名。
     */
    fun parsePortsJson(text: String): List<PortInfo> {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw FetchException("不是有效的 JSON：${text.take(60)}")
        }
        if (obj.has("ok") && !obj.optBoolean("ok")) {
            throw FetchException(obj.optString("error").ifEmpty { "服务返回错误" })
        }

        val byPort = LinkedHashMap<Int, String>()
        fun add(p: Int, name: String) {
            if (p in 1..65535 && p !in byPort) byPort[p] = name
        }

        // 主端口（管理入口）
        add(obj.optInt("mainPort"), "fnOS 主端口")

        // 兼容：纯端口数组（无服务名）
        obj.optJSONArray("ports")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = when (val v = arr.opt(i)) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                    else -> null
                } ?: continue
                add(p, "")
            }
        }

        // 应用端口 → 应用名（优先级最高，覆盖空名）
        obj.optJSONArray("apps")?.let { apps ->
            for (i in 0 until apps.length()) {
                val a = apps.optJSONObject(i) ?: continue
                val p = a.optInt("port", -1)
                if (p in 1..65535) {
                    val name = a.optString("name").ifEmpty { a.optString("appName") }
                    byPort[p] = name
                }
            }
        }

        // Docker 端口 → 容器名（仅当该端口还没有名字时）
        obj.optJSONArray("docker")?.let { dockers ->
            for (i in 0 until dockers.length()) {
                val d = dockers.optJSONObject(i) ?: continue
                val name = d.optString("name").ifEmpty { shortImage(d.optString("image")) }
                val ps = d.optJSONArray("ports") ?: continue
                for (j in 0 until ps.length()) {
                    val p = ps.optJSONObject(j)?.optInt("port", -1) ?: continue
                    if (p in 1..65535 && byPort[p].isNullOrEmpty()) byPort[p] = name
                }
            }
        }

        if (byPort.isEmpty()) throw FetchException("JSON 中没有可用端口")
        return byPort.keys.sorted().map { PortInfo(it, byPort[it].orEmpty()) }
    }

    /** 镜像短名：registry/repo:tag → repo:tag，与 fnos-scanner 网页 shortImg 一致。 */
    private fun shortImage(image: String): String = image.substringAfterLast('/').ifEmpty { image }
}
