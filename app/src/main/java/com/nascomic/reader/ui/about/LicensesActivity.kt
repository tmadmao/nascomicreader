package com.nascomic.reader.ui.about

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nascomic.reader.databinding.ActivityLicensesBinding
import com.nascomic.reader.ui.widget.padBySystemBars

/**
 * 原样显示仓库根的一份许可文本文件（NOTICE / LICENSE，构建时复制进 assets）。
 *
 * 这里刻意不排版、不改写：许可要求是「原文呈现」。
 */
class LicensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15 起强制边到边：自己让开状态栏与导航条
        binding.root.padBySystemBars()

        val asset = intent.getStringExtra(EXTRA_ASSET).orEmpty()
        binding.title.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.back.setOnClickListener { finish() }
        binding.body.text = readAsset(asset)
    }

    private fun readAsset(name: String): String = runCatching {
        assets.open(name).bufferedReader().use { it.readText() }
    }.getOrElse { "读不到 $name：${it.message}" }

    companion object {
        private const val EXTRA_ASSET = "asset"
        private const val EXTRA_TITLE = "title"

        fun intentOf(context: Context, title: String, asset: String): Intent =
            Intent(context, LicensesActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ASSET, asset)
    }
}
