package com.nascomic.reader.ui.settings

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivitySettingsBinding
import com.nascomic.reader.library.SourceRepository
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.settings.ReaderPrefs
import com.nascomic.reader.settings.applyOrientation
import com.nascomic.reader.ui.about.AboutActivity
import com.nascomic.reader.ui.about.LicensesActivity
import com.nascomic.reader.ui.sources.SourcesActivity
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.launch

/**
 * 设置。照界面稿分六组，全部默认展开——手机上滚一遍就到底，折叠反而多点一下。
 *
 * 还没做的功能留着占位行（灰字 + 「未实现」小标），点不动，也不假装能调；
 * 做过的参数按语义放进最贴近的那一组。
 *
 * 行由代码拼，不用 preference 库：控件只有开关、单选、滑杆三种，
 * 自己拼比引一套框架再和它的主题打架划算。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { AppPrefs(this) }
    private val reader by lazy { ReaderPrefs(this) }
    private val db by lazy { ReaderDatabase.get(this) }
    private val repository by lazy { SourceRepository(this, db) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15 起强制边到边：自己让开状态栏与导航条
        binding.root.padBySystemBars()

        binding.back.setOnClickListener { finish() }
        buildRows()
    }

    // ── 六组 ───────────────────────────────────────────────

    private fun buildRows() {
        val host = binding.settingsContainer
        host.removeAllViews()

        addGroup(host, R.string.settings_group_library, buildLibraryRows())
        addGroup(host, R.string.settings_group_flip, buildFlipRows())
        addGroup(host, R.string.settings_group_zoom, buildZoomRows())
        addGroup(host, R.string.settings_group_layout, buildLayoutRows())
        addGroup(host, R.string.settings_group_perf, buildPerfRows())
        addGroup(host, R.string.settings_group_other, buildOtherRows())
    }

    /** 组头只有一行标题：没有项数，也没有展开箭头 */
    private fun addGroup(host: LinearLayout, titleRes: Int, rows: List<View>) {
        host.addView(TextView(this).apply {
            text = getString(titleRes)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.shell_ink))
            setPadding(dp(PAGE_MARGIN), dp(18), dp(PAGE_MARGIN), dp(8))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            clipToOutline = true
        }
        rows.forEach { card.addView(it) }
        host.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(PAGE_MARGIN)
            marginEnd = dp(PAGE_MARGIN)
        })
    }

    /** 书库与仓库：书源管理（真） + 扫描规则（占位） */
    private fun buildLibraryRows(): List<View> = collect {
        addView(sourceEntryRow())
        addView(
            placeholderRow(
                title = getString(R.string.settings_scan_rules),
                desc = getString(R.string.settings_scan_rules_desc),
            )
        )
    }

    /**
     * 「书源管理」入口：右侧那行小字报现状——本地 1 · WebDAV 1 · SMB 0。
     */
    private fun sourceEntryRow(): View {
        val row = rowContainer()
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = getString(R.string.settings_sources_entry)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.shell_ink))
        })
        val desc = TextView(this).apply {
            text = getString(R.string.settings_sources_entry_empty)
            textSize = 11f
            setTextColor(getColor(R.color.shell_ink_faint))
            setPadding(0, dp(3), 0, 0)
        }
        texts.addView(desc)
        row.addView(texts, weighted())
        row.addView(chevron())
        row.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, SourcesActivity::class.java))
        }

        lifecycleScope.launch {
            val list = repository.list()
            desc.text = if (list.isEmpty()) {
                getString(R.string.settings_sources_entry_empty)
            } else {
                getString(
                    R.string.settings_sources_entry_desc,
                    list.count { it.kind == SourceEntity.KIND_LOCAL },
                    list.count { it.kind == SourceEntity.KIND_WEBDAV },
                    list.count { it.kind == SourceEntity.KIND_SMB },
                )
            }
        }
        return withDivider(row)
    }

    /** 翻页与手势：两个真开关 + 五个占位 */
    private fun buildFlipRows(): List<View> = collect {
        addView(
            placeholderRow(
                title = getString(R.string.settings_read_direction),
                desc = getString(R.string.settings_read_direction_desc),
            )
        )

        addView(
            switchRow(
                title = getString(R.string.settings_swap_zones),
                desc = getString(R.string.settings_swap_zones_desc),
                checked = !reader.flipForwardRight,
            ) { checked -> reader.flipForwardRight = !checked }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_tap_turn),
                desc = getString(R.string.settings_tap_turn_desc),
                checked = reader.tapToTurn,
            ) { checked -> reader.tapToTurn = checked }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_skip_blank),
                desc = getString(R.string.settings_skip_blank_desc),
                checked = reader.skipBlank,
            ) { checked -> reader.skipBlank = checked }
        )

        addView(
            placeholderRow(
                title = getString(R.string.settings_jump_distance),
                desc = getString(R.string.settings_jump_distance_desc),
            )
        )
        addView(
            placeholderRow(
                title = getString(R.string.settings_volume_keys),
                desc = getString(R.string.settings_volume_keys_desc),
            )
        )
        addView(
            placeholderRow(
                title = getString(R.string.settings_zone_hint),
                desc = getString(R.string.settings_zone_hint_desc),
            )
        )
    }

    /** 缩放：这一组全是还没做的 */
    private fun buildZoomRows(): List<View> = collect {
        addView(
            placeholderRow(
                title = getString(R.string.settings_double_tap_zoom),
                desc = getString(R.string.settings_double_tap_zoom_desc),
            )
        )
        addView(
            placeholderRow(
                title = getString(R.string.settings_double_tap_anchor),
                desc = getString(R.string.settings_double_tap_anchor_desc),
            )
        )
        addView(
            placeholderRow(
                title = getString(R.string.settings_long_press_zoom),
                desc = getString(R.string.settings_long_press_zoom_desc),
            )
        )
        addView(
            placeholderRow(
                title = getString(R.string.settings_pinch_zoom),
                desc = getString(R.string.settings_pinch_zoom_desc),
            )
        )
    }

    /** 版面与显示：阅读时真正会变样子的一批参数 */
    private fun buildLayoutRows(): List<View> = collect {
        addView(
            placeholderRow(
                title = getString(R.string.settings_page_fit),
                desc = getString(R.string.settings_page_fit_desc),
            )
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

        addView(
            choiceRow(
                title = getString(R.string.settings_canvas),
                desc = null,
                options = listOf(
                    getString(R.string.settings_canvas_light),
                    getString(R.string.settings_canvas_dark),
                    getString(R.string.settings_canvas_black),
                ),
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
                title = getString(R.string.settings_keep_screen_on),
                desc = getString(R.string.settings_keep_screen_on_desc),
                checked = reader.keepScreenOn,
            ) { checked -> reader.keepScreenOn = checked }
        )

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
            placeholderRow(
                title = getString(R.string.settings_native_decode),
                desc = getString(R.string.settings_native_decode_desc),
            )
        )
    }

    /** 动画与性能：预载页数是这一组里唯一能调的 */
    private fun buildPerfRows(): List<View> = collect {
        addView(
            placeholderRow(
                title = getString(R.string.settings_turn_animation),
                desc = getString(R.string.settings_turn_animation_desc),
            )
        )

        addView(
            sliderRow(
                title = getString(R.string.settings_prefetch),
                desc = getString(R.string.settings_prefetch_desc),
                min = 0,
                max = 12,
                step = 1,
                initial = reader.prefetchPages,
                format = {
                    if (it == 0) getString(R.string.settings_prefetch_off)
                    else getString(R.string.settings_prefetch_value, it)
                },
            ) { value -> reader.prefetchPages = value }
        )

        addView(
            placeholderRow(
                title = getString(R.string.settings_cache_limit),
                desc = getString(R.string.settings_cache_limit_desc),
            )
        )
    }

    /** 其他：跳详情、许可、关于、恢复默认 */
    private fun buildOtherRows(): List<View> = collect {
        addView(
            switchRow(
                title = getString(R.string.settings_skip_detail),
                desc = getString(R.string.settings_skip_detail_desc),
                checked = prefs.skipDetail,
            ) { checked -> prefs.skipDetail = checked }
        )

        addView(
            choiceRowWithValue(
                title = getString(R.string.about_licenses),
                desc = getString(R.string.about_licenses_desc),
            ) { startActivity(Intent(this@SettingsActivity, LicensesActivity::class.java)) }
        )

        addView(
            choiceRowWithValue(
                title = getString(R.string.settings_about_row),
                desc = getString(R.string.settings_about_desc),
            ) { startActivity(Intent(this@SettingsActivity, AboutActivity::class.java)) }
        )

        addView(
            choiceRowWithValue(
                title = getString(R.string.settings_reset),
                desc = getString(R.string.settings_reset_desc),
                destructive = true,
            ) { confirmReset() }
        )
    }

    // ── 恢复默认 ───────────────────────────────────────────

    /**
     * 只清阅读与界面偏好：书源、书架、进度、书签都在数据库里，不受影响。
     * 清完立即重建这一页，参数要当场回到初始值，而不是等下次进来。
     */
    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset_title)
            .setMessage(R.string.settings_reset_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                getSharedPreferences(PREFS_FILE, MODE_PRIVATE).edit().clear().apply()
                applyOrientation(prefs)
                buildRows()
                Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ── 五种行 ─────────────────────────────────────────────

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

    private fun switchRow(
        title: String,
        desc: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = rowContainer()
        val switch = SwitchCompat(this).apply {
            isChecked = checked
            thumbTintList = getColorStateList(R.color.switch_thumb_tint)
            trackTintList = getColorStateList(R.color.switch_track_tint)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(titleBlock(title, desc), weighted())
        row.addView(switch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(12) })
        row.setOnClickListener { switch.toggle() }
        return withDivider(row)
    }

    /**
     * 枚举行：两三档的选项直接摆在行尾做分段控件，点一下就换，
     * 不用再弹一层对话框（原来那层单选框在小屏上盖满屏，选完还得回去看一眼）。
     */
    private fun choiceRow(
        title: String,
        desc: String?,
        options: List<String>,
        selected: Int,
        onChange: (Int) -> Unit,
    ): View {
        val row = rowContainer()
        row.addView(titleBlock(title, desc), weighted(if (desc.isNullOrBlank()) 1f else 1.2f))

        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = getDrawable(R.drawable.bg_segment_track)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val labels = ArrayList<TextView>(options.size)
        options.forEachIndexed { index, label ->
            val cell = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(5), dp(11), dp(5))
            }
            labels += cell
            track.addView(cell)
            cell.setOnClickListener {
                onChange(index)
                paintSegments(labels, index)
            }
        }
        paintSegments(labels, selected)
        row.addView(track)
        return withDivider(row)
    }

    /** 选中的那一格铺白底、字转橙；其余只留字 */
    private fun paintSegments(labels: List<TextView>, selected: Int) {
        labels.forEachIndexed { index, cell ->
            val on = index == selected
            cell.background = if (on) getDrawable(R.drawable.bg_segment_selected) else null
            cell.setTextColor(getColor(if (on) R.color.brand_accent else R.color.shell_ink_faint))
            cell.setTypeface(
                cell.typeface,
                if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
        }
    }

    private fun sliderRow(
        title: String,
        desc: String? = null,
        min: Int,
        max: Int,
        step: Int,
        initial: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.shell_ink))
        }, weighted())
        val valueView = TextView(this).apply {
            text = format(initial)
            textSize = 13f
            setTextColor(getColor(R.color.shell_ink_muted))
        }
        head.addView(valueView)
        block.addView(head)
        if (!desc.isNullOrBlank()) {
            block.addView(TextView(this).apply {
                text = desc
                textSize = 11f
                setTextColor(getColor(R.color.shell_ink_faint))
                setPadding(0, dp(3), 0, 0)
            })
        }

        val steps = (max - min) / step
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = ((initial - min) / step).coerceIn(0, steps)
            // 左右各留出滑块的半径：不留的话滑到两端会被卡片圆角切掉半个
            setPadding(dp(10), dp(6), dp(10), 0)
            splitTrack = false
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_accent))
            thumbTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_accent))
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.shell_divider))
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

    /** 只做导航的行：右侧一个箭头，点一下进去或弹确认 */
    private fun choiceRowWithValue(
        title: String,
        desc: String?,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val row = rowContainer()
        val texts = titleBlock(title, desc)
        if (destructive) {
            (texts.getChildAt(0) as TextView).setTextColor(getColor(R.color.brand_accent))
        }
        row.addView(texts, weighted())
        if (!destructive) row.addView(chevron())
        row.setOnClickListener { onClick() }
        return withDivider(row)
    }

    /**
     * 占位行：这一项界面稿里有、还没做。
     * 灰字 + 「未实现」小标，不可点——不装成能调的开关，也不藏起来让人找不到。
     */
    private fun placeholderRow(title: String, desc: String?): View {
        val row = rowContainer()
        row.isClickable = false
        row.isFocusable = false
        row.background = null
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(getColor(R.color.shell_ink_faint))
        })
        if (!desc.isNullOrBlank()) {
            texts.addView(TextView(this).apply {
                text = desc
                textSize = 11f
                setTextColor(getColor(R.color.shell_ink_faint))
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(texts, weighted())
        row.addView(TextView(this).apply {
            text = getString(R.string.settings_not_implemented)
            textSize = 9f
            setTextColor(getColor(R.color.shell_ink_faint))
            background = getDrawable(R.drawable.bg_badge_muted)
            setPadding(dp(7), dp(3), dp(7), dp(3))
        })
        return withDivider(row)
    }

    // ── 小工具 ─────────────────────────────────────────────

    private fun rowContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        setPadding(dp(16), dp(ROW_PADDING), dp(16), dp(ROW_PADDING))
        isClickable = true
        setBackgroundResource(selectableBackground())
    }

    private fun chevron(): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_right)
    }.also { it.layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)) }

    private fun titleBlock(title: String, desc: String?): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.shell_ink))
            })
            if (!desc.isNullOrBlank()) {
                addView(TextView(this@SettingsActivity).apply {
                    text = desc
                    textSize = 11f
                    setTextColor(getColor(R.color.shell_ink_faint))
                    setPadding(0, dp(3), dp(8), 0)
                })
            }
        }
    }

    private fun weighted(weight: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

    /** 每行底部叠一条发丝线：卡片里行多了才分得清边界 */
    private fun withDivider(row: View): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrapper.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.shell_divider))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return wrapper
    }

    private fun selectableBackground(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** 与书架、详情同一把尺子 */
        const val PAGE_MARGIN = 20
        const val ROW_PADDING = 14

        /** 阅读参数与界面开关都在这一份偏好里 */
        const val PREFS_FILE = "reader_prefs"
    }
}
