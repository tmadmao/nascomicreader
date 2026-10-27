package com.nascomic.reader.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.BuildConfig
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityMainBinding
import com.nascomic.reader.library.SourceRepository
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.scan.LibraryScanner
import kotlinx.coroutines.launch

/**
 * 应用唯一入口。当前阶段是开发验证页：选一个目录、扫描、把结果打出来。
 * 书库界面接入后，这里改为承载浏览的壳。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val sources by lazy { SourceRepository(this, db) }
    private val scanner by lazy { LibraryScanner(this, db) }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) scanFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.version.text = getString(R.string.shell_version, BuildConfig.VERSION_NAME)

        binding.addFolder.setOnClickListener { pickFolder.launch(null) }
        binding.rescan.setOnClickListener { rescanAll() }
        refreshCount()
    }

    private fun scanFolder(uri: Uri) {
        lifecycleScope.launch {
            status(getString(R.string.status_scanning))
            try {
                val id = sources.addLocalFolder(uri, null)
                val source: SourceEntity? = db.sourceDao().get(id)
                if (source == null) {
                    status(getString(R.string.status_source_missing))
                    return@launch
                }
                val result = scanner.scan(source)
                status(
                    getString(
                        R.string.status_scan_done,
                        source.label,
                        result.found,
                        result.added,
                        result.updated
                    )
                )
                refreshCount()
            } catch (e: Exception) {
                status(getString(R.string.status_scan_failed, e.message ?: ""))
            }
        }
    }

    private fun rescanAll() {
        lifecycleScope.launch {
            val all = sources.list()
            if (all.isEmpty()) {
                status(getString(R.string.status_no_source))
                return@launch
            }
            var total = 0
            for (s in all) {
                status(getString(R.string.status_scanning_named, s.label))
                total += runCatching { scanner.scan(s).found }.getOrDefault(0)
            }
            status(getString(R.string.status_rescan_done, all.size, total))
            refreshCount()
        }
    }

    private fun refreshCount() {
        lifecycleScope.launch {
            val books = db.bookDao().count()
            val all: List<SourceEntity> = sources.list()
            binding.summary.text = getString(R.string.summary_counts, all.size, books)
        }
    }

    private fun status(text: String) {
        binding.status.text = text
    }
}
