package com.nascomic.reader.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityMainBinding

/**
 * 应用唯一入口。P0 阶段只验证"能构建、能安装、能启动、能退出"，
 * 书库与阅读在 P1/P2 接入，届时这里改为承载 NavHost 的壳。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.version.text = getString(R.string.shell_version, BuildInfo.VERSION_NAME)
    }
}

/** 构建期写入的版本串，避免在界面里硬编码。 */
internal object BuildInfo {
    val VERSION_NAME: String = com.nascomic.reader.BuildConfig.VERSION_NAME
}
