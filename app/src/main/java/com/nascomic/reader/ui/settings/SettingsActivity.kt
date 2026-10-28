package com.nascomic.reader.ui.settings

import android.os.Bundle
import android.util.TypedValue
import android.widget.EditText
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import kotlinx.coroutines.launch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivitySettingsBinding
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.library.SourceRepository
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.RelativeTime
import com.nascomic.reader.library.scan.LibraryScanner
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.settings.ReaderPrefs
import com.nascomic.reader.settings.applyOrientation
import com.nascomic.reader.ui.about.AboutActivity

/**
 * 设置。按组排列，改动立即写入（读取端在 [ReaderPrefs] / [AppPrefs]）。
 *
 * 行由代码拼，不用 preference 库：这里的控件只有开关、单选、滑杆三种，
 * 自己拼比引一套框架再和它的主题打架划算。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { AppPrefs(this) }
    private val reader by lazy { ReaderPrefs(this) }
    private val db by lazy { ReaderDatabase.get(this) }
    private val repository by lazy { SourceRepository(this, db) }
    private val scanner by lazy { LibraryScanner(this, db) }

    /** 本地目录用系统的目录选择器：授权一次，之后一直能用 */
    private val pickLocalFolder = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? -> if (uri != null) addLocalFolder(uri) }

    /** 来源列表的代次：界面重建后旧查询的结果要丢掉 */
    private var sourceGeneration = 0

    /** 展开着哪几组。默认只开「阅读」 */
    private val expanded = mutableSetOf(GROUP_READING)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        buildRows()
    }

    /**
     * 分组抽屉：每组一行（组名 + 项数 + 箭头），点开才展开内容。
     * 默认只展开第一组——全收起会让人以为页面没做完，全展开又太长。
     */
    private fun buildRows() {
        val host = binding.settingsContainer
        host.removeAllViews()

        addGroup(host, GROUP_READING, getString(R.string.settings_section_reading), buildReadingRows())
        addGroup(host, GROUP_SOURCE, getString(R.string.settings_section_source), buildSourceRows())
        addGroup(host, GROUP_OTHER, getString(R.string.settings_section_other), buildOtherRows())
        addGroup(host, GROUP_ABOUT, getString(R.string.settings_about), buildAboutRows())

        binding.expandAll.text = getString(
            if (expanded.size == GROUP_ALL.size) R.string.settings_collapse_all
            else R.string.settings_expand_all
        )
        binding.expandAll.setOnClickListener {
            if (expanded.size == GROUP_ALL.size) expanded.clear() else expanded.addAll(GROUP_ALL)
            buildRows()
        }
    }

    private fun addGroup(host: LinearLayout, key: String, title: String, rows: List<View>) {
        val isOpen = expanded.contains(key)
        if (host.childCount > 0) {
            host.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
                )
            })
        }
        host.addView(groupHeader(title, rows.size, isOpen) {
            if (isOpen) expanded.remove(key) else expanded.add(key)
            buildRows()
        })
        if (!isOpen) return

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.shell_background))
        }
        rows.forEach { content.addView(it) }
        host.addView(content)
    }

    private fun groupHeader(title: String, count: Int, isOpen: Boolean, onToggle: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
            background = getDrawable(R.drawable.bg_group_header)
            setOnClickListener { onToggle() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(getColor(R.color.shell_ink))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.settings_group_count, count)
            textSize = 12f
            setTextColor(getColor(R.color.shell_ink_muted))
            setPadding(0, 0, dp(8), 0)
        })
        row.addView(TextView(this).apply {
            text = if (isOpen) "⌃" else "⌄"
            textSize = 14f
            setTextColor(getColor(R.color.shell_ink_muted))
        })
        return row
    }

    /**
     * 把一组行先塞进临时容器再取出来，避免把每种行都写成「返回 View」。
     * 取出时必须从容器里摘掉，否则再 addView 会报「child already has a parent」。
     */
    private fun collect(block: LinearLayout.() -> Unit): List<View> {
        val temp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        temp.block()
        val out = ArrayList<View>(temp.childCount)
        while (temp.childCount > 0) {
            val child = temp.getChildAt(0)
            temp.removeViewAt(0)
            out += child
        }
        return out
    }

    private fun buildReadingRows(): List<View> = collect {


        addView(
            choiceRow(
                title = getString(R.string.settings_flip_direction),
                desc = getString(R.string.settings_flip_direction_desc),
                options = listOf(
                    getString(R.string.settings_flip_ltr),
                    getString(R.string.settings_flip_rtl),
                ),
                selected = if (reader.flipForwardRight) 0 else 1,
            ) { index ->
                reader.flipForwardRight = index == 0
            }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_dual_page),
                desc = getString(R.string.settings_dual_page_desc),
                checked = reader.dualPage,
            ) { checked -> reader.dualPage = checked }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_trim_border),
                desc = getString(R.string.settings_trim_border_desc),
                checked = reader.trimBorder,
            ) { checked -> reader.trimBorder = checked }
        )

        addView(
            sliderRow(
                title = getString(R.string.settings_trim_level),
                min = 200,
                max = 250,
                step = 5,
                initial = reader.trimLevel,
                format = { getString(R.string.settings_trim_level_value, it) },
            ) { value -> reader.trimLevel = value }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_split_wide),
                desc = getString(R.string.settings_split_wide_desc),
                checked = reader.splitWide,
            ) { checked -> reader.splitWide = checked }
        )

        addView(
            sliderRow(
                title = getString(R.string.settings_margin),
                min = 0,
                max = 32,
                step = 2,
                initial = reader.sideMarginDp,
                format = { getString(R.string.settings_margin_value, it) },
            ) { value -> reader.sideMarginDp = value }
        )

        val canvasLabels = listOf(
            getString(R.string.settings_canvas_light),
            getString(R.string.settings_canvas_dark),
            getString(R.string.settings_canvas_black),
        )
        addView(
            choiceRow(
                title = getString(R.string.settings_canvas),
                desc = null,
                options = canvasLabels,
                selected = ReaderPrefs.Canvas.of(reader.canvas).ordinal,
            ) { index ->
                reader.canvas = ReaderPrefs.Canvas.entries[index].value
            }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_page_label),
                desc = getString(R.string.settings_page_label_desc),
                checked = reader.showPageLabel,
            ) { checked -> reader.showPageLabel = checked }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_skip_blank),
                desc = getString(R.string.settings_skip_blank_desc),
                checked = reader.skipBlank,
            ) { checked -> reader.skipBlank = checked }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_keep_screen_on),
                desc = getString(R.string.settings_keep_screen_on_desc),
                checked = reader.keepScreenOn,
            ) { checked -> reader.keepScreenOn = checked }
        )
    }

    private fun buildSourceRows(): List<View> = collect {


        addView(
            choiceRowWithValue(
                title = getString(R.string.settings_add_local),
                desc = getString(R.string.settings_add_local_desc),
                value = "",
            ) { pickLocalFolder.launch(null) }
        )

        addView(
            choiceRowWithValue(
                title = getString(R.string.settings_add_dav),
                desc = getString(R.string.settings_add_dav_desc),
                value = "",
            ) { showRemoteDialog(SourceEntity.KIND_WEBDAV) }
        )

        addView(
            choiceRowWithValue(
                title = getString(R.string.settings_add_smb),
                desc = getString(R.string.settings_add_smb_desc),
                value = "",
            ) { showRemoteDialog(SourceEntity.KIND_SMB) }
        )

        // 已添加的来源：点一下重扫，长按删除
        val sourceList = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(sourceList)
        loadSources(sourceList)
    }

    private fun buildOtherRows(): List<View> = collect {


        addView(
            choiceRow(
                title = getString(R.string.settings_orientation),
                desc = null,
                options = listOf(
                    getString(R.string.settings_orientation_system),
                    getString(R.string.settings_orientation_portrait),
                    getString(R.string.settings_orientation_landscape),
                ),
                selected = AppPrefs.Orientation.of(prefs.orientation).ordinal,
            ) { index ->
                prefs.orientation = AppPrefs.Orientation.entries[index].value
                applyOrientation(prefs)
            }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_skip_detail),
                desc = getString(R.string.settings_skip_detail_desc),
                checked = prefs.skipDetail,
            ) { checked -> prefs.skipDetail = checked }
        )
    }

    private fun buildAboutRows(): List<View> = collect {


        addView(
            choiceRowWithValue(
                title = getString(R.string.settings_about_row),
                desc = getString(R.string.settings_about_desc),
                value = "",
            ) { startActivity(android.content.Intent(this@SettingsActivity, AboutActivity::class.java)) }
        )
    }

    /**
     * 本地目录选完后先问名字。系统选择器只给路径，名字得留给用户——
     * 一柜子「Download」「漫画」分不清谁是谁。
     */
    private fun addLocalFolder(uri: android.net.Uri) {
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
                scanLocalFolder(uri, input.text.toString().trim())
            }
            .show()
    }

    private fun scanLocalFolder(uri: android.net.Uri, label: String) {
        lifecycleScope.launch {
            val toast = try {
                val id = repository.addLocalFolder(uri, label.ifBlank { null })
                val source = db.sourceDao().get(id)
                val found = source?.let { scanner.scan(it).found } ?: 0
                getString(R.string.source_added, source?.label.orEmpty(), found)
            } catch (e: Exception) {
                getString(R.string.source_failed, e.message ?: "")
            }
            Toast.makeText(this@SettingsActivity, toast, Toast.LENGTH_LONG).show()
            buildRows()
        }
    }

    /**
     * 列出已有来源。点重扫、长按删除。
     *
     * 每次都重新查库：新增或删除之后 [buildRows] 会再走一遍，这里用代次丢掉旧结果，
     * 免得慢的查询把行加到已经废弃的容器里。
     */
    private fun loadSources(container: LinearLayout) {
        val generation = ++sourceGeneration
        lifecycleScope.launch {
            val list = repository.list()
            if (generation != sourceGeneration) return@launch

            if (list.isEmpty()) {
                container.addView(
                    TextView(this@SettingsActivity).apply {
                        text = getString(R.string.source_empty)
                        textSize = 13f
                        setTextColor(getColor(R.color.shell_ink_muted))
                        setPadding(0, dp(6), 0, dp(6))
                    }
                )
                return@launch
            }
            for (source in list) container.addView(sourceRow(source))
        }
    }

    private fun sourceRow(source: SourceEntity): View {
        val row = rowContainer()
        row.addView(titleBlock(source.label, describe(source)), weighted())
        row.setOnClickListener { rescan(source) }
        row.setOnLongClickListener {
            showSourceActions(source)
            true
        }
        return withDivider(row)
    }

    /** 「类型 · 状态」，状态里带上次扫描的相对时间 */
    private fun describe(source: SourceEntity): String {
        val kind = when (source.kind) {
            SourceEntity.KIND_WEBDAV -> R.string.source_kind_webdav
            SourceEntity.KIND_SMB -> R.string.source_kind_smb
            else -> R.string.source_kind_local
        }
        val state = when (source.scanState) {
            SourceEntity.STATE_SCANNING -> getString(R.string.source_state_scanning)
            SourceEntity.STATE_DONE -> {
                val at = source.lastScanAt
                if (at == null || at <= 0) {
                    getString(R.string.source_state_done)
                } else {
                    val shown = RelativeTime.of(System.currentTimeMillis(), at)
                    when (shown.kind) {
                        RelativeTime.Kind.JUST_NOW -> getString(R.string.time_just_now)
                        RelativeTime.Kind.MINUTES -> getString(R.string.time_minutes_ago, shown.amount)
                        RelativeTime.Kind.HOURS -> getString(R.string.time_hours_ago, shown.amount)
                        RelativeTime.Kind.DAYS -> getString(R.string.time_days_ago, shown.amount)
                        RelativeTime.Kind.DATE -> RelativeTime.dateOf(at)
                    }
                }
            }
            SourceEntity.STATE_FAILED -> when (source.errorCode) {
                SourceEntity.ERROR_AUTH -> getString(R.string.source_state_failed_auth)
                SourceEntity.ERROR_UNREACHABLE -> getString(R.string.source_state_failed_unreachable)
                else -> getString(R.string.source_state_failed)
            }
            else -> getString(R.string.source_state_idle)
        }
        return getString(R.string.source_row_desc, getString(kind), state)
    }

    private fun rescan(source: SourceEntity) {
        lifecycleScope.launch {
            val toast = try {
                val result = scanner.scan(source)
                getString(R.string.source_rescan_done, source.label, result.found)
            } catch (e: Exception) {
                getString(R.string.source_rescan_failed, e.message ?: "")
            }
            Toast.makeText(this@SettingsActivity, toast, Toast.LENGTH_LONG).show()
            buildRows()
        }
    }

    /** 长按来源：改名或删掉 */
    private fun showSourceActions(source: SourceEntity) {
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(
                    getString(R.string.source_rename),
                    getString(R.string.action_delete),
                )
            ) { _, which ->
                if (which == 0) renameSource(source) else confirmRemove(source)
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
                    buildRows()
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
                    repository.remove(source.id)
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.source_deleted, source.label),
                        Toast.LENGTH_SHORT,
                    ).show()
                    buildRows()
                }
            }
            .show()
    }

    /** 只做导航的行：右侧没有值，点一下弹对话框 */
    private fun choiceRowWithValue(
        title: String,
        desc: String?,
        value: String,
        onClick: () -> Unit,
    ): View {
        val row = rowContainer()
        row.addView(titleBlock(title, desc), weighted())
        val label = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(getColor(R.color.shell_ink_muted))
        }
        row.addView(label)
        row.setOnClickListener { onClick() }
        return withDivider(row)
    }

    /**
     * 添加远端来源。WebDAV 与 SMB 要填的是同一组东西（地址、账号、密码），
     * 所以共用一个对话框，只有提示语按协议换。
     */
    private fun showRemoteDialog(kind: Int) {
        val smb = kind == SourceEntity.KIND_SMB
        val content = layoutInflater.inflate(R.layout.dialog_remote, null)

        content.findViewById<EditText>(R.id.remote_endpoint).hint =
            getString(if (smb) R.string.smb_endpoint_hint else R.string.remote_endpoint_hint)
        content.findViewById<EditText>(R.id.remote_account).hint =
            getString(if (smb) R.string.smb_account_hint else R.string.remote_account_hint)
        content.findViewById<EditText>(R.id.remote_password).hint =
            getString(R.string.remote_password_hint)

        AlertDialog.Builder(this)
            .setTitle(if (smb) R.string.smb_dialog_title else R.string.dav_dialog_title)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val endpoint = content.findViewById<EditText>(R.id.remote_endpoint)
                    .text.toString().trim()
                if (endpoint.isEmpty()) return@setPositiveButton
                val label = content.findViewById<EditText>(R.id.remote_label).text.toString().trim()
                val account = content.findViewById<EditText>(R.id.remote_account)
                    .text.toString().trim()
                val password = content.findViewById<EditText>(R.id.remote_password)
                    .text.toString()
                addRemote(kind, endpoint, account, password, label)
            }
            .show()
    }

    private fun addRemote(
        kind: Int,
        endpoint: String,
        account: String,
        password: String,
        label: String,
    ) {
        lifecycleScope.launch {
            val toast = try {
                val id = if (kind == SourceEntity.KIND_SMB) {
                    repository.addSmb(endpoint, account, password, label.ifBlank { null })
                } else {
                    repository.addWebDav(endpoint, account, password, label.ifBlank { null })
                }
                val source = db.sourceDao().get(id)
                val result = source?.let { scanner.scan(it) }
                getString(R.string.source_added, source?.label.orEmpty(), result?.found ?: 0)
            } catch (e: Exception) {
                getString(R.string.source_rescan_failed, e.message ?: "")
            }
            Toast.makeText(this@SettingsActivity, toast, Toast.LENGTH_LONG).show()
            buildRows()
        }
    }

    // ---- 三种行 ----

    private companion object {
        const val GROUP_READING = "reading"
        const val GROUP_SOURCE = "source"
        const val GROUP_OTHER = "other"
        const val GROUP_ABOUT = "about"
        val GROUP_ALL = listOf(GROUP_READING, GROUP_SOURCE, GROUP_OTHER, GROUP_ABOUT)
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.shell_ink_muted))
        setPadding(dp(20), dp(20), dp(20), dp(6))
    }

    private fun switchRow(
        title: String,
        desc: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = rowContainer()
        val texts = titleBlock(title, desc)
        val switch = SwitchCompat(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(texts, weighted())
        row.addView(switch)
        return withDivider(row)
    }

    private fun choiceRow(
        title: String,
        desc: String?,
        options: List<String>,
        selected: Int,
        onChange: (Int) -> Unit,
    ): View {
        val row = rowContainer()
        val texts = titleBlock(title, desc)
        val value = TextView(this).apply {
            text = options.getOrElse(selected) { "" }
            textSize = 14f
            setTextColor(getColor(R.color.shell_ink_muted))
        }
        row.addView(texts, weighted())
        row.addView(value)
        row.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                    value.text = options[which]
                    onChange(which)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        return withDivider(row)
    }

    private fun sliderRow(
        title: String,
        min: Int,
        max: Int,
        step: Int,
        initial: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(getColor(R.color.shell_ink))
        }, weighted())
        val valueView = TextView(this).apply {
            text = format(initial)
            textSize = 14f
            setTextColor(getColor(R.color.shell_ink_muted))
        }
        head.addView(valueView)
        block.addView(head)

        val steps = (max - min) / step
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = ((initial - min) / step).coerceIn(0, steps)
            setPadding(0, dp(4), 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress * step
                    valueView.text = format(v)
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        block.addView(bar)
        return withDivider(block)
    }

    // ---- 小工具 ----

    private fun rowContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(12))
        isClickable = true
        setBackgroundResource(selectableBackground())
    }

    private fun titleBlock(title: String, desc: String?): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15f
                setTextColor(getColor(R.color.shell_ink))
            })
            if (!desc.isNullOrBlank()) {
                addView(TextView(this@SettingsActivity).apply {
                    text = desc
                    textSize = 12f
                    setTextColor(getColor(R.color.shell_ink_muted))
                    setPadding(0, dp(4), dp(8), 0)
                })
            }
        }
    }

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** 每行底部叠一条发丝线，行多了才分得清 */
    private fun withDivider(row: View): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrapper.addView(View(this).apply {
            setBackgroundColor(0x11000000)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(20)
            marginEnd = dp(20)
        })
        return wrapper
    }

    private fun selectableBackground(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
