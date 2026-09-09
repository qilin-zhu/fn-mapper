package com.example.fnmapper

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.fnmapper.databinding.ActivitySettingsBinding
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var fetching = false

    /** 当前端口列表（含服务名；省略号折叠显示的完整数据），输入框文本只是它的展示。 */
    private var currentPorts: List<PortInfo> = emptyList()

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@registerForActivityResult // 用户取消扫码
        handleQrContent(content)
    }

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val ports = result.data?.getIntegerArrayListExtra(PortEditorActivity.EXTRA_PORTS)
            ?: return@registerForActivityResult
        val names = result.data?.getStringArrayListExtra(PortEditorActivity.EXTRA_NAMES)
            ?: arrayListOf()
        currentPorts = ports.mapIndexed { i, p -> PortInfo(p, names.getOrElse(i) { "" }) }
        binding.inputPorts.setText(displayPorts(currentPorts))
        binding.tilPorts.error = null
        Snackbar.make(binding.root, R.string.port_editor_applied, Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 右上角「关于」图标（外圈圆形 + 感叹号）
        binding.toolbar.inflateMenu(R.menu.menu_settings)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_about) {
                showAboutDialog()
                true
            } else false
        }

        val cfg = ConfigStore.load(this)
        binding.inputProxyUrl.setText(cfg.proxyUrl)
        binding.inputProxyUser.setText(cfg.proxyUser)
        binding.inputProxyPassword.setText(cfg.proxyPassword)
        binding.inputTarget.setText(cfg.targetHost)
        currentPorts = cfg.ports.ifEmpty { listOf(MappingConfig.DEFAULT_PORT) }
            .map { PortInfo(it, cfg.portNames[it].orEmpty()) }
        binding.inputPorts.setText(displayPorts(currentPorts))
        binding.inputScannerUrl.setText(cfg.scannerUrl)

        binding.btnSave.setOnClickListener { save() }
        binding.btnScanQr.setOnClickListener { launchQrScan() }
        binding.btnFetchPorts.setOnClickListener { fetchFromInput() }
        binding.tilPorts.setEndIconOnClickListener { openPortEditor() }
    }

    // ------------------------------------------------------------------
    // 关于
    // ------------------------------------------------------------------

    /** 关于对话框：图标、名称、版本号，GitHub 仓库与作者点击跳转浏览器。 */
    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        view.findViewById<android.widget.TextView>(R.id.aboutVersion).text = "v$versionName"
        view.findViewById<android.view.View>(R.id.rowGithub).setOnClickListener {
            openLink(getString(R.string.about_github_link))
        }
        view.findViewById<android.view.View>(R.id.rowAuthor).setOnClickListener {
            openLink(getString(R.string.about_author_link))
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    /** 用系统浏览器打开链接。 */
    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    // ------------------------------------------------------------------
    // 端口列表：折叠显示与全屏编辑器
    // ------------------------------------------------------------------

    /** 超过 5 个端口时只显示前 5 个，其余用省略号表示（完整列表保存在 [currentPorts]）。 */
    private fun displayPorts(ports: List<PortInfo>): String {
        val nums = ports.map { it.port }
        return if (nums.size > 5) nums.take(5).joinToString(", ") + ", …"
        else nums.joinToString(", ")
    }

    /** 打开全屏端口编辑页（fnos-scanner 同款深色列表，显示每个端口对应的服务）。 */
    private fun openPortEditor() {
        val intent = Intent(this, PortEditorActivity::class.java).apply {
            putIntegerArrayListExtra(PortEditorActivity.EXTRA_PORTS, ArrayList(currentPorts.map { it.port }))
            putStringArrayListExtra(PortEditorActivity.EXTRA_NAMES, ArrayList(currentPorts.map { it.name }))
        }
        editorLauncher.launch(intent)
    }

    /**
     * 输入框文本 → 端口列表。文本与折叠显示一致（含省略号）时直接用完整列表；
     * 否则视为手动输入，按逗号分隔解析（容错去掉省略号）。
     */
    private fun portsFromUi(): List<Int> {
        val text = binding.inputPorts.text?.toString()?.trim().orEmpty()
        if (currentPorts.isNotEmpty() && text == displayPorts(currentPorts)) {
            return currentPorts.map { it.port }
        }
        if (text.isEmpty()) return emptyList()
        val cleaned = text.replace("…", ",").replace("...", ",")
        return MappingConfig.parsePorts(cleaned) ?: emptyList()
    }

    // ------------------------------------------------------------------
    // 从 fnos-scanner 获取端口列表
    // ------------------------------------------------------------------

    private fun launchQrScan() {
        qrLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.qr_prompt))
                setBeepEnabled(false)
                setOrientationLocked(true)
            }
        )
    }

    /** 二维码内容：URL → 填入并拉取；JSON → 直接解析；其他 → 按端口列表解析。 */
    private fun handleQrContent(content: String) {
        val trimmed = content.trim()
        when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> {
                binding.inputScannerUrl.setText(trimmed)
                binding.tilScannerUrl.error = null
                fetchPortsFrom(trimmed)
            }
            trimmed.startsWith("{") -> {
                try {
                    fillPorts(PortsFetcher.parsePortsJson(trimmed))
                } catch (e: Exception) {
                    showFetchError(e.message)
                }
            }
            else -> {
                val ports = MappingConfig.parsePorts(trimmed)
                if (ports != null) fillPorts(ports.map { PortInfo(it, "") })
                else Snackbar.make(binding.root, R.string.qr_unrecognized, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchFromInput() {
        val url = binding.inputScannerUrl.text?.toString().orEmpty()
        if (url.isBlank()) {
            binding.tilScannerUrl.error = getString(R.string.fetch_empty_url)
            return
        }
        binding.tilScannerUrl.error = null
        fetchPortsFrom(url)
    }

    private fun fetchPortsFrom(url: String) {
        if (fetching) return
        fetching = true
        binding.btnFetchPorts.isEnabled = false
        binding.btnScanQr.isEnabled = false

        // 主线程先取好表单值，再进后台线程
        val proxyStr = binding.inputProxyUrl.text?.toString().orEmpty()
        val user = binding.inputProxyUser.text?.toString().orEmpty()
        val password = binding.inputProxyPassword.text?.toString().orEmpty()

        Thread {
            val proxy = MappingConfig.parseProxy(proxyStr)
            val result = runCatching { PortsFetcher.fetch(url, proxy, user, password) }
            runOnUiThread {
                fetching = false
                binding.btnFetchPorts.isEnabled = true
                binding.btnScanQr.isEnabled = true
                result.onSuccess { fillPorts(it) }
                    .onFailure { showFetchError(it.message) }
            }
        }.start()
    }

    private fun fillPorts(fetched: List<PortInfo>) {
        // 扫码/在线获取后默认并入本机端口 5666、5667（获取结果优先）
        val map = LinkedHashMap(MappingConfig.DEFAULT_PORT_NAMES)
        fetched.forEach { if (it.port in 1..65535) map[it.port] = it.name }
        currentPorts = map.keys.sorted().map { PortInfo(it, map[it].orEmpty()) }
        binding.inputPorts.setText(displayPorts(currentPorts))
        binding.tilPorts.error = null
        Snackbar.make(
            binding.root,
            getString(R.string.fetch_ok, currentPorts.size),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun showFetchError(message: String?) {
        Snackbar.make(
            binding.root,
            getString(R.string.fetch_fail, message ?: "未知错误"),
            Snackbar.LENGTH_LONG
        ).show()
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        val targetInput = binding.inputTarget.text?.toString().orEmpty()
        // 兼容旧写法：地址里带端口时拆出来并入端口列表
        val parsedHost = MappingConfig.parseHost(targetInput)
        val hostPart = parsedHost?.first ?: targetInput
        val embeddedPort = parsedHost?.second
        val ports = portsFromUi()
            .ifEmpty { embeddedPort?.let { listOf(it) } ?: emptyList() }

        // 保留已知端口的服务名（手动输入的新端口无名字）
        val nameMap = currentPorts.associate { it.port to it.name }
        val portNames = ports.mapNotNull { p ->
            nameMap[p]?.takeIf { it.isNotEmpty() }?.let { p to it }
        }.toMap()

        val cfg = MappingConfig(
            proxyUrl = binding.inputProxyUrl.text?.toString().orEmpty(),
            proxyUser = binding.inputProxyUser.text?.toString().orEmpty(),
            proxyPassword = binding.inputProxyPassword.text?.toString().orEmpty(),
            targetHost = hostPart,
            ports = ports,
            scannerUrl = binding.inputScannerUrl.text?.toString().orEmpty(),
            portNames = portNames,
            lanAccess = ConfigStore.load(this).lanAccess // 局域网开关只在首页切换，设置页保存时保留现值
        )

        // 逐项校验并定位到出错输入框
        fun TextInputLayout.setErrorIf(bad: Boolean, msg: String): Boolean {
            error = if (bad) msg else null
            return bad
        }
        var bad = false
        bad = binding.tilProxyUrl.setErrorIf(
            MappingConfig.parseProxy(cfg.proxyUrl) == null,
            getString(R.string.err_proxy_url)
        ) || bad
        bad = binding.tilTarget.setErrorIf(
            MappingConfig.parseHost(cfg.targetHost) == null,
            getString(R.string.err_target)
        ) || bad
        bad = binding.tilPorts.setErrorIf(
            cfg.ports.isEmpty(),
            getString(R.string.err_ports)
        ) || bad

        if (bad) {
            Snackbar.make(binding.root, R.string.err_form, Snackbar.LENGTH_SHORT).show()
            return
        }

        ConfigStore.save(this, cfg)
        // 配置变更后需重启映射服务才能生效
        if (MappingService.isRunning) {
            MappingService.stop(this)
            MappingService.start(this)
        }
        Snackbar.make(binding.root, R.string.saved, Snackbar.LENGTH_SHORT)
            .setAction(R.string.done) { finish() }
            .show()
    }
}
