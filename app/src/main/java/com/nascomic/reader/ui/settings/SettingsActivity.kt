package com.nascomic.reader.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nascomic.reader.databinding.ActivitySettingsBinding
import com.nascomic.reader.settings.AppPrefs

/** 设置。目前只有一个开关，随功能增加逐步补。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { AppPrefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        binding.skipDetail.isChecked = prefs.skipDetail
        binding.skipDetail.setOnCheckedChangeListener { _, checked ->
            prefs.skipDetail = checked
        }
    }
}
