package com.nascomic.reader.ui.author

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nascomic.reader.R
import com.nascomic.reader.library.data.AuthorCount

/**
 * 作者合并面板：把一位作者并到另一位（或一个新的正名）名下。
 *
 * 选已有作者就是合并，输入一个列表里没有的名字就是新建正名——同一个动作、同一颗按钮，
 * 不另开一页。面板只负责选目标，真正落库与撤销提示由作家列表页处理。
 */
internal class AuthorMergeSheet(
    context: Context,
    private val source: String,
    candidates: List<AuthorCount>,
    private val onConfirm: (String) -> Unit,
) : BottomSheetDialog(context) {

    private val all = candidates.filter { it.name != source }
    private val density = context.resources.displayMetrics.density
    private val rows = LinearLayout(context)
    private val search: EditText
    private val confirm: TextView

    /** 选中的目标：已有作者名，或输入的新的正名 */
    private var picked: String? = null
    private var pickedIsNew = false

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(14))
            // 顶部两角跟主题里的对话框同一个圆角尺度，底边贴屏幕
            setBackgroundResource(R.drawable.bg_sheet_top)
        }
        root.addView(grabber(context))

        root.addView(TextView(context).apply {
            text = context.getString(R.string.author_merge_title)
            textSize = 17f
            setTextColor(context.getColor(R.color.shell_ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(10), 0, 0)
        })

        root.addView(TextView(context).apply {
            text = context.getString(R.string.author_merge_desc, source)
            textSize = 11.5f
            setTextColor(context.getColor(R.color.shell_ink_muted))
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        })

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = context.getDrawable(R.drawable.bg_merge_search)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
            ).apply { topMargin = dp(14) }
        }
        search = EditText(context).apply {
            hint = context.getString(R.string.author_merge_search_hint)
            textSize = 12.5f
            setTextColor(context.getColor(R.color.shell_ink))
            setHintTextColor(context.getColor(R.color.shell_ink_faint))
            background = null
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        search.doAfterTextChanged { editable ->
            // 上一轮选的是「新建 XXX」，输入改了就不再是同一个名字
            if (pickedIsNew && picked != editable?.toString()?.trim()) picked = null
            fill(editable?.toString().orEmpty())
        }
        box.addView(search)
        root.addView(box)

        rows.orientation = LinearLayout.VERTICAL
        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(6) }
            addView(rows)
        }
        root.addView(scroller)

        // 按钮上方一条分割线，跟稿图一致：动作区与名单分开
        root.addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.shell_divider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = dp(10) }
        })

        confirm = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(12) }
            setOnClickListener {
                val target = picked ?: return@setOnClickListener
                onConfirm(target)
                dismiss()
            }
        }
        root.addView(confirm)

        root.addView(TextView(context).apply {
            text = context.getString(R.string.author_merge_undo_hint)
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.shell_ink_faint))
            setPadding(0, dp(8), 0, 0)
        })

        setContentView(root)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // 面板高度按内容收：作者只有一两位时不留一大片空白，多了再封顶。
        // 靠 wrap_content 又不行——列表会被压成一条，按钮贴着屏幕底。
        val listHeight = all.size.coerceIn(1, 6) * dp(54)
        val height = minOf(
            dp(250) + listHeight,
            dp(520),
            (context.resources.displayMetrics.heightPixels * 0.62f).toInt(),
        ).coerceAtLeast(dp(320))
        // 内容父级是 FrameLayout，布局参数得是它的子类（同时也满足 BottomSheet 的外边距测量）
        root.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, height
        )
        // 弹层第一次显示时才拿到真正生效的 behavior；之前设的会被默认折叠态盖掉
        setOnShowListener {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.peekHeight = height
            // 弹层容器自带白底方角，压掉它，圆角由内容自己的背景负责
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        fill("")
        updateButton()
    }

    private fun grabber(context: Context): View = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
        )
        addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.shell_stroke))
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER }
        })
    }

    /** 列表按输入过滤；输入的不是现有作者时，最上面给一行「新建」 */
    private fun fill(query: String) {
        rows.removeAllViews()
        val q = query.trim()
        val visible = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }

        if (q.isNotEmpty() && all.none { it.name.equals(q, ignoreCase = true) } && q != source) {
            rows.addView(createRow(q))
        }

        if (visible.isEmpty() && q.isEmpty()) {
            rows.addView(TextView(rows.context).apply {
                text = rows.context.getString(R.string.author_merge_empty)
                textSize = 12f
                setTextColor(rows.context.getColor(R.color.shell_ink_faint))
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, 0)
            })
            return
        }
        visible.forEach { rows.addView(authorRow(it)) }
    }

    private fun authorRow(item: AuthorCount): View {
        val context = rows.context
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
            isClickable = true
            setOnClickListener {
                picked = item.name
                pickedIsNew = false
                fill(search.text.toString())
                updateButton()
            }
        }
        line.addView(TextView(context).apply {
            text = item.name
            textSize = 13.5f
            setTextColor(context.getColor(R.color.shell_ink))
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        line.addView(TextView(context).apply {
            text = context.getString(R.string.author_row_count, item.total)
            textSize = 10.5f
            setTextColor(context.getColor(R.color.shell_ink_faint))
            setPadding(dp(8), 0, 0, 0)
        })
        line.addView(TextView(context).apply {
            text = if (picked == item.name && !pickedIsNew) "✓" else " "
            textSize = 15f
            setTextColor(context.getColor(R.color.brand_accent))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        return line
    }

    private fun createRow(name: String): View {
        val context = rows.context
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener {
                picked = name
                pickedIsNew = true
                fill(search.text.toString())
                updateButton()
            }
        }
        line.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = context.getString(R.string.author_merge_create, name)
                textSize = 13.5f
                setTextColor(context.getColor(R.color.brand_accent))
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.author_merge_create_hint)
                textSize = 10.5f
                setTextColor(context.getColor(R.color.shell_ink_faint))
            })
        })
        line.addView(TextView(context).apply {
            text = if (pickedIsNew && picked == name) "✓" else " "
            textSize = 15f
            setTextColor(context.getColor(R.color.brand_accent))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        return line
    }

    private fun updateButton() {
        val target = picked
        confirm.text = if (target == null) {
            context.getString(R.string.author_merge_need_target)
        } else {
            context.getString(R.string.author_merge_confirm, target)
        }
        confirm.isEnabled = target != null
        confirm.alpha = if (target == null) 0.5f else 1f
        confirm.setBackgroundResource(
            if (target == null) R.drawable.bg_chip_unselected else R.drawable.bg_merge_primary
        )
        confirm.setTextColor(
            context.getColor(if (target == null) R.color.shell_ink_faint else R.color.shell_surface)
        )
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
