package com.nascomic.reader.ui.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * 固定宽高比的图片。书封必须统一比例，否则网格会参差不齐。
 * 宽度由父容器决定，高度按 [heightRatio] 算（默认 4:3，即高是宽的 1.333 倍）。
 */
class AspectImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var heightRatio: Float = 4f / 3f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * heightRatio).toInt()
        setMeasuredDimension(width, height)
    }
}
