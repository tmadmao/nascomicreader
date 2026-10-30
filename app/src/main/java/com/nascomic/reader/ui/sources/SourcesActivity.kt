package com.nascomic.reader.ui.sources

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivitySourcesBinding
import com.nascomic.reader.library.DavAddress
import com.nascomic.reader.library.RelativeTime
import com.nascomic.reader.library.SourceRepository
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.scan.LibraryScanner
import com.nascomic.reader.smb.SmbTarget
import com.nascomic.reader.ui.remote.RemotePickerActivity
import com.nascomic.reader.ui.remote.remoteErrorText
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书源管理。
 *
 * 交互照界面稿：上面三格选「要新加哪一类来源」，选中哪一格，底部主按钮就跟着换文案与图标；
 * 下面列出已有的书源，点一下选中它，底部按钮变成「更新选中书源」，长按改名或删掉。
 *
 * 三种来源都是真能连的：本机文件夹（SAF）、WebDAV、SMB。界面稿里的「云端网盘」「Wi-Fi 传输」
 * 暂时没有实现，这里就不摆空壳。
 */
class SourcesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourcesBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val repository by lazy { SourceRepository(this, db) }
    private val scanner by lazy { LibraryScanner(this, db) }

    /** 选中要新增哪一类；为 null 表示选中的是某个已有书源 */
    private var selectedKind: Int? = SourceEntity.KIND_LOCAL

    private var selectedSource: SourceEntity? = null
    private var sources: List<SourceEntity> = emptyList()
    private var bookCounts: Map<Long, Int> = emptyMap()

    /** 本次进页面后每个来源的同步结果：行内要报「新增几本」，不是「一共几本」 */
    private val lastSync = HashMap<Long, LibraryScanner.Result>()

    /** 来源列表的代次：界面重建后旧查询的结果要丢掉 */
    private var generation = 0

    /** 正在更新时不再接受第二次点击 */
    private var scanning = false

    /** 本地目录用系统的目录选择器：授权一次，之后一直能用 */
    private val pickLocalFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> if (uri != null) addLocalFolder(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15 起强制边到边：自己让开状态栏与导航条
        binding.root.padBySystemBars()

        binding.back.setOnClickListener { finish() }
        binding.typeLocal.setOnClickListener { selectKind(SourceEntity.KIND_LOCAL) }
        binding.typeWebdav.setOnClickListener { selectKind(SourceEntity.KIND_WEBDAV) }
        binding.typeSmb.setOnClickListener { selectKind(SourceEntity.KIND_SMB) }
        binding.action.setOnClickListener { runAction() }

        renderSelection()
        renderAction()
        loadSources()
    }

    override fun onResume() {
        super.onResume()
        // 从远端选目录页回来、或在别处改过来源，都重新读一次
        loadSources()
    }

    // ── 选中态 ─────────────────────────────────────────────

    private fun selectKind(kind: Int) {
        selectedKind = kind
        selectedSource = null
        renderSelection()
        renderAction()
    }

    private fun selectSource(source: SourceEntity) {
        selectedKind = null
        selectedSource = source
        renderSelection()
        renderAction()
        renderList()
    }

    /** 三格卡片的选中样式：橙描边 + 橙调底 + 右上角打勾 */
    private fun renderSelection() {
        paintCard(
            binding.typeLocal, binding.checkLocal, selectedKind == SourceEntity.KIND_LOCAL
        )
        paintCard(
            binding.typeWebdav, binding.checkWebdav, selectedKind == SourceEntity.KIND_WEBDAV
        )
        paintCard(
            binding.typeSmb, binding.checkSmb, selectedKind == SourceEntity.KIND_SMB
        )
    }

    private fun paintCard(card: View, check: View, selected: Boolean) {
        card.setBackgroundResource(
            if (selected) R.drawable.bg_type_card_selected else R.drawable.bg_type_card
        )
        check.isVisible = selected
    }

    /** 底部主按钮：文案、图标、一行说明都跟着选中的东西变 */
    private fun renderAction() {
        val source = selectedSource
        if (source != null) {
            binding.actionLabel.text = getString(R.string.sources_action_update)
            binding.actionIcon.setImageResource(R.drawable.ic_refresh)
            binding.actionHint.text = getString(R.string.sources_hint_update)
            return
        }
        when (selectedKind) {
            SourceEntity.KIND_WEBDAV -> {
                binding.actionLabel.text = getString(R.string.sources_action_webdav)
                binding.actionIcon.setImageResource(R.drawable.ic_server)
                binding.actionHint.text = getString(R.string.sources_hint_webdav)
            }
            SourceEntity.KIND_SMB -> {
                binding.actionLabel.text = getString(R.string.sources_action_smb)
                binding.actionIcon.setImageResource(R.drawable.ic_database)
                binding.actionHint.text = getString(R.string.sources_hint_smb)
            }
            else -> {
                binding.actionLabel.text = getString(R.string.sources_action_local)
                binding.actionIcon.setImageResource(R.drawable.ic_folder)
                binding.actionHint.text = getString(R.string.sources_hint_local)
            }
        }
        ImageViewCompat.setImageTintList(
            binding.actionIcon,
            android.content.res.ColorStateList.valueOf(getColor(android.R.color.white)),
        )
    }

    private fun runAction() {
        if (scanning) return
        val source = selectedSource
        if (source != null) {
            runScan(source)
            return
        }
        when (selectedKind) {
            SourceEntity.KIND_WEBDAV -> showRemoteDialog(SourceEntity.KIND_WEBDAV)
            SourceEntity.KIND_SMB -> showRemoteDialog(SourceEntity.KIND_SMB)
            else -> pickLocalFolder.launch(null)
        }
    }

    // ── 列表 ───────────────────────────────────────────────

    private fun loadSources() {
        val gen = ++generation
        lifecycleScope.launch {
            val list = repository.list()
            val counts = db.bookDao().countsBySource().associate { it.sourceId to it.total }
            val total = db.bookDao().count()
            if (gen != generation) return@launch

            sources = list
            bookCounts = counts
            // 选中的来源可能已经被删掉：这时退回「新增本机文件夹」
            if (selectedSource != null && list.none { it.id == selectedSource!!.id }) {
                selectedSource = null
                selectedKind = SourceEntity.KIND_LOCAL
                renderSelection()
                renderAction()
            }
            binding.sourcesSummary.text =
                getString(R.string.sources_summary, list.size, total)
            renderList()
        }
    }

    private fun renderList() {
        val host = binding.sourceList
        host.removeAllViews()
        binding.sourcesEmpty.isVisible = sources.isEmpty()
        binding.sourcesHint.isVisible = sources.isNotEmpty()
        sources.forEach { host.addView(sourceRow(it)) }
    }

    private fun sourceRow(source: SourceEntity): View {
        val selected = selectedSource?.id == source.id
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(
                if (selected) R.drawable.bg_source_item_selected else R.drawable.bg_source_item
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { selectSource(source) }
            setOnLongClickListener {
                showSourceActions(source)
                true
            }
        }
        // 类型图标
        val tile = FrameLayout(this).apply {
            setBackgroundResource(tileBackground(source.kind))
        }
        tile.addView(
            ImageView(this).apply {
                setImageResource(iconOf(source.kind))
                ImageViewCompat.setImageTintList(
                    this,
                    android.content.res.ColorStateList.valueOf(tintOf(source.kind)),
                )
            },
            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER),
        )
        card.addView(tile, LinearLayout.LayoutParams(dp(40), dp(40)))

        // 名字 + 类型标 + 一行状态
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = source.label
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.shell_ink))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        titleRow.addView(TextView(this).apply {
            text = getString(kindLabel(source.kind))
            textSize = 8f
            setTextColor(tintOf(source.kind))
            background = getDrawable(badgeOf(source.kind))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginStart = dp(6)
            layoutParams = lp
        })
        texts.addView(titleRow)
        texts.addView(TextView(this).apply {
            text = rowMeta(source)
            textSize = 9f
            setTextColor(getColor(R.color.shell_ink_faint))
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 卡片之间留 8dp：第一条不留，避免和上面的说明贴太开
        val wrapper = FrameLayout(this)
        wrapper.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (binding.sourceList.childCount > 0) lp.topMargin = dp(8)
        wrapper.layoutParams = lp
        return wrapper
    }

    /** 同步完之后行里写什么：先报这一次补了几本，再报总数 */
    private fun rowMeta(source: SourceEntity): String {
        val books = bookCounts[source.id] ?: 0
        val state = when (source.scanState) {
            SourceEntity.STATE_SCANNING -> getString(R.string.source_state_scanning)
            SourceEntity.STATE_FAILED -> when (source.errorCode) {
                SourceEntity.ERROR_AUTH -> getString(R.string.source_state_failed_auth)
                SourceEntity.ERROR_UNREACHABLE -> getString(R.string.source_state_failed_unreachable)
                SourceEntity.ERROR_EMPTY_SCAN -> getString(R.string.source_state_failed_empty)
                else -> getString(R.string.source_state_failed)
            }
            SourceEntity.STATE_DONE ->
                if (source.errorCode == SourceEntity.ERROR_PARTIAL) {
                    getString(R.string.source_state_partial)
                } else {
                    source.lastScanAt?.takeIf { it > 0 }?.let(::relativeText)
                }
            else -> null
        }

        val delta = lastSync[source.id]
        if (delta != null && source.scanState == SourceEntity.STATE_DONE) {
            return when {
                delta.added > 0 -> withTime(
                    getString(R.string.sources_row_added, delta.added, books),
                    state,
                )
                delta.updated > 0 || delta.removed > 0 ->
                    getString(R.string.sources_row_changed, delta.updated, delta.removed, books)
                else -> withTime(getString(R.string.sources_row_unchanged, books), state)
            }
        }

        return if (state == null) {
            getString(R.string.sources_row_books, books)
        } else {
            getString(R.string.sources_row_meta, books, state)
        }
    }

    private fun withTime(text: String, time: String?): String =
        if (time.isNullOrBlank()) text else "$text · $time"

    private fun relativeText(at: Long): String {
        val shown = RelativeTime.of(System.currentTimeMillis(), at)
        return when (shown.kind) {
            RelativeTime.Kind.JUST_NOW -> getString(R.string.time_just_now)
            RelativeTime.Kind.MINUTES -> getString(R.string.time_minutes_ago, shown.amount)
            RelativeTime.Kind.HOURS -> getString(R.string.time_hours_ago, shown.amount)
            RelativeTime.Kind.DAYS -> getString(R.string.time_days_ago, shown.amount)
            RelativeTime.Kind.DATE -> RelativeTime.dateOf(at)
        }
    }

    private fun kindLabel(kind: Int): Int = when (kind) {
        SourceEntity.KIND_WEBDAV -> R.string.source_kind_webdav
        SourceEntity.KIND_SMB -> R.string.source_kind_smb
        else -> R.string.source_kind_local
    }

    private fun iconOf(kind: Int): Int = when (kind) {
        SourceEntity.KIND_WEBDAV -> R.drawable.ic_server
        SourceEntity.KIND_SMB -> R.drawable.ic_database
        else -> R.drawable.ic_folder
    }

    private fun tileBackground(kind: Int): Int = when (kind) {
        SourceEntity.KIND_WEBDAV -> R.drawable.bg_tile_webdav
        SourceEntity.KIND_SMB -> R.drawable.bg_tile_smb
        else -> R.drawable.bg_tile_local
    }

    private fun badgeOf(kind: Int): Int = when (kind) {
        SourceEntity.KIND_WEBDAV -> R.drawable.bg_badge_webdav
        SourceEntity.KIND_SMB -> R.drawable.bg_badge_smb
        else -> R.drawable.bg_badge_local
    }

    private fun tintOf(kind: Int): Int = getColor(
        when (kind) {
            SourceEntity.KIND_WEBDAV -> R.color.tile_webdav_ink
            SourceEntity.KIND_SMB -> R.color.tile_smb_ink
            else -> R.color.tile_local_ink
        }
    )

    // ── 扫描 ───────────────────────────────────────────────

    private fun runScan(source: SourceEntity) {
        showProgress(source.label)
        lifecycleScope.launch {
            try {
                val result = scanner.scan(source, scanListener())
                lastSync[source.id] = result
                showSuccess(scanResultText(source, result))
            } catch (e: Exception) {
                showFailure(remoteErrorText(this@SourcesActivity, e))
            } finally {
                scanning = false
                binding.progressPanel.isVisible = false
                loadSources()
            }
        }
    }

    /** 同步结果的说话方式：补了几本优先，没新书就说没新书，别只丢一个总数 */
    private fun scanResultText(source: SourceEntity, result: LibraryScanner.Result): String = when {
        // 一本都没扫到不是「成功」，要把「书还在」说清楚，否则用户以为书全丢了
        result.keptAll ->
            getString(R.string.sources_scan_kept, source.label, bookCounts[source.id] ?: 0)
        result.unreachableDirs > 0 ->
            getString(R.string.sources_scan_partial, source.label, result.unreachableDirs)
        result.added > 0 ->
            getString(R.string.sources_scan_added, source.label, result.added, result.found)
        result.updated > 0 || result.removed > 0 ->
            getString(R.string.sources_scan_changed, source.label, result.updated, result.removed)
        else ->
            getString(R.string.sources_scan_unchanged, source.label, result.found)
    }

    private fun showProgress(label: String) {
        scanning = true
        binding.successPanel.isVisible = false
        binding.progressPanel.isVisible = true
        binding.progressTitle.text = getString(R.string.sources_scanning, label)
        binding.progressDetail.text = getString(R.string.sources_scan_listing)
        binding.progressPercent.text = ""
        binding.progressBar.progress = 0
        binding.progressSpinner.isVisible = true
    }

    private fun showSuccess(text: String) {
        binding.successText.text = text
        binding.successPanel.isVisible = true
        binding.successPanel.postDelayed({
            binding.successPanel.isVisible = false
        }, 4000)
    }

    private fun showFailure(reason: String) {
        binding.successText.text = getString(R.string.sources_scan_failed, reason)
        binding.successPanel.isVisible = true
    }

    /** 扫描回调在 IO 线程，改界面前得回主线程 */
    private fun onUi(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else runOnUiThread(block)
    }

    // ── 新增来源 ───────────────────────────────────────────

    /**
     * 本地目录选完后先问名字。系统选择器只给路径，名字得留给用户——
     * 一柜子「Download」「漫画」分不清谁是谁。
     */
    private fun addLocalFolder(uri: Uri) {
        val suggested = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
        val input = EditText(this).apply {
            hint = getString(R.string.source_local_label_hint)
            setText(suggested)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.source_local_label_hint)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                registerLocal(uri, input.text.toString().trim())
            }
            .show()
    }

    private fun registerLocal(uri: Uri, label: String) {
        showProgress(label.ifBlank { getString(R.string.source_kind_local) })
        lifecycleScope.launch {
            var sourceLabel = label
            try {
                val id = repository.addLocalFolder(uri, label.ifBlank { null })
                val source = db.sourceDao().get(id) ?: error("来源记录丢失")
                sourceLabel = source.label
                binding.progressTitle.text = getString(R.string.sources_scanning, source.label)
                val result = scanner.scan(source, scanListener())
                lastSync[source.id] = result
                showSuccess(getString(R.string.source_added, source.label, result.found))
            } catch (e: Exception) {
                showFailure(e.message ?: "")
            } finally {
                scanning = false
                binding.progressPanel.isVisible = false
                loadSources()
            }
        }
    }

    private fun scanListener() = object : LibraryScanner.Listener {
        override fun onListing() = onUi {
            binding.progressDetail.text = getString(R.string.sources_scan_listing)
        }

        override fun onProgress(done: Int, total: Int) = onUi {
            if (total <= 0) return@onUi
            val percent = (done * 100 / total).coerceIn(0, 100)
            binding.progressSpinner.isVisible = false
            binding.progressDetail.text = getString(R.string.scan_progress, done, total)
            binding.progressPercent.text = "$percent%"
            binding.progressBar.progress = percent
        }
    }

    /**
     * 添加远端来源。WebDAV 与 SMB 填的是一组东西（地址、端口、账号、密码），
     * 共用对话框，只有协议行与提示语按协议换。
     */
    private fun showRemoteDialog(kind: Int) {
        val smb = kind == SourceEntity.KIND_SMB
        val content = layoutInflater.inflate(R.layout.dialog_remote, null)

        val address = content.findViewById<EditText>(R.id.remote_endpoint)
        val port = content.findViewById<EditText>(R.id.remote_port)
        val account = content.findViewById<EditText>(R.id.remote_account)
        val password = content.findViewById<EditText>(R.id.remote_password)
        val label = content.findViewById<EditText>(R.id.remote_label)
        val schemeRow = content.findViewById<View>(R.id.scheme_row)
        val httpChip = content.findViewById<TextView>(R.id.scheme_http)
        val httpsChip = content.findViewById<TextView>(R.id.scheme_https)

        var scheme = "https"

        fun renderScheme() {
            styleSchemeChip(httpChip, scheme == "http")
            styleSchemeChip(httpsChip, scheme == "https")
            port.hint = getString(
                if (smb) R.string.remote_port_hint_smb else R.string.remote_port_hint,
                if (smb) 445 else if (scheme == "http") 80 else 443,
            )
        }

        schemeRow.visibility = if (smb) View.GONE else View.VISIBLE
        httpChip.setOnClickListener { scheme = "http"; renderScheme() }
        httpsChip.setOnClickListener { scheme = "https"; renderScheme() }

        address.hint = getString(if (smb) R.string.smb_endpoint_hint else R.string.remote_host_hint)
        account.hint = getString(if (smb) R.string.smb_account_hint else R.string.remote_account_hint)
        renderScheme()
        address.requestFocus()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (smb) R.string.smb_dialog_title else R.string.dav_dialog_title)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_next, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawAddress = address.text.toString().trim()
                val portText = port.text.toString().trim()
                val portValue = if (portText.isEmpty()) {
                    null
                } else {
                    portText.toIntOrNull()?.takeIf { it in 1..65535 }
                }
                if (portText.isNotEmpty() && portValue == null) {
                    Toast.makeText(this, R.string.remote_port_error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (rawAddress.isEmpty()) {
                    Toast.makeText(this, R.string.remote_address_error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val endpoint = if (smb) {
                    rawAddress
                } else {
                    runCatching { DavAddress.toUrl(DavAddress.parse(scheme, rawAddress, portText)) }
                        .getOrElse {
                            Toast.makeText(this, it.message ?: "", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                }

                pendingKind = kind
                pendingPort = portValue
                pendingAccount = account.text.toString().trim()
                pendingPassword = password.text.toString()
                pendingLabel = label.text.toString().trim()

                val smbEndpoint = if (smb) {
                    val target = runCatching { SmbTarget.parse(rawAddress, portValue) }
                        .getOrElse {
                            Toast.makeText(this, it.message ?: "", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                    buildString {
                        append("smb://").append(target.host).append(':').append(target.port)
                        append('/').append(target.share)
                        if (target.path.isNotEmpty()) append('/').append(target.path)
                    }
                } else {
                    endpoint
                }

                dialog.dismiss()
                pickDirectory.launch(
                    Intent(this, RemotePickerActivity::class.java)
                        .putExtra(RemotePickerActivity.EXTRA_KIND, kind)
                        .putExtra(RemotePickerActivity.EXTRA_ENDPOINT, smbEndpoint)
                        .putExtra(RemotePickerActivity.EXTRA_ACCOUNT, pendingAccount)
                        .putExtra(RemotePickerActivity.EXTRA_PASSWORD, pendingPassword)
                )
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    /** 协议的两个小圆角块：选中的墨色实底白字，未选白底描边 */
    private fun styleSchemeChip(chip: TextView, selected: Boolean) {
        chip.setTextColor(getColor(if (selected) android.R.color.white else R.color.shell_ink_muted))
        chip.background = getDrawable(
            if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
    }

    private var pendingKind = SourceEntity.KIND_WEBDAV
    private var pendingAccount = ""
    private var pendingPassword = ""
    private var pendingLabel = ""
    private var pendingPort: Int? = null

    /** 选完目录才真正登记来源：目录是用户在远端点的，不靠地址里手打 */
    private val pickDirectory = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val picked = result.data?.getStringExtra(RemotePickerActivity.EXTRA_PICKED).orEmpty()
        if (result.resultCode == RESULT_OK && picked.isNotEmpty()) {
            addRemote(
                kind = pendingKind,
                endpoint = picked,
                account = pendingAccount,
                password = pendingPassword,
                label = pendingLabel,
                port = pendingPort,
            )
        }
        pendingPassword = ""
    }

    private fun addRemote(
        kind: Int,
        endpoint: String,
        account: String,
        password: String,
        label: String,
        port: Int? = null,
    ) {
        showProgress(label.ifBlank { endpoint })
        lifecycleScope.launch {
            try {
                val id = if (kind == SourceEntity.KIND_SMB) {
                    repository.addSmb(endpoint, account, password, label.ifBlank { null }, port)
                } else {
                    repository.addWebDav(endpoint, account, password, label.ifBlank { null })
                }
                val source = db.sourceDao().get(id) ?: error("来源记录丢失")
                binding.progressTitle.text = getString(R.string.sources_scanning, source.label)
                val result = scanner.scan(source, scanListener())
                lastSync[source.id] = result
                if (result.found == 0) {
                    showSuccess(getString(R.string.source_added_empty, source.label))
                } else {
                    showSuccess(getString(R.string.source_added, source.label, result.found))
                }
            } catch (e: Exception) {
                showFailure(remoteErrorText(this@SourcesActivity, e))
            } finally {
                scanning = false
                binding.progressPanel.isVisible = false
                loadSources()
            }
        }
    }

    // ── 来源操作 ───────────────────────────────────────────

    /** 长按来源：重扫、改名或删掉 */
    private fun showSourceActions(source: SourceEntity) {
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(
                    getString(R.string.sources_action_update),
                    getString(R.string.source_rename),
                    getString(R.string.action_delete),
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        selectSource(source)
                        runScan(source)
                    }
                    1 -> renameSource(source)
                    else -> confirmRemove(source)
                }
            }
            .show()
    }

    private fun renameSource(source: SourceEntity) {
        val input = EditText(this).apply {
            hint = getString(R.string.source_rename_hint)
            setText(source.label)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.source_rename_title)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    repository.rename(source.id, input.text.toString())
                    loadSources()
                }
            }
            .show()
    }

    private fun confirmRemove(source: SourceEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.source_delete_title)
            .setMessage(getString(R.string.source_delete_message, source.label))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    // 先记下这门来源下的书，删完把它们的页缓存一起清掉
                    val doomed = db.bookDao().idsBySource(source.id)
                    repository.remove(source.id)
                    withContext(Dispatchers.IO) {
                        val cache = com.nascomic.reader.ui.reader.DiskPageCache(this@SourcesActivity)
                        doomed.forEach { cache.clearBook(it) }
                        // 为打开格式落到本地的副本（7z / RAR / 远端 PDF）按文件认不出属于哪个来源，
                        // 一并清掉：它们都能重新生成，留着只会一直占空间
                        com.nascomic.reader.format.LocalCopies.clear(this@SourcesActivity)
                    }
                    Toast.makeText(
                        this@SourcesActivity,
                        getString(R.string.source_deleted, source.label),
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadSources()
                }
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
