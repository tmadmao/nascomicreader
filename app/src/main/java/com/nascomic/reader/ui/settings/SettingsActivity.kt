package com.nascomic.reader.ui.settings

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
import com.nascomic.reader.ui.sources.SourcesActivity
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** 从阅读页进来时带书的 id：这一页的阅读参数只改这本书 */
    private val bookId by lazy { intent.getLongExtra(EXTRA_BOOK_ID, 0L) }
    private val bookTitle by lazy { intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty() }

    private val reader by lazy { ReaderPrefs(this, bookId) }

    /** 书架列数可选档：0 = 自动，其余定死 */
    private val columnChoices = listOf(0, 3, 4, 5, 6, 8)

    /** 磁盘缓存上限可选档（GB）：0 = 按剩余空间自动 */
    private val cacheGbOptions = listOf(0, 1, 2, 3, 5)

    /** 内存缓存上限可选档（MB）：0 = 按堆自动 */
    private val cacheMemOptions = listOf(0, 64, 128, 256, 512)
    private val db by lazy { ReaderDatabase.get(this) }

    /** 页的磁盘缓存：缓存管理里要报占用、要清空 */
    private val diskCache by lazy { com.nascomic.reader.ui.reader.DiskPageCache(this) }
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

        // 从阅读页进来时说明白：下面这些只改当前这本，不动全局默认
        if (bookId > 0) {
            host.addView(TextView(this).apply {
                text = getString(R.string.settings_book_scope, bookTitle)
                textSize = 11f
                setTextColor(getColor(R.color.shell_ink_muted))
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(PAGE_MARGIN)
                    marginEnd = dp(PAGE_MARGIN)
                    topMargin = dp(12)
                }
            })
        }

        addGroup(host, R.string.settings_group_library, buildLibraryRows())
        addGroup(host, R.string.settings_group_flip, buildFlipRows())
        addGroup(host, R.string.settings_group_zoom, buildZoomRows())
        addGroup(host, R.string.settings_group_layout, buildLayoutRows())
        addGroup(host, R.string.settings_group_load, buildLoadRows())
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

    /**
     * 书库与仓库：只留「书源管理」。
     *
     * 扫描规则、历史、封面这些曾经堆在这里，现在各归各位：
     * 章的正倒序在详情页的目录上，阅读历史在首页，封面改成书架长按那本书再换。
     */
    private fun buildLibraryRows(): List<View> = collect {
        addView(sourceEntryRow())
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
            choiceRow(
                title = getString(R.string.settings_read_direction),
                desc = getString(R.string.settings_read_direction_desc),
                options = listOf(
                    getString(R.string.settings_direction_vertical),
                    getString(R.string.settings_direction_right),
                    getString(R.string.settings_direction_left),
                ),
                selected = reader.scrollMode,
            ) { index -> reader.scrollMode = index }
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
            choiceRow(
                title = getString(R.string.settings_jump_distance),
                desc = getString(R.string.settings_jump_distance_desc),
                options = listOf(
                    getString(R.string.settings_jump_in_screen),
                    getString(R.string.settings_jump_half),
                    getString(R.string.settings_jump_full),
                ),
                selected = reader.flipStep,
            ) { index -> reader.flipStep = index }
        )
        addView(
            switchRow(
                title = getString(R.string.settings_volume_keys),
                desc = getString(R.string.settings_volume_keys_desc),
                checked = reader.keyFlip,
            ) { checked -> reader.keyFlip = checked }
        )
        addView(
            choiceRowWithValue(
                title = getString(R.string.settings_key_prev),
                desc = keyLabel(reader.keyPrevCode),
            ) { captureKey(R.string.settings_key_prev) { code -> reader.keyPrevCode = code } }
        )
        addView(
            choiceRowWithValue(
                title = getString(R.string.settings_key_next),
                desc = keyLabel(reader.keyNextCode),
            ) { captureKey(R.string.settings_key_next) { code -> reader.keyNextCode = code } }
        )
        addView(
            choiceRow(
                title = getString(R.string.settings_wheel),
                desc = getString(R.string.settings_wheel_desc),
                options = listOf(
                    getString(R.string.settings_wheel_off),
                    getString(R.string.settings_wheel_up_prev),
                    getString(R.string.settings_wheel_up_next),
                ),
                selected = reader.wheelMode,
            ) { index -> reader.wheelMode = index }
        )
        addView(
            switchRow(
                title = getString(R.string.settings_zone_hint),
                desc = getString(R.string.settings_zone_hint_desc),
                checked = reader.zoneHint,
            ) { checked -> reader.zoneHint = checked }
        )
        addView(
            switchRow(
                title = getString(R.string.settings_turn_animation),
                desc = getString(R.string.settings_turn_animation_desc),
                checked = reader.turnAnimation,
            ) { checked -> reader.turnAnimation = checked }
        )
    }

    /** 缩放：双击 / 双指 / 长按，以及放大后往哪一角落位 */
    private fun buildZoomRows(): List<View> = collect {
        addView(
            switchRow(
                title = getString(R.string.settings_double_tap_zoom),
                desc = getString(R.string.settings_double_tap_zoom_desc),
                checked = reader.doubleZoom,
            ) { checked -> reader.doubleZoom = checked }
        )
        addView(
            choiceRow(
                title = getString(R.string.settings_double_tap_anchor_h),
                desc = getString(R.string.settings_double_tap_anchor_desc),
                options = focusOptions(),
                selected = reader.doubleZoomFocusH,
            ) { index -> reader.doubleZoomFocusH = index }
        )
        addView(
            choiceRow(
                title = getString(R.string.settings_double_tap_anchor_v),
                desc = getString(R.string.settings_anchor_v_desc),
                options = focusOptions(),
                selected = reader.doubleZoomFocusV,
            ) { index -> reader.doubleZoomFocusV = index }
        )
        addView(
            sliderRow(
                title = getString(R.string.settings_double_zoom_factor),
                min = 12,
                max = 30,
                step = 1,
                initial = (reader.doubleZoomFactor * 10).toInt(),
                format = { getString(R.string.settings_zoom_factor_value, it / 10f) },
            ) { value -> reader.doubleZoomFactor = value / 10f }
        )
        addView(
            switchRow(
                title = getString(R.string.settings_pinch_zoom),
                desc = getString(R.string.settings_pinch_zoom_desc),
                checked = reader.pinchZoom,
            ) { checked -> reader.pinchZoom = checked }
        )
        addView(
            switchRow(
                title = getString(R.string.settings_long_press_zoom),
                desc = getString(R.string.settings_long_press_zoom_desc),
                checked = reader.holdZoom,
            ) { checked -> reader.holdZoom = checked }
        )
        addView(
            choiceRow(
                title = getString(R.string.settings_hold_zoom_anchor),
                desc = null,
                options = focusOptions(),
                selected = reader.holdZoomFocus,
            ) { index -> reader.holdZoomFocus = index }
        )
        addView(
            sliderRow(
                title = getString(R.string.settings_hold_zoom_factor),
                min = 12,
                max = 30,
                step = 1,
                initial = (reader.holdZoomFactor * 10).toInt(),
                format = { getString(R.string.settings_zoom_factor_value, it / 10f) },
            ) { value -> reader.holdZoomFactor = value / 10f }
        )
    }

    /** 放大后对准的五个落点，双击与长按共用 */
    private fun focusOptions(): List<String> = listOf(
        getString(R.string.settings_focus_tap),
        getString(R.string.settings_focus_screen_center),
        getString(R.string.settings_focus_page_center),
        getString(R.string.settings_focus_top_left),
        getString(R.string.settings_focus_top_right),
    )

    /** 版面与显示：阅读时真正会变样子的一批参数 */
    private fun buildLayoutRows(): List<View> = collect {
        addView(
            choiceRow(
                title = getString(R.string.settings_page_fit),
                desc = getString(R.string.settings_page_fit_desc),
                options = listOf(
                    getString(R.string.settings_page_fit_width),
                    getString(R.string.settings_page_fit_screen),
                ),
                selected = if (reader.fitWidth) 0 else 1,
            ) { index -> reader.fitWidth = index == 0 }
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
                desc = getString(R.string.settings_trim_level_desc),
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
                max = 25,
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
                    getString(R.string.settings_canvas_system),
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
            switchRow(
                title = getString(R.string.settings_skip_detail),
                desc = getString(R.string.settings_skip_detail_desc),
                checked = prefs.skipDetail,
            ) { checked -> prefs.skipDetail = checked }
        )

        addView(
            choiceRow(
                title = getString(R.string.settings_shelf_columns),
                desc = getString(R.string.settings_shelf_columns_desc),
                options = columnChoices.map {
                    if (it == 0) getString(R.string.settings_columns_auto) else it.toString()
                },
                selected = columnChoices.indexOf(prefs.shelfColumns).coerceAtLeast(0),
            ) { index -> prefs.shelfColumns = columnChoices[index] }
        )
    }

    /**
     * 加载管理：解码口径、预加载、磁盘缓存。
     *
     * 这一组决定「一页图怎么进内存、怎么留在磁盘」。翻页顺不顺、放大糊不糊、
     * 读过一遍的书要不要重新下载，全在这几项上。
     */
    private fun buildLoadRows(): List<View> = collect {
        addView(
            switchRow(
                title = getString(R.string.settings_native_decode),
                desc = getString(R.string.settings_native_decode_desc),
                checked = reader.nativeDecode,
            ) { checked -> reader.nativeDecode = checked }
        )

        addView(
            switchRow(
                title = getString(R.string.settings_argb),
                desc = getString(R.string.settings_argb_desc),
                checked = reader.argb8888,
            ) { checked -> reader.argb8888 = checked }
        )

        addView(
            sliderRow(
                title = getString(R.string.settings_prefetch_prev),
                desc = getString(R.string.settings_prefetch_prev_desc),
                min = 0,
                max = 12,
                step = 1,
                initial = reader.prefetchPrev,
                format = { pagesText(it) },
            ) { value -> reader.prefetchPrev = value }
        )

        addView(
            sliderRow(
                title = getString(R.string.settings_prefetch_next),
                desc = getString(R.string.settings_prefetch_next_desc),
                min = 0,
                max = 12,
                step = 1,
                initial = reader.prefetchNext,
                format = { pagesText(it) },
            ) { value -> reader.prefetchNext = value }
        )

        addView(noteRow(getString(R.string.settings_prefetch_hint)))

        // 内存上限：自动跟堆走；手选档位在 ReaderPrefs 里夹到堆的 1/8–1/2
        addView(
            choiceRow(
                title = getString(R.string.settings_cache_mem),
                desc = getString(R.string.settings_cache_mem_desc),
                options = cacheMemOptions.map {
                    if (it == 0) getString(R.string.settings_cache_gb_auto)
                    else getString(R.string.settings_cache_mem_value, it)
                },
                selected = cacheMemOptions.indexOfFirst { it == reader.cacheMb }.coerceAtLeast(0),
            ) { index -> reader.cacheMb = cacheMemOptions[index] }
        )

        // 磁盘缓存上限：自动跟剩余空间走（1–5 GB），也能定死
        addView(
            choiceRow(
                title = getString(R.string.settings_cache_gb),
                desc = getString(R.string.settings_cache_gb_desc),
                options = cacheGbOptions.map {
                    if (it == 0) getString(R.string.settings_cache_gb_auto)
                    else getString(R.string.settings_cache_gb_value, it)
                },
                selected = cacheGbOptions.indexOfFirst { it == reader.cacheGb }.coerceAtLeast(0),
                badge = cacheUsedText(),
                onBadgeClick = { showCacheDetail() },
            ) { index -> reader.cacheGb = cacheGbOptions[index] }
        )
    }

    private fun pagesText(pages: Int): String =
        if (pages == 0) getString(R.string.settings_prefetch_off)
        else getString(R.string.settings_prefetch_value, pages)

    /** 一条只有说明的灰字，用来解释上面那几项怎么算 */
    private fun noteRow(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(getColor(R.color.shell_ink_faint))
        // 不铺卡片底：它就在组里那张卡内部，再铺一层会看着像另起一块
        setPadding(dp(16), dp(10), dp(16), dp(12))
    }

    /** 磁盘页缓存 + 为打开格式落下的副本，一共占多少（内存那份不进这里，它一退阅读就没了） */
    private fun cacheUsedText(): String = getString(
        R.string.settings_cache_used,
        formatBytes(diskBytes()),
    )

    private fun diskBytes(): Long =
        diskCache.totalBytes() + com.nascomic.reader.format.LocalCopies.totalBytes(this)

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return getString(R.string.settings_cache_empty)
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) return "%.2f GB".format(gb)
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1024} KB"
    }

    /**
     * 缓存明细：一共占多少、上限多少，再按书库分开列。
     *
     * 只报一个总数看不出「哪套书库在吃空间」，删来源时也不知道能省回多少，
     * 所以按来源分组——一页页都是那套书库的。
     */
    private fun showCacheDetail() {
        lifecycleScope.launch {
            val usage = withContext(Dispatchers.IO) { diskCache.bytesByBook() }
            val books = db.bookDao().allSorted().associateBy { it.id }
            val sources = repository.list().associateBy { it.id }

            val bySource = HashMap<Long, LongArray>()
            var total = 0L
            for ((bookId, bytes) in usage) {
                val book = books[bookId] ?: continue
                val slot = bySource.getOrPut(book.sourceId) { LongArray(2) }
                slot[0] += bytes
                slot[1] += 1
                total += bytes
            }

            val box = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(6), dp(20), dp(2))
            }
            box.addView(TextView(this@SettingsActivity).apply {
                text = getString(
                    R.string.settings_cache_page_summary,
                    formatBytes(total),
                    formatBytes(reader.diskBudgetBytes(filesDir.usableSpace)),
                )
                textSize = 13f
                setTextColor(getColor(R.color.shell_ink_muted))
                setPadding(0, 0, 0, dp(12))
            })

            if (bySource.isEmpty()) {
                box.addView(TextView(this@SettingsActivity).apply {
                    text = getString(R.string.settings_cache_page_empty)
                    textSize = 12f
                    setTextColor(getColor(R.color.shell_ink_faint))
                    setPadding(0, 0, 0, dp(8))
                })
            } else {
                for ((sourceId, slot) in bySource.entries.sortedByDescending { it.value[0] }) {
                    val label = sources[sourceId]?.label ?: getString(R.string.settings_sources_entry)
                    box.addView(LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(7), 0, dp(7))
                        addView(TextView(this@SettingsActivity).apply {
                            text = getString(
                                R.string.settings_cache_page_source,
                                label,
                                slot[1].toInt(),
                            )
                            textSize = 13f
                            setTextColor(getColor(R.color.shell_ink))
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }, weighted())
                        addView(TextView(this@SettingsActivity).apply {
                            text = formatBytes(slot[0])
                            textSize = 13f
                            setTextColor(getColor(R.color.shell_ink_muted))
                        })
                    })
                }
            }

            val content = android.widget.ScrollView(this@SettingsActivity).apply { addView(box) }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_cache_page_title)
                .setView(content)
                .setPositiveButton(R.string.settings_cache_page_set) { _, _ -> showCacheLimitDialog() }
                .setNeutralButton(R.string.settings_cache_page_clear) { _, _ -> confirmClearCache() }
                .setNegativeButton(R.string.settings_cache_page_close, null)
                .show()
        }
    }

    /** 改磁盘缓存上限。改小不会立刻删，等下次写页时按新上限收 */
    private fun showCacheLimitDialog() {
        val labels = cacheGbOptions.map {
            if (it == 0) getString(R.string.settings_cache_gb_auto)
            else getString(R.string.settings_cache_gb_value, it)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_cache_gb)
            .setSingleChoiceItems(
                labels,
                cacheGbOptions.indexOfFirst { it == reader.cacheGb }.coerceAtLeast(0),
            ) { dialog, which ->
                reader.cacheGb = cacheGbOptions[which]
                dialog.dismiss()
                // 调小之后立刻按新上限收一次，不然要等下次进阅读才见效
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        com.nascomic.reader.ui.reader.DiskPageCache(
                            this@SettingsActivity,
                            reader.diskBudgetBytes(filesDir.usableSpace),
                        ).trim(0L)
                    }
                    buildRows()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setMessage(R.string.settings_cache_clear_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                com.nascomic.reader.ui.reader.PageCacheHolder.current()?.clear()
                diskCache.clearAll()
                com.nascomic.reader.format.LocalCopies.clear(this)
                buildRows()
                Toast.makeText(
                    this,
                    R.string.settings_cache_cleared,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .show()
    }

    /** 其他：关于、恢复默认。开源许可在关于页里，不在这里再摆一个 */
    private fun buildOtherRows(): List<View> = collect {
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

        // 当前这本单独调过参数时，给一条路回到全局默认
        if (bookId > 0 && reader.hasBookOverrides()) {
            addView(
                choiceRowWithValue(
                    title = getString(R.string.settings_book_reset),
                    desc = getString(R.string.settings_book_reset_desc),
                    destructive = true,
                ) {
                    reader.clearBookOverrides()
                    buildRows()
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_book_reset_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            )
        }
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
     * 枚举行：选项铺在标题下方做分段控件，点一下就换，不弹对话框。
     * 标题、说明、控件各占一行，和上面的滑杆一致——档位多了也挤不到标题，
     * 每格按等宽均分，窄屏上不会溢出行外。
     */
    private fun choiceRow(
        title: String,
        desc: String?,
        options: List<String>,
        selected: Int,
        badge: String? = null,
        onBadgeClick: (() -> Unit)? = null,
        onChange: (Int) -> Unit,
    ): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        head.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.shell_ink))
        }, weighted())
        if (badge != null) {
            // 右侧这枚胶囊是可以点的：缓存管理就是靠它清空
            head.addView(TextView(this).apply {
                text = badge
                textSize = 11f
                setTextColor(getColor(R.color.brand_accent))
                background = getDrawable(R.drawable.bg_chip_unselected)
                setPadding(dp(9), dp(4), dp(9), dp(4))
                isClickable = onBadgeClick != null
                if (onBadgeClick != null) {
                    setOnClickListener { onBadgeClick() }
                }
            })
        }
        block.addView(head)
        if (!desc.isNullOrBlank()) {
            block.addView(TextView(this).apply {
                text = desc
                textSize = 11f
                setTextColor(getColor(R.color.shell_ink_faint))
                setPadding(0, dp(3), 0, 0)
            })
        }

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
                setPadding(0, dp(5), 0, dp(5))
            }
            labels += cell
            track.addView(
                cell,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            cell.setOnClickListener {
                onChange(index)
                paintSegments(labels, index)
            }
        }
        paintSegments(labels, selected)
        block.addView(track, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        return withDivider(block)
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
                android.content.res.ColorStateList.valueOf(getColor(R.color.slider_track))
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
     * 绑按键：弹一层只剩键盘输入的框，按下第一个非返回键就绑上。
     * 音量键也能绑回来，改错了按音量键即可恢复。
     */
    private fun captureKey(titleRes: Int, onPicked: (Int) -> Unit) {
        val dialog = android.app.Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(16))
            addView(TextView(this@SettingsActivity).apply {
                text = getString(R.string.settings_key_capture_title, getString(titleRes))
                textSize = 15f
                setTextColor(getColor(R.color.shell_ink))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = getString(R.string.settings_key_capture_hint)
                textSize = 12f
                setTextColor(getColor(R.color.shell_ink_muted))
                setPadding(0, dp(8), 0, 0)
            })
        }
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(getDrawable(R.drawable.bg_card))
        dialog.show()
        box.isFocusableInTouchMode = true
        box.requestFocus()
        box.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK) {
                onPicked(keyCode)
                dialog.dismiss()
                true
            } else {
                false
            }
        }
    }

    /** 键码翻成人话。屏幕上只认音量上下，其余按系统名去掉前缀 */
    private fun keyLabel(code: Int): String = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> getString(R.string.key_volume_up)
        KeyEvent.KEYCODE_VOLUME_DOWN -> getString(R.string.key_volume_down)
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
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

    companion object {
        /** 从阅读页进来时带的书：带了就只改这一本 */
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_BOOK_TITLE = "book_title"

        /** 与书架、详情同一把尺子 */
        const val PAGE_MARGIN = 20
        const val ROW_PADDING = 14

        /** 阅读参数与界面开关都在这一份偏好里 */
        const val PREFS_FILE = "reader_prefs"
    }
}
