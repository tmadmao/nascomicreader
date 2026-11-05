package com.nascomic.reader.ui.about

import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityAboutBinding
import com.nascomic.reader.update.UpdateCenter
import com.nascomic.reader.update.UpdateSources
import com.nascomic.reader.update.UpdateState
import com.nascomic.reader.ui.update.UpdateDialogs
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.launch

/**
 * 关于页：版本、更新、开源许可、源码、赞助。
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

        // 「检查更新」只在用户主动点时才请求；开屏那次在书架页，安静地做
        binding.rowUpdate.setOnClickListener { checkUpdate() }
        // 长按改清单地址：普通用户用不上，但平台整体失效时这是唯一还能自救的口
        binding.rowUpdate.setOnLongClickListener {
            editUpdateSource()
            true
        }
        watchUpdateState()

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

    private fun checkUpdate() {
        binding.updateStatus.text = getString(R.string.update_checking)
        UpdateCenter.check(this, manual = true)
    }

    /**
     * 改清单地址，留空即恢复内置源。
     *
     * 不做成设置项：九成九的用户永远不该看见它。但托管平台哪天整体不能用了，
     * 这条是唯一还能把更新通道接回来的路——而那时候恰好也没法靠发新版来修。
     */
    private fun editUpdateSource() {
        val input = EditText(this).apply {
            setText(com.nascomic.reader.settings.AppPrefs(this@AboutActivity).updateManifestUrl)
            hint = UpdateSources.GITEE
            setTextColor(getColor(R.color.shell_ink))
            setHintTextColor(getColor(R.color.shell_ink_faint))
            textSize = 13f
            setPadding(18, 14, 18, 14)
            minLines = 2
            gravity = Gravity.START or Gravity.TOP
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_update_source)
            .setMessage(R.string.settings_update_source_hint)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                com.nascomic.reader.settings.AppPrefs(this).updateManifestUrl =
                    input.text.toString()
                Toast.makeText(this, R.string.settings_update_source_saved, Toast.LENGTH_SHORT)
                    .show()
            }
            .show()
    }

    /**
     * 手动检查必须有回声：在查、已是最新、失败在哪一步，都写在这行小字里。
     * 自动检查不会走到这里——那种情况失败就该安静。
     */
    private fun watchUpdateState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateCenter.state.collect { state ->
                    when (state) {
                        UpdateState.Checking ->
                            binding.updateStatus.text = getString(R.string.update_checking)

                        UpdateState.UpToDate ->
                            binding.updateStatus.text = getString(R.string.update_latest)

                        is UpdateState.Available -> {
                            binding.updateStatus.text = getString(
                                R.string.update_title_available,
                                state.manifest.versionName,
                            )
                            UpdateDialogs.showAvailable(this@AboutActivity, state.manifest, state.forced) {
                                UpdateCenter.consume()
                            }
                        }

                        is UpdateState.Failed -> {
                            binding.updateStatus.text =
                                getString(R.string.update_failed, state.reason)
                            Toast.makeText(this@AboutActivity, state.reason, Toast.LENGTH_LONG).show()
                            UpdateCenter.consume()
                        }

                        // 下载与安装发生在书架页那边，这一页只管「查」
                        else -> Unit
                    }
                }
            }
        }
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
