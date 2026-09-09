package com.example.fnmapper

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.fnmapper.databinding.ActivityPortEditorBinding
import com.example.fnmapper.databinding.ItemPortEntryBinding
import com.google.android.material.snackbar.Snackbar

/**
 * 全屏端口列表编辑页（fnos-scanner 同款深色风格）。
 *
 * 每行显示：端口号 + 服务名（应用名 / Docker 镜像短名 / 本机端口固定名称）+ 删除按钮。
 * - 点行 → 进入编辑模式（底部输入框带出原端口，按钮变「保存」）
 * - 点行右侧删除图标 → 删除该端口
 * - 底部输入 + 「添加」→ 新增端口
 * 工具栏「完成」回传完整列表给 SettingsActivity；返回键取消。
 */
class PortEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPortEditorBinding
    private val entries = mutableListOf<PortInfo>()

    /** 正在编辑的端口；null 表示当前处于新增模式 */
    private var editingPort: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPortEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val portList = intent.getIntegerArrayListExtra(EXTRA_PORTS) ?: arrayListOf()
        val nameList = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        entries.addAll(portList.mapIndexed { i, p -> PortInfo(p, nameList.getOrElse(i) { "" }) })

        binding.toolbar.setNavigationOnClickListener { finish() } // 返回 = 取消
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_done) {
                finishWithResult()
                true
            } else false
        }

        binding.btnAdd.setOnClickListener { confirmInput(addMode = editingPort == null) }
        binding.btnCancelEdit.setOnClickListener { cancelEdit() }
        binding.inputNewPort.setOnEditorActionListener { _, _, _ ->
            confirmInput(addMode = editingPort == null)
            true
        }

        renderRows()
    }

    private fun finishWithResult() {
        if (entries.isEmpty()) {
            Snackbar.make(binding.root, R.string.port_editor_none, Snackbar.LENGTH_SHORT).show()
            return
        }
        val data = Intent().apply {
            putIntegerArrayListExtra(EXTRA_PORTS, ArrayList(entries.map { it.port }))
            putStringArrayListExtra(EXTRA_NAMES, ArrayList(entries.map { it.name }))
        }
        setResult(RESULT_OK, data)
        finish()
    }

    // ------------------------------------------------------------------
    // 行渲染
    // ------------------------------------------------------------------

    private fun renderRows() {
        binding.listContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        entries.forEach { info ->
            val row = ItemPortEntryBinding.inflate(inflater, binding.listContainer, true)
            row.pnum.text = info.port.toString()
            row.pname.text = info.name.ifEmpty { getString(R.string.port_editor_manual) }
            row.rowRoot.setBackgroundResource(
                if (info.port == editingPort) R.drawable.bg_row_active else R.drawable.bg_row_normal
            )
            row.rowRoot.setOnClickListener { startEdit(info.port) }
            row.btnDelete.setOnClickListener { deletePort(info.port) }
        }
        binding.badgeCount.text = entries.size.toString()
        binding.emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // 增 / 改 / 删
    // ------------------------------------------------------------------

    /** 点行进入编辑模式；再点同一行取消编辑。 */
    private fun startEdit(port: Int) {
        if (editingPort == port) {
            cancelEdit()
            return
        }
        editingPort = port
        binding.inputNewPort.setText(port.toString())
        binding.inputNewPort.hint = getString(R.string.port_editor_hint_edit)
        binding.inputNewPort.requestFocus()
        binding.inputNewPort.setSelection(binding.inputNewPort.text?.length ?: 0)
        binding.btnAdd.text = getString(R.string.port_editor_save)
        binding.btnCancelEdit.visibility = View.VISIBLE
        renderRows()
    }

    /** 退出编辑模式回到新增模式。 */
    private fun cancelEdit() {
        editingPort = null
        binding.inputNewPort.setText("")
        binding.inputNewPort.hint = getString(R.string.port_editor_hint_new)
        binding.btnAdd.text = getString(R.string.port_editor_add)
        binding.btnCancelEdit.visibility = View.GONE
        renderRows()
    }

    /** 确认底部输入框内容：新增模式添加端口，编辑模式替换原端口（保留服务名）。 */
    private fun confirmInput(addMode: Boolean) {
        val text = binding.inputNewPort.text?.toString()?.trim().orEmpty()
        val n = text.toIntOrNull()
        if (n == null || n !in 1..65535) {
            toast(getString(R.string.port_editor_bad_number))
            return
        }
        if (addMode) {
            if (entries.any { it.port == n }) {
                toast(getString(R.string.port_editor_dup, n))
                return
            }
            entries.add(PortInfo(n, ""))
            entries.sortBy { it.port }
            binding.inputNewPort.setText("")
            renderRows()
            scrollRowIntoView(n)
        } else {
            val old = editingPort
            if (old != null && n != old && entries.any { it.port == n }) {
                toast(getString(R.string.port_editor_dup, n))
                return
            }
            val oldName = entries.firstOrNull { it.port == old }?.name.orEmpty()
            if (old != null) entries.removeAll { it.port == old }
            entries.add(PortInfo(n, oldName))
            entries.sortBy { it.port }
            cancelEdit()
            scrollRowIntoView(n)
        }
    }

    private fun deletePort(port: Int) {
        if (editingPort == port) cancelEdit()
        entries.removeAll { it.port == port }
        renderRows()
    }

    /** 滚动让新加/编辑的行可见。 */
    private fun scrollRowIntoView(port: Int) {
        binding.portListScroll.post {
            val index = entries.indexOfFirst { it.port == port }
            val child = if (index >= 0) binding.listContainer.getChildAt(index) else null
            if (child != null) binding.portListScroll.smoothScrollTo(0, child.top)
        }
    }

    private fun toast(msg: String) = Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PORTS = "ports"
        const val EXTRA_NAMES = "names"
    }
}
