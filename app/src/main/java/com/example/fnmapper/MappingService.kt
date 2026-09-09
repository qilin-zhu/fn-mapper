package com.example.fnmapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：在手机本地为每个配置端口监听 127.0.0.1:端口，
 * 将每一条客户端连接通过 HTTPS 代理的 CONNECT 隧道转发到飞牛主机同端口。
 *
 * 每个端口由一个独立的 [PortMapper]（自带监听线程与连接线程组）承载，
 * 端口之间完全隔离，单端口异常不影响其他端口。
 */
class MappingService : Service() {

    companion object {
        const val CHANNEL_ID = "fn_mapper"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set

        /** 各端口最近错误（供 UI 展示），key=端口 */
        @Volatile
        var lastErrors: Map<Int, String> = emptyMap()
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MappingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MappingService::class.java))
        }
    }

    /** 单个端口的完整映射单元：监听线程 + 连接线程组，相互独立。 */
    private inner class PortMapper(
        val port: Int,
        private val proxy: Triple<String, Int, Boolean>,
        private val host: String,
        private val proxyUser: String,
        private val proxyPassword: String,
        private val lanAccess: Boolean = false
    ) {
        private val stopping = AtomicBoolean(false)
        private var serverSocket: ServerSocket? = null
        private val activeSockets = CopyOnWriteArrayList<Socket>()
        private var acceptThread: Thread? = null

        /** 启动监听，返回错误消息或 null（成功）。 */
        fun start(): String? {
            return try {
                // 局域网访问开启时监听 0.0.0.0（同网段设备可连），否则仅本机 127.0.0.1
                val bindAddr = java.net.InetAddress.getByName(if (lanAccess) "0.0.0.0" else "127.0.0.1")
                serverSocket = ServerSocket(port, 64, bindAddr)
                stopping.set(false)
                acceptThread = Thread { acceptLoop() }.also { it.start() }
                null
            } catch (e: Exception) {
                serverSocket = null
                "端口 $port 监听失败：${e.message}"
            }
        }

        fun stop() {
            stopping.set(true)
            runCatching { serverSocket?.close() }
            serverSocket = null
            activeSockets.forEach { runCatching { it.close() } }
            activeSockets.clear()
            acceptThread?.let { t -> runCatching { t.join(1000) } }
            acceptThread = null
        }

        private fun acceptLoop() {
            val server = serverSocket ?: return
            while (!stopping.get()) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                activeSockets.add(client)
                Thread {
                    handleClient(client)
                    activeSockets.remove(client)
                }.start()
            }
        }

        /**
         * 处理一条客户端连接：先经代理建立隧道（不读客户端任何字节，避免丢失），
         * 再双向透明转发。任一方向结束后半关闭对端，两个方向都结束后统一关闭。
         */
        private fun handleClient(client: Socket) {
            var tunnel: ProxyTunnel.Tunnel? = null
            try {
                client.tcpNoDelay = true
                tunnel = ProxyTunnel.connect(proxy, host, port, proxyUser, proxyPassword)

                val t = tunnel
                val remote = t.socket
                activeSockets.add(remote)

                val up = Thread {
                    ProxyTunnel.pump(client.getInputStream(), remote.getOutputStream()) {
                        runCatching { remote.shutdownOutput() }
                    }
                }
                val down = Thread {
                    ProxyTunnel.pump(t.input, client.getOutputStream()) {
                        runCatching { client.shutdownOutput() }
                    }
                }
                up.start()
                down.start()
                up.join()
                down.join()
            } catch (_: InterruptedException) {
            } catch (e: ProxyTunnel.TunnelException) {
                reportError(port, e.message)
            } catch (_: Exception) {
            } finally {
                runCatching { tunnel?.socket?.close() }
                runCatching { client.close() }
                tunnel?.socket?.let { activeSockets.remove(it) }
            }
        }
    }

    private val mappers = CopyOnWriteArrayList<PortMapper>()
    private var runningConfig: MappingConfig? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        lastErrors = emptyMap()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfg = ConfigStore.load(this)
        val proxy = MappingConfig.parseProxy(cfg.proxyUrl)
        val host = MappingConfig.parseHost(cfg.targetHost)?.first

        // Android 12+ 要求 startForegroundService 后必须尽快 startForeground，
        // 即使配置无效也要先进入前台再自行退出
        startForeground(
            NOTIFICATION_ID,
            buildNotification(cfg, proxy, host),
            if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0
        )

        val error = MappingConfig.validate(cfg)
        if (error != null || proxy == null || host == null) {
            if (error != null) lastErrors = mapOf(-1 to error)
            stopSelf()
            return START_NOT_STICKY
        }

        // 已在运行：配置没变则维持现状，配置变了则全部重启
        if (mappers.isNotEmpty()) {
            if (runningConfig == cfg) return START_STICKY
            shutdownAll()
        }

        // 每个端口启动一个独立 PortMapper
        val errors = mutableMapOf<Int, String>()
        for (port in cfg.ports) {
            val mapper = PortMapper(port, proxy, host, cfg.proxyUser, cfg.proxyPassword, cfg.lanAccess)
            val err = mapper.start()
            if (err != null) {
                errors[port] = err
            } else {
                mappers.add(mapper)
            }
        }
        lastErrors = errors
        runningConfig = cfg
        return START_STICKY
    }

    private fun reportError(port: Int, message: String?) {
        message ?: return
        lastErrors = lastErrors.toMutableMap().apply { put(port, message) }
    }

    private fun shutdownAll() {
        mappers.forEach { it.stop() }
        mappers.clear()
    }

    override fun onDestroy() {
        shutdownAll()
        runningConfig = null
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(
        cfg: MappingConfig,
        proxy: Triple<String, Int, Boolean>?,
        host: String?
    ): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val portsText = cfg.ports.joinToString(",")
        val bindText = if (cfg.lanAccess) "0.0.0.0" else "127.0.0.1"
        val proxyText = proxy?.let { (h, p, tls) ->
            "（经 ${if (tls) "https" else "http"} 代理 $h:$p）"
        } ?: ""
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("$bindText:$portsText → ${host ?: "-"}:$portsText$proxyText")
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setForegroundServiceBehavior(androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
    }
}
