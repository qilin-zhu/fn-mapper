package com.example.fnmapper

import android.content.Context
import android.content.SharedPreferences

/**
 * 端口映射配置：用户在设置页填写的代理与飞牛信息。
 *
 * 飞牛地址只填主机（如 192.168.31.18），端口可填多个（如 5666, 5667），
 * 每个端口 P 独立映射：本地 127.0.0.1:P → 飞牛主机:P（经代理）。
 */
data class MappingConfig(
    val proxyUrl: String,      // HTTPS 代理地址，如 https://proxy.example.com:8443
    val proxyUser: String,     // 代理用户名（可为空）
    val proxyPassword: String, // 代理密码（可为空）
    val targetHost: String,    // 飞牛主机，如 192.168.31.18
    val ports: List<Int>,      // 映射端口列表（本地端口 = 飞牛端口），默认 [5666]
    val scannerUrl: String = "", // fnos-scanner 端口 JSON 在线地址（可选，用于一键获取端口）
    val portNames: Map<Int, String> = emptyMap(), // 端口 → 服务名（应用名/Docker 容器名），仅用于展示
    val lanAccess: Boolean = false // 局域网访问：true 时监听 0.0.0.0（同网段设备可连），false 时仅本机 127.0.0.1
) {
    companion object {
        const val DEFAULT_PORT = 5666

        /** 扫码/在线获取端口后默认并入的本机端口及名称 */
        val DEFAULT_PORT_NAMES: Map<Int, String> = linkedMapOf(
            DEFAULT_PORT to "fnOS 主端口",
            5667 to "FnOS SSL 端口"
        )
        val DEFAULT_PORTS: List<Int> = DEFAULT_PORT_NAMES.keys.toList()

        /**
         * 解析代理地址为 (host, port, 是否TLS)。
         * 支持 https://host:port、http://host:port、host:port 三种写法。
         */
        fun parseProxy(url: String): Triple<String, Int, Boolean>? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            var tls = false
            var rest = trimmed
            val schemeSep = trimmed.indexOf("://")
            if (schemeSep >= 0) {
                val scheme = trimmed.substring(0, schemeSep).lowercase()
                if (scheme != "http" && scheme != "https") return null
                tls = scheme == "https"
                rest = trimmed.substring(schemeSep + 3)
            }
            rest = rest.substringBefore('/')
            rest = rest.substringBefore('?')
            if (rest.isEmpty()) return null
            val host: String
            val port: Int
            if (rest.startsWith("[")) {
                // IPv6 字面量 [::1]:8443
                val close = rest.indexOf(']')
                if (close < 0) return null
                host = rest.substring(1, close)
                val after = rest.substring(close + 1)
                port = if (after.startsWith(":")) after.substring(1).toIntOrNull() ?: return null
                else if (after.isEmpty()) if (tls) 443 else 80
                else return null
            } else {
                val colon = rest.lastIndexOf(':')
                if (colon > 0 && rest.indexOf(':') == colon) {
                    host = rest.substring(0, colon)
                    port = rest.substring(colon + 1).toIntOrNull() ?: return null
                } else {
                    host = rest
                    port = if (tls) 443 else 80
                }
            }
            if (host.isEmpty() || port !in 1..65535) return null
            return Triple(host, port, tls)
        }

        /**
         * 解析飞牛主机地址，返回 (host, address中携带的端口或null)。
         * 主机写法：192.168.31.18、http://192.168.31.18、[::1]。
         * 兼容旧写法 192.168.31.18:5666 —— 拆出主机，端口由调用方并入端口列表。
         */
        fun parseHost(address: String): Pair<String, Int?>? {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return null
            var rest = trimmed
            val schemeSep = trimmed.indexOf("://")
            if (schemeSep >= 0) rest = trimmed.substring(schemeSep + 3)
            rest = rest.substringBefore('/')
            rest = rest.substringBefore('?')
            if (rest.isEmpty()) return null
            if (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                if (close < 0) return null
                val host = rest.substring(1, close)
                val after = rest.substring(close + 1)
                val port = if (after.startsWith(":")) after.substring(1).toIntOrNull() ?: return null else null
                if (host.isEmpty() || (port != null && port !in 1..65535)) return null
                return host to port
            }
            val colon = rest.lastIndexOf(':')
            return if (colon > 0 && rest.indexOf(':') == colon) {
                val host = rest.substring(0, colon)
                val port = rest.substring(colon + 1).toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) return null
                host to port
            } else {
                if (rest.isEmpty()) return null
                rest to null
            }
        }

        /**
         * 解析多个端口（逗号/空格/分号/换行分隔），自动去重保序。
         * 返回 null 表示格式不正确或列表为空。
         */
        fun parsePorts(input: String): List<Int>? {
            val parts = input.split(',', '，', ';', '；', ' ', '\n', '\t', '/')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val ports = mutableListOf<Int>()
            for (p in parts) {
                val n = p.toIntOrNull() ?: return null
                if (n !in 1..65535) return null
                if (n !in ports) ports.add(n)
            }
            return ports
        }

        /**
         * 校验配置完整性，返回错误消息（null 表示通过）。
         */
        fun validate(cfg: MappingConfig): String? {
            if (parseProxy(cfg.proxyUrl) == null) return "代理地址格式不正确，示例：https://proxy.example.com:8443"
            if (parseHost(cfg.targetHost) == null) return "飞牛地址格式不正确，示例：192.168.31.18"
            if (cfg.ports.isEmpty()) return "请至少填写一个映射端口"
            return null
        }
    }
}

