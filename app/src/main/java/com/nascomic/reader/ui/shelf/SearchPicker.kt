package com.nascomic.reader.ui.shelf

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nascomic.reader.R

/**
 * 带搜索的底部选择器。两处都用它：
 * - 往系列里加卷：搜书名，多选，底部一颗「加入 N 卷」；
 * - 把书加入已有系列：搜系列名，单选，点一行就进去。
 *
 * 用代码搭而不是铺两套 XML：两种用法只差一个复选与一颗按钮，分开写必然走散。
 */
class SearchPicker(
    context: Context,
    private val title: String,
    private val hint: String,
    items: List<Item>,
    private val multi: Boolean,
    private val onPick: (Item) -> Unit,
    private val onMultiPick: (List<Item>) -> Unit = {},
) : BottomSheetDialog(context) {

    class Item(val id: Long, val title: String, val subtitle: String)

    private val all = items
    private val chosen = LinkedHashSet<Long>()
    private val rows = LinearLayout(context)
    private val confirm: TextView
    private val search: EditText

    init {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            setBackgroundColor(context.getColor(R.color.shell_surface))
        }
        root.addView(handleBar(context, density))
        root.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(context.getColor(R.color.shell_ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(10))
        })

        // 搜索只在这一层里起作用，不改动触发它的页面
        val searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = context.getDrawable(R.drawable.bg_search_bar)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
            )
        }
        searchBox.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(context.getColor(R.color.shell_ink_faint))
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15))
        })
        search = EditText(context).apply {
            this.hint = this@SearchPicker.hint
            textSize = 12.5f
            setTextColor(context.getColor(R.color.shell_ink))
            setHintTextColor(context.getColor(R.color.shell_ink_faint))
            background = null
            setSingleLine()
            setPadding(dp(9), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = fill(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        searchBox.addView(search)
        root.addView(searchBox)

        rows.orientation = LinearLayout.VERTICAL
        val scroller = android.widget.ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(rows)
        }
        root.addView(scroller)

        confirm = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 13.5f
            setTextColor(context.getColor(R.color.shell_surface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = context.getDrawable(R.drawable.bg_primary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(10) }
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                val picked = all.filter { it.id in chosen }
                onMultiPick(picked)
                dismiss()
            }
        }
        if (multi) root.addView(confirm)

        setContentView(root)
        fill("")
        updateConfirm()
    }

    private fun handleBar(context: Context, density: Float): View {
        val wrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (20 * density).toInt()
            )
        }
        wrapper.addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.shell_stroke))
            layoutParams = FrameLayout.LayoutParams((36 * density).toInt(), (4 * density).toInt()).apply {
                gravity = Gravity.CENTER
            }
        })
        return wrapper
    }

    private fun fill(query: String) {
        rows.removeAllViews()
        val q = query.trim()
        val list = if (q.isEmpty()) all else all.filter {
            it.title.contains(q, ignoreCase = true) || it.subtitle.contains(q, ignoreCase = true)
        }
        if (list.isEmpty()) {
            rows.addView(TextView(rows.context).apply {
                text = rows.context.getString(R.string.picker_no_match)
                textSize = 12f
                setTextColor(rows.context.getColor(R.color.shell_ink_faint))
                gravity = Gravity.CENTER
                setPadding(0, (28 * rows.resources.displayMetrics.density).toInt(), 0, 0)
            })
            return
        }
        for (item in list) rows.addView(row(item))
    }

    private fun row(item: Item): View {
        val context = rows.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
            isClickable = true
            setOnClickListener {
                if (multi) {
                    if (!chosen.add(item.id)) chosen.remove(item.id)
                    fill(search.text.toString())
                    updateConfirm()
                } else {
                    onPick(item)
                    dismiss()
                }
            }
        }
        if (multi) {
            line.addView(TextView(context).apply {
                text = if (item.id in chosen) "☑" else "☐"
                textSize = 15f
                setTextColor(
                    context.getColor(
                        if (item.id in chosen) R.color.brand_accent else R.color.shell_ink_faint
                    )
                )
                layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }
        line.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = item.title
                textSize = 13.5f
                setTextColor(context.getColor(R.color.shell_ink))
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (item.subtitle.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = item.subtitle
                    textSize = 10.5f
                    setTextColor(context.getColor(R.color.shell_ink_faint))
                })
            }
        })
        return line
    }

    private fun updateConfirm() {
        if (!multi) return
        confirm.text = if (chosen.isEmpty()) {
            confirm.context.getString(R.string.picker_confirm_none)
        } else {
            confirm.context.getString(R.string.picker_confirm, chosen.size)
        }
        confirm.isEnabled = chosen.isNotEmpty()
        confirm.alpha = if (chosen.isEmpty()) 0.5f else 1f
    }
}
