package com.exio.inkleaf.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only manual entry point for #50 JavaScriptEngine device validation. */
class JavaScriptEngineDiagnosticActivity : AppCompatActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val callbacks: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private lateinit var reportView: TextView
    private lateinit var safeButton: Button
    private lateinit var terminationButton: Button
    private lateinit var heapButton: Button
    private lateinit var copyButton: Button
    private val reportSections = mutableListOf<String>()
    private val runner by lazy { JavaScriptEngineDiagnosticRunner(applicationContext, callbacks) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "JavaScriptEngine diagnostics"
        setContentView(createContent())
        runSafeProbe()
    }

    override fun onDestroy() {
        runner.close()
        worker.shutdownNow()
        callbacks.shutdownNow()
        super.onDestroy()
    }

    private fun createContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val description = TextView(this).apply {
            text =
                "#50 诊断入口\n安全探针会自动运行。终止探针和 heap 探针会主动结束 JS 或 sandbox，只在需要时点击。"
        }
        root.addView(description, matchWrap())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        safeButton = Button(this).apply {
            text = "重新运行安全探针"
            setOnClickListener { runSafeProbe() }
        }
        terminationButton = Button(this).apply {
            text = "运行终止与恢复探针"
            setOnClickListener { runTerminationProbe() }
        }
        heapButton = Button(this).apply {
            text = "运行 heap / sandbox 探针"
            setOnClickListener { confirmHeapProbe() }
        }
        copyButton = Button(this).apply {
            text = "复制报告"
            setOnClickListener { copyReport() }
        }
        actions.addView(safeButton, matchWrap())
        actions.addView(terminationButton, matchWrap())
        actions.addView(heapButton, matchWrap())
        actions.addView(copyButton, matchWrap())
        root.addView(actions, matchWrap())

        reportView = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(12, 12, 12, 12)
            text = "等待安全探针..."
        }
        val scroll = ScrollView(this).apply { addView(reportView) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return root
    }

    private fun runSafeProbe() {
        runProbe("safe") { runner.runSafeProbe() }
    }

    private fun runTerminationProbe() {
        runProbe("termination") { runner.runTerminationProbe() }
    }

    private fun confirmHeapProbe() {
        AlertDialog.Builder(this)
            .setTitle("确认运行 heap 探针？")
            .setMessage("它会让 JS 分配内存，可能终止整个 JavaScript sandbox，但不应终止 Inkleaf 主进程。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> runProbe("heap") { runner.runHeapProbe() } }
            .show()
    }

    private fun runProbe(name: String, operation: () -> List<String>) {
        if (!running.compareAndSet(false, true)) return
        setButtonsEnabled(false)
        worker.execute {
            val section = try {
                operation().joinToString("\n")
            } catch (error: Throwable) {
                "probe=$name\nunhandled=${error::class.java.simpleName}: ${error.message}"
            }
            runOnUiThread {
                reportSections += section
                reportView.text = reportSections.joinToString("\n\n")
                running.set(false)
                setButtonsEnabled(true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        safeButton.isEnabled = enabled
        terminationButton.isEnabled = enabled
        heapButton.isEnabled = enabled
        copyButton.isEnabled = enabled
    }

    private fun copyReport() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("JavaScriptEngine diagnostics", reportView.text))
        Toast.makeText(this, "报告已复制", Toast.LENGTH_SHORT).show()
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
}
