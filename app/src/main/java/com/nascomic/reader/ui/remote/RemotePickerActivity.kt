package com.nascomic.reader.ui.remote

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.R
import com.nascomic.reader.core.text.NaturalSort
import com.nascomic.reader.databinding.ActivityRemotePickerBinding
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.smb.SmbClient
import com.nascomic.reader.smb.SmbTarget
import com.nascomic.reader.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选书库目录：连上远端之后，让用户在远端目录树里点到自己要的那一层。
 *
 * 地址栏只填服务器，具体是哪个文件夹在这里选——不在地址里让用户手打路径。
 * 只列文件夹；点进去往下，左上角或系统返回退上一层。
 * 选定后把这一层的地址回传给调用方，由调用方登记来源并扫描。
 */
class RemotePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        /** WebDAV 传完整 URL（含协议与端口，可带起始路径）；SMB 传 smb://主机[:端口]/共享[/路径] */
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_PASSWORD = "password"
        /** 回传：选定这一层的地址。WebDAV 是 URL，SMB 是完整 smb:// 地址 */
        const val EXTRA_PICKED = "picked"
    }

    private lateinit var binding: ActivityRemotePickerBinding

    private var kind = SourceEntity.KIND_WEBDAV
    private var account: String? = null
    private var password: String? = null

    private var dav: WebDavClient? = null
    private var smb: SmbClient? = null
    private var smbTarget: SmbTarget? = null
    private var smbShareRoot = ""

    /** 走过的每一层，最后一个就是当前层。退上一层 = 弹一个 */
    private val trail = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemotePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        kind = intent.getIntExtra(EXTRA_KIND, SourceEntity.KIND_WEBDAV)
        account = intent.getStringExtra(EXTRA_ACCOUNT)?.takeIf { it.isNotBlank() }
        password = intent.getStringExtra(EXTRA_PASSWORD)

        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        if (endpoint.isBlank() || !start(endpoint)) {
            finish()
            return
        }

        binding.back.setOnClickListener { up() }
        binding.useThis.setOnClickListener { pick() }
        applyInsets()
        load()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.pickerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.pickerRoot.updatePadding(top = bars.top)
            val lp = binding.useThis.layoutParams as LinearLayout.LayoutParams
            lp.bottomMargin = dp(16) + bars.bottom
            binding.useThis.layoutParams = lp
            insets
        }
    }

    /** 记下起点，之后都在这个起点之下走 */
    private fun start(endpoint: String): Boolean {
        if (kind == SourceEntity.KIND_SMB) {
            val target = runCatching { SmbTarget.parse(endpoint) }.getOrNull() ?: return false
            smbTarget = target
            smbShareRoot = if (target.port == SmbTarget.DEFAULT_PORT) {
                "smb://${target.host}/${target.share}"
            } else {
                "smb://${target.host}:${target.port}/${target.share}"
            }
            trail.addLast(target.path)
        } else {
            trail.addLast(endpoint.trimEnd('/'))
        }
        return true
    }

    private fun load() {
        val where = trail.last()
        binding.loading.isVisible = true
        binding.hint.isVisible = false
        binding.list.removeAllViews()
        binding.path.text = if (where.isEmpty()) getString(R.string.picker_root) else where

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { listFolders(where) } }
            // 列目录期间用户可能已经退层了，回来时对不上就别画
            if (trail.lastOrNull() != where) return@launch

            binding.loading.isVisible = false
            result.onSuccess { folders ->
                if (folders.isEmpty()) {
                    binding.hint.text = getString(R.string.picker_empty)
                    binding.hint.isVisible = true
                } else {
                    binding.scroll.scrollTo(0, 0)
                    folders.forEach { binding.list.addView(row(it)) }
                }
            }.onFailure { e ->
                binding.hint.text =
                    getString(R.string.picker_failed, remoteErrorText(this@RemotePickerActivity, e))
                binding.hint.isVisible = true
            }
        }
    }

    /** 一个文件夹：显示名 + 进入它时用的 key（WebDAV 是 URL，SMB 是共享内路径） */
    private class Folder(val name: String, val key: String)

    private fun listFolders(where: String): List<Folder> {
        val folders = if (kind == SourceEntity.KIND_SMB) {
            val target = smbTarget ?: return emptyList()
            val client = smb ?: SmbClient(target, account, password).also { smb = it }
            client.list(where)
                .filter { it.isDirectory }
                .map { Folder(it.name, it.path) }
        } else {
            val client = dav ?: WebDavClient(account, password).also { dav = it }
            client.list(where)
                .filter { it.isDirectory }
                .map { Folder(it.name, it.url.trimEnd('/')) }
        }
        return folders.sortedBy { NaturalSort.key(it.name) }
    }

    private fun row(folder: Folder): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
            isClickable = true
            // 点下去的反馈用系统的选择态，别自己画
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
        }
        item.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_folder)
                imageTintList = ColorStateList.valueOf(getColor(R.color.shell_ink_muted))
            },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) },
        )
        item.addView(
            TextView(this).apply {
                text = folder.name
                textSize = 15f
                setTextColor(getColor(R.color.shell_ink))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        item.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(getColor(R.color.shell_stroke))
            },
            LinearLayout.LayoutParams(dp(16), dp(16)),
        )
        item.setOnClickListener {
            trail.addLast(folder.key)
            load()
        }

        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(item)
        wrapper.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.shell_divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
        )
        return wrapper
    }

    private fun up() {
        if (trail.size <= 1) {
            finish()
            return
        }
        trail.removeLast()
        load()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        up()
    }

    /** 当前这一层当书库根，回传给调用方 */
    private fun pick() {
        val where = trail.last()
        val result = if (kind == SourceEntity.KIND_SMB) {
            buildString {
                append(smbShareRoot)
                if (where.isNotEmpty()) append('/').append(where)
            }
        } else {
            where
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PICKED, result))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { smb?.close() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
