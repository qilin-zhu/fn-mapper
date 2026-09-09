package com.example.fnmapper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.fnmapper.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var config: MappingConfig = MappingConfig("", "", "", "", listOf(MappingConfig.DEFAULT_PORT))
    private var hintExpanded = false // 运行日志是否展开（点「详情」切换）

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 渐变背景顶部较深，状态栏图标用白色
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        // 右上角齿轮 → 设置页（图标强制白色，渐变背景上可见）
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.menu.findItem(R.id.action_settings)?.icon?.mutate()
            ?.setTint(android.graphics.Color.WHITE)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else false
        }

        binding.btnToggle.setOnClickListener { toggleService() }
        binding.btnToggleFnvideo.setOnClickListener { toggleService() }
        binding.btnToggleFnmusic.setOnClickListener { toggleService() }
        binding.btnLan.setOnClickListener { toggleLanAccess() }
        binding.btnLanFnvideo.setOnClickListener { toggleLanAccess() }
        binding.btnLanFnmusic.setOnClickListener { toggleLanAccess() }
        binding.btnOpenFnos.setOnClickListener { openFnosApp() }
        binding.btnOpenFnvideo.setOnClickListener { openApp(FNVIDEO_PACKAGE, R.string.fnvideo_not_installed) }
        binding.btnOpenFnmusic.setOnClickListener { openApp(FNMUSIC_PACKAGE, R.string.fnmusic_not_installed) }
        binding.btnDetails.setOnClickListener {
            hintExpanded = !hintExpanded
            binding.hintText.visibility = if (hintExpanded) View.VISIBLE else View.GONE
        }
        binding.btnDetailsFnvideo.setOnClickListener {
            hintExpanded = !hintExpanded
            binding.hintTextFnvideo.visibility = if (hintExpanded) View.VISIBLE else View.GONE
        }
        binding.btnDetailsFnmusic.setOnClickListener {
            hintExpanded = !hintExpanded
            binding.hintTextFnmusic.visibility = if (hintExpanded) View.VISIBLE else View.GONE
        }

        // 页面宽度 = 屏幕宽度（HorizontalScrollView 内三页才能正好各占一屏，参考 android-launcher）
        // 布局顺序 [飞牛影视][映射][飞牛音乐]：默认停在第 2 屏（映射页），右滑到飞牛影视，左滑到飞牛音乐
        binding.pageScroller.post {
            val pageWidth = binding.pageScroller.width
            binding.pageMapping.layoutParams.width = pageWidth
            binding.pageFnvideo.layoutParams.width = pageWidth
            binding.pageFnmusic.layoutParams.width = pageWidth
            binding.pageMapping.requestLayout()
            binding.pageFnvideo.requestLayout()
            binding.pageFnmusic.requestLayout()
            // 等布局完成后定位到映射页
            binding.pageScroller.post { binding.pageScroller.scrollX = binding.pageFnvideo.width }
        }

        // 上次开启过映射：打开 App 自动恢复（仅在进程新建时触发一次，避免从设置页返回时误启动）
        if (ConfigStore.isAutoStart(this) && !MappingService.isRunning) {
            config = ConfigStore.load(this)
            if (MappingConfig.validate(config) == null) {
                MappingService.start(this)
                // startForegroundService 是异步的，等服务起来后再刷新状态
                binding.root.postDelayed({ refreshUi() }, 400)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        config = ConfigStore.load(this)
        refreshUi()
        // Android 13+ 前台服务通知需要通知权限（未授权时服务仍可运行，仅通知不显示）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 打开飞牛 App（未安装时提示）。 */
    private fun openFnosApp() {
        openApp(FNOS_PACKAGE, R.string.fnos_not_installed)
    }

    /** 打开指定包名的 App（未安装时提示）。 */
    private fun openApp(packageName: String, notInstalledRes: Int) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Snackbar.make(binding.root, notInstalledRes, Snackbar.LENGTH_LONG).show()
            return
        }
        startActivity(intent)
    }

    private fun toggleService() {
        if (MappingService.isRunning) {
            ConfigStore.setAutoStart(this, false) // 手动停止：下次不再自动开启
            MappingService.stop(this)
            // stopService 是异步的，稍后刷新
            binding.root.postDelayed({ refreshUi() }, 200)
            return
        }
        val error = MappingConfig.validate(config)
        if (error != null) {
            binding.hintText.text = error
            return
        }
        ConfigStore.setAutoStart(this, true) // 开启：记住状态供下次自动恢复
        MappingService.start(this)
        binding.root.postDelayed({ refreshUi() }, 400)
    }

    /**
     * 切换局域网访问：开启后端口监听 0.0.0.0（同网段设备可通过手机 IP 访问），
     * 关闭时仅本机 127.0.0.1。映射运行中会自动重启服务以重新绑定监听地址。
     */
    private fun toggleLanAccess() {
        val newCfg = config.copy(lanAccess = !config.lanAccess)
        ConfigStore.save(this, newCfg)
        config = newCfg

        if (MappingService.isRunning && MappingConfig.validate(newCfg) == null) {
            // 重启服务：onStartCommand 检测到配置变化会关闭旧监听、按新地址重新绑定
            MappingService.stop(this)
            MappingService.start(this)
            binding.root.postDelayed({ refreshUi() }, 400)
        } else {
            refreshUi()
        }
    }

    /**
     * 获取手机局域网 IPv4 地址（多个时优先 192.168 开头，其次 10 开头）。
     * 取不到（如未连 WiFi）返回 null。
     */
    private fun getLanIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .sortedWith(
                compareByDescending<String> { it.startsWith("192.168.") }
                    .thenByDescending { it.startsWith("10.") }
            )
            .firstOrNull()
    }.getOrNull()

    private fun refreshUi() {
        config = ConfigStore.load(this)
        val running = MappingService.isRunning
        val configured = MappingConfig.validate(config) == null

        // 状态前缀：局域网访问开启显示手机局域网 IP（优先 192.168 开头），否则显示 127.0.0.1
        val lanIp = getLanIp()
        val statusPrefix = if (config.lanAccess) lanIp ?: "0.0.0.0" else "127.0.0.1"

        // 三页共用同一状态
        val dot = if (running) R.drawable.dot_on else R.drawable.dot_off
        val statusTextRes = if (running) R.string.status_running else R.string.status_stopped
        val statusText = "$statusPrefix ${getString(statusTextRes)}"
        val toggleTextRes = if (running) R.string.btn_stop else R.string.btn_start
        val lanTextRes = if (config.lanAccess) R.string.btn_lan_disable else R.string.btn_lan_enable

        binding.statusDot.setImageResource(dot)
        binding.statusText.text = statusText
        binding.btnToggle.setText(toggleTextRes)
        binding.btnLan.setText(lanTextRes)

        binding.statusDotFnvideo.setImageResource(dot)
        binding.statusTextFnvideo.text = statusText
        binding.btnToggleFnvideo.setText(toggleTextRes)
        binding.btnLanFnvideo.setText(lanTextRes)

        binding.statusDotFnmusic.setImageResource(dot)
        binding.statusTextFnmusic.text = statusText
        binding.btnToggleFnmusic.setText(toggleTextRes)
        binding.btnLanFnmusic.setText(lanTextRes)

        // 未配置时给出引导提示；运行出错时显示最近错误（按端口汇总）
        val errs = MappingService.lastErrors
        val hint = when {
            !configured -> getString(R.string.hint_not_configured)
            errs.isNotEmpty() -> getString(
                R.string.hint_error,
                errs.entries.joinToString("；") { "${it.key}: ${it.value}" }
            )
            else -> ""
        }
        binding.hintText.text = hint
        binding.hintTextFnvideo.text = hint
        binding.hintTextFnmusic.text = hint
    }

    companion object {
        /** 飞牛 App 包名（来自 飞牛-1.35.2.apk） */
        const val FNOS_PACKAGE = "com.trim.app"

        /** 飞牛影视播放器包名（来自 android-launcher 项目） */
        const val FNVIDEO_PACKAGE = "com.trim.media"

        /** 飞牛音乐包名（来自 FN_Music_1.0.0.apk） */
        const val FNMUSIC_PACKAGE = "com.trim.music"
    }
}
