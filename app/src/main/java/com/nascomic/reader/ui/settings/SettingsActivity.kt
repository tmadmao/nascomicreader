package com.nascomic.reader.ui.settings

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlinx.coroutines.launch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivitySettingsBinding
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.library.SourceRepository
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.settings.ReaderPrefs
import com.nascomic.reader.settings.applyOrientation
import com.nascomic.reader.ui.about.AboutActivity
import com.nascomic.reader.ui.sources.SourcesActivity
import com.nascomic.reader.ui.widget.padBySystemBars

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

    /** 展开着哪几组。默认只开「阅读」 */
    private val expanded = mutableSetOf(GROUP_READING)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15 起强制边到边：自己让开状态栏与导航条
        binding.root.padBySystemBars()

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

        addSourceEntry(host)
        addGroup(host, GROUP_READING, getString(R.string.settings_section_reading), buildReadingRows())
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

    /**
     * 「书源管理」入口：设置页第一个，不是抽屉，点一下进第三页。
     * 下面那行小字报现状——本地 1 · WebDAV 1 · SMB 0。
     */
    private fun addSourceEntry(host: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            clipToOutline = true
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            setBackgroundResource(selectableBackground())
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, SourcesActivity::class.java))
            }
        }
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
        card.addView(row)

        host.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(PAGE_MARGIN)
            marginEnd = dp(PAGE_MARGIN)
            topMargin = dp(4)
            bottomMargin = dp(2)
        })

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
    }

    private fun addGroup(host: LinearLayout, key: String, title: String, rows: List<View>) {
        val isOpen = expanded.contains(key)
        host.addView(groupHeader(title, rows.size, isOpen) {
            if (isOpen) expanded.remove(key) else expanded.add(key)
            buildRows()
        })
        if (!isOpen) return

        // 展开的内容装进一张白卡：行与行之间一条发丝线，卡片自己收边
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
            topMargin = dp(2)
            bottomMargin = dp(4)
        })
    }

    private fun groupHeader(title: String, count: Int, isOpen: Boolean, onToggle: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(PAGE_MARGIN), dp(16), dp(PAGE_MARGIN), dp(10))
            background = getDrawable(R.drawable.bg_group_header)
            setOnClickListener { onToggle() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.shell_ink))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.settings_group_count, count)
            textSize = 10f
            setTextColor(getColor(R.color.shell_ink_faint))
            setPadding(0, 0, dp(6), 0)
        })
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_down)
            rotation = if (isOpen) 180f else 0f
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
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
            switchRow(
                title = getString(R.string.settings_keep_screen_on),
                desc = getString(R.string.settings_keep_screen_on_desc),
                checked = reader.keepScreenOn,
            ) { checked -> reader.keepScreenOn = checked }
        )
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

    /** 只做导航的行：右侧给一个箭头，点一下进去或弹对话框 */
    private fun choiceRowWithValue(
        title: String,
        desc: String?,
        value: String,
        onClick: () -> Unit,
    ): View {
        val row = rowContainer()
        row.addView(titleBlock(title, desc), weighted())
        if (value.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(getColor(R.color.shell_ink_faint))
                setPadding(0, 0, dp(6), 0)
            })
        }
        row.addView(chevron())
        row.setOnClickListener { onClick() }
        return withDivider(row)
    }

    // ---- 三种行 ----

    private companion object {
        const val GROUP_READING = "reading"
        const val GROUP_OTHER = "other"
        const val GROUP_ABOUT = "about"
        val GROUP_ALL = listOf(GROUP_READING, GROUP_OTHER, GROUP_ABOUT)

        /** 页面左右留白与行高：与书架、详情同一把尺子 */
        const val PAGE_MARGIN = 20
        const val ROW_PADDING = 14
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
            thumbTintList = getColorStateList(R.color.switch_thumb_tint)
            trackTintList = getColorStateList(R.color.switch_track_tint)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(texts, weighted())
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

    // ---- 小工具 ----

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
}
