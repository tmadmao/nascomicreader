package com.nascomic.reader.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityAboutBinding
import com.nascomic.reader.ui.widget.padBySystemBars

/**
 * 关于页：版本、开源许可、源码、赞助。
 *
 * 许可清单不在这里写：它来自仓库根的 `NOTICE`，构建时复制进 assets，
 * 由 [LicensesActivity] 原样显示。这样组件增减只改 `NOTICE` 一处。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15 起强制边到边：自己让开状态栏与导航条
        binding.root.padBySystemBars()

        binding.back.setOnClickListener { finish() }
        binding.version.text = versionText()

        binding.rowLicenses.setOnClickListener {
            startActivity(LicensesActivity.intentOf(this, getString(R.string.about_licenses), NOTICE))
        }
        binding.rowLicenseFull.setOnClickListener {
            startActivity(
                LicensesActivity.intentOf(this, getString(R.string.about_license_full), LICENSE)
            )
        }
        binding.rowSource.setOnClickListener { openUrl(SOURCE_URL) }
        binding.rowSponsor.setOnClickListener { openUrl(SPONSOR_URL) }
    }

    /** 版本号取包信息：改 `build.gradle.kts` 里的 versionName / versionCode 就够了 */
    private fun versionText(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        getString(R.string.about_version, info.versionName, code)
    }.getOrElse { getString(R.string.about_version_unknown) }

    private fun openUrl(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        runCatching { startActivity(intent) }
    }

    private companion object {
        const val NOTICE = "NOTICE"
        const val LICENSE = "LICENSE"
        const val SOURCE_URL = "https://github.com/tmadmao/nascomicreader"

        /** 收款码放在仓库 design/ 下，与 .github/FUNDING.yml 指向同一张图 */
        const val SPONSOR_URL =
            "https://raw.githubusercontent.com/tmadmao/nascomicreader/main/design/wechat-pay.png"
    }
}