/**
 * 配置持久化（SharedPreferences），含旧版单端口数据自动迁移。
 */
object ConfigStore {
    private const val PREFS = "fn_mapper_config"
    private const val KEY_PROXY_URL = "proxy_url"
    private const val KEY_PROXY_USER = "proxy_user"
    private const val KEY_PROXY_PASSWORD = "proxy_password"
    private const val KEY_TARGET = "target_address"   // 旧版存 host:port，新版存纯 host
    private const val KEY_PORTS = "ports"             // 新版：逗号分隔端口列表
    private const val KEY_SCANNER_URL = "scanner_url" // fnos-scanner 端口 JSON 地址
    private const val KEY_PORT_NAMES = "port_names"   // 端口 → 服务名，格式 "5666=fnOS 主端口;8080=nginx"
    private const val KEY_AUTO_START = "auto_start"   // 上次是否开启了映射（用于打开 App 自动恢复）
    private const val KEY_LAN_ACCESS = "lan_access"   // 局域网访问：监听 0.0.0.0 还是 127.0.0.1

    /** 上次会话是否开启了映射。 */
    fun isAutoStart(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START, false)

    /** 记录映射开关状态：启动时置 true，手动停止时置 false。 */
    fun setAutoStart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun load(context: Context): MappingConfig {
        val sp: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedTarget = sp.getString(KEY_TARGET, "").orEmpty()
        val savedPorts = sp.getString(KEY_PORTS, null)

        // 解析旧地址（可能携带端口）
        val parsed = MappingConfig.parseHost(savedTarget)
        val host = parsed?.first ?: savedTarget
        val embeddedPort = parsed?.second

        val ports: List<Int> = when {
            // 新版端口列表
            savedPorts != null -> MappingConfig.parsePorts(savedPorts) ?: emptyList()
            // 旧版迁移：地址带端口用之，否则用旧 KEY_LOCAL_PORT 字段（默认 5666）
            else -> listOf(embeddedPort ?: sp.getInt("local_port", MappingConfig.DEFAULT_PORT))
        }

        return MappingConfig(
            proxyUrl = sp.getString(KEY_PROXY_URL, "").orEmpty(),
            proxyUser = sp.getString(KEY_PROXY_USER, "").orEmpty(),
            proxyPassword = sp.getString(KEY_PROXY_PASSWORD, "").orEmpty(),
            targetHost = host,
            ports = ports,
            scannerUrl = sp.getString(KEY_SCANNER_URL, "").orEmpty(),
            portNames = parsePortNames(sp.getString(KEY_PORT_NAMES, "").orEmpty()),
            lanAccess = sp.getBoolean(KEY_LAN_ACCESS, false)
        )
    }

    /** 解析 "5666=fnOS 主端口;8080=nginx" 格式的端口名映射。 */
    private fun parsePortNames(raw: String): Map<Int, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val i = entry.indexOf('=')
            if (i <= 0) null
            else entry.substring(0, i).trim().toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?.let { it to entry.substring(i + 1) }
        }.toMap()
    }

    fun save(context: Context, cfg: MappingConfig) {
        val namesRaw = cfg.portNames.entries
            .filter { it.value.isNotEmpty() }
            .joinToString(";") { "${it.key}=${it.value}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROXY_URL, cfg.proxyUrl.trim())
            .putString(KEY_PROXY_USER, cfg.proxyUser.trim())
            .putString(KEY_PROXY_PASSWORD, cfg.proxyPassword)
            .putString(KEY_TARGET, cfg.targetHost.trim())
            .putString(KEY_PORTS, cfg.ports.joinToString(","))
            .putString(KEY_SCANNER_URL, cfg.scannerUrl.trim())
            .putString(KEY_PORT_NAMES, namesRaw)
                .putBoolean(KEY_LAN_ACCESS, cfg.lanAccess)
                .remove("local_port") // 清理旧字段
                .apply()
    }
}
