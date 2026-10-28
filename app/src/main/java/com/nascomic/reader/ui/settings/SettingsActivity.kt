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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        buildRows()
    }

    private fun buildRows() {
        val host = binding.settingsContainer
        host.removeAllViews()

        host.addView(sectionHeader(getString(R.string.settings_section_reading)))

        host.addView(
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

        host.addView(
            switchRow(
                title = getString(R.string.settings_dual_page),
                desc = getString(R.string.settings_dual_page_desc),
                checked = reader.dualPage,
            ) { checked -> reader.dualPage = checked }
        )

        host.addView(
            switchRow(
                title = getString(R.string.settings_trim_border),
                desc = getString(R.string.settings_trim_border_desc),
                checked = reader.trimBorder,
            ) { checked -> reader.trimBorder = checked }
        )

        host.addView(
            sliderRow(
                title = getString(R.string.settings_trim_level),
                min = 200,
                max = 250,
                step = 5,
                initial = reader.trimLevel,
                format = { getString(R.string.settings_trim_level_value, it) },
            ) { value -> reader.trimLevel = value }
        )

        host.addView(
            switchRow(
                title = getString(R.string.settings_split_wide),
                desc = getString(R.string.settings_split_wide_desc),
                checked = reader.splitWide,
            ) { checked -> reader.splitWide = checked }
        )

        host.addView(
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
        host.addView(
            choiceRow(
                title = getString(R.string.settings_canvas),
                desc = null,
                options = canvasLabels,
                selected = ReaderPrefs.Canvas.of(reader.canvas).ordinal,
            ) { index ->
                reader.canvas = ReaderPrefs.Canvas.entries[index].value
            }
        )

        host.addView(
            switchRow(
                title = getString(R.string.settings_page_label),
                desc = getString(R.string.settings_page_label_desc),
                checked = reader.showPageLabel,
            ) { checked -> reader.showPageLabel = checked }
        )

        host.addView(
            switchRow(
                title = getString(R.string.settings_skip_blank),
                desc = getString(R.string.settings_skip_blank_desc),
                checked = reader.skipBlank,
            ) { checked -> reader.skipBlank = checked }
        )

        host.addView(
            switchRow(
                title = getString(R.string.settings_keep_screen_on),
                desc = getString(R.string.settings_keep_screen_on_desc),
                checked = reader.keepScreenOn,
            ) { checked -> reader.keepScreenOn = checked }
        )

        host.addView(sectionHeader(getString(R.string.settings_section_source)))

        host.addView(
            choiceRowWithValue(
                title = getString(R.string.settings_add_local),
                desc = getString(R.string.settings_add_local_desc),
                value = "",
            ) { pickLocalFolder.launch(null) }
        )

        host.addView(
            choiceRowWithValue(
                title = getString(R.string.settings_add_dav),
                desc = getString(R.string.settings_add_dav_desc),
                value = "",
            ) { showRemoteDialog(SourceEntity.KIND_WEBDAV) }
        )

        host.addView(
            choiceRowWithValue(
                title = getString(R.string.settings_add_smb),
                desc = getString(R.string.settings_add_smb_desc),
                value = "",
            ) { showRemoteDialog(SourceEntity.KIND_SMB) }
        )

        // 已添加的来源：点一下重扫，长按删除
        val sourceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.addView(sourceList)
        loadSources(sourceList)

        host.addView(sectionHeader(getString(R.string.settings_section_other)))

        host.addView(
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

        host.addView(
            switchRow(
                title = getString(R.string.settings_skip_detail),
                desc = getString(R.string.settings_skip_detail_desc),
                checked = prefs.skipDetail,
            ) { checked -> prefs.skipDetail = checked }
        )

        host.addView(sectionHeader(getString(R.string.settings_about)))

        host.addView(
            choiceRowWithValue(
                title = getString(R.string.settings_about_row),
                desc = getString(R.string.settings_about_desc),
                value = "",
            ) { startActivity(android.content.Intent(this, AboutActivity::class.java)) }
        )
    }

    private fun addLocalFolder(uri: android.net.Uri) {
        lifecycleScope.launch {
            val toast = try {
                val id = repository.addLocalFolder(uri, null)
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
            confirmRemove(source)
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
                val account = content.findViewById<EditText>(R.id.remote_account)
                    .text.toString().trim()
                val password = content.findViewById<EditText>(R.id.remote_password)
                    .text.toString()
                addRemote(kind, endpoint, account, password)
            }
            .show()
    }

    private fun addRemote(kind: Int, endpoint: String, account: String, password: String) {
        lifecycleScope.launch {
            val toast = try {
                val id = if (kind == SourceEntity.KIND_SMB) {
                    repository.addSmb(endpoint, account, password, null)
                } else {
                    repository.addWebDav(endpoint, account, password, null)
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
