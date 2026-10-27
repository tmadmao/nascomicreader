package com.nascomic.reader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.BuildConfig
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityMainBinding
import com.nascomic.reader.library.SourceRepository
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.scan.LibraryScanner
import com.nascomic.reader.ui.reader.ReadingActivity
import kotlinx.coroutines.launch

/**
 * 应用入口。当前阶段是开发验证页：加目录、扫描、点书名进阅读页。
 * 书架界面（封面网格、标签页、搜索）做好后替换这里。
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
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从阅读页回来时页数可能已经算出来了
        refresh()
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
                        source.label, result.found, result.added, result.updated
                    )
                )
                refresh()
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
            refresh()
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val all = sources.list()
            val books = db.bookDao().allSorted()
            binding.summary.text = getString(R.string.summary_counts, all.size, books.size)
            renderBooks(books)
        }
    }

    private fun renderBooks(books: List<BookEntity>) {
        binding.bookList.removeAllViews()
        val pad = (12 * resources.displayMetrics.density).toInt()
        for (book in books) {
            val row = TextView(this).apply {
                text = describe(book)
                setTextColor(getColor(R.color.shell_ink))
                textSize = 14f
                gravity = Gravity.START
                setPadding(0, pad, 0, pad)
                setOnClickListener { openBook(book) }
            }
            binding.bookList.addView(row)
        }
    }

    private fun describe(book: BookEntity): String {
        val kind = when (book.format) {
            BookEntity.FORMAT_ZIP -> getString(R.string.kind_archive)
            BookEntity.FORMAT_FOLDER ->
                if (book.isSingleVolume == 0) getString(R.string.kind_chaptered)
                else getString(R.string.kind_folder)
            else -> getString(R.string.kind_other)
        }
        val chapters = if (book.isSingleVolume == 0 && book.chapterTotal > 0) " · ${book.chapterTotal} ${getString(R.string.unit_chapter)}" else ""
        val pages = if (book.pageTotal > 0) " · ${book.pageTotal} ${getString(R.string.unit_page)}" else ""
        return "${book.title}\n$kind$chapters$pages"
    }

    private fun openBook(book: BookEntity) {
        startActivity(
            Intent(this, ReadingActivity::class.java)
                .putExtra(ReadingActivity.EXTRA_BOOK_ID, book.id)
        )
    }

    private fun status(text: String) {
        binding.status.text = text
    }
}
