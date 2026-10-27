package com.nascomic.reader.ui.settings

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivitySettingsBinding
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.settings.ReaderPrefs

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

        host.addView(sectionHeader(getString(R.string.settings_section_other)))

        host.addView(
            switchRow(
                title = getString(R.string.settings_skip_detail),
                desc = getString(R.string.settings_skip_detail_desc),
                checked = prefs.skipDetail,
            ) { checked -> prefs.skipDetail = checked }
        )
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
