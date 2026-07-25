package com.exio.inkleaf.debug

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.plugin.PluginActionRequest
import com.exio.inkleaf.plugin.PluginContentCodec
import com.exio.inkleaf.plugin.PluginSearchRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Debug-only manual entry for installing and exercising a real .inkleaf-plugin package. */
class PluginDiagnosticActivity : AppCompatActivity() {
    private lateinit var report: TextView
    private lateinit var urlField: EditText
    private var selectedPluginId: String? = null

    private val openPackage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) installUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Plugin diagnostics"
        setContentView(createContent())
        refresh()
    }

    private fun createContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "安装、启用和调用真实 .inkleaf-plugin。运行时能力缺失时会 fail-closed。"
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "选择本地插件包"
            setOnClickListener { openPackage.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        }, matchWrap())
        urlField = EditText(this).apply {
            hint = "插件 URL（HTTP/HTTPS）"
            singleLine = true
        }
        root.addView(urlField, matchWrap())
        root.addView(Button(this).apply {
            text = "从 URL 安装并启用"
            setOnClickListener { installUrl(urlField.text.toString()) }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "刷新插件状态"
            setOnClickListener { refresh() }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "调用选中插件 describe"
            setOnClickListener { describeSelected() }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "调用选中插件 search fixture"
            setOnClickListener { searchSelected() }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "调用选中插件 host-smoke action"
            setOnClickListener { hostSmokeSelected() }
        }, matchWrap())
        report = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(12, 12, 12, 12)
        }
        root.addView(ScrollView(this).apply { addView(report) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return root
    }

    private fun refresh() {
        lifecycleScope.launch {
            val plugins = try {
                withContext(Dispatchers.IO) { app().pluginManager.installed() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toast(error.message ?: "读取插件状态失败")
                return@launch
            }
            selectedPluginId = plugins.firstOrNull()?.state?.pluginId
            report.text = if (plugins.isEmpty()) {
                "没有已安装插件。"
            } else {
                plugins.joinToString("\n\n") { plugin ->
                    "${plugin.state.pluginId}\n" +
                        "active=${plugin.state.activeVersion}\n" +
                        "disabled=${plugin.state.disabled}\n" +
                        "health=${plugin.state.health}\n" +
                        "versions=${plugin.state.versions.joinToString { it.version }}"
                }
            }
        }
    }

    private fun installUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching { app().pluginManager.installUri(uri, activate = true) }
                .onSuccess { result ->
                    toast("${result.status}: ${result.pluginId ?: "unknown"}@${result.version ?: "unknown"}")
                    refresh()
                }
                .onFailure { error ->
                    error.rethrowCancellation()
                    toast(error.message ?: "安装失败")
                }
        }
    }

    private fun installUrl(url: String) {
        if (url.isBlank()) {
            toast("请输入插件 URL")
            return
        }
        lifecycleScope.launch {
            runCatching {
                app().pluginManager.installUrl(
                    com.exio.inkleaf.plugin.PluginDownloadSource(url),
                    activate = true,
                )
            }.onSuccess { result ->
                toast("${result.status}: ${result.pluginId ?: "unknown"}@${result.version ?: "unknown"}")
                refresh()
            }.onFailure { error ->
                error.rethrowCancellation()
                toast(error.message ?: "下载失败")
            }
        }
    }

    private fun describeSelected() {
        val pluginId = selectedPluginId ?: run { toast("没有选中插件"); return }
        lifecycleScope.launch {
            runCatching { app().pluginCatalog.describe(pluginId) }
                .onSuccess { descriptor -> report.text = PluginContentCodec.json.encodeToString(descriptor) }
                .onFailure { error ->
                    error.rethrowCancellation()
                    toast(error.message ?: "describe 失败")
                }
        }
    }

    private fun searchSelected() {
        val pluginId = selectedPluginId ?: run { toast("没有选中插件"); return }
        lifecycleScope.launch {
            runCatching {
                app().pluginCatalog.search(
                    PluginSearchRequest(query = "fixture", limit = 20),
                    listOf(pluginId),
                ).single()
            }.onSuccess { result ->
                report.text = result.page?.let { PluginContentCodec.json.encodeToString(it) }
                    ?: "error=${result.error}"
            }.onFailure { error ->
                error.rethrowCancellation()
                toast(error.message ?: "search 失败")
            }
        }
    }

    private fun hostSmokeSelected() {
        val pluginId = selectedPluginId ?: run { toast("没有选中插件"); return }
        lifecycleScope.launch {
            runCatching {
                app().pluginCatalog.invokeAction(
                    pluginId,
                    PluginActionRequest("host-smoke"),
                )
            }.onSuccess { result ->
                report.text = result.toString()
            }.onFailure { error ->
                error.rethrowCancellation()
                toast(error.message ?: "host-smoke 失败")
            }
        }
    }

    private fun app() = application as InkleafApplication

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun Throwable.rethrowCancellation() {
        if (this is CancellationException) throw this
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
