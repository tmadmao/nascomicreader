package com.nascomic.reader.ui.sources

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityBookModeBinding
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.ui.widget.padBySystemBars

/**
 * 新增书源的第 2 步：这个书库是连载还是单行本。
 *
 * 放在选目录之前——它决定「什么算一本书」，先看目录再改粒度，用户会怀疑刚才看到的东西。
 * 来源类型（本机文件夹 / WebDAV / SMB）只影响顶栏副标题，不改变这一页的内容。
 */
class BookModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookModeBinding
    private var chosen = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBySystemBars()

        val kind = intent.getIntExtra(EXTRA_KIND, SourceEntity.KIND_LOCAL)
        binding.subtitle.text = getString(R.string.book_mode_step, kindLabel(kind))

        binding.back.setOnClickListener { finish() }
        binding.cardSerial.setOnClickListener { select(SourceEntity.MODE_SERIAL) }
        binding.cardSingle.setOnClickListener { select(SourceEntity.MODE_SINGLE) }
        binding.primary.setOnClickListener {
            if (chosen < 0) return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_MODE, chosen))
            finish()
        }
        render()
    }

    private fun select(mode: Int) {
        chosen = mode
        render()
    }

    private fun render() {
        val serial = chosen == SourceEntity.MODE_SERIAL
        val single = chosen == SourceEntity.MODE_SINGLE

        // 选中态用同一张底图 + tint，不新增 drawable
        binding.cardSerial.backgroundTintList = tintOf(serial)
        binding.cardSingle.backgroundTintList = tintOf(single)
        binding.serialName.setTextColor(color(if (serial) R.color.brand_accent else R.color.shell_ink))
        binding.singleName.setTextColor(color(if (single) R.color.brand_accent else R.color.shell_ink))

        markRing(binding.serialRing, serial)
        markRing(binding.singleRing, single)

        val ready = chosen >= 0
        binding.primary.text = getString(
            if (ready) R.string.book_mode_next else R.string.book_mode_choose_first
        )
        binding.primary.isEnabled = ready
        // 未选是「灰底灰字」，不是把墨色按钮调淡——淡掉的墨色看着像半透明的黑
        binding.primary.setBackgroundResource(
            if (ready) R.drawable.bg_primary else R.drawable.bg_chip_unselected
        )
        binding.primary.setTextColor(
            color(if (ready) R.color.shell_surface else R.color.shell_ink_faint)
        )
        // 未选时压成浅灰实底，一眼看出「还差一步」而不是一个能点的空按钮
        binding.primary.backgroundTintList = if (ready) null else android.content.res.ColorStateList.valueOf(
            color(R.color.shell_stroke)
        )
    }

    /** 选中：橙底白勾；未选：空心细环 */
    private fun markRing(ring: View, on: Boolean) {
        if (on) {
            ring.setBackgroundResource(R.drawable.bg_check_circle)
            (ring as android.widget.ImageView).setImageResource(R.drawable.ic_check)
            ring.setPadding(dp(4), dp(4), dp(4), dp(4))
        } else {
            ring.setBackgroundResource(R.drawable.bg_ring_faint)
            (ring as android.widget.ImageView).setImageDrawable(null)
            ring.setPadding(0, 0, 0, 0)
        }
    }

    private fun tintOf(on: Boolean) = if (on) {
        android.content.res.ColorStateList.valueOf(color(R.color.book_mode_selected))
    } else {
        null
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun kindLabel(kind: Int): String = getString(
        when (kind) {
            SourceEntity.KIND_WEBDAV -> R.string.source_kind_webdav
            SourceEntity.KIND_SMB -> R.string.source_kind_smb
            else -> R.string.book_mode_kind_local
        }
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_MODE = "mode"

        fun intent(context: Context, kind: Int): Intent =
            Intent(context, BookModeActivity::class.java).putExtra(EXTRA_KIND, kind)
    }
}
